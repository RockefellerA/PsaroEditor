package psaro.romfs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import psaro.format.Cia;
import psaro.format.Ncch;
import psaro.format.Ncsd;
import psaro.format.RomfsImage;
import psaro.format.Source;

/**
 * Extracts the romfs of a decrypted title's file into folders beside it, ready to open: the other
 * way round from {@link psaro.patch.TitleBuild}, and its folders named as that one takes them back.
 * A .3ds, a CIA or a lone NCCH (.cxi) is told by what it holds rather than its name. PsaroEditor
 * never decrypts: a file still encrypted is refused.
 *
 * <p>Beside {@code <name>.3ds} (or .cia, .cxi; a "decrypted" at the end of the name left out):
 * <pre>
 * &lt;name&gt;/                 the game's romfs (partition or content 0), opened as the game
 * &lt;name&gt; manual/          its manual's (1)
 * &lt;name&gt; download play/   its download-play child's (2), when it has one
 * </pre>
 * A card's system update (partition 7) is left out. A DLC's CIA gives {@code <name>/} holding a
 * folder for each content, its index in hex ({@code 0000}, {@code 0001}…), as Open romfs → DLC
 * takes them.
 */
public final class TitleExtract {

	/** What a title's file holds: a game (or its update), or a DLC. */
	public enum Kind {
		GAME, DLC
	}

	/** One romfs to extract: what it is, for messages; where its image is in the file; the folder it goes to. */
	public record Piece(String what, long at, Path folder) {
	}

	/**
	 * What extracting a file writes: its kind and title id, the folder it is opened as (the game's
	 * romfs, or the DLC's folder of content folders), and each romfs.
	 */
	public record Plan(Path file, Kind kind, long titleId, Path opens, List<Piece> pieces) {

		/** The title id as sixteen hex digits. */
		public String id() {
			return String.format(Locale.ROOT, "%016X", titleId);
		}

		/** The folders it writes into that hold something already. */
		public List<Path> occupied() throws IOException {
			List<Path> out = new ArrayList<>();
			for (Piece p : pieces) {
				if (Files.isDirectory(p.folder())) {
					try (var list = Files.list(p.folder())) {
						if (list.findAny().isPresent()) {
							out.add(p.folder());
						}
					}
				} else if (Files.exists(p.folder())) {
					out.add(p.folder());
				}
			}
			return out;
		}
	}

	/** What was extracted: the files written and their size together. */
	public record Result(int files, long bytes) {
	}

	/** A DLC's title id's high word. */
	private static final long DLC = 0x0004008CL;
	/** The partitions or contents of a game extracted, each with what its folder is named after the title's. */
	private static final String[] GAME_PARTS = {"", " manual", " download play"};
	private static final String[] GAME_WHAT = {"the game", "the manual", "the download-play child"};

	private TitleExtract() {
	}

	/**
	 * What extracting {@code file} would write, its headers read and nothing written. Every reason
	 * it cannot be extracted is given at once.
	 */
	public static Plan plan(Path file) throws IOException {
		byte[] start;
		try (InputStream in = Files.newInputStream(file)) {
			start = in.readNBytes(0x200);
		}
		String name = file.getFileName().toString();
		Path parent = file.toAbsolutePath().getParent();
		String stem = stem(name);
		List<Piece> pieces = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		long titleId;
		Kind kind = Kind.GAME;
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			Source s = Source.of(ch);
			if (Ncsd.is(start)) {
				Ncsd card = Ncsd.read(file);
				titleId = card.titleId();
				for (int i = 0; i < GAME_PARTS.length; i++) {
					Ncsd.Partition p = card.partition(i);
					if (p != null) {
						add(s, p.offset(), "partition " + hex(i) + " (" + GAME_WHAT[i] + ")", parent.resolve(stem + GAME_PARTS[i]),
								i == 0, pieces, problems);
					}
				}
			} else if (Ncch.is(start)) {
				titleId = Ncch.programId(start);
				add(s, 0, name, parent.resolve(stem), true, pieces, problems);
			} else {
				Cia cia;
				try {
					cia = Cia.read(file);
				} catch (IOException | RuntimeException e) {
					throw new IOException(name + " is not a .3ds, a CIA or an NCCH (.cxi).");
				}
				titleId = cia.titleId();
				kind = titleId >>> 32 == DLC ? Kind.DLC : Kind.GAME;
				for (Cia.Content c : cia.contents()) {
					if (kind == Kind.GAME && c.index() >= GAME_PARTS.length) {
						continue;
					}
					String what = "content " + hex(c.index()) + (kind == Kind.GAME ? " (" + GAME_WHAT[c.index()] + ")" : "");
					if (c.encrypted()) {
						problems.add(what + " is encrypted. Start from a decrypted copy.");
						continue;
					}
					Path folder = kind == Kind.DLC ? parent.resolve(stem).resolve(hex(c.index()))
							: parent.resolve(stem + GAME_PARTS[c.index()]);
					add(s, c.offset(), what, folder, kind == Kind.GAME && c.index() == 0, pieces, problems);
				}
			}
		}
		if (pieces.isEmpty() && problems.isEmpty()) {
			problems.add(name + " holds no romfs.");
		}
		if (!problems.isEmpty()) {
			throw new IOException(name + ":\n" + String.join("\n", problems));
		}
		Path opens = kind == Kind.DLC ? parent.resolve(stem) : pieces.get(0).folder();
		return new Plan(file, kind, titleId, opens, List.copyOf(pieces));
	}

	/**
	 * Adds the romfs of the NCCH at {@code at} to {@code pieces}, or why it cannot be read to
	 * {@code problems}; one with no romfs is a problem only when {@code needed}.
	 */
	private static void add(Source s, long at, String what, Path folder, boolean needed, List<Piece> pieces,
			List<String> problems) throws IOException {
		byte[] header = s.read(at, 0x200);
		if (!Ncch.is(header)) {
			problems.add(what + " is not an NCCH.");
			return;
		}
		if (!Ncch.decrypted(header)) {
			problems.add(what + " is encrypted. Start from a decrypted copy.");
			return;
		}
		long[] romfs = Ncch.romfsPlace(header);
		if (romfs[1] == 0) {
			if (needed) {
				problems.add(what + " has no romfs.");
			}
			return;
		}
		pieces.add(new Piece(what, at + romfs[0], folder));
	}

	/**
	 * Writes every file of {@code plan}'s romfs into its folder, replacing any of the same name
	 * there and leaving everything else; {@code progress} hears how far along it is, 0 to 1. Each
	 * romfs is listed before anything is written, so one that cannot be read stops it first.
	 */
	public static Result extract(Plan plan, DoubleConsumer progress) throws IOException {
		try (FileChannel ch = FileChannel.open(plan.file(), StandardOpenOption.READ)) {
			Source s = Source.of(ch);
			List<List<RomfsImage.Stored>> lists = new ArrayList<>();
			long total = 0;
			for (Piece p : plan.pieces()) {
				List<RomfsImage.Stored> files;
				try {
					files = RomfsImage.list(s, p.at());
				} catch (IllegalArgumentException e) {
					throw new IOException(p.what() + ": " + e.getMessage());
				}
				for (RomfsImage.Stored f : files) {
					target(p.folder(), f.path());
					if (f.offset() < 0 || f.offset() + f.size() > ch.size()) {
						throw new IOException(p.what() + ": " + f.path() + " runs past the end of " + plan.file().getFileName());
					}
					total += f.size();
				}
				lists.add(files);
			}
			long whole = Math.max(1, total);
			long done = 0;
			int count = 0;
			for (int i = 0; i < lists.size(); i++) {
				Path folder = plan.pieces().get(i).folder();
				Files.createDirectories(folder);
				for (RomfsImage.Stored f : lists.get(i)) {
					Path out = target(folder, f.path());
					Files.createDirectories(out.getParent());
					try (FileChannel o = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
							StandardOpenOption.TRUNCATE_EXISTING)) {
						long copied = 0;
						while (copied < f.size()) {
							long n = ch.transferTo(f.offset() + copied, f.size() - copied, o);
							if (n <= 0) {
								throw new IOException(plan.file().getFileName() + " ended while " + f.path() + " was copied");
							}
							copied += n;
							progress.accept((double) (done + copied) / whole);
						}
					}
					done += f.size();
					count++;
				}
			}
			progress.accept(1);
			return new Result(count, total);
		}
	}

	/**
	 * Where the romfs file {@code path} goes in {@code folder}, refusing a name that would land
	 * outside it or that Windows cannot hold.
	 */
	private static Path target(Path folder, String path) throws IOException {
		for (String part : path.split("/", -1)) {
			if (part.isEmpty() || part.equals(".") || part.equals("..") || part.matches(".*[\\\\:*?\"<>|\\x00-\\x1F].*")) {
				throw new IOException("The romfs has a file named " + path + ", which cannot be written safely.");
			}
		}
		Path out = folder.resolve(path).normalize();
		if (!out.startsWith(folder.normalize())) {
			throw new IOException("The romfs has a file named " + path + ", which cannot be written safely.");
		}
		return out;
	}

	/** {@code name} without its extension and a "decrypted" at its end, as the folders are named. */
	static String stem(String name) {
		String stem = name.replaceFirst("(?i)\\.(3ds|cci|cia|cxi|app)$", "")
				.replaceFirst("(?i)[\\s._-]*[(\\[]?decrypted[)\\]]?$", "").strip();
		return stem.isEmpty() ? "romfs" : stem;
	}

	private static String hex(int index) {
		return String.format(Locale.ROOT, "%04x", index);
	}
}
