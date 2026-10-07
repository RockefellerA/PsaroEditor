package psaro.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts between a string as the game stores it and as the editor shows it.
 *
 * <p>The game's strings switch colour with {@code \u0002} followed by one code character
 * ({@code \u0002\u0001} back to the normal colour, {@code \u0002\u0002} and others to a
 * highlight). Those are invisible in a text field, so the editor shows each as {@code {NN}},
 * the code in two hex digits: {@code {02}Name{01}}. Any other control character except
 * {@code \n} shows as {@code {uXXXX}}. The game's text never contains braces, so the
 * conversion is unambiguous.
 */
public final class ControlCodes {

	/** Starts a colour switch; the next character is the code. */
	public static final char COLOUR = '\u0002';

	private static final Pattern TOKEN = Pattern.compile("\\{([0-9A-Fa-f]{2})\\}|\\{u([0-9A-Fa-f]{4})\\}");

	private ControlCodes() {
	}

	/** The game's text as the editor shows it. */
	public static String toDisplay(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == COLOUR && i + 1 < raw.length()) {
				out.append(String.format("{%02X}", (int) raw.charAt(++i)));
			} else if (c < 0x20 && c != '\n') {
				out.append(String.format("{u%04X}", (int) c));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** The editor's text as the game stores it. */
	public static String toRaw(String display) {
		Matcher m = TOKEN.matcher(display);
		StringBuilder out = new StringBuilder(display.length());
		while (m.find()) {
			String code = m.group(1) != null
					? "" + COLOUR + (char) Integer.parseInt(m.group(1), 16)
					: "" + (char) Integer.parseInt(m.group(2), 16);
			m.appendReplacement(out, Matcher.quoteReplacement(code));
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
