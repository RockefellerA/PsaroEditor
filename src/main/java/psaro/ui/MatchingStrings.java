package psaro.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.patch.LayoutOverrides;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.romfs.RomfsIndex.Usage;

/**
 * The other strings whose Japanese is exactly a string's, so one translation can go to them all:
 * the dozens of 戻る buttons, say. A match whose every box has the same shape as one of the
 * string's (font, box, size and spacing as the layout has them) can also take the changes made to
 * the string's panes; one in a box of another shape, or in none, only its English.
 */
final class MatchingStrings {

	/**
	 * Another string with the same Japanese: its English now ({@code null} if none), whether it
	 * keeps its Japanese, and whether every box showing it has the shape of one of the source's
	 * ({@code false} when no layout shows it).
	 */
	record Match(StringTable table, String key, String english, boolean keepsJapanese, boolean sameBoxes) {

		boolean untranslated() {
			return english == null;
		}
	}

	private MatchingStrings() {
	}

	/**
	 * The strings other than {@code key} of {@code table} with the same Japanese, but for those that
	 * already read as it does and could take no pane change.
	 */
	static List<Match> find(RomfsIndex index, Translations translations, LayoutOverrides overrides, StringTable table,
			String key) {
		String japanese = table.strings().get(key);
		String english = translations.get(table, key);
		boolean keep = translations.keepsJapanese(table, key);
		Map<Shape, TextOverride> changes = changes(index.panes(table, key), overrides);
		Set<Shape> ours = shapes(index.panes(table, key));
		List<Match> out = new ArrayList<>();
		for (StringTable t : index.tables()) {
			for (Map.Entry<String, String> e : t.strings().entrySet()) {
				if (t == table && e.getKey().equals(key) || !e.getValue().equals(japanese)) {
					continue;
				}
				List<Usage> panes = index.panes(t, e.getKey());
				boolean same = !panes.isEmpty() && panes.stream().allMatch(u -> ours.contains(shape(u)));
				String theirs = translations.get(t, e.getKey());
				boolean theyKeep = translations.keepsJapanese(t, e.getKey());
				boolean readsTheSame = Objects.equals(theirs, english) && theyKeep == keep;
				if (readsTheSame && (!same || panes.stream().allMatch(u -> takes(u, changes, overrides) == null))) {
					continue;
				}
				out.add(new Match(t, e.getKey(), theyKeep ? null : theirs, theyKeep, same));
			}
		}
		return out;
	}

	/**
	 * Gives each of {@code chosen} the source's English (or its keep-Japanese mark), and each
	 * box of a match with {@code sameBoxes} the change made to the source's box of its shape.
	 * Returns how many panes were changed.
	 */
	static int apply(RomfsIndex index, Translations translations, LayoutOverrides overrides, StringTable table, String key,
			List<Match> chosen) throws IOException {
		String english = translations.get(table, key);
		boolean keep = translations.keepsJapanese(table, key);
		Map<Shape, TextOverride> changes = changes(index.panes(table, key), overrides);
		int panes = 0;
		for (Match m : chosen) {
			if (keep) {
				translations.setKeepsJapanese(m.table(), m.key(), true);
			} else {
				translations.set(m.table(), m.key(), english);
			}
			if (!m.sameBoxes()) {
				continue;
			}
			for (Usage u : index.panes(m.table(), m.key())) {
				TextOverride t = takes(u, changes, overrides);
				if (t != null) {
					overrides.set(u.layout(), List.of(u.pane().name()), t);
					panes++;
				}
			}
		}
		return panes;
	}

	/**
	 * The change {@code u}'s box should take from the source's box of its shape, or null when that
	 * has none or {@code u}'s already is it. A font its layout does not list is left out.
	 */
	private static TextOverride takes(Usage u, Map<Shape, TextOverride> changes, LayoutOverrides overrides) {
		TextOverride t = changes.get(shape(u));
		if (t == null) {
			return null;
		}
		if (t.font() != null && !u.layoutFonts().contains(t.font())) {
			t = t.withFont(null);
		}
		return t.isEmpty() || t.equals(overrides.get(u.layout(), u.pane().name())) ? null : t;
	}

	/** The changes made to {@code panes}, by their shape; the first change of a shape wins. */
	private static Map<Shape, TextOverride> changes(List<Usage> panes, LayoutOverrides overrides) {
		Map<Shape, TextOverride> out = new HashMap<>();
		for (Usage u : panes) {
			TextOverride t = overrides.get(u.layout(), u.pane().name());
			if (!t.isEmpty()) {
				out.putIfAbsent(shape(u), t);
			}
		}
		return out;
	}

	private static Set<Shape> shapes(List<Usage> panes) {
		Set<Shape> out = new HashSet<>();
		panes.forEach(u -> out.add(shape(u)));
		return out;
	}

	/** What decides how text fits a box, as the layout has it. */
	private record Shape(String font, float boxWidth, float boxHeight, float fontSizeX, float fontSizeY, float charSpace,
			float lineSpace) {
	}

	private static Shape shape(Usage u) {
		TextInfo t = u.pane().text();
		return new Shape(t.font(), t.boxWidth(), t.boxHeight(), t.fontSizeX(), t.fontSizeY(), t.charSpace(), t.lineSpace());
	}
}
