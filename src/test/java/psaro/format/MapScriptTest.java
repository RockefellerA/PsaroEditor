package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleCpk;

class MapScriptTest {

	/** Commands as the game's scripts have them: a face shown, a line, a narration, a jump over a flag, the end. */
	private static final byte[] FACE = {0x1B, 0x0C, 0x00, 0x01, 0x03};
	private static final byte[] PAUSE = {0x04, 0x1E, 0x1C};
	private static final byte[] FLAG = {0x0F, 0x01, 0x00, 0x00, 0x00, 0x01};
	private static final byte[] END = {0x01};

	/** A conditional jump (0x06) to {@code target}. */
	private static byte[] jumpTo(int target) {
		return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0x06).putInt(0xFB).put((byte) 1).putInt(target).array();
	}

	/** A narration (0x24), shaped as a spoken line is. */
	private static byte[] narration(String text) {
		byte[] line = SampleCpk.mapLine(text);
		line[0] = 0x24;
		return line;
	}

	/** {@code face, line, pause, jump past the flag, flag, narration, end}, and where the jump's target is. */
	private static byte[] script(String line) {
		byte[] spoken = SampleCpk.mapLine(line);
		byte[] said = narration("………それは");
		int target = FACE.length + spoken.length + PAUSE.length + 10 + FLAG.length;
		return SampleCpk.concat(FACE, spoken, PAUSE, jumpTo(target), FLAG, said, END);
	}

	/**
	 * Every line and narration, numbered in order, a backslash-n as a line break, without the %
	 * and NUL; a line of only ……… counts.
	 */
	@Test
	void readsTheLinesAmongTheCommands() {
		Map<String, String> lines = MapScript.lines(script("情報通りだと、\nそろそろ接触できる頃だな。"));
		assertEquals(List.of("00", "01"), List.copyOf(lines.keySet()));
		assertEquals("情報通りだと、\nそろそろ接触できる頃だな。", lines.get("00"));
		assertEquals("………それは", lines.get("01"));
		assertEquals(Map.of("00", "………"), MapScript.lines(SampleCpk.concat(SampleCpk.mapLine("………"), END)));
	}

	/** A file that does not read as commands from its first byte to its last is no script, whatever it holds. */
	@Test
	void aFileThatIsNotCommandsHasNoLines() {
		assertTrue(MapScript.lines(SampleCpk.concat(new byte[] {(byte) 0xEE}, SampleCpk.mapLine("ハッ！"))).isEmpty());
		assertTrue(MapScript.lines(SampleCpk.concat(new byte[] {0x1B, 0x0C}, SampleCpk.mapLine("ハッ！"))).isEmpty());
		assertNull(MapScript.commands(new byte[] {0x06, 0x01}));
	}

	/**
	 * A longer line pushes what follows it along, and the jump after it is moved to lead to the
	 * same command; the narration not given English is kept as it was.
	 */
	@Test
	void aLongerLineMovesTheJumpsAfterIt() {
		byte[] d = script("ハッ！");
		byte[] out = MapScript.write(d, Map.of("00", "What was that?!\nIt can't be..."));
		byte[] expected = script("What was that?!\nIt can't be...");
		assertArrayEquals(expected, out);
		List<int[]> commands = MapScript.commands(out);
		assertNotNull(commands);
		// face, line, three pauses, the jump, the flag, the narration it leads to
		int jump = commands.get(5)[0];
		assertEquals(0x06, out[jump]);
		assertEquals(commands.get(7)[0], Bytes.u32(out, jump + 6), "the jump leads past the flag still");
		assertEquals("………それは", MapScript.lines(out).get("00"), "the English line is not Japanese, the narration is");
	}

	/** A line unchanged, or a key no line has, leaves the file as it was. */
	@Test
	void textAsItWasChangesNothing() {
		byte[] d = script("ハッ！");
		assertArrayEquals(d, MapScript.write(d, Map.of("00", "ハッ！", "07", "Nothing")));
	}

	/** Text a line cannot hold is refused: past the one-byte length, or not Shift-JIS. */
	@Test
	void textALineCannotHoldIsRefused() {
		byte[] d = script("ハッ！");
		assertNull(MapScript.problem("x".repeat(MapScript.MAX_TEXT)));
		assertNotNull(MapScript.problem("x".repeat(MapScript.MAX_TEXT + 1)));
		assertNotNull(MapScript.problem("café"));
		assertThrows(IllegalArgumentException.class, () -> MapScript.write(d, Map.of("00", "x".repeat(MapScript.MAX_TEXT + 1))));
		assertThrows(IllegalArgumentException.class, () -> MapScript.write(d, Map.of("00", "café")));
	}
}
