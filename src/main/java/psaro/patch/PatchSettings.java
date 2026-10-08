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
 * every change.
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
	/** Font file name to the typeface its added letters come from; a font not here borrows from the game's. */
	private final Map<String, Typeface> lettersFrom = new TreeMap<>();
	/** The one value all fonts shared before, until it is handed out; null once it has been. */
	private String legacy = OLD_DEFAULT;
	private boolean copyToMods;
	private Path modsFolder;

	private PatchSettings(Path file) {
		this.file = file;
	}

	/** Loads what was saved for {@code romfs}; nothing saved yet gives the defaults. */
	public static PatchSettings open(Path romfs) throws IOException {
		PatchSettings s = new PatchSettings(Translations.folderFor(romfs).resolve("fonts.json"));
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
						Typeface t = Typeface.of(from.getString(font));
						if (t.bundled()) {
							s.lettersFrom.put(font, t);
						}
					});
				}
				s.copyToMods = json.optBoolean("copyToMods", false);
				String mods = json.optString("modsFolder", "");
				s.modsFolder = mods.isEmpty() ? null : Path.of(mods);
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

	/** Where the letters added to {@code font} come from: the game's fonts unless set. */
	public synchronized Typeface lettersFrom(String font) {
		return lettersFrom.getOrDefault(font, Typeface.GAME);
	}

	/** Sets where the letters added to {@code font} come from. */
	public synchronized void setLettersFrom(String font, Typeface typeface) throws IOException {
		if (typeface.bundled()) {
			lettersFrom.put(font, typeface);
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
			lettersFrom.forEach((font, t) -> from.put(font, t.id()));
			json.put("lettersFrom", from);
		}
		json.put("copyToMods", copyToMods);
		if (modsFolder != null) {
			json.put("modsFolder", modsFolder.toString());
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}
}
