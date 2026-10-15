package psaro.format;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * {@code *.dat} event scripts: a {@link Records} file whose records are scenes of dialogue, each
 * a list of commands, most of them a line someone says.
 *
 * <pre>
 * record: 64-byte ASCII name, 8 × s32 the characters in the scene (-1 for none),
 *         then 0x144-byte entries up to the record's end:
 * entry:  0x40-byte ASCII command (&lt;Talk&gt;, &lt;Talk Select 0&gt;, &lt;Menu Select&gt;, &lt;Jump&gt;),
 *         s32 its argument (who speaks), 0x100-byte Shift-JIS text, NUL-terminated
 * </pre>
 *
 * A command of {@code <end>} ends the scene; the entries after it hold whatever memory the tool
 * that wrote the file had, and are kept as they are. Text holds inline tags the game acts on
 * ({@code <chara02 w3 ef6>} who speaks and how, {@code <p>} wait for a press), {@code \n} line
 * breaks and {@code \u0011}kanji{@code \u0012}reading{@code \u0013} furigana.
 *
 * <p>The text is every entry's that holds Japanese, keyed by entry number ({@code 004}), and by
 * the record's number first ({@code 1:004}) in a file of more than one scene.
 */
public final class Script {

	private static final Charset SHIFT_JIS = Charset.forName("windows-31j");
	private static final int NAME = 0x40;
	private static final int ENTRIES = NAME + 8 * 4;
	private static final int ENTRY = 0x144;
	private static final int COMMAND = 0x40;
	private static final int TEXT = COMMAND + 4;
	private static final int TEXT_LENGTH = ENTRY - TEXT;
	private static final String END = "<end>";

	private Script() {
	}

	/**
	 * Whether {@code d} is a script: a record file whose every record holds whole entries after
	 * its name, the first of them a command, and ends with {@code <end>}.
	 */
	public static boolean is(byte[] d) {
		if (!Records.is(d)) {
			return false;
		}
		List<byte[]> records = Records.read(d);
		if (records.isEmpty()) {
			return false;
		}
		for (byte[] r : records) {
			if (r.length < ENTRIES + ENTRY || (r.length - ENTRIES) % ENTRY != 0 || r[ENTRIES] != '<' || end(r) < 0) {
				return false;
			}
		}
		return true;
	}

	/** Every entry's text that holds Japanese, in file order. */
	public static LinkedHashMap<String, String> read(byte[] d) {
		if (!is(d)) {
			throw new IllegalArgumentException("not a script");
		}
		List<byte[]> records = Records.read(d);
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (int r = 0; r < records.size(); r++) {
			byte[] record = records.get(r);
			int end = end(record);
			for (int e = 0; e < end; e++) {
				String text = text(record, ENTRIES + e * ENTRY);
				if (text != null && japanese(text)) {
					String key = String.format(Locale.ROOT, "%03d", e);
					out.put(records.size() > 1 ? r + ":" + key : key, text);
				}
			}
		}
		return out;
	}

	/** The number of the {@code <end>} entry, or -1 when the record has none. */
	private static int end(byte[] record) {
		for (int e = 0; ENTRIES + (e + 1) * ENTRY <= record.length; e++) {
			int o = ENTRIES + e * ENTRY;
			int n = 0;
			while (n < COMMAND && record[o + n] != 0) {
				n++;
			}
			if (new String(record, o, n, StandardCharsets.US_ASCII).equals(END)) {
				return e;
			}
		}
		return -1;
	}

	/** The text of the entry at {@code o}, or null when it is not Shift-JIS. */
	private static String text(byte[] record, int o) {
		int start = o + TEXT;
		int e = start;
		while (e < start + TEXT_LENGTH && record[e] != 0) {
			e++;
		}
		try {
			return SHIFT_JIS.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(record, start, e - start))
					.toString();
		} catch (CharacterCodingException notText) {
			return null;
		}
	}

	/** Whether {@code text} holds kana or kanji, so it is a line rather than a label or a number. */
	private static boolean japanese(String text) {
		return text.codePoints().anyMatch(c -> c >= 0x3040 && c < 0xA000 || c >= 0xFF00 && c < 0xFFF0);
	}
}
