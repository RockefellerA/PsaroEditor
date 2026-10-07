package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code romfs/text/*.tdt} string tables.
 *
 * <pre>
 * header: u32 entry count, u32 offset of the first entry (8)
 * entry:  u32 entry length, u32 key offset (12), u32 text offset,
 *         ASCII key + NUL, UTF-16LE text + NUL     (offsets relative to the entry)
 * </pre>
 *
 * A table pairs with the archive of the same name ({@code config.arc.lz} reads
 * {@code config_Japanese.tdt}); keys are what that archive's layouts reference. Text may hold
 * {@code \u0002\u0001}..{@code \u0002\u0003} colour switches and {@code \n} line breaks.
 */
public final class Tdt {

	private Tdt() {
	}

	/** Keys to text, in file order. */
	public static LinkedHashMap<String, String> read(byte[] d) {
		int count = Bytes.u32(d, 0);
		int o = Bytes.u32(d, 4);
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			int len = Bytes.u32(d, o);
			String key = Bytes.ascii(d, o + Bytes.u32(d, o + 4));
			String text = Bytes.utf16(d, o + Bytes.u32(d, o + 8), o + len);
			out.put(key, text);
			o += len;
		}
		return out;
	}

	public static byte[] write(Map<String, String> entries) {
		Buf out = new Buf(64 + entries.size() * 48);
		out.putInt(entries.size()).putInt(8);
		for (Map.Entry<String, String> e : entries.entrySet()) {
			byte[] key = e.getKey().getBytes(StandardCharsets.US_ASCII);
			byte[] text = e.getValue().getBytes(StandardCharsets.UTF_16LE);
			int textOff = 12 + key.length + 1;
			out.putInt(textOff + text.length + 2).putInt(12).putInt(textOff)
					.put(key).put(0).put(text).putShort(0);
		}
		return out.toArray();
	}
}
