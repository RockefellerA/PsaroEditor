package psaro.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleRomfs;

class RecordsTest {

	/** 0.3f, which as UTF-16 reads as a kanji. */
	private static final byte[] FLOAT = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.3f).array();

	/**
	 * Items as a game keeps them: a UTF-16 name, a description after a float, a Shift-JIS
	 * category. The third name is all hiragana whose bytes are all printable ASCII, which alone
	 * could be an id; its field is found from the others.
	 */
	private static byte[] items() {
		String[] names = {"はじまりのつえ", "ほしのつえ", "おかね", "きんのつえ"};
		List<byte[]> records = new ArrayList<>();
		for (int i = 0; i < names.length; i++) {
			records.add(SampleRomfs.record(0x100, i, "rod00_0" + i, Map.of(
					0x24, SampleRomfs.utf16(names[i]),
					0x80, FLOAT,
					0x84, SampleRomfs.utf16("まほう\u0011初心者\u0012しょしんしゃ\u0013でも使えるつえ。\nその" + i),
					0xD0, SampleRomfs.shiftJis("武器（つえ）"))));
		}
		return SampleRomfs.records(records);
	}

	@Test
	void textFieldsAreFoundAcrossTheRecordsAndKeyedByRecordAndOffset() {
		byte[] file = items();
		assertTrue(Records.is(file));
		assertEquals(List.of(new Records.Column(0x24, true), new Records.Column(0x84, true), new Records.Column(0xD0, false)),
				Records.columns(Records.read(file)));
		Map<String, String> strings = Records.strings(file);
		assertEquals(12, strings.size());
		assertEquals(List.of("rod00_00:0x24", "rod00_00:0x84", "rod00_00:0xd0"), List.copyOf(strings.keySet()).subList(0, 3));
		assertEquals("おかね", strings.get("rod00_02:0x24"));
		assertEquals("まほう\u0011初心者\u0012しょしんしゃ\u0013でも使えるつえ。\nその1", strings.get("rod00_01:0x84"));
		assertEquals("武器（つえ）", strings.get("rod00_03:0xd0"));
	}

	@Test
	void recordsWithoutIdsOfTheirOwnAreKeyedByNumber() {
		List<byte[]> records = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			records.add(SampleRomfs.record(0x60, i, "LIMIT", Map.of(0x40, SampleRomfs.shiftJis("街の外に出れるか" + i))));
		}
		assertEquals(List.of("000:0x40", "001:0x40", "002:0x40"), List.copyOf(Records.strings(SampleRomfs.records(records)).keySet()));
	}

	/**
	 * A script folder's {@code index.dat}: scene names padded with leftover memory, which holds
	 * pieces of dialogue. Its records do not start with their index, so it is no table of text.
	 */
	@Test
	void aFileOfPaddedNamesHasNoText() {
		List<byte[]> records = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			byte[] r = new byte[0x40];
			byte[] name = ("prot1_out_pink" + i + "\0").getBytes(StandardCharsets.US_ASCII);
			System.arraycopy(name, 0, r, 0, name.length);
			byte[] leftover = SampleRomfs.shiftJis("ありがとう。<p>");
			System.arraycopy(leftover, 0, r, name.length, leftover.length);
			records.add(r);
		}
		byte[] file = SampleRomfs.records(records);
		assertTrue(Records.is(file));
		assertTrue(Records.strings(file).isEmpty());
	}

	@Test
	void otherFilesAreNotRecordFiles() {
		assertFalse(Records.is("darc".getBytes(StandardCharsets.US_ASCII)));
		byte[] truncated = SampleRomfs.records(List.of(new byte[0x40]));
		assertFalse(Records.is(java.util.Arrays.copyOf(truncated, truncated.length - 1)));
		assertThrows(IllegalArgumentException.class, () -> Records.read(new byte[16]));
	}

	/**
	 * English goes in each field, UTF-16 or Shift-JIS as the field is, using the zeros after the
	 * string in every record as room; a field with a number right after it in some record holds
	 * no more than reaches that number.
	 */
	@Test
	void writesEachFieldWithinItsRoom() {
		byte[] d = SampleRomfs.records(List.of(
				SampleRomfs.record(0x80, 0, "rod00_00", Map.of(0x24, SampleRomfs.utf16("はじまりのつえ"), 0x50, SampleRomfs.shiftJis("つえ"),
						0x60, new byte[] {7})),
				SampleRomfs.record(0x80, 1, "rod00_01", Map.of(0x24, SampleRomfs.utf16("ほしのつえ"), 0x50, SampleRomfs.shiftJis("ほし"),
						0x60, new byte[] {7}))));
		byte[] out = Records.write(d, Map.of("rod00_00:0x24", "Starter Staff", "rod00_01:0x50", "Star rod"));
		List<byte[]> records = Records.read(out);
		assertEquals("Starter Staff", Records.string(records.get(0), new Records.Column(0x24, true)));
		assertEquals("Star rod", Records.string(records.get(1), new Records.Column(0x50, false)));
		assertEquals("ほしのつえ", Records.string(records.get(1), new Records.Column(0x24, true)));
		assertEquals(7, records.get(1)[0x60], "the number after the field is kept");
		assertThrows(IllegalArgumentException.class, () -> Records.write(d, Map.of("rod00_00:0x50", "A very long staff")));
		assertThrows(IllegalArgumentException.class, () -> Records.write(d, Map.of("rod00_00:0x50", "Bâton")));
	}
}
