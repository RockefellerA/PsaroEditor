package psaro.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The DLC attached to a game's project, in {@code <game>.psaro/DLC/dlc.json}: the DLC's folder of
 * content romfs folders (as extracted from its CIA, one folder per content), as an absolute path
 * and as a path from the file (tried when the first is gone, for folders moved together), and the
 * content folders chosen to work on. The DLC's project is the rest of that {@code DLC} folder
 * ({@link Translations#attachDlc}).
 */
public final class DlcLink {

	private static final String FILE = "dlc.json";

	/** The DLC's folder and the content folders in it chosen to work on, by name, in order. */
	public record Dlc(Path folder, List<String> contents) {

		public Dlc {
			contents = List.copyOf(contents);
		}
	}

	private DlcLink() {
	}

	/** Where the link of {@code game}'s DLC is kept. */
	public static Path file(Path game) {
		return Translations.folderFor(game).resolve(Translations.DLC).resolve(FILE);
	}

	/**
	 * The DLC attached to the game whose romfs is {@code game}, or null when none is. Its folder may
	 * no longer be there: the absolute path is kept so a message can say where it was.
	 */
	public static Dlc read(Path game) throws IOException {
		Path file = file(game);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
			String absolute = json.optString("folder", "");
			String relative = json.optString("relative", "");
			Path folder;
			if (!absolute.isEmpty() && Files.isDirectory(Path.of(absolute))) {
				folder = Path.of(absolute);
			} else if (!relative.isEmpty() && Files.isDirectory(file.getParent().resolve(relative).normalize())) {
				folder = file.getParent().resolve(relative).normalize();
			} else if (!absolute.isEmpty()) {
				folder = Path.of(absolute);
			} else {
				throw new IOException(file + " names no DLC folder");
			}
			List<String> contents = new ArrayList<>();
			JSONArray chosen = json.optJSONArray("contents");
			if (chosen != null) {
				for (int i = 0; i < chosen.length(); i++) {
					contents.add(chosen.getString(i));
				}
			}
			return new Dlc(folder, contents);
		} catch (JSONException e) {
			throw new IOException(file + " is not a valid DLC link: " + e.getMessage(), e);
		}
	}

	/** Attaches {@code dlc} to the game whose romfs is {@code game}, in place of any DLC it had. */
	public static void write(Path game, Dlc dlc) throws IOException {
		Path file = file(game);
		Path folder = dlc.folder().toAbsolutePath().normalize();
		JSONObject json = new JSONObject();
		json.put("folder", folder.toString());
		try {
			json.put("relative", file.getParent().toAbsolutePath().normalize().relativize(folder).toString().replace('\\', '/'));
		} catch (IllegalArgumentException differentRoots) {
			// only the absolute path then
		}
		json.put("contents", new JSONArray(dlc.contents()));
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}
}
