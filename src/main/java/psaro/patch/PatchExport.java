package psaro.patch;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the patch folder ({@code <romfs>.psaro/romfs}, the files the patch and the text export
 * write) into a zip to hand out: every file under {@code luma/titles/<title id>/romfs/}, where
 * Luma3DS's game patching looks for them on a 3DS's SD card, beside a readme on putting them
 * there or in an emulator's mods folder. Players bring their own copy of the game; the zip holds
 * only the changed files, so it suits any dump of it.
 */
public final class PatchExport {

	/** What was packed. */
	public record Result(int files, long bytes) {
	}

	private static final Pattern TITLE_ID = Pattern.compile("[0-9A-Fa-f]{16}");

	private PatchExport() {
	}

	/** Whether {@code id} is a title id: sixteen hex digits. */
	public static boolean isTitleId(String id) {
		return id != null && TITLE_ID.matcher(id).matches();
	}

	/**
	 * The title id a Citra mods folder is named for ({@code …/mods/0004000000140000/romfs}),
	 * upper-cased, or null when its path holds none.
	 */
	public static String titleIdFrom(Path modsFolder) {
		for (Path p = modsFolder; p != null; p = p.getParent()) {
			Path name = p.getFileName();
			if (name != null && isTitleId(name.toString())) {
				return name.toString().toUpperCase(Locale.ROOT);
			}
		}
		return null;
	}

	/**
	 * Writes the files of {@code patchFolder} to {@code zip} under the Luma layout for
	 * {@code titleId}, with a readme; leftovers of an interrupted write ({@code .tmp}) are left
	 * out. The zip is written beside its place, then moved there. Reports each file to
	 * {@code progress}.
	 */
	public static Result write(Path patchFolder, String titleId, Path zip, Consumer<String> progress) throws IOException {
		if (!isTitleId(titleId)) {
			throw new IllegalArgumentException(titleId + " is not a title id");
		}
		String id = titleId.toUpperCase(Locale.ROOT);
		List<Path> files;
		try (Stream<Path> walk = Files.walk(patchFolder)) {
			files = walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".tmp")).sorted()
					.toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		if (files.isEmpty()) {
			throw new IOException("The patch folder " + patchFolder + " has nothing in it yet: patch first.");
		}
		Path tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
		long bytes = 0;
		try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out)) {
			z.putNextEntry(new ZipEntry("README.txt"));
			z.write(readme(id).getBytes(StandardCharsets.UTF_8));
			z.closeEntry();
			for (Path f : files) {
				String rel = patchFolder.relativize(f).toString().replace('\\', '/');
				progress.accept("Packing " + rel);
				z.putNextEntry(new ZipEntry("luma/titles/" + id + "/romfs/" + rel));
				bytes += Files.copy(f, z);
				z.closeEntry();
			}
		}
		try {
			Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
		}
		return new Result(files.size(), bytes);
	}

	/** How to put the patch in place, on a 3DS and in an emulator. */
	static String readme(String titleId) {
		return String.join("\r\n",
				"This patch replaces some of the game's files; it holds none of the game itself.",
				"Use it with your own copy of the game (title id " + titleId + ").",
				"",
				"On a 3DS with Luma3DS",
				"  1. Copy the luma folder in this zip to the root of the SD card, merging it with the",
				"     luma folder already there, so the files end up in",
				"     /luma/titles/" + titleId + "/romfs/",
				"  2. Hold SELECT while powering on to open the Luma3DS configuration, and turn on",
				"     \"Enable game patching\". Save and boot.",
				"",
				"In Citra (or a fork of it)",
				"  1. Right-click the game in the list and choose Open Mods Location.",
				"  2. Copy the romfs folder from luma/titles/" + titleId + "/ in this zip into that folder,",
				"     so it holds romfs/ beside any other mods.",
				"",
				"To remove the patch, delete the romfs folder you copied.",
				"");
	}
}
