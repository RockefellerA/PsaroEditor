package psaro.patch;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import psaro.format.Cia;
import psaro.format.Ncch;
import psaro.format.Ncsd;
import psaro.format.Source;

/**
 * Builds a patched copy of a title from a decrypted copy of it, the patch's files put in its romfs,
 * to install or play in place of the original: the other way to patch it than Luma3DS's game
 * patching, and for a DLC the only one, since Luma3DS patches no DLC and Citra lays one mods folder
 * over all of a DLC's contents alike. A CIA (from the eShop, or one GodMode9 builds) gives a CIA; a
 * .3ds (a game card's image, as GodMode9 dumps it) gives a .3ds. PsaroEditor never decrypts: the
 * copy must be decrypted already.
 *
 * <p>The files are given by content: {@code 0000} for a game (its content or partition 0), and for a
 * DLC the folder each content was extracted to, its index in hex ({@code 009a}) or its id
 * ({@code 0000009e}). Each content named is rebuilt with its files in place of its romfs's own
 * ({@link Ncch#rebuild}), and everything else is copied as it is; a game of a gigabyte is read a
 * piece at a time and never held whole. The result is unsigned, so it installs or runs where
 * signatures go unchecked: FBI on a 3DS with custom firmware, and Citra.
 */
public final class TitleBuild {

	/** What a title's file is. */
	public enum Format {
		CIA(".cia"), THREE_DS(".3ds");

		public final String extension;

		Format(String extension) {
			this.extension = extension;
		}
	}

	/** A title's file: what it is, the title's id, and its version where the file says (a CIA's TMD), else null. */
	public record Info(Format format, long titleId, Integer version) {

		/** The title id as sixteen hex digits. */
		public String id() {
			return String.format(Locale.ROOT, "%016X", titleId);
		}
	}

	/** What was built: the contents rebuilt, the files put in them, and the result's size. */
	public record Result(int contents, int files, long bytes, Format format) {
	}

	/** The content of a game: its first, the one with its romfs. */
	public static final String GAME = "0000";

	private TitleBuild() {
	}

	/** What {@code file} is: a CIA or a .3ds, told by what it holds rather than its name. */
	public static Info info(Path file) throws IOException {
		if (isNcsd(file)) {
			return new Info(Format.THREE_DS, Ncsd.read(file).titleId(), null);
		}
		Cia cia = Cia.read(file);
		return new Info(Format.CIA, cia.titleId(), cia.version());
	}

	private static boolean isNcsd(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return Ncsd.is(in.readNBytes(0x200));
		}
	}

	/**
	 * Writes {@code base} with {@code patched} (by content, each file by its path in the content's
	 * romfs) in it to {@code out}, beside its place first and then moved there; {@code progress}
	 * hears how far along it is, 0 to 1. Nothing is written when any content cannot take its files;
	 * every reason is given at once.
	 */
	public static Result build(Path base, Map<String, Map<String, byte[]>> patched, Path out, DoubleConsumer progress)
			throws IOException {
		if (patched.isEmpty()) {
			throw new IOException("There is nothing patched to build with yet: save some English first.");
		}
		boolean ncsd = isNcsd(base);
		Cia cia = ncsd ? null : Cia.read(base);
		Ncsd card = ncsd ? Ncsd.read(base) : null;
		String name = base.getFileName().toString();
		List<String> problems = new ArrayList<>();
		// each content to rebuild: where it is in the file, and its size
		Map<Integer, long[]> places = new LinkedHashMap<>();
		Map<Integer, Map<String, byte[]>> files = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, byte[]>> e : patched.entrySet()) {
			String folder = e.getKey();
			if (ncsd) {
				Ncsd.Partition p = folder.matches("(?i)[0-9a-f]{4}") ? card.partition(Integer.parseInt(folder, 16)) : null;
				if (p == null) {
					problems.add(folder + ": " + name + " has no partition " + folder + ".");
					continue;
				}
				places.put(p.index(), new long[] {p.offset(), p.size()});
				files.put(p.index(), e.getValue());
				continue;
			}
			Cia.Content c = contentFor(cia, folder);
			if (c == null) {
				problems.add(folder + ": " + name + " has no content of that index or id.");
				continue;
			}
			if (c.encrypted()) {
				problems.add(folder + ": content " + hex(c.index()) + " of " + name + " is encrypted. Start from a decrypted copy.");
				continue;
			}
			places.put(c.index(), new long[] {c.offset(), c.size()});
			files.put(c.index(), e.getValue());
		}
		long total = Files.size(base);
		for (long[] place : places.values()) {
			total += 2 * place[1];
		}
		long whole = total;
		long[] done = {0};
		java.util.function.LongConsumer read = n -> {
			done[0] += n;
			progress.accept(Math.min(1, (double) done[0] / whole));
		};

		Map<Integer, Ncch.Rebuilt> rebuilt = new LinkedHashMap<>();
		int count = 0;
		try (FileChannel ch = FileChannel.open(base, StandardOpenOption.READ)) {
			Source s = Source.of(ch);
			for (Map.Entry<Integer, long[]> e : places.entrySet()) {
				int index = e.getKey();
				String what = (ncsd ? "partition " : "content ") + hex(index);
				byte[] header = s.read(e.getValue()[0], 0x200);
				if (!Ncch.is(header)) {
					problems.add(what + " of " + name + " is not an NCCH.");
					continue;
				}
				if (!Ncch.decrypted(header)) {
					problems.add(what + " of " + name + " is encrypted. Start from a decrypted copy.");
					continue;
				}
				if (!problems.isEmpty()) {
					// no use rebuilding what will not be written; the rest are only checked
					continue;
				}
				try {
					rebuilt.put(index, Ncch.rebuild(s, e.getValue()[0], e.getValue()[1], files.get(index), read));
					count += files.get(index).size();
				} catch (IllegalArgumentException ex) {
					problems.add(what + ": " + ex.getMessage() + " (is this the title the patch was made for?)");
				}
			}
		}
		if (!problems.isEmpty()) {
			throw new IOException(String.join("\n", problems));
		}
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
			if (ncsd) {
				card.write(o, rebuilt, read);
			} else {
				cia.write(o, rebuilt, read);
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		try {
			Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		}
		progress.accept(1);
		return new Result(rebuilt.size(), count, Files.size(out), ncsd ? Format.THREE_DS : Format.CIA);
	}

	/**
	 * The content {@code folder} names: four hex digits for its index, eight for its id; null when
	 * it names none the CIA holds.
	 */
	static Cia.Content contentFor(Cia cia, String folder) {
		if (!folder.matches("(?i)[0-9a-f]{4}|[0-9a-f]{8}")) {
			return null;
		}
		int n = Integer.parseUnsignedInt(folder, 16);
		if (folder.length() == 4) {
			return cia.content(n);
		}
		return cia.contents().stream().filter(c -> c.id() == n).findFirst().orElse(null);
	}

	/** A game's patched files: everything in its patch folder ({@code <romfs>.psaro/romfs}), as content {@link #GAME}'s. */
	public static Map<String, Map<String, byte[]>> gameFiles(Path patchFolder) throws IOException {
		Map<String, byte[]> files = files(patchFolder);
		return files.isEmpty() ? Map.of() : Map.of(GAME, files);
	}

	/** A DLC's patched files: each content folder of its patch folder, its files. */
	public static Map<String, Map<String, byte[]>> dlcFiles(Path patchFolder) throws IOException {
		Map<String, Map<String, byte[]>> out = new TreeMap<>();
		if (!Files.isDirectory(patchFolder)) {
			return out;
		}
		try (Stream<Path> folders = Files.list(patchFolder)) {
			for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
				Map<String, byte[]> files = files(folder);
				if (!files.isEmpty()) {
					out.put(folder.getFileName().toString(), files);
				}
			}
		}
		return out;
	}

	/** Every file under {@code folder} by its path from it; leftovers of an interrupted write ({@code .tmp}) left out. */
	private static Map<String, byte[]> files(Path folder) throws IOException {
		Map<String, byte[]> files = new TreeMap<>();
		if (!Files.isDirectory(folder)) {
			return files;
		}
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path f : walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".tmp")).sorted()
					.toList()) {
				files.put(folder.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
			}
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		return files;
	}

	private static String hex(int index) {
		return String.format(Locale.ROOT, "%04x", index);
	}
}
