package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class BcfntTest {

	/** An LA8 font whose glyph for each code is a solid block from row {@code top} to the baseline. */
	private static Bcfnt font(int cellW, int cellH, int baseline, int height, int width, String chars) {
		Bcfnt f = new Bcfnt();
		f.format = Texture.LA8;
		f.cellW = cellW;
		f.cellH = cellH;
		f.baseline = baseline;
		f.height = height;
		f.width = width;
		f.ascent = baseline;
		f.lineFeed = height;
		for (char c : chars.toCharArray()) {
			int[] px = new int[cellW * cellH];
			for (int y = 2; y < baseline; y++) {
				for (int x = 1; x < cellW - 1; x++) {
					px[y * cellW + x] = 0xFF << 8 | 0xFF;   // white, opaque
				}
			}
			f.cmap.put((int) c, f.glyphs.size());
			f.glyphs.add(new Bcfnt.Glyph(px, -1, cellW - 2, cellW - 1));
			f.maxCharWidth = Math.max(f.maxCharWidth, cellW - 1);
		}
		f.fitSheets(256, 512);
		return f;
	}

	@Test
	void writtenFontParsesBackIdentically() {
		Bcfnt f = font(12, 14, 11, 16, 13, " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz");
		byte[] bytes = f.toBytes();
		Bcfnt g = Bcfnt.parse(bytes);
		assertEquals(f.cmap, g.cmap);
		assertEquals(f.glyphs.size(), g.glyphs.size());
		for (int i = 0; i < f.glyphs.size(); i++) {
			assertArrayEquals(f.glyphs.get(i).pixels, g.glyphs.get(i).pixels, "glyph " + i);
			assertEquals(f.glyphs.get(i).charWidth, g.glyphs.get(i).charWidth);
		}
		assertArrayEquals(bytes, g.toBytes());
	}

	@Test
	void mergedGlyphsLandOnTheTargetBaselineAndOriginalsSurvive() {
		Bcfnt target = font(10, 12, 9, 14, 11, "あい");
		Bcfnt donor = font(12, 16, 13, 14, 11, "ABC");
		List<Integer> added = target.addGlyphsFrom(donor, List.of((int) 'A', (int) 'B', (int) 'Z'), false);
		assertEquals(List.of((int) 'A', (int) 'B'), added);
		assertEquals(13, target.baseline);   // the taller ascender wins
		assertTrue(target.has('あ') && target.has('A'));
		Bcfnt reread = Bcfnt.parse(target.toBytes());
		int[] a = reread.rgba(reread.cmap.get((int) 'A'));
		int[] hira = reread.rgba(reread.cmap.get(0x3042));
		int w = reread.cellW;
		// both glyphs' last solid row sits directly above the shared baseline
		assertEquals(255, a[(reread.baseline - 1) * w + 2] & 0xFF);
		assertEquals(0, a[reread.baseline * w + 2] & 0xFF);
		assertEquals(255, hira[(reread.baseline - 1) * w + 2] & 0xFF);
		assertEquals(0, hira[reread.baseline * w + 2] & 0xFF);
	}

	@Test
	void scaledDonorTakesTheTargetsProportions() {
		Bcfnt target = font(10, 12, 9, 15, 13, "x");
		Bcfnt donor = font(20, 24, 18, 30, 26, "AW");
		Bcfnt scaled = donor.scaledTo(target, List.of((int) 'A'));
		assertEquals(target.height, scaled.height);
		assertEquals(10, scaled.cellW);
		assertEquals(9, scaled.baseline);
		assertEquals(Math.round(19 * 0.5), scaled.glyph('A').charWidth);
		assertTrue(!scaled.has('W'));
		// an interior pixel of the solid block stays opaque white after the box filter
		int[] px = scaled.rgba(0);
		assertEquals(0xFFFFFFFF, px[5 * scaled.cellW + 4]);
	}

	@Test
	void sheetFitPrefersFewestTexels() {
		Bcfnt f = font(27, 33, 25, 39, 34, "");
		for (int i = 0; i < 136; i++) {
			f.glyphs.add(new Bcfnt.Glyph(new int[27 * 33], 0, 1, 1));
		}
		f.fitSheets(256, 512);
		// 136 cells of 27x33: five 128x256 sheets (4x7 grid) beat two 256x512 ones
		assertEquals(List.of(128, 256, 4, 7), List.of(f.sheetW, f.sheetH, f.cols, f.rows));
	}
}
