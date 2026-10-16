package psaro.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.project.Translations;

/**
 * How the font patch builds, in {@code <romfs>.psaro/fonts.json}: per font, the letters that get
 * an extra pixel of advance when they are added to it and the typeface they are drawn from
 * ({@link Typeface}), and whether to copy the patched archives into a mods folder. Written on
 * every change. A typeface is kept by its id, so one not imported here ({@link Typefaces}) keeps
 * its setting, drawing with the game font until it is.
 *
 * <p>Older settings gave every font the same letters (one string, "ty" when unset). That value is
 * kept as {@link #legacyExtraSpace} until {@link FontPatcher} hands it to the fonts it was used
 * for and calls {@link #setExtraSpace(Collection, String)}.
 */
public final class PatchSettings {

	/** What every font got before the letters were set per font, when nothing else was saved. */
	static final String OLD_DEFAULT = "ty";

	private final Path file;
	/** Font file name to its letters; a font not here gets none. */
	private final Map<String, String> extraSpace = new TreeMap<>();
	/** Font file name to the id of the typeface its added letters come from; a font not here borrows from the game's. */
	private final Map<String, String> lettersFrom = new TreeMap<>();
	/** The typefaces there are to draw from. */
	private final Typefaces typefaces;
	/** The one value all fonts shared before, until it is handed out; null once it has been. */
	private String legacy = OLD_DEFAULT;
	private boolean copyToMods;
	private Path modsFolder;
	/** The game's title id, as the exported patch's folders name it, or null if none was given. */
	private String titleId;

	private PatchSettings(Path file, Typefaces typefaces) {
		this.file = file;
		this.typefaces = typefaces;
	}

	/** Loads what was saved for {@code romfs}, and the typefaces imported into it; nothing saved yet gives the defaults. */
	public static PatchSettings open(Path romfs) throws IOException {
		PatchSettings s = new PatchSettings(Translations.folderFor(romfs).resolve("fonts.json"), Typefaces.open(romfs));
		if (Files.isRegularFile(s.file)) {
			try {
				JSONObject json = new JSONObject(Files.readString(s.file, StandardCharsets.UTF_8));
				Object space = json.opt("extraSpace");
				if (space instanceof JSONObject perFont) {
					s.legacy = null;
					perFont.keySet().forEach(font -> s.extraSpace.put(font, perFont.getString(font)));
				} else if (space instanceof String all) {
					s.legacy = all;
				}
				JSONObject from = json.optJSONObject("lettersFrom");
				if (from != null) {
					from.keySet().forEach(font -> {
						String id = from.getString(font);
						if (!id.equals(Typeface.GAME.id())) {
							s.lettersFrom.put(font, id);
						}
					});
				}
				s.copyToMods = json.optBoolean("copyToMods", false);
				String mods = json.optString("modsFolder", "");
				s.modsFolder = mods.isEmpty() ? null : Path.of(mods);
				String id = json.optString("titleId", "");
				s.titleId = id.isEmpty() ? null : id;
			} catch (JSONException e) {
				throw new IOException(s.file + " is not valid font patch settings: " + e.getMessage(), e);
			}
		}
		return s;
	}

	/** The characters that get one more pixel of advance when they are added to {@code font}. */
	public synchronized String extraSpace(String font) {
		return extraSpace.getOrDefault(font, "");
	}

	/** The typefaces there are to draw from: the bundled one and those imported into the project. */
	public Typefaces typefaces() {
		return typefaces;
	}

	/**
	 * Where the letters added to {@code font} come from: the game's fonts unless set, or set to a
	 * typeface not imported here.
	 */
	public synchronized Typeface lettersFrom(String font) {
		return typefaces.of(lettersFrom.get(font));
	}

	/** The id of the typeface {@code font} is set to, imported here or not; null for the game's fonts. */
	public synchronized String lettersFromId(String font) {
		return lettersFrom.get(font);
	}

	/** Every font set to a typeface there is here, with it. */
	public synchronized Map<String, Typeface> fontsDrawnFrom() {
		Map<String, Typeface> out = new TreeMap<>();
		lettersFrom.forEach((font, id) -> {
			Typeface t = typefaces.of(id);
			if (t.bundled()) {
				out.put(font, t);
			}
		});
		return Map.copyOf(out);
	}

	/** The id of every typeface a font is set to, imported here or not. */
	public synchronized java.util.Set<String> typefaceIds() {
		return java.util.Set.copyOf(lettersFrom.values());
	}

	/** Sets where the letters added to {@code font} come from. */
	public synchronized void setLettersFrom(String font, Typeface typeface) throws IOException {
		if (typeface.bundled()) {
			lettersFrom.put(font, typeface.id());
		} else {
			lettersFrom.remove(font);
		}
		save();
	}

	/** The letters every font shared under the older settings, or null once handed out. */
	synchronized String legacyExtraSpace() {
		return legacy;
	}

	public boolean copyToMods() {
		return copyToMods;
	}

	/** The romfs-shaped mods folder to copy to, or null if none was chosen. */
	public Path modsFolder() {
		return modsFolder;
	}

	/** The game's title id the exported patch is laid out for, or null if none was given. */
	public synchronized String titleId() {
		return titleId;
	}

	public synchronized void setTitleId(String id) throws IOException {
		titleId = id;
		save();
	}

	/** Sets the letters of one font; spaces are dropped, and none clears it. */
	public void setExtraSpace(String font, String chars) throws IOException {
		setExtraSpace(List.of(font), chars);
	}

	/**
	 * Sets the letters of each of {@code fonts}, and with that retires the older shared value
	 * (any fonts it was used for having been given it).
	 */
	public synchronized void setExtraSpace(Collection<String> fonts, String chars) throws IOException {
		String clean = chars.codePoints().filter(c -> !Character.isWhitespace(c)).distinct()
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
		for (String font : fonts) {
			if (clean.isEmpty()) {
				extraSpace.remove(font);
			} else {
				extraSpace.put(font, clean);
			}
		}
		legacy = null;
		save();
	}

	/**
	 * Takes the settings {@code other} (a related project's, such as a game's DLC's parent) has for
	 * the fonts {@code wanted} accepts, where this project has set nothing for that font: the letters
	 * that get an extra pixel of advance, and the typeface the added letters come from when it is
	 * imported here. Settings already made here are kept, and the copy-to-mods and title id
	 * settings are never taken. Returns how many fonts were given something.
	 */
	public synchronized int adopt(PatchSettings other, java.util.function.Predicate<String> wanted) throws IOException {
		java.util.Set<String> took = new java.util.TreeSet<>();
		for (Map.Entry<String, String> e : other.extraSpace.entrySet()) {
			if (wanted.test(e.getKey()) && !extraSpace.containsKey(e.getKey())) {
				extraSpace.put(e.getKey(), e.getValue());
				took.add(e.getKey());
			}
		}
		for (Map.Entry<String, String> e : other.lettersFrom.entrySet()) {
			if (wanted.test(e.getKey()) && !lettersFrom.containsKey(e.getKey()) && typefaces.of(e.getValue()).bundled()) {
				lettersFrom.put(e.getKey(), e.getValue());
				took.add(e.getKey());
			}
		}
		if (took.isEmpty()) {
			return 0;
		}
		// as setExtraSpace does, the per-font letters now stand for what the shared value once did
		legacy = null;
		save();
		return took.size();
	}

	public synchronized void setCopyToMods(boolean copy, Path folder) throws IOException {
		copyToMods = copy;
		modsFolder = folder;
		save();
	}

	private void save() throws IOException {
		JSONObject json = new JSONObject();
		JSONObject space = new JSONObject();
		extraSpace.forEach(space::put);
		json.put("extraSpace", legacy != null ? legacy : space);
		if (!lettersFrom.isEmpty()) {
			JSONObject from = new JSONObject();
			lettersFrom.forEach(from::put);
			json.put("lettersFrom", from);
		}
		json.put("copyToMods", copyToMods);
		if (modsFolder != null) {
			json.put("modsFolder", modsFolder.toString());
		}
		if (titleId != null) {
			json.put("titleId", titleId);
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}
}
