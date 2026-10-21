package psaro.romfs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import psaro.format.Darc;
import psaro.format.Lz11;

/**
 * What the files of an extracted romfs are, told by what they hold rather than their names: each
 * file's format, whether PsaroEditor reads that format, and which files are LZ11-compressed. The
 * files inside each DARC archive are told too. A file whose start matches no format known here is
 * grouped by its first four bytes when they are letters (a new format's signature), else by its
 * extension, so a new game's unread formats stand out.
 */
public final class FileSurvey {

	/** A format: its name, a line on what it is, and whether PsaroEditor reads it. */
	public record Format(String name, String about, boolean read) {
	}

	/**
	 * One file: its path from the folder surveyed (inside an archive, the archive's path, then
	 * {@code /}, then its own), its size as stored, its format, and whether it is LZ11-compressed
	 * or inside an archive.
	 */
	public record Item(String path, long size, Format format, boolean compressed, boolean inArchive) {
	}

	/** The files of one format, with how many and how large they are together. */
	public record Group(Format format, List<Item> items) {

		public int loose() {
			return (int) items.stream().filter(i -> !i.inArchive()).count();
		}

		public int inArchives() {
			return items.size() - loose();
		}

		public long bytes() {
			return items.stream().mapToLong(Item::size).sum();
		}

		public int compressed() {
			return (int) items.stream().filter(Item::compressed).count();
		}
	}

	/** A format known by bytes at the file's start. */
	private record Signature(byte[] bytes, Format format) {
	}

	private static final int HEAD = 0x40;
	/** A BCLIM's footer, the last 0x28 bytes: "CLIM" there and "imag" 0x14 on. */
	private static final int FOOTER = 0x28;
	/** How much of an LZ11 file is read to tell what it holds; enough for every signature here. */
	private static final int LZ_HEAD = 0x1000;
	/** The largest compressed file unpacked whole to read its footer or list its archive. */
	private static final long UNPACK_LIMIT = 64 << 20;

	public static final Format DARC = new Format("DARC archive", "Nintendo's archive of layouts, images and fonts; read as *.arc or *.arc.lz", true);
	public static final Format BCLYT = new Format("Layout (BCLYT)", "A screen's panes and text boxes; read inside archives", true);
	public static final Format BCLIM = new Format("Image (BCLIM)", "A layout's texture; read inside archives and as *.bclim(.lz)", true);
	public static final Format BCFNT = new Format("Font (BCFNT)", "A font's glyphs; read and patched", true);
	public static final Format CGFX = new Format("Model (CGFX)", "A 3D model; its textures read as *.bcmdl(.lz)", true);
	public static final Format TDT = new Format("String table (TDT)", "Keyed UTF-16 strings; read in text/", true);
	public static final Format MSGD = new Format("Messages (MSGD)", "Numbered strings; read as message/*_jp.mdt", true);
	public static final Format CSV = new Format("Data table (CSV)", "UTF-16 rows of game data; read in table/", true);
	public static final Format MBB = new Format("Message tables (TBB1)", "Tables of strings; read as *.mbb", true);
	public static final Format RECORDS = new Format("Record file (DARC)", "Fixed-size records with text fields; read as *.dat", true);
	public static final Format CPK = new Format("CRI archive (CPK)", "CRI's archive; its files are read, but not listed here", true);
	public static final Format COMP = new Format("Compressed bundle (COMP)", "An LZ11 bundle of files; read as *.cmp", true);
	public static final Format NBF = new Format("Bundle (2NBF)", "A bundle of files; read inside *.cmp", true);
	public static final Format THSS = new Format("Text records (THSS)", "Records and strings; read inside CPKs, with their FHSS", true);
	public static final Format FHSS = new Format("Text pointers (FHSS)", "Where a THSS's strings are; read inside CPKs", true);

	private static final List<Signature> SIGNATURES = List.of(
			sig("darc", DARC), sig("CLYT", BCLYT), sig("CFNT", BCFNT), sig("CGFX", CGFX), sig("MSGD", MSGD), sig("TBB1", MBB),
			sig("DARC", RECORDS), sig("CPK ", CPK), sig("COMP", COMP), sig("2NBF", NBF), sig("THSS", THSS), sig("FHSS", FHSS),
			sig("CLAN", "Layout animation (BCLAN)", "A layout's animation"),
			sig("CFNU", "Font (BCFNU)", "A system font, uncompressed"),
			sig("FFNT", "Font (BFFNT)", "A newer font format"),
			sig("CSTM", "Streamed audio (BCSTM)", "Music or long sound"),
			sig("CWAV", "Sound (BCWAV)", "A short sound"),
			sig("CSAR", "Sound archive (BCSAR)", "Sound effects, sequences and banks"),
			sig("CWAR", "Wave archive (BCWAR)", "Sounds for a sound bank"),
			sig("CWSD", "Wave sound data (BCWSD)", "Sound effect settings"),
			sig("CSEQ", "Sequence (BCSEQ)", "Sequenced music"),
			sig("CBNK", "Sound bank (BCBNK)", "Instruments for sequenced music"),
			sig("CGRP", "Sound group (BCGRP)", "A group of sound files"),
			sig("CTPK", "Texture package (CTPK)", "Textures, often for menus or effects"),
			sig("BCH\0", "Model (BCH)", "A 3D model or textures, H3D"),
			sig("SARC", "SARC archive", "Nintendo's other archive"),
			sig("Yaz0", "Yaz0-compressed", "Compressed with Yaz0"),
			sig("CRAG", "GARC archive", "An archive of numbered files"),
			sig("CBMD", "Banner (CBMD)", "The HOME menu banner"),
			sig("SMDH", "Icon and titles (SMDH)", "A HOME menu icon and its titles"),
			sig("DVLB", "Shader binary (DVLB)", "GPU shader program"),
			sig("DVOJ", "Shader object (DVOJ)", "GPU shader object"),
			sig("MsgStdBn", "Message text (MSBT)", "Nintendo's message format, keyed strings"),
			sig("MsgPrjBn", "Message project (MSBP)", "Colours, tags and styles MSBT files use"),
			sig("@UTF", "CRI table (ACB/ACF)", "CRI's table format, often an audio cue sheet"),
			sig("AFS2", "CRI wave bank (AWB)", "CRI's audio"),
			sig("CRID", "CRI video (USM)", "CRI's video"),
			sig("CRILAYLA", "CRILAYLA-compressed", "Compressed with CRI's LAYLA"),
			sig(new byte[] {'L', '2', (byte) 0xAA, (byte) 0xAB}, "Video (Moflex)", "Mobiclip video"),
			sig(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "PNG image", "An ordinary image"),
			sig(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "JPEG image", "An ordinary image"),
			sig("RIFF", "RIFF (WAV)", "An ordinary audio file"),
			sig("OggS", "Ogg audio", "An ordinary audio file"),
			sig("PK\3\4", "Zip archive", "An ordinary zip"),
			sig(new byte[] {0, 1, 0, 0}, "Font (TrueType)", "An ordinary font"),
			sig("OTTO", "Font (OpenType)", "An ordinary font"));

	private static final Format XML = new Format("XML", "Text markup, often a tool's settings", false);
	private static final Format UTF8 = new Format("UTF-8 text", "Plain text", false);
	private static final Format UTF16 = new Format("UTF-16 text", "Plain text", false);
	private static final Format FLIM = new Format("Image (BFLIM)", "A newer layout texture", false);

	private FileSurvey() {
	}

	private static Signature sig(String ascii, Format format) {
		return new Signature(ascii.getBytes(StandardCharsets.ISO_8859_1), format);
	}

	private static Signature sig(String ascii, String name, String about) {
		return sig(ascii, new Format(name, about, false));
	}

	private static Signature sig(byte[] bytes, String name, String about) {
		return new Signature(bytes, new Format(name, about, false));
	}

	/**
	 * Every file under {@code root} and in its DARC archives, each told; a {@code .psaro} project
	 * folder inside it is left out. {@code progress} hears how far along it is, 0 to 1.
	 */
	public static List<Item> scan(Path root, DoubleConsumer progress) throws IOException {
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root)) {
			files = walk.filter(Files::isRegularFile).filter(p -> !inProject(root, p)).sorted().toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		List<Item> out = new ArrayList<>();
		for (int i = 0; i < files.size(); i++) {
			Path file = files.get(i);
			survey(root.relativize(file).toString().replace('\\', '/'), file, out);
			progress.accept((double) (i + 1) / files.size());
		}
		return out;
	}

	private static boolean inProject(Path root, Path file) {
		for (Path part : root.relativize(file)) {
			if (part.toString().endsWith(".psaro")) {
				return true;
			}
		}
		return false;
	}

	/** Tells {@code file}, read a little at a time, adding it (and what its archive holds) to {@code out}. */
	private static void survey(String path, Path file, List<Item> out) throws IOException {
		long size = Files.size(file);
		byte[] head;
		byte[] tail;
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			head = read(ch, 0, (int) Math.min(size, LZ_HEAD));
			tail = read(ch, Math.max(0, size - FOOTER), (int) Math.min(size, FOOTER));
		}
		String name = file.getFileName().toString();
		// the end of an LZ11 stream can hold a footer's bytes as they were, so a footer is looked for only once unpacked
		Format f = identify(head, new byte[0], name);
		if (f == null && compressed(head)) {
			int unpacked = unpackedSize(head);
			byte[] inner = Lz11.decompress(head, HEAD);
			f = identify(inner, new byte[0], name);
			byte[] whole = null;
			if ((f == null || f == DARC) && size <= UNPACK_LIMIT && unpacked <= UNPACK_LIMIT) {
				whole = unpack(Files.readAllBytes(file));
				if (f == null && whole != null) {
					f = identify(whole, tailOf(whole), name);
				}
			}
			out.add(new Item(path, size, f != null ? f : unknown(inner, extension(name, true)), true, false));
			if (f == DARC && whole != null) {
				listArchive(path, whole, out);
			}
			return;
		}
		if (f == null) {
			f = identify(head, tail, name);
		}
		out.add(new Item(path, size, f != null ? f : unknown(head, extension(name, false)), false, false));
		if (f == DARC && size <= UNPACK_LIMIT) {
			listArchive(path, Files.readAllBytes(file), out);
		}
	}

	/** Adds each file of the DARC archive {@code d} to {@code out}, its path under {@code archive}'s; nested archives too. */
	private static void listArchive(String archive, byte[] d, List<Item> out) {
		Map<String, Darc.Node> files;
		try {
			files = Darc.files(Darc.read(d));
		} catch (RuntimeException unreadable) {
			return;
		}
		for (Map.Entry<String, Darc.Node> e : files.entrySet()) {
			byte[] data = e.getValue().data;
			String path = archive + "/" + e.getKey().replaceFirst("^/+", "");
			String name = path.substring(path.lastIndexOf('/') + 1);
			Format f = identify(data, new byte[0], name);
			boolean packed = false;
			byte[] inner = data;
			if (f == null && compressed(data)) {
				byte[] whole = unpack(data);
				if (whole != null) {
					packed = true;
					inner = whole;
					f = identify(whole, tailOf(whole), name);
				}
			}
			if (f == null && !packed) {
				f = identify(data, tailOf(data), name);
			}
			out.add(new Item(path, data.length, f != null ? f : unknown(inner, extension(name, packed)), packed, true));
			if (f == DARC) {
				listArchive(path, inner, out);
			}
		}
	}

	/**
	 * The format {@code head} (a file's start) and {@code tail} (its last bytes, or none) show, or
	 * null when they show none known here. {@code name} tells a CSV from other UTF-16 text.
	 */
	static Format identify(byte[] head, byte[] tail, String name) {
		for (Signature s : SIGNATURES) {
			if (starts(head, 0, s.bytes())) {
				return s.format();
			}
		}
		if (tdt(head)) {
			return TDT;
		}
		if (tail.length >= FOOTER) {
			int at = tail.length - FOOTER;
			if (starts(tail, at, "CLIM".getBytes(StandardCharsets.US_ASCII))
					&& starts(tail, at + 0x14, "imag".getBytes(StandardCharsets.US_ASCII))) {
				return BCLIM;
			}
			if (starts(tail, at, "FLIM".getBytes(StandardCharsets.US_ASCII))) {
				return FLIM;
			}
		}
		if (starts(head, 0, new byte[] {(byte) 0xFF, (byte) 0xFE})) {
			return name.toLowerCase(Locale.ROOT).matches(".*\\.csv(\\.lz)?") ? CSV : UTF16;
		}
		boolean utf8 = starts(head, 0, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
		if (markup(head, utf8 ? 3 : 0)) {
			return XML;
		}
		return utf8 ? UTF8 : null;
	}

	/** Whether {@code d} holds a tag at {@code at}: {@code <} and a letter, {@code ?} or {@code !}. */
	private static boolean markup(byte[] d, int at) {
		if (at + 1 >= d.length || d[at] != '<') {
			return false;
		}
		char c = (char) (d[at + 1] & 0xFF);
		return c == '?' || c == '!' || c < 0x80 && Character.isLetter(c);
	}

	/** Whether {@code d} starts as a {@code .tdt} does: an entry count, the first entry at 8, its key at 12 into it. */
	private static boolean tdt(byte[] d) {
		return d.length >= 16 && u32(d, 0) > 0 && u32(d, 4) == 8 && u32(d, 8) >= 14 && u32(d, 12) == 12;
	}

	/** A format for a file known by none here: by its first four bytes when they spell a signature, else by its extension. */
	static Format unknown(byte[] head, String extension) {
		if (head.length >= 4) {
			String sig = new String(head, 0, 4, StandardCharsets.ISO_8859_1);
			if (sig.matches("[A-Za-z][A-Za-z0-9_ ]{3}")) {
				return new Format("Unknown \"" + sig.strip() + "\"", "Starts \"" + sig + "\", a signature PsaroEditor does not know", false);
			}
		}
		return new Format("Unknown " + (extension.isEmpty() ? "(no extension)" : extension),
				"No signature PsaroEditor knows", false);
	}

	/** {@code name}'s last extension ({@code .btn}); with {@code compressed}, the one before a {@code .lz}. */
	private static String extension(String name, boolean compressed) {
		String n = compressed ? name.replaceFirst("(?i)\\.lz$", "") : name;
		int dot = n.lastIndexOf('.');
		return dot <= 0 ? "" : n.substring(dot).toLowerCase(Locale.ROOT);
	}

	/** Whether {@code d} looks like an LZ11 stream whose start unpacks. */
	private static boolean compressed(byte[] d) {
		if (!Lz11.isCompressed(d) || d.length < 8) {
			return false;
		}
		long size = unpackedSize(d);
		if (size <= 0 || size > 1L << 30) {
			return false;
		}
		try {
			Lz11.decompress(d, (int) Math.min(size, 16));
			return true;
		} catch (RuntimeException notLz) {
			return false;
		}
	}

	private static int unpackedSize(byte[] d) {
		int size = (d[1] & 0xFF) | (d[2] & 0xFF) << 8 | (d[3] & 0xFF) << 16;
		return size != 0 ? size : u32(d, 4);
	}

	/** {@code d} unpacked whole, or null when it does not unpack. */
	private static byte[] unpack(byte[] d) {
		try {
			return Lz11.decompress(d);
		} catch (RuntimeException notLz) {
			return null;
		}
	}

	private static byte[] tailOf(byte[] d) {
		return d.length < FOOTER ? new byte[0] : java.util.Arrays.copyOfRange(d, d.length - FOOTER, d.length);
	}

	private static byte[] read(FileChannel ch, long at, int n) throws IOException {
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(n);
		while (b.hasRemaining() && ch.read(b, at + b.position()) > 0) {
			// until full or at the end
		}
		return b.position() == n ? b.array() : java.util.Arrays.copyOf(b.array(), b.position());
	}

	private static boolean starts(byte[] d, int at, byte[] sig) {
		if (at < 0 || at + sig.length > d.length) {
			return false;
		}
		for (int i = 0; i < sig.length; i++) {
			if (d[at + i] != sig[i]) {
				return false;
			}
		}
		return true;
	}

	private static int u32(byte[] d, int i) {
		return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | (d[i + 3] & 0xFF) << 24;
	}

	/**
	 * {@code items} by format: the formats PsaroEditor does not read first, then those it does, each
	 * most files first; within one, its files by path.
	 */
	public static List<Group> groups(List<Item> items) {
		Map<Format, List<Item>> by = new LinkedHashMap<>();
		for (Item i : items) {
			by.computeIfAbsent(i.format(), f -> new ArrayList<>()).add(i);
		}
		return by.entrySet().stream().map(e -> new Group(e.getKey(), List.copyOf(e.getValue())))
				.sorted(Comparator.comparing((Group g) -> g.format().read()).thenComparing(g -> -g.items().size())
						.thenComparing(g -> g.format().name()))
				.toList();
	}
}
