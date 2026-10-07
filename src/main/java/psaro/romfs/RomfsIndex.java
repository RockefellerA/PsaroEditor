package psaro.romfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import psaro.format.Archive;
import psaro.format.Bclyt;
import psaro.format.Bcfnt;
import psaro.format.Darc;
import psaro.format.Tdt;

/**
 * What an extracted romfs holds for translation: every string table, and for each string the
 * layout text panes that show it.
 *
 * <p>The tables ({@code text/<name>_Japanese.tdt}) are the unit of translation: some hold
 * strings no layout shows, which the game draws from code. A pane's key resolves to the table
 * named after its archive ({@code config.arc.lz} reads {@code config_Japanese.tdt}); a key that
 * table lacks resolves to every other table that has it, which is how archives sharing common
 * buttons pick up their labels. Keys are not unique across tables, so the archive's own table
 * always wins.
 *
 * <p>A pane's font is a {@code .bcfnt} inside the same archive, except for the 3DS system font,
 * which no romfs carries; {@link #font} returns null for those.
 */
public final class RomfsIndex {

	/** {@code text/<name>_Japanese.tdt}: its keys and Japanese text, in file order. */
	public record StringTable(String name, Path path, Map<String, String> strings) {
	}

	/** One text pane that shows a string: the archive, the layout's path inside it, and the pane. */
	public record Usage(Path archive, String layout, Bclyt.Pane pane) {

		public String fontName() {
			return pane.text().font();
		}
	}

	private static final String TABLE_SUFFIX = "_Japanese.tdt";
	private static final String ARCHIVE_SUFFIX = ".arc.lz";

	private final Path root;
	private final Map<String, StringTable> tables;
	/** Keyed by {@code table + "/" + key}. */
	private final Map<String, List<Usage>> usages;
	private final List<Usage> unresolved;
	private final int layoutCount;
	/** Keyed by archive path + "!" + font name; empty when the archive does not carry the font. */
	private final Map<String, Optional<Bcfnt>> fonts = new HashMap<>();

	private RomfsIndex(Path root, Map<String, StringTable> tables, Map<String, List<Usage>> usages,
			List<Usage> unresolved, int layoutCount) {
		this.root = root;
		this.tables = tables;
		this.usages = usages;
		this.unresolved = unresolved;
		this.layoutCount = layoutCount;
	}

	/** Whether {@code dir} has the string tables an index is built from. */
	public static boolean looksLikeRomfs(Path dir) throws IOException {
		return !tableFiles(dir).isEmpty();
	}

	/** Reads every string table and every layout under {@code root}. */
	public static RomfsIndex scan(Path root) throws IOException {
		Map<String, StringTable> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		Map<String, List<String>> tablesByKey = new HashMap<>();
		for (Path p : tableFiles(root)) {
			String name = strip(p, TABLE_SUFFIX);
			Map<String, String> strings = Collections.unmodifiableMap(Tdt.read(Files.readAllBytes(p)));
			tables.put(name, new StringTable(name, p, strings));
			for (String key : strings.keySet()) {
				tablesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
			}
		}

		Map<String, List<Usage>> usages = new HashMap<>();
		List<Usage> unresolved = new ArrayList<>();
		int layouts = 0;
		for (Path archive : files(root, ARCHIVE_SUFFIX)) {
			StringTable own = tables.get(strip(archive, ARCHIVE_SUFFIX));
			Darc.Node darc;
			try {
				darc = Archive.load(archive);
			} catch (IllegalArgumentException notDarc) {
				continue;
			}
			for (Map.Entry<String, Darc.Node> file : Darc.files(darc).entrySet()) {
				if (!file.getKey().endsWith(".bclyt")) {
					continue;
				}
				layouts++;
				for (Bclyt.Pane pane : Bclyt.read(file.getValue().data).textPanes()) {
					Usage usage = new Usage(archive, file.getKey(), pane);
					for (String key : pane.keys()) {
						List<String> homes = own != null && own.strings().containsKey(key)
								? List.of(own.name())
								: tablesByKey.getOrDefault(key, List.of());
						if (homes.isEmpty()) {
							unresolved.add(usage);
						}
						for (String home : homes) {
							usages.computeIfAbsent(home + "/" + key, k -> new ArrayList<>()).add(usage);
						}
					}
				}
			}
		}
		return new RomfsIndex(root, Collections.unmodifiableMap(tables), usages, List.copyOf(unresolved), layouts);
	}

	public Path root() {
		return root;
	}

	/** Every string table, by name. */
	public List<StringTable> tables() {
		return List.copyOf(tables.values());
	}

	public StringTable table(String name) {
		return tables.get(name);
	}

	/** The text panes that show {@code key} from {@code table}; empty for strings drawn from code. */
	public List<Usage> usages(StringTable table, String key) {
		return Collections.unmodifiableList(usages.getOrDefault(table.name() + "/" + key, List.of()));
	}

	/** Text panes whose key no string table holds. */
	public List<Usage> unresolved() {
		return unresolved;
	}

	public int layoutCount() {
		return layoutCount;
	}

	public int stringCount() {
		return tables.values().stream().mapToInt(t -> t.strings().size()).sum();
	}

	/**
	 * The font {@code usage}'s pane draws with, or null when its archive does not carry it (the
	 * system font). Loads every font of that archive on first use.
	 */
	public synchronized Bcfnt font(Usage usage) throws IOException {
		String id = usage.archive() + "!" + usage.fontName();
		if (!fonts.containsKey(id)) {
			Map<String, Bcfnt> found = new LinkedHashMap<>();
			for (Map.Entry<String, Darc.Node> file : Darc.files(Archive.load(usage.archive())).entrySet()) {
				String path = file.getKey();
				if (path.endsWith(".bcfnt")) {
					found.put(path.substring(path.lastIndexOf('/') + 1), Bcfnt.parse(file.getValue().data));
				}
			}
			for (Map.Entry<String, Bcfnt> f : found.entrySet()) {
				fonts.put(usage.archive() + "!" + f.getKey(), Optional.of(f.getValue()));
			}
			fonts.putIfAbsent(id, Optional.empty());
		}
		return fonts.get(id).orElse(null);
	}

	private static List<Path> tableFiles(Path root) throws IOException {
		Path text = root.resolve("text");
		if (!Files.isDirectory(text)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(text)) {
			return files.filter(p -> p.getFileName().toString().endsWith(TABLE_SUFFIX)).sorted().toList();
		}
	}

	private static List<Path> files(Path root, String suffix) throws IOException {
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
		}
	}

	private static String strip(Path p, String suffix) {
		String name = p.getFileName().toString();
		return name.substring(0, name.length() - suffix.length());
	}
}
