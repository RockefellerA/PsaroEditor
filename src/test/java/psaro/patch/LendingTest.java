package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.format.Bcfnt;
import psaro.romfs.RomfsIndex.Donor;
import psaro.romfs.SampleRomfs;

class LendingTest {

	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	private static Donor donor(String name, Bcfnt font) {
		return new Donor(Path.of(name + ".arc.lz"), name + ".bcfnt", font);
	}

	private static List<Integer> codes(String s) {
		return s.codePoints().boxed().toList();
	}

	@Test
	void everyLetterComesFromTheFirstDonorWithTheWholeAlphabet() {
		Bcfnt target = SampleRomfs.font("あ", 10);
		Donor partial = donor("partial", SampleRomfs.font("Lv", 5));
		Donor full = donor("full", SampleRomfs.font(ALPHABET, 8));
		Donor punctuation = donor("punct", SampleRomfs.font("%", 6));
		Map<Integer, Donor> from = Lending.add(target, codes("Lv%"), List.of(partial, full, punctuation), "");
		assertSame(full, from.get((int) 'L'));
		assertSame(full, from.get((int) 'v'));
		assertSame(punctuation, from.get((int) '%'));
		assertEquals(8, target.glyph('L').charWidth);
		assertTrue(target.has('あ'));
	}

	@Test
	void withoutAWholeAlphabetDonorsAreTakenInOrder() {
		Bcfnt target = SampleRomfs.font("あ", 10);
		Donor first = donor("first", SampleRomfs.font("Lv", 5));
		Donor second = donor("second", SampleRomfs.font("Lvx", 8));
		Map<Integer, Donor> from = Lending.add(target, codes("Lvx"), List.of(first, second), "");
		assertSame(first, from.get((int) 'L'));
		assertSame(second, from.get((int) 'x'));
	}

	@Test
	void aDonorOfOtherProportionsIsScaledAndExtraSpaceIsAdded() {
		Bcfnt target = SampleRomfs.font("あ", 10);
		Bcfnt big = SampleRomfs.font(ALPHABET, 8);
		big.width = 32;
		big.height = 32;
		Lending.add(target, codes("st"), List.of(donor("big", big)), "t");
		assertEquals(4, target.glyph('s').charWidth);
		assertEquals(5, target.glyph('t').charWidth);
	}

	@Test
	void keepsWhatTheFontHasAndLeavesOutWhatNoDonorHas() {
		Bcfnt target = SampleRomfs.font("あA", 10);
		Map<Integer, Donor> from = Lending.add(target, codes("AB!"), List.of(donor("d", SampleRomfs.font("AB", 8))), "");
		assertEquals(List.of((int) 'B'), List.copyOf(from.keySet()));
		assertEquals(10, target.glyph('A').charWidth);
		assertFalse(target.has('!'));
	}
}
