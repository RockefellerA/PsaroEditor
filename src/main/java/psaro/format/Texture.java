package psaro.format;

/**
 * 3DS GPU texture layout and the pixel formats the fonts use.
 *
 * <p>Images are 8x8 tiles in row-major order; inside a tile pixels follow a Morton (Z) curve.
 * Font sheets store their top row first. Raw pixel values stay in the sheet's own format so
 * a read-write cycle is lossless; {@link #toRgba} / {@link #fromRgba} convert only when glyphs
 * move between fonts of different formats. RGBA values are packed {@code 0xRRGGBBAA}.
 */
public final class Texture {

	public static final int RGBA8 = 0;
	public static final int LA8 = 5;
	public static final int A8 = 8;
	public static final int LA4 = 9;
	public static final int A4 = 11;

	private static final int[] TILE_X = new int[64];
	private static final int[] TILE_Y = new int[64];

	static {
		for (int t = 0; t < 64; t++) {
			int x = 0;
			int y = 0;
			for (int b = 0; b < 3; b++) {
				x |= (t >> (2 * b) & 1) << b;
				y |= (t >> (2 * b + 1) & 1) << b;
			}
			TILE_X[t] = x;
			TILE_Y[t] = y;
		}
	}

	private Texture() {
	}

	public static int bitsPerPixel(int format) {
		return switch (format) {
			case RGBA8 -> 32;
			case 1 -> 24;
			case 2, 3, 4, LA8, 6 -> 16;
			case 7, A8, LA4 -> 8;
			case 10, A4 -> 4;
			default -> throw new IllegalArgumentException("unsupported texture format " + format);
		};
	}

	public static String formatName(int format) {
		return switch (format) {
			case RGBA8 -> "RGBA8";
			case LA8 -> "LA8";
			case A8 -> "A8";
			case LA4 -> "LA4";
			case A4 -> "A4";
			default -> "format " + format;
		};
	}

	/** Sheet bytes at {@code off} to row-major raw pixel values. */
	public static int[] unswizzle(byte[] data, int off, int w, int h, int format) {
		int bpp = bitsPerPixel(format);
		int bytesPer = bpp / 8;
		int[] px = new int[w * h];
		int i = 0;
		for (int ty = 0; ty < h; ty += 8) {
			for (int tx = 0; tx < w; tx += 8) {
				for (int t = 0; t < 64; t++, i++) {
					int v;
					if (bpp == 4) {
						int b = data[off + (i >> 1)] & 0xFF;
						v = (i & 1) == 0 ? b & 0xF : b >> 4;
					} else {
						v = 0;
						int p = off + i * bytesPer;
						for (int k = 0; k < bytesPer; k++) {
							v |= (data[p + k] & 0xFF) << (8 * k);
						}
					}
					px[(ty + TILE_Y[t]) * w + tx + TILE_X[t]] = v;
				}
			}
		}
		return px;
	}

	public static byte[] swizzle(int[] px, int w, int h, int format) {
		int bpp = bitsPerPixel(format);
		int bytesPer = bpp / 8;
		byte[] out = new byte[w * h * bpp / 8];
		int i = 0;
		for (int ty = 0; ty < h; ty += 8) {
			for (int tx = 0; tx < w; tx += 8) {
				for (int t = 0; t < 64; t++, i++) {
					int v = px[(ty + TILE_Y[t]) * w + tx + TILE_X[t]];
					if (bpp == 4) {
						out[i >> 1] |= (byte) ((i & 1) == 0 ? v & 0xF : (v & 0xF) << 4);
					} else {
						for (int k = 0; k < bytesPer; k++) {
							out[i * bytesPer + k] = (byte) (v >> (8 * k));
						}
					}
				}
			}
		}
		return out;
	}

	public static int toRgba(int v, int format) {
		return switch (format) {
			case LA8 -> grey(v >> 8 & 0xFF, v & 0xFF);
			case LA4 -> grey((v >> 4 & 0xF) * 17, (v & 0xF) * 17);
			case A8 -> grey(255, v & 0xFF);
			case A4 -> grey(255, (v & 0xF) * 17);
			case RGBA8 -> (v >>> 24) << 24 | (v >> 16 & 0xFF) << 16 | (v >> 8 & 0xFF) << 8 | (v & 0xFF);
			default -> throw new IllegalArgumentException("unsupported texture format " + format);
		};
	}

	public static int fromRgba(int rgba, int format) {
		int r = rgba >>> 24;
		int g = rgba >> 16 & 0xFF;
		int b = rgba >> 8 & 0xFF;
		int a = rgba & 0xFF;
		int lum = (r * 299 + g * 587 + b * 114) / 1000;
		return switch (format) {
			case LA8 -> lum << 8 | a;
			case LA4 -> (lum / 17) << 4 | a / 17;
			case A8 -> a;
			case A4 -> a / 17;
			case RGBA8 -> rgba;
			default -> throw new IllegalArgumentException("unsupported texture format " + format);
		};
	}

	private static int grey(int l, int a) {
		return l << 24 | l << 16 | l << 8 | a;
	}
}
