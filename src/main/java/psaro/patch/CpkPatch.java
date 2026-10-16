package psaro.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.format.Cpk;
import psaro.format.FieldTable;
import psaro.format.MapScript;
import psaro.format.TextBlocks;
import psaro.format.Thss;
import psaro.patch.FontPatcher.Text;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Packed;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * Writes the English of the tables read from inside a CPK ({@link RomfsIndex#packed}) back into
 * it: the patch rebuilds the whole CPK into the patch folder ({@link Cpk#write}), since a game
 * patched through Luma3DS or an emulator's mods folder has whole files replaced, never part of
 * one. A CPK is hundreds of megabytes, so it is rebuilt by Patch, not on every save, and only when
 * what it would hold has changed since it was last written: {@code <romfs>.psaro/cpk.json}
 * remembers, per CPK, a digest of the files last written into it and the size of what was written.
 *
 * <p>A string the game's format cannot hold (a character Shift-JIS has not, a map script line
 * longer than a line can be) is left in Japanese and named among the change's problems.
 */
public final class CpkPatch {

	/**
	 * What the patch does to one CPK: {@code files} the files it writes into it (by path), none
	 * when its tables have no English; {@code problems} the strings left out, one line each;
	 * {@code hasWork} whether what was last written differs from that.
	 */
	public record Change(Path cpk, Map<String, byte[]> files, List<String> problems, boolean hasWork) {

		/** Whether the CPK is written with files of its own replaced. */
		public boolean changed() {
			return !files.isEmpty();
		}
	}

	private static final String MANIFEST = "cpk.json";

	private CpkPatch() {
	}

	/** What the patch would do to every CPK under the romfs for {@code texts}, against what is in {@code output}. */
	public static List<Change> plan(RomfsIndex index, List<Text> texts, Path output) {
		Map<Path, Map<StringTable, Map<String, String>>> byCpk = new LinkedHashMap<>();
		for (Path cpk : index.cpks()) {
			byCpk.put(cpk, new LinkedHashMap<>());
		}
		for (Text t : texts) {
			Packed p = index.packed(t.table());
			if (p != null) {
				byCpk.computeIfAbsent(p.cpk(), k -> new LinkedHashMap<>()).computeIfAbsent(t.table(), k -> new LinkedHashMap<>())
						.put(t.key(), t.text());
			}
		}
		JSONObject manifest = manifest(index.root());
		List<Change> out = new ArrayList<>();
		byCpk.forEach((cpk, tables) -> {
			List<String> problems = new ArrayList<>();
			Map<String, byte[]> files;
			try {
				files = files(index, cpk, tables, problems);
			} catch (IOException | RuntimeException e) {
				problems.add("Could not read " + cpk.getFileName() + ": " + e.getMessage());
				out.add(new Change(cpk, Map.of(), problems, false));
				return;
			}
			Path written = output.resolve(index.root().relativize(cpk).toString());
			out.add(new Change(cpk, files, List.copyOf(problems), hasWork(manifest, key(index, cpk), files, written)));
		});
		return out;
	}

	/**
	 * Carries out {@code change}: rebuilds its CPK into {@code output} with its files, or removes
	 * the copy there when it has none, and remembers what was written. Returns the CPK written, or
	 * null when it wrote none.
	 */
	public static Path write(RomfsIndex index, Change change, Path output, Consumer<String> progress) throws IOException {
		Path written = output.resolve(index.root().relativize(change.cpk()).toString());
		JSONObject manifest = manifest(index.root());
		String key = key(index, change.cpk());
		if (!change.changed()) {
			progress.accept("Removing " + index.root().relativize(change.cpk()));
			Files.deleteIfExists(written);
			if (manifest.has(key)) {
				manifest.remove(key);
				save(index.root(), manifest);
			}
			return null;
		}
		progress.accept("Rebuilding " + index.root().relativize(change.cpk()));
		try (Cpk cpk = Cpk.open(change.cpk())) {
			cpk.write(change.files(), written, progress);
		}
		manifest.put(key, new JSONObject().put("digest", digest(change.files())).put("size", Files.size(written)));
		save(index.root(), manifest);
		return written;
	}

	/** The files {@code tables}' English changes in {@code cpk}, by path; each string that cannot be written goes in {@code problems}. */
	private static Map<String, byte[]> files(RomfsIndex index, Path cpk, Map<StringTable, Map<String, String>> tables,
			List<String> problems) throws IOException {
		Map<String, byte[]> out = new TreeMap<>();
		if (tables.isEmpty()) {
			return out;
		}
		try (Cpk c = Cpk.open(cpk)) {
			for (Map.Entry<StringTable, Map<String, String>> t : tables.entrySet()) {
				Packed p = index.packed(t.getKey());
				Map<String, String> ok = new LinkedHashMap<>();
				t.getValue().forEach((key, text) -> {
					String problem = p.format() == RomfsIndex.PackedFormat.MAP_SCRIPTS ? MapScript.problem(text)
							: p.format() == RomfsIndex.PackedFormat.FIELD_TABLE ? FieldTable.problem(text)
							: p.format() == RomfsIndex.PackedFormat.TEXT_BLOCKS ? TextBlocks.problem(text) : Thss.problem(text);
					if (problem == null) {
						ok.put(key, text);
					} else {
						problems.add(t.getKey().name() + " " + key + ": " + problem);
					}
				});
				switch (p.format()) {
					case FIELD_TABLE -> put(out, c, p.path(), d -> FieldTable.write(d, ok));
					case TEXT_BLOCKS -> put(out, c, p.path(), d -> TextBlocks.write(d, ok));
					case DATABASE -> {
						Cpk.Entry pointers = c.find(p.path().substring(0, p.path().lastIndexOf('.')) + ".ofs");
						byte[] ofs = c.read(pointers);
						put(out, c, p.path(), d -> Thss.write(d, ofs, ok));
					}
					case MAP_SCRIPTS -> {
						// keyed <file>:<line>, each file of the folder written with its own lines
						Map<String, Map<String, String>> byFile = new TreeMap<>();
						ok.forEach((key, text) -> byFile.computeIfAbsent(key.substring(0, key.indexOf(':')), k -> new LinkedHashMap<>())
								.put(key.substring(key.indexOf(':') + 1), text));
						for (Map.Entry<String, Map<String, String>> f : byFile.entrySet()) {
							put(out, c, p.path() + "/" + f.getKey() + ".bin", d -> MapScript.write(d, f.getValue()));
						}
					}
				}
			}
		}
		return out;
	}

	private interface Writer {
		byte[] write(byte[] original);
	}

	/** Puts the file at {@code path} written by {@code writer} in {@code out}, unless it comes out as it was. */
	private static void put(Map<String, byte[]> out, Cpk c, String path, Writer writer) throws IOException {
		Cpk.Entry e = c.find(path);
		if (e == null) {
			throw new IOException(path + " is not in the CPK any more");
		}
		byte[] original = c.read(e);
		byte[] written = writer.write(original);
		if (!Arrays.equals(original, written)) {
			out.put(path, written);
		}
	}

	private static boolean hasWork(JSONObject manifest, String key, Map<String, byte[]> files, Path written) {
		boolean there = Files.isRegularFile(written);
		if (files.isEmpty()) {
			return there;
		}
		JSONObject last = manifest.optJSONObject(key);
		try {
			return !there || last == null || !digest(files).equals(last.optString("digest"))
					|| last.optLong("size", -1) != Files.size(written);
		} catch (IOException e) {
			return true;
		}
	}

	/** The CPK's path from the romfs, as the manifest names it. */
	private static String key(RomfsIndex index, Path cpk) {
		return index.root().relativize(cpk).toString().replace('\\', '/');
	}

	/** A digest of {@code files}: each one's path and bytes, in path order. */
	static String digest(Map<String, byte[]> files) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			for (Map.Entry<String, byte[]> f : new TreeMap<>(files).entrySet()) {
				sha.update(f.getKey().getBytes(StandardCharsets.UTF_8));
				sha.update((byte) 0);
				sha.update(Long.toString(f.getValue().length).getBytes(StandardCharsets.US_ASCII));
				sha.update((byte) 0);
				sha.update(f.getValue());
			}
			return HexFormat.of().formatHex(sha.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** What was last written, by CPK; empty when nothing was or it cannot be read, so the next patch writes again. */
	private static JSONObject manifest(Path romfs) {
		Path file = Translations.folderFor(romfs).resolve(MANIFEST);
		try {
			return Files.isRegularFile(file) ? new JSONObject(Files.readString(file, StandardCharsets.UTF_8)) : new JSONObject();
		} catch (IOException | JSONException e) {
			return new JSONObject();
		}
	}

	private static void save(Path romfs, JSONObject manifest) throws IOException {
		Path file = Translations.folderFor(romfs).resolve(MANIFEST);
		Files.createDirectories(file.getParent());
		Files.writeString(file, manifest.toString(2), StandardCharsets.UTF_8);
	}
}
