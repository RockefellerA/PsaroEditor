package psaro.format;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code romfs/message/*_jp.mdt} message files: strings the game draws from code, by number (the
 * tutorial's dialogs and buttons, help pages, errors), beside the {@link Tdt} tables layouts name.
 *
 * <pre>
 * header: "MSGD", u32 0, u32 0, u32 string count, u32 offset table offset (0x20),
 *         u32 text offset, u32 0, u32 0
 * offset table: u32 per string, its byte offset from the text offset, then zero padding
 * text:   each string UTF-16LE with every unit XORed with 0xFF73, ending in a 0 unit
 * </pre>
 *
 * Text holds the same {@code \u0002}-led colour switches and {@code \n} line breaks as a table's,
 * and may hold {@code printf} fields ({@code %d}) the game fills in.
 */
public final class Msgd {

	private static final int KEY = 0xFF73;

	private Msgd() {
	}

	/** The strings, in number order. */
	public static List<String> read(byte[] d) {
		if (d.length < 0x20 || d[0] != 'M' || d[1] != 'S' || d[2] != 'G' || d[3] != 'D') {
			throw new IllegalArgumentException("not an MSGD message file");
		}
		int count = Bytes.u32(d, 0x0C);
		int table = Bytes.u32(d, 0x10);
		int text = Bytes.u32(d, 0x14);
		List<String> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			StringBuilder s = new StringBuilder();
			for (int o = text + Bytes.u32(d, table + 4 * i); o + 1 < d.length; o += 2) {
				int unit = (d[o] & 0xFF | (d[o + 1] & 0xFF) << 8) ^ KEY;
				if (unit == 0) {
					break;
				}
				s.append((char) unit);
			}
			out.add(s.toString());
		}
		return out;
	}

	/**
	 * {@code original} with its strings replaced by {@code strings}, as many as it has: its header
	 * and the padding after its offset table kept as they are, the offsets and text rebuilt.
	 */
	public static byte[] write(byte[] original, List<String> strings) {
		int count = Bytes.u32(original, 0x0C);
		if (strings.size() != count) {
			throw new IllegalArgumentException(count + " strings expected, " + strings.size() + " given");
		}
		int table = Bytes.u32(original, 0x10);
		int text = Bytes.u32(original, 0x14);
		Buf out = new Buf(original.length + 64);
		out.put(original, 0, text);
		Buf body = new Buf(original.length);
		int[] offsets = new int[count];
		for (int i = 0; i < count; i++) {
			offsets[i] = body.size();
			String s = strings.get(i);
			for (int k = 0; k < s.length(); k++) {
				body.putShort(s.charAt(k) ^ KEY);
			}
			body.putShort(KEY);
		}
		byte[] bytes = out.toArray();
		for (int i = 0; i < count; i++) {
			int at = table + 4 * i;
			bytes[at] = (byte) offsets[i];
			bytes[at + 1] = (byte) (offsets[i] >> 8);
			bytes[at + 2] = (byte) (offsets[i] >> 16);
			bytes[at + 3] = (byte) (offsets[i] >>> 24);
		}
		return new Buf(bytes.length + body.size()).put(bytes).put(body.toArray()).toArray();
	}
}
