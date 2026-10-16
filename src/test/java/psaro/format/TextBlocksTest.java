package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleCpk;

class TextBlocksTest {

	/** Each text by its number, lines broken by \n, without the CRLF, ; and NUL that end it; an empty one left out. */
	@Test
	void readsEachText() {
		byte[] help = SampleCpk.textBlocks(List.of("「変形」コマンドでユニットが変形", "",
				"・威力がそのままダメージ\n※水中でダメージ大幅減"));
		assertTrue(TextBlocks.is(help));
		var texts = TextBlocks.read(help);
		assertEquals(List.of("000", "002"), List.copyOf(texts.keySet()));
		assertEquals("「変形」コマンドでユニットが変形", texts.get("000"));
		assertEquals("・威力がそのままダメージ\n※水中でダメージ大幅減", texts.get("002"));
	}

	/**
	 * A text written back has its lines CRLF-ended again and its length rewritten; the empty one,
	 * the others and every text's number the game reads with it are kept.
	 */
	@Test
	void writesATextWithItsLinesAndLength() {
		byte[] help = SampleCpk.textBlocks(List.of("「変形」コマンドでユニットが変形", "", "・威力がそのままダメージ\n※水中でダメージ大幅減"));
		byte[] out = TextBlocks.write(help, Map.of("000", "変形できる", "002", "・Damage as is\n※Much less underwater"));
		assertArrayEquals(SampleCpk.textBlocks(List.of("変形できる", "", "・Damage as is\n※Much less underwater")), out);
		assertEquals(Map.of("000", "変形できる", "002", "・Damage as is\n※Much less underwater"), TextBlocks.read(out));
		assertArrayEquals(help, TextBlocks.write(help, Map.of("002", "・威力がそのままダメージ\n※水中でダメージ大幅減")));
		assertThrows(IllegalArgumentException.class, () -> TextBlocks.write(help, Map.of("000", "Transformé")));
	}

	/** Lengths that miss the file's end, or a text not ending ; NUL, are not a file of texts. */
	@Test
	void lengthsThatDoNotAddUpAreNoFileOfTexts() {
		byte[] good = SampleCpk.textBlocks(List.of("連邦軍のＭＳパイロット。"));
		byte[] longer = Arrays.copyOf(good, good.length + 2);
		byte[] noEnd = good.clone();
		noEnd[noEnd.length - 2] = '.';
		assertFalse(TextBlocks.is(longer));
		assertFalse(TextBlocks.is(noEnd));
		assertFalse(TextBlocks.is(new byte[4]));
		assertThrows(IllegalArgumentException.class, () -> TextBlocks.read(noEnd));
	}
}
