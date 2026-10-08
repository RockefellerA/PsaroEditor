package psaro.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bcfnt;
import psaro.patch.FontPatcher;
import psaro.render.TextRenderer;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Shown;
import psaro.romfs.RomfsIndex.Usage;

/**
 * How a string's English fares in the panes that show it: whether it fits, and which characters
 * their fonts lack.
 *
 * <p>A line of English must fit the pane's box across: the game carries a character that would
 * cross the box's edge to a new line, mid-word ("Ye", then "s"), so any such break counts as too
 * wide. Down, the lines may use the box's height or the most room the game already gives
 * Japanese there when that is larger: the string's own Japanese, or the tallest Japanese in any
 * pane of the same box, font, size and spacing. Text below the box is still drawn, and a button
 * whose Japanese is one line looks as fine with two as its same-sized neighbours do, so holding
 * the English to less would only raise false alarms.
 *
 * <p>Many panes have a drop-shadow twin drawing the same text in a blurred font. The twin is
 * left out of {@link #previewable} and of the fit, since it shares the main pane's box, but its
 * font still counts for missing glyphs: a character it lacks loses its shadow in the game.
 *
 * <p>A character the pane's font lacks is measured with the glyph the font patch would give it
 * ({@link FontPatcher#preview}); only one no donor has is measured with the stand-in typeface,
 * which makes the result an estimate.
 */
public record Fit(boolean shown, boolean tooWide, boolean tooTall, Set<Integer> missing, Set<Integer> unpatchable,
		boolean estimate) {

	private static final Fit NOT_SHOWN = new Fit(false, false, false, Set.of(), Set.of(), false);
	private static final double TOLERANCE = 0.5;
	/**
	 * Per index, the tallest Japanese in each group of same-shaped panes, keyed by the group's
	 * list from {@link RomfsIndex#sameShape}.
	 */
	private static final Map<RomfsIndex, Map<List<Shown>, Double>> ROOM =
			Collections.synchronizedMap(new WeakHashMap<>());

	/** What set the height English may use. */
	public enum Limit {
		/** The pane's box. */
		BOX,
		/** This string's Japanese, which runs below the box. */
		JAPANESE,
		/** The largest Japanese in a pane of the same shape. */
		SAME_SHAPE
	}

	/**
	 * The English measured in one pane: {@code tooWide} when the game would break a line of it
	 * (where is in {@code english.breaks()}), {@code tooTall} when its lines need more than
	 * {@code limitHeight}, which {@code heightBy} names the source of.
	 */
	public record Judgement(TextRenderer.Result english, double boxWidth, double boxHeight, double limitHeight,
			Limit heightBy, boolean tooWide, boolean tooTall) {

		public boolean overflows() {
			return tooWide || tooTall;
		}

		/** True when stand-in characters make the measurement an estimate. */
		public boolean estimate() {
			return english.standInOnly() || !english.standIn().isEmpty();
		}
	}

	public boolean overflows() {
		return tooWide || tooTall;
	}

	/**
	 * Measures {@code english} in {@code u}'s pane against the room the game gives Japanese there,
	 * with what the pane's font lacks as the font patch would add it. The original Japanese (a
	 * string marked to keep it) in a pane left as the layout has it always fits: the game already
	 * shows it that way, so a measured overflow would only be the measuring's mistake.
	 */
	public static Judgement judge(FontPatcher fonts, Usage u, String english, String japanese) {
		RomfsIndex index = fonts.index();
		Bcfnt font = fonts.current(u);
		Supplier<List<Bcfnt>> donors = donors(fonts, u);
		TextInfo info = fonts.text(u);
		TextRenderer.Result en = TextRenderer.measure(font, donors, info, english);
		boolean changed = changed(fonts, u);
		// the room the game gives the Japanese is the layout's own, whatever this pane was changed to
		TextRenderer.Result jp = changed ? TextRenderer.measure(font(index, u), u.pane().text(), japanese)
				: TextRenderer.measure(font, donors, info, japanese);
		double room = room(index, u);
		double jpH = jp.textBounds().getHeight();
		Limit heightBy = largest(info.boxHeight(), jpH, room);
		double h = Math.max(info.boxHeight(), Math.max(jpH, room));
		boolean asShipped = english.equals(japanese) && !changed;
		return new Judgement(en, info.boxWidth(), info.boxHeight(), h, heightBy, !asShipped && en.tooWide(),
				!asShipped && en.textBounds().getHeight() > h + TOLERANCE);
	}

	/** Whether {@code u}'s pane was given another font, box or type settings than the layout's. */
	static boolean changed(FontPatcher fonts, Usage u) {
		return !fonts.overrides().get(u.layout(), u.pane().name()).isEmpty();
	}

	/** Which of the three is largest; a tie goes to the earlier one. */
	private static Limit largest(double box, double japanese, double sameShape) {
		if (sameShape > box && sameShape > japanese) {
			return Limit.SAME_SHAPE;
		}
		return japanese > box ? Limit.JAPANESE : Limit.BOX;
	}

	/**
	 * The tallest Japanese the game shows in panes shaped like {@code u}'s, each measured in its
	 * own archive's font. Left out are panes in the system font or whose font lacks some of their
	 * Japanese, whose sizes would only be guesses, and Japanese the game would break across the
	 * box: mostly placeholder text that the game replaces before showing it.
	 */
	private static double room(RomfsIndex index, Usage u) {
		List<Shown> same = index.sameShape(u);
		Map<List<Shown>, Double> known = ROOM.computeIfAbsent(index, k -> Collections.synchronizedMap(new IdentityHashMap<>()));
		Double cached = known.get(same);
		if (cached != null) {
			return cached;
		}
		double h = 0;
		for (Shown s : same) {
			Bcfnt font = font(index, s.usage());
			if (font == null) {
				continue;
			}
			TextRenderer.Result r = TextRenderer.measure(font, s.usage().pane().text(), s.japanese());
			if (r.missing().isEmpty() && !r.tooWide()) {
				h = Math.max(h, r.textBounds().getHeight());
			}
		}
		known.put(same, h);
		return h;
	}

	/** Checks {@code english} in every pane that shows the string. */
	public static Fit check(FontPatcher fonts, List<Usage> usages, String english, String japanese) {
		if (usages.isEmpty()) {
			return NOT_SHOWN;
		}
		boolean wide = false;
		boolean tall = false;
		boolean estimate = false;
		Set<Integer> missing = new LinkedHashSet<>();
		Set<Integer> unpatchable = new LinkedHashSet<>();
		List<Usage> main = previewable(usages);
		for (Usage u : usages) {
			if (main.contains(u)) {
				Judgement j = judge(fonts, u, english, japanese);
				missing.addAll(j.english().missing());
				unpatchable.addAll(j.english().standIn());
				wide |= j.tooWide();
				tall |= j.tooTall();
				estimate |= j.estimate();
			} else {
				TextRenderer.Result r = TextRenderer.measure(fonts.current(u), donors(fonts, u), fonts.text(u), english);
				missing.addAll(r.missing());
				unpatchable.addAll(r.standIn());
			}
		}
		return new Fit(true, wide, tall, missing, unpatchable, estimate);
	}

	/** The panes worth previewing: a layout's shadow panes are dropped when it has a main pane. */
	public static List<Usage> previewable(List<Usage> usages) {
		List<Usage> out = new ArrayList<>();
		for (Usage u : usages) {
			boolean twin = isShadow(u) && usages.stream()
					.anyMatch(o -> !isShadow(o) && o.archive().equals(u.archive()) && o.layout().equals(u.layout()));
			if (!twin) {
				out.add(u);
			}
		}
		return out;
	}

	static boolean isShadow(Usage u) {
		String name = u.pane().name().toLowerCase(Locale.ROOT);
		return name.contains("shad") || name.contains("sdw") || name.endsWith("_sh");
	}

	/** The pane's font, or null for the system font or an archive that cannot be read. */
	static Bcfnt font(RomfsIndex index, Usage u) {
		try {
			return index.font(u);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** The pane's font as patched, to borrow what it lacks from; built only when asked for. */
	static Supplier<List<Bcfnt>> donors(FontPatcher fonts, Usage u) {
		return () -> {
			Bcfnt patched = fonts.preview(u);
			return patched == null ? List.of() : List.of(patched);
		};
	}

	/** Missing characters as a short readable list. */
	public static String describe(Set<Integer> missing) {
		StringBuilder out = new StringBuilder();
		for (int cp : missing) {
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(cp == ' ' ? "space" : cp == 0x3000 ? "ideographic-space" : Character.toString(cp));
		}
		return out.toString();
	}
}
