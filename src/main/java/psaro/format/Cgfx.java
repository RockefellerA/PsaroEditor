package psaro.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CGFX models' image textures ({@code *.bcmdl}, often LZ11-compressed as {@code *.bcmdl.lz}):
 * some hold text, as the record book's pages do its headers. Each is read as a BCLIM, the way a
 * layout's image is ({@link Bclim#of}), and replaced in place, the same size and format, the rest
 * of the model as it was.
 *
 * <pre>
 * TXOB, an image texture: u32 type 0x20000011, "TXOB", u32 revision, u32 name offset (from that
 *   field), u32 user data count and offset, u32 height, u32 width, u32 GL format, u32 GL type,
 *   u32 mipmap levels, u32 runtime object, u32 location, u32 GPU format ({@link Texture}),
 *   then its image: u32, u32 height, u32 width, u32 data size, u32 data offset (from that field)
 * </pre>
 *
 * A texture with mipmaps is left out, its smaller levels needing to be made again; so is one in
 * a format the editor does not read.
 */
public final class Cgfx {

	/** An image texture: its name, size, GPU format, and where its pixels are in the model. */
	public record Tex(String name, int width, int height, int format, int dataOffset, int dataSize) {
	}

	private static final int IMAGE_TEXTURE = 0x20000011;

	private Cgfx() {
	}

	/** Whether {@code fileName} is a model's: {@code *.bcmdl} or {@code *.bcmdl.lz}. */
	public static boolean isModel(String fileName) {
		return fileName.endsWith(".bcmdl") || fileName.endsWith(".bcmdl.lz");
	}

	/** The model at {@code path}, decompressed when it is compressed. */
	public static byte[] read(Path path) throws IOException {
		byte[] raw = Files.readAllBytes(path);
		return Lz11.isCompressed(raw) ? Lz11.decompress(raw) : raw;
	}

	/** Writes {@code model} to {@code path}, compressed when the file's name says it is ({@code .lz}). */
	public static void write(byte[] model, Path path) throws IOException {
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(path, path.getFileName().toString().endsWith(".lz") ? Lz11.compress(model) : model);
	}

	/** {@code d}'s image textures that can be read and replaced, in file order. */
	public static List<Tex> textures(byte[] d) {
		if (d.length < 4 || !Bytes.magic(d, 0, "CGFX")) {
			throw new IllegalArgumentException("not a CGFX model");
		}
		List<Tex> out = new ArrayList<>();
		for (int o = 0; o + 0x4C <= d.length; o += 4) {
			if (Bytes.u32(d, o) != IMAGE_TEXTURE || !Bytes.magic(d, o + 4, "TXOB")) {
				continue;
			}
			int mipmaps = Bytes.u32(d, o + 0x28), format = Bytes.u32(d, o + 0x34);
			int height = Bytes.u32(d, o + 0x3C), width = Bytes.u32(d, o + 0x40), size = Bytes.u32(d, o + 0x44);
			int data = o + 0x48 + Bytes.u32(d, o + 0x48);
			int bits;
			try {
				bits = Texture.bitsPerPixel(format);
			} catch (IllegalArgumentException unknown) {
				continue;
			}
			if (mipmaps != 1 || width < 8 || height < 8 || (long) width * height * bits / 8 != size || data < 0
					|| data + size > d.length) {
				continue;
			}
			out.add(new Tex(Bytes.ascii(d, o + 12 + Bytes.u32(d, o + 12)), width, height, format, data, size));
		}
		return out;
	}

	/** {@code t}'s image as a BCLIM, as a layout's image is read. */
	public static byte[] asBclim(byte[] d, Tex t) {
		return Bclim.of(Arrays.copyOfRange(d, t.dataOffset(), t.dataOffset() + t.dataSize()), t.width(), t.height(), t.format());
	}

	/** {@code d} with {@code t}'s pixels those of {@code bclim}, an image of its size and format. */
	public static byte[] with(byte[] d, Tex t, byte[] bclim) {
		byte[] data = Bclim.data(bclim);
		if (data.length != t.dataSize()) {
			throw new IllegalArgumentException(t.name() + " holds " + t.dataSize() + " bytes, not " + data.length);
		}
		byte[] out = d.clone();
		System.arraycopy(data, 0, out, t.dataOffset(), data.length);
		return out;
	}
}
