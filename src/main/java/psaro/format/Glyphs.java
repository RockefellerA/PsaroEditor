package psaro.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The characters of a game that stores text as places in its font rather than in a character
 * set: Lost Heroes' message tables ({@link Mbb}) hold each character as the number of its cell in
 * the font's sheets, left to right and top to bottom, counted from 1, as 0 ends a string. Nothing
 * in the game names the characters, so the table of them was read off the font's pictures.
 *
 * <p>A table lists the characters in code order from code 1, as UTF-8; line breaks are not
 * characters, so each line can hold one row of the font's sheet. A cell no character is known
 * for is {@code U+FFFD}.
 *
 * <p>Codes from {@code 0x8000} are the game's commands. {@code 0x8001} breaks the line and shows
 * as {@code \n}; every other, and every cell no character is known for, shows as its four hex
 * digits in braces, {@code {8074}}, and is written back as it was.
 */
public final class Glyphs {

	/** Breaks the line. */
	public static final int NEW_LINE = 0x8001;
	private static final int UNKNOWN = 0xFFFD;
	private static final Pattern CODE = Pattern.compile("\\{([0-9A-Fa-f]{4})\\}");
	private static final String LOST_HEROES = "/glyphs/lost-heroes.txt";

	private static Glyphs lostHeroes;

	/** By code; 0 for none. */
	private final int[] chars;
	/** The first code of each character. */
	private final Map<Integer, Integer> codes = new HashMap<>();

	private Glyphs(int[] chars) {
		this.chars = chars;
		for (int code = 1; code < chars.length; code++) {
			if (chars[code] != 0) {
				codes.putIfAbsent(chars[code], code);
			}
		}
	}

	/** The table {@code text} lists, in code order from code 1. */
	public static Glyphs of(String text) {
		List<Integer> chars = new ArrayList<>();
		chars.add(0);
		text.codePoints().filter(c -> c != '\n' && c != '\r').forEach(c -> chars.add(c == UNKNOWN ? 0 : c));
		return new Glyphs(chars.stream().mapToInt(Integer::intValue).toArray());
	}

	/**
	 * Lost Heroes' characters, the same in each of its fonts: codes 1 to 2014, every cell its text
	 * uses. The font's later cells, which no text uses, are left out, so a character is never
	 * written as a cell nobody has checked.
	 */
	public static synchronized Glyphs lostHeroes() {
		if (lostHeroes == null) {
			try (InputStream in = Glyphs.class.getResourceAsStream(LOST_HEROES)) {
				if (in == null) {
					throw new IllegalStateException(LOST_HEROES + " is missing");
				}
				lostHeroes = of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return lostHeroes;
	}

	/** The character of {@code code}, or -1 when it is a command or a cell no character is known for. */
	public int character(int code) {
		return code > 0 && code < chars.length && chars[code] != 0 ? chars[code] : -1;
	}

	/** The text {@code codes} spell, without the 0 that ends them. */
	public String decode(int[] codes) {
		StringBuilder out = new StringBuilder(codes.length);
		for (int code : codes) {
			int c = character(code);
			if (code == NEW_LINE) {
				out.append('\n');
			} else if (c >= 0) {
				out.appendCodePoint(c);
			} else {
				out.append(String.format(Locale.ROOT, "{%04X}", code));
			}
		}
		return out.toString();
	}

	/** The codes that spell {@code text}, without the 0 that ends them. Refused when the font lacks a character. */
	public int[] encode(String text) {
		String missing = unencodable(text);
		if (missing != null) {
			throw new IllegalArgumentException("the font has no " + missing);
		}
		return read(text).stream().mapToInt(t -> t[0]).toArray();
	}

	/** The first character of {@code text} the font has no cell for, or null when it has all of them. */
	public String unencodable(String text) {
		for (int[] t : read(text)) {
			if (t[0] < 0) {
				return new String(Character.toChars(t[1]));
			}
		}
		return null;
	}

	/** {@code text} as {code, character} pairs, the code -1 for a character the font lacks. */
	private List<int[]> read(String text) {
		List<int[]> out = new ArrayList<>(text.length());
		Matcher m = CODE.matcher(text);
		int i = 0;
		while (i < text.length()) {
			if (text.charAt(i) == '{' && m.find(i) && m.start() == i) {
				out.add(new int[] {Integer.parseInt(m.group(1), 16), '{'});
				i = m.end();
				continue;
			}
			int c = text.codePointAt(i);
			Integer code = c == '\n' ? Integer.valueOf(NEW_LINE) : codes.get(c);
			out.add(new int[] {code == null ? -1 : code, c});
			i += Character.charCount(c);
		}
		return out;
	}
}
