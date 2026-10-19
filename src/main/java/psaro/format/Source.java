package psaro.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Where bytes are read from by position, as a {@link FileChannel} reads them: a title's file,
 * read a piece at a time, so one of gigabytes is never held whole.
 */
@FunctionalInterface
public interface Source {

	/** Reads into {@code dst} from {@code position}, returning how many bytes, or -1 at the end. */
	int read(ByteBuffer dst, long position) throws IOException;

	/** The bytes of {@code data}. */
	static Source of(byte[] data) {
		return (dst, position) -> {
			if (position >= data.length) {
				return -1;
			}
			int n = (int) Math.min(dst.remaining(), data.length - position);
			dst.put(data, (int) position, n);
			return n;
		};
	}

	/** The bytes of a file open for reading. */
	static Source of(FileChannel channel) {
		return channel::read;
	}

	/** {@code n} bytes from {@code at}; fewer only at the end, which is an error. */
	default byte[] read(long at, int n) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(n);
		while (b.hasRemaining()) {
			int got = read(b, at + b.position());
			if (got <= 0) {
				throw new IOException("the file ends early, at " + (at + b.position()));
			}
		}
		return b.array();
	}
}
