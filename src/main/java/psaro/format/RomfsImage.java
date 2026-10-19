package psaro.format;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * A romfs as one image, as an NCCH holds it: an IVFC hash tree over the romfs proper (level 3).
 * This reads the files of one and rebuilds it with some of them replaced, its tree, names and
 * lookup tables kept as they were, so only the file data and the hashes over it change. A rebuild
 * is pieces of the original ({@link Part}), so a game's romfs of a gigabyte is never held whole.
 *
 * <pre>
 * 0x00  "IVFC", 0x10000, master hash size, then three levels: logical offset (u64), size (u64), block size (log2)
 * 0x54  0x5C (the header's size); the master hash at 0x60
 * level 3, at 0x60 + master hash rounded up to its block: the romfs proper
 * level 1 after it, then level 2, each at the next block; the whole padded to level 2's block
 * </pre>
 *
 * Each level hashes the blocks of the next: the master hash level 1's, level 1 level 2's, level 2
 * level 3's, every block SHA-256 and the last one padded with zeros. Level 3 is a header of ten
 * words (its own size, then the offset and size of the directory hash table, the directories, the
 * file hash table and the files, then where the file data starts) and its tables; a file's entry
 * holds its parent, its next sibling, its data's offset and size (u64s), its next in the hash
 * bucket, and its name in UTF-16. Little-endian throughout.
 */
public final class RomfsImage {

	private static final int IVFC_HEADER = 0x60;
	private static final int NONE = 0xFFFFFFFF;
	/** Where each file's data starts in level 3, as the game's romfs builds lay it out. */
	private static final int DATA_ALIGN = 0x10;
	/** An image's size is a whole number of NCCH media units. */
	private static final int MEDIA_UNIT = 0x200;

	/** A romfs rebuilt: its pieces, ranges of the source it was read from, and their size together. */
	public record Rebuilt(List<Part> parts, long size) {
	}

	/** Where a file's entry is in level 3, and its data. */
	private record Entry(String path, int meta, long offset, long size) {
	}

	private RomfsImage() {
	}

	/** Every file of {@code image}, by its path ({@code dir/name}), in level 3's order. */
	public static Map<String, byte[]> files(byte[] image) {
		try {
			Source s = Source.of(image);
			int[] blockLog = blockLog(s, 0);
			long lv3 = lv3(s, 0, blockLog);
			byte[] tables = tables(s, lv3);
			Map<String, byte[]> out = new LinkedHashMap<>();
			long dataStart = lv3 + Bytes.u32(tables, 0x24);
			for (Entry e : entries(tables)) {
				out.put(e.path(), java.util.Arrays.copyOfRange(image, (int) (dataStart + e.offset()),
						(int) (dataStart + e.offset() + e.size())));
			}
			return out;
		} catch (IOException e) {
			throw new IllegalArgumentException("the romfs is cut short", e);
		}
	}

	/** {@code image} rebuilt with each file {@code replace} names (by path) holding its bytes there; see {@link #rebuild}. */
	public static byte[] replace(byte[] image, Map<String, byte[]> replace) {
		try {
			Source s = Source.of(image);
			return Part.bytes(s, rebuild(s, 0, replace, n -> { }).parts());
		} catch (IOException e) {
			throw new IllegalArgumentException("the romfs is cut short", e);
		}
	}

	/**
	 * The romfs image at {@code at} in {@code source}, rebuilt with each file {@code replace} names
	 * (by path) holding its bytes there: the files laid out again in their order, every hash level
	 * rebuilt over them, the whole padded to a block. With nothing replaced it is the image as the
	 * game's tools built it. Its level 3 is read once, to hash it; {@code read} hears how much. A
	 * path the image has no file at is refused.
	 */
	public static Rebuilt rebuild(Source source, long at, Map<String, byte[]> replace, LongConsumer read) throws IOException {
		int[] blockLog = blockLog(source, at);
		long lv3 = lv3(source, at, blockLog);
		byte[] tables = tables(source, lv3);
		int dataOffset = Bytes.u32(tables, 0x24);
		List<Entry> entries = entries(tables);
		for (String path : replace.keySet()) {
			if (entries.stream().noneMatch(e -> e.path().equals(path))) {
				throw new IllegalArgumentException("the romfs has no file " + path);
			}
		}
		// the tables as they were, but for each file's place; then the files, in the order their data was
		ByteBuffer meta = ByteBuffer.wrap(tables).order(ByteOrder.LITTLE_ENDIAN);
		List<Entry> byData = new ArrayList<>(entries);
		byData.sort(Comparator.comparingLong(Entry::offset).thenComparingInt(Entry::meta));
		List<Part> level3 = new ArrayList<>();
		level3.add(new Part.Bytes(tables));
		long dataAt = lv3 + dataOffset;
		long pos = 0;
		for (Entry e : byData) {
			long aligned = align(pos, DATA_ALIGN);
			if (aligned > pos) {
				level3.add(Part.zeros(aligned - pos));
			}
			pos = aligned;
			byte[] bytes = replace.get(e.path());
			Part data = bytes != null ? new Part.Bytes(bytes) : new Part.Range(dataAt + e.offset(), dataAt + e.offset() + e.size());
			int entry = Bytes.u32(tables, 0x1C) + e.meta();
			meta.putLong(entry + 0x08, pos);
			meta.putLong(entry + 0x10, data.size());
			level3.add(data);
			pos += data.size();
		}
		return assemble(source, level3, dataOffset + pos, blockLog, read);
	}

	/** An image of {@code level3}, its hash levels built over it with the block sizes {@code blockLog} (levels 1 to 3). */
	static byte[] build(byte[] level3, int[] blockLog) {
		try {
			Source s = Source.of(new byte[0]);
			return Part.bytes(s, assemble(s, List.of(new Part.Bytes(level3)), level3.length, blockLog, n -> { }).parts());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The image of the level 3 {@code level3} (of {@code lv3Size} bytes): its header and master hash, level 3, then levels 1 and 2. */
	private static Rebuilt assemble(Source source, List<Part> level3, long lv3Size, int[] blockLog, LongConsumer read)
			throws IOException {
		byte[] level2 = Part.blockHashes(source, level3, 1 << blockLog[2], read);
		byte[] level1 = Part.blockHashes(source, List.of(new Part.Bytes(level2)), 1 << blockLog[1], n -> { });
		byte[] master = Part.blockHashes(source, List.of(new Part.Bytes(level1)), 1 << blockLog[0], n -> { });
		long lv3At = align(IVFC_HEADER + master.length, 1 << blockLog[2]);
		long lv1At = align(lv3At + lv3Size, 1 << blockLog[0]);
		long lv2At = align(lv1At + level1.length, 1 << blockLog[1]);
		// padded to level 2's block, as the game's are, which is a whole number of media units
		long total = align(align(lv2At + level2.length, 1 << blockLog[1]), MEDIA_UNIT);

		byte[] top = new byte[(int) lv3At];
		ByteBuffer h = ByteBuffer.wrap(top).order(ByteOrder.LITTLE_ENDIAN);
		h.put("IVFC".getBytes(StandardCharsets.US_ASCII)).putInt(0x10000).putInt(master.length);
		long lv2Logical = align(level1.length, 1 << blockLog[1]);
		long lv3Logical = align(lv2Logical + level2.length, 1 << blockLog[2]);
		long[][] levels = {{0, level1.length}, {lv2Logical, level2.length}, {lv3Logical, lv3Size}};
		for (int k = 0; k < 3; k++) {
			h.putLong(levels[k][0]).putLong(levels[k][1]).putInt(blockLog[k]).putInt(0);
		}
		h.putInt(0x54, 0x5C);
		System.arraycopy(master, 0, top, IVFC_HEADER, master.length);

		List<Part> out = new ArrayList<>();
		out.add(new Part.Bytes(top));
		out.addAll(level3);
		out.add(Part.zeros(lv1At - lv3At - lv3Size));
		out.add(new Part.Bytes(level1));
		out.add(Part.zeros(lv2At - lv1At - level1.length));
		out.add(new Part.Bytes(level2));
		out.add(Part.zeros(total - lv2At - level2.length));
		return new Rebuilt(List.copyOf(out), total);
	}

	/** How many bytes from the start of {@code image} its NCCH superblock hash covers: the header and master hash, in media units. */
	public static int hashRegionSize(byte[] image) {
		return (int) align(IVFC_HEADER + Bytes.u32(image, 0x08), MEDIA_UNIT);
	}

	public static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The block size (log2) of each level of the image at {@code at}, checking it is one. */
	private static int[] blockLog(Source s, long at) throws IOException {
		byte[] head = s.read(at, IVFC_HEADER);
		if (!Bytes.magic(head, 0, "IVFC") || Bytes.u32(head, 4) != 0x10000) {
			throw new IllegalArgumentException("not an IVFC romfs");
		}
		int[] blockLog = new int[3];
		for (int k = 0; k < 3; k++) {
			blockLog[k] = Bytes.u32(head, 0x0C + k * 0x18 + 16);
			if (blockLog[k] < 6 || blockLog[k] > 20) {
				throw new IllegalArgumentException("not an IVFC romfs: block size 2^" + blockLog[k]);
			}
		}
		return blockLog;
	}

	/** Where level 3 of the image at {@code at} is, checking its header is there. */
	private static long lv3(Source s, long at, int[] blockLog) throws IOException {
		int master = Bytes.u32(s.read(at, 0x0C), 0x08);
		long lv3 = at + align(IVFC_HEADER + (long) master, 1 << blockLog[2]);
		if (Bytes.u32(s.read(lv3, 4), 0) != 0x28) {
			throw new IllegalArgumentException("the romfs's level 3 is not where its header says");
		}
		return lv3;
	}

	/** Level 3's header and tables, up to its file data. */
	private static byte[] tables(Source s, long lv3) throws IOException {
		int dataOffset = Bytes.u32(s.read(lv3, 0x28), 0x24);
		if (dataOffset < 0x28 || dataOffset > 1 << 28) {
			throw new IllegalArgumentException("the romfs's level 3 header is not one");
		}
		return s.read(lv3, dataOffset);
	}

	/** Every file of level 3, its tables {@code tables}, walking the directories from the root. */
	private static List<Entry> entries(byte[] tables) {
		int dirs = Bytes.u32(tables, 0x0C);
		int files = Bytes.u32(tables, 0x1C);
		List<Entry> out = new ArrayList<>();
		walk(tables, dirs, files, 0, "", out, 0);
		return out;
	}

	private static void walk(byte[] t, int dirs, int files, int dir, String path, List<Entry> out, int depth) {
		if (depth > 64) {
			throw new IllegalArgumentException("the romfs's directories loop");
		}
		ByteBuffer b = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
		for (int f = Bytes.u32(t, dirs + dir + 0x0C); f != NONE; f = Bytes.u32(t, files + f + 0x04)) {
			out.add(new Entry(path + name(t, files + f + 0x20, Bytes.u32(t, files + f + 0x1C)), f, b.getLong(files + f + 0x08),
					b.getLong(files + f + 0x10)));
		}
		for (int d = Bytes.u32(t, dirs + dir + 0x08); d != NONE; d = Bytes.u32(t, dirs + d + 0x04)) {
			walk(t, dirs, files, d, path + name(t, dirs + d + 0x18, Bytes.u32(t, dirs + d + 0x14)) + "/", out, depth + 1);
		}
	}

	private static String name(byte[] d, int at, int length) {
		return new String(d, at, length, StandardCharsets.UTF_16LE);
	}

	private static long align(long x, long a) {
		return (x + a - 1) / a * a;
	}
}
