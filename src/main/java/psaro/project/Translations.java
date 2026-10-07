package psaro.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * The English text for a romfs, kept beside it and never inside it:
 * {@code <romfs>.psaro/english/<table>.json}, one JSON object per string table mapping keys to
 * English.
 *
 * <p>A file holds only translated strings, in the table's own order so diffs stay readable, and
 * no Japanese: the folder can be shared or put in git without carrying any game text. Keys a
 * file has but its table lacks are kept as they are. Nothing is written until {@link #save}, and
 * a table left with no translations has its file removed.
 */
public final class Translations {

	private static final String FOLDER_SUFFIX = ".psaro";
	private static final String ENGLISH = "english";

	private final RomfsIndex index;
	private final Path folder;
	/** Table name to key to English; only translated strings. */
	private final Map<String, Map<String, String>> english = new HashMap<>();
	private final Set<String> dirty = new TreeSet<>();

	private Translations(RomfsIndex index, Path folder) {
		this.index = index;
		this.folder = folder;
	}

	/** {@code <romfs>.psaro} beside the romfs folder. */
	public static Path folderFor(Path romfs) {
		Path abs = romfs.toAbsolutePath().normalize();
		Path parent = abs.getParent();
		if (parent == null || abs.getFileName() == null) {
			// a drive root has no folder beside it
			return Path.of(System.getProperty("user.home"), "romfs" + FOLDER_SUFFIX);
		}
		return parent.resolve(abs.getFileName() + FOLDER_SUFFIX);
	}

	/** Loads whatever has been saved for {@code index}'s romfs; nothing yet is not an error. */
	public static Translations open(RomfsIndex index) throws IOException {
		Translations t = new Translations(index, folderFor(index.root()));
		for (StringTable table : index.tables()) {
			Path file = t.file(table);
			if (Files.isRegularFile(file)) {
				t.english.put(table.name(), read(file));
			}
		}
		return t;
	}

	/** The project folder, which may not exist yet. */
	public Path folder() {
		return folder;
	}

	/** The English for {@code key}, or null if it has none. */
	public String get(StringTable table, String key) {
		return english.getOrDefault(table.name(), Map.of()).get(key);
	}

	/** Sets the English for {@code key}; null or empty removes it. */
	public void set(StringTable table, String key, String text) {
		String value = text == null || text.isEmpty() ? null : text;
		Map<String, String> strings = english.computeIfAbsent(table.name(), n -> new HashMap<>());
		if (Objects.equals(strings.get(key), value)) {
			return;
		}
		if (value == null) {
			strings.remove(key);
		} else {
			strings.put(key, value);
		}
		dirty.add(table.name());
	}

	/** How many of {@code table}'s strings have English. */
	public int translatedCount(StringTable table) {
		Map<String, String> strings = english.getOrDefault(table.name(), Map.of());
		return (int) table.strings().keySet().stream().filter(strings::containsKey).count();
	}

	public int translatedCount() {
		return index.tables().stream().mapToInt(this::translatedCount).sum();
	}

	public boolean isDirty() {
		return !dirty.isEmpty();
	}

	/** Names of the tables changed since the last save. */
	public Set<String> dirtyTables() {
		return Collections.unmodifiableSet(dirty);
	}

	/** Writes every changed table. */
	public void save() throws IOException {
		for (String name : Set.copyOf(dirty)) {
			StringTable table = index.table(name);
			Map<String, String> strings = english.getOrDefault(name, Map.of());
			Path file = file(table);
			if (strings.isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				Files.createDirectories(file.getParent());
				write(file, json(table, strings));
			}
			dirty.remove(name);
		}
	}

	private Path file(StringTable table) {
		return folder.resolve(ENGLISH).resolve(table.name() + ".json");
	}

	/** One key per line: the table's keys in table order, then any it no longer has, sorted. */
	static String json(StringTable table, Map<String, String> strings) {
		Map<String, String> ordered = new LinkedHashMap<>();
		for (String key : table.strings().keySet()) {
			if (strings.containsKey(key)) {
				ordered.put(key, strings.get(key));
			}
		}
		new TreeSet<>(strings.keySet()).forEach(k -> ordered.putIfAbsent(k, strings.get(k)));

		StringBuilder out = new StringBuilder("{\n");
		int i = 0;
		for (Map.Entry<String, String> e : ordered.entrySet()) {
			out.append("  ").append(JSONObject.quote(e.getKey())).append(": ").append(JSONObject.quote(e.getValue()));
			out.append(++i < ordered.size() ? ",\n" : "\n");
		}
		return out.append("}\n").toString();
	}

	private static Map<String, String> read(Path file) throws IOException {
		JSONObject json;
		try {
			json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
		} catch (JSONException e) {
			throw new IOException(file + " is not valid JSON: " + e.getMessage(), e);
		}
		Map<String, String> out = new HashMap<>();
		for (String key : json.keySet()) {
			Object value = json.get(key);
			if (!(value instanceof String s)) {
				throw new IOException(file + ": the value for " + key + " is not text");
			}
			if (!s.isEmpty()) {
				out.put(key, s);
			}
		}
		return out;
	}

	/** Writes beside the target, then moves it into place, so a crash cannot leave half a file. */
	private static void write(Path file, String text) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
