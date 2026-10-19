package psaro.format;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * A piece of a title being rebuilt: bytes held here, or a range of the file it is rebuilt from,
 * read only when written or hashed. A rebuilt content is a list of them, so a game's romfs of a
 * gigabyte is written from its original file with only the changed files and the hashes held.
 */
public sealed interface Part {

	/** How many bytes it is. */
	long size();

	/** Bytes held here. */
	record Bytes(byte[] data) implements Part {
		@Override
		public long size() {
			return data.length;
		}
	}

	/** Bytes {@code start} to {@code end} of the source. */
	record Range(long start, long end) implements Part {
		@Override
		public long size() {
			return end - start;
		}
	}

	/** {@code n} zero bytes. */
	static Part zeros(long n) {
		return new Bytes(new byte[Math.toIntExact(n)]);
	}

	/** The pieces' sizes together. */
	static long size(List<Part> parts) {
		return parts.stream().mapToLong(Part::size).sum();
	}

	/** How much is read at once from a range. */
	int CHUNK = 8 << 20;

	/** Something done with the pieces' bytes in order, in chunks of at most {@link #CHUNK}. */
	@FunctionalInterface
	interface Chunks {
		void accept(byte[] data, int length) throws IOException;
	}

	/** Hands every byte of {@code parts} to {@code chunks}, in order, reading the ranges from {@code source}; {@code read} hears how much was read. */
	static void each(Source source, List<Part> parts, Chunks chunks, LongConsumer read) throws IOException {
		byte[] buf = null;
		for (Part p : parts) {
			if (p instanceof Bytes b) {
				if (b.data().length > 0) {
					chunks.accept(b.data(), b.data().length);
				}
				continue;
			}
			Range r = (Range) p;
			if (buf == null) {
				buf = new byte[CHUNK];
			}
			for (long at = r.start(); at < r.end();) {
				ByteBuffer dst = ByteBuffer.wrap(buf, 0, (int) Math.min(CHUNK, r.end() - at));
				while (dst.hasRemaining()) {
					if (source.read(dst, at + dst.position()) <= 0) {
						throw new IOException("the file ends early, at " + (at + dst.position()));
					}
				}
				chunks.accept(buf, dst.position());
				read.accept(dst.position());
				at += dst.position();
			}
		}
	}

	/** Writes {@code parts} to {@code out}. */
	static void write(Source source, List<Part> parts, OutputStream out, LongConsumer read) throws IOException {
		each(source, parts, (d, n) -> out.write(d, 0, n), read);
	}

	/** The bytes of {@code parts}, which must be under 2 GB together. */
	static byte[] bytes(Source source, List<Part> parts) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.toIntExact(size(parts)));
		write(source, parts, out, n -> { });
		return out.toByteArray();
	}

	/** SHA-256 of {@code parts}. */
	static byte[] sha256(Source source, List<Part> parts, LongConsumer read) throws IOException {
		MessageDigest sha = RomfsImage.sha256();
		each(source, parts, (d, n) -> sha.update(d, 0, n), read);
		return sha.digest();
	}

	/** SHA-256 of each {@code block}-sized piece of {@code parts}, the last padded with zeros; one block for none. */
	static byte[] blockHashes(Source source, List<Part> parts, int block, LongConsumer read) throws IOException {
		List<byte[]> out = new ArrayList<>();
		MessageDigest sha = RomfsImage.sha256();
		long[] fill = {0};
		each(source, parts, (d, n) -> {
			int i = 0;
			while (i < n) {
				int take = (int) Math.min(block - fill[0], n - i);
				sha.update(d, i, take);
				fill[0] += take;
				i += take;
				if (fill[0] == block) {
					out.add(sha.digest());
					fill[0] = 0;
				}
			}
		}, read);
		if (fill[0] > 0 || out.isEmpty()) {
			sha.update(new byte[(int) (block - fill[0])]);
			out.add(sha.digest());
		}
		byte[] all = new byte[out.size() * 32];
		for (int k = 0; k < out.size(); k++) {
			System.arraycopy(out.get(k), 0, all, k * 32, 32);
		}
		return all;
	}
}
