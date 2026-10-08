package psaro.format;

/**
 * ETC1 and ETC1A4, the 3DS's block-compressed texture formats, both ways.
 *
 * <p>The image is cut into 8x8 tiles, row by row; each tile into four 4x4 blocks, top-left,
 * top-right, bottom-left, bottom-right. A block is a little-endian 64-bit ETC1 word, and in
 * ETC1A4 an alpha word before it: 4 bits a pixel, pixel (x, y) at nibble {@code x * 4 + y}.
 *
 * <p>An ETC1 word holds two half-blocks (side by side, or one above the other when flipped), each
 * a base colour and one of eight brightness tables; every pixel picks one of its table's four
 * steps. The encoder tries both splits and both colour modes (two 4-bit bases, or a 5-bit base
 * and a 3-bit difference), takes each half's average colour, weighted by how opaque its pixels
 * are, and the table and steps closest to the pixels.
 */
public final class Etc1 {

	private static final int[][] TABLES = {{2, 8}, {5, 17}, {9, 29}, {13, 42}, {18, 60}, {24, 80}, {33, 106}, {47, 183}};

	private Etc1() {
	}

	/** {@code data} from {@code off}, {@code w} x {@code h} (multiples of 8), to row-major ARGB. */
	public static int[] decode(byte[] data, int off, int w, int h, boolean alpha) {
		int[] argb = new int[w * h];
		int o = off;
		for (int ty = 0; ty < h; ty += 8) {
			for (int tx = 0; tx < w; tx += 8) {
				for (int b = 0; b < 4; b++) {
					int bx = tx + (b % 2) * 4;
					int by = ty + (b / 2) * 4;
					long a = -1;
					if (alpha) {
						a = le64(data, o);
						o += 8;
					}
					long c = le64(data, o);
					o += 8;
					for (int x = 0; x < 4; x++) {
						for (int y = 0; y < 4; y++) {
							int al = alpha ? (int) (a >>> ((x * 4 + y) * 4) & 0xF) * 0x11 : 0xFF;
							argb[(by + y) * w + bx + x] = al << 24 | pixel(c, x, y);
						}
					}
				}
			}
		}
		return argb;
	}

	/** Row-major ARGB, {@code w} x {@code h} (multiples of 8), to ETC1 or ETC1A4 data. */
	public static byte[] encode(int[] argb, int w, int h, boolean alpha) {
		byte[] out = new byte[w * h / 2 * (alpha ? 2 : 1)];
		int o = 0;
		int[] block = new int[16];
		for (int ty = 0; ty < h; ty += 8) {
			for (int tx = 0; tx < w; tx += 8) {
				for (int b = 0; b < 4; b++) {
					int bx = tx + (b % 2) * 4;
					int by = ty + (b / 2) * 4;
					long a = 0;
					for (int x = 0; x < 4; x++) {
						for (int y = 0; y < 4; y++) {
							int p = argb[(by + y) * w + bx + x];
							block[x * 4 + y] = p;
							a |= (long) (((p >>> 24) + 8) / 17) << ((x * 4 + y) * 4);
						}
					}
					if (alpha) {
						putLe64(out, o, a);
						o += 8;
					}
					putLe64(out, o, block(block));
					o += 8;
				}
			}
		}
		return out;
	}

	/** One block's ETC1 word; {@code px[x * 4 + y]} ARGB. */
	static long block(int[] px) {
		long best = 0;
		long bestError = Long.MAX_VALUE;
		for (int flip = 0; flip < 2; flip++) {
			for (int diff = 0; diff < 2; diff++) {
				long[] word = {0};
				long error = tryMode(px, flip == 1, diff == 1, word);
				if (error < bestError) {
					bestError = error;
					best = word[0];
				}
			}
		}
		return best;
	}

	/** Encodes {@code px} with one split and colour mode into {@code out[0]}; returns the error, or MAX when it cannot. */
	private static long tryMode(int[] px, boolean flip, boolean diff, long[] out) {
		double[][] avg = new double[2][];
		for (int half = 0; half < 2; half++) {
			avg[half] = average(px, flip, half);
		}
		int[][] base = new int[2][3];
		int[][] colour = new int[2][3];
		if (diff) {
			for (int ch = 0; ch < 3; ch++) {
				int b1 = (int) Math.round(avg[0][ch] * 31 / 255);
				int b2 = (int) Math.round(avg[1][ch] * 31 / 255);
				int d = Math.max(-4, Math.min(3, b2 - b1));
				base[0][ch] = b1;
				base[1][ch] = d;
				colour[0][ch] = b1 << 3 | b1 >> 2;
				int v = b1 + d;
				colour[1][ch] = v << 3 | v >> 2;
			}
		} else {
			for (int half = 0; half < 2; half++) {
				for (int ch = 0; ch < 3; ch++) {
					int v = (int) Math.round(avg[half][ch] / 17);
					base[half][ch] = v;
					colour[half][ch] = v * 17;
				}
			}
		}
		long word = 0;
		long total = 0;
		int[] tables = new int[2];
		for (int half = 0; half < 2; half++) {
			long bestError = Long.MAX_VALUE;
			long bestBits = 0;
			int bestTable = 0;
			for (int t = 0; t < 8; t++) {
				long error = 0;
				long bits = 0;
				for (int x = 0; x < 4; x++) {
					for (int y = 0; y < 4; y++) {
						if ((flip ? y >= 2 : x >= 2) != (half == 1)) {
							continue;
						}
						int p = px[x * 4 + y];
						long weight = (p >>> 24) + 1;
						long pixelBest = Long.MAX_VALUE;
						int pick = 0;
						for (int m = 0; m < 4; m++) {
							int mod = TABLES[t][m & 1] * ((m & 2) != 0 ? -1 : 1);
							long e = 0;
							for (int ch = 0; ch < 3; ch++) {
								int v = Math.max(0, Math.min(255, colour[half][ch] + mod));
								int want = p >> (16 - 8 * ch) & 0xFF;
								e += (long) (v - want) * (v - want);
							}
							if (e < pixelBest) {
								pixelBest = e;
								pick = m;
							}
						}
						error += pixelBest * weight;
						int bit = x * 4 + y;
						bits |= (long) (pick & 1) << bit | (long) (pick >> 1 & 1) << (bit + 16);
					}
				}
				if (error < bestError) {
					bestError = error;
					bestBits = bits;
					bestTable = t;
				}
			}
			total += bestError;
			word |= bestBits;
			tables[half] = bestTable;
		}
		if (diff) {
			word |= (long) base[0][0] << 59 | (long) (base[1][0] & 7) << 56 | (long) base[0][1] << 51
					| (long) (base[1][1] & 7) << 48 | (long) base[0][2] << 43 | (long) (base[1][2] & 7) << 40;
		} else {
			word |= (long) base[0][0] << 60 | (long) base[1][0] << 56 | (long) base[0][1] << 52 | (long) base[1][1] << 48
					| (long) base[0][2] << 44 | (long) base[1][2] << 40;
		}
		word |= (long) tables[0] << 37 | (long) tables[1] << 34 | (diff ? 1L : 0L) << 33 | (flip ? 1L : 0L) << 32;
		out[0] = word;
		return total;
	}

	/** A half-block's average RGB, each pixel weighted by its opacity (all alike when all are clear). */
	private static double[] average(int[] px, boolean flip, int half) {
		double[] sum = new double[3];
		double weights = 0;
		for (int pass = 0; pass < 2 && weights == 0; pass++) {
			for (int x = 0; x < 4; x++) {
				for (int y = 0; y < 4; y++) {
					if ((flip ? y >= 2 : x >= 2) != (half == 1)) {
						continue;
					}
					int p = px[x * 4 + y];
					double w = pass == 0 ? (p >>> 24) : 1;
					sum[0] += (p >> 16 & 0xFF) * w;
					sum[1] += (p >> 8 & 0xFF) * w;
					sum[2] += (p & 0xFF) * w;
					weights += w;
				}
			}
		}
		return new double[] {sum[0] / weights, sum[1] / weights, sum[2] / weights};
	}

	/** Pixel (x, y)'s RGB from ETC1 word {@code c}. */
	static int pixel(long c, int x, int y) {
		boolean diff = (c >>> 33 & 1) != 0;
		boolean flip = (c >>> 32 & 1) != 0;
		boolean second = flip ? y >= 2 : x >= 2;
		int[] rgb = new int[3];
		for (int ch = 0; ch < 3; ch++) {
			int shift = 59 - 8 * ch;
			if (diff) {
				int b = (int) (c >>> shift & 31);
				int d = (int) (c >>> (shift - 3) & 7);
				int v = second ? b + (d >= 4 ? d - 8 : d) : b;
				rgb[ch] = v << 3 | v >> 2;
			} else {
				rgb[ch] = (int) (c >>> (second ? shift - 3 : shift + 1) & 15) * 17;
			}
		}
		int table = (int) (c >>> (second ? 34 : 37) & 7);
		int bit = x * 4 + y;
		int msb = (int) (c >>> (bit + 16) & 1);
		int lsb = (int) (c >>> bit & 1);
		int mod = TABLES[table][lsb] * (msb == 1 ? -1 : 1);
		int out = 0;
		for (int ch = 0; ch < 3; ch++) {
			out |= Math.max(0, Math.min(255, rgb[ch] + mod)) << (16 - 8 * ch);
		}
		return out;
	}

	private static long le64(byte[] d, int i) {
		long v = 0;
		for (int k = 7; k >= 0; k--) {
			v = v << 8 | (d[i + k] & 0xFF);
		}
		return v;
	}

	private static void putLe64(byte[] d, int i, long v) {
		for (int k = 0; k < 8; k++) {
			d[i + k] = (byte) (v >>> (8 * k));
		}
	}
}
