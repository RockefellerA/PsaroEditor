package psaro.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.format.Smdh;

/**
 * The title the HOME Menu shows for the game in place of its own, kept per project in
 * {@code <romfs>.psaro/home-title.json}: a short title, a long title and a publisher, each left out
 * to keep the game's. Only a patched copy carries it ({@link psaro.patch.TitleBuild}): the HOME
 * Menu reads it from the title installed, which Luma3DS's game patching does not reach.
 */
public final class HomeTitle {

	private static final String FILE = "home-title.json";

	private HomeTitle() {
	}

	/** The title {@code romfs}'s project gives the game, or null when it gives none. */
	public static Smdh.Title of(Path romfs) throws IOException {
		Path file = Translations.folderFor(romfs).resolve(FILE);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
			Smdh.Title title = new Smdh.Title(json.optString("short", ""), json.optString("long", ""),
					json.optString("publisher", ""));
			return title.isBlank() ? null : title;
		} catch (JSONException e) {
			throw new IOException(file + " is not a valid HOME Menu title: " + e.getMessage(), e);
		}
	}

	/** Gives {@code title} to the game of {@code romfs}'s project; null or blank gives the game its own back. */
	public static void set(Path romfs, Smdh.Title title) throws IOException {
		Path file = Translations.folderFor(romfs).resolve(FILE);
		if (title == null || title.isBlank()) {
			Files.deleteIfExists(file);
			return;
		}
		JSONObject json = new JSONObject();
		put(json, "short", title.shortTitle());
		put(json, "long", title.longTitle());
		put(json, "publisher", title.publisher());
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}

	private static void put(JSONObject json, String key, String value) {
		if (value != null && !value.isBlank()) {
			json.put(key, value);
		}
	}
}
