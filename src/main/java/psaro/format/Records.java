package psaro.format;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code *.dat} record files: an upper-case {@code DARC} (not {@link Darc}'s archive) of
 * fixed-size records, as a game's item, enemy and quest tables and its system messages are kept.
 *
 * <pre>
 * header: "DARC", u32 record count, 8 bytes of zero, then per record u32 offset, u32 size
 * record: u32 index, a 32-byte ASCII id ({@code access00_00}), then the record's fields
 * </pre>
 *
 * A field of text is a NUL-terminated string in a fixed run of bytes, UTF-16LE in some files
 * and Shift-JIS in others, at times both in one file. Nothing says where the text fields are, so
 * they are found by voting: an offset is a text field when, in enough of the records, a
 * Japanese string starts there. One record alone cannot tell: a float before a string can read
 * as kanji, and an ASCII id as UTF-16 does too. Only a table's records are read for text: each
 * starts with its index, below the number of records. A file of names padded with whatever
 * memory was left (a script folder's {@code index.dat}) does not, and its leftovers are not text.
 *
 * <p>The text is every text field holding a string, keyed {@code <record id>:<offset>}
 * ({@code access00_00:0x24}): the record's id when every record has its own, else its number.
 * Text may hold {@code \u0011}kanji{@code \u0012}reading{@code \u0013} furigana and {@code \n}
 * line breaks.
 */
public final class Records {

	private static final Charset SHIFT_JIS = Charset.forName("windows-31j");
	/** Where a record's id is. */
	private static final int ID = 4;
	private static final int ID_LENGTH = 32;

	private Records() {
	}

	/** A text field: where it starts in each record, and whether it is UTF-16 (else Shift-JIS). */
	public record Column(int offset, boolean utf16) {
	}

	/** Whether {@code d} is a record file: the magic, and a table of records that fit in it. */
	public static boolean is(byte[] d) {
		return records(d) != null;
	}

	/** Each record's bytes, in file order. */
	public static List<byte[]> read(byte[] d) {
		List<int[]> spans = records(d);
		if (spans == null) {
			throw new IllegalArgumentException("not a DARC record file");
		}
		List<byte[]> out = new ArrayList<>(spans.size());
		for (int[] s : spans) {
			byte[] r = new byte[s[1]];
			System.arraycopy(d, s[0], r, 0, s[1]);
			out.add(r);
		}
		return out;
	}

	/**
	 * The strings of every text field, keyed by record and offset, record by record; none when the
	 * records are not a table's.
	 */
	public static LinkedHashMap<String, String> strings(byte[] d) {
		List<byte[]> records = read(d);
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		if (!table(records)) {
			return out;
		}
		List<Column> columns = columns(records);
		String[] ids = ids(records);
		for (int r = 0; r < records.size(); r++) {
			for (Column c : columns) {
				String s = string(records.get(r), c);
				if (s != null && !s.isEmpty()) {
					out.put(ids[r] + ":" + String.format(Locale.ROOT, "0x%x", c.offset()), s);
				}
			}
		}
		return out;
	}

	/**
	 * The text fields of {@code records}, by offset: where, in at least a quarter of them (and two,
	 * when there are two or more), a string holding two or more kana or kanji starts. Where both encodings start at one offset, or fields would overlap, the one more
	 * records agree on wins.
	 */
	public static List<Column> columns(List<byte[]> records) {
		Map<Column, Integer> votes = new HashMap<>();
		Map<Column, Integer> ends = new HashMap<>();
		for (byte[] r : records) {
			Set<Column> seen = new HashSet<>();
			for (int[] s : candidates(r, true)) {
				Column c = new Column(s[0], true);
				if (seen.add(c)) {
					votes.merge(c, 1, Integer::sum);
					ends.merge(c, s[1], Math::max);
				}
			}
			for (int[] s : candidates(r, false)) {
				Column c = new Column(s[0], false);
				if (seen.add(c)) {
					votes.merge(c, 1, Integer::sum);
					ends.merge(c, s[1], Math::max);
				}
			}
		}
		int needed = Math.max(records.size() >= 2 ? 2 : 1, (records.size() + 3) / 4);
		// UTF-16 kanji can read as Shift-JIS far more often than the other way round, so it wins a tie
		List<Column> ranked = votes.keySet().stream().filter(c -> votes.get(c) >= needed)
				.sorted(Comparator.comparing((Column c) -> -votes.get(c)).thenComparing(c -> !c.utf16())
						.thenComparingInt(Column::offset))
				.toList();
		List<Column> kept = new ArrayList<>();
		for (Column c : ranked) {
			boolean overlaps = kept.stream().anyMatch(k -> c.offset() < ends.get(k) && k.offset() < ends.get(c));
			if (!overlaps) {
				kept.add(c);
			}
		}
		kept.sort((a, b) -> Integer.compare(a.offset(), b.offset()));
		return kept;
	}

	/** The string in field {@code c} of {@code record}, or null when its bytes are not text. */
	public static String string(byte[] record, Column c) {
		if (c.offset() >= record.length) {
			return null;
		}
		return c.utf16() ? utf16(record, c.offset()) : shiftJis(record, c.offset());
	}

	/** Whether every record starts with an index below the number of records, as a table's do. */
	private static boolean table(List<byte[]> records) {
		for (byte[] r : records) {
			if (r.length < 4 || (Bytes.u32(r, 0) & 0xFFFFFFFFL) >= records.size()) {
				return false;
			}
		}
		return !records.isEmpty();
	}

	/** Each record's id: its own when every record has a different one, else its number. */
	private static String[] ids(List<byte[]> records) {
		String[] ids = new String[records.size()];
		Set<String> seen = new HashSet<>();
		boolean own = true;
		for (int r = 0; r < ids.length && own; r++) {
			String id = id(records.get(r));
			own = id != null && seen.add(id);
			ids[r] = id;
		}
		if (!own) {
			for (int r = 0; r < ids.length; r++) {
				ids[r] = String.format(Locale.ROOT, "%03d", r);
			}
		}
		return ids;
	}

	/** The record's ASCII id, or null when it has none there. */
	private static String id(byte[] record) {
		if (record.length < ID + ID_LENGTH) {
			return null;
		}
		int e = ID;
		while (e < ID + ID_LENGTH && record[e] != 0) {
			if (record[e] <= 0x20 || record[e] >= 0x7F) {
				return null;
			}
			e++;
		}
		return e == ID || e == ID + ID_LENGTH ? null : new String(record, ID, e - ID, StandardCharsets.US_ASCII);
	}

	/**
	 * The {start, end} of every run of text in {@code record} that ends in a NUL and reads as
	 * Japanese: for UTF-16, runs of whole units at even offsets, as long as every unit could be
	 * text; for Shift-JIS, from each byte after one that could not be text.
	 */
	private static List<int[]> candidates(byte[] record, boolean utf16) {
		List<int[]> out = new ArrayList<>();
		if (utf16) {
			int start = -1;
			for (int i = 0; i + 1 < record.length; i += 2) {
				int unit = Bytes.u16(record, i);
				if (unit == 0) {
					if (start >= 0 && japaneseUtf16(record, start, i)) {
						out.add(new int[] {start, i + 2});
					}
					start = -1;
				} else if (!plausible(unit)) {
					start = -1;
				} else if (start < 0) {
					start = i;
				}
			}
			return out;
		}
		int i = 0;
		while (i < record.length) {
			if (record[i] == 0) {
				i++;
				continue;
			}
			int end = i;
			while (end < record.length && record[end] != 0) {
				end++;
			}
			String s = end < record.length ? decode(record, i, end) : null;
			if (s != null && japanese(s)) {
				out.add(new int[] {i, end + 1});
				i = end + 1;
			} else {
				i++;
			}
		}
		return out;
	}

	/**
	 * Whether UTF-16 {@code record[start, end)} reads as Japanese: two or more units of kana or
	 * kanji, and not an ASCII id read two bytes at a time: every byte printable ASCII (the last
	 * unit's high byte its NUL when it has an odd length). Hiragana from ぁ to ゾ is all printable
	 * bytes too, but each unit's high byte is 0x30, where an id's vary.
	 */
	private static boolean japaneseUtf16(byte[] record, int start, int end) {
		int kana = 0;
		boolean ascii = true;
		boolean hiragana = true;
		for (int i = start; i < end; i += 2) {
			if (kanaOrKanji(Bytes.u16(record, i))) {
				kana++;
			}
			ascii &= printableAscii(record[i]) && (printableAscii(record[i + 1]) || i + 2 == end && record[i + 1] == 0);
			hiragana &= record[i + 1] == 0x30;
		}
		return kana >= 2 && (!ascii || hiragana);
	}

	/** Whether {@code s} could all be text and holds two or more kana or kanji. */
	private static boolean japanese(String s) {
		return s.chars().allMatch(Records::plausible) && s.chars().filter(Records::kanaOrKanji).count() >= 2;
	}

	private static String utf16(byte[] record, int offset) {
		int e = Bytes.utf16End(record, offset, record.length);
		String s = new String(record, offset, e - offset, StandardCharsets.UTF_16LE);
		return s.chars().allMatch(Records::plausible) ? s : null;
	}

	private static String shiftJis(byte[] record, int offset) {
		int e = offset;
		while (e < record.length && record[e] != 0) {
			e++;
		}
		String s = decode(record, offset, e);
		return s != null && s.chars().allMatch(Records::plausible) ? s : null;
	}

	/** {@code record[start, end)} as Shift-JIS, or null when it is not. */
	private static String decode(byte[] record, int start, int end) {
		CharsetDecoder decoder = SHIFT_JIS.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			return decoder.decode(ByteBuffer.wrap(record, start, end - start)).toString();
		} catch (CharacterCodingException notShiftJis) {
			return null;
		}
	}

	/** A character text could hold: ASCII, a line break, furigana marks, Japanese or its punctuation. */
	private static boolean plausible(int c) {
		return c >= 0x20 && c < 0x7F || c == '\n' || c >= 0x11 && c <= 0x13 || c >= 0x3000 && c <= 0x30FF
				|| c >= 0x4E00 && c <= 0x9FFF || c >= 0xFF01 && c <= 0xFF9F || c >= 0x2010 && c <= 0x266F;
	}

	private static boolean kanaOrKanji(int c) {
		return c >= 0x3041 && c <= 0x30FF || c >= 0x4E00 && c <= 0x9FFF;
	}

	private static boolean printableAscii(byte b) {
		return b >= 0x20 && b < 0x7F;
	}

	/** Each record's {offset, size}, or null when {@code d} is not a record file. */
	private static List<int[]> records(byte[] d) {
		if (d.length < 16 || !Bytes.magic(d, 0, "DARC")) {
			return null;
		}
		long count = Bytes.u32(d, 4) & 0xFFFFFFFFL;
		if (16 + count * 8 > d.length) {
			return null;
		}
		List<int[]> out = new ArrayList<>((int) count);
		for (int i = 0; i < count; i++) {
			long offset = Bytes.u32(d, 16 + 8 * i) & 0xFFFFFFFFL;
			long size = Bytes.u32(d, 20 + 8 * i) & 0xFFFFFFFFL;
			if (offset + size > d.length) {
				return null;
			}
			out.add(new int[] {(int) offset, (int) size});
		}
		return out;
	}
}
