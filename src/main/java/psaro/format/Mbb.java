package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lost Heroes' {@code *.mbb} message tables: a {@code TBB1} file of {@code MTBL} tables of
 * strings, each character a code into the font ({@link Glyphs}).
 *
 * <pre>
 * file:   "TBB1", u32 0x10, u32 table count, u32 file size, u32 offset of each table; 16-aligned
 * table:  "MTBL", u32 0x10, u32 size after these 16 bytes, u32 string count, then from there:
 *         u32 (0 in most), u32 offset of each string from there (0 for none); 16-aligned
 * string: u16 codes ending in 0, padded to 16 bytes
 * </pre>
 *
 * Strings are kept in the order of their offsets, which is not always their numbers' order, and
 * a few files keep a string no number points to. A file whose strings all stay as they are is
 * written back as it was; one with a string changed is laid out afresh, its strings in the order
 * they were kept.
 *
 * <p>The text is every string that holds anything, keyed by its number ({@code 004}), and by its
 * table's number first ({@code 1:004}) in a file of more than one table.
 */
public final class Mbb {

	private static final String MAGIC = "TBB1";
	private static final String TABLE = "MTBL";
	private static final int HEADER = 0x10;
	private static final int ALIGN = 16;

	/**
	 * A table: its first word, and each string's offset (0 for none), codes, and whether it was
	 * changed, so has a place of its own rather than one it shared.
	 */
	private record Table(int first, int[] offsets, int[][] strings, boolean[] fresh) {
	}

	private Mbb() {
	}

	/** Whether {@code d} is a message table file: {@code TBB1} holding {@code MTBL} tables, at least one. */
	public static boolean is(byte[] d) {
		if (d.length < HEADER || !Bytes.magic(d, 0, MAGIC) || Bytes.u32(d, 4) != HEADER) {
			return false;
		}
		int count = Bytes.u32(d, 8);
		if (count < 1 || HEADER + count * 4 > d.length) {
			return false;
		}
		for (int i = 0; i < count; i++) {
			int at = Bytes.u32(d, HEADER + i * 4);
			if (at < 0 || !Bytes.magic(d, at, TABLE)) {
				return false;
			}
		}
		return true;
	}

	/** Every string that holds anything, in order of number. */
	public static LinkedHashMap<String, String> read(byte[] d, Glyphs glyphs) {
		List<Table> tables = tables(d);
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (int t = 0; t < tables.size(); t++) {
			int[][] strings = tables.get(t).strings();
			for (int s = 0; s < strings.length; s++) {
				if (strings[s] != null && strings[s].length > 0) {
					out.put(key(tables.size(), t, s), glyphs.decode(strings[s]));
				}
			}
		}
		return out;
	}

	/**
	 * {@code d} with each string {@code texts} has a different text for (by key) holding it.
	 * Refused when a text has a character the font has not.
	 */
	public static byte[] write(byte[] d, Map<String, String> texts, Glyphs glyphs) {
		Map<String, String> current = read(d, glyphs);
		List<Table> tables = tables(d);
		boolean changed = false;
		for (int t = 0; t < tables.size(); t++) {
			int[][] strings = tables.get(t).strings();
			for (int s = 0; s < strings.length; s++) {
				String key = key(tables.size(), t, s);
				String text = texts.get(key);
				if (text == null || !current.containsKey(key) || text.equals(current.get(key))) {
					continue;
				}
				try {
					strings[s] = glyphs.encode(text);
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException(key + ": " + e.getMessage());
				}
				tables.get(t).fresh()[s] = true;
				changed = true;
			}
		}
		return changed ? write(tables) : d;
	}

	/** {@code d} laid out afresh, as a change would have it, for tests to hold against the game's own files. */
	static byte[] rebuild(byte[] d) {
		return write(tables(d));
	}

	private static String key(int tables, int table, int string) {
		String key = String.format(Locale.ROOT, "%03d", string);
		return tables > 1 ? table + ":" + key : key;
	}

	private static List<Table> tables(byte[] d) {
		if (!is(d)) {
			throw new IllegalArgumentException("not a message table file");
		}
		int count = Bytes.u32(d, 8);
		List<Table> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int at = Bytes.u32(d, HEADER + i * 4);
			int base = at + Bytes.u32(d, at + 4);
			int strings = Bytes.u32(d, at + 12);
			int[] offsets = new int[strings];
			int[][] codes = new int[strings][];
			for (int s = 0; s < strings; s++) {
				offsets[s] = Bytes.u32(d, base + 4 + s * 4);
				if (offsets[s] != 0) {
					codes[s] = string(d, base + offsets[s]);
				}
			}
			out.add(new Table(Bytes.u32(d, base), offsets, codes, new boolean[strings]));
		}
		return out;
	}

	private static int[] string(byte[] d, int at) {
		int end = at;
		while (Bytes.u16(d, end) != 0) {
			end += 2;
		}
		int[] out = new int[(end - at) / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = Bytes.u16(d, at + i * 2);
		}
		return out;
	}

	/** The file of {@code tables}, each string where its offset puts it among the rest, those sharing one kept together. */
	private static byte[] write(List<Table> tables) {
		Buf out = new Buf(HEADER + tables.size() * 4);
		out.put(MAGIC.getBytes(StandardCharsets.US_ASCII)).putInt(HEADER).putInt(tables.size()).putInt(0);
		for (int i = 0; i < tables.size(); i++) {
			out.putInt(0);
		}
		out.align(ALIGN);
		for (int t = 0; t < tables.size(); t++) {
			out.setInt(HEADER + t * 4, out.size());
			byte[] table = table(tables.get(t));
			out.put(TABLE.getBytes(StandardCharsets.US_ASCII)).putInt(HEADER).putInt(table.length)
					.putInt(tables.get(t).strings().length).put(table);
		}
		out.setInt(12, out.size());
		return out.toArray();
	}

	private static byte[] table(Table table) {
		int[] offsets = table.offsets();
		int[][] strings = table.strings();
		Buf out = new Buf(4 + offsets.length * 4);
		out.putInt(table.first());
		for (int i = 0; i < offsets.length; i++) {
			out.putInt(0);
		}
		out.align(ALIGN);
		// in the order they were kept, numbers that shared a string sharing it still
		Integer[] order = new Integer[offsets.length];
		Arrays.setAll(order, i -> i);
		Arrays.sort(order, Comparator.comparingInt((Integer i) -> offsets[i]).thenComparingInt(i -> i));
		Map<Integer, Integer> placed = new HashMap<>();
		for (int i : order) {
			if (strings[i] == null) {
				continue;
			}
			Integer shared = table.fresh()[i] ? null : placed.get(offsets[i]);
			int at = shared != null ? shared : out.size();
			if (shared == null) {
				for (int code : strings[i]) {
					out.putShort(code);
				}
				out.putShort(0).align(ALIGN);
				if (!table.fresh()[i]) {
					placed.put(offsets[i], at);
				}
			}
			out.setInt(4 + i * 4, at);
		}
		return out.toArray();
	}
}
