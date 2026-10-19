package psaro.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>A file holds only translated strings, in the table's own order so diffs stay readable. A
 * string that needs no translation (a name, a number, "？？？") is marked to keep its Japanese,
 * stored as {@code true} rather than a copy of the text, which is how it differs from a string not
 * translated yet. Keys a file has but its table lacks are kept as they are. Nothing is written
 * until {@link #save}, and a table left with no translations has its file removed. The user's
 * notes to themselves are saved the same way, one file per table in {@code notes/}. These files
 * are the record of the work; the game's own tables are built from them
 * ({@link psaro.patch.TextExport}).
 */
public final class Translations {

	private static final String FOLDER_SUFFIX = ".psaro";
	private static final String ENGLISH = "english";
	private static final String NOTES = "notes";
	/** The folder in a game's project that holds the project of the DLC attached to it. */
	public static final String DLC = "DLC";
	/** A DLC's folder (of content romfs folders) to the game's romfs it is attached to. */
	private static final Map<Path, Path> DLC_GAMES = new java.util.concurrent.ConcurrentHashMap<>();

	private final RomfsIndex index;
	private final Path folder;
	/** Table name to key to English; only translated strings. */
	private final Map<String, Map<String, String>> english = new HashMap<>();
	/** Table name to the keys that keep their Japanese. A key is in this or in {@link #english}, not both. */
	private final Map<String, Set<String>> keepJapanese = new HashMap<>();
	private final Set<String> dirty = new TreeSet<>();
	/** Table name to key to the user's own note on that string; kept beside the English, never part of it. */
	private final Map<String, Map<String, String>> notes = new HashMap<>();
	private final Set<String> dirtyNotes = new TreeSet<>();

	/** One table's file: its English and the keys marked to keep their Japanese. */
	private record Saved(Map<String, String> english, Set<String> keepJapanese) {
	}

	private Translations(RomfsIndex index, Path folder) {
		this.index = index;
		this.folder = folder;
	}

	/**
	 * {@code <romfs>.psaro} beside the romfs folder; for a DLC attached to a game ({@link #attachDlc}),
	 * the game's project's {@link #DLC} folder.
	 */
	public static Path folderFor(Path romfs) {
		Path abs = romfs.toAbsolutePath().normalize();
		Path game = DLC_GAMES.get(abs);
		if (game != null) {
			return folderFor(game).resolve(DLC);
		}
		Path parent = abs.getParent();
		if (parent == null || abs.getFileName() == null) {
			// a drive root has no folder beside it
			return Path.of(System.getProperty("user.home"), "romfs" + FOLDER_SUFFIX);
		}
		return parent.resolve(abs.getFileName() + FOLDER_SUFFIX);
	}

	/**
	 * Keeps the project of the DLC whose content romfs folders are in {@code dlc} inside the
	 * project of the game whose romfs is {@code game}, in its {@link #DLC} folder, rather than
	 * beside the DLC: every file {@link #folderFor} places for {@code dlc} goes there from now on.
	 */
	public static void attachDlc(Path dlc, Path game) {
		DLC_GAMES.put(dlc.toAbsolutePath().normalize(), game.toAbsolutePath().normalize());
	}

	/** Forgets that {@code dlc} is attached to a game, so its project is beside it again. */
	public static void detachDlc(Path dlc) {
		DLC_GAMES.remove(dlc.toAbsolutePath().normalize());
	}

	/** The game's romfs {@code romfs} is attached to as its DLC, or null when it is not a DLC. */
	public static Path gameOf(Path romfs) {
		return DLC_GAMES.get(romfs.toAbsolutePath().normalize());
	}

	/** Loads whatever has been saved for {@code index}'s romfs; nothing yet is not an error. */
	public static Translations open(RomfsIndex index) throws IOException {
		Translations t = new Translations(index, folderFor(index.root()));
		for (StringTable table : index.tables()) {
			Path file = t.file(table);
			if (Files.isRegularFile(file)) {
				Saved saved = read(file);
				t.english.put(table.name(), saved.english());
				t.keepJapanese.put(table.name(), saved.keepJapanese());
			}
			Path notes = t.notesFile(table);
			if (Files.isRegularFile(notes)) {
				t.notes.put(table.name(), readNotes(notes));
			}
		}
		return t;
	}

	/** The project folder, which may not exist yet. */
	public Path folder() {
		return folder;
	}

	/**
	 * The text the game should show for {@code key}: its English, its Japanese when it is marked
	 * to keep that, or null if it has neither yet.
	 */
	public String get(StringTable table, String key) {
		if (keepsJapanese(table, key)) {
			return table.strings().get(key);
		}
		return english.getOrDefault(table.name(), Map.of()).get(key);
	}

	/** Sets the English for {@code key}; null or empty removes it. English replaces a keep-Japanese mark. */
	public void set(StringTable table, String key, String text) {
		String value = text == null || text.isEmpty() ? null : text;
		Map<String, String> strings = english.computeIfAbsent(table.name(), n -> new HashMap<>());
		boolean unmarked = value != null && keepJapanese.getOrDefault(table.name(), Set.of()).contains(key);
		if (unmarked) {
			keepJapanese.get(table.name()).remove(key);
		}
		if (!unmarked && Objects.equals(strings.get(key), value)) {
			return;
		}
		if (value == null) {
			strings.remove(key);
		} else {
			strings.put(key, value);
		}
		dirty.add(table.name());
	}

	/** Whether {@code key} is marked to keep its Japanese, needing no translation. */
	public boolean keepsJapanese(StringTable table, String key) {
		return keepJapanese.getOrDefault(table.name(), Set.of()).contains(key);
	}

	/** Marks {@code key} to keep its Japanese, dropping any English it had, or clears the mark. */
	public void setKeepsJapanese(StringTable table, String key, boolean keep) {
		Set<String> keys = keepJapanese.computeIfAbsent(table.name(), n -> new HashSet<>());
		boolean changed = keep ? keys.add(key) : keys.remove(key);
		if (keep) {
			changed |= english.getOrDefault(table.name(), new HashMap<>()).remove(key) != null;
		}
		if (changed) {
			dirty.add(table.name());
		}
	}

	/** How many of {@code table}'s strings are done: given English, or marked to keep their Japanese. */
	public int translatedCount(StringTable table) {
		Map<String, String> strings = english.getOrDefault(table.name(), Map.of());
		Set<String> kept = keepJapanese.getOrDefault(table.name(), Set.of());
		return (int) table.strings().keySet().stream().filter(k -> strings.containsKey(k) || kept.contains(k)).count();
	}

	public int translatedCount() {
		return index.tables().stream().mapToInt(this::translatedCount).sum();
	}

	/** How many strings in {@code tables} are done. */
	public int translatedCountIn(Collection<StringTable> tables) {
		return tables.stream().mapToInt(this::translatedCount).sum();
	}

	/** The note left on {@code key}, or null if it has none. */
	public String note(StringTable table, String key) {
		return notes.getOrDefault(table.name(), Map.of()).get(key);
	}

	/** Sets the note on {@code key}; null or blank removes it. */
	public void setNote(StringTable table, String key, String text) {
		String value = text == null || text.isBlank() ? null : text;
		Map<String, String> map = notes.computeIfAbsent(table.name(), n -> new HashMap<>());
		if (Objects.equals(map.get(key), value)) {
			return;
		}
		if (value == null) {
			map.remove(key);
		} else {
			map.put(key, value);
		}
		dirtyNotes.add(table.name());
	}

	public boolean isDirty() {
		return !dirty.isEmpty() || !dirtyNotes.isEmpty();
	}

	/** Names of the tables changed since the last save. */
	public Set<String> dirtyTables() {
		Set<String> all = new TreeSet<>(dirty);
		all.addAll(dirtyNotes);
		return Collections.unmodifiableSet(all);
	}

	/** Writes every changed table. */
	public void save() throws IOException {
		for (String name : Set.copyOf(dirty)) {
			StringTable table = index.table(name);
			Map<String, String> strings = english.getOrDefault(name, Map.of());
			Set<String> kept = keepJapanese.getOrDefault(name, Set.of());
			Path file = file(table);
			if (strings.isEmpty() && kept.isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				Files.createDirectories(file.getParent());
				write(file, json(table, strings, kept));
			}
			dirty.remove(name);
		}
		for (String name : Set.copyOf(dirtyNotes)) {
			StringTable table = index.table(name);
			Map<String, String> map = notes.getOrDefault(name, Map.of());
			Path file = notesFile(table);
			if (map.isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				Files.createDirectories(file.getParent());
				write(file, json(table, map, Set.of()));
			}
			dirtyNotes.remove(name);
		}
	}

	private Path notesFile(StringTable table) {
		return folder.resolve(NOTES).resolve(table.name() + ".json");
	}

	private static Map<String, String> readNotes(Path file) throws IOException {
		JSONObject json;
		try {
			json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
		} catch (JSONException e) {
			throw new IOException(file + " is not valid JSON: " + e.getMessage(), e);
		}
		Map<String, String> out = new HashMap<>();
		for (String key : json.keySet()) {
			if (json.get(key) instanceof String s && !s.isBlank()) {
				out.put(key, s);
			}
		}
		return out;
	}

	private Path file(StringTable table) {
		return folder.resolve(ENGLISH).resolve(table.name() + ".json");
	}

	/**
	 * One key per line: the table's keys in table order, then any it no longer has, sorted. A key
	 * that keeps its Japanese is written as {@code true}.
	 */
	static String json(StringTable table, Map<String, String> strings, Set<String> kept) {
		Map<String, String> values = new HashMap<>();
		strings.forEach((k, v) -> values.put(k, JSONObject.quote(v)));
		kept.forEach(k -> values.put(k, "true"));
		Map<String, String> ordered = new LinkedHashMap<>();
		for (String key : table.strings().keySet()) {
			if (values.containsKey(key)) {
				ordered.put(key, values.get(key));
			}
		}
		new TreeSet<>(values.keySet()).forEach(k -> ordered.putIfAbsent(k, values.get(k)));

		StringBuilder out = new StringBuilder("{\n");
		int i = 0;
		for (Map.Entry<String, String> e : ordered.entrySet()) {
			out.append("  ").append(JSONObject.quote(e.getKey())).append(": ").append(e.getValue());
			out.append(++i < ordered.size() ? ",\n" : "\n");
		}
		return out.append("}\n").toString();
	}

	private static Saved read(Path file) throws IOException {
		JSONObject json;
		try {
			json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
		} catch (JSONException e) {
			throw new IOException(file + " is not valid JSON: " + e.getMessage(), e);
		}
		Map<String, String> out = new HashMap<>();
		Set<String> kept = new HashSet<>();
		for (String key : json.keySet()) {
			Object value = json.get(key);
			if (Boolean.TRUE.equals(value)) {
				kept.add(key);
			} else if (value instanceof String s) {
				if (!s.isEmpty()) {
					out.put(key, s);
				}
			} else {
				throw new IOException(file + ": the value for " + key + " is neither text nor true");
			}
		}
		return new Saved(out, kept);
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
