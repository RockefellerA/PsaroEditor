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
 * What Export patch was last told, per project: how the patch is applied ({@link Mode}); for
 * LayeredFS, the readme, kept in {@code <romfs>.psaro/patch-readme.txt} so it can be edited
 * anywhere (the default readme while there is none), the DLC's patcher page's readme the same way
 * in {@code patcher-readme.txt}, and the files added to the zip, each by the path it is read from
 * and its path in the zip; for a patched CIA, whether the patcher download is written, its readme
 * in {@code download-readme.txt}, the files added to it the same way, and whether patched copies
 * are built. All but the readmes are in
 * {@code <romfs>.psaro/export.json}. An added file is read when the zip is written, so the zip
 * always has it as it is then.
 */
public final class ExportSettings {

	/** How players apply the patch. */
	public enum Mode {
		/** A zip of the patched files, which Luma3DS's game patching or Citra's mods folder lays over the game's romfs. */
		LAYERED_FS,
		/** The game and its DLC rebuilt with the patch in them ({@link TitleBuild}), by a patcher page or here. */
		CIA
	}

	/** The readme's file in the project folder. */
	public static final String README_FILE = "patch-readme.txt";
	/** The DLC's patcher page's readme's file in the project folder. */
	public static final String PATCHER_README_FILE = "patcher-readme.txt";
	/** The patcher download's readme's file in the project folder. */
	public static final String DOWNLOAD_README_FILE = "download-readme.txt";
	private static final String FILE = "export.json";

	private final Path readmeFile;
	private final Path patcherReadmeFile;
	private final Path downloadReadmeFile;
	private final Path file;
	private String readme;
	private String patcherReadme;
	private String downloadReadme;
	private List<Extra> extras = List.of();
	/** For a patched CIA: the files added to the patcher download. */
	private List<Extra> downloadExtras = List.of();
	private Mode mode = Mode.LAYERED_FS;
	/** For a patched CIA: whether the patcher download is written ({@link PatcherPage}). */
	private boolean patcherDownload = true;
	/** For a patched CIA: whether patched copies are built from the decrypted copies chosen. */
	private boolean buildCopies;

	private ExportSettings(Path folder) {
		this.readmeFile = folder.resolve(README_FILE);
		this.patcherReadmeFile = folder.resolve(PATCHER_README_FILE);
		this.downloadReadmeFile = folder.resolve(DOWNLOAD_README_FILE);
		this.file = folder.resolve(FILE);
	}

	/** What was saved for {@code romfs}'s project; the default readme and no files when nothing was. */
	public static ExportSettings open(Path romfs) throws IOException {
		ExportSettings s = new ExportSettings(Translations.folderFor(romfs));
		if (Files.isRegularFile(s.readmeFile)) {
			// the footer is added at export; a readme written with it keeps it once
			s.readme = PatchExport.withoutFooter(Files.readString(s.readmeFile, StandardCharsets.UTF_8));
		}
		if (Files.isRegularFile(s.patcherReadmeFile)) {
			s.patcherReadme = PatchExport.withoutFooter(Files.readString(s.patcherReadmeFile, StandardCharsets.UTF_8));
		}
		if (Files.isRegularFile(s.downloadReadmeFile)) {
			s.downloadReadme = PatchExport.withoutFooter(Files.readString(s.downloadReadmeFile, StandardCharsets.UTF_8));
		}
		if (Files.isRegularFile(s.file)) {
			try {
				JSONObject json = new JSONObject(Files.readString(s.file, StandardCharsets.UTF_8));
				s.mode = json.optEnum(Mode.class, "mode", Mode.LAYERED_FS);
				s.patcherDownload = json.optBoolean("patcherDownload", true);
				s.buildCopies = json.optBoolean("buildCopies", false);
				s.extras = extras(json.optJSONArray("extraFiles"));
				s.downloadExtras = extras(json.optJSONArray("downloadFiles"));
			} catch (JSONException | InvalidPathException e) {
				throw new IOException(s.file + " is not valid export settings: " + e.getMessage(), e);
			}
		}
		return s;
	}

	/** The added files {@code files} lists, none when it is null. */
	private static List<Extra> extras(JSONArray files) {
		List<Extra> read = new ArrayList<>();
		for (int i = 0; files != null && i < files.length(); i++) {
			JSONObject e = files.getJSONObject(i);
			read.add(new Extra(Path.of(e.getString("source")), e.getString("destination")));
		}
		return List.copyOf(read);
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
		readme = keep(text, PatchExport.defaultReadme(), readme, readmeFile);
	}

	/**
	 * The readme of the DLC's patcher page in a LayeredFS zip ({@link PatcherPage#FOLDER}/README.txt),
	 * {@link PatchExport#TITLE_ID} standing for the DLC's title id: the project's own, or the default.
	 */
	public synchronized String patcherReadme() {
		return patcherReadme != null ? patcherReadme : PatcherPage.defaultDlcReadme();
	}

	/** Whether the project has a patcher readme of its own. */
	public synchronized boolean ownPatcherReadme() {
		return patcherReadme != null;
	}

	/** Saves {@code text} as the project's patcher readme; the default's text (or none) goes back to the default. */
	public synchronized void setPatcherReadme(String text) throws IOException {
		patcherReadme = keep(text, PatcherPage.defaultDlcReadme(), patcherReadme, patcherReadmeFile);
	}

	/**
	 * For a patched CIA, the patcher download's readme, {@link PatchExport#TITLE_ID} standing for the
	 * game's title id and {@link PatcherPage#DLC_TITLE_ID} the DLC's: the project's own, or null
	 * while the page writes its own ({@link PatcherPage#defaultDownloadReadme}).
	 */
	public synchronized String downloadReadme() {
		return downloadReadme;
	}

	/**
	 * Saves {@code text} as the project's patcher download readme; {@code byDefault}'s text (or none),
	 * what the page would write, goes back to the page's own.
	 */
	public synchronized void setDownloadReadme(String text, String byDefault) throws IOException {
		downloadReadme = keep(text, byDefault, downloadReadme, downloadReadmeFile);
	}

	/**
	 * Saves {@code text} in {@code file}, which {@code had} was read from, and returns it; when it is
	 * {@code byDefault}'s text (or none), removes the file instead and returns null.
	 */
	private static String keep(String text, String byDefault, String had, Path file) throws IOException {
		String clean = text == null ? null : PatchExport.withoutFooter(text);
		if (clean == null || clean.equals(byDefault)) {
			Files.deleteIfExists(file);
			return null;
		}
		if (!clean.equals(had)) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, clean, StandardCharsets.UTF_8);
		}
		return clean;
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

	/** For a patched CIA: the files added to the patcher download, in order. */
	public synchronized List<Extra> downloadExtras() {
		return downloadExtras;
	}

	/** Saves the files added to the patcher download. */
	public synchronized void setDownloadExtras(List<Extra> files) throws IOException {
		if (files.equals(downloadExtras)) {
			return;
		}
		downloadExtras = List.copyOf(files);
		save();
	}

	/** How players apply the patch, as last chosen. */
	public synchronized Mode mode() {
		return mode;
	}

	/**
	 * For a patched CIA: whether the patcher download is written, the patcher page ({@link PatcherPage})
	 * with the game's patched files and its DLC's, for players to build their own patched copies. About
	 * as large as the patch.
	 */
	public synchronized boolean patcherDownload() {
		return patcherDownload;
	}

	/** For a patched CIA: whether patched copies are built here, from the decrypted copies chosen. */
	public synchronized boolean buildCopies() {
		return buildCopies;
	}

	/** Saves how the patch is applied and, for a patched CIA, what is made. */
	public synchronized void setMode(Mode mode, boolean patcherDownload, boolean buildCopies) throws IOException {
		if (mode != this.mode || patcherDownload != this.patcherDownload || buildCopies != this.buildCopies) {
			this.mode = mode;
			this.patcherDownload = patcherDownload;
			this.buildCopies = buildCopies;
			save();
		}
	}

	private void save() throws IOException {
		JSONObject json = new JSONObject().put("extraFiles", list(extras)).put("mode", mode.name());
		if (!downloadExtras.isEmpty()) {
			json.put("downloadFiles", list(downloadExtras));
		}
		if (!patcherDownload) {
			json.put("patcherDownload", false);
		}
		if (buildCopies) {
			json.put("buildCopies", true);
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}

	private static JSONArray list(List<Extra> files) {
		JSONArray list = new JSONArray();
		for (Extra e : files) {
			list.put(new JSONObject().put("source", e.source().toString()).put("destination", e.destination()));
		}
		return list;
	}
}
