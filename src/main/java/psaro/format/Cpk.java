package psaro.format;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A CRI {@code .cpk}: one file holding a game's whole romfs (SD Gundam G Generation 3D keeps
 * everything in {@code data.cpk}), each file in it stored as it is or {@link Crilayla}-compressed.
 *
 * <pre>
 * at 0:          "CPK ", u32 0xFF, u64 size, then an @UTF table of one row: TocOffset,
 *                ContentOffset, ContentSize, EtocOffset and the like
 * at TocOffset:  "TOC ", u32 0xFF, u64 size, then an @UTF table, a row per file: DirName,
 *                FileName, FileSize (as stored), ExtractSize, FileOffset (from the lower of
 *                TocOffset and ContentOffset)
 * then:          the files, each starting on a multiple of the header's Align
 * at EtocOffset: "ETOC", a table of each file's date, after the files
 * </pre>
 *
 * An @UTF table is big-endian: "@UTF", u32 size, then from 8 on u32 where the rows, the strings
 * and the data start, u32 the table's name, u16 columns, u16 row length, u32 rows; then each
 * column: u8 flags (how it is kept in the high nibble: 1 nothing, 3 one value for every row, 5 a
 * value per row; its type in the low one), u32 its name, and a value of one for every row. Some
 * games scramble the tables; they are unscrambled as they are read.
 *
 * <p>Only the tables are read on opening; a file is read when asked for, so a CPK of hundreds of
 * megabytes opens at once. A CPK with no file names (an ID-only table) lists no files.
 *
 * <p>{@link #write} rebuilds one with some of its files replaced, laid out as it was: the header
 * and the TOC where they were, the files in their order, each other file copied as it is stored
 * (compressed or not), each replaced one stored uncompressed, and what came after the files (the
 * ETOC) after them again, with the TOC's sizes and offsets and the header's offsets changed to
 * match.
 */
public final class Cpk implements Closeable {

	/** A file in the CPK: its path, where it starts in the CPK, its size there, and its size once decompressed. */
	public record Entry(String path, long offset, int size, int extractSize) {

		public boolean compressed() {
			return size != extractSize;
		}
	}

	private static final Charset SHIFT_JIS = Charset.forName("windows-31j");
	/** The header chunk, and the table chunk's size field, before the @UTF table. */
	private static final int CHUNK = 0x10;
	/** How far apart files start when the header does not say. */
	private static final int ALIGN = 0x800;
	/** The header's offsets of tables that may come after the files. */
	private static final List<String> TABLES_AFTER = List.of("EtocOffset", "ItocOffset", "GtocOffset", "HtocOffset");
	/** Tables whose rows name files by size and offset too, which a rebuild would have to change as well. */
	private static final List<String> UNSUPPORTED = List.of("ItocOffset", "GtocOffset", "HtocOffset");

	private final FileChannel channel;
	private final Table header;
	private final Table toc;
	private final long tocAt;
	private final long base;
	private final List<Entry> entries;
	/** Each entry's row in the TOC. */
	private final List<Integer> rows;
	private final Map<String, Integer> byPath = new HashMap<>();

	private Cpk(FileChannel channel, Table header, Table toc, long tocAt, long base, List<Entry> entries, List<Integer> rows) {
		this.channel = channel;
		this.header = header;
		this.toc = toc;
		this.tocAt = tocAt;
		this.base = base;
		this.entries = Collections.unmodifiableList(entries);
		this.rows = rows;
		for (int i = 0; i < entries.size(); i++) {
			byPath.putIfAbsent(entries.get(i).path(), i);
		}
	}

	/** Whether {@code file} starts as a CPK does, read no further than that. */
	public static boolean is(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			return Bytes.magic(in.readNBytes(4), 0, "CPK ");
		} catch (IOException unreadable) {
			return false;
		}
	}

	/** Reads {@code file}'s tables; its files are read by {@link #read}. Close it when done. */
	public static Cpk open(Path file) throws IOException {
		FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
		try {
			Table header = chunk(channel, 0, "CPK ");
			if (header.rows.size() != 1) {
				throw new IllegalArgumentException("CPK header has " + header.rows.size() + " rows");
			}
			long tocAt = header.number(0, "TocOffset");
			long content = header.number(0, "ContentOffset");
			List<Entry> entries = new ArrayList<>();
			List<Integer> rows = new ArrayList<>();
			Table toc = null;
			long base = content > 0 && tocAt > 0 ? Math.min(tocAt, content) : tocAt;
			if (tocAt > 0) {
				toc = chunk(channel, tocAt, "TOC ");
				for (int r = 0; r < toc.rows.size(); r++) {
					Map<String, Object> row = toc.rows.get(r);
					String dir = row.get("DirName") instanceof String s ? s : "";
					String name = row.get("FileName") instanceof String s ? s : null;
					if (name == null) {
						continue;
					}
					entries.add(new Entry(dir.isEmpty() ? name : dir + "/" + name, base + toc.number(r, "FileOffset"),
							Math.toIntExact(toc.number(r, "FileSize")), Math.toIntExact(toc.number(r, "ExtractSize"))));
					rows.add(r);
				}
			}
			return new Cpk(channel, header, toc, tocAt, base, entries, rows);
		} catch (ArithmeticException tooLarge) {
			channel.close();
			throw new IllegalArgumentException("CPK file too large", tooLarge);
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	/** Every file, in the table's order. */
	public List<Entry> entries() {
		return entries;
	}

	/** The file at {@code path} ({@code common/database/general/gg3d_unit.bin}), or null. */
	public Entry find(String path) {
		Integer i = byPath.get(path);
		return i == null ? null : entries.get(i);
	}

	/** {@code e}'s bytes, decompressed. */
	public byte[] read(Entry e) throws IOException {
		byte[] d = readAt(channel, e.offset(), e.size());
		if (!e.compressed()) {
			return d;
		}
		byte[] out = Crilayla.decompress(d);
		if (out.length != e.extractSize()) {
			throw new IllegalArgumentException(e.path() + " decompressed to " + out.length + " bytes, not " + e.extractSize());
		}
		return out;
	}

	/**
	 * Writes this CPK to {@code out} with the files {@code replace} names (by path) holding its
	 * bytes instead, written beside {@code out} and then moved there. Reports how far it has got
	 * to {@code progress}. Refused for a CPK whose layout this does not know how to keep: an ID or
	 * group table besides the TOC, or tables between the files.
	 */
	public void write(Map<String, byte[]> replace, Path out, Consumer<String> progress) throws IOException {
		if (toc == null) {
			throw new IllegalArgumentException("the CPK has no TOC to rebuild");
		}
		for (String column : UNSUPPORTED) {
			if (header.number(0, column) > 0) {
				throw new IllegalArgumentException("rebuilding a CPK with " + column.replace("Offset", "") + " is not supported");
			}
		}
		for (String path : replace.keySet()) {
			if (!byPath.containsKey(path)) {
				throw new IllegalArgumentException(path + " is not in the CPK");
			}
		}
		long length = channel.size();
		long align = header.number(0, "Align") > 0 ? header.number(0, "Align") : ALIGN;
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < entries.size(); i++) {
			order.add(i);
		}
		order.sort(Comparator.comparingLong(i -> entries.get(i).offset()));
		long first = order.isEmpty() ? length : entries.get(order.get(0)).offset();
		long last = 0;
		for (Entry e : entries) {
			last = Math.max(last, e.offset() + e.size());
		}
		long after = Math.min(align(last, align), length);
		if (tocAt + CHUNK + toc.bytes.length > first) {
			throw new IllegalArgumentException("the CPK's TOC is not before its files");
		}
		for (String column : TABLES_AFTER) {
			long at = header.number(0, column);
			if (at > 0 && at >= first && at < after) {
				throw new IllegalArgumentException("the CPK's " + column.replace("Offset", "") + " is among its files");
			}
		}

		Table header = this.header.copy();
		Table toc = this.toc.copy();
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		Path parent = out.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (FileChannel w = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE)) {
			copy(channel, 0, first, w, 0);
			long at = first;
			long reported = 0;
			// files stored once for several entries stay shared
			Map<Long, Long> copied = new HashMap<>();
			for (int i : order) {
				Entry e = entries.get(i);
				int row = rows.get(i);
				byte[] bytes = replace.get(e.path());
				long placed;
				int size;
				int extract;
				if (bytes != null) {
					placed = align(at, align);
					w.write(ByteBuffer.wrap(bytes), placed);
					size = bytes.length;
					extract = bytes.length;
					at = placed + size;
				} else {
					Long shared = copied.get(e.offset());
					placed = shared != null ? shared : align(at, align);
					if (shared == null) {
						copy(channel, e.offset(), e.size(), w, placed);
						copied.put(e.offset(), placed);
						at = placed + e.size();
					}
					size = e.size();
					extract = e.extractSize();
				}
				toc.set(row, "FileOffset", placed - base);
				toc.set(row, "FileSize", size);
				toc.set(row, "ExtractSize", extract);
				if (at - reported > length / 50) {
					reported = at;
					progress.accept(String.format("Writing %s: %d%%", out.getFileName(), 100 * at / Math.max(1, length)));
				}
			}
			long moved = align(at, align);
			long shift = moved - after;
			copy(channel, after, length - after, w, moved);
			long content = header.number(0, "ContentOffset");
			if (content > 0 && content + header.number(0, "ContentSize") == after) {
				header.set(0, "ContentSize", moved - content);
			}
			for (String column : TABLES_AFTER) {
				long table = header.number(0, column);
				if (table >= after) {
					header.set(0, column, table + shift);
				}
			}
			w.write(ByteBuffer.wrap(header.stored()), CHUNK);
			w.write(ByteBuffer.wrap(toc.stored()), tocAt + CHUNK);
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		try {
			Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}

	private static long align(long at, long align) {
		return (at + align - 1) / align * align;
	}

	private static void copy(FileChannel from, long at, long length, FileChannel to, long where) throws IOException {
		long done = 0;
		while (done < length) {
			long n = from.transferTo(at + done, length - done, to.position(where + done));
			if (n <= 0) {
				throw new IOException("CPK ended early");
			}
			done += n;
		}
	}

	/** The @UTF table of the chunk tagged {@code tag} at {@code at}. */
	private static Table chunk(FileChannel channel, long at, String tag) throws IOException {
		byte[] head = readAt(channel, at, CHUNK);
		if (!Bytes.magic(head, 0, tag)) {
			throw new IllegalArgumentException("no " + tag.strip() + " table at 0x" + Long.toHexString(at));
		}
		long size = Bytes.u32(head, 8) & 0xFFFFFFFFL | (long) Bytes.u32(head, 12) << 32;
		if (size < 32 || size > Integer.MAX_VALUE || at + CHUNK + size > channel.size()) {
			throw new IllegalArgumentException(tag.strip() + " table size out of range");
		}
		byte[] table = readAt(channel, at + CHUNK, (int) size);
		boolean scrambled = !Bytes.magic(table, 0, "@UTF");
		if (scrambled) {
			scramble(table);
			if (!Bytes.magic(table, 0, "@UTF")) {
				throw new IllegalArgumentException(tag.strip() + " table is not @UTF");
			}
		}
		return new Table(table, scrambled);
	}

	private static byte[] readAt(FileChannel channel, long at, int length) throws IOException {
		if (at < 0 || length < 0 || at + length > channel.size()) {
			throw new IllegalArgumentException("read past the end of the CPK");
		}
		ByteBuffer b = ByteBuffer.allocate(length);
		while (b.hasRemaining()) {
			if (channel.read(b, at + b.position()) < 0) {
				throw new IOException("CPK ended early");
			}
		}
		return b.array();
	}

	/** The scrambling some games give their tables, each byte XORed with a running key; it undoes itself. */
	private static void scramble(byte[] d) {
		int key = 0x655F;
		for (int i = 0; i < d.length; i++) {
			d[i] ^= (byte) key;
			key *= 0x4115;
		}
	}

	/** An @UTF table's rows, each column's value by its name: a Long, Double, String or byte[]. */
	static List<Map<String, Object>> utf(byte[] t) {
		return new Table(t, false).rows;
	}

	/**
	 * An @UTF table as read: its rows, and where in it each per-row value is, so a number can be
	 * changed in place ({@link #set}) and the table written back as it was kept ({@link #stored}).
	 */
	private static final class Table {
		final byte[] bytes;
		final boolean scrambled;
		final List<Map<String, Object>> rows = new ArrayList<>();
		/** Per row, each per-row column's {where, type}. */
		final List<Map<String, int[]>> places = new ArrayList<>();

		Table(byte[] t, boolean scrambled) {
			this.bytes = t;
			this.scrambled = scrambled;
			int rowsAt = 8 + be32(t, 8);
			int stringsAt = 8 + be32(t, 12);
			int dataAt = 8 + be32(t, 16);
			int columns = be16(t, 24);
			int rowLength = be16(t, 26);
			int count = be32(t, 28);
			if (rowsAt < 32 || stringsAt < rowsAt || dataAt < stringsAt || dataAt > t.length || count < 0
					|| (long) count * rowLength > stringsAt - rowsAt) {
				throw new IllegalArgumentException("@UTF table out of range");
			}
			Utf u = new Utf(t, stringsAt, dataAt);
			List<String> names = new ArrayList<>();
			List<Integer> types = new ArrayList<>();
			List<Boolean> perRow = new ArrayList<>();
			List<Object> constants = new ArrayList<>();
			int p = 32;
			for (int c = 0; c < columns; c++) {
				int flags = t[p] & 0xFF;
				names.add(u.string(be32(t, p + 1)));
				p += 5;
				int storage = flags >> 4;
				int type = flags & 0xF;
				types.add(type);
				perRow.add(storage == 5);
				if (storage == 3) {
					constants.add(u.value(type, p));
					p += Utf.size(type);
				} else {
					constants.add(null);
				}
			}
			for (int r = 0; r < count; r++) {
				Map<String, Object> row = new LinkedHashMap<>();
				Map<String, int[]> at = new HashMap<>();
				int q = rowsAt + r * rowLength;
				for (int c = 0; c < columns; c++) {
					if (perRow.get(c)) {
						row.put(names.get(c), u.value(types.get(c), q));
						at.put(names.get(c), new int[] {q, types.get(c)});
						q += Utf.size(types.get(c));
					} else {
						row.put(names.get(c), constants.get(c));
					}
				}
				rows.add(row);
				places.add(at);
			}
		}

		long number(int row, String column) {
			return rows.get(row).get(column) instanceof Number n ? n.longValue() : 0;
		}

		/** Sets {@code row}'s {@code column}, which must be a per-row integer wide enough for {@code value}. */
		void set(int row, String column, long value) {
			int[] at = places.get(row).get(column);
			if (at == null) {
				throw new IllegalArgumentException("the CPK's " + column + " is not kept per row");
			}
			int size = switch (at[1]) {
				case 0, 1 -> 1;
				case 2, 3 -> 2;
				case 4, 5 -> 4;
				case 6, 7 -> 8;
				default -> throw new IllegalArgumentException("the CPK's " + column + " is not an integer");
			};
			if (size < 8 && (value < 0 || value >> 8 * size != 0)) {
				throw new IllegalArgumentException(column + " " + value + " does not fit the CPK's " + size + " bytes");
			}
			for (int k = 0; k < size; k++) {
				bytes[at[0] + k] = (byte) (value >> 8 * (size - 1 - k));
			}
			rows.get(row).put(column, value);
		}

		/** The table as the CPK keeps it: scrambled again when it was. */
		byte[] stored() {
			byte[] out = bytes.clone();
			if (scrambled) {
				scramble(out);
			}
			return out;
		}

		/** A copy to change, as this was read. */
		Table copy() {
			return new Table(bytes.clone(), scrambled);
		}
	}

	private record Utf(byte[] t, int stringsAt, int dataAt) {

		static int size(int type) {
			return switch (type) {
				case 0, 1 -> 1;
				case 2, 3 -> 2;
				case 4, 5, 8, 0xA -> 4;
				case 6, 7, 9, 0xB -> 8;
				default -> throw new IllegalArgumentException("@UTF column type " + type);
			};
		}

		Object value(int type, int p) {
			return switch (type) {
				case 0 -> (long) (t[p] & 0xFF);
				case 1 -> (long) t[p];
				case 2 -> (long) be16(t, p);
				case 3 -> (long) (short) be16(t, p);
				case 4 -> be32(t, p) & 0xFFFFFFFFL;
				case 5 -> (long) be32(t, p);
				case 6, 7 -> (be32(t, p) & 0xFFFFFFFFL) << 32 | be32(t, p + 4) & 0xFFFFFFFFL;
				case 8 -> (double) Float.intBitsToFloat(be32(t, p));
				case 9 -> Double.longBitsToDouble((be32(t, p) & 0xFFFFFFFFL) << 32 | be32(t, p + 4) & 0xFFFFFFFFL);
				case 0xA -> string(be32(t, p));
				case 0xB -> {
					int at = dataAt + be32(t, p);
					int n = be32(t, p + 4);
					if (at < dataAt || n < 0 || at + n > t.length) {
						throw new IllegalArgumentException("@UTF data out of range");
					}
					byte[] d = new byte[n];
					System.arraycopy(t, at, d, 0, n);
					yield d;
				}
				default -> throw new IllegalArgumentException("@UTF column type " + type);
			};
		}

		String string(int offset) {
			int at = stringsAt + offset;
			if (offset < 0 || at >= dataAt) {
				throw new IllegalArgumentException("@UTF string out of range");
			}
			int e = at;
			while (e < dataAt && t[e] != 0) {
				e++;
			}
			return new String(t, at, e - at, SHIFT_JIS);
		}
	}

	private static int be16(byte[] d, int i) {
		return (d[i] & 0xFF) << 8 | d[i + 1] & 0xFF;
	}

	private static int be32(byte[] d, int i) {
		return be16(d, i) << 16 | be16(d, i + 2);
	}
}
