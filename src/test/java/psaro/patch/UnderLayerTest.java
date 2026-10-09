package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

import psaro.format.Bcfnt;
import psaro.format.Texture;
import psaro.romfs.RomfsIndex.Donor;
import psaro.romfs.SampleRomfs;

class UnderLayerTest {

	private static final String JAPANESE = "あいうえお漢字";

	/**
	 * A font of {@code top}'s letters made into a lower layer: each pixel {@code alphaAt} its
	 * distance from the top letter's ink, in black, {@code by} pixels larger each way and moved
	 * {@code down}.
	 */
	private static Bcfnt layer(Bcfnt top, String chars, int by, int down, DoubleUnaryOperator alphaAt) {
		Bcfnt f = new Bcfnt();
		f.format = Texture.LA8;
		f.width = top.width;
		f.height = top.height;
		f.ascent = top.ascent;
		f.lineFeed = top.lineFeed;
		f.cellW = top.cellW + 2 * by;
		f.cellH = top.cellH + 2 * by + down;
		f.baseline = top.baseline + by;
		chars.codePoints().forEach(c -> {
			Bcfnt.Glyph g = top.glyph(c);
			int[] ink = top.rgba(top.cmap.get(c));
			int[] px = new int[f.cellW * f.cellH];
			for (int y = 0; y < f.cellH; y++) {
				for (int x = 0; x < f.cellW; x++) {
					double best = Double.MAX_VALUE;
					for (int ty = 0; ty < top.cellH; ty++) {
						for (int tx = 0; tx < top.cellW; tx++) {
							if ((ink[ty * top.cellW + tx] & 0xFF) >= 128) {
								best = Math.min(best, Math.hypot(tx - (x - by), ty - (y - by - down)));
							}
						}
					}
					int a = (int) Math.round(alphaAt.applyAsDouble(best));
					px[y * f.cellW + x] = a == 0 ? 0 : Texture.fromRgba(a, f.format);
				}
			}
			f.cmap.put(c, f.glyphs.size());
			f.glyphs.add(new Bcfnt.Glyph(px, g.left - by, g.glyphWidth + 2 * by, g.charWidth));
		});
		f.fitSheets(256, 512);
		return f;
	}

	/** An outline 2 pixels thick: solid to 2 pixels from the letter, nothing past. */
	private static double outline(double d) {
		return d <= 2 ? 255 : 0;
	}

	/** A glow: solid on the letter, fading over 4 pixels past it. */
	private static double glow(double d) {
		return Math.max(0, 255 - 60 * d);
	}

	private static int alpha(Bcfnt f, int c, int x, int y) {
		return f.rgba(f.cmap.get(c))[y * f.cellW + x] & 0xFF;
	}

	/** {@code c}'s pixel in {@code f} at pen position ({@code x}, {@code y}), y up from the baseline negative. */
	private static int alphaAtPen(Bcfnt f, int c, int x, int y) {
		Bcfnt.Glyph g = f.glyph(c);
		int cx = x - g.left;
		int cy = y + f.baseline;
		return cx < 0 || cy < 0 || cx >= f.cellW || cy >= f.cellH ? 0 : alpha(f, c, cx, cy);
	}

	@Test
	void anOutlinesLettersAreMadeFromTheTopLettersGrown() {
		Bcfnt top = SampleRomfs.font(JAPANESE + "Z", 10);
		Bcfnt under = layer(top, JAPANESE, 3, 0, UnderLayerTest::outline);
		UnderLayer how = UnderLayer.learn(under, top);
		assertNotNull(how);
		Bcfnt made = how.derive(top, List.of((int) 'Z'), under);
		// the sample's block runs pen x 1..10 and y -9..-1
		assertEquals(255, alphaAtPen(made, 'Z', 5, -5), "on the letter");
		assertEquals(255, alphaAtPen(made, 'Z', -1, -5), "2 pixels out");
		assertEquals(0, alphaAtPen(made, 'Z', -2, -5), "past the outline");
		assertEquals(top.glyph('Z').charWidth, made.glyph('Z').charWidth, "the top letter's advance");
		assertEquals(0x000000FF, made.rgba(made.cmap.get((int) 'Z'))[made.baseline * made.cellW + 3] | 0xFF,
				"in the layer's colour");
	}

	@Test
	void aGlowFadesAsTheGamesDoes() {
		Bcfnt top = SampleRomfs.font(JAPANESE + "Z", 10);
		UnderLayer how = UnderLayer.learn(layer(top, JAPANESE, 5, 0, UnderLayerTest::glow), top);
		assertNotNull(how);
		Bcfnt made = how.derive(top, List.of((int) 'Z'), top);
		int one = alphaAtPen(made, 'Z', 0, -5);
		int three = alphaAtPen(made, 'Z', -2, -5);
		assertTrue(Math.abs(one - 195) <= 20, "1 pixel out: " + one);
		assertTrue(Math.abs(three - 75) <= 20, "3 pixels out: " + three);
	}

	@Test
	void aShadowCastDownIsMovedDown() {
		Bcfnt top = SampleRomfs.font(JAPANESE + "Z", 10);
		UnderLayer how = UnderLayer.learn(layer(top, JAPANESE, 1, 2, UnderLayerTest::outline), top);
		assertNotNull(how);
		Bcfnt made = how.derive(top, List.of((int) 'Z'), top);
		// the block's bottom row is pen y -1: an outline 2 out, moved 2 down, reaches y 3
		assertEquals(255, alphaAtPen(made, 'Z', 5, 3));
		assertEquals(0, alphaAtPen(made, 'Z', 5, 4));
		assertEquals(0, alphaAtPen(made, 'Z', 5, -12), "nor as high above");
	}

	@Test
	void fontsThePaneScalesApartOrThatDrawOtherLettersAreNotLayers() {
		Bcfnt top = SampleRomfs.font(JAPANESE, 10);
		Bcfnt sized = layer(top, JAPANESE, 2, 0, UnderLayerTest::outline);
		sized.height = 24;
		assertNull(UnderLayer.learn(sized, top));
		assertNull(UnderLayer.learn(layer(top, "あい", 2, 0, UnderLayerTest::outline), top), "too few letters shared");
		// a font of other letters: each a thin bar where the top's are blocks
		Bcfnt other = SampleRomfs.font(JAPANESE, 10);
		for (Bcfnt.Glyph g : other.glyphs) {
			int[] px = new int[other.cellW * other.cellH];
			for (int y = 0; y < other.cellH; y++) {
				px[y * other.cellW] = Texture.fromRgba(0xFFFFFFFF, other.format);
			}
			g.pixels = px;
		}
		assertNull(UnderLayer.learn(other, top));
	}

	@Test
	void aLowerLayerIsLentItsTopFontsLettersMadeIntoItKeepingTheirAdvance() {
		Bcfnt top = SampleRomfs.font(JAPANESE + "Z", 10);
		Bcfnt under = layer(top, JAPANESE, 3, 0, UnderLayerTest::outline);
		UnderLayer how = UnderLayer.learn(under, top);
		Donor topDonor = new Donor(Path.of("a.arc.lz"), "top.bcfnt", top);
		Bcfnt lent = under.copy();
		Map<Integer, Donor> from = Lending.add("under.bcfnt", lent, List.of((int) 'Z'), List.of(), "Z",
				new Lending.Layered(topDonor, how));
		assertEquals(Map.of((int) 'Z', topDonor), from);
		assertEquals(10, lent.glyph('Z').charWidth, "the top letter's advance, no extra space of its own");
		assertEquals(255, alphaAtPen(lent, 'Z', -1, -5));
	}

	@Test
	void aLowerLayersStandInIsMadeFromTheTopFontsStandIn() {
		Bcfnt topFont = SampleRomfs.font(JAPANESE, 10);
		Bcfnt under = layer(topFont, JAPANESE, 3, 0, UnderLayerTest::outline);
		UnderLayer how = UnderLayer.learn(under, topFont);
		FreeFont top = new FreeFont("SulaPro_B_Tutorial_01.bcfnt", Typeface.M_PLUS_ROUNDED, topFont, "");
		FreeFont lower = new FreeFont("SulaPro_B_Tutorial_03.bcfnt", under, top, how);
		assertTrue(lower.layered());
		lower.draw(List.of((int) 'A'));
		assertEquals(top.glyph('A').charWidth, lower.glyph('A').charWidth);
		assertEquals(Typeface.M_PLUS_ROUNDED, lower.drawnBy('A'));
		int wider = lower.glyph('A').glyphWidth - top.glyph('A').glyphWidth;
		assertTrue(wider >= 3 && wider <= 6, "about 2 pixels a side: " + wider);
		assertEquals(under.format, lower.build(List.of((int) 'A')).format, "in the game font's format");
	}
}
