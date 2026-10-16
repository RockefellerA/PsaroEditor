package psaro.format;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code *.bin} tables of fixed-size records whose header names the fields that hold text, as
 * SD Gundam G Generation 3D keeps its unit, pilot, weapon and ability names, its guidance and its
 * map messages ({@code gg3d_unit.bin}, {@code map_msg.bin}).
 *
 * <pre>
 * header:  u32 record size, u32 records, u32 where the records start, u32 where the text starts,
 *          u32 text fields, then per text field u32 where it is in a record
 * record:  the record's fields; each text field a u32 offset into the text
 * text:    Shift-JIS strings, NUL-terminated
 * </pre>
 *
 * Nothing marks the format but its header adding up: the text fields fill it exactly, the records
 * follow it and fill the space up to the text. A table may have no text fields at all.
 *
 * <p>The text is every text field's string that holds Japanese (a {@code ナシ} placeholder does,
 * a {@code ―} or a ruler of {@code □■} does not), keyed by record number ({@code 012}), and by
 * the field's offset after it ({@code 012:0x8}) in a table of more than one text field. Text
 * written back goes after the table's own, which nothing then needs to move for.
 */
public final class FieldTable {

	private static final int FIELDS = 20;

	private FieldTable() {
	}

	/** Whether {@code d}'s header adds up as a table's does. */
	public static boolean is(byte[] d) {
		if (d.length < FIELDS) {
			return false;
		}
		long size = Bytes.u32(d, 0) & 0xFFFFFFFFL;
		long records = Bytes.u32(d, 4) & 0xFFFFFFFFL;
		long recordsAt = Bytes.u32(d, 8) & 0xFFFFFFFFL;
		long textAt = Bytes.u32(d, 12) & 0xFFFFFFFFL;
		long fields = Bytes.u32(d, 16) & 0xFFFFFFFFL;
		if (size == 0 || recordsAt != FIELDS + 4 * fields || recordsAt + size * records != textAt || textAt > d.length) {
			return false;
		}
		for (int f = 0; f < fields; f++) {
			if ((Bytes.u32(d, FIELDS + 4 * f) & 0xFFFFFFFFL) + 4 > size) {
				return false;
			}
		}
		return true;
	}

	/** Every text field's string that holds Japanese, record by record. */
	public static LinkedHashMap<String, String> strings(byte[] d) {
		if (!is(d)) {
			throw new IllegalArgumentException("not a field table");
		}
		int size = Bytes.u32(d, 0);
		int records = Bytes.u32(d, 4);
		int recordsAt = Bytes.u32(d, 8);
		int textAt = Bytes.u32(d, 12);
		int fields = Bytes.u32(d, 16);
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (int r = 0; r < records; r++) {
			for (int f = 0; f < fields; f++) {
				int field = Bytes.u32(d, FIELDS + 4 * f);
				long at = textAt + (Bytes.u32(d, recordsAt + r * size + field) & 0xFFFFFFFFL);
				String s = at < d.length ? ShiftJis.terminated(d, (int) at, d.length) : null;
				if (s != null && ShiftJis.japanese(s)) {
					String key = String.format(Locale.ROOT, "%03d", r);
					out.put(fields > 1 ? key + String.format(Locale.ROOT, ":0x%x", field) : key, s);
				}
			}
		}
		return out;
	}

	/** Whether {@code text} can be written into a table: Shift-JIS. Null when it can, else why not. */
	public static String problem(String text) {
		String missing = ShiftJis.unencodable(text);
		return missing == null ? null : "Shift-JIS has no " + missing;
	}

	/**
	 * {@code d} with each string {@code texts} has a different text for (by key) holding it: the
	 * text added after the table's own, which stays as it was for the fields still pointing there
	 * (a placeholder many records share), and the field pointed at it. Refused when a text is not
	 * Shift-JIS.
	 */
	public static byte[] write(byte[] d, Map<String, String> texts) {
		Map<String, String> current = strings(d);
		int size = Bytes.u32(d, 0);
		int records = Bytes.u32(d, 4);
		int recordsAt = Bytes.u32(d, 8);
		int textAt = Bytes.u32(d, 12);
		int fields = Bytes.u32(d, 16);
		ByteArrayOutputStream added = new ByteArrayOutputStream();
		Map<String, Integer> placed = new HashMap<>();
		byte[] out = d.clone();
		for (int r = 0; r < records; r++) {
			for (int f = 0; f < fields; f++) {
				int field = Bytes.u32(d, FIELDS + 4 * f);
				String key = String.format(Locale.ROOT, "%03d", r) + (fields > 1 ? String.format(Locale.ROOT, ":0x%x", field) : "");
				String text = texts.get(key);
				if (text == null || !current.containsKey(key) || text.equals(current.get(key))) {
					continue;
				}
				String problem = problem(text);
				if (problem != null) {
					throw new IllegalArgumentException(key + ": " + problem);
				}
				Integer at = placed.get(text);
				if (at == null) {
					at = d.length - textAt + added.size();
					placed.put(text, at);
					added.writeBytes(ShiftJis.encode(text + "\0"));
				}
				int p = recordsAt + r * size + field;
				out[p] = (byte) (int) at;
				out[p + 1] = (byte) (at >> 8);
				out[p + 2] = (byte) (at >> 16);
				out[p + 3] = (byte) (at >> 24);
			}
		}
		ByteArrayOutputStream result = new ByteArrayOutputStream(out.length + added.size());
		result.writeBytes(out);
		result.writeBytes(added.toByteArray());
		return result.toByteArray();
	}
}
