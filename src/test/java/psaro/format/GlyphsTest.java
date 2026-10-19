package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GlyphsTest {

	/**
	 * A code is a cell of the font, counted from 1; line breaks in the table are not cells. The
	 * line-break command shows as one; any other command, and a cell no character is known for,
	 * as its four hex digits in braces, which encode back to it.
	 */
	@Test
	void decodesCellsLineBreaksAndCommands() {
		Glyphs g = Glyphs.of("あい\nう�");
		int[] codes = {1, 2, Glyphs.NEW_LINE, 3, 0x8074, 4};
		assertEquals("あい\nう{8074}{0004}", g.decode(codes));
		assertArrayEquals(codes, g.encode("あい\nう{8074}{0004}"));
		assertEquals('う', g.character(3));
		assertEquals(-1, g.character(4));
		assertEquals(-1, g.character(0x8074));
	}

	/** A character the font has twice is written as its first. */
	@Test
	void aCharacterTwiceIsWrittenAsItsFirst() {
		assertArrayEquals(new int[] {1, 1}, Glyphs.of("ああ").encode("ああ"));
	}

	/** Text with a character the font has no cell for is refused, naming it. */
	@Test
	void refusesACharacterTheFontLacks() {
		Glyphs g = Glyphs.of("あ");
		assertEquals("X", g.unencodable("あX"));
		assertNull(g.unencodable("あ{8001}"));
		assertThrows(IllegalArgumentException.class, () -> g.encode("あX"));
	}

	/**
	 * Lost Heroes' table follows its font: the blank and ？, full-width digits and letters, kana,
	 * the ASCII letters and signs from ! (with a narrow space where " would be), punctuation, then
	 * the kanji, with … among them.
	 */
	@Test
	void lostHeroesFollowsTheFont() {
		Glyphs g = Glyphs.lostHeroes();
		assertEquals('　', g.character(1));
		assertEquals('？', g.character(2));
		assertEquals('０', g.character(3));
		assertEquals('ぁ', g.character(65));
		assertEquals('ァ', g.character(148));
		assertEquals('ヶ', g.character(233));
		assertEquals('!', g.character(234));
		assertEquals(' ', g.character(235));
		assertEquals('、', g.character(327));
		assertEquals('！', g.character(329));
		assertEquals('ー', g.character(330));
		assertEquals('戦', g.character(364));
		assertEquals('…', g.character(538));
		assertArrayEquals(new int[] {290, 302, 316, 235, 234}, g.encode("Yes !"));
	}
}
