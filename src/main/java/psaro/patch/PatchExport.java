package psaro.patch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *
 * <p>A project may write its own readme and add files of its own anywhere in the zip
 * ({@link ExportSettings}), such as a code patch Luma3DS reads beside the romfs folder. In both,
 * {@link #TITLE_ID} stands for the title id. Every readme ends with {@link #FOOTER}, which a
 * project's text does not hold.
 */
public final class PatchExport {

	/** What was packed: the patch folder's files, the bytes of everything packed, and the files added besides. */
	public record Result(int files, long bytes, int extras) {
	}

	/** A file added to the zip: where it is read from, and its path in the zip, which may hold {@link #TITLE_ID}. */
	public record Extra(Path source, String destination) {
	}

	/** Told what is being packed and how much of the zip is written, 0 to 1 by bytes. */
	@FunctionalInterface
	public interface Progress {
		void at(String step, double share);
	}

	/** Stands for the title id in a readme or an added file's path in the zip. */
	public static final String TITLE_ID = "{titleId}";
	private static final String README = "README.txt";
	private static final Pattern TITLE_ID_PATTERN = Pattern.compile("[0-9A-Fa-f]{16}");

	private PatchExport() {
	}

	/** Whether {@code id} is a title id: sixteen hex digits. */
	public static boolean isTitleId(String id) {
		return id != null && TITLE_ID_PATTERN.matcher(id).matches();
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
	 * {@code titleId}, with the default readme; leftovers of an interrupted write ({@code .tmp})
	 * are left out. The zip is written beside its place, then moved there. Reports each file, and
	 * how far along it is, to {@code progress}.
	 */
	public static Result write(Path patchFolder, String titleId, Path zip, Progress progress) throws IOException {
		return write(patchFolder, titleId, defaultReadme(), List.of(), zip, progress);
	}

	/**
	 * As {@link #write(Path, String, Path, Progress)}, with {@code readme} (its {@link #TITLE_ID}
	 * filled in, {@link #FOOTER} after it) and {@code extras} added where they say. Nothing is
	 * written when any of {@link #problems} stands in the way.
	 */
	public static Result write(Path patchFolder, String titleId, String readme, List<Extra> extras, Path zip,
			Progress progress) throws IOException {
		if (!isTitleId(titleId)) {
			throw new IllegalArgumentException(titleId + " is not a title id");
		}
		String id = titleId.toUpperCase(Locale.ROOT);
		List<Path> files = patchFiles(patchFolder);
		if (files.isEmpty()) {
			throw new IOException("The patch folder " + patchFolder + " has nothing in it yet: patch first.");
		}
		List<String> problems = problems(patchFolder, id, extras);
		if (!problems.isEmpty()) {
			throw new IOException(String.join("\n", problems));
		}
		Path tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
		long[] done = {0};
		long whole = size(files) + size(extras.stream().map(Extra::source).toList());
		try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out)) {
			z.putNextEntry(new ZipEntry(README));
			z.write(fill(withFooter(readme), id).getBytes(StandardCharsets.UTF_8));
			z.closeEntry();
			for (Path f : files) {
				String rel = relative(patchFolder, f);
				z.putNextEntry(new ZipEntry(romfsEntry(id, rel)));
				copy(f, z, "Packing " + rel, done, whole, progress);
				z.closeEntry();
			}
			for (Extra e : extras) {
				String name = destination(e.destination(), id);
				z.putNextEntry(new ZipEntry(name));
				copy(e.source(), z, "Adding " + name, done, whole, progress);
				z.closeEntry();
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		try {
			Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
		}
		return new Result(files.size(), done[0], extras.size());
	}

	/**
	 * Writes a zip of {@code readme} ({@link #TITLE_ID} filled in, {@link #FOOTER} after it) and
	 * {@code files} at their paths in it, and nothing of the patch folder: the patcher download,
	 * which players take instead of the Luma3DS one. Written beside its place, then moved there;
	 * how far along it is goes to {@code progress}.
	 */
	public static Result writeFiles(String titleId, String readme, List<Extra> files, Path zip, Progress progress)
			throws IOException {
		List<String> problems = problems(null, titleId, files);
		if (!problems.isEmpty()) {
			throw new IOException(String.join("\n", problems));
		}
		String id = titleId.toUpperCase(Locale.ROOT);
		Path tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
		long[] done = {0};
		long whole = size(files.stream().map(Extra::source).toList());
		try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out)) {
			z.putNextEntry(new ZipEntry(README));
			z.write(fill(withFooter(readme), id).getBytes(StandardCharsets.UTF_8));
			z.closeEntry();
			for (Extra e : files) {
				String name = destination(e.destination(), id);
				z.putNextEntry(new ZipEntry(name));
				copy(e.source(), z, "Adding " + name, done, whole, progress);
				z.closeEntry();
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		try {
			Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
		}
		return new Result(0, done[0], files.size());
	}

	/**
	 * What stands in the way of adding {@code extras} to a zip for {@code titleId}, one line each:
	 * a title id that is none, a file that is not there, or a path in the zip that is not plain
	 * (empty, absolute, climbing out with {@code ..}), is the readme's, is another's, or is one of
	 * {@code patchFolder}'s files' (when it is given). None when the export can go ahead.
	 */
	public static List<String> problems(Path patchFolder, String titleId, List<Extra> extras) throws IOException {
		List<String> out = new ArrayList<>();
		if (!isTitleId(titleId)) {
			out.add((titleId == null || titleId.isBlank() ? "No title id" : titleId + " is not a title id")
					+ ": it has sixteen hex digits, 0-9 and A-F.");
			return out;
		}
		String id = titleId.toUpperCase(Locale.ROOT);
		Set<String> taken = new HashSet<>();
		taken.add(README.toLowerCase(Locale.ROOT));
		if (patchFolder != null && Files.isDirectory(patchFolder)) {
			for (Path f : patchFiles(patchFolder)) {
				taken.add(romfsEntry(id, relative(patchFolder, f)).toLowerCase(Locale.ROOT));
			}
		}
		for (Extra e : extras) {
			String name = destination(e.destination(), id);
			if (!Files.isRegularFile(e.source())) {
				out.add(e.source() + " is not there any more.");
			}
			if (!plain(name)) {
				out.add("\"" + e.destination() + "\" is not a file's path inside the zip.");
			} else if (!taken.add(name.toLowerCase(Locale.ROOT))) {
				out.add(name + " is in the zip already (the readme, a patched file or another added file).");
			}
		}
		return out;
	}

	/** {@code destination} with the title id filled in, its separators forward slashes. */
	public static String destination(String destination, String titleId) {
		return destination.replace(TITLE_ID, titleId.toUpperCase(Locale.ROOT)).replace('\\', '/').strip();
	}

	/** Where a file added to the zip goes unless it is told otherwise: beside the romfs folder, where Luma3DS reads code patches. */
	public static String defaultDestination(Path source) {
		return "luma/titles/" + TITLE_ID + "/" + source.getFileName();
	}

	/** {@code readme} with {@link #TITLE_ID} filled in and its lines ending CRLF, as Windows' Notepad shows them. */
	public static String fill(String readme, String titleId) {
		return readme.replace(TITLE_ID, titleId.toUpperCase(Locale.ROOT)).replace("\r\n", "\n").replace("\n", "\r\n");
	}

	/** Where every readme ends, whatever a project writes above it: credit to the editor. */
	public static final String FOOTER = "This patch was made with PsaroEditor: https://github.com/RockefellerA/PsaroEditor";

	/** {@code readme} ending with {@link #FOOTER} after a blank line, the footer it may already end with not repeated. */
	public static String withFooter(String readme) {
		String body = withoutFooter(readme).stripTrailing();
		return (body.isEmpty() ? "" : body + "\n\n") + FOOTER + "\n";
	}

	/** {@code readme} without the {@link #FOOTER} it may end with, or the blank lines after it, its lines ending LF. */
	public static String withoutFooter(String readme) {
		String body = readme.replace("\r\n", "\n").stripTrailing();
		if (body.endsWith(FOOTER)) {
			body = body.substring(0, body.length() - FOOTER.length()).stripTrailing();
		}
		return body.isEmpty() ? "" : body + "\n";
	}

	/** The readme a project has until it writes its own, {@link #TITLE_ID} standing for the title id; {@link #FOOTER} follows it. */
	public static String defaultReadme() {
		String id = TITLE_ID;
		return String.join("\n",
				"This patch replaces some of the game's files; it holds none of the game itself.",
				"Use it with your own copy of the game (title id " + id + ").",
				"",
				"On a 3DS with Luma3DS",
				"  1. Copy the luma folder in this zip to the root of the SD card, merging it with the",
				"     luma folder already there, so the files end up in",
				"     /luma/titles/" + id + "/romfs/",
				"  2. Hold SELECT while powering on to open the Luma3DS configuration, and turn on",
				"     \"Enable game patching\". Save and boot.",
				"",
				"In Citra (or a fork of it)",
				"  1. Right-click the game in the list and choose Open Mods Location.",
				"  2. Copy the romfs folder from luma/titles/" + id + "/ in this zip into that folder,",
				"     so it holds romfs/ beside any other mods.",
				"",
				"To remove the patch, delete the romfs folder you copied.",
				"");
	}

	/** How to put the patch in place, on a 3DS and in an emulator: the default readme for {@code titleId}, with the footer. */
	static String readme(String titleId) {
		return fill(withFooter(defaultReadme()), titleId);
	}

	/** Whether {@code name} is a file's path inside the zip: not empty, not absolute, no step out of it. */
	private static boolean plain(String name) {
		if (name.isEmpty() || name.startsWith("/") || name.endsWith("/") || name.matches("^[A-Za-z]:.*")) {
			return false;
		}
		for (String part : name.split("/")) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				return false;
			}
		}
		return true;
	}

	/** The bytes of {@code files} together. */
	private static long size(List<Path> files) throws IOException {
		long n = 0;
		for (Path f : files) {
			n += Files.size(f);
		}
		return n;
	}

	/**
	 * Copies {@code from} into {@code to}, adding its bytes to {@code done} and telling
	 * {@code progress} the share of {@code whole} written as it goes, so a large file moves the bar too.
	 */
	private static void copy(Path from, OutputStream to, String step, long[] done, long whole, Progress progress)
			throws IOException {
		progress.at(step, whole == 0 ? 0 : (double) done[0] / whole);
		byte[] buf = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(from)) {
			for (int n; (n = in.read(buf)) > 0;) {
				to.write(buf, 0, n);
				done[0] += n;
				progress.at(step, whole == 0 ? 1 : Math.min(1, (double) done[0] / whole));
			}
		}
	}

	private static String romfsEntry(String titleId, String rel) {
		return "luma/titles/" + titleId + "/romfs/" + rel;
	}

	private static String relative(Path folder, Path file) {
		return folder.relativize(file).toString().replace('\\', '/');
	}

	/** The patch folder's files, but an interrupted write's leftovers ({@code .tmp}), in order. */
	private static List<Path> patchFiles(Path patchFolder) throws IOException {
		try (Stream<Path> walk = Files.walk(patchFolder)) {
			return walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".tmp")).sorted()
					.toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}
}
