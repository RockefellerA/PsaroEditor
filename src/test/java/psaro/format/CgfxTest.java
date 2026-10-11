package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleRomfs;

class CgfxTest {

	private static byte[] red() {
		int[] px = new int[16 * 8];
		Arrays.fill(px, 0xFFFF0000);
		return SampleRomfs.bclim(16, 8, 9, px);
	}

	/** An image texture is found by its record: its name, size, format, and where its pixels are. */
	@Test
	void anImageTextureIsReadAsALayoutsImageIs() {
		byte[] model = SampleRomfs.cgfx("Garai_book", red(), Texture.RGBA8);
		List<Cgfx.Tex> found = Cgfx.textures(model);
		assertEquals(1, found.size());
		Cgfx.Tex t = found.get(0);
		assertEquals("Garai_book", t.name());
		assertEquals(16, t.width());
		assertEquals(8, t.height());
		assertEquals(Texture.RGBA8, t.format());
		BufferedImage image = Bclim.read(Cgfx.asBclim(model, t)).image();
		assertEquals(0xFFFF0000, image.getRGB(3, 5));
		assertArrayEquals(red(), Cgfx.asBclim(model, t), "the same bytes a BCLIM of the image has");
		assertThrows(IllegalArgumentException.class, () -> Cgfx.textures(new byte[64]));
	}

	/** Replacing a texture changes its pixels and nothing else, and wants an image of its size. */
	@Test
	void replacingATextureChangesOnlyItsPixels() {
		byte[] model = SampleRomfs.cgfx("Garai_book", red(), Texture.RGBA8);
		Cgfx.Tex t = Cgfx.textures(model).get(0);
		BufferedImage white = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
		white.setRGB(2, 2, 0xFFFFFFFF);
		byte[] out = Cgfx.with(model, t, Bclim.read(Cgfx.asBclim(model, t)).with(white));
		assertEquals(model.length, out.length);
		assertArrayEquals(Arrays.copyOf(model, t.dataOffset()), Arrays.copyOf(out, t.dataOffset()));
		BufferedImage back = Bclim.read(Cgfx.asBclim(out, Cgfx.textures(out).get(0))).image();
		assertEquals(0xFFFFFFFF, back.getRGB(2, 2));
		assertEquals(0, back.getRGB(0, 0));
		assertThrows(IllegalArgumentException.class,
				() -> Cgfx.with(model, t, SampleRomfs.bclim(8, 8, 9, new int[64])), "another size");
	}

	/** Against a real romfs when one is supplied: every model's textures read, and write back byte for byte. */
	@Test
	void realModelsTexturesRoundTrip() throws IOException {
		String dir = System.getProperty("psaro.romfs", System.getenv("PSARO_ROMFS"));
		assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "no romfs supplied; set -Dpsaro.romfs");
		int textures = 0;
		try (Stream<Path> files = Files.walk(Path.of(dir))) {
			for (Path p : files.filter(f -> Cgfx.isModel(f.getFileName().toString())).toList()) {
				byte[] d;
				try {
					d = Cgfx.read(p);
				} catch (RuntimeException notCompressedRight) {
					continue;
				}
				if (!Bytes.magic(d, 0, "CGFX")) {
					continue;
				}
				for (Cgfx.Tex t : Cgfx.textures(d)) {
					byte[] bclim = Cgfx.asBclim(d, t);
					Bclim.read(bclim).image();
					assertArrayEquals(d, Cgfx.with(d, t, bclim), p + " " + t.name());
					textures++;
				}
			}
		}
		assumeTrue(textures > 0, "the romfs has no model textures");
	}
}
