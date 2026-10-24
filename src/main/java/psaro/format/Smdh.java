package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A title's SMDH, the ExeFS file {@code icon} the HOME Menu shows it by: its name in each of sixteen
 * languages (a short title, a long title and its publisher), then its settings and its two icons.
 * The HOME Menu shows the names of the 3DS's language.
 *
 * <pre>
 * 0x0000 "SMDH"   0x0004 version
 * 0x0008 16 × 0x200: short title (0x40 UTF-16 units), long title (0x80), publisher (0x40)
 * 0x2008 settings   0x2040 icons: 24×24, then 48×48
 * </pre>
 */
public final class Smdh {

	public static final int SIZE = 0x36C0;
	public static final int LANGUAGES = 16;
	/** What each language's slot holds, in UTF-16 units, its last left for the terminator. */
	public static final int SHORT_UNITS = 0x40;
	public static final int LONG_UNITS = 0x80;
	public static final int PUBLISHER_UNITS = 0x40;
	/** The languages of the slots in order; the last four slots are not used. */
	public static final List<String> LANGUAGE_NAMES = List.of("Japanese", "English", "French", "German", "Italian",
			"Spanish", "Simplified Chinese", "Korean", "Dutch", "Portuguese", "Russian", "Traditional Chinese");
	public static final int JAPANESE = 0;
	public static final int ENGLISH = 1;

	private static final int NAMES = 0x08;
	private static final int SLOT = 0x200;
	private static final int LONG = 0x80;
	private static final int PUBLISHER = 0x180;

	/**
	 * A title's names in one language. In {@link #withTitle} a part null or blank keeps what each
	 * language had for it, a language that had none taking the English's (else the Japanese's).
	 */
	public record Title(String shortTitle, String longTitle, String publisher) {

		/** Why this cannot be written, a part being too long for its slot; null when it can. */
		public String problem() {
			String p = tooLong("short title", shortTitle, SHORT_UNITS);
			if (p == null) {
				p = tooLong("long title", longTitle, LONG_UNITS);
			}
			return p != null ? p : tooLong("publisher", publisher, PUBLISHER_UNITS);
		}

		private static String tooLong(String what, String text, int units) {
			return text == null || text.length() < units ? null
					: "The " + what + " is " + text.length() + " characters long; the 3DS has room for " + (units - 1) + ".";
		}

		/** Whether every part is null or blank, so writing it changes nothing. */
		public boolean isBlank() {
			return blank(shortTitle) && blank(longTitle) && blank(publisher);
		}
	}

	private Smdh() {
	}

	/** Whether {@code smdh} is an SMDH. */
	public static boolean is(byte[] smdh) {
		return smdh != null && smdh.length >= SIZE && Bytes.magic(smdh, 0, "SMDH");
	}

	/** The names {@code smdh} gives in {@code language} (an index of {@link #LANGUAGE_NAMES}). */
	public static Title title(byte[] smdh, int language) {
		check(smdh);
		int slot = NAMES + language * SLOT;
		return new Title(Bytes.utf16(smdh, slot, slot + LONG), Bytes.utf16(smdh, slot + LONG, slot + PUBLISHER),
				Bytes.utf16(smdh, slot + PUBLISHER, slot + SLOT));
	}

	/**
	 * A copy of {@code smdh} with {@code title}'s parts in every language's slot but Japanese, the
	 * empty ones too, so the HOME Menu shows them whatever the 3DS's language is, and a Japanese 3DS
	 * the game's own ({@link Title} for a part not given).
	 */
	public static byte[] withTitle(byte[] smdh, Title title) {
		check(smdh);
		String problem = title.problem();
		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}
		Title english = title(smdh, ENGLISH);
		Title japanese = title(smdh, JAPANESE);
		Title fallback = new Title(part(null, english.shortTitle(), japanese.shortTitle()),
				part(null, english.longTitle(), japanese.longTitle()), part(null, english.publisher(), japanese.publisher()));
		byte[] out = smdh.clone();
		for (int language = JAPANESE + 1; language < LANGUAGES; language++) {
			Title own = title(smdh, language);
			int slot = NAMES + language * SLOT;
			put(out, slot, SHORT_UNITS, part(title.shortTitle(), own.shortTitle(), fallback.shortTitle()));
			put(out, slot + LONG, LONG_UNITS, part(title.longTitle(), own.longTitle(), fallback.longTitle()));
			put(out, slot + PUBLISHER, PUBLISHER_UNITS, part(title.publisher(), own.publisher(), fallback.publisher()));
		}
		return out;
	}

	/** What a language's part becomes: {@code given}, else its {@code own}, else {@code fallback}. */
	private static String part(String given, String own, String fallback) {
		return !blank(given) ? given : !blank(own) ? own : fallback;
	}

	private static void put(byte[] out, int at, int units, String text) {
		if (blank(text)) {
			return;
		}
		byte[] b = text.getBytes(StandardCharsets.UTF_16LE);
		Arrays.fill(out, at, at + 2 * units, (byte) 0);
		System.arraycopy(b, 0, out, at, b.length);
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static void check(byte[] smdh) {
		if (!is(smdh)) {
			throw new IllegalArgumentException("not an SMDH");
		}
	}
}
