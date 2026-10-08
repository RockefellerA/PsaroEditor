package psaro.romfs;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * which no romfs carries; {@link #font} returns null for those. Each archive's font is a subset
 * holding only the characters its Japanese needs, so {@link #donors} finds the other copies and
 * sizes of a font that do hold English letters.
 *
 * <p>Archives are found anywhere under the root except inside another romfs: a folder named
 * {@code romfs} or one with string tables of its own. A working folder often keeps rebuilt or
 * test copies of the romfs beside the original, and their layouts and fonts are not the game's.
 */
public final class RomfsIndex {

	/** {@code text/<name>_Japanese.tdt}: its keys and Japanese text, in file order. */
	public record StringTable(String name, Path path, Map<String, String> strings) {
	}

	/**
	 * One text pane that shows a string: the archive, the layout's path inside it, the pane, and
	 * the fonts the layout lists, which the pane can be switched among.
	 */
	public record Usage(Path archive, String layout, Bclyt.Pane pane, List<String> layoutFonts) {

		/** The layout's own font for the pane, before any change. */
		public String fontName() {
			return pane.text().font();
		}
	}

	/** A pane and the Japanese it shows. */
	public record Shown(Usage usage, String japanese) {
	}

	/** What decides how much room text takes in a pane, whatever its position or colour. */
	private record Shape(String font, float boxWidth, float boxHeight, float fontSizeX, float fontSizeY,
			float charSpace, float lineSpace) {

		static Shape of(Bclyt.TextInfo t) {
			return new Shape(t.font(), t.boxWidth(), t.boxHeight(), t.fontSizeX(), t.fontSizeY(), t.charSpace(),
					t.lineSpace());
		}
	}

	private static final String TABLE_SUFFIX = "_Japanese.tdt";
	private static final String ARCHIVE_SUFFIX = ".arc.lz";
	/**
	 * {@code SulaPro_B_04a_22_C.bcfnt}: family, weight (some fonts have none), style (the look
	 * baked into the pixels: {@code 04a} is white with a black outline), size, then any variant.
	 */
	private static final Pattern FONT_NAME =
			Pattern.compile("([^_]+)_(?:([A-Z]+)_)?(\\d+[a-z])_(\\d+)(?:_\\w+)?\\.bcfnt");

	/** Font weights from lightest to heaviest, as the names spell them. */
	private static final List<String> WEIGHTS = List.of("EL", "L", "R", "M", "DB", "B", "EB", "H", "U");

	/** A font that can lend glyphs: the archive it is in, its file name, and the font. */
	public record Donor(Path archive, String name, Bcfnt font) {
	}

	private final Path root;
	private final Map<String, StringTable> tables;
	/** Keyed by {@code table + "/" + key}. */
	private final Map<String, List<Usage>> usages;
	private final List<Usage> unresolved;
	private final int layoutCount;
	/** Font file name to the archives that carry a copy of it. */
	private final Map<String, List<Path>> fontHomes;
	/** Layout path inside an archive to the archives that carry a copy of it. */
	private final Map<String, List<Path>> layoutHomes;
	private final Map<Shape, List<Shown>> shapes;
	/** Keyed by archive path + "!" + font name; empty when the archive does not carry the font. */
	private final Map<String, Optional<Bcfnt>> fonts = new HashMap<>();
	/** Keyed by font name. */
	private final Map<String, List<Donor>> donors = new HashMap<>();

	private RomfsIndex(Path root, Map<String, StringTable> tables, Map<String, List<Usage>> usages,
			List<Usage> unresolved, int layoutCount, Map<String, List<Path>> fontHomes,
			Map<String, List<Path>> layoutHomes, Map<Shape, List<Shown>> shapes) {
		this.root = root;
		this.tables = tables;
		this.usages = usages;
		this.unresolved = unresolved;
		this.layoutCount = layoutCount;
		this.fontHomes = fontHomes;
		this.layoutHomes = layoutHomes;
		this.shapes = shapes;
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
		Map<String, List<Path>> fontHomes = new HashMap<>();
		Map<String, List<Path>> layoutHomes = new HashMap<>();
		Map<Shape, List<Shown>> shapes = new HashMap<>();
		int layouts = 0;
		for (Path archive : archives(root)) {
			StringTable own = tables.get(strip(archive, ARCHIVE_SUFFIX));
			Darc.Node darc;
			try {
				darc = Archive.load(archive);
			} catch (IllegalArgumentException notDarc) {
				continue;
			}
			for (Map.Entry<String, Darc.Node> file : Darc.files(darc).entrySet()) {
				if (file.getKey().endsWith(".bcfnt")) {
					fontHomes.computeIfAbsent(fileName(file.getKey()), k -> new ArrayList<>()).add(archive);
				}
				if (!file.getKey().endsWith(".bclyt")) {
					continue;
				}
				layouts++;
				layoutHomes.computeIfAbsent(file.getKey(), k -> new ArrayList<>()).add(archive);
				Bclyt.Layout layout = Bclyt.read(file.getValue().data);
				List<String> layoutFonts = List.copyOf(layout.fonts());
				for (Bclyt.Pane pane : layout.textPanes()) {
					Usage usage = new Usage(archive, file.getKey(), pane, layoutFonts);
					for (String key : pane.keys()) {
						List<String> homes = own != null && own.strings().containsKey(key)
								? List.of(own.name())
								: tablesByKey.getOrDefault(key, List.of());
						if (homes.isEmpty()) {
							unresolved.add(usage);
						}
						for (String home : homes) {
							usages.computeIfAbsent(home + "/" + key, k -> new ArrayList<>()).add(usage);
							shapes.computeIfAbsent(Shape.of(pane.text()), k -> new ArrayList<>())
									.add(new Shown(usage, tables.get(home).strings().get(key)));
						}
					}
				}
			}
		}
		shapes.replaceAll((shape, shown) -> List.copyOf(shown));
		return new RomfsIndex(root, Collections.unmodifiableMap(tables), usages, List.copyOf(unresolved), layouts,
				fontHomes, layoutHomes, shapes);
	}

	public Path root() {
		return root;
	}

	/** Every archive that carries a copy of font {@code font} (its file name). */
	public List<Path> archivesWithFont(String font) {
		return List.copyOf(fontHomes.getOrDefault(font, List.of()));
	}

	/** Every archive that carries layout {@code layout} (its path inside the archive). */
	public List<Path> archivesWithLayout(String layout) {
		return List.copyOf(layoutHomes.getOrDefault(layout, List.of()));
	}

	/**
	 * Every pane, in any archive, with the same box, font, size and spacing as {@code usage}'s,
	 * with the Japanese each shows: room the game already gives text in one of them is there in
	 * all of them. The same list each time for panes of one shape, so it can key a cache.
	 */
	public List<Shown> sameShape(Usage usage) {
		return shapes.getOrDefault(Shape.of(usage.pane().text()), List.of());
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

	/**
	 * When no pane shows {@code key} from {@code table}, the first other table holding the same
	 * key whose copy some pane does show; null otherwise. A layout's key is matched to its own
	 * archive's table first, so a key two tables share ({@code cftp_1000} in config and
	 * config_select) is matched to only one, though the game may read either.
	 */
	public StringTable sharedWith(StringTable table, String key) {
		if (!usages(table, key).isEmpty()) {
			return null;
		}
		for (StringTable other : tables.values()) {
			if (other != table && other.strings().containsKey(key) && !usages(other, key).isEmpty()) {
				return other;
			}
		}
		return null;
	}

	/**
	 * The panes that show {@code key} from {@code table}, or, when none do, those that show the
	 * same key from the table {@link #sharedWith} names.
	 */
	public List<Usage> panes(StringTable table, String key) {
		StringTable other = sharedWith(table, key);
		return usages(other != null ? other : table, key);
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
	 * The layout's own font for {@code usage}'s pane, or null when its archive does not carry it
	 * (the system font). Loads every font of that archive on first use.
	 */
	public Bcfnt font(Usage usage) throws IOException {
		return font(usage.archive(), usage.fontName());
	}

	/**
	 * The font {@code name} in {@code archive}, or null when the archive does not carry it. Loads
	 * every font of that archive on first use. The font is shared: copy it before changing it.
	 */
	public synchronized Bcfnt font(Path archive, String name) throws IOException {
		String id = archive + "!" + name;
		if (!fonts.containsKey(id)) {
			Map<String, Bcfnt> found = new LinkedHashMap<>();
			for (Map.Entry<String, Darc.Node> file : Darc.files(Archive.load(archive)).entrySet()) {
				String path = file.getKey();
				if (path.endsWith(".bcfnt")) {
					found.put(fileName(path), Bcfnt.parse(file.getValue().data));
				}
			}
			for (Map.Entry<String, Bcfnt> f : found.entrySet()) {
				fonts.put(archive + "!" + f.getKey(), Optional.of(f.getValue()));
			}
			fonts.putIfAbsent(id, Optional.empty());
		}
		return fonts.get(id).orElse(null);
	}

	/**
	 * The fonts that can lend {@code fontName} the English it lacks, nearest first: its copies in
	 * other archives; the same family, weight and style at other sizes; the same family and style
	 * in another weight, nearest weight first; the same family in another style; then another
	 * family in the same style, and last another family in another style. Closer sizes come
	 * first. Only copies holding English letters are kept. Loaded on first use, which reads every
	 * archive that carries one.
	 *
	 * <p>The style is baked into the pixels (an outline, a shade), so another style's glyphs do
	 * not quite match, and another family's letters are another typeface; they are there for the
	 * fonts whose own family has no whole alphabet anywhere in the romfs (TBMarugothic has no
	 * lowercase at all), where they beat the blank the game would show.
	 */
	public synchronized List<Donor> donors(String fontName) {
		List<Donor> cached = donors.get(fontName);
		if (cached != null) {
			return cached;
		}
		Matcher own = FONT_NAME.matcher(fontName);
		Map<String, Integer> rank = new HashMap<>();
		rank.put(fontName, 0);
		if (own.matches()) {
			int size = Integer.parseInt(own.group(4));
			for (String name : fontHomes.keySet()) {
				Matcher m = FONT_NAME.matcher(name);
				if (name.equals(fontName) || !m.matches()) {
					continue;
				}
				int sizes = Math.abs(Integer.parseInt(m.group(4)) - size);
				int weights = 100 * weightDistance(m.group(2), own.group(2));
				boolean sameStyle = m.group(3).equals(own.group(3));
				if (!m.group(1).equals(own.group(1))) {
					rank.put(name, (sameStyle ? 4000 : 5000) + weights + sizes);
				} else {
					rank.put(name, !sameStyle ? 3000 + weights + sizes
							: Objects.equals(m.group(2), own.group(2)) ? 1000 + sizes
							: 2000 + weights + sizes);
				}
			}
		}
		List<String> names = rank.keySet().stream()
				.sorted(Comparator.comparing((String n) -> rank.get(n)).thenComparing(n -> n)).toList();
		List<Donor> found = new ArrayList<>();
		boolean alphabet = false;
		for (String name : names) {
			if (rank.get(name) >= 3000 && alphabet) {
				// another style or family is wanted only until a whole alphabet is found; reading the rest is slow
				break;
			}
			for (Path archive : fontHomes.getOrDefault(name, List.of())) {
				try {
					// through the per-archive cache, so each archive is unpacked once for every font's donors
					Bcfnt font = font(archive, name);
					if (font != null && hasEnglish(font)) {
						found.add(new Donor(archive, name, font));
						alphabet |= hasAlphabet(font);
					}
				} catch (IOException | RuntimeException unreadable) {
					// a broken copy just lends nothing
				}
			}
		}
		List<Donor> result = List.copyOf(found);
		donors.put(fontName, result);
		return result;
	}

	/** {@code fontName}'s style ({@code 04a} of {@code SulaPro_B_04a_20.bcfnt}), or null if it names none. */
	public static String style(String fontName) {
		Matcher m = FONT_NAME.matcher(fontName);
		return m.matches() ? m.group(3) : null;
	}

	/** {@code fontName}'s weight ({@code B} of {@code SulaPro_B_04a_20.bcfnt}), or null if it names none. */
	public static String weight(String fontName) {
		Matcher m = FONT_NAME.matcher(fontName);
		return m.matches() ? m.group(2) : null;
	}

	/** {@code fontName}'s family ({@code SulaPro} of {@code SulaPro_B_04a_20.bcfnt}), or null if it names none. */
	public static String family(String fontName) {
		Matcher m = FONT_NAME.matcher(fontName);
		return m.matches() ? m.group(1) : null;
	}

	/** How many steps apart two weights are; an unknown or missing weight counts as far. */
	private static int weightDistance(String a, String b) {
		int i = a == null ? -1 : WEIGHTS.indexOf(a);
		int j = b == null ? -1 : WEIGHTS.indexOf(b);
		return i < 0 || j < 0 ? WEIGHTS.size() : Math.abs(i - j);
	}

	private static boolean hasAlphabet(Bcfnt font) {
		for (char c = 'A'; c <= 'Z'; c++) {
			if (!font.has(c) || !font.has(Character.toLowerCase(c))) {
				return false;
			}
		}
		return true;
	}

	private static boolean hasEnglish(Bcfnt font) {
		for (int c = 'A'; c <= 'z'; c++) {
			if (Character.isLetter(c) && font.has(c)) {
				return true;
			}
		}
		return false;
	}

	private static String fileName(String darcPath) {
		return darcPath.substring(darcPath.lastIndexOf('/') + 1);
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

	/** Every archive under {@code root}, leaving out any other romfs nested inside it. */
	private static List<Path> archives(Path root) throws IOException {
		List<Path> found = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				return dir.equals(root) || !isNestedRomfs(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (file.getFileName().toString().endsWith(ARCHIVE_SUFFIX)) {
					found.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(found);
		return found;
	}

	private static boolean isNestedRomfs(Path dir) throws IOException {
		return dir.getFileName().toString().toLowerCase(Locale.ROOT).equals("romfs") || looksLikeRomfs(dir);
	}

	private static String strip(Path p, String suffix) {
		String name = p.getFileName().toString();
		return name.substring(0, name.length() - suffix.length());
	}
}
