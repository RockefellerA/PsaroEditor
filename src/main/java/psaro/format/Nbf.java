package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lost Heroes' {@code 2NBF} bundles, what most of its {@link Comp} files hold: the files one scene
 * or screen loads together (models, textures, scripts, message tables), each by its path in the
 * romfs, as a copy of the loose file there.
 *
 * <pre>
 * header: "2NBF", u32 file count, u32 where the data starts, u32 0x10 (the header's size)
 * entry:  0x80-byte ASCII path (with / or \), u32 offset from the data's start, u32 0, u32 size
 * data:   each file in entry order, starting on 16 bytes; the whole padded to 16
 * </pre>
 */
public final class Nbf {

	private static final String MAGIC = "2NBF";
	private static final int HEADER = 0x10;
	private static final int NAME = 0x80;
	private static final int ENTRY = NAME + 12;
	private static final int ALIGN = 16;

	/** One file of a bundle: its path as the bundle spells it, and its bytes. */
	public record Member(String name, byte[] data) {

		/** Its path with forward slashes, as a romfs path is spelled. */
		public String path() {
			return name.replace('\\', '/');
		}
	}

	private Nbf() {
	}

	public static boolean is(byte[] d) {
		return d.length >= HEADER && Bytes.magic(d, 0, MAGIC) && Bytes.u32(d, 12) == HEADER;
	}

	/** How many bytes from the start of a bundle hold its entries, given its first 16 bytes. */
	public static int headerSize(byte[] head) {
		if (!is(head)) {
			throw new IllegalArgumentException("not a 2NBF bundle");
		}
		return HEADER + Bytes.u32(head, 4) * ENTRY;
	}

	/** The paths of a bundle's files, from its start up to {@link #headerSize} at least, with forward slashes. */
	public static List<String> paths(byte[] head) {
		int count = (headerSize(head) - HEADER) / ENTRY;
		List<String> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			out.add(Bytes.ascii(head, HEADER + i * ENTRY).replace('\\', '/'));
		}
		return out;
	}

	public static List<Member> read(byte[] d) {
		int count = (headerSize(d) - HEADER) / ENTRY;
		int data = Bytes.u32(d, 8);
		List<Member> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int e = HEADER + i * ENTRY;
			String name = Bytes.ascii(d, e);
			int at = data + Bytes.u32(d, e + NAME);
			out.add(new Member(name, Arrays.copyOfRange(d, at, at + Bytes.u32(d, e + NAME + 8))));
		}
		return out;
	}

	/** A bundle of {@code members}, laid out as the game's are. */
	public static byte[] write(List<Member> members) {
		int data = align(HEADER + members.size() * ENTRY);
		Buf out = new Buf(data);
		out.put(MAGIC.getBytes(StandardCharsets.US_ASCII)).putInt(members.size()).putInt(data).putInt(HEADER);
		int offset = 0;
		for (Member m : members) {
			byte[] name = m.name().getBytes(StandardCharsets.US_ASCII);
			if (name.length >= NAME) {
				throw new IllegalArgumentException(m.name() + ": a path holds at most " + (NAME - 1) + " characters");
			}
			int entry = out.size();
			out.put(name).padTo(entry + NAME);
			offset = align(offset);
			out.putInt(offset).putInt(0).putInt(m.data().length);
			offset += m.data().length;
		}
		out.padTo(data);
		for (Member m : members) {
			out.align(ALIGN).put(m.data());
		}
		return out.align(ALIGN).toArray();
	}

	private static int align(int n) {
		return (n + ALIGN - 1) / ALIGN * ALIGN;
	}
}
