package psaro.format;

/**
 * CRI's {@code CRILAYLA} compression, as a {@link Cpk} stores most of its files.
 *
 * <pre>
 * header: "CRILAYLA", u32 size once decompressed (less the 0x100 bytes below), u32 where those
 *         bytes start, counted from 0x10
 * then:   the compressed bits, then the file's first 0x100 bytes as they are
 * </pre>
 *
 * The bits are read from the end of the compressed run towards its start, each byte's from the
 * top, and fill the output from its end backwards: a 0 bit is a literal byte (8 bits); a 1 is a
 * copy of bytes already written after it, 13 bits of distance (plus 3) and a length of 3 plus
 * fields of 2, 3, 5 and 8 bits, each read only when the one before it is all ones, then bytes of
 * 8 bits for as long as they are 255.
 */
public final class Crilayla {

	private static final int RAW = 0x100;
	private static final int[] LENGTH_BITS = {2, 3, 5, 8};

	private Crilayla() {
	}

	/** Whether {@code d} starts as a CRILAYLA file does. */
	public static boolean isCompressed(byte[] d) {
		return Bytes.magic(d, 0, "CRILAYLA") && d.length >= 0x10;
	}

	public static byte[] decompress(byte[] d) {
		if (!isCompressed(d)) {
			throw new IllegalArgumentException("not CRILAYLA");
		}
		int size = Bytes.u32(d, 8);
		int rawAt = 0x10 + Bytes.u32(d, 12);
		if (size < 0 || rawAt < 0x10 || rawAt + RAW > d.length) {
			throw new IllegalArgumentException("CRILAYLA header out of range");
		}
		byte[] out = new byte[RAW + size];
		System.arraycopy(d, rawAt, out, 0, RAW);
		Bits in = new Bits(d, rawAt - 1);
		int end = RAW + size - 1;
		int written = 0;
		while (written < size) {
			if (in.next(1) == 0) {
				out[end - written++] = (byte) in.next(8);
				continue;
			}
			int from = end - written + in.next(13) + 3;
			int length = 3;
			int level = 0;
			for (; level < LENGTH_BITS.length; level++) {
				int part = in.next(LENGTH_BITS[level]);
				length += part;
				if (part != (1 << LENGTH_BITS[level]) - 1) {
					break;
				}
			}
			if (level == LENGTH_BITS.length) {
				int part;
				do {
					part = in.next(8);
					length += part;
				} while (part == 0xFF);
			}
			if (from > end || length > size - written) {
				throw new IllegalArgumentException("CRILAYLA copy out of range");
			}
			for (int k = 0; k < length; k++) {
				out[end - written++] = out[from--];
			}
		}
		return out;
	}

	/** The bits of a byte array, read from {@code at} towards its start, each byte's from the top. */
	private static final class Bits {
		private final byte[] d;
		private int at;
		private int pool;
		private int left;

		Bits(byte[] d, int at) {
			this.d = d;
			this.at = at;
		}

		int next(int count) {
			int v = 0;
			while (count > 0) {
				if (left == 0) {
					if (at < 0x10) {
						throw new IllegalArgumentException("CRILAYLA bits run out");
					}
					pool = d[at--] & 0xFF;
					left = 8;
				}
				int take = Math.min(left, count);
				v = v << take | pool >> left - take & (1 << take) - 1;
				left -= take;
				count -= take;
			}
			return v;
		}
	}
}
