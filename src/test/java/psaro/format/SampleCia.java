package psaro.format;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds small decrypted titles for tests as the game's tools lay them out: a romfs image of some
 * files ({@link RomfsImage}), an NCCH holding it, and a CIA of NCCH contents.
 */
public final class SampleCia {

	private static final int NONE = 0xFFFFFFFF;

	private SampleCia() {
	}

	/** A romfs image of {@code files} (by path, {@code icons/1.icn}), blocks of 0x1000 as the game's are. */
	public static byte[] romfs(Map<String, byte[]> files) {
		return RomfsImage.build(level3(files), new int[] {12, 12, 12});
	}

	/** A directory of the tree being built, with its entry's offset once laid out. */
	private static final class Dir {
		final String name;
		final Dir parent;
		final Map<String, Dir> dirs = new LinkedHashMap<>();
		final Map<String, byte[]> files = new LinkedHashMap<>();
		int offset;

		Dir(String name, Dir parent) {
			this.name = name;
			this.parent = parent;
		}
	}

	/** Level 3 of a romfs holding {@code files}: its header, hash tables, entries and file data. */
	static byte[] level3(Map<String, byte[]> files) {
		Dir root = new Dir("", null);
		files.forEach((path, data) -> {
			String[] parts = path.split("/");
			Dir d = root;
			for (int i = 0; i < parts.length - 1; i++) {
				Dir parent = d;
				String part = parts[i];
				d = d.dirs.computeIfAbsent(part, n -> new Dir(n, parent));
			}
			d.files.put(parts[parts.length - 1], data);
		});
		List<Dir> dirs = new ArrayList<>();
		collect(root, dirs);
		int dirMetaSize = 0;
		for (Dir d : dirs) {
			d.offset = dirMetaSize;
			dirMetaSize += 0x18 + align(utf16(d.name).length, 4);
		}
		record FileEntry(Dir parent, String name, byte[] data, int offset) {
		}
		List<FileEntry> fileEntries = new ArrayList<>();
		int fileMetaSize = 0;
		for (Dir d : dirs) {
			for (Map.Entry<String, byte[]> f : d.files.entrySet()) {
				fileEntries.add(new FileEntry(d, f.getKey(), f.getValue(), fileMetaSize));
				fileMetaSize += 0x20 + align(utf16(f.getKey()).length, 4);
			}
		}
		int dirBuckets = buckets(dirs.size());
		int fileBuckets = buckets(fileEntries.size());
		int dirHash = 0x28;
		int dirMeta = dirHash + 4 * dirBuckets;
		int fileHash = dirMeta + dirMetaSize;
		int fileMeta = fileHash + 4 * fileBuckets;
		int dataStart = align(fileMeta + fileMetaSize, 0x10);
		int dataSize = 0;
		for (FileEntry f : fileEntries) {
			dataSize = align(dataSize, 0x10) + f.data().length;
		}
		ByteBuffer b = ByteBuffer.allocate(dataStart + dataSize).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0x28).putInt(dirHash).putInt(4 * dirBuckets).putInt(dirMeta).putInt(dirMetaSize).putInt(fileHash)
				.putInt(4 * fileBuckets).putInt(fileMeta).putInt(fileMetaSize).putInt(dataStart);
		for (int i = 0; i < dirBuckets; i++) {
			b.putInt(dirHash + 4 * i, NONE);
		}
		for (int i = 0; i < fileBuckets; i++) {
			b.putInt(fileHash + 4 * i, NONE);
		}
		for (Dir d : dirs) {
			int at = dirMeta + d.offset;
			List<Dir> siblings = d.parent == null ? List.of(d) : new ArrayList<>(d.parent.dirs.values());
			int next = siblings.indexOf(d) + 1;
			b.putInt(at, d.parent == null ? 0 : d.parent.offset);
			b.putInt(at + 4, next < siblings.size() ? siblings.get(next).offset : NONE);
			b.putInt(at + 8, d.dirs.isEmpty() ? NONE : d.dirs.values().iterator().next().offset);
			FileEntry first = fileEntries.stream().filter(f -> f.parent() == d).findFirst().orElse(null);
			b.putInt(at + 12, first == null ? NONE : first.offset());
			int bucket = Integer.remainderUnsigned(hash(d.parent == null ? 0 : d.parent.offset, d.name), dirBuckets);
			b.putInt(at + 16, b.getInt(dirHash + 4 * bucket));
			b.putInt(dirHash + 4 * bucket, d.offset);
			byte[] name = utf16(d.name);
			b.putInt(at + 20, name.length);
			b.put(at + 24, name);
		}
		int data = 0;
		for (int i = 0; i < fileEntries.size(); i++) {
			FileEntry f = fileEntries.get(i);
			int at = fileMeta + f.offset();
			FileEntry next = i + 1 < fileEntries.size() && fileEntries.get(i + 1).parent() == f.parent() ? fileEntries.get(i + 1) : null;
			data = align(data, 0x10);
			b.putInt(at, f.parent().offset);
			b.putInt(at + 4, next == null ? NONE : next.offset());
			b.putLong(at + 8, data);
			b.putLong(at + 16, f.data().length);
			int bucket = Integer.remainderUnsigned(hash(f.parent().offset, f.name()), fileBuckets);
			b.putInt(at + 24, b.getInt(fileHash + 4 * bucket));
			b.putInt(fileHash + 4 * bucket, f.offset());
			byte[] name = utf16(f.name());
			b.putInt(at + 28, name.length);
			b.put(at + 32, name);
			b.put(dataStart + data, f.data());
			data += f.data().length;
		}
		return b.array();
	}

	private static void collect(Dir d, List<Dir> out) {
		out.add(d);
		d.dirs.values().forEach(c -> collect(c, out));
	}

	/** The romfs's path hash: the parent's entry offset, then each UTF-16 unit rotated in. */
	private static int hash(int parent, String name) {
		int h = parent ^ 123456789;
		for (char c : name.toCharArray()) {
			h = (h >>> 5) | (h << 27);
			h ^= c;
		}
		return h;
	}

	private static int buckets(int entries) {
		if (entries < 3) {
			return 3;
		}
		if (entries < 19) {
			return entries | 1;
		}
		int n = entries;
		while (n % 2 == 0 || n % 3 == 0 || n % 5 == 0 || n % 7 == 0 || n % 11 == 0 || n % 13 == 0 || n % 17 == 0) {
			n++;
		}
		return n;
	}

	/**
	 * An NCCH content of a DLC holding {@code romfs}: its header (program id {@code programId},
	 * NoCrypto unless {@code encrypted}), padded to 0x1000, then the romfs.
	 */
	public static byte[] ncch(long programId, byte[] romfs, boolean encrypted) {
		return ncch(programId, romfs, encrypted, Map.of());
	}

	/**
	 * As {@link #ncch(long, byte[], boolean)}, with an ExeFS of {@code exefs} (by name, ten at most)
	 * from 0x200, its files each padded to 0x200 and hashed, the romfs after it on the next 0x1000.
	 */
	public static byte[] ncch(long programId, byte[] romfs, boolean encrypted, Map<String, byte[]> exefs) {
		ByteArrayOutputStream fs = new ByteArrayOutputStream();
		if (!exefs.isEmpty()) {
			ByteBuffer e = ByteBuffer.allocate(0x200).order(ByteOrder.LITTLE_ENDIAN);
			int i = 0;
			int at = 0;
			for (Map.Entry<String, byte[]> f : exefs.entrySet()) {
				e.put(i * 0x10, f.getKey().getBytes(StandardCharsets.US_ASCII));
				e.putInt(i * 0x10 + 8, at).putInt(i * 0x10 + 12, f.getValue().length);
				e.put(0x1E0 - 0x20 * i, sha(f.getValue()));
				at = align(at + f.getValue().length, 0x200);
				i++;
			}
			fs.writeBytes(e.array());
			for (byte[] f : exefs.values()) {
				fs.writeBytes(f);
				fs.writeBytes(new byte[align(f.length, 0x200) - f.length]);
			}
		}
		byte[] exefsBytes = fs.toByteArray();
		int romfsAt = exefs.isEmpty() ? 0x1000 : align(0x200 + exefsBytes.length, 0x1000);
		byte[] out = new byte[romfsAt + romfs.length];
		ByteBuffer h = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
		h.put(0x100, "NCCH".getBytes(StandardCharsets.US_ASCII));
		h.putInt(0x104, out.length / 0x200);
		h.putLong(0x108, programId);
		h.putLong(0x118, programId);
		out[0x18C] = 1;
		out[0x18D] = 1;
		out[0x18F] = (byte) (encrypted ? 0 : 4);
		if (!exefs.isEmpty()) {
			h.putInt(0x1A0, 1);
			h.putInt(0x1A4, exefsBytes.length / 0x200);
			h.putInt(0x1A8, 1);
			System.arraycopy(exefsBytes, 0, out, 0x200, exefsBytes.length);
			System.arraycopy(sha(java.util.Arrays.copyOf(exefsBytes, 0x200)), 0, out, 0x1C0, 32);
		}
		h.putInt(0x1B0, romfsAt / 0x200);
		h.putInt(0x1B4, romfs.length / 0x200);
		h.putInt(0x1B8, RomfsImage.hashRegionSize(romfs) / 0x200);
		System.arraycopy(romfs, 0, out, romfsAt, romfs.length);
		System.arraycopy(sha(java.util.Arrays.copyOf(romfs, RomfsImage.hashRegionSize(romfs))), 0, out, 0x1E0, 32);
		return out;
	}

	/** An SMDH naming the title {@code shortTitle} (long title the same) by {@code publisher} in Japanese and English only. */
	public static byte[] smdh(String shortTitle, String publisher) {
		byte[] out = new byte[Smdh.SIZE];
		ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
		b.put(0, "SMDH".getBytes(StandardCharsets.US_ASCII));
		for (int language = 0; language < 2; language++) {
			int slot = 8 + language * 0x200;
			b.put(slot, utf16(shortTitle));
			b.put(slot + 0x80, utf16(shortTitle));
			b.put(slot + 0x180, utf16(publisher));
		}
		// the icons, so they can be seen to be kept
		java.util.Arrays.fill(out, 0x2040, Smdh.SIZE, (byte) 0x5A);
		return out;
	}

	/** A content of a CIA: its id, index, type and data. */
	public record Content(int id, int index, int type, byte[] data) {
	}

	/** A CIA of {@code titleId} holding {@code contents} in order, its TMD's hashes right, with {@code meta} after. */
	public static byte[] cia(long titleId, List<Content> contents, byte[] meta) {
		int body = 0x140;
		int records = body + 0xC4 + 64 * 0x24;
		ByteBuffer tmd = ByteBuffer.allocate(records + 0x30 * contents.size());
		tmd.putInt(0, 0x10004);
		tmd.putLong(body + 0x4C, titleId);
		tmd.putShort(body + 0x9E, (short) contents.size());
		for (int i = 0; i < contents.size(); i++) {
			Content c = contents.get(i);
			int r = records + i * 0x30;
			tmd.putInt(r, c.id()).putShort(r + 4, (short) c.index()).putShort(r + 6, (short) c.type())
					.putLong(r + 8, c.data().length);
			tmd.put(r + 16, sha(c.data()));
		}
		tmd.putShort(body + 0xC4, (short) 0).putShort(body + 0xC6, (short) contents.size());
		tmd.put(body + 0xC8, sha(java.util.Arrays.copyOfRange(tmd.array(), records, records + 0x30 * contents.size())));
		tmd.put(body + 0xA4, sha(java.util.Arrays.copyOfRange(tmd.array(), body + 0xC4, body + 0xC4 + 64 * 0x24)));

		long contentSize = contents.stream().mapToLong(c -> c.data().length).sum();
		ByteBuffer header = ByteBuffer.allocate(0x2020).order(ByteOrder.LITTLE_ENDIAN);
		header.putInt(0x2020).putShort((short) 0).putShort((short) 0).putInt(0x40).putInt(0x40).putInt(tmd.capacity())
				.putInt(meta.length).putLong(contentSize);
		for (Content c : contents) {
			int at = 0x20 + c.index() / 8;
			header.put(at, (byte) (header.get(at) | 0x80 >> (c.index() % 8)));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, header.array());
		write(out, new byte[0x40]);
		write(out, new byte[0x40]);
		write(out, tmd.array());
		for (Content c : contents) {
			out.writeBytes(c.data());
		}
		pad(out);
		out.writeBytes(meta);
		return out.toByteArray();
	}

	/**
	 * A game card's image (.3ds) of {@code partitions} (by index: 0 the game, 1 its manual, 7 an
	 * update), laid out from 0x4000 one after another, with a gap of {@code gap} bytes before the
	 * update, its card size {@code cardSize} bytes and its data padded to it with 0xFF, as a dump is.
	 */
	public static byte[] ncsd(Map<Integer, byte[]> partitions, int gap, long cardSize) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] head = new byte[0x4000];
		ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
		h.put(0x100, "NCSD".getBytes(StandardCharsets.US_ASCII));
		h.putInt(0x104, (int) (cardSize / 0x200));
		int at = head.length;
		for (Map.Entry<Integer, byte[]> p : new java.util.TreeMap<>(partitions).entrySet()) {
			if (p.getKey() == 7) {
				at += gap;
			}
			h.putInt(0x120 + p.getKey() * 8, at / 0x200);
			h.putInt(0x124 + p.getKey() * 8, p.getValue().length / 0x200);
			at += p.getValue().length;
		}
		h.putInt(0x300, at);
		out.writeBytes(head);
		for (Map.Entry<Integer, byte[]> p : new java.util.TreeMap<>(partitions).entrySet()) {
			if (p.getKey() == 7) {
				out.writeBytes(new byte[gap]);
			}
			out.writeBytes(p.getValue());
		}
		byte[] pad = new byte[(int) (cardSize - out.size())];
		java.util.Arrays.fill(pad, (byte) 0xFF);
		out.writeBytes(pad);
		return out.toByteArray();
	}

	private static void write(ByteArrayOutputStream out, byte[] section) {
		out.writeBytes(section);
		pad(out);
	}

	private static void pad(ByteArrayOutputStream out) {
		out.writeBytes(new byte[(0x40 - out.size() % 0x40) % 0x40]);
	}

	static byte[] sha(byte[] d) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(d);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] utf16(String s) {
		return s.getBytes(StandardCharsets.UTF_16LE);
	}

	private static int align(int x, int a) {
		return (x + a - 1) / a * a;
	}
}
