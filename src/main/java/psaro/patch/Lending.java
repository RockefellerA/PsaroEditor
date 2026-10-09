package psaro.patch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import psaro.format.Bcfnt;
import psaro.romfs.RomfsIndex;
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
	public static Map<Integer, Donor> add(String targetName, Bcfnt target, Collection<Integer> codes,
			List<Donor> donors, String extraSpace) {
		return add(targetName, target, codes, donors, extraSpace, null);
	}

	/**
	 * The font on top of a lower layer's, lent the same codes, and how the lower layer's letters
	 * are made from it.
	 */
	public record Layered(Donor top, UnderLayer how) {
	}

	/**
	 * As {@link #add(String, Bcfnt, Collection, List, String)}, for a font drawn under
	 * {@code layered}'s top font in one text's layers (null for none): what the top font has, lent
	 * or its own, is made from its letters ({@link UnderLayer}), so the layers line up, keeping
	 * the top letter's advance; only the rest comes from {@code donors}.
	 */
	public static Map<Integer, Donor> add(String targetName, Bcfnt target, Collection<Integer> codes,
			List<Donor> donors, String extraSpace, Layered layered) {
		Map<Integer, Donor> from = new TreeMap<>();
		if (layered != null) {
			List<Integer> made = new ArrayList<>();
			for (int c : codes) {
				if (!target.has(c) && layered.top().font().has(c)) {
					made.add(c);
					from.put(c, layered.top());
				}
			}
			if (!made.isEmpty()) {
				target.addGlyphsFrom(layered.how().derive(layered.top().font(), made, target), made, false);
			}
		}
		Set<Integer> derived = Set.copyOf(from.keySet());
		List<Donor> order = primaryFirst(donors);
		Map<Donor, List<Integer>> byDonor = new LinkedHashMap<>();
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
			Bcfnt src;
			double ink = otherLook(targetName, e.getKey().name()) ? inkRatio(target, donor) : Double.NaN;
			if (!Double.isNaN(ink)) {
				// another style or family sets its nominal size its own way: match the glyphs' real height instead
				src = donor.scaledTo(target, e.getValue(), ink, ink);
			} else {
				boolean same = donor.width == target.width && donor.height == target.height;
				src = same ? donor : donor.scaledTo(target, e.getValue());
			}
			target.addGlyphsFrom(src, e.getValue(), false);
		}
		// a lower layer's letter keeps its top letter's advance, widened there already
		widen(target, from.keySet().stream().filter(c -> !derived.contains(c)).toList(), extraSpace);
		return from;
	}

	/** Gives each of {@code codes} in {@code extraSpace} one more pixel of advance. */
	static void widen(Bcfnt target, Collection<Integer> codes, String extraSpace) {
		for (int c : codes) {
			if (extraSpace.codePoints().anyMatch(s -> s == c)) {
				Bcfnt.Glyph g = target.glyph(c);
				g.charWidth++;
				target.maxCharWidth = Math.max(target.maxCharWidth, g.charWidth);
			}
		}
	}

	/** Whether {@code donor} is another style or family than {@code target}, so its glyphs only approximate. */
	public static boolean otherLook(String target, String donor) {
		String style = RomfsIndex.style(target);
		String family = RomfsIndex.family(target);
		return style != null && !style.equals(RomfsIndex.style(donor))
				|| family != null && !family.equals(RomfsIndex.family(donor));
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

	/**
	 * How much taller {@code target}'s glyphs are than {@code donor}'s: the median ratio of their
	 * inked heights over the characters both have, or NaN when they share fewer than three.
	 */
	static double inkRatio(Bcfnt target, Bcfnt donor) {
		List<Double> ratios = new ArrayList<>();
		for (int c : target.cmap.keySet()) {
			if (c > 0x20 && donor.has(c)) {
				int t = inkHeight(target, c);
				int d = inkHeight(donor, c);
				if (t > 0 && d > 0) {
					ratios.add((double) t / d);
				}
			}
		}
		if (ratios.size() < 3) {
			return Double.NaN;
		}
		ratios.sort(null);
		return ratios.get(ratios.size() / 2);
	}

	/** Rows of {@code c}'s cell holding a clearly visible pixel, top to bottom; 0 for a blank glyph. */
	private static int inkHeight(Bcfnt font, int c) {
		int[] px = font.rgba(font.cmap.get(c));
		int top = -1;
		int bottom = -1;
		for (int y = 0; y < font.cellH; y++) {
			for (int x = 0; x < font.cellW; x++) {
				if ((px[y * font.cellW + x] & 0xFF) > 64) {
					if (top < 0) {
						top = y;
					}
					bottom = y;
					break;
				}
			}
		}
		return top < 0 ? 0 : bottom - top + 1;
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
