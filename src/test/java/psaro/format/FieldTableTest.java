package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleCpk;

class FieldTableTest {

	/** One text field: keyed by record number; a string with no kana or kanji (a dash) is not text. */
	@Test
	void readsATableOfOneTextField() {
		byte[] units = SampleCpk.fieldTable(16, new int[] {4},
				List.of(new String[] {"ナシ"}, new String[] {"ガンダム"}, new String[] {"―"}));
		assertTrue(FieldTable.is(units));
		assertEquals(Map.of("000", "ナシ", "001", "ガンダム"), FieldTable.strings(units));
	}

	/** Several text fields: each string keyed by its record and the field's offset, a ruler of □■ left out. */
	@Test
	void readsATableOfSeveralTextFields() {
		byte[] messages = SampleCpk.fieldTable(16, new int[] {8, 12},
				List.of(new String[] {"や………やったな！", "□□□□■"}, new String[] {"しまった！\nこんなんじゃあ………！", "当たった！？"}));
		assertEquals(List.of("000:0x8", "001:0x8", "001:0xc"), List.copyOf(FieldTable.strings(messages).keySet()));
		assertEquals("しまった！\nこんなんじゃあ………！", FieldTable.strings(messages).get("001:0x8"));
	}

	/**
	 * English goes after the table's own text, the field pointed at it; a record sharing the old
	 * string keeps it, and the same English twice is stored once.
	 */
	@Test
	void writesEnglishAfterTheTablesText() {
		byte[] units = SampleCpk.fieldTable(16, new int[] {4},
				List.of(new String[] {"ナシ"}, new String[] {"ガンダム"}, new String[] {"ガンキャノン"}));
		byte[] out = FieldTable.write(units, Map.of("001", "Gundam", "002", "Gundam", "000", "ナシ"));
		assertTrue(FieldTable.is(out));
		assertEquals(units.length + "Gundam\0".length(), out.length);
		int textAt = Bytes.u32(out, 12);
		assertArrayEquals(Arrays.copyOfRange(units, textAt, units.length), Arrays.copyOfRange(out, textAt, units.length),
				"the table's own text is kept");
		for (int r = 1; r <= 2; r++) {
			int at = textAt + Bytes.u32(out, Bytes.u32(out, 8) + r * 16 + 4);
			assertEquals("Gundam", ShiftJis.terminated(out, at, out.length));
		}
		assertEquals(Map.of("000", "ナシ"), FieldTable.strings(out));
	}

	@Test
	void textShiftJisCannotHoldIsRefused() {
		byte[] units = SampleCpk.fieldTable(16, new int[] {4}, List.<String[]>of(new String[] {"ガンダム"}));
		assertThrows(IllegalArgumentException.class, () -> FieldTable.write(units, Map.of("000", "Gundám")));
	}

	/** A header that does not add up is no table: a field past the record's end, or records that miss the text. */
	@Test
	void aHeaderThatDoesNotAddUpIsNoTable() {
		byte[] good = SampleCpk.fieldTable(8, new int[] {4}, List.<String[]>of(new String[] {"ナシ"}));
		byte[] fieldOutside = good.clone();
		fieldOutside[20] = 8;
		byte[] shortRecords = good.clone();
		shortRecords[0] = 4;
		assertFalse(FieldTable.is(fieldOutside));
		assertFalse(FieldTable.is(shortRecords));
		assertFalse(FieldTable.is(new byte[8]));
		assertThrows(IllegalArgumentException.class, () -> FieldTable.strings(shortRecords));
	}
}
