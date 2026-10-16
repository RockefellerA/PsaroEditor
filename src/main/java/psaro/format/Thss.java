package psaro.format;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * A {@code THSS} data file and its {@code FHSS} pointer list, as SD Gundam G Generation 3D keeps
 * its database ({@code data/foundation/database.dat} and {@code database.ofs}): records and the
 * strings they point to, in one block the game loads and then fixes up the pointers of.
 *
 * <pre>
 * .dat: "THSS", u32, u32, a 4-byte tag ("1DBD"), then the records and their strings, mixed
 * .ofs: "FHSS", u32 pointers, then per pointer u32 where it is in the .dat
 * pointer: u32 an offset from the .dat's start
 * </pre>
 *
 * Pointers lead to records and lists as well as to strings, so a string is found by what is
 * there: Shift-JIS up to a NUL holding kana, kanji or a full-width letter (not only half-width
 * katakana, which a lone byte of a number reads as), with no pointer inside it, which a list of
 * pointers that reads as a kanji has.
 *
 * <p>The text is every such string, once however many pointers lead to it, keyed by where it is
 * ({@code 0x48b68}), in file order. Battle lines hold codes the game acts on: {@code $s100} sets
 * how fast the text runs, {@code $(MARU"…")} draws a button.
 */
public final class Thss {

	private static final int POINTERS = 8;

	private Thss() {
	}

	/** Whether {@code dat} and {@code ofs} are a data file and its pointer list, every pointer inside the data. */
	public static boolean is(byte[] dat, byte[] ofs) {
		if (!Bytes.magic(dat, 0, "THSS") || !Bytes.magic(ofs, 0, "FHSS") || ofs.length < POINTERS) {
			return false;
		}
		long count = Bytes.u32(ofs, 4) & 0xFFFFFFFFL;
		if (POINTERS + 4 * count > ofs.length) {
			return false;
		}
		for (int i = 0; i < count; i++) {
			long at = Bytes.u32(ofs, POINTERS + 4 * i) & 0xFFFFFFFFL;
			if (at + 4 > dat.length) {
				return false;
			}
		}
		return true;
	}

	/** Every string a pointer leads to, by where it is. */
	public static LinkedHashMap<String, String> strings(byte[] dat, byte[] ofs) {
		if (!is(dat, ofs)) {
			throw new IllegalArgumentException("not a THSS data file and pointer list");
		}
		int count = Bytes.u32(ofs, 4);
		BitSet pointers = new BitSet(dat.length);
		TreeSet<Long> targets = new TreeSet<>();
		for (int i = 0; i < count; i++) {
			int at = Bytes.u32(ofs, POINTERS + 4 * i);
			pointers.set(at, at + 4);
			targets.add(Bytes.u32(dat, at) & 0xFFFFFFFFL);
		}
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (long target : targets) {
			if (target >= dat.length) {
				continue;
			}
			int at = (int) target;
			String s = ShiftJis.terminated(dat, at, dat.length);
			if (s == null || !text(s)) {
				continue;
			}
			int end = at;
			while (dat[end] != 0) {
				end++;
			}
			int inside = pointers.nextSetBit(at);
			if (inside < 0 || inside > end) {
				out.put(String.format(Locale.ROOT, "0x%x", at), s);
			}
		}
		return out;
	}

	/** Whether {@code text} can be written into the data: Shift-JIS. Null when it can, else why not. */
	public static String problem(String text) {
		String missing = ShiftJis.unencodable(text);
		return missing == null ? null : "Shift-JIS has no " + missing;
	}

	/**
	 * {@code dat} with each string {@code texts} has a different text for (by key) holding it: in
	 * the string's own place when it fits there, the rest of that filled with NULs; otherwise after
	 * the data, with every pointer that led to the string pointed there. The pointer list is
	 * unchanged, since no pointer moves. Refused when a text is not Shift-JIS.
	 */
	public static byte[] write(byte[] dat, byte[] ofs, Map<String, String> texts) {
		Map<String, String> current = strings(dat, ofs);
		byte[] out = dat.clone();
		ByteArrayOutputStream added = new ByteArrayOutputStream();
		Map<Integer, Integer> moved = new HashMap<>();
		for (Map.Entry<String, String> s : current.entrySet()) {
			String text = texts.get(s.getKey());
			if (text == null || text.equals(s.getValue())) {
				continue;
			}
			String problem = problem(text);
			if (problem != null) {
				throw new IllegalArgumentException(s.getKey() + ": " + problem);
			}
			int at = Integer.parseInt(s.getKey().substring(2), 16);
			int end = at;
			while (dat[end] != 0) {
				end++;
			}
			byte[] stored = ShiftJis.encode(text);
			if (stored.length <= end - at) {
				Arrays.fill(out, at, end, (byte) 0);
				System.arraycopy(stored, 0, out, at, stored.length);
			} else {
				moved.put(at, dat.length + added.size());
				added.writeBytes(stored);
				added.write(0);
			}
		}
		int count = Bytes.u32(ofs, 4);
		for (int i = 0; i < count; i++) {
			int p = Bytes.u32(ofs, POINTERS + 4 * i);
			Integer now = moved.get(Bytes.u32(dat, p));
			if (now != null) {
				out[p] = (byte) (int) now;
				out[p + 1] = (byte) (now >> 8);
				out[p + 2] = (byte) (now >> 16);
				out[p + 3] = (byte) (now >> 24);
			}
		}
		ByteArrayOutputStream result = new ByteArrayOutputStream(out.length + added.size());
		result.writeBytes(out);
		result.writeBytes(added.toByteArray());
		return result.toByteArray();
	}

	/** Whether {@code s} holds kana, kanji or a full-width form, not only half-width katakana. */
	private static boolean text(String s) {
		return s.codePoints().anyMatch(c -> c >= 0x3040 && c < 0xA000 || c >= 0xFF00 && c < 0xFF61 || c >= 0xFFA0 && c < 0xFFF0);
	}
}
