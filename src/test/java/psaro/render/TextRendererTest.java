package psaro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import psaro.format.Bclyt.TextInfo;
import psaro.format.Bcfnt;
import psaro.format.Texture;
import psaro.render.TextRenderer.Result;

class TextRendererTest {

	/**
	 * A font drawn at 1:1 by a pane with font size 20: each glyph advances 10, lines feed 24,
	 * baseline 16. 'W' is wide (advance 18).
	 */
	private static Bcfnt font() {
		Bcfnt f = new Bcfnt();
		f.format = Texture.A8;
		f.cellW = 18;
		f.cellH = 20;
		f.baseline = 16;
		f.ascent = 16;
		f.height = 20;
		f.width = 20;
		f.lineFeed = 24;
		for (char c : "ABW ".toCharArray()) {
			int[] px = new int[f.cellW * f.cellH];
			for (int y = 4; y < 16; y++) {
				for (int x = 1; x < 8; x++) {
					px[y * f.cellW + x] = c == ' ' ? 0 : 0xFF;
				}
			}
			f.cmap.put((int) c, f.glyphs.size());
			f.glyphs.add(new Bcfnt.Glyph(px, 0, 8, c == 'W' ? 18 : 10));
		}
		return f;
	}

	private static TextInfo pane(float w, float h, int position, int alignment, float charSpace, float lineSpace) {
		return new TextInfo("f.bcfnt", w, h, 4, 20, 20, charSpace, lineSpace, "*", position, alignment,
				0xFFFFFFFF, 0xFFFFFFFF);
	}

	@Test
	void advancesByCharWidthWithSpacingBetweenCharacters() {
		Result r = TextRenderer.measure(font(), pane(200, 30, 0, 0, 2, 0), "ABW");
		assertEquals(10 + 2 + 10 + 2 + 18, r.textBounds().getWidth(), 1e-9);
		assertEquals(24, r.textBounds().getHeight(), 1e-9);
		assertFalse(r.overflows());
		assertTrue(r.missing().isEmpty());
	}

	@Test
	void fontSizeScalesTheFont() {
		TextInfo half = new TextInfo("f.bcfnt", 200, 30, 4, 10, 10, 0, 0, "*", 0, 0, -1, -1);
		assertEquals(10, TextRenderer.measure(font(), half, "AB").textBounds().getWidth(), 1e-9);
	}

	@Test
	void textPositionPlacesTheBlockInTheBox() {
		Rectangle2D centred = TextRenderer.measure(font(), pane(100, 40, 4, 0, 0, 0), "AB").textBounds();
		assertEquals((100 - 20) / 2.0, centred.getX(), 1e-9);
		assertEquals((40 - 24) / 2.0, centred.getY(), 1e-9);
		Rectangle2D bottomRight = TextRenderer.measure(font(), pane(100, 40, 8, 0, 0, 0), "AB").textBounds();
		assertEquals(80, bottomRight.getX(), 1e-9);
		assertEquals(16, bottomRight.getY(), 1e-9);
	}

	@Test
	void linesBreakOnlyAtNewlinesAndColourCodesTakeNoSpace() {
		Result r = TextRenderer.measure(font(), pane(200, 100, 0, 0, 0, 3), "A\u0002\u0002B\u0002\u0001\nW");
		assertEquals(20, r.textBounds().getWidth(), 1e-9);
		assertEquals(24 + 3 + 24, r.textBounds().getHeight(), 1e-9);
	}

	@Test
	void reportsTextTooWideOrTooTallForTheBox() {
		Result wide = TextRenderer.measure(font(), pane(25, 60, 0, 0, 0, 0), "ABW");
		assertTrue(wide.tooWide());
		assertFalse(wide.tooTall());
		Result tall = TextRenderer.measure(font(), pane(200, 30, 0, 0, 0, 0), "A\nB");
		assertTrue(tall.tooTall());
		assertFalse(tall.tooWide());
	}

	@Test
	void aCharacterThatWouldCrossTheBoxEdgeStartsANewLineAsInTheGame() {
		// A 10 + 2 + B 10 = 22 fits 25; + 2 + W 18 would not, so W starts the next line
		Result r = TextRenderer.measure(font(), pane(25, 60, 0, 0, 2, 3), "ABW");
		assertEquals(List.of("AB"), r.breaks());
		assertTrue(r.tooWide());
		assertEquals(22, r.textBounds().getWidth(), 1e-9);
		assertEquals(24 + 3 + 24, r.textBounds().getHeight(), 1e-9);
		// exactly filling the box is not a break
		assertTrue(TextRenderer.measure(font(), pane(22, 60, 0, 0, 2, 0), "AB").breaks().isEmpty());
	}

	@Test
	void missingGlyphsAreReportedButStillTakeSpace() {
		Result r = TextRenderer.measure(font(), pane(200, 30, 0, 0, 0, 0), "AxyA");
		assertEquals(Set.of((int) 'x', (int) 'y'), r.missing());
		assertTrue(r.textBounds().getWidth() > 20);
	}

	@Test
	void missingGlyphsAreMeasuredFromADonorAtTheDonorsOwnScale() {
		// twice the font's proportions, so its 'x' (advance 16) comes out 8 wide in the pane
		Bcfnt donor = font();
		donor.width = 40;
		donor.height = 40;
		donor.cmap.put((int) 'x', donor.glyphs.size());
		donor.glyphs.add(new Bcfnt.Glyph(new int[donor.cellW * donor.cellH], 0, 8, 16));
		Result r = TextRenderer.measure(font(), () -> List.of(donor), pane(200, 30, 0, 0, 0, 0), "AxyA");
		assertEquals(Set.of((int) 'x', (int) 'y'), r.missing());
		assertEquals(Set.of((int) 'y'), r.standIn());
		double y = TextRenderer.measure(font(), pane(200, 30, 0, 0, 0, 0), "y").textBounds().getWidth();
		assertEquals(10 + 8 + y + 10, r.textBounds().getWidth(), 1e-9);
	}

	@Test
	void donorsAreNotAskedForWhenNothingIsMissing() {
		Result r = TextRenderer.measure(font(), () -> {
			throw new AssertionError("donors looked up");
		}, pane(200, 30, 0, 0, 0, 0), "AB");
		assertTrue(r.missing().isEmpty());
	}

	@Test
	void systemFontDrawsEverythingWithTheStandIn() {
		Result r = TextRenderer.render(null, pane(200, 30, 0, 0, 0, 0), "Hello", 2);
		assertTrue(r.standInOnly());
		assertTrue(r.missing().isEmpty());
		assertTrue(r.textBounds().getWidth() > 0);
	}

	@Test
	void renderedImageCoversTheBoxAtTheZoomAndShowsTheGlyph() {
		Result r = TextRenderer.render(font(), pane(100, 40, 0, 0, 0, 0), "A", 2);
		assertTrue(r.image().getWidth() >= 200 && r.image().getHeight() >= 80);
		// the glyph's solid block (cell x 1..7, y 4..15) drawn at 2x near the box's top left
		int pad = 6;
		int argb = r.image().getRGB(pad + 4 * 2, pad + 10 * 2);
		assertEquals(0xFF, argb >>> 24);
		assertTrue((argb & 0xFF) > 0xE0, Integer.toHexString(argb));
	}
}
