package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleRomfs;

class MbbTest {

	private static final Glyphs GLYPHS = Glyphs.lostHeroes();

	/** Every string that holds anything, by number; a number with none, or an empty string, is left out. */
	@Test
	void readsEachStringByNumber() {
		byte[] file = SampleRomfs.mbb(List.of(Arrays.asList("はい", null, "", "いいえ\nです{8074}")), false);
		assertTrue(Mbb.is(file));
		assertEquals(Map.of("000", "はい", "003", "いいえ\nです{8074}"), Mbb.read(file, GLYPHS));
		assertEquals(List.of("000", "003"), List.copyOf(Mbb.read(file, GLYPHS).keySet()));
	}

	/** In a file of several tables, a string's key starts with its table's number. */
	@Test
	void severalTablesKeyByTable() {
		byte[] file = SampleRomfs.mbb(List.of(List.of("はい"), List.of("いいえ", "Yes")), false);
		assertEquals(List.of("0:000", "1:000", "1:001"), List.copyOf(Mbb.read(file, GLYPHS).keySet()));
		assertEquals("Yes", Mbb.read(file, GLYPHS).get("1:001"));
	}

	@Test
	void aDataTableIsNotAMessageTableFile() {
		byte[] tbb = SampleRomfs.mbb(List.of(List.of("はい")), false);
		System.arraycopy("TBL1".getBytes(), 0, tbb, 0x20, 4);
		assertFalse(Mbb.is(tbb));
		assertThrows(IllegalArgumentException.class, () -> Mbb.read(tbb, GLYPHS));
	}

	/** A file none of whose strings change is written back as it was. */
	@Test
	void anUnchangedFileIsWrittenAsItWas() {
		byte[] file = SampleRomfs.mbb(List.of(List.of("はい", "いいえ")), true);
		assertSame(file, Mbb.write(file, Mbb.read(file, GLYPHS), GLYPHS));
		assertSame(file, Mbb.write(file, Map.of("009", "Yes"), GLYPHS));
	}

	/**
	 * A changed string holds its new text and the rest theirs; the strings stay in the order they
	 * were kept, and each table keeps its first word.
	 */
	@Test
	void writesAChangedStringKeepingTheLayout() {
		byte[] file = SampleRomfs.mbb(List.of(List.of("はい", "いいえ", "もう")), true);
		// a table's first word, which is not 0 in a few of the game's files
		int base = 0x20 + 0x10;
		file[base] = 3;
		assertArrayEquals(file, Mbb.rebuild(file));
		byte[] out = Mbb.write(file, Map.of("001", "No thanks, maybe later"), GLYPHS);
		assertEquals(Map.of("000", "はい", "001", "No thanks, maybe later", "002", "もう"), Mbb.read(out, GLYPHS));
		assertEquals(3, out[base]);
		int[] offsets = {Bytes.u32(out, base + 4), Bytes.u32(out, base + 8), Bytes.u32(out, base + 12)};
		assertTrue(offsets[2] < offsets[1] && offsets[1] < offsets[0], Arrays.toString(offsets));
		assertEquals(out.length, Bytes.u32(out, 12));
	}

	@Test
	void refusesTextTheFontCannotShow() {
		byte[] file = SampleRomfs.mbb(List.of(List.of("はい")), false);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Mbb.write(file, Map.of("000", "☺"), GLYPHS));
		assertTrue(e.getMessage().startsWith("000: "), e.getMessage());
	}
}
