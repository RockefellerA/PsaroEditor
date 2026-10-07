package psaro.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bcfnt;
import psaro.render.TextRenderer;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Usage;

/**
 * How a string's English fares in the panes that show it: whether it fits, and which characters
 * their fonts lack.
 *
 * <p>English must fit the pane's box, or the space the original Japanese already takes when that
 * is larger: some Japanese runs a little past its box and the game shows it fine, so holding the
 * English to less would only raise false alarms.
 *
 * <p>Many panes have a drop-shadow twin drawing the same text in a blurred font. The twin is
 * left out of {@link #previewable} and of the fit, since it shares the main pane's box, but its
 * font still counts for missing glyphs: a character it lacks loses its shadow in the game.
 */
public record Fit(boolean shown, boolean tooWide, boolean tooTall, Set<Integer> missing, boolean estimate) {

	private static final Fit NOT_SHOWN = new Fit(false, false, false, Set.of(), false);
	private static final double TOLERANCE = 0.5;

	/**
	 * The English measured in one pane against the space it may use: {@code limitWidth} and
	 * {@code limitHeight} are the box's, or the Japanese's where that is larger.
	 */
	public record Judgement(TextRenderer.Result english, double boxWidth, double boxHeight, double limitWidth,
			double limitHeight, boolean tooWide, boolean tooTall) {

		public boolean overflows() {
			return tooWide || tooTall;
		}

		public boolean widthRelaxed() {
			return limitWidth > boxWidth;
		}

		public boolean heightRelaxed() {
			return limitHeight > boxHeight;
		}

		/** True when stand-in characters make the measurement an estimate. */
		public boolean estimate() {
			return english.standInOnly() || !english.missing().isEmpty();
		}
	}

	public boolean overflows() {
		return tooWide || tooTall;
	}

	/** Measures {@code english} in one pane, against the box or the Japanese's extent. */
	public static Judgement judge(Bcfnt font, TextInfo info, String english, String japanese) {
		TextRenderer.Result en = TextRenderer.measure(font, info, english);
		TextRenderer.Result jp = TextRenderer.measure(font, info, japanese);
		double w = Math.max(info.boxWidth(), jp.textBounds().getWidth());
		double h = Math.max(info.boxHeight(), jp.textBounds().getHeight());
		return new Judgement(en, info.boxWidth(), info.boxHeight(), w, h,
				en.textBounds().getWidth() > w + TOLERANCE, en.textBounds().getHeight() > h + TOLERANCE);
	}

	/** Checks {@code english} in every pane that shows the string. */
	public static Fit check(RomfsIndex index, List<Usage> usages, String english, String japanese) {
		if (usages.isEmpty()) {
			return NOT_SHOWN;
		}
		boolean wide = false;
		boolean tall = false;
		boolean estimate = false;
		Set<Integer> missing = new LinkedHashSet<>();
		List<Usage> main = previewable(usages);
		for (Usage u : usages) {
			Bcfnt font = font(index, u);
			if (main.contains(u)) {
				Judgement j = judge(font, u.pane().text(), english, japanese);
				missing.addAll(j.english().missing());
				wide |= j.tooWide();
				tall |= j.tooTall();
				estimate |= j.estimate();
			} else {
				missing.addAll(TextRenderer.measure(font, u.pane().text(), english).missing());
			}
		}
		return new Fit(true, wide, tall, missing, estimate);
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
