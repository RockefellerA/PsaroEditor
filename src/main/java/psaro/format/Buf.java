package psaro.format;

import java.util.Arrays;

/** Growable little-endian byte buffer for the format writers. */
final class Buf {

	private byte[] data;
	private int size;

	Buf(int capacity) {
		data = new byte[Math.max(capacity, 16)];
	}

	int size() {
		return size;
	}

	Buf put(int b) {
		ensure(size + 1);
		data[size++] = (byte) b;
		return this;
	}

	Buf putShort(int v) {
		return put(v & 0xFF).put(v >> 8 & 0xFF);
	}

	Buf putInt(int v) {
		return putShort(v & 0xFFFF).putShort(v >>> 16);
	}

	Buf put(byte[] bytes) {
		return put(bytes, 0, bytes.length);
	}

	Buf put(byte[] bytes, int off, int len) {
		ensure(size + len);
		System.arraycopy(bytes, off, data, size, len);
		size += len;
		return this;
	}

	/** Zero bytes up to the next multiple of {@code alignment}. */
	Buf align(int alignment) {
		while (size % alignment != 0) {
			put(0);
		}
		return this;
	}

	/** Zero bytes up to absolute offset {@code offset}. */
	Buf padTo(int offset) {
		while (size < offset) {
			put(0);
		}
		return this;
	}

	void set(int at, int b) {
		data[at] = (byte) b;
	}

	void setShort(int at, int v) {
		data[at] = (byte) v;
		data[at + 1] = (byte) (v >> 8);
	}

	void setInt(int at, int v) {
		setShort(at, v & 0xFFFF);
		setShort(at + 2, v >>> 16);
	}

	byte[] toArray() {
		return Arrays.copyOf(data, size);
	}

	private void ensure(int capacity) {
		if (capacity > data.length) {
			data = Arrays.copyOf(data, Math.max(capacity, data.length * 2));
		}
	}
}
