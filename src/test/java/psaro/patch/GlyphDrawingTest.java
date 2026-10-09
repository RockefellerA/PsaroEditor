package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import psaro.format.Bcfnt;
import psaro.format.Texture;
import psaro.romfs.SampleRomfs;

class GlyphDrawingTest {

	/** An LA8 font of white blocks ringed by a 1-pixel black outline, like the game's 04a style. */
	private static Bcfnt outlined(String chars) {
		Bcfnt f = SampleRomfs.font(chars, 10);
		for (Bcfnt.Glyph g : f.glyphs) {
			int[] px = new int[f.cellW * f.cellH];
			for (int y = 1; y <= f.baseline; y++) {
				for (int x = 0; x < f.cellW; x++) {
					boolean ring = y == 1 || y == f.baseline || x == 0 || x == f.cellW - 1;
					px[y * f.cellW + x] = Texture.fromRgba(ring ? 0x000000FF : 0xFFFFFFFF, f.format);
				}
			}
			g.pixels = px;
		}
		return f;
	}

	@Test
	void aPlainFontHasNoOutline() {
		GlyphDrawing.Look look = GlyphDrawing.look(SampleRomfs.font("あいうえお", 10));
		assertEquals(0, look.radius());
		assertEquals(0xFFFFFFFF, look.fill());
	}

	/** A font of dark letters has no light pixels to take a fill from: its fill is its dark. */
	@Test
	void aFontOfDarkLettersIsReadAsDark() {
		Bcfnt dark = SampleRomfs.font("あいうえお", 10);
		for (Bcfnt.Glyph g : dark.glyphs) {
			for (int i = 0; i < g.pixels.length; i++) {
				if (g.pixels[i] != 0) {
					g.pixels[i] = Texture.fromRgba(0x202020FF, dark.format);
				}
			}
		}
		GlyphDrawing.Look look = GlyphDrawing.look(dark);
		assertEquals(0, look.radius());
		assertEquals(0x202020FF, look.fill());
		// and a font can be drawn for it
		assertTrue(GlyphDrawing.draw(Typeface.M_PLUS_ROUNDED, "SulaPro_B_Tutorial_03.bcfnt", dark, List.of((int) 'A')).has('A'));
	}

	@Test
	void anOutlinedFontsColoursAndThicknessAreRead() {
		GlyphDrawing.Look look = GlyphDrawing.look(outlined("あいうえお"));
		assertEquals(0xFFFFFFFF, look.fill());
		assertEquals(0x000000FF, look.outline());
		assertTrue(look.radius() > 0.5 && look.radius() < 2, "radius " + look.radius());
	}

	@Test
	void aLetterComesOutTheSameWhicheverOthersAreDrawnWithIt() {
		Bcfnt target = outlined("あいうえお漢字");
		Bcfnt alone = GlyphDrawing.draw(Typeface.NOTO_SANS, "SulaPro_B_04a_20.bcfnt", target, List.of((int) 'e'));
		Bcfnt many = GlyphDrawing.draw(Typeface.NOTO_SANS, "SulaPro_B_04a_20.bcfnt", target, List.of((int) 'W', (int) 'e', (int) 'g'));
		assertEquals(alone.cellW, many.cellW);
		assertEquals(alone.cellH, many.cellH);
		assertArrayEquals(alone.glyph('e').pixels, many.glyph('e').pixels);
		assertEquals(alone.glyph('e').charWidth, many.glyph('e').charWidth);
	}

	@Test
	void lettersAreDrawnWithInkAndAnAdvanceAndASpaceWithOnlyAnAdvance() {
		Bcfnt target = SampleRomfs.font("あいうえお", 10);
		Bcfnt drawn = GlyphDrawing.draw(Typeface.M_PLUS_ROUNDED, "TBMarugothic_H_04a_16.bcfnt", target,
				List.of((int) 'A', (int) 'g', (int) ' '));
		for (int c : new int[] {'A', 'g'}) {
			Bcfnt.Glyph g = drawn.glyph(c);
			assertTrue(g.charWidth > 0 && g.glyphWidth > 0);
			assertTrue(java.util.Arrays.stream(drawn.rgba(drawn.cmap.get(c))).anyMatch(p -> (p & 0xFF) > 200));
		}
		// 'g' hangs below the baseline, 'A' does not
		assertTrue(lowestInk(drawn, 'g') > drawn.baseline);
		assertTrue(lowestInk(drawn, 'A') <= drawn.baseline);
		assertEquals(0, drawn.glyph(' ').glyphWidth);
		assertTrue(drawn.glyph(' ').charWidth > 0);
		assertFalse(drawn.has('あ'), "only what was asked for");
	}

	/**
	 * A dark LA8 font of {@link SampleRomfs#font}'s blocks grown by {@code by} pixels on each side,
	 * like the tutorial titles' black layers under their white letters.
	 */
	private static Bcfnt grown(String chars, int by) {
		Bcfnt f = SampleRomfs.font(chars, 10);
		int cellW = f.cellW + 2 * by;
		int cellH = f.cellH + 2 * by;
		for (Bcfnt.Glyph g : f.glyphs) {
			int[] px = new int[cellW * cellH];
			// the sample's block spans x 1..cellW-2 and y 2..baseline-1 of its cell
			for (int y = 2; y < f.baseline + 2 * by; y++) {
				for (int x = 1; x < cellW - 1; x++) {
					px[y * cellW + x] = Texture.fromRgba(0x000000FF, f.format);
				}
			}
			g.pixels = px;
			g.left -= by;
			g.glyphWidth += 2 * by;
		}
		f.cellW = cellW;
		f.cellH = cellH;
		f.baseline += by;
		return f;
	}

	@Test
	void howMuchALayersLettersAreGrownIsRead() {
		Bcfnt body = SampleRomfs.font("あいうえお漢字", 10);
		assertEquals(2, GlyphDrawing.grownBy(grown("あいうえお漢字", 2), body), 0.01);
		assertEquals(0, GlyphDrawing.grownBy(body, body), 0.01);
		assertTrue(Double.isNaN(GlyphDrawing.grownBy(grown("あい", 2), body)), "too few letters shared to tell");
	}

	/** A layer under the letters is drawn at their size and grown, so the two line up. */
	@Test
	void aLayerUnderAnotherFontIsDrawnAsItsLettersGrown() {
		Bcfnt body = SampleRomfs.font("あいうえお漢字", 10);
		Bcfnt under = grown("あいうえお漢字", 2);
		List<Integer> a = List.of((int) 'A');
		Bcfnt top = new GlyphDrawing.Pen(Typeface.M_PLUS_ROUNDED, "SulaPro_B_Tutorial_01.bcfnt", body).draw(a);
		Bcfnt layer = new GlyphDrawing.Pen(Typeface.M_PLUS_ROUNDED, "SulaPro_B_Tutorial_03.bcfnt", under,
				"SulaPro_B_Tutorial_01.bcfnt", body).draw(a);
		Bcfnt alone = new GlyphDrawing.Pen(Typeface.M_PLUS_ROUNDED, "SulaPro_B_Tutorial_03.bcfnt", under).draw(a);
		assertEquals(top.glyph('A').charWidth, layer.glyph('A').charWidth, "the same advance, so the layers stay together");
		int wider = layer.glyph('A').glyphWidth - top.glyph('A').glyphWidth;
		assertTrue(wider >= 3 && wider <= 5, "grown about 2 pixels a side: " + wider);
		assertEquals(top.glyph('A').left - 2, layer.glyph('A').left, 1);
		assertTrue(alone.glyph('A').charWidth > top.glyph('A').charWidth, "measured alone, it would be drawn bigger");
	}

	private static int lowestInk(Bcfnt f, int c) {
		int[] px = f.rgba(f.cmap.get(c));
		int lowest = -1;
		for (int i = 0; i < px.length; i++) {
			if ((px[i] & 0xFF) > 64) {
				lowest = i / f.cellW;
			}
		}
		return lowest;
	}
}
