package psaro.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The decrypted copy of a title (a CIA or a .3ds) its patched copy is built from, remembered per
 * project in {@code build.json}: the game's in its project, a DLC's in the game's {@code DLC} folder.
 */
public final class BuildBase {

	private static final String FILE = "build.json";

	private BuildBase() {
	}

	/** The copy {@code romfs}'s project built from last, or null when none was chosen or it cannot be read. */
	public static Path of(Path romfs) {
		Path file = Translations.folderFor(romfs).resolve(FILE);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			String base = new JSONObject(Files.readString(file, StandardCharsets.UTF_8)).optString("base", "");
			return base.isEmpty() ? null : Path.of(base);
		} catch (IOException | JSONException | java.nio.file.InvalidPathException unreadable) {
			return null;
		}
	}

	/** Remembers {@code base} as the copy {@code romfs}'s project builds from. */
	public static void set(Path romfs, Path base) throws IOException {
		Path file = Translations.folderFor(romfs).resolve(FILE);
		Files.createDirectories(file.getParent());
		Files.writeString(file, new JSONObject().put("base", base.toAbsolutePath().normalize().toString()).toString(2) + "\n",
				StandardCharsets.UTF_8);
	}
}
