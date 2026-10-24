package psaro.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.UnaryOperator;

/**
 * An NCCH, the container each content or partition of a title is: a 0x200-byte header, then its
 * sections (extended header, ExeFS, romfs), the romfs last ({@link RomfsImage}). Only one with no
 * encryption is read here (its NoCrypto flag set, as a decrypted CIA's contents and a decrypted
 * .3ds's partitions are), and its romfs can be replaced, the header's sizes and the romfs's
 * superblock hash following; everything before the romfs is kept as it was, but for ExeFS files
 * changed in place, which keep their size.
 *
 * <pre>
 * 0x100 "NCCH"        0x104 content size (media units)   0x118 program id
 * 0x188 flags: [6] media unit 0x200 &lt;&lt; n, [7] bit 2 NoCrypto
 * 0x1A0 ExeFS offset, size, hash region size (media units)
 * 0x1B0 romfs offset, size, hash region size (media units)
 * 0x1C0 SHA-256 of the ExeFS's hash region: its header
 * 0x1E0 SHA-256 of the romfs's hash region: its IVFC header and master hash
 * </pre>
 *
 * The ExeFS's 0x200-byte header names up to ten files (name, offset from the header's end, size;
 * 0x10 bytes each), and from 0x1E0 back gives each one's SHA-256, the first file's last.
 *
 * The header's signature is not made again; a rebuilt title runs only where signatures go
 * unchecked, as with custom firmware and in emulators.
 */
public final class Ncch {

	private static final int HEADER = 0x200;

	/** An NCCH rebuilt: its pieces, ranges of the source it was read from, their size, and the SHA-256 of them all. */
	public record Rebuilt(List<Part> parts, long size, byte[] sha256) {
	}

	private Ncch() {
	}

	/** Whether {@code ncch} starts as an NCCH does. */
	public static boolean is(byte[] ncch) {
		return ncch.length >= HEADER && Bytes.magic(ncch, 0x100, "NCCH");
	}

	/** Whether {@code ncch} has no encryption, so its sections can be read as they are. */
	public static boolean decrypted(byte[] ncch) {
		return is(ncch) && (ncch[0x18F] & 0x04) != 0;
	}

	/** The program id, as the title id it belongs to is written. */
	public static long programId(byte[] ncch) {
		return ByteBuffer.wrap(ncch).order(ByteOrder.LITTLE_ENDIAN).getLong(0x118);
	}

	private static int mediaUnit(byte[] ncch) {
		return HEADER << (ncch[0x18E] & 0xFF);
	}

	/** Where the romfs of the NCCH {@code header} starts, from the NCCH's start, and its size, in bytes; both 0 when it has none. */
	public static long[] romfsPlace(byte[] header) {
		check(header);
		int unit = mediaUnit(header);
		long size = (Bytes.u32(header, 0x1B4) & 0xFFFFFFFFL) * unit;
		return size == 0 ? new long[] {0, 0} : new long[] {(Bytes.u32(header, 0x1B0) & 0xFFFFFFFFL) * unit, size};
	}

	/** The romfs's image, which a decrypted NCCH holds as it is. */
	public static byte[] romfs(byte[] ncch) {
		check(ncch);
		int unit = mediaUnit(ncch);
		long offset = (long) Bytes.u32(ncch, 0x1B0) * unit;
		long size = (long) Bytes.u32(ncch, 0x1B4) * unit;
		if (size == 0 || offset + size > ncch.length) {
			throw new IllegalArgumentException("the NCCH has no romfs");
		}
		return Arrays.copyOfRange(ncch, (int) offset, (int) (offset + size));
	}

	/**
	 * A copy of {@code ncch} with {@code romfs} (a whole number of media units) in place of its
	 * romfs, which must be its last section: the content's and the romfs's sizes, the hash region's
	 * size and the superblock hash set for it.
	 */
	public static byte[] withRomfs(byte[] ncch, byte[] romfs) {
		check(ncch);
		int offset = romfsOffset(ncch, ncch.length);
		byte[] out = Arrays.copyOf(ncch, offset + romfs.length);
		System.arraycopy(romfs, 0, out, offset, romfs.length);
		fixHeader(out, romfs.length, romfs);
		return out;
	}

	/**
	 * The file {@code name} of the ExeFS of the decrypted NCCH at {@code at} in {@code source}, or null
	 * when it has no ExeFS or no such file.
	 */
	public static byte[] exefsFile(Source source, long at, String name) throws IOException {
		byte[] header = source.read(at, HEADER);
		check(header);
		long exefs = exefsOffset(header);
		if (exefs == 0) {
			return null;
		}
		int[] f = exefsEntry(source.read(at + exefs, HEADER), 0, name);
		return f == null ? null : source.read(at + exefs + HEADER + f[1], f[2]);
	}

	/**
	 * The decrypted NCCH at {@code at} in {@code source} ({@code size} bytes) rebuilt with each romfs
	 * file {@code replace} names holding its bytes there ({@link RomfsImage#rebuild}), and each ExeFS
	 * file {@code exefs} names made anew from its own bytes, at the size it was. Everything else
	 * before the romfs is read and kept; the romfs is read twice, to hash its level 3 and then the
	 * whole content, {@code read} hearing how much.
	 */
	public static Rebuilt rebuild(Source source, long at, long size, Map<String, byte[]> replace,
			Map<String, UnaryOperator<byte[]>> exefs, LongConsumer read) throws IOException {
		byte[] header = source.read(at, HEADER);
		check(header);
		int offset = romfsOffset(header, size);
		byte[] pre = source.read(at, offset);
		exefs.forEach((name, change) -> changeExefsFile(pre, name, change));
		RomfsImage.Rebuilt romfs = RomfsImage.rebuild(source, at + offset, replace, read);
		byte[] top = ((Part.Bytes) romfs.parts().get(0)).data();
		fixHeader(pre, romfs.size(), top);
		List<Part> parts = new ArrayList<>();
		parts.add(new Part.Bytes(pre));
		parts.addAll(romfs.parts());
		return new Rebuilt(List.copyOf(parts), offset + romfs.size(), Part.sha256(source, parts, read));
	}

	/** Where the romfs of the NCCH {@code header} starts, checking it is the last section of the {@code size} bytes. */
	private static int romfsOffset(byte[] header, long size) {
		int unit = mediaUnit(header);
		long offset = (long) Bytes.u32(header, 0x1B0) * unit;
		long romfsSize = (long) Bytes.u32(header, 0x1B4) * unit;
		long content = (long) Bytes.u32(header, 0x104) * unit;
		if (romfsSize == 0 || offset < HEADER) {
			throw new IllegalArgumentException("the NCCH has no romfs");
		}
		if (offset + romfsSize != content || content > size) {
			throw new IllegalArgumentException("the NCCH has sections after its romfs");
		}
		if (offset > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("the NCCH's sections before its romfs are too large");
		}
		return (int) offset;
	}

	/**
	 * Sets in {@code pre} (the header and what follows it up to the romfs) the sizes for a romfs of
	 * {@code romfsSize} bytes starting with {@code romfsTop}, and its superblock hash.
	 */
	private static void fixHeader(byte[] pre, long romfsSize, byte[] romfsTop) {
		int unit = mediaUnit(pre);
		if (romfsSize % unit != 0) {
			throw new IllegalArgumentException("the romfs is not a whole number of media units");
		}
		int offset = Bytes.u32(pre, 0x1B0) * unit;
		int hashed = (RomfsImage.hashRegionSize(romfsTop) + unit - 1) / unit;
		ByteBuffer h = ByteBuffer.wrap(pre).order(ByteOrder.LITTLE_ENDIAN);
		h.putInt(0x104, (int) ((offset + romfsSize) / unit));
		h.putInt(0x1B4, (int) (romfsSize / unit));
		h.putInt(0x1B8, hashed);
		var sha = RomfsImage.sha256();
		sha.update(romfsTop, 0, Math.min(romfsTop.length, hashed * unit));
		System.arraycopy(sha.digest(), 0, pre, 0x1E0, 32);
	}

	/** Where the ExeFS of the NCCH {@code header} starts, from the NCCH's start; 0 when it has none. */
	private static long exefsOffset(byte[] header) {
		return Bytes.u32(header, 0x1A4) == 0 ? 0 : (Bytes.u32(header, 0x1A0) & 0xFFFFFFFFL) * mediaUnit(header);
	}

	/**
	 * The file {@code name} of the ExeFS whose header is at {@code exefs} in {@code d}: its index, and
	 * its offset from the header's end and size; null when there is no such file.
	 */
	private static int[] exefsEntry(byte[] d, int exefs, String name) {
		for (int i = 0; i < 10; i++) {
			int e = exefs + i * 0x10;
			String n = new String(d, e, 8, StandardCharsets.US_ASCII);
			int end = n.indexOf('\0');
			if (d[e] != 0 && (end < 0 ? n : n.substring(0, end)).equals(name)) {
				return new int[] {i, Bytes.u32(d, e + 8), Bytes.u32(d, e + 12)};
			}
		}
		return null;
	}

	/**
	 * Makes the ExeFS file {@code name} in {@code pre} (the NCCH up to its romfs) anew with
	 * {@code change}, at the size it was; its hash and the ExeFS header's hash follow.
	 */
	private static void changeExefsFile(byte[] pre, String name, UnaryOperator<byte[]> change) {
		long exefs = exefsOffset(pre);
		int[] f = exefs == 0 || exefs + HEADER > pre.length ? null : exefsEntry(pre, (int) exefs, name);
		if (f == null) {
			throw new IllegalArgumentException("the ExeFS has no " + name);
		}
		int at = (int) exefs + HEADER + f[1];
		if (f[1] < 0 || f[2] < 0 || (long) at + f[2] > pre.length) {
			throw new IllegalArgumentException("the ExeFS's " + name + " is not inside it");
		}
		byte[] data = change.apply(Arrays.copyOfRange(pre, at, at + f[2]));
		if (data.length != f[2]) {
			throw new IllegalArgumentException("the ExeFS's " + name + " must stay " + f[2] + " bytes");
		}
		System.arraycopy(data, 0, pre, at, data.length);
		var sha = RomfsImage.sha256();
		System.arraycopy(sha.digest(data), 0, pre, (int) exefs + 0x1E0 - 0x20 * f[0], 32);
		sha.update(pre, (int) exefs, Math.min(Bytes.u32(pre, 0x1A8) * mediaUnit(pre), pre.length - (int) exefs));
		System.arraycopy(sha.digest(), 0, pre, 0x1C0, 32);
	}

	private static void check(byte[] ncch) {
		if (!is(ncch)) {
			throw new IllegalArgumentException("not an NCCH");
		}
		if (!decrypted(ncch)) {
			throw new IllegalArgumentException("the NCCH is encrypted");
		}
	}
}
