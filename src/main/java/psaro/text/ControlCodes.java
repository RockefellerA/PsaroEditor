package psaro.text;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts between a string as the game stores it and as the editor shows it.
 *
 * <p>The game's strings switch color with {@code \u0002} followed by one code character
 * ({@code \u0002\u0001} back to the normal color, {@code \u0002\u0002} and others to a
 * highlight). Those are invisible in a text field, so the editor shows a code by its name, as
 * {@code <GREEN>}, or, when it has none, as {@code {NN}}, the code in two hex digits. Either form
 * converts back. Any other control character except {@code \n} shows as {@code {uXXXX}}. The
 * game's text never contains braces or angle brackets, so the conversion is unambiguous; a
 * {@code <TAG>} that names no code stays as typed.
 */
public final class ControlCodes {

	/** Starts a color switch; the next character is the code. */
	public static final char COLOUR = '\u0002';

	/** What a code may be named: capitals, digits and underscores, as {@code ORANGE_TUTORIAL}. */
	public static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

	private static final Pattern TOKEN =
			Pattern.compile("\\{([0-9A-Fa-f]{2})\\}|\\{u([0-9A-Fa-f]{4})\\}|<(" + NAME.pattern() + ")>");

	private ControlCodes() {
	}

	/** The game's text as the editor shows it, with every code as {@code {NN}}. */
	public static String toDisplay(String raw) {
		return toDisplay(raw, Map.of());
	}

	/** The game's text as the editor shows it; {@code names} maps codes to their tag names. */
	public static String toDisplay(String raw, Map<Integer, String> names) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == COLOUR && i + 1 < raw.length()) {
				int code = raw.charAt(++i);
				String name = names.get(code);
				out.append(name != null ? "<" + name + ">" : String.format("{%02X}", code));
			} else if (c < 0x20 && c != '\n') {
				out.append(String.format("{u%04X}", (int) c));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** The editor's text as the game stores it, reading only {@code {NN}} codes. */
	public static String toRaw(String display) {
		return toRaw(display, Map.of());
	}

	/** The editor's text as the game stores it; {@code names} maps codes to their tag names. */
	public static String toRaw(String display, Map<Integer, String> names) {
		Map<String, Integer> codes = new HashMap<>();
		names.forEach((code, name) -> codes.put(name, code));
		Matcher m = TOKEN.matcher(display);
		StringBuilder out = new StringBuilder(display.length());
		while (m.find()) {
			String replacement;
			if (m.group(1) != null) {
				replacement = "" + COLOUR + (char) Integer.parseInt(m.group(1), 16);
			} else if (m.group(2) != null) {
				replacement = "" + (char) Integer.parseInt(m.group(2), 16);
			} else {
				Integer code = codes.get(m.group(3));
				replacement = code == null ? m.group() : "" + COLOUR + (char) code.intValue();
			}
			m.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** The text without its codes, on one line, for a list or table cell. */
	public static String summary(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == COLOUR) {
				i++;
			} else if (c == '\n') {
				out.append(" ⏎ ");
			} else if (c >= 0x20) {
				out.append(c);
			}
		}
		return out.toString();
	}
}
