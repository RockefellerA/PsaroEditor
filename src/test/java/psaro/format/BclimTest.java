package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleRomfs;

class BclimTest {

	/** A 20 x 10 picture: opaque red left of x = 10, translucent blue right, a clear first row. */
	private static int[] picture() {
		int[] argb = new int[20 * 10];
		for (int y = 0; y < 10; y++) {
			for (int x = 0; x < 20; x++) {
				argb[y * 20 + x] = y == 0 ? 0 : x < 10 ? 0xFFFF0000 : 0x800000FF;
			}
		}
		return argb;
	}

	private static int[] pixels(BufferedImage img) {
		return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
	}

	@Test
	void readsTheVisibleImageAndItsFormat() {
		Bclim b = Bclim.read(SampleRomfs.bclim(20, 10, 9, picture()));
		assertEquals(20, b.width());
		assertEquals(10, b.height());
		assertEquals(Texture.RGBA8, b.format());
		assertArrayEquals(picture(), pixels(b.image()));
	}

	@Test
	void aReplacementKeepsTheFormatAndLength() {
		byte[] file = SampleRomfs.bclim(20, 10, 9, picture());
		BufferedImage next = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
		next.setRGB(3, 4, 0xFF00FF00);
		byte[] out = Bclim.read(file).with(next);
		assertEquals(file.length, out.length);
		assertEquals(0xFF00FF00, Bclim.read(out).image().getRGB(3, 4));
		assertEquals(0, Bclim.read(out).image().getRGB(0, 0));
		// the unchanged image comes back byte for byte
		assertArrayEquals(file, Bclim.read(file).with(Bclim.read(file).image()));
	}

	@Test
	void anImageOfAnotherSizeIsRefused() {
		assertThrows(IllegalArgumentException.class,
				() -> Bclim.read(SampleRomfs.bclim(20, 10, 9, picture())).with(new BufferedImage(21, 10, BufferedImage.TYPE_INT_ARGB)));
	}

	/** ETC1A4 is lossy: flat colours come back close, and alpha to its 4 bits. */
	@Test
	void etc1a4KeepsFlatColoursAndItsAlpha() {
		Bclim b = Bclim.read(SampleRomfs.bclim(20, 10, 11, picture()));
		assertEquals(Texture.ETC1A4, b.format());
		int[] back = pixels(b.image());
		int[] want = picture();
		for (int i = 0; i < want.length; i++) {
			assertEquals(want[i] >>> 24, back[i] >>> 24, 17, "alpha at " + i);
			if ((want[i] >>> 24) > 0) {
				for (int shift = 0; shift < 24; shift += 8) {
					assertEquals(want[i] >> shift & 0xFF, back[i] >> shift & 0xFF, 12, "channel at " + i);
				}
			}
		}
		byte[] again = b.with(b.image());
		assertTrue(again.length == SampleRomfs.bclim(20, 10, 11, picture()).length);
	}
}
