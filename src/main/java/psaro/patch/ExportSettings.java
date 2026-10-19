package psaro.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.patch.PatchExport.Extra;
import psaro.project.Translations;

/**
 * What Export patch packs besides the patch folder, per project: the readme, kept in
 * {@code <romfs>.psaro/patch-readme.txt} so it can be edited anywhere (the default readme while
 * there is none), and the files added to the zip, in {@code <romfs>.psaro/export.json}, each by
 * the path it is read from and its path in the zip, with whether the zip's patcher page holds the
 * game's files too. A file is read when the zip is written, so the zip always has it as it is then.
 */
public final class ExportSettings {

	/** The readme's file in the project folder. */
	public static final String README_FILE = "patch-readme.txt";
	private static final String FILE = "export.json";

	private final Path readmeFile;
	private final Path file;
	private String readme;
	private List<Extra> extras = List.of();
	/** Whether the zip's patcher page holds the game's files too ({@link PatcherPage}). */
	private boolean gamePatcher;

	private ExportSettings(Path folder) {
		this.readmeFile = folder.resolve(README_FILE);
		this.file = folder.resolve(FILE);
	}

	/** What was saved for {@code romfs}'s project; the default readme and no files when nothing was. */
	public static ExportSettings open(Path romfs) throws IOException {
		ExportSettings s = new ExportSettings(Translations.folderFor(romfs));
		if (Files.isRegularFile(s.readmeFile)) {
			// the footer is added at export; a readme written with it keeps it once
			s.readme = PatchExport.withoutFooter(Files.readString(s.readmeFile, StandardCharsets.UTF_8));
		}
		if (Files.isRegularFile(s.file)) {
			try {
				JSONObject json = new JSONObject(Files.readString(s.file, StandardCharsets.UTF_8));
				s.gamePatcher = json.optBoolean("gamePatcher", false);
				JSONArray files = json.optJSONArray("extraFiles");
				List<Extra> read = new ArrayList<>();
				for (int i = 0; files != null && i < files.length(); i++) {
					JSONObject e = files.getJSONObject(i);
					read.add(new Extra(Path.of(e.getString("source")), e.getString("destination")));
				}
				s.extras = List.copyOf(read);
			} catch (JSONException | InvalidPathException e) {
				throw new IOException(s.file + " is not valid export settings: " + e.getMessage(), e);
			}
		}
		return s;
	}

	/**
	 * The readme, {@link PatchExport#TITLE_ID} standing for the title id: the project's own, or the
	 * default; either way without {@link PatchExport#FOOTER}, which the export adds.
	 */
	public synchronized String readme() {
		return readme != null ? readme : PatchExport.defaultReadme();
	}

	/** Whether the project has a readme of its own. */
	public synchronized boolean ownReadme() {
		return readme != null;
	}

	/** Saves {@code text} as the project's readme; the default readme's text (or none) goes back to the default. */
	public synchronized void setReadme(String text) throws IOException {
		String clean = text == null ? null : PatchExport.withoutFooter(text);
		if (clean == null || clean.equals(PatchExport.defaultReadme())) {
			readme = null;
			Files.deleteIfExists(readmeFile);
			return;
		}
		if (clean.equals(readme)) {
			return;
		}
		readme = clean;
		Files.createDirectories(readmeFile.getParent());
		Files.writeString(readmeFile, clean, StandardCharsets.UTF_8);
	}

	/** The files added to the zip, in order. */
	public synchronized List<Extra> extras() {
		return extras;
	}

	/** Saves the files added to the zip. */
	public synchronized void setExtras(List<Extra> files) throws IOException {
		if (files.equals(extras)) {
			return;
		}
		extras = List.copyOf(files);
		save();
	}

	/**
	 * Whether the zip's patcher page ({@link PatcherPage}) holds the game's patched files, so players
	 * can build a patched copy of the game instead of using Luma3DS's game patching. A DLC's files
	 * are always in it; the game's make it as large again as the patch.
	 */
	public synchronized boolean gamePatcher() {
		return gamePatcher;
	}

	public synchronized void setGamePatcher(boolean on) throws IOException {
		if (on != gamePatcher) {
			gamePatcher = on;
			save();
		}
	}

	private void save() throws IOException {
		JSONArray list = new JSONArray();
		for (Extra e : extras) {
			list.put(new JSONObject().put("source", e.source().toString()).put("destination", e.destination()));
		}
		JSONObject json = new JSONObject().put("extraFiles", list);
		if (gamePatcher) {
			json.put("gamePatcher", true);
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}
}
