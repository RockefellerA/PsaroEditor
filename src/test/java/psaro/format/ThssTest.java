package psaro.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleCpk;

class ThssTest {

	/**
	 * Every string a pointer leads to, once, by where it is; not a list of pointers that reads as
	 * a kanji, nor a lone half-width byte.
	 */
	@Test
	void readsTheStringsPointersLeadTo() {
		byte[][] db = SampleCpk.thss(List.of("ガンダム", "$s20バカな！？やられただと$s100………$s20！"));
		assertTrue(Thss.is(db[0], db[1]));
		Map<String, String> strings = Thss.strings(db[0], db[1]);
		assertEquals(List.of("ガンダム", "$s20バカな！？やられただと$s100………$s20！"), List.copyOf(strings.values()));
		// the strings end the file
		int first = db[0].length - stored("ガンダム") - stored("$s20バカな！？やられただと$s100………$s20！");
		assertEquals(String.format("0x%x", first), strings.keySet().iterator().next());
	}

	/**
	 * A string that fits stays where it is, the rest of its place cleared; a longer one goes after
	 * the data with both pointers that led to it pointed there; the pointer list does not change.
	 */
	@Test
	void writesStringsInPlaceOrAfterTheData() {
		byte[][] db = SampleCpk.thss(List.of("ガンダム", "逃げ遅れるなよ！"));
		Map<String, String> keys = Thss.strings(db[0], db[1]);
		String gundam = keys.keySet().stream().toList().get(0);
		String line = keys.keySet().stream().toList().get(1);
		byte[] out = Thss.write(db[0], db[1], Map.of(gundam, "Gundam Mk-II", line, "Don't fall"));
		assertTrue(Thss.is(out, db[1]));
		int lineAt = Integer.parseInt(line.substring(2), 16);
		assertEquals("Don't fall", ShiftJis.terminated(out, lineAt, out.length), "fits in its own place");
		assertEquals(0, out[lineAt + "Don't fall".length() + 1]);
		assertEquals(db[0].length + "Gundam Mk-II\0".length(), out.length);
		// the first pointer and the second one to the same string
		for (int site : new int[] {0x10, 0x10 + 4 * 2}) {
			assertEquals(db[0].length, Bytes.u32(out, site));
		}
		assertEquals("Gundam Mk-II", ShiftJis.terminated(out, db[0].length, out.length));
		assertThrows(IllegalArgumentException.class, () -> Thss.write(db[0], db[1], Map.of(gundam, "Gundám")));
	}

	/** A pointer outside the data file, or the wrong magic, is not a database. */
	@Test
	void aPointerOutsideTheDataIsNoDatabase() {
		byte[][] db = SampleCpk.thss(List.of("ガンダム"));
		byte[] cut = Arrays.copyOf(db[0], 0x14);
		assertFalse(Thss.is(cut, db[1]));
		assertFalse(Thss.is(db[1], db[0]));
		assertThrows(IllegalArgumentException.class, () -> Thss.strings(cut, db[1]));
	}

	/** How many bytes {@code s} takes in the file: Shift-JIS and its NUL. */
	private static int stored(String s) {
		return (s + "\0").getBytes(ShiftJis.CHARSET).length;
	}
}
