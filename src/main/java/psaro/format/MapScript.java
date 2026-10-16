package psaro.format;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SD Gundam G Generation 3D's event scripts ({@code suzuki/mapscript/<stage>/ev000.bin}):
 * bytecode run on a stage's map, its lines of dialogue among the commands.
 *
 * <pre>
 * command:  u8 opcode, then operands of a fixed length per opcode ({@link #LENGTH})
 * a line:   u8 0x1D (someone speaks) or 0x24 (narration), u8 its length, then that many bytes:
 *           Shift-JIS text, "%", NUL
 * a label:  u8 0x0B, u8 its length, then that many bytes: a name, NUL
 * jumps:    0x06 (u32 at +6), 0x07 (+1), 0x0E (+5), 0x10 (+5), 0x11 (+9) hold an offset in the
 *           file of the command to go on from
 * </pre>
 *
 * The lengths were worked out from the game's own scripts, each of which they read from its first
 * byte to exactly its last, and the jumps from their offsets, which always fall on a command; a
 * file they do not read so is not a script, and has no lines. A stage's other scripts
 * ({@code turn.bin}, {@code action_end.bin}) number their commands differently and hold no lines.
 *
 * <p>The text is every line holding Japanese (or only the {@code ………} of someone saying nothing),
 * keyed by its number in the file ({@code 03}), its line breaks (a backslash and {@code n} in the
 * file) as {@code \n}, without the {@code %} and NUL that end it. A line written back may be
 * longer or shorter, up to {@link #MAX_TEXT} bytes; the jumps are moved with what follows it.
 */
public final class MapScript {

	/** A command's length; {@link #PREFIXED} for one whose second byte is the length of what follows. */
	private static final int[] LENGTH = new int[256];
	private static final int PREFIXED = -1;
	private static final int SPEAKS = 0x1D;
	private static final int NARRATES = 0x24;
	/** Where in each jump the offset is. */
	private static final Map<Integer, Integer> JUMPS = Map.of(0x06, 6, 0x07, 1, 0x0E, 5, 0x10, 5, 0x11, 9);
	/** The most bytes a line's text can be: its length is one byte, which counts the % and NUL too. */
	public static final int MAX_TEXT = 0xFF - 2;

	static {
		lengths(1, 0x01, 0x04, 0x1C, 0x1E, 0x26, 0x30, 0x36, 0x38, 0x39, 0x3A, 0x3B, 0x4E, 0x50, 0x51);
		lengths(2, 0x09, 0x13, 0x18, 0x37, 0x42, 0x4C, 0x52, 0x53);
		lengths(3, 0x03, 0x16, 0x17, 0x19, 0x1A, 0x22, 0x28, 0x2C, 0x2D, 0x31, 0x41, 0x4A);
		lengths(4, 0x1F, 0x3C, 0x4B);
		lengths(5, 0x07, 0x08, 0x0A, 0x0C, 0x0D, 0x1B, 0x35, 0x3D, 0x3E, 0x3F, 0x45, 0x47, 0x48, 0x49, 0x4F);
		lengths(6, 0x05, 0x0F);
		lengths(7, 0x12, 0x29, 0x2A, 0x34, 0x44);
		lengths(9, 0x0E, 0x10, 0x23, 0x2E, 0x2F, 0x32, 0x46);
		lengths(10, 0x06);
		lengths(13, 0x11, 0x2B);
		lengths(PREFIXED, 0x0B, SPEAKS, NARRATES);
	}

	private static void lengths(int length, int... opcodes) {
		for (int op : opcodes) {
			LENGTH[op] = length;
		}
	}

	private MapScript() {
	}

	/** Each command's {start, length}, in order, or null when {@code d} does not read as commands to its end. */
	static List<int[]> commands(byte[] d) {
		List<int[]> out = new ArrayList<>();
		int i = 0;
		while (i < d.length) {
			int length = LENGTH[d[i] & 0xFF];
			if (length == PREFIXED) {
				length = i + 1 < d.length ? 2 + (d[i + 1] & 0xFF) : 0;
			}
			if (length == 0 || i + length > d.length) {
				return null;
			}
			out.add(new int[] {i, length});
			i += length;
		}
		return out;
	}

	/** Every line of dialogue in {@code d}, in file order; none when it is not a script or holds none. */
	public static LinkedHashMap<String, String> lines(byte[] d) {
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		List<int[]> commands = commands(d);
		if (commands == null) {
			return out;
		}
		for (int[] c : commands) {
			String text = line(d, c);
			if (text != null) {
				out.put(key(out.size()), text.replace("\\n", "\n"));
			}
		}
		return out;
	}

	/** Whether {@code text} can be a line: Shift-JIS, no longer than {@link #MAX_TEXT} bytes. Null when it can, else why not. */
	public static String problem(String text) {
		String missing = ShiftJis.unencodable(text);
		if (missing != null) {
			return "Shift-JIS has no " + missing;
		}
		int bytes = stored(text).length - 2;
		return bytes > MAX_TEXT ? bytes + " bytes, more than the " + MAX_TEXT + " a line can hold" : null;
	}

	/**
	 * {@code d} with each line {@code texts} has a different text for (by key) holding it, the
	 * jumps moved to where their commands now are; refused when a text cannot be a line.
	 */
	public static byte[] write(byte[] d, Map<String, String> texts) {
		List<int[]> commands = commands(d);
		if (commands == null) {
			throw new IllegalArgumentException("not a map script");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
		Map<Integer, Integer> moved = new HashMap<>();
		int lines = 0;
		for (int[] c : commands) {
			moved.put(c[0], out.size());
			String text = line(d, c);
			String english = text == null ? null : texts.get(key(lines++));
			if (english == null || english.equals(text.replace("\\n", "\n"))) {
				out.write(d, c[0], c[1]);
				continue;
			}
			String problem = problem(english);
			if (problem != null) {
				throw new IllegalArgumentException(key(lines - 1) + ": " + problem);
			}
			byte[] stored = stored(english);
			out.write(d[c[0]]);
			out.write(stored.length);
			out.writeBytes(stored);
		}
		moved.put(d.length, out.size());
		byte[] result = out.toByteArray();
		for (int[] c : commands) {
			Integer at = JUMPS.get(d[c[0]] & 0xFF);
			if (at == null) {
				continue;
			}
			int target = Bytes.u32(d, c[0] + at);
			Integer now = moved.get(target);
			if (now == null) {
				throw new IllegalArgumentException("a jump at 0x" + Integer.toHexString(c[0]) + " leads to no command");
			}
			int p = moved.get(c[0]) + at;
			result[p] = (byte) (int) now;
			result[p + 1] = (byte) (now >> 8);
			result[p + 2] = (byte) (now >> 16);
			result[p + 3] = (byte) (now >> 24);
		}
		return result;
	}

	private static String key(int line) {
		return String.format(Locale.ROOT, "%02d", line);
	}

	/** A line's text as the file holds it: backslash-n breaks, then % and NUL. */
	private static byte[] stored(String text) {
		return ShiftJis.encode(text.replace("\n", "\\n") + "%\0");
	}

	/** The text of command {@code c}, as the file holds it, when it is a line; else null. */
	private static String line(byte[] d, int[] c) {
		int op = d[c[0]] & 0xFF;
		int end = c[0] + c[1];
		if (op != SPEAKS && op != NARRATES || c[1] < 5 || d[end - 1] != 0 || d[end - 2] != '%') {
			return null;
		}
		for (int k = c[0] + 2; k < end - 1; k++) {
			if (d[k] == 0) {
				return null;
			}
		}
		String text = ShiftJis.decode(d, c[0] + 2, end - 2);
		return text != null && (ShiftJis.japanese(text) || text.codePoints().anyMatch(MapScript::punctuation)) ? text : null;
	}

	/** Whether {@code c} is a dash, an ellipsis or a bracket, as a line of only {@code ………} is made of. */
	private static boolean punctuation(int c) {
		return c >= 0x2010 && c <= 0x2026 || c >= 0x3000 && c < 0x3040;
	}
}
