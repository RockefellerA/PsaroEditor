package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import psaro.format.Bcfnt;
import psaro.romfs.SampleRomfs;

class FreeFontTest {

	/**
	 * The game font's Japanese keeps its advances, so text laid out for them still fits; a letter
	 * only the English adds is spaced as the typeface spaces it.
	 */
	@Test
	void whatTheGameFontHasKeepsItsAdvance() {
		Bcfnt game = SampleRomfs.font("はい漢字", 9);
		FreeFont free = new FreeFont("SulaPro_EB_04a_18.bcfnt", Typeface.M_PLUS_ROUNDED, game, "");
		free.draw(List.of((int) 'は', (int) 'い', (int) 'W'));
		assertEquals(9, free.glyph('は').charWidth);
		assertEquals(9, free.glyph('い').charWidth);
		Bcfnt plain = new GlyphDrawing.Pen(Typeface.M_PLUS_ROUNDED, "SulaPro_EB_04a_18.bcfnt", game)
				.draw(List.of((int) 'は', (int) 'W'));
		assertNotEquals(9, plain.glyph('は').charWidth, "the typeface spaces it otherwise");
		assertEquals(plain.glyph('W').charWidth, free.glyph('W').charWidth, "the typeface's own advance");
		int shift = Math.round((9 - plain.glyph('は').charWidth) / 2f);
		assertEquals(plain.glyph('は').left + shift, free.glyph('は').left, "centred in the game's advance");
	}
}
