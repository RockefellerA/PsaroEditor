package psaro.patch;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import psaro.romfs.RomfsIndex;

/**
 * What a game font's text is drawn with: the game font itself ({@link #GAME}, the default), lent
 * what it lacks from the game's other fonts; or a typeface, from which the patch draws a whole new
 * font in the game font's size and outline ({@link FreeFont}) and puts it in the game font's place,
 * the game font left as it is.
 *
 * <p>One typeface ships with PsaroEditor, M PLUS Rounded 1c, under {@code /fonts} and licensed
 * under the SIL Open Font License 1.1 (its text sits beside its files). Others are imported into a
 * project ({@link Typefaces}): a family of one or more files, one per weight. A game font is drawn
 * in the weight nearest its own ({@link #weightFor}).
 */
public final class Typeface {

	public static final Typeface GAME = new Typeface("game", "Game font", null, null, Map.of());
	public static final Typeface M_PLUS_ROUNDED = new Typeface("m-plus-rounded-1c", "M PLUS Rounded 1c", "MPLUSRounded1c",
			null, Map.of(500, "MPLUSRounded1c-Medium.ttf", 700, "MPLUSRounded1c-Bold.ttf", 800,
					"MPLUSRounded1c-ExtraBold.ttf", 900, "MPLUSRounded1c-Black.ttf"));

	/** The weights a game font is drawn in ({@link #weightFor}), by name, and the weight class each is. */
	private static final Map<String, Integer> WEIGHTS = Map.of("Medium", 500, "Bold", 700, "ExtraBold", 800, "Black", 900);

	private final String id;
	private final String label;
	private final String prefix;
	/** The folder an imported typeface's files are in; null for the one bundled under {@code /fonts}. */
	private final Path folder;
	/** Each weight class's file. */
	private final NavigableMap<Integer, String> files;
	private final Map<Integer, Font> loaded = new ConcurrentHashMap<>();

	private Typeface(String id, String label, String prefix, Path folder, Map<Integer, String> files) {
		this.id = id;
		this.label = label;
		this.prefix = prefix;
		this.folder = folder;
		this.files = new TreeMap<>(files);
	}

	/** A family imported into {@code folder}: its files there, by weight class. */
	static Typeface imported(String family, Path folder, Map<Integer, String> files) {
		return new Typeface(idFor(family), family, prefixFor(family), folder, files);
	}

	/** A typeface a setting names that is not imported here: shown by its id, drawing nothing. */
	static Typeface missing(String id) {
		return new Typeface(id, id + " (not imported)", null, null, Map.of());
	}

	/** How a family's typeface is saved: {@code rounded-mplus-1c} for {@code Rounded Mplus 1c}. */
	static String idFor(String family) {
		String id = family.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		return id.isEmpty() ? "font-" + Integer.toHexString(family.hashCode()) : id;
	}

	/** What a family's drawn fonts are named with: its letters and digits, {@code RoundedMplus1c}. */
	static String prefixFor(String family) {
		String prefix = family.replaceAll("[^A-Za-z0-9]", "");
		return prefix.isEmpty() ? "Font" + Integer.toHexString(family.hashCode()) : prefix;
	}

	/** How the setting is saved. */
	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	@Override
	public String toString() {
		return label;
	}

	/** True for a typeface to draw from, the bundled one or one imported; false for the game's own fonts. */
	public boolean bundled() {
		return !files.isEmpty();
	}

	/** True for the typeface PsaroEditor ships with. */
	public boolean builtIn() {
		return bundled() && folder == null;
	}

	/** The weight classes it has files for, lightest first, each with its file's name. */
	public NavigableMap<Integer, String> weights() {
		return java.util.Collections.unmodifiableNavigableMap(files);
	}

	/**
	 * The weight nearest {@code gameFont}'s ({@code B} of {@code SulaPro_B_04a_20}): R and M take
	 * Medium, DB and B Bold, EB ExtraBold, H and U Black; a font naming none takes Bold.
	 */
	public static String weightFor(String gameFont) {
		String w = RomfsIndex.weight(gameFont);
		return w == null ? "Bold" : switch (w) {
			case "EL", "L", "R", "M" -> "Medium";
			case "EB" -> "ExtraBold";
			case "H", "U" -> "Black";
			default -> "Bold";
		};
	}

	/** Its drawn fonts' names before the game font's weight, as {@code MPLUSRounded1c}; null for the game's fonts. */
	public String prefix() {
		return prefix;
	}

	/** The file drawn from for {@code weight} ({@link #weightFor}), as {@code MPLUSRounded1c-Bold.ttf}. */
	public String fileName(String weight) {
		return files.get(nearest(weight));
	}

	/** This typeface in {@code weight} ({@link #weightFor}), or the nearest weight it has, at a size of 1, loaded once. */
	public Font font(String weight) {
		if (!bundled()) {
			throw new IllegalStateException(label + " is not a typeface to draw from");
		}
		return loaded.computeIfAbsent(nearest(weight), w -> {
			String file = files.get(w);
			try (InputStream in = folder == null ? Typeface.class.getResourceAsStream("/fonts/" + file)
					: Files.newInputStream(folder.resolve(file))) {
				if (in == null) {
					throw new IllegalStateException("missing bundled font /fonts/" + file);
				}
				return Font.createFont(Font.TRUETYPE_FONT, in);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (FontFormatException e) {
				throw new IllegalStateException("unreadable font " + file, e);
			}
		});
	}

	/** The weight class of the file nearest {@code weight}: the heavier of two as near. */
	private int nearest(String weight) {
		int want = WEIGHTS.getOrDefault(weight, 700);
		Integer below = files.floorKey(want);
		Integer above = files.ceilingKey(want);
		if (below == null) {
			return above;
		}
		return above == null || want - below < above - want ? below : above;
	}

	/** Typefaces are the same when they are saved the same. */
	@Override
	public boolean equals(Object o) {
		return o instanceof Typeface t && t.id.equals(id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}
}
