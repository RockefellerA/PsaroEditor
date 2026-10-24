package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The HOME Menu's names for a title, in its SMDH ({@link Smdh}). */
class SmdhTest {

	private static final String JAPANESE = "シアトリズム ドラゴンクエスト";

	/** Each language's names are read from its slot; a language left empty reads empty. */
	@Test
	void eachLanguagesNamesAreRead() {
		byte[] smdh = SampleCia.smdh(JAPANESE, "SQUARE ENIX");
		assertTrue(Smdh.is(smdh));
		assertFalse(Smdh.is(Arrays.copyOf(smdh, 0x100)));
		assertEquals(new Smdh.Title(JAPANESE, JAPANESE, "SQUARE ENIX"), Smdh.title(smdh, 0));
		assertEquals(new Smdh.Title(JAPANESE, JAPANESE, "SQUARE ENIX"), Smdh.title(smdh, 1));
		assertEquals(new Smdh.Title("", "", ""), Smdh.title(smdh, 2));
	}

	/**
	 * A title goes into every language but Japanese, the empty ones too, a shorter one leaving
	 * nothing of the longer one behind; Japanese keeps the game's own. A part left blank keeps each
	 * language's own, the empty ones taking the English's; the icons are kept.
	 */
	@Test
	void aTitleGoesIntoEveryLanguage() {
		byte[] smdh = SampleCia.smdh(JAPANESE, "SQUARE ENIX");
		// a language of its own, so it can be seen to keep it
		byte[] spanish = "SQUARE ENIX ES".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
		System.arraycopy(spanish, 0, smdh, 8 + 5 * 0x200 + 0x180, spanish.length);
		byte[] out = Smdh.withTitle(smdh, new Smdh.Title("TDQ", "Theatrhythm Dragon Quest", " "));
		assertEquals(Smdh.SIZE, out.length);
		assertEquals(new Smdh.Title(JAPANESE, JAPANESE, "SQUARE ENIX"), Smdh.title(out, Smdh.JAPANESE));
		for (int language = Smdh.ENGLISH; language < Smdh.LANGUAGES; language++) {
			Smdh.Title t = Smdh.title(out, language);
			assertEquals("TDQ", t.shortTitle());
			assertEquals("Theatrhythm Dragon Quest", t.longTitle());
			assertEquals(language == 5 ? "SQUARE ENIX ES" : "SQUARE ENIX", t.publisher(), "language " + language);
		}
		assertArrayEquals(Arrays.copyOfRange(smdh, 0x2008, Smdh.SIZE), Arrays.copyOfRange(out, 0x2008, Smdh.SIZE));
		assertEquals(JAPANESE, Smdh.title(smdh, 0).shortTitle(), "the SMDH given is not changed");
	}

	/** A part too long for its slot, its terminator counted, is refused with why. */
	@Test
	void aPartTooLongIsRefused() {
		assertNull(new Smdh.Title("x".repeat(63), "x".repeat(127), "x".repeat(63)).problem());
		Smdh.Title tooLong = new Smdh.Title("ok", "x".repeat(128), null);
		assertEquals("The long title is 128 characters long; the 3DS has room for 127.", tooLong.problem());
		assertThrows(IllegalArgumentException.class, () -> Smdh.withTitle(SampleCia.smdh(JAPANESE, "SQUARE ENIX"), tooLong));
		assertTrue(new Smdh.Title(null, " ", "").isBlank());
		assertFalse(new Smdh.Title(null, null, "SQUARE ENIX").isBlank());
	}
}
