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
		return blocks(chars, 0xFFFFFFFF, 0x000000FF);
	}

	/** An LA8 font of {@code fill} blocks ringed by a 1-pixel {@code ring} outline. */
	private static Bcfnt blocks(String chars, int fill, int ring) {
		Bcfnt f = SampleRomfs.font(chars, 10);
		for (Bcfnt.Glyph g : f.glyphs) {
			int[] px = new int[f.cellW * f.cellH];
			for (int y = 1; y <= f.baseline; y++) {
				for (int x = 0; x < f.cellW; x++) {
					boolean edge = y == 1 || y == f.baseline || x == 0 || x == f.cellW - 1;
					px[y * f.cellW + x] = Texture.fromRgba(edge ? ring : fill, f.format);
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

	/** Dark letters in a light outline, like the game's 10a style: not white letters with no outline. */
	@Test
	void aLightOutlineRoundDarkLettersIsRead() {
		GlyphDrawing.Look look = GlyphDrawing.look(blocks("あいうえお", 0x000000FF, 0xFFFFFFFF));
		assertEquals(0x000000FF, look.fill());
		assertEquals(0xFFFFFFFF, look.outline());
		assertTrue(look.radius() > 0.5 && look.radius() < 2, "radius " + look.radius());
	}

	/** Light letters over a dark shadow below, like the game's 02a style: no outline. */
	@Test
	void aDropShadowIsNotAnOutline() {
		Bcfnt f = SampleRomfs.font("あいうえお", 10);
		for (Bcfnt.Glyph g : f.glyphs) {
			int[] px = new int[f.cellW * f.cellH];
			for (int y = 1; y <= f.baseline; y++) {
				for (int x = 0; x < f.cellW; x++) {
					boolean shadow = y >= f.baseline - 1;
					px[y * f.cellW + x] = Texture.fromRgba(shadow ? 0x000000FF : 0xFFFFFFFF, f.format);
				}
			}
			g.pixels = px;
		}
		GlyphDrawing.Look look = GlyphDrawing.look(f);
		assertEquals(0, look.radius());
		assertEquals(0xFFFFFFFF, look.fill());
	}

	@Test
	void aLetterComesOutTheSameWhicheverOthersAreDrawnWithIt() {
		Bcfnt target = outlined("あいうえお漢字");
		Bcfnt alone = GlyphDrawing.draw(Typeface.M_PLUS_ROUNDED, "SulaPro_B_04a_20.bcfnt", target, List.of((int) 'e'));
		Bcfnt many = GlyphDrawing.draw(Typeface.M_PLUS_ROUNDED, "SulaPro_B_04a_20.bcfnt", target, List.of((int) 'W', (int) 'e', (int) 'g'));
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
	 * Outlined, f and t sit a pixel right of where the typeface puts them, so their outline leaves
	 * the letter before alone, and keep the typeface's advance; other letters, and plain fonts' f
	 * and t, sit where it puts them.
	 */
	@Test
	void anOutlinedFontsFAndTAreNudgedRightWithTheirAdvanceKept() {
		String name = "SulaPro_B_04a_20.bcfnt";
		List<Integer> codes = List.of((int) 'f', (int) 't', (int) 'l');
		for (Bcfnt target : List.of(outlined("あいうえお漢字"), SampleRomfs.font("あいうえお漢字", 10))) {
			GlyphDrawing.Look look = GlyphDrawing.look(target);
			Bcfnt drawn = GlyphDrawing.draw(Typeface.M_PLUS_ROUNDED, name, target, codes);
			String weight = Typeface.weightFor(name);
			java.awt.Font sized = Typeface.M_PLUS_ROUNDED.font(weight).deriveFont((float) GlyphDrawing.emSize(target, look, weight));
			var frc = new java.awt.font.FontRenderContext(new java.awt.geom.AffineTransform(), true, true);
			for (int c : codes) {
				var gv = sized.createGlyphVector(frc, Character.toChars(c));
				// the first column the letter and its outline touch
				int left = (int) Math.floor(gv.getOutline().getBounds2D().getMinX() - look.radius());
				int nudge = look.radius() > 0 && c != 'l' ? 1 : 0;
				assertEquals(left + nudge, drawn.glyph(c).left, (char) c + " with outline " + look.radius());
				assertEquals((int) Math.round(gv.getGlyphMetrics(0).getAdvanceX()), drawn.glyph(c).charWidth);
			}
		}
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
