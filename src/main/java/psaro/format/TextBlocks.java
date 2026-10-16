package psaro.format;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code *.bin} files of numbered texts of several lines, as SD Gundam G Generation 3D keeps its
 * ability and damage help, its gallery profiles and its navigator's comments
 * ({@code gg3d_ability_txt.bin}, {@code gg3d_prof_ms.bin}, {@code gg3d_navi_operator01.bin}).
 *
 * <pre>
 * header: u32 texts, then per text s32 a number the game reads with it (a face, an icon; -1 for
 *         none) and u32 its length in bytes
 * texts:  one after another, each Shift-JIS lines ending CRLF, then ";" and NUL
 * </pre>
 *
 * Nothing marks the format but the lengths adding up to the file's and every text ending
 * {@code ;} NUL.
 *
 * <p>The text is every one that holds Japanese, keyed by its number ({@code 004}), its lines
 * broken by {@code \n}, without the line break, {@code ;} and NUL that end it.
 */
public final class TextBlocks {

	private static final String END = "\r\n";

	private TextBlocks() {
	}

	/** Whether {@code d} is a file of texts: lengths adding up to its own, each text ending {@code ;} NUL. */
	public static boolean is(byte[] d) {
		if (d.length < 4) {
			return false;
		}
		long count = Bytes.u32(d, 0) & 0xFFFFFFFFL;
		if (count == 0 || 4 + 8 * count > d.length) {
			return false;
		}
		long at = 4 + 8 * count;
		for (int i = 0; i < count; i++) {
			long length = Bytes.u32(d, 8 + 8 * i) & 0xFFFFFFFFL;
			at += length;
			if (length < 2 || at > d.length || d[(int) at - 2] != ';' || d[(int) at - 1] != 0) {
				return false;
			}
		}
		return at == d.length;
	}

	/** Every text that holds Japanese, in file order. */
	public static LinkedHashMap<String, String> read(byte[] d) {
		if (!is(d)) {
			throw new IllegalArgumentException("not a file of texts");
		}
		int count = Bytes.u32(d, 0);
		int at = 4 + 8 * count;
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			int length = Bytes.u32(d, 8 + 8 * i);
			String text = ShiftJis.decode(d, at, at + length - 2);
			at += length;
			if (text == null || !ShiftJis.japanese(text)) {
				continue;
			}
			if (text.endsWith(END)) {
				text = text.substring(0, text.length() - END.length());
			}
			out.put(String.format(Locale.ROOT, "%03d", i), text.replace(END, "\n"));
		}
		return out;
	}

	/** Whether {@code text} can be written into a file of texts: Shift-JIS. Null when it can, else why not. */
	public static String problem(String text) {
		String missing = ShiftJis.unencodable(text);
		return missing == null ? null : "Shift-JIS has no " + missing;
	}

	/**
	 * {@code d} with each text {@code texts} has a different text for (by key) holding it, its
	 * lines ending CRLF as the game's do, and its length and every other text as they were.
	 * Refused when a text is not Shift-JIS.
	 */
	public static byte[] write(byte[] d, Map<String, String> texts) {
		Map<String, String> current = read(d);
		int count = Bytes.u32(d, 0);
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		head.write(d, 0, 4);
		int at = 4 + 8 * count;
		for (int i = 0; i < count; i++) {
			int length = Bytes.u32(d, 8 + 8 * i);
			String key = String.format(Locale.ROOT, "%03d", i);
			String text = texts.get(key);
			byte[] stored;
			if (text == null || !current.containsKey(key) || text.equals(current.get(key))) {
				stored = Arrays.copyOfRange(d, at, at + length);
			} else {
				String problem = problem(text);
				if (problem != null) {
					throw new IllegalArgumentException(key + ": " + problem);
				}
				// the line break the game's text ends with, which reading left off
				boolean ends = new String(d, at, length - 2, ShiftJis.CHARSET).endsWith(END);
				stored = ShiftJis.encode(text.replace("\n", END) + (ends ? END : "") + ";\0");
			}
			at += length;
			head.write(d, 4 + 8 * i, 4);
			head.write(stored.length);
			head.write(stored.length >> 8);
			head.write(stored.length >> 16);
			head.write(stored.length >> 24);
			body.writeBytes(stored);
		}
		head.writeBytes(body.toByteArray());
		return head.toByteArray();
	}
}
