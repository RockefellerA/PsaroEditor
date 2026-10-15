package psaro.patch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Font files for tests to import: the bundled typeface's, renamed to another family of the same
 * length ({@code Rounded Mplus 9z}), so they read as a typeface of their own and draw as the
 * bundled one does, without another font in the repository.
 */
public final class TestFonts {

	/** The family the renamed files give. */
	public static final String FAMILY = "Rounded Mplus 9z";

	private TestFonts() {
	}

	/** {@code MPLUSRounded1c-<weight>.ttf}, renamed, written to {@code dir} as {@code Renamed-<weight>.ttf}. */
	public static Path renamed(Path dir, String weight) throws IOException {
		byte[] d;
		try (InputStream in = TestFonts.class.getResourceAsStream("/fonts/MPLUSRounded1c-" + weight + ".ttf")) {
			d = in.readAllBytes();
		}
		replace(d, "Rounded Mplus 1c".getBytes(StandardCharsets.UTF_16BE), FAMILY.getBytes(StandardCharsets.UTF_16BE));
		replace(d, "Rounded Mplus 1c".getBytes(StandardCharsets.ISO_8859_1), FAMILY.getBytes(StandardCharsets.ISO_8859_1));
		replace(d, "RoundedMplus1c".getBytes(StandardCharsets.UTF_16BE), "RoundedMplus9z".getBytes(StandardCharsets.UTF_16BE));
		replace(d, "RoundedMplus1c".getBytes(StandardCharsets.ISO_8859_1), "RoundedMplus9z".getBytes(StandardCharsets.ISO_8859_1));
		Files.createDirectories(dir);
		Path out = dir.resolve("Renamed-" + weight + ".ttf");
		Files.write(out, d);
		return out;
	}

	/** Puts {@code to} over every {@code from} in {@code d}; the two are as long. */
	private static void replace(byte[] d, byte[] from, byte[] to) {
		for (int i = 0; i + from.length <= d.length; i++) {
			if (java.util.Arrays.equals(d, i, i + from.length, from, 0, from.length)) {
				System.arraycopy(to, 0, d, i, to.length);
			}
		}
	}
}
