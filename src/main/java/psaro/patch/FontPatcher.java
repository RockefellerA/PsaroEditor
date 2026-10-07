package psaro.patch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import psaro.format.Archive;
import psaro.format.Bclyt;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.format.Bcfnt;
import psaro.format.Darc;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Donor;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.romfs.RomfsIndex.Usage;
import psaro.text.ControlCodes;

/**
 * Adds the characters the English uses to the fonts that lack them, borrowing glyphs from other
 * copies and sizes of the same font ({@link RomfsIndex#donors}).
 *
 * <p>Each archive carries its own subset fonts, so the patch works per archive and font: a font
 * gets exactly the characters that the English in its archive's panes uses and it lacks, and
 * nothing more, so a glyph a re-translation no longer needs is dropped on the next patch. The
 * patched archives go to {@code <romfs>.psaro/romfs}, at the same paths as in the romfs, which
 * is never changed; an archive that no longer needs anything is removed from there.
 *
 * <p>The fit check measures with {@link #preview}, the font with every glyph its donors can
 * lend, built the same way, so what the editor measures is what the patch writes.
 *
 * <p>The patch also writes the changes made to text panes' box and type settings
 * ({@link LayoutOverrides}) into every archive that carries the changed layout, and measuring
 * uses them through {@link #text}.
 */
public final class FontPatcher {

	/** One string's text as the game would show it. */
	public record Text(StringTable table, String key, String text) {
	}

	/**
	 * What the patch does to one font in one archive. {@code needed} is what its archive's
	 * English uses and it lacks; {@code unavailable} the part of that no donor has; {@code add}
	 * and {@code remove} what the patch would change against what was last written; {@code stale}
	 * that glyphs already written differ from what the patch would write now (the extra-space
	 * letters changed, say). {@code from} names each lendable character's donor.
	 */
	public record FontChange(Path archive, String font, Set<Integer> needed, Set<Integer> unavailable,
			Set<Integer> add, Set<Integer> remove, boolean stale, Map<Integer, Donor> from) {

		public boolean hasWork() {
			return !add.isEmpty() || !remove.isEmpty() || stale;
		}

		/** What the patched font gets: the needed characters some donor has. */
		public Set<Integer> lent() {
			Set<Integer> out = new TreeSet<>(needed);
			out.removeAll(unavailable);
			return out;
		}
	}

	/**
	 * What the patch does to one layout in one archive: {@code panes} are its changes (none when
	 * a previous patch's change was undone), {@code changed} whether they leave it different from
	 * the romfs's, and {@code hasWork} whether what was last written differs from that.
	 */
	public record LayoutChange(Path archive, String layout, Map<String, TextOverride> panes, boolean changed,
			boolean hasWork) {
	}

	/**
	 * Every font the English touches and every font a previous patch wrote; every changed layout
	 * and every layout a previous patch changed.
	 */
	public record Plan(List<FontChange> fonts, List<LayoutChange> layouts) {

		public boolean hasWork() {
			return fonts.stream().anyMatch(FontChange::hasWork) || layouts.stream().anyMatch(LayoutChange::hasWork);
		}

		/** Layouts the patch would write or put back. */
		public int layoutsToWrite() {
			return (int) layouts.stream().filter(LayoutChange::hasWork).count();
		}

		public int toAdd() {
			return fonts.stream().mapToInt(f -> f.add().size()).sum();
		}

		public int toRemove() {
			return fonts.stream().mapToInt(f -> f.remove().size()).sum();
		}

		public int stale() {
			return (int) fonts.stream().filter(FontChange::stale).count();
		}

		/** Characters no donor has, over every font. */
		public Set<Integer> unavailable() {
			Set<Integer> out = new TreeSet<>();
			fonts.forEach(f -> out.addAll(f.unavailable()));
			return out;
		}
	}

	/** A font with every glyph its donors can lend, and where each came from. */
	private record Lent(Bcfnt font, Map<Integer, Donor> from) {
	}

	/** A written archive's fonts and layouts, as of its modification time. */
	private record Written(FileTime time, Map<String, Bcfnt> fonts, Map<String, byte[]> layouts) {
	}

	private static final Written NOTHING = new Written(null, Map.of(), Map.of());

	private final RomfsIndex index;
	private final PatchSettings settings;
	private final LayoutOverrides overrides;
	private final Path output;
	/** Keyed by archive + "!" + font; empty for a font the archive does not carry. */
	private final Map<String, Optional<Lent>> previews = new HashMap<>();
	private final Map<Path, Written> written = new HashMap<>();
	/** Each romfs archive's layouts by path, as read; they never change. */
	private final Map<Path, Map<String, byte[]>> originalLayouts = new HashMap<>();

	public FontPatcher(RomfsIndex index, PatchSettings settings, LayoutOverrides overrides) {
		this.index = index;
		this.settings = settings;
		this.overrides = overrides;
		this.output = outputFor(index.root());
		handOutLegacyExtraSpace();
	}

	/**
	 * Gives the letters all fonts shared under the older settings to the fonts a previous patch
	 * added glyphs to, which were built with them, so they come out the same; every other font
	 * starts with none. Done once: the shared value is then retired.
	 */
	private void handOutLegacyExtraSpace() {
		String legacy = settings.legacyExtraSpace();
		if (legacy == null) {
			return;
		}
		Set<String> patched = new TreeSet<>();
		for (Path archive : writtenArchives()) {
			Path original = index.root().resolve(output.relativize(archive).toString());
			written(archive).fonts().forEach((name, font) -> {
				try {
					Bcfnt own = index.font(original, name);
					if (own != null && !own.cmap.keySet().containsAll(font.cmap.keySet())) {
						patched.add(name);
					}
				} catch (IOException | RuntimeException unreadable) {
					// not one of ours to judge
				}
			});
		}
		try {
			settings.setExtraSpace(patched, legacy);
		} catch (IOException e) {
			// left as it was; handed out next time
		}
	}

	/** Where the patched archives of {@code romfs} go. */
	public static Path outputFor(Path romfs) {
		return Translations.folderFor(romfs).resolve("romfs");
	}

	public RomfsIndex index() {
		return index;
	}

	public PatchSettings settings() {
		return settings;
	}

	public LayoutOverrides overrides() {
		return overrides;
	}

	/** {@code usage}'s pane's box and type settings, with any change made to them. */
	public TextInfo text(Usage usage) {
		return overrides.apply(usage.layout(), usage.pane().name(), usage.pane().text());
	}

	public Path output() {
		return output;
	}

	/** Forgets the previews, after the settings they are built with change. */
	public synchronized void settingsChanged() {
		previews.clear();
	}

	/**
	 * The font {@code usage}'s pane draws with in the game as patched so far: the last patch's
	 * copy when one was written, else the romfs's own. Null for the system font or an archive
	 * that cannot be read.
	 */
	public Bcfnt current(Usage usage) {
		Bcfnt written = written(outputPath(usage.archive())).fonts().get(usage.fontName());
		if (written != null) {
			return written;
		}
		try {
			return index.font(usage);
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	/**
	 * {@code usage}'s font as the patch would leave it given every character its donors can
	 * lend; null for the system font or an archive that cannot be read.
	 */
	public Bcfnt preview(Usage usage) {
		Lent l = lent(usage.archive(), usage.fontName());
		return l == null ? null : l.font();
	}

	private synchronized Lent lent(Path archive, String fontName) {
		String id = archive + "!" + fontName;
		Optional<Lent> known = previews.get(id);
		if (known == null) {
			Lent built = null;
			try {
				Bcfnt original = index.font(archive, fontName);
				if (original != null) {
					List<Donor> donors = index.donors(fontName);
					Bcfnt copy = Bcfnt.parse(original.toBytes());
					built = new Lent(copy,
							Lending.add(copy, Lending.lendableFrom(donors), donors, settings.extraSpace(fontName)));
				}
			} catch (IOException | RuntimeException unreadable) {
				// measured with the stand-in instead
			}
			known = Optional.ofNullable(built);
			previews.put(id, known);
		}
		return known.orElse(null);
	}

	/** The characters {@code text} draws: no color codes or line breaks. */
	public static Set<Integer> characters(String text) {
		Set<Integer> out = new TreeSet<>();
		for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
			int cp = text.codePointAt(i);
			if (cp == ControlCodes.COLOUR) {
				i++;
			} else if (cp >= 0x20) {
				out.add(cp);
			}
		}
		return out;
	}

	/**
	 * What the patch would do for {@code texts} (every string that has English, as the game would
	 * show it) against what was last written.
	 */
	public Plan plan(List<Text> texts) {
		Map<String, Set<Integer>> used = new TreeMap<>();
		Map<String, Usage> where = new HashMap<>();
		for (Text t : texts) {
			Set<Integer> chars = characters(t.text());
			for (Usage u : index.usages(t.table(), t.key())) {
				String id = u.archive() + "!" + u.fontName();
				used.computeIfAbsent(id, k -> new TreeSet<>()).addAll(chars);
				where.putIfAbsent(id, u);
			}
		}
		List<FontChange> fonts = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Map.Entry<String, Set<Integer>> e : used.entrySet()) {
			Usage u = where.get(e.getKey());
			FontChange c = change(u.archive(), u.fontName(), e.getValue());
			if (c != null) {
				fonts.add(c);
				seen.add(e.getKey());
			}
		}
		// fonts a previous patch wrote that no English uses any more
		for (Path archive : writtenArchives()) {
			Path original = index.root().resolve(output.relativize(archive).toString());
			for (String font : written(archive).fonts().keySet()) {
				if (!seen.contains(original + "!" + font)) {
					FontChange c = change(original, font, Set.of());
					if (c != null && !c.remove().isEmpty()) {
						fonts.add(c);
					}
				}
			}
		}
		return new Plan(List.copyOf(fonts), layoutChanges());
	}

	/** Every layout with a change, and every layout a previous patch wrote changed. */
	private List<LayoutChange> layoutChanges() {
		List<LayoutChange> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String layout : overrides.layouts()) {
			for (Path archive : index.archivesWithLayout(layout)) {
				layoutChange(archive, layout, seen, out);
			}
		}
		for (Path archive : writtenArchives()) {
			Path original = index.root().resolve(output.relativize(archive).toString());
			for (String layout : written(archive).layouts().keySet()) {
				layoutChange(original, layout, seen, out);
			}
		}
		return List.copyOf(out);
	}

	private void layoutChange(Path archive, String layout, Set<String> seen, List<LayoutChange> out) {
		if (!seen.add(archive + "!" + layout)) {
			return;
		}
		byte[] original = originalLayouts(archive).get(layout);
		if (original == null) {
			return;
		}
		Map<String, TextOverride> panes = overrides.forLayout(layout);
		byte[] expected = panes.isEmpty() ? original : Bclyt.withText(original, panes);
		boolean changed = !Arrays.equals(expected, original);
		Path outFile = outputPath(archive);
		byte[] current = Files.isRegularFile(outFile) ? written(outFile).layouts().get(layout) : original;
		boolean hasWork = !Arrays.equals(expected, current);
		if (changed || hasWork) {
			out.add(new LayoutChange(archive, layout, panes, changed, hasWork));
		}
	}

	/** {@code archive}'s layouts by path, read once; empty when it cannot be read. */
	private synchronized Map<String, byte[]> originalLayouts(Path archive) {
		return originalLayouts.computeIfAbsent(archive, a -> {
			try {
				return layoutsOf(Darc.files(Archive.load(a)));
			} catch (IOException | RuntimeException unreadable) {
				return Map.of();
			}
		});
	}

	private static Map<String, byte[]> layoutsOf(Map<String, Darc.Node> files) {
		Map<String, byte[]> out = new HashMap<>();
		files.forEach((path, node) -> {
			if (path.endsWith(".bclyt")) {
				out.put(path, node.data);
			}
		});
		return out;
	}

	private FontChange change(Path archive, String fontName, Set<Integer> used) {
		Bcfnt original;
		try {
			original = index.font(archive, fontName);
		} catch (IOException | RuntimeException unreadable) {
			original = null;
		}
		if (original == null) {
			return null;
		}
		Set<Integer> needed = new TreeSet<>(used);
		needed.removeAll(original.cmap.keySet());
		Lent lent = lent(archive, fontName);
		Set<Integer> unavailable = new TreeSet<>();
		Map<Integer, Donor> from = new TreeMap<>();
		for (int c : needed) {
			Donor d = lent == null ? null : lent.from().get(c);
			if (d == null) {
				unavailable.add(c);
			} else {
				from.put(c, d);
			}
		}
		Bcfnt out = written(outputPath(archive)).fonts().get(fontName);
		Set<Integer> added = new TreeSet<>();
		if (out != null) {
			added.addAll(out.cmap.keySet());
			added.removeAll(original.cmap.keySet());
		}
		Set<Integer> add = new TreeSet<>(from.keySet());
		add.removeAll(added);
		Set<Integer> remove = new TreeSet<>(added);
		remove.removeAll(from.keySet());
		boolean stale = false;
		for (int c : from.keySet()) {
			if (added.contains(c) && !sameGlyph(out.glyph(c), lent.font().glyph(c))) {
				stale = true;
			}
		}
		if (needed.isEmpty() && added.isEmpty()) {
			return null;
		}
		return new FontChange(archive, fontName, Collections.unmodifiableSet(needed),
				Collections.unmodifiableSet(unavailable), Collections.unmodifiableSet(add),
				Collections.unmodifiableSet(remove), stale, Collections.unmodifiableMap(from));
	}

	private static boolean sameGlyph(Bcfnt.Glyph a, Bcfnt.Glyph b) {
		return a.left == b.left && a.glyphWidth == b.glyphWidth && a.charWidth == b.charWidth;
	}

	/** Where {@code archive}'s patched copy goes. */
	public Path outputPath(Path archive) {
		return output.resolve(index.root().relativize(archive).toString());
	}

	/**
	 * Carries out {@code plan}: rebuilds from the romfs every archive with a font or layout to
	 * change, removes written archives nothing needs any more, then, if the settings say so,
	 * copies every written archive and string table into the mods folder ({@link #copyToMods}).
	 * Reports each step to {@code progress}. Returns the archives written.
	 */
	public List<Path> write(Plan plan, Consumer<String> progress) throws IOException {
		Map<Path, List<FontChange>> fontsBy = new LinkedHashMap<>();
		Map<Path, List<LayoutChange>> layoutsBy = new LinkedHashMap<>();
		for (FontChange f : plan.fonts()) {
			fontsBy.computeIfAbsent(f.archive(), k -> new ArrayList<>()).add(f);
		}
		for (LayoutChange l : plan.layouts()) {
			layoutsBy.computeIfAbsent(l.archive(), k -> new ArrayList<>()).add(l);
		}
		Set<Path> archives = new LinkedHashSet<>(fontsBy.keySet());
		archives.addAll(layoutsBy.keySet());
		List<Path> wrote = new ArrayList<>();
		for (Path archive : archives) {
			List<FontChange> fonts = fontsBy.getOrDefault(archive, List.of());
			List<LayoutChange> layouts = layoutsBy.getOrDefault(archive, List.of());
			Path out = outputPath(archive);
			Map<String, Set<Integer>> lend = new HashMap<>();
			for (FontChange f : fonts) {
				if (!f.lent().isEmpty()) {
					lend.put(f.font(), f.lent());
				}
			}
			if (lend.isEmpty() && layouts.stream().noneMatch(LayoutChange::changed)) {
				progress.accept("Removing " + index.root().relativize(archive));
				Files.deleteIfExists(out);
				continue;
			}
			if (fonts.stream().noneMatch(FontChange::hasWork) && layouts.stream().noneMatch(LayoutChange::hasWork)
					&& Files.isRegularFile(out)) {
				continue;
			}
			progress.accept("Patching " + index.root().relativize(archive));
			Darc.Node root = Archive.load(archive);
			for (Map.Entry<String, Darc.Node> file : Darc.files(root).entrySet()) {
				String path = file.getKey();
				String name = path.substring(path.lastIndexOf('/') + 1);
				Set<Integer> codes = lend.get(name);
				if (codes != null) {
					Bcfnt font = Bcfnt.parse(file.getValue().data);
					Lending.add(font, codes, index.donors(name), settings.extraSpace(name));
					file.getValue().data = font.toBytes();
				}
				Map<String, TextOverride> panes = path.endsWith(".bclyt") ? overrides.forLayout(path) : Map.of();
				if (!panes.isEmpty()) {
					file.getValue().data = Bclyt.withText(file.getValue().data, panes);
				}
			}
			Archive.save(root, out);
			wrote.add(out);
		}
		copyToMods(outputFiles(".arc.lz", ".tdt"), progress);
		return wrote;
	}

	/**
	 * Copies {@code files}, from the output folder, to the same paths in the mods folder when the
	 * settings say so. Nothing in the mods folder is ever removed.
	 */
	public void copyToMods(List<Path> files, Consumer<String> progress) throws IOException {
		if (!settings.copyToMods() || settings.modsFolder() == null) {
			return;
		}
		for (Path file : files) {
			Path target = settings.modsFolder().resolve(output.relativize(file).toString());
			progress.accept("Copying to mods: " + output.relativize(file));
			Files.createDirectories(target.getParent());
			Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Every archive a previous patch wrote. */
	private List<Path> writtenArchives() {
		return outputFiles(".arc.lz");
	}

	/** Every file in the output folder whose name ends with one of {@code suffixes}. */
	private List<Path> outputFiles(String... suffixes) {
		if (!Files.isDirectory(output)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(output)) {
			return files.filter(p -> {
				String name = p.getFileName().toString();
				return Stream.of(suffixes).anyMatch(name::endsWith);
			}).sorted().toList();
		} catch (IOException | UncheckedIOException e) {
			return List.of();
		}
	}

	/**
	 * The fonts (by name) and layouts (by path) in a written archive; none when there is no such
	 * archive or it cannot be read.
	 */
	private synchronized Written written(Path out) {
		try {
			if (!Files.isRegularFile(out)) {
				written.remove(out);
				return NOTHING;
			}
			FileTime time = Files.getLastModifiedTime(out);
			Written w = written.get(out);
			if (w == null || !w.time().equals(time)) {
				Map<String, Darc.Node> files = Darc.files(Archive.load(out));
				Map<String, Bcfnt> fonts = new HashMap<>();
				for (Map.Entry<String, Darc.Node> file : files.entrySet()) {
					if (file.getKey().endsWith(".bcfnt")) {
						fonts.put(file.getKey().substring(file.getKey().lastIndexOf('/') + 1),
								Bcfnt.parse(file.getValue().data));
					}
				}
				w = new Written(time, fonts, layoutsOf(files));
				written.put(out, w);
			}
			return w;
		} catch (IOException | RuntimeException unreadable) {
			return NOTHING;
		}
	}
}
