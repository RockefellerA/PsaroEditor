package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DARC, the archive inside every romfs {@code *.arc.lz}.
 *
 * <pre>
 * 0x00  "darc", BOM FFFE, header size 0x1C, version 0x01000000,
 *       file size, table offset (0x1C), table length, data offset
 * table: 12-byte entries, then UTF-16LE NUL-terminated names.
 *       entry = (name offset | 0x01000000 for a directory,
 *                data offset, or parent index for a directory,
 *                size, or index one past the directory's last descendant)
 * </pre>
 *
 * Data starts at the table end rounded up to 4. Textures ({@code .bclim}) and fonts
 * ({@code .bcfnt}) are aligned to 0x80 for the GPU, everything else to 4. The root entry
 * is named "" and holds a single "." directory.
 */
public final class Darc {

	private static final int HEADER_SIZE = 0x1C;
	private static final int DIR_FLAG = 0x01000000;

	private Darc() {
	}

	/** A file (data set, children null) or a directory (children set). */
	public static final class Node {
		public final String name;
		public byte[] data;
		public final List<Node> children;

		private Node(String name, byte[] data, List<Node> children) {
			this.name = name;
			this.data = data;
			this.children = children;
		}

		public static Node file(String name, byte[] data) {
			return new Node(name, data, null);
		}

		public static Node dir(String name) {
			return new Node(name, null, new ArrayList<>());
		}

		public boolean isDir() {
			return children != null;
		}
	}

	public static Node read(byte[] d) {
		if (!Bytes.magic(d, 0, "darc")) {
			throw new IllegalArgumentException("not a DARC archive");
		}
		int tableOff = Bytes.u32(d, 0x10);
		int count = Bytes.u32(d, tableOff + 8);
		int namesBase = tableOff + count * 12;
		int[] next = {0};
		return readEntry(d, tableOff, namesBase, next);
	}

	private static Node readEntry(byte[] d, int tableOff, int namesBase, int[] index) {
		int i = index[0];
		int e = tableOff + i * 12;
		int nameWord = Bytes.u32(d, e);
		int off = Bytes.u32(d, e + 4);
		int size = Bytes.u32(d, e + 8);
		String name = Bytes.utf16(d, namesBase + (nameWord & 0xFFFFFF), d.length);
		if ((nameWord & DIR_FLAG) == 0) {
			index[0] = i + 1;
			byte[] data = new byte[size];
			System.arraycopy(d, off, data, 0, size);
			return Node.file(name, data);
		}
		Node dir = Node.dir(name);
		index[0] = i + 1;
		while (index[0] < size) {
			dir.children.add(readEntry(d, tableOff, namesBase, index));
		}
		index[0] = size;
		return dir;
	}

	public static byte[] write(Node root) {
		List<Node> entries = new ArrayList<>();
		List<Integer> parents = new ArrayList<>();
		List<Integer> ends = new ArrayList<>();
		flatten(root, 0, entries, parents, ends);

		Buf names = new Buf(entries.size() * 32);
		int[] nameOffs = new int[entries.size()];
		for (int i = 0; i < entries.size(); i++) {
			nameOffs[i] = names.size();
			names.put(entries.get(i).name.getBytes(StandardCharsets.UTF_16LE)).putShort(0);
		}
		int tableLen = entries.size() * 12 + names.size();
		int dataOff = align(HEADER_SIZE + tableLen, 4);

		int[] offsets = new int[entries.size()];
		int pos = dataOff;
		for (int i = 0; i < entries.size(); i++) {
			Node n = entries.get(i);
			if (n.isDir()) {
				continue;
			}
			pos = align(pos, alignmentFor(n.name));
			offsets[i] = pos;
			pos += n.data.length;
		}
		int total = pos;

		Buf out = new Buf(total);
		out.put('d').put('a').put('r').put('c').putShort(0xFEFF).putShort(HEADER_SIZE)
				.putInt(0x01000000).putInt(total).putInt(HEADER_SIZE).putInt(tableLen).putInt(dataOff);
		for (int i = 0; i < entries.size(); i++) {
			Node n = entries.get(i);
			if (n.isDir()) {
				out.putInt(nameOffs[i] | DIR_FLAG).putInt(parents.get(i)).putInt(ends.get(i));
			} else {
				out.putInt(nameOffs[i]).putInt(offsets[i]).putInt(n.data.length);
			}
		}
		out.put(names.toArray());
		for (int i = 0; i < entries.size(); i++) {
			Node n = entries.get(i);
			if (!n.isDir()) {
				out.padTo(offsets[i]).put(n.data);
			}
		}
		return out.toArray();
	}

	private static void flatten(Node node, int parent, List<Node> entries, List<Integer> parents, List<Integer> ends) {
		int idx = entries.size();
		entries.add(node);
		parents.add(parent);
		ends.add(0);
		if (node.isDir()) {
			for (Node c : node.children) {
				flatten(c, idx, entries, parents, ends);
			}
			ends.set(idx, entries.size());
		}
	}

	/** Every file keyed by its path inside the archive, e.g. {@code font/x.bcfnt}, in table order. */
	public static Map<String, Node> files(Node root) {
		Map<String, Node> out = new LinkedHashMap<>();
		collect(root, "", out);
		return out;
	}

	private static void collect(Node dir, String prefix, Map<String, Node> out) {
		for (Node c : dir.children) {
			boolean transparent = c.name.isEmpty() || c.name.equals(".");
			if (c.isDir()) {
				collect(c, transparent ? prefix : prefix + c.name + "/", out);
			} else {
				out.put(prefix + c.name, c);
			}
		}
	}

	public static Node find(Node root, String path) {
		return files(root).get(path);
	}

	private static int alignmentFor(String name) {
		String lower = name.toLowerCase();
		return lower.endsWith(".bclim") || lower.endsWith(".bcfnt") ? 0x80 : 4;
	}

	private static int align(int n, int a) {
		return (n + a - 1) / a * a;
	}
}
