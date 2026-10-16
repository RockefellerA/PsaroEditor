package psaro.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import psaro.format.Bclyt.PaneOverride;
import psaro.format.Bclyt.TextOverride;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatchSettings;
import psaro.patch.Typeface;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.PaneRef;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * Carries the work done on a parent project (a game) into a related one (its DLC): the English
 * for strings that read the same, the panes strings are linked to, the text and placement
 * changes made to layouts both have, the per-font patch settings, and the typefaces those draw
 * from. Only what the project has not set itself is taken, so importing is safe to do again
 * after the parent has moved on, and nothing in the parent is touched. What the DLC has that
 * the parent lacks (a layout, a font, a string) is simply left to be done.
 *
 * <p>A string takes the parent's English when the parent has the same key in a table of the same
 * name with the same Japanese, or else when every string in the parent with that Japanese has one
 * English (a Japanese text the parent translated two ways is left alone, and counted).
 */
public final class ParentImport {

	/** What an import took, and what it left alone. */
	public record Result(int translations, int keptJapanese, int ambiguous, int paneLinks, int textSettings,
			int placements, int fontSettings, int typefaces, List<String> notes) {

		/** Whether anything was taken. */
		public boolean any() {
			return translations + keptJapanese + paneLinks + textSettings + placements + fontSettings + typefaces > 0;
		}

		/** A few lines for the user: what was taken. */
		public String summary() {
			if (!any()) {
				return "Nothing to import: the project already has everything the parent has that applies to it.";
			}
			List<String> lines = new ArrayList<>();
			if (translations + keptJapanese > 0) {
				lines.add(translations + " translations" + (keptJapanese > 0 ? " and " + keptJapanese + " strings kept in Japanese" : ""));
			}
			if (paneLinks > 0) {
				lines.add(paneLinks + " strings linked to panes");
			}
			if (textSettings + placements > 0) {
				lines.add(textSettings + " text pane settings and " + placements + " pane placements");
			}
			if (fontSettings > 0) {
				lines.add("patch settings for " + fontSettings + " fonts");
			}
			if (typefaces > 0) {
				lines.add(typefaces + " imported typefaces");
			}
			String out = "Imported " + String.join(", ", lines) + ".";
			if (ambiguous > 0) {
				out += "\n" + ambiguous + " strings read the same as parent strings translated more than one way; they were left for you.";
			}
			return out;
		}
	}

	/** A parent string's outcome: English, or kept in Japanese. */
	private record Choice(String english, boolean keep) {
	}

	private ParentImport() {
	}

	/**
	 * Imports from the project whose romfs is {@code parentRomfs} into the one open, whose parts
	 * are given; each is saved as it changes. The parent's romfs is read (not changed), which can
	 * take a while.
	 */
	public static Result run(RomfsIndex index, Translations translations, PaneLinks links, LayoutOverrides layouts,
			PatchSettings settings, Path parentRomfs) throws IOException {
		RomfsIndex parent = RomfsIndex.scan(parentRomfs);
		List<String> notes = new ArrayList<>();

		int typefaces = importTypefaces(parentRomfs, settings, notes);
		int fonts = settings.adopt(PatchSettings.open(parentRomfs), font -> !index.archivesWithFont(font).isEmpty());
		int[] text = importText(parent, index, translations);
		int paneLinks = importLinks(PaneLinks.open(parent), index, links);
		int[] layoutChanges = importLayouts(LayoutOverrides.open(parentRomfs), index, layouts, settings);
		return new Result(text[0], text[1], text[2], paneLinks, layoutChanges[0], layoutChanges[1], fonts, typefaces,
				List.copyOf(notes));
	}

	/** English (translations, kept) and ambiguous counts, in that order. */
	private static int[] importText(RomfsIndex parent, RomfsIndex index, Translations translations) throws IOException {
		Translations theirs = Translations.open(parent);
		Map<String, Set<Choice>> byJapanese = new HashMap<>();
		for (StringTable table : parent.tables()) {
			for (Map.Entry<String, String> s : table.strings().entrySet()) {
				Choice c = choice(theirs, table, s.getKey());
				if (c != null && !s.getValue().isBlank()) {
					byJapanese.computeIfAbsent(s.getValue(), k -> new HashSet<>()).add(c);
				}
			}
		}
		int english = 0;
		int kept = 0;
		int ambiguous = 0;
		for (StringTable table : index.tables()) {
			StringTable same = parent.table(table.name());
			for (Map.Entry<String, String> s : table.strings().entrySet()) {
				String key = s.getKey();
				String japanese = s.getValue();
				if (japanese.isBlank() || translations.get(table, key) != null) {
					continue;
				}
				Choice c = same != null && japanese.equals(same.strings().get(key)) ? choice(theirs, same, key) : null;
				if (c == null) {
					Set<Choice> all = byJapanese.getOrDefault(japanese, Set.of());
					if (all.size() > 1) {
						ambiguous++;
					} else if (all.size() == 1) {
						c = all.iterator().next();
					}
				}
				if (c == null) {
					continue;
				}
				if (c.keep()) {
					translations.setKeepsJapanese(table, key, true);
					kept++;
				} else {
					translations.set(table, key, c.english());
					english++;
				}
			}
		}
		translations.save();
		return new int[] {english, kept, ambiguous};
	}

	private static Choice choice(Translations t, StringTable table, String key) {
		if (t.keepsJapanese(table, key)) {
			return new Choice(null, true);
		}
		String english = t.get(table, key);
		return english == null ? null : new Choice(english, false);
	}

	/** Strings the parent linked to panes, where this project's string has none and its layouts have the panes. */
	private static int importLinks(PaneLinks theirs, RomfsIndex index, PaneLinks links) throws IOException {
		Set<PaneRef> panes = new HashSet<>();
		index.textPanes().forEach(u -> panes.add(new PaneRef(u.layout(), u.pane().name())));
		int n = 0;
		for (Map.Entry<String, Map<String, List<PaneRef>>> t : theirs.all().entrySet()) {
			StringTable table = index.table(t.getKey());
			if (table == null) {
				continue;
			}
			Map<String, List<PaneRef>> take = new TreeMap<>();
			for (Map.Entry<String, List<PaneRef>> k : t.getValue().entrySet()) {
				String key = k.getKey();
				if (!table.strings().containsKey(key) || !links.get(table, key).isEmpty() || !index.panes(table, key).isEmpty()) {
					continue;
				}
				List<PaneRef> here = k.getValue().stream().filter(panes::contains).toList();
				if (!here.isEmpty()) {
					take.put(key, here);
				}
			}
			links.setMany(table, take);
			n += take.size();
		}
		return n;
	}

	/** Text settings and placements, in that order. */
	private static int[] importLayouts(LayoutOverrides theirs, RomfsIndex index, LayoutOverrides layouts,
			PatchSettings settings) throws IOException {
		Set<PaneRef> textPanes = new HashSet<>();
		index.textPanes().forEach(u -> textPanes.add(new PaneRef(u.layout(), u.pane().name())));
		int text = 0;
		int places = 0;
		for (String layout : theirs.layouts()) {
			if (index.archivesWithLayout(layout).isEmpty()) {
				continue;
			}
			for (Map.Entry<String, TextOverride> e : theirs.forLayout(layout).entrySet()) {
				String pane = e.getKey();
				if (!layouts.get(layout, pane).isEmpty() || !textPanes.contains(new PaneRef(layout, pane))) {
					continue;
				}
				TextOverride t = e.getValue();
				String font = t.font() != null && index.archivesWithFont(t.font()).isEmpty() ? null : t.font();
				String drawWith = t.drawWith() != null && !Typeface.GAME.id().equals(t.drawWith())
						&& !settings.typefaces().has(t.drawWith()) ? null : t.drawWith();
				TextOverride use = new TextOverride(t.boxWidth(), t.boxHeight(), t.fontSizeX(), t.fontSizeY(), t.charSpace(),
						t.lineSpace(), font, drawWith);
				if (!use.isEmpty()) {
					layouts.set(layout, List.of(pane), use);
					text++;
				}
			}
			Map<String, PaneOverride> move = new TreeMap<>();
			theirs.placesForLayout(layout).forEach((pane, p) -> {
				if (layouts.place(layout, pane).isEmpty()) {
					move.put(pane, p);
				}
			});
			if (!move.isEmpty()) {
				layouts.setPlaces(layout, move);
				places += move.size();
			}
		}
		return new int[] {text, places};
	}

	/** Typefaces the parent imported that this project lacks are copied into its fonts folder. */
	private static int importTypefaces(Path parentRomfs, PatchSettings settings, List<String> notes)
			throws IOException {
		var theirs = PatchSettings.open(parentRomfs).typefaces();
		List<Path> files = new ArrayList<>();
		int n = 0;
		for (Typeface t : theirs.imported()) {
			if (settings.typefaces().has(t.id())) {
				continue;
			}
			n++;
			t.weights().values().forEach(f -> files.add(theirs.folder().resolve(f)));
		}
		if (!files.isEmpty()) {
			var done = settings.typefaces().importFiles(files);
			done.refused().forEach(r -> notes.add("Not imported: " + r));
		}
		return n;
	}
}
