package psaro.format;

import java.nio.charset.StandardCharsets;

/** Little-endian reads over a byte array. */
final class Bytes {

	private Bytes() {
	}

	static int u8(byte[] d, int i) {
		return d[i] & 0xFF;
	}

	static int s8(byte[] d, int i) {
		return d[i];
	}

	static int u16(byte[] d, int i) {
		return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
	}

	static int u32(byte[] d, int i) {
		return u16(d, i) | u16(d, i + 2) << 16;
	}

	static float f32(byte[] d, int i) {
		return Float.intBitsToFloat(u32(d, i));
	}

	static boolean magic(byte[] d, int i, String tag) {
		if (i + tag.length() > d.length) {
			return false;
		}
		for (int k = 0; k < tag.length(); k++) {
			if (d[i + k] != (byte) tag.charAt(k)) {
				return false;
			}
		}
		return true;
	}

	/** ASCII string from {@code i} up to the first NUL. */
	static String ascii(byte[] d, int i) {
		int e = i;
		while (e < d.length && d[e] != 0) {
			e++;
		}
		return new String(d, i, e - i, StandardCharsets.US_ASCII);
	}

	/** Index of the UTF-16 NUL terminator at or after {@code i} (stepping by 2). */
	static int utf16End(byte[] d, int i, int limit) {
		int e = i;
		while (e + 1 < limit && (d[e] != 0 || d[e + 1] != 0)) {
			e += 2;
		}
		return e;
	}

	static String utf16(byte[] d, int i, int limit) {
		int e = utf16End(d, i, limit);
		return new String(d, i, e - i, StandardCharsets.UTF_16LE);
	}
}
