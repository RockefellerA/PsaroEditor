package psaro.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * A CIA, the file a title is installed from: a header, the certificate chain, the ticket, the TMD,
 * the contents and an optional meta section, each starting on a 0x40-byte boundary, the contents
 * one after another. The TMD lists each content (its id, index, type and size, and the SHA-256 of
 * its data as installed); the header's bitmap says which of them the file holds. A copy can be
 * written with some contents replaced, the TMD's sizes and hashes made to match; the certificate
 * chain, ticket and meta are copied as they are, and nothing is signed again.
 *
 * <pre>
 * header (little-endian): header size 0x2020, type, version, then the sizes of the certificate
 *   chain, ticket, TMD and meta (u32) and of all the contents (u64), then the content bitmap
 * TMD (big-endian): signature, then the body: 0x4C title id, 0x9E content count, 0xA4 SHA-256 of
 *   the 64 content info records at 0xC4 (each: first record, record count, SHA-256 of those
 *   records), then a 0x30-byte record per content: id, index, type (bit 0: encrypted), size, SHA-256
 * </pre>
 */
public final class Cia {

	private static final int ALIGN = 0x40;
	private static final int INFO_RECORDS = 64;
	private static final int INFO_RECORD = 0x24;
	private static final int CHUNK_RECORD = 0x30;

	/** One content of the CIA: its id, index and type, its size, and where its data is in the file. */
	public record Content(int id, int index, int type, long size, long offset) {

		/** Whether its data is encrypted with the title key, as a CIA from the eShop's is. */
		public boolean encrypted() {
			return (type & 1) != 0;
		}
	}

	private final Path file;
	private final byte[] header;
	private final byte[] certs;
	private final byte[] ticket;
	private final byte[] tmd;
	private final byte[] meta;
	/** Where the TMD's body starts, past its signature. */
	private final int body;
	private final List<Content> contents;

	private Cia(Path file, byte[] header, byte[] certs, byte[] ticket, byte[] tmd, byte[] meta, int body,
			List<Content> contents) {
		this.file = file;
		this.header = header;
		this.certs = certs;
		this.ticket = ticket;
		this.tmd = tmd;
		this.meta = meta;
		this.body = body;
		this.contents = contents;
	}

	/** Reads {@code file}'s sections, but its contents, which are read when asked for. */
	public static Cia read(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] header = in.readNBytes(0x20);
			ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			if (header.length < 0x20 || h.getInt(0) != 0x2020) {
				throw new IOException(file.getFileName() + " is not a CIA");
			}
			int certSize = h.getInt(0x08);
			int ticketSize = h.getInt(0x0C);
			int tmdSize = h.getInt(0x10);
			int metaSize = h.getInt(0x14);
			long contentSize = h.getLong(0x18);
			if (certSize < 0 || ticketSize < 0 || tmdSize < 0x200 || metaSize < 0 || contentSize < 0) {
				throw new IOException(file.getFileName() + " is not a CIA");
			}
			byte[] all = Arrays.copyOf(header, 0x2020);
			byte[] rest = in.readNBytes(0x2020 - 0x20);
			System.arraycopy(rest, 0, all, 0x20, rest.length);
			skip(in, align(0x2020) - 0x2020);
			byte[] certs = in.readNBytes(certSize);
			skip(in, align(certSize) - certSize);
			byte[] ticket = in.readNBytes(ticketSize);
			skip(in, align(ticketSize) - ticketSize);
			byte[] tmd = in.readNBytes(tmdSize);
			if (tmd.length < tmdSize) {
				throw new IOException(file.getFileName() + " ends before its TMD does");
			}
			long contentsAt = align(0x2020) + align(certSize) + align(ticketSize) + align(tmdSize);
			int body = bodyOffset(ByteBuffer.wrap(tmd).getInt(0));
			ByteBuffer t = ByteBuffer.wrap(tmd);
			int count = t.getShort(body + 0x9E) & 0xFFFF;
			int records = body + 0xC4 + INFO_RECORDS * INFO_RECORD;
			if (records + (long) count * CHUNK_RECORD > tmd.length) {
				throw new IOException(file.getFileName() + "'s TMD is cut short");
			}
			List<Content> contents = new ArrayList<>();
			long at = contentsAt;
			for (int i = 0; i < count; i++) {
				int r = records + i * CHUNK_RECORD;
				int index = t.getShort(r + 4) & 0xFFFF;
				if ((all[0x20 + index / 8] & (0x80 >> (index % 8))) == 0) {
					// listed, but not in this file
					continue;
				}
				long size = t.getLong(r + 8);
				contents.add(new Content(t.getInt(r), index, t.getShort(r + 6) & 0xFFFF, size, at));
				at += size;
			}
			if (at - contentsAt != contentSize) {
				throw new IOException(file.getFileName() + "'s contents do not add up to the size its header gives");
			}
			byte[] meta = new byte[0];
			if (metaSize > 0) {
				try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
					ByteBuffer m = ByteBuffer.allocate(metaSize);
					ch.read(m, contentsAt + align(contentSize));
					meta = m.array();
				}
			}
			return new Cia(file, all, certs, ticket, tmd, meta, body, List.copyOf(contents));
		}
	}

	/** The title id the TMD is for. */
	public long titleId() {
		return ByteBuffer.wrap(tmd).getLong(body + 0x4C);
	}

	/** The title's version, as the TMD gives it ({@code v48}). */
	public int version() {
		return ByteBuffer.wrap(tmd).getShort(body + 0x9C) & 0xFFFF;
	}

	/** The contents the file holds, in its order. */
	public List<Content> contents() {
		return contents;
	}

	/** The content of index {@code index}, or null when the file does not hold it. */
	public Content content(int index) {
		return contents.stream().filter(c -> c.index() == index).findFirst().orElse(null);
	}

	/** The file the CIA is read from, which a rebuilt content's pieces are ranges of. */
	public Path file() {
		return file;
	}

	/** {@code c}'s data, as the file holds it. */
	public byte[] read(Content c) throws IOException {
		if (c.size() > Integer.MAX_VALUE - 16) {
			throw new IOException("content " + c.index() + " is too large to read whole");
		}
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer b = ByteBuffer.allocate((int) c.size());
			while (b.hasRemaining() && ch.read(b, c.offset() + b.position()) > 0) {
				// reading on
			}
			if (b.hasRemaining()) {
				throw new IOException(file.getFileName() + " ends inside content " + c.index());
			}
			return b.array();
		}
	}

	/** {@code ncch}, whole, as a rebuilt content to write in place of one. */
	public static Ncch.Rebuilt whole(byte[] ncch) {
		return new Ncch.Rebuilt(List.of(new Part.Bytes(ncch)), ncch.length, RomfsImage.sha256().digest(ncch));
	}

	/**
	 * Writes a copy of the CIA to {@code out}, each content {@code replaced} has an index for rebuilt
	 * (its pieces read from this CIA's file), decrypted (its encrypted flag cleared), its size and
	 * hash in the TMD made to match; every other content is copied as it is. {@code read} hears how
	 * much was read.
	 */
	public void write(OutputStream out, Map<Integer, Ncch.Rebuilt> replaced, LongConsumer read) throws IOException {
		byte[] newTmd = tmd.clone();
		ByteBuffer t = ByteBuffer.wrap(newTmd);
		int records = body + 0xC4 + INFO_RECORDS * INFO_RECORD;
		int count = t.getShort(body + 0x9E) & 0xFFFF;
		long total = 0;
		for (int i = 0; i < count; i++) {
			int r = records + i * CHUNK_RECORD;
			int index = t.getShort(r + 4) & 0xFFFF;
			Ncch.Rebuilt rebuilt = replaced.get(index);
			if (rebuilt != null) {
				t.putShort(r + 6, (short) (t.getShort(r + 6) & ~1));
				t.putLong(r + 8, rebuilt.size());
				System.arraycopy(rebuilt.sha256(), 0, newTmd, r + 16, 32);
			}
		}
		for (Content c : contents) {
			total += replaced.containsKey(c.index()) ? replaced.get(c.index()).size() : c.size();
		}
		// each info record hashes the records it covers, and the body hashes the info records
		for (int k = 0; k < INFO_RECORDS; k++) {
			int info = body + 0xC4 + k * INFO_RECORD;
			int first = t.getShort(info) & 0xFFFF;
			int n = t.getShort(info + 2) & 0xFFFF;
			if (n > 0) {
				var sha = RomfsImage.sha256();
				sha.update(newTmd, records + first * CHUNK_RECORD, n * CHUNK_RECORD);
				System.arraycopy(sha.digest(), 0, newTmd, info + 4, 32);
			}
		}
		var sha = RomfsImage.sha256();
		sha.update(newTmd, body + 0xC4, INFO_RECORDS * INFO_RECORD);
		System.arraycopy(sha.digest(), 0, newTmd, body + 0xA4, 32);

		byte[] newHeader = header.clone();
		ByteBuffer.wrap(newHeader).order(ByteOrder.LITTLE_ENDIAN).putLong(0x18, total);
		out.write(newHeader);
		pad(out, 0x2020);
		out.write(certs);
		pad(out, certs.length);
		out.write(ticket);
		pad(out, ticket.length);
		out.write(newTmd);
		pad(out, newTmd.length);
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			Source s = Source.of(ch);
			for (Content c : contents) {
				Ncch.Rebuilt rebuilt = replaced.get(c.index());
				Part.write(s, rebuilt != null ? rebuilt.parts() : List.of(new Part.Range(c.offset(), c.offset() + c.size())), out,
						read);
			}
		}
		pad(out, total);
		out.write(meta);
	}

	/** Where a TMD's body starts after a signature of {@code type}. */
	private static int bodyOffset(int type) throws IOException {
		return switch (type) {
			case 0x10000, 0x10003 -> 0x240;
			case 0x10001, 0x10004 -> 0x140;
			case 0x10002, 0x10005 -> 0x80;
			default -> throw new IOException(String.format("unknown TMD signature type %#x", type));
		};
	}

	private static long align(long x) {
		return (x + ALIGN - 1) / ALIGN * ALIGN;
	}

	private static void pad(OutputStream out, long written) throws IOException {
		out.write(new byte[(int) (align(written) - written)]);
	}

	private static void skip(InputStream in, long n) throws IOException {
		try {
			in.skipNBytes(n);
		} catch (java.io.EOFException e) {
			throw new IOException("the CIA ends early", e);
		}
	}
}
