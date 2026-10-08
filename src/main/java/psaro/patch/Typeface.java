package psaro.patch;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import psaro.romfs.RomfsIndex;

/**
 * What a game font's text is drawn with: the game font itself ({@link #GAME}, the default), lent
 * what it lacks from the game's other fonts; or a typeface bundled under {@code /fonts},
 * licensed under the SIL Open Font License 1.1 (its text sits beside each typeface's files),
 * from which the patch draws a whole new font in the game font's size and outline
 * ({@link FreeFont}) and puts it in the game font's place, the game font left as it is.
 */
public enum Typeface {

	GAME("game", "Game font", null),
	M_PLUS_ROUNDED("m-plus-rounded-1c", "M PLUS Rounded 1c", "MPLUSRounded1c"),
	NOTO_SANS("noto-sans", "Noto Sans", "NotoSans");

	/** The bundled weights, lightest first, by the file name's suffix. */
	private static final List<String> WEIGHTS = List.of("Medium", "Bold", "ExtraBold", "Black");

	private final String id;
	private final String label;
	private final String file;
	private final Map<String, Font> loaded = new ConcurrentHashMap<>();

	Typeface(String id, String label, String file) {
		this.id = id;
		this.label = label;
		this.file = file;
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

	/** The typeface saved as {@code id}; the game's fonts for none or an unknown one. */
	public static Typeface of(String id) {
		for (Typeface t : values()) {
			if (t.id.equals(id)) {
				return t;
			}
		}
		return GAME;
	}

	/** True for a bundled typeface, false for the game's own fonts. */
	public boolean bundled() {
		return file != null;
	}

	/**
	 * The bundled weight nearest {@code gameFont}'s ({@code B} of {@code SulaPro_B_04a_20}): R and
	 * M take Medium, DB and B Bold, EB ExtraBold, H and U Black; a font naming none takes Bold.
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

	/** The bundled files' name before the weight, as {@code MPLUSRounded1c}; null for the game's fonts. */
	public String prefix() {
		return file;
	}

	/** The bundled file for {@code weight}, as {@code MPLUSRounded1c-Bold.ttf}. */
	public String fileName(String weight) {
		return file + "-" + weight + ".ttf";
	}

	/** This typeface in {@code weight} at a size of 1, loaded once. */
	public Font font(String weight) {
		if (!bundled()) {
			throw new IllegalStateException("the game's fonts are not a bundled typeface");
		}
		String w = WEIGHTS.contains(weight) ? weight : "Bold";
		return loaded.computeIfAbsent(w, k -> {
			try (InputStream in = Typeface.class.getResourceAsStream("/fonts/" + fileName(k))) {
				if (in == null) {
					throw new IllegalStateException("missing bundled font /fonts/" + fileName(k));
				}
				return Font.createFont(Font.TRUETYPE_FONT, in);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (FontFormatException e) {
				throw new IllegalStateException("unreadable bundled font " + fileName(k), e);
			}
		});
	}
}
