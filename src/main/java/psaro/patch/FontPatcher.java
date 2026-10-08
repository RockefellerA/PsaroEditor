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
import psaro.format.Bclim;
import psaro.format.Bclyt;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.format.Bcfnt;
import psaro.format.Darc;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Donor;
import psaro.romfs.RomfsIndex.Image;
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
 * uses them through {@link #text}. A pane switched to another of its layout's fonts is measured
 * in that font, and that font gets the glyphs its English needs.
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
	 *
	 * <p>A {@link FreeFont} the patch adds has {@code drawnFor}, the game font it stands in for;
	 * its {@code needed} is all it holds, what that font holds and what the English adds.
	 */
	public record FontChange(Path archive, String font, Set<Integer> needed, Set<Integer> unavailable,
			Set<Integer> add, Set<Integer> remove, boolean stale, Map<Integer, Donor> from, String drawnFor) {

		/** A change to a game font, which lends it what it lacks. */
		public FontChange(Path archive, String font, Set<Integer> needed, Set<Integer> unavailable, Set<Integer> add,
				Set<Integer> remove, boolean stale, Map<Integer, Donor> from) {
			this(archive, font, needed, unavailable, add, remove, stale, from, null);
		}

		public boolean hasWork() {
			return !add.isEmpty() || !remove.isEmpty() || stale;
		}

		/** True for a font drawn from a bundled typeface, which the patch adds beside the game's. */
		public boolean free() {
			return drawnFor != null;
		}

		/** The font whose settings (the extra-space letters) this one is built with. */
		public String settingsFont() {
			return drawnFor != null ? drawnFor : font;
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
	 * a previous patch's change was undone), {@code fonts} the game fonts it now names by their
	 * free fonts' names, {@code changed} whether these leave it different from the romfs's, and
	 * {@code hasWork} whether what was last written differs from that.
	 */
	public record LayoutChange(Path archive, String layout, Map<String, TextOverride> panes, Map<String, String> fonts,
			boolean changed, boolean hasWork) {
	}

	/**
	 * What the patch does to one image in one archive ({@link ImageEdits}): {@code changed} whether
	 * it is written replaced, {@code hasWork} whether what was last written differs from that (a
	 * replacement drawn again, or one taken back).
	 */
	public record ImageChange(Path archive, String path, boolean changed, boolean hasWork) {
	}

	/**
	 * Every font the English touches and every font a previous patch wrote; every changed layout
	 * and every layout a previous patch changed; every replaced image and every one a previous
	 * patch replaced.
	 */
	public record Plan(List<FontChange> fonts, List<LayoutChange> layouts, List<ImageChange> images) {

		/** A plan with no images. */
		public Plan(List<FontChange> fonts, List<LayoutChange> layouts) {
			this(fonts, layouts, List.of());
		}

		public boolean hasWork() {
			return fonts.stream().anyMatch(FontChange::hasWork) || layouts.stream().anyMatch(LayoutChange::hasWork)
					|| images.stream().anyMatch(ImageChange::hasWork);
		}

		/** Images the patch would write or put back. */
		public int imagesToWrite() {
			return (int) images.stream().filter(ImageChange::hasWork).count();
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

	/** A font lent only what the English needs, for a plan, with that set. */
	private record PlanLent(Set<Integer> needed, Lent lent) {
	}

	/** A written archive's fonts and layouts, as of its modification time. */
	private record Written(FileTime time, Map<String, Bcfnt> fonts, Map<String, byte[]> layouts, Map<String, String> images) {
	}

	private static final Written NOTHING = new Written(null, Map.of(), Map.of(), Map.of());

	private final RomfsIndex index;
	private final PatchSettings settings;
	private final LayoutOverrides overrides;
	private final Path output;
	/** Keyed by archive + "!" + font; empty for a font the archive does not carry. */
	private final Map<String, Optional<Lent>> previews = new HashMap<>();
	/** The last plan's lending, keyed by archive + "!" + font. */
	private final Map<String, PlanLent> planLents = new HashMap<>();
	/** Keyed by game font + "!" + typeface; empty for a font no archive carries. Kept across settings. */
	private final Map<String, Optional<FreeFont>> freeFonts = new HashMap<>();
	private final ImageEdits images;
	/** The romfs's images by archive + "!" + path, made when first asked for. */
	private Map<String, Image> imagesByPlace;
	private final Map<Path, Written> written = new HashMap<>();
	/** Each romfs archive's layouts by path, as read; they never change. */
	private final Map<Path, Map<String, byte[]>> originalLayouts = new HashMap<>();

	public FontPatcher(RomfsIndex index, PatchSettings settings, LayoutOverrides overrides, ImageEdits images) {
		this.index = index;
		this.settings = settings;
		this.overrides = overrides;
		this.images = images;
		this.output = outputFor(index.root());
		handOutLegacyExtraSpace();
	}

	/** With the image edits saved for the romfs, none if they cannot be read. */
	public FontPatcher(RomfsIndex index, PatchSettings settings, LayoutOverrides overrides) {
		this(index, settings, overrides, openImages(index.root()));
	}

	private static ImageEdits openImages(Path romfs) {
		try {
			return ImageEdits.open(romfs);
		} catch (IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
	}

	public ImageEdits images() {
		return images;
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

	/** The font {@code usage}'s pane draws with: the layout's own, or the one it was switched to. */
	public String fontName(Usage usage) {
		return text(usage).font();
	}

	public Path output() {
		return output;
	}

	/**
	 * Forgets the previews, after the settings they are built with change; the free fonts' glyphs
	 * are kept, given the new extra-space letters.
	 */
	public synchronized void settingsChanged() {
		previews.clear();
		planLents.clear();
		freeFonts.values().forEach(f -> f.ifPresent(ff -> ff.setExtraSpace(settings.extraSpace(ff.gameFont()))));
	}

	/**
	 * The font drawn from a bundled typeface for game font {@code gameFont}, when it is set to
	 * one and some archive carries it; else null. Measured on the copy with the most glyphs.
	 */
	public synchronized FreeFont freeFont(String gameFont) {
		Typeface face = settings.lettersFrom(gameFont);
		if (!face.bundled()) {
			return null;
		}
		return freeFonts.computeIfAbsent(gameFont + "!" + face.id(), k -> {
			Bcfnt reference = null;
			for (Path archive : index.archivesWithFont(gameFont)) {
				try {
					Bcfnt copy = index.font(archive, gameFont);
					if (copy != null && (reference == null || copy.cmap.size() > reference.cmap.size())) {
						reference = copy;
					}
				} catch (IOException | RuntimeException unreadable) {
					// another copy will do
				}
			}
			return Optional.ofNullable(reference)
					.map(r -> new FreeFont(gameFont, face, r, settings.extraSpace(gameFont)));
		}).orElse(null);
	}

	/**
	 * The font {@code usage}'s pane draws with in the game as patched so far: the last patch's
	 * copy when one was written, else the romfs's own. For a font set to a bundled typeface, the
	 * font drawn from it instead, which the patch puts in its place. Null for the system font or
	 * an archive that cannot be read.
	 */
	public Bcfnt current(Usage usage) {
		String name = fontName(usage);
		FreeFont free = freeFont(name);
		if (free != null) {
			return free.preview();
		}
		Bcfnt written = written(outputPath(usage.archive())).fonts().get(name);
		if (written != null) {
			return written;
		}
		try {
			return index.font(usage.archive(), name);
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	/**
	 * {@code usage}'s font as the patch would leave it given every character its donors can
	 * lend; null for the system font or an archive that cannot be read.
	 */
	public Bcfnt preview(Usage usage) {
		FreeFont free = freeFont(fontName(usage));
		if (free != null) {
			return free.preview();
		}
		Lent l = lent(usage.archive(), fontName(usage));
		return l == null ? null : l.font();
	}

	/**
	 * Has the font drawn for {@code usage}'s pane, when it is drawn from a bundled typeface, draw
	 * the characters of {@code text}, so they can be measured.
	 */
	public void prepare(Usage usage, String text) {
		FreeFont free = freeFont(fontName(usage));
		if (free != null && text != null) {
			free.draw(characters(text));
		}
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
					Bcfnt copy = original.copy();
					built = new Lent(copy,
							Lending.add(fontName, copy, Lending.lendableFrom(donors), donors, settings.extraSpace(fontName)));
				}
			} catch (IOException | RuntimeException unreadable) {
				// measured with the stand-in instead
			}
			known = Optional.ofNullable(built);
			previews.put(id, known);
		}
		return known.orElse(null);
	}

	/**
	 * The characters {@code text} draws: no color codes or line breaks, and nothing of a
	 * {@code [name]} the game replaces before drawing ({@code [シリーズ名]}, the series name).
	 */
	public static Set<Integer> characters(String text) {
		Set<Integer> out = new TreeSet<>();
		for (String line : text.split("\n", -1)) {
			boolean placeholder = false;
			for (int i = 0; i < line.length(); i += Character.charCount(line.codePointAt(i))) {
				int cp = line.codePointAt(i);
				if (cp == ControlCodes.COLOUR) {
					i++;
					continue;
				}
				boolean inPlaceholder = placeholder || cp == '[' && line.indexOf(']', i) > i;
				placeholder = inPlaceholder && cp != ']';
				if (cp >= 0x20 && !inPlaceholder) {
					out.add(cp);
				}
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
		// what the English adds to the fonts drawn from a typeface, by archive and game font
		Map<String, Set<Integer>> usedFree = new HashMap<>();
		for (Text t : texts) {
			Set<Integer> chars = characters(t.text());
			// a key shown only through another table's copy may be read from this one: its fonts need these too
			for (Usage u : index.panes(t.table(), t.key())) {
				String name = fontName(u);
				String id = u.archive() + "!" + name;
				if (settings.lettersFrom(name).bundled()) {
					usedFree.computeIfAbsent(id, k -> new TreeSet<>()).addAll(chars);
					continue;
				}
				used.computeIfAbsent(id, k -> new TreeSet<>()).addAll(chars);
				where.putIfAbsent(id, u);
			}
		}
		List<FontChange> fonts = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Map.Entry<String, Set<Integer>> e : used.entrySet()) {
			Usage u = where.get(e.getKey());
			FontChange c = change(u.archive(), fontName(u), e.getValue());
			if (c != null) {
				fonts.add(c);
				seen.add(e.getKey());
			}
		}
		// a font drawn from a typeface beside every copy of the game font set to it
		for (String gameFont : settings.fontsDrawnFrom().keySet()) {
			FreeFont free = freeFont(gameFont);
			if (free == null) {
				continue;
			}
			for (Path archive : index.archivesWithFont(gameFont)) {
				FontChange c = freeChange(archive, free, usedFree.getOrDefault(archive + "!" + gameFont, Set.of()));
				if (c != null) {
					fonts.add(c);
					seen.add(archive + "!" + c.font());
				}
			}
		}
		// fonts a previous patch wrote that no English uses any more, and drawn fonts no longer wanted
		for (Path archive : writtenArchives()) {
			Path original = index.root().resolve(output.relativize(archive).toString());
			for (Map.Entry<String, Bcfnt> font : written(archive).fonts().entrySet()) {
				if (!seen.contains(original + "!" + font.getKey())) {
					FontChange c = change(original, font.getKey(), Set.of());
					if (c == null && !carries(original, font.getKey())) {
						c = new FontChange(original, font.getKey(), Set.of(), Set.of(), Set.of(),
								Set.copyOf(font.getValue().cmap.keySet()), false, Map.of());
					}
					if (c != null && !c.remove().isEmpty()) {
						fonts.add(c);
					}
				}
			}
		}
		return new Plan(List.copyOf(fonts), layoutChanges(), imageChanges());
	}

	/** Every image with a replacement, in each archive holding it, and every image a previous patch replaced. */
	private List<ImageChange> imageChanges() {
		List<ImageChange> out = new ArrayList<>();
		Set<String> edited = images.edited();
		Set<String> seen = new HashSet<>();
		for (Image image : index.images()) {
			if (edited.contains(image.hash())) {
				imageChange(image, seen, out);
			}
		}
		for (Path archive : writtenArchives()) {
			Path original = index.root().resolve(output.relativize(archive).toString());
			for (String path : written(archive).images().keySet()) {
				Image image = image(original, path);
				if (image != null) {
					imageChange(image, seen, out);
				}
			}
		}
		return List.copyOf(out);
	}

	private void imageChange(Image image, Set<String> seen, List<ImageChange> out) {
		if (!seen.add(image.archive() + "!" + image.path())) {
			return;
		}
		byte[] replacement = images.replacement(image.hash(), () -> imageBytes(image));
		String expected = replacement == null ? image.hash() : Bclim.hash(replacement);
		boolean changed = !expected.equals(image.hash());
		Path outFile = outputPath(image.archive());
		String current = Files.isRegularFile(outFile) ? written(outFile).images().get(image.path()) : image.hash();
		boolean hasWork = !expected.equals(current);
		if (changed || hasWork) {
			out.add(new ImageChange(image.archive(), image.path(), changed, hasWork));
		}
	}

	/** {@code image}'s file as the romfs has it, or null when it cannot be read. */
	private byte[] imageBytes(Image image) {
		try {
			return index.imageBytes(image);
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	/** The romfs image at {@code path} in {@code archive}, or null. */
	private synchronized Image image(Path archive, String path) {
		if (imagesByPlace == null) {
			imagesByPlace = new HashMap<>();
			for (Image i : index.images()) {
				imagesByPlace.put(i.archive() + "!" + i.path(), i);
			}
		}
		return imagesByPlace.get(archive + "!" + path);
	}

	/** Whether {@code archive} in the romfs carries font {@code name}. */
	private boolean carries(Path archive, String name) {
		try {
			return index.font(archive, name) != null;
		} catch (IOException | RuntimeException unreadable) {
			return false;
		}
	}

	/**
	 * The free font beside {@code archive}'s copy of the game font: all that copy holds and what
	 * the English adds ({@code english}), drawn; against the one last written there.
	 */
	private FontChange freeChange(Path archive, FreeFont free, Set<Integer> english) {
		Bcfnt own;
		try {
			own = index.font(archive, free.gameFont());
		} catch (IOException | RuntimeException unreadable) {
			own = null;
		}
		if (own == null) {
			return null;
		}
		Set<Integer> needed = new TreeSet<>(own.cmap.keySet());
		needed.addAll(english);
		needed.add((int) ' ');
		needed.removeIf(c -> c < 0x20);
		Set<Integer> unavailable = free.draw(needed);
		Set<Integer> lent = new TreeSet<>(needed);
		lent.removeAll(unavailable);
		Bcfnt out = written(outputPath(archive)).fonts().get(free.name());
		Set<Integer> have = out == null ? Set.of() : out.cmap.keySet();
		Set<Integer> add = new TreeSet<>(lent);
		add.removeAll(have);
		Set<Integer> remove = new TreeSet<>(have);
		remove.removeAll(lent);
		boolean stale = false;
		Map<Typeface, Donor> donors = new HashMap<>();
		Map<Integer, Donor> from = new TreeMap<>();
		String weight = Typeface.weightFor(free.gameFont());
		for (int c : lent) {
			if (have.contains(c) && !sameGlyph(out.glyph(c), free.glyph(c))) {
				stale = true;
			}
			Typeface t = free.drawnBy(c);
			if (t != null) {
				from.put(c, donors.computeIfAbsent(t, k -> new Donor(Path.of(k.fileName(weight)), k.label(), free.preview())));
			}
		}
		return new FontChange(archive, free.name(), Collections.unmodifiableSet(needed),
				Collections.unmodifiableSet(unavailable), Collections.unmodifiableSet(add),
				Collections.unmodifiableSet(remove), stale, Collections.unmodifiableMap(from), free.gameFont());
	}

	/**
	 * The game fonts {@code archive} carries that are set to a bundled typeface, by the names of
	 * the fonts drawn for them, which its layouts name instead.
	 */
	private Map<String, String> freeNames(Path archive) {
		Map<String, String> out = new TreeMap<>();
		settings.fontsDrawnFrom().forEach((gameFont, face) -> {
			if (carries(archive, gameFont)) {
				out.put(gameFont, FreeFont.name(gameFont, face));
			}
		});
		return out;
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
		// every layout of an archive with a font drawn from a typeface may name it
		for (String gameFont : settings.fontsDrawnFrom().keySet()) {
			for (Path archive : index.archivesWithFont(gameFont)) {
				for (String layout : originalLayouts(archive).keySet()) {
					layoutChange(archive, layout, seen, out);
				}
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
		Map<String, String> names = freeNames(archive);
		byte[] expected = expected(original, panes, names);
		Map<String, String> renamed = new TreeMap<>(names);
		renamed.keySet().retainAll(Bclyt.read(original).fonts());
		boolean changed = !Arrays.equals(expected, original);
		Path outFile = outputPath(archive);
		byte[] current = Files.isRegularFile(outFile) ? written(outFile).layouts().get(layout) : original;
		boolean hasWork = !Arrays.equals(expected, current);
		if (changed || hasWork) {
			out.add(new LayoutChange(archive, layout, panes, Map.copyOf(renamed), changed, hasWork));
		}
	}

	/** Layout {@code original} with {@code panes}' changes, naming fonts by {@code names}. */
	private static byte[] expected(byte[] original, Map<String, TextOverride> panes, Map<String, String> names) {
		byte[] out = panes.isEmpty() ? original : Bclyt.withText(original, panes);
		return names.isEmpty() ? out : Bclyt.withFontNames(out, names);
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
		Bcfnt out = written(outputPath(archive)).fonts().get(fontName);
		Set<Integer> added = new TreeSet<>();
		if (out != null) {
			added.addAll(out.cmap.keySet());
			added.removeAll(original.cmap.keySet());
		}
		if (needed.isEmpty() && added.isEmpty()) {
			return null;
		}
		Lent lent = lentFor(archive, fontName, original, needed);
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
		return new FontChange(archive, fontName, Collections.unmodifiableSet(needed),
				Collections.unmodifiableSet(unavailable), Collections.unmodifiableSet(add),
				Collections.unmodifiableSet(remove), stale, Collections.unmodifiableMap(from));
	}

	/**
	 * {@code original} lent just {@code needed}, as the patch would write it: cheaper than the
	 * preview's every lendable character, and kept until the needed characters or the settings
	 * change, so a plan after an edit that adds no new character lends nothing again.
	 */
	private synchronized Lent lentFor(Path archive, String fontName, Bcfnt original, Set<Integer> needed) {
		String id = archive + "!" + fontName;
		PlanLent known = planLents.get(id);
		if (known != null && known.needed().equals(needed)) {
			return known.lent();
		}
		Bcfnt copy = original.copy();
		Lent lent = new Lent(copy, Lending.add(fontName, copy, needed, index.donors(fontName), settings.extraSpace(fontName)));
		planLents.put(id, new PlanLent(Set.copyOf(needed), lent));
		return lent;
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
		Map<Path, List<ImageChange>> imagesBy = new LinkedHashMap<>();
		for (ImageChange i : plan.images()) {
			imagesBy.computeIfAbsent(i.archive(), k -> new ArrayList<>()).add(i);
		}
		Set<Path> archives = new LinkedHashSet<>(fontsBy.keySet());
		archives.addAll(layoutsBy.keySet());
		archives.addAll(imagesBy.keySet());
		List<Path> wrote = new ArrayList<>();
		for (Path archive : archives) {
			List<FontChange> fonts = fontsBy.getOrDefault(archive, List.of());
			List<LayoutChange> layouts = layoutsBy.getOrDefault(archive, List.of());
			List<ImageChange> imageChanges = imagesBy.getOrDefault(archive, List.of());
			Set<String> replaced = new HashSet<>();
			imageChanges.stream().filter(ImageChange::changed).forEach(i -> replaced.add(i.path()));
			Path out = outputPath(archive);
			Map<String, Set<Integer>> lend = new HashMap<>();
			List<FontChange> free = new ArrayList<>();
			Map<String, String> names = new TreeMap<>();
			for (FontChange f : fonts) {
				if (f.lent().isEmpty()) {
					continue;
				}
				if (f.free()) {
					free.add(f);
					names.put(f.drawnFor(), f.font());
				} else {
					lend.put(f.font(), f.lent());
				}
			}
			if (lend.isEmpty() && free.isEmpty() && replaced.isEmpty() && layouts.stream().noneMatch(LayoutChange::changed)) {
				progress.accept("Removing " + index.root().relativize(archive));
				Files.deleteIfExists(out);
				continue;
			}
			if (fonts.stream().noneMatch(FontChange::hasWork) && layouts.stream().noneMatch(LayoutChange::hasWork)
					&& imageChanges.stream().noneMatch(ImageChange::hasWork) && Files.isRegularFile(out)) {
				continue;
			}
			progress.accept("Patching " + index.root().relativize(archive));
			Darc.Node root = Archive.load(archive);
			Map<String, Darc.Node> files = Darc.files(root);
			Map<String, Darc.Node> byName = new HashMap<>();
			for (Map.Entry<String, Darc.Node> file : files.entrySet()) {
				String path = file.getKey();
				String name = path.substring(path.lastIndexOf('/') + 1);
				byName.put(name, file.getValue());
				Set<Integer> codes = lend.get(name);
				if (codes != null) {
					Bcfnt font = Bcfnt.parse(file.getValue().data);
					Lending.add(name, font, codes, index.donors(name), settings.extraSpace(name));
					file.getValue().data = font.toBytes();
				}
				if (path.endsWith(".bclyt")) {
					file.getValue().data = expected(file.getValue().data, overrides.forLayout(path), names);
				}
				if (replaced.contains(path)) {
					byte[] image = images.replacement(file.getValue().data);
					if (image == null) {
						throw new IOException("cannot read the replacement for " + path + " (" + images.png(Bclim.hash(file.getValue().data)) + ")");
					}
					file.getValue().data = image;
				}
			}
			// the drawn fonts beside the game's, which are left as they are
			for (FontChange f : free) {
				FreeFont ff = freeFont(f.drawnFor());
				Darc.Node game = byName.get(f.drawnFor());
				if (ff == null || game == null) {
					throw new IOException("cannot add " + f.font() + " beside " + f.drawnFor() + " in " + archive);
				}
				Darc.addBeside(root, game, f.font(), ff.build(f.lent()).toBytes());
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
				Map<String, String> images = new HashMap<>();
				for (Map.Entry<String, Darc.Node> file : files.entrySet()) {
					if (file.getKey().endsWith(".bcfnt")) {
						fonts.put(file.getKey().substring(file.getKey().lastIndexOf('/') + 1),
								Bcfnt.parse(file.getValue().data));
					} else if (file.getKey().endsWith(".bclim")) {
						images.put(file.getKey(), Bclim.hash(file.getValue().data));
					}
				}
				w = new Written(time, fonts, layoutsOf(files), images);
				written.put(out, w);
			}
			return w;
		} catch (IOException | RuntimeException unreadable) {
			return NOTHING;
		}
	}
}
