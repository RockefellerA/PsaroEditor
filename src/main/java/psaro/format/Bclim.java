package psaro.format;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BCLIM, a layout's image: the pixel data, then a 0x28-byte trailer.
 *
 * <pre>
 * data      power-of-two sized (8 at least) in the 3DS's tiled layout
 * end-0x28  CLIM: magic, BOM, header size u16 (0x14), version u32, file size u32, block count u16
 * end-0x14  imag: magic, size u32 (0x10), width u16, height u16, format u32, data size u32
 * </pre>
 *
 * The visible image is the top-left {@code width} x {@code height} of the data. Replacing it keeps
 * the size and format, so the file comes out the same length and every layout still fits it.
 */
public final class Bclim {

	/** BCLIM's own format numbers, to the GPU's ({@link Texture}). */
	private static final int[] GPU = {Texture.L8, Texture.A8, Texture.LA4, Texture.LA8, Texture.HILO8, Texture.RGB565,
			Texture.RGB8, Texture.RGBA5551, Texture.RGBA4, Texture.RGBA8, Texture.ETC1, Texture.ETC1A4, Texture.L4,
			Texture.A4};

	private final byte[] file;
	private final int width;
	private final int height;
	/** The GPU format ({@link Texture}). */
	private final int format;
	private final int dataW;
	private final int dataH;

	private Bclim(byte[] file, int width, int height, int format) {
		this.file = file;
		this.width = width;
		this.height = height;
		this.format = format;
		this.dataW = pow2(width);
		this.dataH = pow2(height);
	}

	public static Bclim read(byte[] d) {
		int clim = d.length - 0x28;
		if (clim < 0 || !Bytes.magic(d, clim, "CLIM") || !Bytes.magic(d, clim + 0x14, "imag")) {
			throw new IllegalArgumentException("not a CLIM image");
		}
		int imag = clim + 0x14;
		int f = Bytes.u32(d, imag + 12);
		if (f < 0 || f >= GPU.length) {
			throw new IllegalArgumentException("unknown CLIM format " + f);
		}
		return new Bclim(d, Bytes.u16(d, imag + 8), Bytes.u16(d, imag + 10), GPU[f]);
	}

	/** The SHA-256 of an image file, hex: the same for every archive's copy of one image. */
	public static String hash(byte[] file) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** The GPU format ({@link Texture#formatName}). */
	public int format() {
		return format;
	}

	/** The visible image. */
	public BufferedImage image() {
		int[] all = argb();
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			img.setRGB(0, y, width, 1, all, y * dataW, dataW);
		}
		return img;
	}

	/**
	 * The file with {@code img} in place of the visible image, in the same format, the hidden
	 * padding around it as it was. {@code img} must be the same size.
	 */
	public byte[] with(BufferedImage img) {
		if (img.getWidth() != width || img.getHeight() != height) {
			throw new IllegalArgumentException("the image must be " + width + "x" + height + ", not "
					+ img.getWidth() + "x" + img.getHeight());
		}
		int[] all = argb();
		for (int y = 0; y < height; y++) {
			img.getRGB(0, y, width, 1, all, y * dataW, dataW);
		}
		byte[] data;
		if (format == Texture.ETC1 || format == Texture.ETC1A4) {
			data = Etc1.encode(all, dataW, dataH, format == Texture.ETC1A4);
		} else {
			int[] raw = new int[all.length];
			for (int i = 0; i < raw.length; i++) {
				int p = all[i];
				raw[i] = Texture.fromRgba(p << 8 | p >>> 24, format);
			}
			data = Texture.swizzle(raw, dataW, dataH, format);
		}
		byte[] out = file.clone();
		System.arraycopy(data, 0, out, 0, Math.min(data.length, file.length - 0x28));
		ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(file.length - 0x28 + 0x0C, out.length);
		return out;
	}

	/** Every pixel of the data, padding included, row-major ARGB. */
	private int[] argb() {
		if (format == Texture.ETC1 || format == Texture.ETC1A4) {
			return Etc1.decode(file, 0, dataW, dataH, format == Texture.ETC1A4);
		}
		int[] raw = Texture.unswizzle(file, 0, dataW, dataH, format);
		int[] out = new int[raw.length];
		for (int i = 0; i < raw.length; i++) {
			int rgba = Texture.toRgba(raw[i], format);
			out[i] = (rgba & 0xFF) << 24 | rgba >>> 8;
		}
		return out;
	}

	private static int pow2(int v) {
		int p = 8;
		while (p < v) {
			p *= 2;
		}
		return p;
	}
}
