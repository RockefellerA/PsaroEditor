package psaro.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.format.Comp;
import psaro.format.Mbb;
import psaro.format.Nbf;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * Writes Lost Heroes' {@code .cmp} bundles ({@link Nbf}) with the English of the message tables
 * they carry copies of ({@link RomfsIndex#bundles}) into the patch folder, beside the loose
 * {@code .mbb} files {@link TextExport} writes, as a scene may read its text from its bundle; and
 * removes the copy of a bundle whose tables have no English any more. A bundle is unpacked and
 * packed again only when its tables' English has changed since it was last written:
 * {@code <romfs>.psaro/bundles.json} remembers, per bundle, a digest of that English and the size
 * of what was written.
 */
final class BundleExport {

	private static final String MANIFEST = "bundles.json";

	private BundleExport() {
	}

	/**
	 * Brings every bundle's copy in {@code output} up to date with {@code english}, the English of
	 * each message table written, by key. Returns the bundles written.
	 */
	static List<Path> export(RomfsIndex index, Map<StringTable, Map<String, String>> english, Path output)
			throws IOException {
		List<Path> written = new ArrayList<>();
		if (index.bundles().isEmpty()) {
			return written;
		}
		JSONObject manifest = manifest(index.root());
		boolean changed = false;
		for (Map.Entry<Path, List<StringTable>> b : index.bundles().entrySet()) {
			Path bundle = b.getKey();
			String key = index.root().relativize(bundle).toString().replace('\\', '/');
			Path out = output.resolve(index.root().relativize(bundle).toString());
			Map<StringTable, Map<String, String>> tables = new LinkedHashMap<>();
			b.getValue().stream().filter(english::containsKey).forEach(t -> tables.put(t, english.get(t)));
			if (tables.isEmpty()) {
				Files.deleteIfExists(out);
				changed |= manifest.remove(key) != null;
				continue;
			}
			String digest = digest(tables);
			JSONObject last = manifest.optJSONObject(key);
			if (Files.isRegularFile(out) && last != null && digest.equals(last.optString("digest"))
					&& last.optLong("size", -1) == Files.size(out)) {
				continue;
			}
			byte[] packed = Comp.compress(Nbf.write(withEnglish(Nbf.read(Comp.decompress(Files.readAllBytes(bundle))), tables)));
			Files.createDirectories(out.getParent());
			write(out, packed);
			manifest.put(key, new JSONObject().put("digest", digest).put("size", packed.length));
			changed = true;
			written.add(out);
		}
		if (changed) {
			save(index.root(), manifest);
		}
		return written;
	}

	/** {@code members} with each copy of a table in {@code tables} holding its English; one that cannot is kept as it was. */
	private static List<Nbf.Member> withEnglish(List<Nbf.Member> members, Map<StringTable, Map<String, String>> tables) {
		List<Nbf.Member> out = new ArrayList<>(members.size());
		for (Nbf.Member m : members) {
			Nbf.Member kept = m;
			for (Map.Entry<StringTable, Map<String, String>> t : tables.entrySet()) {
				if (t.getKey().path().endsWith(m.path())) {
					try {
						kept = new Nbf.Member(m.name(), Mbb.write(m.data(), t.getValue(), RomfsIndex.glyphs()));
					} catch (IllegalArgumentException cannot) {
						// TextExport has named the string its loose copy could not hold
					}
					break;
				}
			}
			out.add(kept);
		}
		return out;
	}

	/** A digest of each table's English: its name, then each key and text, in name order. */
	private static String digest(Map<StringTable, Map<String, String>> tables) {
		Map<String, byte[]> files = new TreeMap<>();
		tables.forEach((t, strings) -> {
			StringBuilder s = new StringBuilder();
			strings.forEach((k, v) -> s.append(k).append('\0').append(v).append('\0'));
			files.put(t.name(), s.toString().getBytes(StandardCharsets.UTF_8));
		});
		return CpkPatch.digest(files);
	}

	/** Writes beside the target, then moves it into place, so a crash cannot leave half a file. */
	private static void write(Path file, byte[] bytes) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** What was last written, by bundle; empty when nothing was or it cannot be read, so the next save writes again. */
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
