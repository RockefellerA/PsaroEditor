package psaro.patch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import psaro.format.Bcfnt;
import psaro.romfs.RomfsIndex.Donor;

/** Adds the glyphs a font lacks from donor fonts, the same way for the preview and the patch. */
public final class Lending {

	private Lending() {
	}

	/**
	 * Whether a font may borrow {@code codePoint}: anything below the kana and kanji, which each
	 * font already draws for its own Japanese.
	 */
	public static boolean lendable(int codePoint) {
		return codePoint >= 0x20 && codePoint < 0x3000;
	}

	/**
	 * Adds each of {@code codes} that {@code target} lacks, resampled to the target's proportions
	 * when the donor's differ, and gives each added character in {@code extraSpace} one more pixel
	 * of advance (a donor's outline can crowd the next letter). The target's own glyphs are kept.
	 *
	 * <p>So that a word does not mix weights or sizes, every character comes from the first of
	 * {@code donors} with the whole alphabet when it has it; only what that font lacks comes from
	 * the others, in order. Returns the donor each added code came from; a code no donor has is
	 * left out.
	 */
	public static Map<Integer, Donor> add(Bcfnt target, Collection<Integer> codes, List<Donor> donors,
			String extraSpace) {
		List<Donor> order = primaryFirst(donors);
		Map<Donor, List<Integer>> byDonor = new LinkedHashMap<>();
		Map<Integer, Donor> from = new TreeMap<>();
		for (int c : codes) {
			if (target.has(c) || from.containsKey(c)) {
				continue;
			}
			for (Donor d : order) {
				if (d.font().has(c)) {
					byDonor.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
					from.put(c, d);
					break;
				}
			}
		}
		for (Map.Entry<Donor, List<Integer>> e : byDonor.entrySet()) {
			Bcfnt donor = e.getKey().font();
			boolean same = donor.width == target.width && donor.height == target.height;
			target.addGlyphsFrom(same ? donor : donor.scaledTo(target, e.getValue()), e.getValue(), false);
		}
		for (int c : from.keySet()) {
			if (extraSpace.codePoints().anyMatch(s -> s == c)) {
				Bcfnt.Glyph g = target.glyph(c);
				g.charWidth++;
				target.maxCharWidth = Math.max(target.maxCharWidth, g.charWidth);
			}
		}
		return from;
	}

	/**
	 * {@code donors} with the first that has all of A-Z and a-z moved to the front, then its other
	 * copies; unchanged when none has.
	 */
	static List<Donor> primaryFirst(List<Donor> donors) {
		Donor primary = donors.stream().filter(d -> hasAlphabet(d.font())).findFirst().orElse(null);
		if (primary == null) {
			return donors;
		}
		List<Donor> out = new ArrayList<>(List.of(primary));
		donors.stream().filter(d -> d != primary && d.name().equals(primary.name())).forEach(out::add);
		donors.stream().filter(d -> !d.name().equals(primary.name())).forEach(out::add);
		return out;
	}

	private static boolean hasAlphabet(Bcfnt font) {
		for (char c = 'A'; c <= 'Z'; c++) {
			if (!font.has(c) || !font.has(Character.toLowerCase(c))) {
				return false;
			}
		}
		return true;
	}

	/** Every code point some donor could lend. */
	public static List<Integer> lendableFrom(List<Donor> donors) {
		TreeMap<Integer, Boolean> all = new TreeMap<>();
		for (Donor d : donors) {
			for (int c : d.font().cmap.keySet()) {
				if (lendable(c)) {
					all.put(c, true);
				}
			}
		}
		return List.copyOf(all.keySet());
	}
}
