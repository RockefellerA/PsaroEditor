package psaro.patch;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.font.FontRenderContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import psaro.format.OpenType;
import psaro.project.Translations;

/**
 * The typefaces a project's text can be drawn with: the game font, the typeface PsaroEditor ships
 * with ({@link Typeface#M_PLUS_ROUNDED}), and those imported into the project, kept in
 * {@code <romfs>.psaro/fonts}. Every {@code .ttf} or {@code .otf} there is read for its family and
 * weight ({@link OpenType}); the files of one family are one typeface, a weight to a file. Fonts
 * are imported into the project rather than shipped, so their licences stay the project's.
 *
 * <p>A setting may name a typeface not imported here (the project was moved, or its fonts
 * removed): it draws with the game font, its setting kept, until it is imported again.
 */
public final class Typefaces {

	/** The project folder's subfolder imported fonts are kept in. */
	public static final String FOLDER = "fonts";
	private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

	/** Where imported fonts are kept; null for none. */
	private final Path folder;
	private List<Typeface> imported = List.of();
	/** Files in the folder that could not be read, with why. */
	private List<String> unreadable = List.of();
	/** The family name the bundled typeface's files give, which an imported one cannot take. */
	private static String builtInFamily;

	private Typefaces(Path folder) {
		this.folder = folder;
	}

	/** The typefaces imported into {@code romfs}'s project, read from its fonts folder. */
	public static Typefaces open(Path romfs) {
		Typefaces t = new Typefaces(Translations.folderFor(romfs).resolve(FOLDER));
		t.scan();
		return t;
	}

	/** Only the game font and the bundled typeface, with nowhere to import to. */
	public static Typefaces none() {
		return new Typefaces(null);
	}

	/** Where imported fonts are kept, or null when there is nowhere. */
	public Path folder() {
		return folder;
	}

	/** The game font, the bundled typeface, then those imported, by name. */
	public synchronized List<Typeface> all() {
		List<Typeface> out = new ArrayList<>(List.of(Typeface.GAME, Typeface.M_PLUS_ROUNDED));
		out.addAll(imported);
		return Collections.unmodifiableList(out);
	}

	/** The imported typefaces, by name. */
	public synchronized List<Typeface> imported() {
		return imported;
	}

	/** Files in the fonts folder that could not be read, each with why. */
	public synchronized List<String> unreadable() {
		return unreadable;
	}

	/** The typeface saved as {@code id}; the game font for none, or for one not imported here. */
	public synchronized Typeface of(String id) {
		if (id == null) {
			return Typeface.GAME;
		}
		for (Typeface t : all()) {
			if (t.id().equals(id)) {
				return t;
			}
		}
		return Typeface.GAME;
	}

	/** Whether {@code id} names the game font or a typeface there is here. */
	public synchronized boolean has(String id) {
		return id == null || all().stream().anyMatch(t -> t.id().equals(id));
	}

	/** The typeface saved as {@code id}, or one standing for it, by its id, when it is not imported here. */
	public synchronized Typeface named(String id) {
		return has(id) ? of(id) : Typeface.missing(id);
	}

	/** The typeface a drawn font's file name ({@link FreeFont#name}) was drawn from; the game's fonts for another name. */
	public synchronized Typeface ofFreeFont(String freeName) {
		for (Typeface t : all()) {
			if (t.bundled() && freeName.startsWith(t.prefix() + "_")) {
				return t;
			}
		}
		return Typeface.GAME;
	}

	/** What an import did: each typeface imported or added to, with its weights, and each file not imported, with why. */
	public record Imported(List<String> added, List<String> refused) {
	}

	/**
	 * Copies {@code files} into the fonts folder, a file of a family and weight already there
	 * taking its place. A file is refused when it is no single TrueType or OpenType font, when
	 * Java cannot draw it, or when it is the bundled typeface's family.
	 */
	public synchronized Imported importFiles(List<Path> files) throws IOException {
		if (folder == null) {
			throw new IOException("there is no project to import fonts into");
		}
		Files.createDirectories(folder);
		Map<String, List<String>> added = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		List<String> refused = new ArrayList<>();
		for (Path file : files) {
			String name = file.getFileName().toString();
			byte[] d;
			OpenType font;
			try {
				d = Files.readAllBytes(file);
				font = OpenType.read(d);
				drawable(d);
			} catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
				refused.add(name + ": " + e.getMessage());
				continue;
			}
			if (builtIn(font.family())) {
				refused.add(name + ": " + Typeface.M_PLUS_ROUNDED.label() + " comes with PsaroEditor");
				continue;
			}
			String clash = prefixClash(font.family());
			if (clash != null) {
				refused.add(name + ": its family, " + font.family() + ", is named too like " + clash);
				continue;
			}
			// the same family and weight already here, from another file: this one replaces it
			for (Path old : fontFiles()) {
				OpenType o = read(old);
				if (o != null && Typeface.idFor(o.family()).equals(Typeface.idFor(font.family())) && o.weight() == font.weight()
						&& !old.getFileName().toString().equals(name)) {
					Files.delete(old);
				}
			}
			write(target(name, font), d);
			added.computeIfAbsent(font.family(), k -> new ArrayList<>()).add(OpenType.weightName(font.weight()));
		}
		scan();
		List<String> out = new ArrayList<>();
		added.forEach((family, weights) -> out.add(family + " (" + String.join(", ", weights) + ")"));
		return new Imported(List.copyOf(out), List.copyOf(refused));
	}

	/** Deletes {@code t}'s files; settings naming it keep it, and draw with the game font until it is imported again. */
	public synchronized void remove(Typeface t) throws IOException {
		if (folder == null || t.builtIn() || !t.bundled()) {
			return;
		}
		for (String file : t.weights().values()) {
			Files.deleteIfExists(folder.resolve(file));
		}
		scan();
	}

	/** Reads the fonts folder again. */
	public synchronized void scan() {
		Map<String, String> families = new LinkedHashMap<>();
		Map<String, Map<Integer, String>> weights = new TreeMap<>();
		List<String> bad = new ArrayList<>();
		for (Path file : fontFiles()) {
			OpenType o;
			try {
				o = OpenType.read(Files.readAllBytes(file));
			} catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
				bad.add(file.getFileName() + ": " + e.getMessage());
				continue;
			}
			String id = Typeface.idFor(o.family());
			families.putIfAbsent(id, o.family());
			weights.computeIfAbsent(id, k -> new TreeMap<>()).putIfAbsent(o.weight(), file.getFileName().toString());
		}
		List<Typeface> found = new ArrayList<>();
		weights.forEach((id, w) -> {
			if (!builtIn(families.get(id))) {
				found.add(Typeface.imported(families.get(id), folder, w));
			}
		});
		found.sort(Comparator.comparing(Typeface::label, String.CASE_INSENSITIVE_ORDER));
		imported = List.copyOf(found);
		unreadable = List.copyOf(bad);
	}

	/**
	 * Where a file named {@code name} holding {@code font} goes: under its own name, unless a file
	 * of another family or weight has it, then under the first {@code name-2}, {@code name-3} free.
	 */
	private Path target(String name, OpenType font) {
		Path out = folder.resolve(name);
		int dot = name.lastIndexOf('.');
		for (int n = 2; Files.exists(out); n++) {
			OpenType there = read(out);
			if (there != null && Typeface.idFor(there.family()).equals(Typeface.idFor(font.family()))
					&& there.weight() == font.weight()) {
				break;
			}
			out = folder.resolve(name.substring(0, dot) + "-" + n + name.substring(dot));
		}
		return out;
	}

	/** The fonts folder's {@code .ttf} and {@code .otf} files, by name. */
	private List<Path> fontFiles() {
		if (folder == null || !Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> {
				String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return Files.isRegularFile(p) && (n.endsWith(".ttf") || n.endsWith(".otf"));
			}).sorted().toList();
		} catch (IOException | UncheckedIOException e) {
			return List.of();
		}
	}

	private static OpenType read(Path file) {
		try {
			return OpenType.read(Files.readAllBytes(file));
		} catch (IOException | IllegalArgumentException | IndexOutOfBoundsException unreadable) {
			return null;
		}
	}

	/** Throws when Java cannot load {@code d} or draw a letter from it (some OpenType outlines it cannot). */
	private static void drawable(byte[] d) {
		Font font;
		try (InputStream in = new ByteArrayInputStream(d)) {
			font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(100f);
		} catch (IOException | FontFormatException e) {
			throw new IllegalArgumentException("Java cannot read it (" + e.getMessage() + ")");
		}
		for (int c : new int[] {'A', 'a', '0', 'あ', 'ア', '字'}) {
			if (font.canDisplay(c)
					&& !font.createGlyphVector(FRC, Character.toString(c)).getOutline().getBounds2D().isEmpty()) {
				return;
			}
		}
		throw new IllegalArgumentException("Java cannot draw its letters (try the font's .ttf version)");
	}

	/** Whether {@code family} is the bundled typeface's, as its files or its label name it. */
	private static synchronized boolean builtIn(String family) {
		if (builtInFamily == null) {
			try (InputStream in = Typeface.class.getResourceAsStream("/fonts/" + Typeface.M_PLUS_ROUNDED.fileName("Bold"))) {
				builtInFamily = in == null ? "" : OpenType.read(in.readAllBytes()).family();
			} catch (IOException | IllegalArgumentException e) {
				builtInFamily = "";
			}
		}
		String id = Typeface.idFor(family);
		return id.equals(Typeface.idFor(builtInFamily)) || id.equals(Typeface.M_PLUS_ROUNDED.id());
	}

	/** Another typeface whose drawn fonts would be named the same as {@code family}'s, or null. */
	private String prefixClash(String family) {
		String id = Typeface.idFor(family);
		String prefix = Typeface.prefixFor(family);
		for (Typeface t : all()) {
			if (t.bundled() && !t.id().equals(id) && t.prefix().equalsIgnoreCase(prefix)) {
				return t.label();
			}
		}
		return null;
	}

	/** Writes beside the target, then moves it into place, so a crash cannot leave half a font. */
	private static void write(Path file, byte[] bytes) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
