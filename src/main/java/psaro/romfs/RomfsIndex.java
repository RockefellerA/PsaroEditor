package psaro.romfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import psaro.format.Archive;
import psaro.format.Bclim;
import psaro.format.Bclyt;
import psaro.format.Bcfnt;
import psaro.format.Cgfx;
import psaro.format.Cpk;
import psaro.format.Csv;
import psaro.format.Darc;
import psaro.format.FieldTable;
import psaro.format.MapScript;
import psaro.format.Msgd;
import psaro.format.Records;
import psaro.format.Script;
import psaro.format.Tdt;
import psaro.format.TextBlocks;
import psaro.format.Thss;

/**
 * What an extracted romfs holds for translation: every string table, and for each string the
 * layout text panes that show it.
 *
 * <p>The tables ({@code text/<name>.tdt}, often with a language tag: {@code <name>_Japanese.tdt})
 * are the unit of translation: some hold strings no layout shows, which the game draws from code.
 * A pane's key resolves to the table named after its archive ({@code config.arc.lz} reads
 * {@code config_Japanese.tdt}, or {@code config.tdt} in a game that tags none); a key that
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
 * An archive is a {@code .arc.lz}, or a {@code .arc} some games keep uncompressed.
 *
 * <p>Other games keep their text elsewhere, and whatever this knows is read wherever it is under
 * the root: {@code .dat} record files ({@link Records}), as tables of their text fields, and
 * {@code .dat} event scripts ({@link Script}), as tables of their lines. Each is named by its path
 * from the root ({@code paramData/Acc}, {@code scriptData/prot1/prot1_out_pink1}).
 *
 * <p>A game that keeps its whole romfs in a CRI {@code .cpk} ({@link Cpk}) has its text read from
 * inside it, each table named by its path in the CPK: record tables ({@link FieldTable}) and its
 * database ({@link Thss}) as record tables, numbered texts ({@link TextBlocks}) as scripts, and
 * the lines of each folder of map scripts ({@link MapScript}) as one script, keyed by file and
 * line ({@code ev000:03}).
 */
public final class RomfsIndex {

	/**
	 * {@code text/<name>.tdt}: its keys and Japanese text, in file order. Or a message
	 * file, {@code message/<name>_jp.mdt}, named {@code message/<name>}, keyed by each string's
	 * number ({@code 062}): strings the game draws from code, so no layout names them. Or a data
	 * table, {@code table/<name>.csv}, named {@code table/<name>}, keyed by row and column
	 * ({@code dqc0101a:1}): its Japanese cells, names the game also draws from code. Or a record
	 * table or a script ({@link Kind#RECORDS}, {@link Kind#SCRIPT}), named by its path.
	 */
	public record StringTable(String name, Path path, Map<String, String> strings, Kind kind) {

		/** A table, a message file or a data table, told apart by its file's name. */
		public StringTable(String name, Path path, Map<String, String> strings) {
			this(name, path, strings, kindOf(path.getFileName().toString()));
		}

		private static Kind kindOf(String file) {
			return file.endsWith(MESSAGE_SUFFIX) ? Kind.MESSAGE : file.endsWith(DATA_SUFFIX) ? Kind.DATA : Kind.TABLE;
		}

		/** True for numbered strings the game draws from code: a message file ({@link Msgd}) or a script. */
		public boolean message() {
			return kind == Kind.MESSAGE || kind == Kind.SCRIPT;
		}

		/** True for strings keyed by row and column: a data table ({@link Csv}) or a record table. */
		public boolean data() {
			return kind == Kind.DATA || kind == Kind.RECORDS;
		}

		/** True for the text layouts hold themselves ({@link #BUILT_IN}). */
		public boolean builtIn() {
			return kind == Kind.BUILT_IN;
		}
	}

	/** Where a {@link StringTable}'s strings live. */
	public enum Kind {
		/** {@code text/<name>.tdt}, keyed by what layouts name. */
		TABLE,
		/** {@code message/<name>_jp.mdt}, numbered strings the game draws from code. */
		MESSAGE,
		/** {@code table/<name>.csv}, the Japanese cells of the game's records, drawn from code. */
		DATA,
		/**
		 * A {@code .dat} record file's text fields ({@link Records}), keyed by record and offset
		 * ({@code access00_00:0x24}): item, enemy and quest names, descriptions, system messages.
		 */
		RECORDS,
		/** A {@code .dat} event script's lines ({@link Script}), keyed by entry number ({@code 004}). */
		SCRIPT,
		/**
		 * The text text panes hold in their layouts, for panes the game shows it in: those with no
		 * key, a key no table has, or in an archive with no table of its own. Keyed by
		 * {@code <layout>:<pane>}, the first pane of the layout holding that text; every pane of the
		 * layout holding it shows the string. The patch writes the English into the layouts.
		 */
		BUILT_IN
	}

	/** The name of the table of text layouts hold themselves. */
	public static final String BUILT_IN = "built-in text";

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

	/**
	 * A layout image: its archive, its path inside it, the SHA-256 of its file (copies in other
	 * archives share it), and its size and GPU format ({@link psaro.format.Texture}).
	 */
	public record Image(Path archive, String path, String hash, int width, int height, int format) {

		/** Its file name without the folder and extension: {@code main_menu_up_titl_config}. */
		public String name() {
			return path.substring(path.lastIndexOf('/') + 1).replace(".bclim", "");
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

	private static final String TABLE_SUFFIX = ".tdt";
	/**
	 * A table file's tag for Japanese, which its name goes without, so {@code config_Japanese.tdt}
	 * and {@code config_jp.tdt} pair with {@code config.arc.lz} as {@code config.tdt} does.
	 */
	private static final Pattern JAPANESE_TAG = Pattern.compile("(?i)_(?:japanese|jpn|jp|ja)$");
	private static final String MESSAGE_SUFFIX = "_jp.mdt";
	/** The name a message file's table takes before its own: {@code message/tutorial_and_help}. */
	public static final String MESSAGE_PREFIX = "message/";
	private static final String DATA_SUFFIX = ".csv";
	/** The name a data table takes before its own: {@code table/CharaTable}. */
	public static final String DATA_PREFIX = "table/";
	/** An archive's name ends with one of these: compressed, or as some games keep them, not. */
	private static final List<String> ARCHIVE_SUFFIXES = List.of(".arc.lz", ".arc");
	private static final String RECORD_SUFFIX = ".dat";
	private static final String CPK_SUFFIX = ".cpk";
	/** In a CPK, the files text is read from besides a database, and a database's pointer list. */
	private static final String CPK_TEXT_SUFFIX = ".bin";
	private static final String CPK_POINTERS_SUFFIX = ".ofs";
	/** In a CPK, files larger than this are models, textures and sound, not text, and are not read. */
	private static final int CPK_TEXT_LIMIT = 4 << 20;
	/** How deep under a folder {@link #looksLikeRomfs} looks for files it can read. */
	private static final int LOOK_DEPTH = 3;
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
	/** Every layout image in every archive. */
	private final List<Image> images;
	/** A font drawn under another in one text's layers to the font drawn on top ({@link #layeredUnder}). */
	private final Map<String, String> bodies;
	/** Keyed by layout + "!" + pane: the pane drawn on top of a lower layer in another font ({@link #paneOnTop}). */
	private final Map<String, Bclyt.Pane> tops;
	/** Every text pane of every layout in every archive, in archive and layout order. */
	private final List<Usage> textPanes;
	/** By table name, where each table read from inside a CPK is. */
	private final Map<String, Packed> packed;
	private final List<Path> cpks;
	/** Keyed by {@code table + "/" + key}: the panes linked to a string no layout names ({@link #link}). */
	private final Map<String, List<Usage>> linked = new HashMap<>();
	/** Keyed by archive path + "!" + font name; empty when the archive does not carry the font. */
	private final Map<String, Optional<Bcfnt>> fonts = new HashMap<>();
	/** Keyed by font name. */
	private final Map<String, List<Donor>> donors = new HashMap<>();

	private RomfsIndex(Path root, Map<String, StringTable> tables, Map<String, List<Usage>> usages,
			List<Usage> unresolved, int layoutCount, Map<String, List<Path>> fontHomes,
			Map<String, List<Path>> layoutHomes, Map<Shape, List<Shown>> shapes, List<Image> images,
			Map<String, String> bodies, Map<String, Bclyt.Pane> tops, List<Usage> textPanes, Map<String, Packed> packed,
			List<Path> cpks) {
		this.packed = packed;
		this.cpks = cpks;
		this.root = root;
		this.tables = tables;
		this.usages = usages;
		this.unresolved = unresolved;
		this.textPanes = textPanes;
		this.layoutCount = layoutCount;
		this.fontHomes = fontHomes;
		this.layoutHomes = layoutHomes;
		this.shapes = shapes;
		this.images = images;
		this.bodies = bodies;
		this.tops = tops;
	}

	/**
	 * Whether {@code dir} has something an index is built from: string tables, or near the top
	 * (a few folders down at most) message files, data tables, archives, record files or a CPK. The
	 * rest of the folder is not looked at, so a folder that is not a romfs is turned down quickly.
	 */
	public static boolean looksLikeRomfs(Path dir) throws IOException {
		if (!tableFiles(dir).isEmpty()) {
			return true;
		}
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> files = Files.walk(dir, LOOK_DEPTH)) {
			return files.anyMatch(RomfsIndex::readable);
		} catch (UncheckedIOException unreadable) {
			return false;
		}
	}

	/** A file {@link #scan} reads: a message file, a data table, an archive, a record file or a CPK. */
	private static boolean readable(Path p) {
		String name = p.getFileName() == null ? "" : p.getFileName().toString();
		Path parent = p.getParent() == null ? null : p.getParent().getFileName();
		String folder = parent == null ? "" : parent.toString();
		return folder.equals("message") && name.endsWith(MESSAGE_SUFFIX) || folder.equals("table") && name.endsWith(DATA_SUFFIX)
				|| archive(name) || name.endsWith(RECORD_SUFFIX) && Files.isRegularFile(p) && recordFile(p)
				|| name.endsWith(CPK_SUFFIX) && Files.isRegularFile(p) && Cpk.is(p);
	}

	/** Whether {@code file} starts as a record file does, read no further than that. */
	private static boolean recordFile(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] magic = in.readNBytes(4);
			return magic.length == 4 && magic[0] == 'D' && magic[1] == 'A' && magic[2] == 'R' && magic[3] == 'C';
		} catch (IOException unreadable) {
			return false;
		}
	}

	/** Reads every string table and every layout under {@code root}. */
	public static RomfsIndex scan(Path root) throws IOException {
		Map<String, StringTable> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		Map<String, List<String>> tablesByKey = new HashMap<>();
		for (Path p : tableFiles(root)) {
			String name = tableName(p, tables.keySet());
			Map<String, String> strings = Collections.unmodifiableMap(Tdt.read(Files.readAllBytes(p)));
			tables.put(name, new StringTable(name, p, strings));
			for (String key : strings.keySet()) {
				tablesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
			}
		}
		// keyed by number, which every message file repeats, so no pane's key resolves to one
		for (Path p : messageFiles(root)) {
			List<String> read;
			try {
				read = Msgd.read(Files.readAllBytes(p));
			} catch (IllegalArgumentException | IndexOutOfBoundsException notMsgd) {
				continue;
			}
			Map<String, String> strings = new LinkedHashMap<>();
			for (int i = 0; i < read.size(); i++) {
				strings.put(String.format(Locale.ROOT, "%03d", i), read.get(i));
			}
			String name = MESSAGE_PREFIX + strip(p, MESSAGE_SUFFIX);
			tables.put(name, new StringTable(name, p, Collections.unmodifiableMap(strings)));
		}
		// keyed by row id and column, none of which a pane's key is; a table with no Japanese is left out
		for (Path p : dataFiles(root)) {
			Map<String, String> strings;
			try {
				strings = Csv.read(Files.readAllBytes(p));
			} catch (IllegalArgumentException notCsv) {
				continue;
			}
			if (!strings.isEmpty()) {
				String name = DATA_PREFIX + strip(p, DATA_SUFFIX);
				tables.put(name, new StringTable(name, p, Collections.unmodifiableMap(strings)));
			}
		}

		// every archive, model and record file, in one walk of the tree
		Found found = walk(root);
		// record tables and scripts, named by their path; one with no Japanese is left out
		for (Path p : found.records()) {
			String name = recordName(root, p);
			Map<String, String> strings;
			Kind kind;
			try {
				byte[] d = Files.readAllBytes(p);
				kind = Script.is(d) ? Kind.SCRIPT : Kind.RECORDS;
				strings = kind == Kind.SCRIPT ? Script.read(d) : Records.strings(d);
			} catch (IllegalArgumentException | IndexOutOfBoundsException notRecords) {
				continue;
			}
			if (!strings.isEmpty() && !tables.containsKey(name)) {
				tables.put(name, new StringTable(name, p, Collections.unmodifiableMap(strings), kind));
			}
		}
		// the text inside each CPK, named by its path there
		Map<String, Packed> packed = new HashMap<>();
		for (Path cpk : found.cpks()) {
			for (PackedTable t : cpkTables(cpk)) {
				if (tables.putIfAbsent(t.table().name(), t.table()) == null) {
					packed.put(t.table().name(), t.packed());
				}
			}
		}

		Map<String, List<Usage>> usages = new HashMap<>();
		List<Usage> unresolved = new ArrayList<>();
		List<Usage> textPanes = new ArrayList<>();
		// layout to the text its panes hold to those panes, in every archive carrying it
		Map<String, Map<String, List<Usage>>> builtIn = new LinkedHashMap<>();
		Map<String, List<Path>> fontHomes = new HashMap<>();
		Map<String, List<Path>> layoutHomes = new HashMap<>();
		Map<Shape, List<Shown>> shapes = new HashMap<>();
		List<Image> images = new ArrayList<>();
		Map<String, String> bodies = new HashMap<>();
		Map<String, Bclyt.Pane> tops = new HashMap<>();
		int layouts = 0;
		for (Path archive : found.archives()) {
			StringTable own = tables.get(archiveName(archive));
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
				if (file.getKey().endsWith(".bclim")) {
					try {
						Bclim b = Bclim.read(file.getValue().data);
						images.add(new Image(archive, file.getKey(), Bclim.hash(file.getValue().data), b.width(), b.height(),
								b.format()));
					} catch (IllegalArgumentException notAnImage) {
						// an image this cannot read is left out
					}
				}
				if (!file.getKey().endsWith(".bclyt")) {
					continue;
				}
				layouts++;
				layoutHomes.computeIfAbsent(file.getKey(), k -> new ArrayList<>()).add(archive);
				Bclyt.Layout layout = Bclyt.read(file.getValue().data);
				List<String> layoutFonts = List.copyOf(layout.fonts());
				addBodies(file.getKey(), layout.textPanes(), bodies, tops);
				for (Bclyt.Pane pane : layout.textPanes()) {
					Usage usage = new Usage(archive, file.getKey(), pane, layoutFonts);
					textPanes.add(usage);
					// the text the layout holds, where the game shows it: no table fills the pane
					boolean unkeyed = pane.keys().stream().noneMatch(tablesByKey::containsKey);
					if (japanese(pane.text().placeholder()) && (own == null || unkeyed)) {
						builtIn.computeIfAbsent(file.getKey(), k -> new LinkedHashMap<>())
								.computeIfAbsent(pane.text().placeholder(), k -> new ArrayList<>()).add(usage);
					}
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
		Map<String, String> held = new LinkedHashMap<>();
		builtIn.forEach((layout, byText) -> byText.forEach((text, panes) -> {
			String key = layout + ":" + panes.get(0).pane().name();
			held.put(key, text);
			usages.put(BUILT_IN + "/" + key, panes);
		}));
		if (!held.isEmpty()) {
			tables.put(BUILT_IN, new StringTable(BUILT_IN, root, Collections.unmodifiableMap(held), Kind.BUILT_IN));
		}
		// models' textures, which hold text too (the record book's page headers), as images of their own
		for (Path model : found.models()) {
			try {
				byte[] d = Cgfx.read(model);
				for (Cgfx.Tex t : Cgfx.textures(d)) {
					images.add(new Image(model, t.name(), Bclim.hash(Cgfx.asBclim(d, t)), t.width(), t.height(), t.format()));
				}
			} catch (IllegalArgumentException | IndexOutOfBoundsException notAModel) {
				// a model this cannot read is left out
			}
		}
		shapes.replaceAll((shape, shown) -> List.copyOf(shown));
		return new RomfsIndex(root, Collections.unmodifiableMap(tables), usages, List.copyOf(unresolved), layouts,
				fontHomes, layoutHomes, shapes, List.copyOf(images), bodies, tops, List.copyOf(textPanes), Map.copyOf(packed),
				List.copyOf(found.cpks()));
	}

	/**
	 * Notes, for each text pane of {@code layout} drawn under others of one text in another font
	 * (an outline under its fill: the same string, from the same place, in the same box), the last
	 * of them, the one drawn on top, and its font. A font seen first keeps what it was first seen
	 * under.
	 */
	private static void addBodies(String layout, List<Bclyt.Pane> text, Map<String, String> bodies,
			Map<String, Bclyt.Pane> tops) {
		for (int i = 0; i < text.size(); i++) {
			Bclyt.Pane under = text.get(i);
			Bclyt.Pane top = null;
			String body = null;
			for (int j = i + 1; j < text.size(); j++) {
				Bclyt.Pane over = text.get(j);
				if (!under.keys().isEmpty() && under.keys().equals(over.keys()) && under.stacksOn(over)
						&& under.text().boxWidth() == over.text().boxWidth()
						&& under.text().boxHeight() == over.text().boxHeight()
						&& !under.text().font().equals(over.text().font())) {
					body = over.text().font();
					top = over;
				}
			}
			if (body != null) {
				bodies.putIfAbsent(under.text().font(), body);
				tops.put(layout + "!" + under.name(), top);
			}
		}
	}

	/**
	 * The font drawn on top of {@code font} where a layout draws one text in layers of different
	 * fonts (a fill over an outline, the outline a font of its own), or null when no layout does.
	 */
	public String layeredUnder(String font) {
		return bodies.get(font);
	}

	/**
	 * The pane {@code layout} draws on top of its pane {@code pane} where the two are layers of one
	 * text in different fonts (an outline under the letters), or null when it draws none.
	 */
	public Bclyt.Pane paneOnTop(String layout, String pane) {
		return tops.get(layout + "!" + pane);
	}

	/** Every layout that draws some text in layers of different fonts ({@link #paneOnTop}). */
	public Set<String> layeredLayouts() {
		Set<String> out = new TreeSet<>();
		tops.keySet().forEach(k -> out.add(k.substring(0, k.lastIndexOf('!'))));
		return out;
	}

	/** How a table read from inside a CPK is kept there, which says how its English is written back. */
	public enum PackedFormat {
		/** A record table ({@link FieldTable}), in the file at the path. */
		FIELD_TABLE,
		/** A file of numbered texts ({@link TextBlocks}), at the path. */
		TEXT_BLOCKS,
		/** The map scripts ({@link MapScript}) of the folder at the path, keyed {@code <file>:<line>}. */
		MAP_SCRIPTS,
		/** A THSS data file ({@link Thss}) at the path, its pointer list beside it as {@code .ofs}. */
		DATABASE
	}

	/** Where a table read from inside a CPK is: the CPK, the path there (a folder, for map scripts), and how it is kept. */
	public record Packed(Path cpk, String path, PackedFormat format) {
	}

	/** Where {@code table} is inside a CPK, or null when it is a file of its own. */
	public Packed packed(StringTable table) {
		return packed.get(table.name());
	}

	/** Every CPK under the root, whether or not it holds text. */
	public List<Path> cpks() {
		return cpks;
	}

	public Path root() {
		return root;
	}

	/** Every layout image (BCLIM) in every archive, in archive order. */
	public List<Image> images() {
		return images;
	}

	/** {@code image}'s file, read from its archive; a model's texture as a BCLIM ({@link Cgfx#asBclim}). */
	public byte[] imageBytes(Image image) throws IOException {
		if (Cgfx.isModel(image.archive().getFileName().toString())) {
			byte[] d = Cgfx.read(image.archive());
			for (Cgfx.Tex t : Cgfx.textures(d)) {
				if (t.name().equals(image.path())) {
					return Cgfx.asBclim(d, t);
				}
			}
			throw new IOException(image.path() + " is no longer in " + image.archive());
		}
		Darc.Node node = Darc.find(Archive.load(image.archive()), image.path());
		if (node == null) {
			throw new IOException(image.path() + " is no longer in " + image.archive());
		}
		return node.data;
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
		List<Usage> own = usages.getOrDefault(table.name() + "/" + key, List.of());
		List<Usage> more;
		synchronized (linked) {
			more = linked.get(table.name() + "/" + key);
		}
		if (more == null) {
			return Collections.unmodifiableList(own);
		}
		List<Usage> out = new ArrayList<>(own);
		more.stream().filter(u -> !own.contains(u)).forEach(out::add);
		return Collections.unmodifiableList(out);
	}

	/** A pane, wherever its layout is: the layout's path inside an archive, and the pane's name. */
	public record PaneRef(String layout, String pane) {
	}

	/**
	 * Has {@code panes} show {@code key} from {@code table} (in every archive carrying their
	 * layouts) besides those whose layout names it: for a string the game draws from code, the
	 * panes it draws it in, so the preview, the fit and the patch's fonts know them. None unlinks.
	 */
	public void link(StringTable table, String key, Collection<PaneRef> panes) {
		List<Usage> found = textPanes.stream().filter(u -> panes.contains(new PaneRef(u.layout(), u.pane().name()))).toList();
		synchronized (linked) {
			if (found.isEmpty()) {
				linked.remove(table.name() + "/" + key);
			} else {
				linked.put(table.name() + "/" + key, found);
			}
		}
	}

	/** Every text pane of every layout in every archive, in archive and layout order. */
	public List<Usage> textPanes() {
		return textPanes;
	}

	/**
	 * When no pane shows {@code key} from {@code table}, the first other table holding the same
	 * key whose copy some pane does show; null otherwise. A layout's key is matched to its own
	 * archive's table first, so a key two tables share ({@code cftp_1000} in config and
	 * config_select) is matched to only one, though the game may read either.
	 */
	public StringTable sharedWith(StringTable table, String key) {
		// a message file's keys are numbers every one of them repeats, a data table's its own rows: none shares another's
		if (table.message() || table.data() || !usages(table, key).isEmpty()) {
			return null;
		}
		for (StringTable other : tables.values()) {
			if (other != table && !other.message() && !other.data() && other.strings().containsKey(key)
					&& !usages(other, key).isEmpty()) {
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
			// the Japanese first, to have the name when another language's table shares it
			return files.filter(p -> p.getFileName().toString().endsWith(TABLE_SUFFIX))
					.sorted(Comparator.comparing((Path p) -> !JAPANESE_TAG.matcher(strip(p, TABLE_SUFFIX)).find())
							.thenComparing(Comparator.naturalOrder()))
					.toList();
		}
	}

	/**
	 * {@code p}'s table name: its file name without {@code .tdt} or a tag for Japanese, which the
	 * archive it pairs with is named; its whole file name when another table already has that.
	 */
	private static String tableName(Path p, Set<String> taken) {
		String name = JAPANESE_TAG.matcher(strip(p, TABLE_SUFFIX)).replaceFirst("");
		return name.isEmpty() || taken.contains(name) ? p.getFileName().toString() : name;
	}

	/** Whether {@code text} holds kana or kanji, so it reads as text rather than a placeholder ({@code *}, {@code 123}). */
	private static boolean japanese(String text) {
		return text != null && text.codePoints().anyMatch(c -> c >= 0x3040 && c < 0xA000 || c >= 0xFF00 && c < 0xFFF0);
	}

	private static List<Path> dataFiles(Path root) throws IOException {
		Path table = root.resolve("table");
		if (!Files.isDirectory(table)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(table)) {
			return files.filter(p -> p.getFileName().toString().endsWith(DATA_SUFFIX)).sorted().toList();
		}
	}

	private static List<Path> messageFiles(Path root) throws IOException {
		Path message = root.resolve("message");
		if (!Files.isDirectory(message)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(message)) {
			return files.filter(p -> p.getFileName().toString().endsWith(MESSAGE_SUFFIX)).sorted().toList();
		}
	}

	/** The archives, models, record files and CPKs under a root, each sorted. */
	private record Found(List<Path> archives, List<Path> models, List<Path> records, List<Path> cpks) {
	}

	/**
	 * Every archive, model, record file and CPK under {@code root}, leaving out any other romfs
	 * nested inside it; a {@code .dat} only when it starts as a record file does, a {@code .cpk}
	 * only when it starts as a CPK does.
	 */
	private static Found walk(Path root) throws IOException {
		List<Path> archives = new ArrayList<>();
		List<Path> models = new ArrayList<>();
		List<Path> records = new ArrayList<>();
		List<Path> cpks = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				return dir.equals(root) || !isNestedRomfs(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				String name = file.getFileName().toString();
				if (archive(name)) {
					archives.add(file);
				} else if (Cgfx.isModel(name)) {
					models.add(file);
				} else if (name.endsWith(RECORD_SUFFIX) && recordFile(file)) {
					records.add(file);
				} else if (name.endsWith(CPK_SUFFIX) && Cpk.is(file)) {
					cpks.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(archives);
		Collections.sort(models);
		Collections.sort(records);
		Collections.sort(cpks);
		return new Found(archives, models, records, cpks);
	}

	/**
	 * The text inside {@code cpk}: each record table, file of texts and database by its path in the
	 * CPK without its extension, and each folder's map scripts as one table by the folder's path.
	 * A table's path is where it is inside the CPK ({@code data.cpk/common/database/...}), not a file
	 * of its own. A file this cannot read is left out, as is a table with no Japanese.
	 */
	private static List<PackedTable> cpkTables(Path cpk) throws IOException {
		List<PackedTable> out = new ArrayList<>();
		Map<String, Map<String, String>> scripts = new TreeMap<>();
		try (Cpk c = Cpk.open(cpk)) {
			for (Cpk.Entry e : c.entries()) {
				String path = e.path();
				if (e.extractSize() > CPK_TEXT_LIMIT) {
					continue;
				}
				try {
					Map<String, String> strings;
					Kind kind;
					PackedFormat format;
					if (path.endsWith(CPK_TEXT_SUFFIX)) {
						byte[] d = c.read(e);
						if (FieldTable.is(d)) {
							strings = FieldTable.strings(d);
							kind = Kind.RECORDS;
							format = PackedFormat.FIELD_TABLE;
						} else if (TextBlocks.is(d)) {
							strings = TextBlocks.read(d);
							kind = Kind.SCRIPT;
							format = PackedFormat.TEXT_BLOCKS;
						} else {
							String file = stem(path.substring(path.lastIndexOf('/') + 1));
							Map<String, String> lines = scripts.computeIfAbsent(folder(path), k -> new LinkedHashMap<>());
							MapScript.lines(d).forEach((key, line) -> lines.put(file + ":" + key, line));
							continue;
						}
					} else if (path.endsWith(RECORD_SUFFIX)) {
						Cpk.Entry pointers = c.find(stem(path) + CPK_POINTERS_SUFFIX);
						if (pointers == null) {
							continue;
						}
						byte[] d = c.read(e);
						byte[] ofs = c.read(pointers);
						if (!Thss.is(d, ofs)) {
							continue;
						}
						strings = Thss.strings(d, ofs);
						kind = Kind.RECORDS;
						format = PackedFormat.DATABASE;
					} else {
						continue;
					}
					if (!strings.isEmpty()) {
						out.add(new PackedTable(new StringTable(stem(path), cpk.resolve(path), Collections.unmodifiableMap(strings),
								kind), new Packed(cpk, path, format)));
					}
				} catch (IllegalArgumentException | IndexOutOfBoundsException unreadable) {
					// a file this cannot read is left out
				}
			}
		} catch (IllegalArgumentException | IndexOutOfBoundsException notCpk) {
			return List.of();
		}
		scripts.forEach((folder, lines) -> {
			if (!lines.isEmpty()) {
				out.add(new PackedTable(new StringTable(folder, cpk.resolve(folder), Collections.unmodifiableMap(lines), Kind.SCRIPT),
						new Packed(cpk, folder, PackedFormat.MAP_SCRIPTS)));
			}
		});
		return out;
	}

	/** A table read from inside a CPK, and where it is there. */
	private record PackedTable(StringTable table, Packed packed) {
	}

	/** A path in a CPK without its extension. */
	private static String stem(String path) {
		int dot = path.lastIndexOf('.');
		return dot > path.lastIndexOf('/') ? path.substring(0, dot) : path;
	}

	/** The folder of a path in a CPK; empty at its top. */
	private static String folder(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	/** Whether a file of this name is an archive. */
	private static boolean archive(String name) {
		return ARCHIVE_SUFFIXES.stream().anyMatch(name::endsWith);
	}

	/** An archive's name without its suffix ({@code config} of {@code config.arc.lz}), as its table is named. */
	private static String archiveName(Path archive) {
		String name = archive.getFileName().toString();
		for (String suffix : ARCHIVE_SUFFIXES) {
			if (name.endsWith(suffix)) {
				return name.substring(0, name.length() - suffix.length());
			}
		}
		return name;
	}

	/** A record file's table name: its path from the root, without {@code .dat}, with forward slashes. */
	private static String recordName(Path root, Path file) {
		String path = root.relativize(file).toString().replace('\\', '/');
		return path.substring(0, path.length() - RECORD_SUFFIX.length());
	}

	/** A folder named {@code romfs}, or one with string tables of its own: a copy, not the game's. */
	private static boolean isNestedRomfs(Path dir) throws IOException {
		return dir.getFileName().toString().toLowerCase(Locale.ROOT).equals("romfs") || !tableFiles(dir).isEmpty();
	}

	private static String strip(Path p, String suffix) {
		String name = p.getFileName().toString();
		return name.substring(0, name.length() - suffix.length());
	}
}
