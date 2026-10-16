package psaro.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleRomfs;

class ScriptTest {

	/**
	 * A scene's lines are its entries' text that holds Japanese, by entry number, up to
	 * {@code <end>}: a jump's label is not a line, and what memory was left after the end is not.
	 */
	@Test
	void linesAreReadUpToTheEnd() {
		byte[] file = SampleRomfs.script("prot1_out_pink1", List.of(
				new String[] {"<Talk>", "<chara02 w3 ef6>マフィンおばさーん！<p>"},
				new String[] {"<Jump>", "label_01"},
				new String[] {"<Talk>", "<chara03 h0 ef15>はにわになっても\n\u0011話\u0012はな\u0013せるから<p>"}), "ありがとう。<p>");
		assertTrue(Script.is(file));
		Map<String, String> lines = Script.read(file);
		assertEquals(List.of("000", "002"), List.copyOf(lines.keySet()));
		assertEquals("<chara02 w3 ef6>マフィンおばさーん！<p>", lines.get("000"));
		assertEquals("<chara03 h0 ef15>はにわになっても\n\u0011話\u0012はな\u0013せるから<p>", lines.get("002"));
	}

	@Test
	void aRecordFileOfAnotherShapeIsNotAScript() {
		byte[] items = SampleRomfs.records(List.of(SampleRomfs.record(0x100, 0, "rod00_00", Map.of(0x24, SampleRomfs.utf16("つえ")))));
		assertFalse(Script.is(items));
		assertThrows(IllegalArgumentException.class, () -> Script.read(items));
	}

	/** A line written back fills its entry's text, the rest cleared; one longer than the entry holds is refused. */
	@Test
	void writesALineInItsEntry() {
		byte[] file = SampleRomfs.script("prot1_out_pink1", List.<String[]>of(
				new String[] {"<Talk>", "<chara02 w3 ef6>マフィンおばさーん！<p>"}), "ありがとう。<p>");
		byte[] out = Script.write(file, Map.of("000", "<chara02 w3 ef6>Auntie Muffin!<p>"));
		byte[] entry = Records.read(out).get(0);
		int text = 0x60 + 0x44;
		assertEquals("<chara02 w3 ef6>Auntie Muffin!<p>", Bytes.ascii(entry, text));
		assertEquals(0, entry[text + 0xFF]);
		assertEquals(file.length, out.length);
		assertThrows(IllegalArgumentException.class, () -> Script.write(file, Map.of("000", "x".repeat(0x100))));
	}
}
