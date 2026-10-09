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
 * font still counts for missing glyphs: a character it lacks loses its shadow in the game. So
 * do the other layers of panes stacked to draw one text ({@link #layers}).
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
	 * shows it that way, so a measured overflow would only be the measuring's mistake. In a
	 * changed pane it is too wide only when the change breaks it into more lines than the game's
	 * own did: placeholder digits like {@code 1234567890} overflow their one-digit box as shipped.
	 */
	public static Judgement judge(FontPatcher fonts, Usage u, String english, String japanese) {
		RomfsIndex index = fonts.index();
		fonts.prepare(u, english);
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
		boolean original = english.equals(japanese);
		boolean asShipped = original && !changed;
		// the game's own text in a changed pane: as wide as the game had it is no worse than the game
		boolean noWorse = original && changed && jp.tooWide() && en.breaks().size() <= jp.breaks().size();
		return new Judgement(en, info.boxWidth(), info.boxHeight(), h, heightBy, !asShipped && !noWorse && en.tooWide(),
				!asShipped && en.textBounds().getHeight() > h + TOLERANCE);
	}

	/**
	 * Whether {@code u}'s pane was given another font, box or type settings than the layout's, or
	 * its font is drawn from a bundled typeface in the patch.
	 */
	static boolean changed(FontPatcher fonts, Usage u) {
		return !fonts.overrides().get(u.layout(), u.pane().name()).isEmpty() || fonts.drawnWith(u).bundled();
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
				fonts.prepare(u, english);
				TextRenderer.Result r = TextRenderer.measure(fonts.current(u), donors(fonts, u), fonts.text(u), english);
				missing.addAll(r.missing());
				unpatchable.addAll(r.standIn());
			}
		}
		return new Fit(true, wide, tall, missing, unpatchable, estimate);
	}

	/**
	 * The panes worth previewing: a layout's shadow panes are dropped when it has a main pane, and
	 * of panes stacked into one ({@link #layers}) only the first is kept.
	 */
	public static List<Usage> previewable(List<Usage> usages) {
		List<Usage> out = new ArrayList<>();
		for (Usage u : usages) {
			boolean twin = isShadow(u) && usages.stream()
					.anyMatch(o -> !isShadow(o) && o.archive().equals(u.archive()) && o.layout().equals(u.layout()));
			boolean layer = out.stream().anyMatch(o -> layers(o, usages).contains(u));
			if (!twin && !layer) {
				out.add(u);
			}
		}
		return out;
	}

	/**
	 * The panes among {@code all} drawn together with {@code u} as one text, which take its
	 * changes: its layout's shadow panes, and the panes stacked on it to draw one text in layers
	 * (a fill over an outline, say), which show the same string from about the same place in the
	 * same box, and the panes stacked on those. A layer changed on its own draws its letters
	 * apart from the rest.
	 */
	public static List<Usage> layers(Usage u, List<Usage> all) {
		List<Usage> out = new ArrayList<>();
		for (Usage o : all) {
			if (isShadow(o) && o.archive().equals(u.archive()) && o.layout().equals(u.layout())) {
				addPane(out, u, o);
			}
		}
		// stacked on u, or on a pane stacked on it
		List<Usage> stack = new ArrayList<>(List.of(u));
		for (int i = 0; i < stack.size(); i++) {
			for (Usage o : all) {
				if (stacked(stack.get(i), o) && stack.stream().noneMatch(x -> x.pane().name().equals(o.pane().name()))) {
					stack.add(o);
					addPane(out, u, o);
				}
			}
		}
		return out;
	}

	/** Adds {@code o} to {@code out} unless it is {@code u}'s pane or one already there. */
	private static void addPane(List<Usage> out, Usage u, Usage o) {
		String name = o.pane().name();
		if (!name.equals(u.pane().name()) && out.stream().noneMatch(x -> x.pane().name().equals(name))) {
			out.add(o);
		}
	}

	/** Whether {@code a} and {@code b} are two panes of one layout drawn on top of one another. */
	private static boolean stacked(Usage a, Usage b) {
		return a.archive().equals(b.archive()) && a.layout().equals(b.layout())
				&& !a.pane().name().equals(b.pane().name()) && a.pane().stacksOn(b.pane())
				&& a.pane().text().boxWidth() == b.pane().text().boxWidth()
				&& a.pane().text().boxHeight() == b.pane().text().boxHeight();
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
