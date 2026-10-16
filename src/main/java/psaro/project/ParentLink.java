package psaro.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.romfs.RomfsIndex;

/**
 * The project a romfs borrows from, in {@code <romfs>.psaro/parent.json}: a DLC's game, say. It
 * holds the parent's romfs folder, as an absolute path and as a path from this project's folder
 * (tried when the first is gone, for a pair of folders moved together), and whether the user
 * has said not to be asked about a parent for this project. Nothing is written until the link
 * is made, dismissed or cleared.
 */
public final class ParentLink {

	private static final String FILE = "parent.json";

	private final Path file;
	private final Path romfs;
	private Path parent;
	private boolean dismissed;

	private ParentLink(Path romfs) {
		this.romfs = romfs.toAbsolutePath().normalize();
		this.file = Translations.folderFor(romfs).resolve(FILE);
	}

	/** Loads what was saved for {@code romfs}; nothing saved yet is not an error. */
	public static ParentLink open(Path romfs) throws IOException {
		ParentLink l = new ParentLink(romfs);
		if (Files.isRegularFile(l.file)) {
			try {
				JSONObject json = new JSONObject(Files.readString(l.file, StandardCharsets.UTF_8));
				l.dismissed = json.optBoolean("dismissed", false);
				String absolute = json.optString("romfs", "");
				String relative = json.optString("relative", "");
				Path base = l.file.getParent();
				if (!absolute.isEmpty() && Files.isDirectory(Path.of(absolute))) {
					l.parent = Path.of(absolute);
				} else if (!relative.isEmpty() && Files.isDirectory(base.resolve(relative).normalize())) {
					l.parent = base.resolve(relative).normalize();
				} else if (!absolute.isEmpty()) {
					// kept so the dialog can say where it was; reading it fails clearly
					l.parent = Path.of(absolute);
				}
			} catch (JSONException e) {
				throw new IOException(l.file + " is not a valid parent link: " + e.getMessage(), e);
			}
		}
		return l;
	}

	/** The parent's romfs folder, or null when there is none. It may no longer exist: see {@link #parentExists}. */
	public Path parent() {
		return parent;
	}

	public boolean parentExists() {
		return parent != null && Files.isDirectory(parent);
	}

	/** Whether the user said not to be asked about a parent for this project. */
	public boolean dismissed() {
		return dismissed;
	}

	/**
	 * Links to the project whose romfs is {@code parentRomfs}. It must be another extracted romfs,
	 * not this one.
	 */
	public void link(Path parentRomfs) throws IOException {
		Path p = parentRomfs.toAbsolutePath().normalize();
		if (p.equals(romfs)) {
			throw new IOException("A project cannot be its own parent.");
		}
		if (!RomfsIndex.looksLikeRomfs(p)) {
			throw new IOException("No string tables were found in " + p + ", so it does not look like an extracted romfs.");
		}
		parent = p;
		dismissed = false;
		save();
	}

	/** Forgets the parent; the user is asked about one again when a DLC is opened. */
	public void unlink() throws IOException {
		parent = null;
		dismissed = false;
		save();
	}

	/** Remembers not to ask about a parent for this project. */
	public void dismiss() throws IOException {
		dismissed = true;
		save();
	}

	private void save() throws IOException {
		if (parent == null && !dismissed) {
			Files.deleteIfExists(file);
			return;
		}
		JSONObject json = new JSONObject();
		if (parent != null) {
			json.put("romfs", parent.toString());
			try {
				json.put("relative", file.getParent().relativize(parent).toString().replace('\\', '/'));
			} catch (IllegalArgumentException differentRoots) {
				// only the absolute path then
			}
		}
		if (dismissed) {
			json.put("dismissed", true);
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}
}
