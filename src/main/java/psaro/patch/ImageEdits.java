package psaro.patch;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.format.Bclim;
import psaro.project.Translations;

/**
 * Images replaced in the patch, in {@code <romfs>.psaro/images}: each a PNG beside an
 * {@code images.json} that maps the original image's SHA-256 to the PNG and the image's path in
 * its archive. Keyed by the original's content, an edit goes to every archive holding that very
 * image, the same button in a dozen scenes. The PNGs are plain files, to change in any editor.
 */
public final class ImageEdits {

	/** One replaced image: its path inside an archive (as found first) and its PNG's file name. */
	public record Edit(String path, String png) {
	}

	private final Path folder;
	private final Path file;
	private final Map<String, Edit> edits = new TreeMap<>();
	/** Encoded replacements, by original hash, as of the PNG's modification time. */
	private final Map<String, Encoded> encoded = new HashMap<>();

	private record Encoded(FileTime time, byte[] bytes) {
	}

	private ImageEdits(Path folder) {
		this.folder = folder;
		this.file = folder.resolve("images.json");
	}

	/** Loads what was saved for {@code romfs}; nothing saved yet is not an error. */
	public static ImageEdits open(Path romfs) throws IOException {
		ImageEdits e = new ImageEdits(Translations.folderFor(romfs).resolve("images"));
		if (Files.isRegularFile(e.file)) {
			try {
				JSONObject json = new JSONObject(Files.readString(e.file, StandardCharsets.UTF_8));
				for (String hash : json.keySet()) {
					JSONObject o = json.getJSONObject(hash);
					e.edits.put(hash, new Edit(o.getString("path"), o.getString("png")));
				}
			} catch (JSONException bad) {
				throw new IOException(e.file + " is not a valid list of image edits: " + bad.getMessage(), bad);
			}
		}
		return e;
	}

	/** The SHA-256 of an image file, as edits are keyed. */
	public static String hash(byte[] bclim) {
		return Bclim.hash(bclim);
	}

	public synchronized Set<String> edited() {
		return Set.copyOf(edits.keySet());
	}

	public synchronized Edit get(String hash) {
		return edits.get(hash);
	}

	/** The replacement PNG's file for {@code hash}, or null when it has none. */
	public synchronized Path png(String hash) {
		Edit e = edits.get(hash);
		return e == null ? null : folder.resolve(e.png());
	}

	/**
	 * Replaces the image whose original is {@code original} (at {@code path} in its archive) with
	 * {@code image}, which must be its size: written as a PNG and remembered.
	 */
	public synchronized void set(byte[] original, String path, BufferedImage image) throws IOException {
		Bclim.read(original).with(image); // the right size and encodable, or this throws
		String hash = hash(original);
		String name = path.substring(path.lastIndexOf('/') + 1).replace(".bclim", "") + "-" + hash.substring(0, 8) + ".png";
		Files.createDirectories(folder);
		ImageIO.write(image, "png", folder.resolve(name).toFile());
		edits.put(hash, new Edit(path, name));
		encoded.remove(hash);
		save();
	}

	/** Puts the image back as the game has it. */
	public synchronized void remove(String hash) throws IOException {
		Edit e = edits.remove(hash);
		encoded.remove(hash);
		if (e != null) {
			Files.deleteIfExists(folder.resolve(e.png()));
			save();
		}
	}

	/**
	 * The image file to write in place of {@code original}: its replacement encoded in its format,
	 * kept until the PNG changes; null when it has none or the PNG is gone or the wrong size.
	 */
	public byte[] replacement(byte[] original) {
		return replacement(hash(original), () -> original);
	}

	/**
	 * As {@link #replacement(byte[])} for the original with {@code hash}, which {@code original}
	 * reads only when the replacement must be encoded again.
	 */
	public synchronized byte[] replacement(String hash, java.util.function.Supplier<byte[]> original) {
		Path png = png(hash);
		if (png == null || !Files.isRegularFile(png)) {
			return null;
		}
		try {
			FileTime time = Files.getLastModifiedTime(png);
			Encoded known = encoded.get(hash);
			if (known == null || !known.time().equals(time)) {
				BufferedImage image = ImageIO.read(png.toFile());
				byte[] file = original.get();
				if (image == null || file == null) {
					return null;
				}
				known = new Encoded(time, Bclim.read(file).with(image));
				encoded.put(hash, known);
			}
			return known.bytes();
		} catch (IOException | RuntimeException unusable) {
			return null;
		}
	}

	private void save() throws IOException {
		if (edits.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		JSONObject json = new JSONObject();
		edits.forEach((hash, e) -> json.put(hash, new JSONObject().put("path", e.path()).put("png", e.png())));
		Files.createDirectories(folder);
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}
}
