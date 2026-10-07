package psaro.format;

import java.util.Arrays;

/**
 * Nintendo LZ11 (type byte 0x11) compression, the wrapper around every romfs {@code *.lz} file.
 *
 * <p>A stream is a 4-byte header (type, 24-bit size; size 0 means a 32-bit size follows), then
 * groups of one flag byte and eight tokens. A clear flag bit is a literal byte; a set bit is a
 * back-reference of 2, 3 or 4 bytes encoding a length of 3..0x10110 and a distance of 1..0x1000.
 */
public final class Lz11 {

	private static final int WINDOW = 0x1000;
	private static final int MIN_LEN = 3;
	private static final int MAX_LEN = 0x10110;
	private static final int CHAIN_DEPTH = 32;
	private static final int HASH_BITS = 16;

	private Lz11() {
	}

	public static boolean isCompressed(byte[] data) {
		return data.length >= 4 && (data[0] & 0xFF) == 0x11;
	}

	public static byte[] decompress(byte[] data) {
		if (!isCompressed(data)) {
			throw new IllegalArgumentException("not LZ11 data");
		}
		int size = u8(data, 1) | u8(data, 2) << 8 | u8(data, 3) << 16;
		int pos = 4;
		if (size == 0) {
			size = u8(data, 4) | u8(data, 5) << 8 | u8(data, 6) << 16 | u8(data, 7) << 24;
			pos = 8;
		}
		byte[] out = new byte[size];
		int o = 0;
		while (o < size) {
			int flags = u8(data, pos++);
			for (int bit = 0; bit < 8 && o < size; bit++) {
				if ((flags & (0x80 >> bit)) == 0) {
					out[o++] = data[pos++];
					continue;
				}
				int b = u8(data, pos);
				int length;
				int disp;
				switch (b >> 4) {
					case 0 -> {
						length = ((b & 0xF) << 4 | u8(data, pos + 1) >> 4) + 0x11;
						disp = ((u8(data, pos + 1) & 0xF) << 8 | u8(data, pos + 2)) + 1;
						pos += 3;
					}
					case 1 -> {
						length = ((b & 0xF) << 12 | u8(data, pos + 1) << 4 | u8(data, pos + 2) >> 4) + 0x111;
						disp = ((u8(data, pos + 2) & 0xF) << 8 | u8(data, pos + 3)) + 1;
						pos += 4;
					}
					default -> {
						length = (b >> 4) + 1;
						disp = ((b & 0xF) << 8 | u8(data, pos + 1)) + 1;
						pos += 2;
					}
				}
				length = Math.min(length, size - o);
				for (int i = 0; i < length; i++, o++) {
					out[o] = out[o - disp];
				}
			}
		}
		return out;
	}

	/** Greedy compression over hash chains of 3-byte prefixes. */
	public static byte[] compress(byte[] data) {
		int n = data.length;
		Buf out = new Buf(n / 2 + 16);
		// A zero 24-bit size announces a 32-bit one, so empty input needs the long header too.
		if (n == 0 || n > 0xFFFFFF) {
			out.put(0x11).put(0).put(0).put(0).putInt(n);
		} else {
			out.put(0x11).put(n & 0xFF).put(n >> 8 & 0xFF).put(n >> 16 & 0xFF);
		}
		int[] head = new int[1 << HASH_BITS];
		Arrays.fill(head, -1);
		int[] prev = new int[Math.max(n, 1)];
		int pos = 0;
		int indexed = 0;
		while (pos < n) {
			int flagAt = out.size();
			out.put(0);
			int flags = 0;
			for (int bit = 0; bit < 8 && pos < n; bit++) {
				for (; indexed < pos; indexed++) {
					insert(data, indexed, head, prev);
				}
				int bestLen = 0;
				int bestDisp = 0;
				int limit = Math.min(MAX_LEN, n - pos);
				if (limit >= MIN_LEN) {
					int cand = head[hash(data, pos)];
					for (int depth = 0; cand >= 0 && depth < CHAIN_DEPTH && pos - cand <= WINDOW; depth++) {
						int len = 0;
						while (len < limit && data[cand + len] == data[pos + len]) {
							len++;
						}
						if (len > bestLen) {
							bestLen = len;
							bestDisp = pos - cand;
							if (len == limit) {
								break;
							}
						}
						cand = prev[cand];
					}
				}
				if (bestLen >= MIN_LEN) {
					flags |= 0x80 >> bit;
					int d = bestDisp - 1;
					if (bestLen <= 0x10) {
						out.put((bestLen - 1) << 4 | d >> 8).put(d & 0xFF);
					} else if (bestLen <= 0x110) {
						int l = bestLen - 0x11;
						out.put(l >> 4).put((l & 0xF) << 4 | d >> 8).put(d & 0xFF);
					} else {
						int l = bestLen - 0x111;
						out.put(0x10 | l >> 12).put(l >> 4 & 0xFF).put((l & 0xF) << 4 | d >> 8).put(d & 0xFF);
					}
					pos += bestLen;
				} else {
					out.put(data[pos] & 0xFF);
					pos++;
				}
			}
			out.set(flagAt, flags);
		}
		while (out.size() % 4 != 0) {
			out.put(0);
		}
		return out.toArray();
	}

	private static void insert(byte[] data, int p, int[] head, int[] prev) {
		if (p + MIN_LEN > data.length) {
			return;
		}
		int h = hash(data, p);
		prev[p] = head[h];
		head[h] = p;
	}

	private static int hash(byte[] d, int p) {
		int v = (d[p] & 0xFF) << 16 | (d[p + 1] & 0xFF) << 8 | (d[p + 2] & 0xFF);
		return (v * 0x9E3779B1) >>> (32 - HASH_BITS);
	}

	private static int u8(byte[] d, int i) {
		return d[i] & 0xFF;
	}
}
