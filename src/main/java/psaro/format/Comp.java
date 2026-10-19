package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Lost Heroes' {@code *.cmp} files: {@code COMP}, then an {@link Lz11} stream. Most hold a
 * {@link Nbf} bundle; the fonts, a few tables and maps hold one file of their own.
 */
public final class Comp {

	private static final String MAGIC = "COMP";
	private static final int HEADER = 4;

	private Comp() {
	}

	public static boolean is(byte[] d) {
		return d.length > HEADER && Bytes.magic(d, 0, MAGIC) && (d[HEADER] & 0xFF) == 0x11;
	}

	public static byte[] decompress(byte[] d) {
		return decompress(d, Integer.MAX_VALUE);
	}

	/** The first {@code limit} bytes of what {@code d} holds ({@link Lz11#decompress(byte[], int)}). */
	public static byte[] decompress(byte[] d, int limit) {
		if (!is(d)) {
			throw new IllegalArgumentException("not a COMP file");
		}
		return Lz11.decompress(Arrays.copyOfRange(d, HEADER, d.length), limit);
	}

	public static byte[] compress(byte[] data) {
		byte[] packed = Lz11.compress(data);
		byte[] out = new byte[HEADER + packed.length];
		System.arraycopy(MAGIC.getBytes(StandardCharsets.US_ASCII), 0, out, 0, HEADER);
		System.arraycopy(packed, 0, out, HEADER, packed.length);
		return out;
	}
}
