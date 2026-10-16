package psaro.romfs;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds small synthetic CRI files for tests: a {@code .cpk} ({@link psaro.format.Cpk}), its
 * {@code CRILAYLA} compression, and the text files SD Gundam G Generation 3D keeps in one.
 */
public final class SampleCpk {

	private static final Charset SHIFT_JIS = Charset.forName("windows-31j");
	private static final int ALIGN = 0x800;

	private SampleCpk() {
	}

	/**
	 * A CPK holding {@code files} (path to bytes), in order: each of 0x100 bytes or more
	 * {@link #crilayla compressed} when {@code compress}, its tables scrambled when {@code scramble}.
	 */
	public static byte[] cpk(Map<String, byte[]> files, boolean compress, boolean scramble) {
		return cpk(files, compress, scramble, null);
	}

	/** As {@link #cpk(Map, boolean, boolean)}, with {@code etoc} after the files as a game's ETOC is, when given. */
	public static byte[] cpk(Map<String, byte[]> files, boolean compress, boolean scramble, byte[] etoc) {
		int toc = ALIGN;
		List<byte[]> stored = new ArrayList<>();
		List<List<Object>> rows = new ArrayList<>();
		long at = 0;
		for (Map.Entry<String, byte[]> f : files.entrySet()) {
			byte[] d = compress && f.getValue().length >= 0x100 ? crilayla(f.getValue()) : f.getValue();
			stored.add(d);
			int slash = f.getKey().lastIndexOf('/');
			rows.add(List.of(slash < 0 ? "" : f.getKey().substring(0, slash), f.getKey().substring(slash + 1), d.length,
					f.getValue().length, at, rows.size()));
			at += align(d.length);
		}
		List<Column> tocColumns = List.of(new Column("DirName", 0x5A, null), new Column("FileName", 0x5A, null),
				new Column("FileSize", 0x54, null), new Column("ExtractSize", 0x54, null),
				new Column("FileOffset", 0x56, null), new Column("ID", 0x54, null), new Column("UserString", 0x3A, "<NULL>"));
		byte[] tocChunk = chunk("TOC ", utf("CpkTocInfo", tocColumns, rows), scramble);
		int content = toc + align(tocChunk.length);
		// file offsets count from the TOC, the lower of the two
		List<List<Object>> moved = new ArrayList<>();
		for (List<Object> r : rows) {
			List<Object> m = new ArrayList<>(r);
			m.set(4, (long) r.get(4) + content - toc);
			moved.add(m);
		}
		tocChunk = chunk("TOC ", utf("CpkTocInfo", tocColumns, moved), scramble);
		long etocAt = etoc == null ? 0 : content + at;
		byte[] header = chunk("CPK ", utf("CpkHeader", List.of(new Column("ContentOffset", 0x56, null),
				new Column("ContentSize", 0x56, null), new Column("TocOffset", 0x56, null),
				new Column("EtocOffset", 0x56, null), new Column("Files", 0x54, null), new Column("Comment", 0x1A, null)),
				List.of(List.of((long) content, at, (long) toc, etocAt, files.size()))), scramble);
		ByteBuffer out = ByteBuffer.allocate(content + (int) at + (etoc == null ? 0 : etoc.length));
		out.put(0, header).put(toc, tocChunk);
		if (etoc != null) {
			out.put((int) etocAt, etoc);
		}
		int p = content;
		for (byte[] d : stored) {
			out.put(p, d);
			p += align(d.length);
		}
		return out.array();
	}

	/** An @UTF table column: its name, its flags (how it is kept and its type), and its value when every row shares one. */
	private record Column(String name, int flags, Object constant) {
	}

	private static byte[] chunk(String tag, byte[] utf, boolean scramble) {
		if (scramble) {
			int key = 0x655F;
			for (int i = 0; i < utf.length; i++) {
				utf[i] ^= (byte) key;
				key *= 0x4115;
			}
		}
		ByteBuffer b = ByteBuffer.allocate(16 + utf.length).order(ByteOrder.LITTLE_ENDIAN);
		b.put(tag.getBytes(StandardCharsets.US_ASCII)).putInt(0xFF).putLong(utf.length).put(utf);
		return b.array();
	}

	/** An @UTF table: big-endian, its per-row columns' values in {@code rows}, in column order. */
	private static byte[] utf(String name, List<Column> columns, List<List<Object>> rows) {
		ByteArrayOutputStream strings = new ByteArrayOutputStream();
		Map<String, Integer> pool = new HashMap<>();
		java.util.function.Function<String, Integer> string = s -> pool.computeIfAbsent(s, k -> {
			int o = strings.size();
			strings.writeBytes((k + "\0").getBytes(SHIFT_JIS));
			return o;
		});
		string.apply("<NULL>");
		int nameAt = string.apply(name);
		ByteBuffer cols = ByteBuffer.allocate(columns.size() * 13);
		int rowLength = 0;
		for (Column c : columns) {
			cols.put((byte) c.flags()).putInt(string.apply(c.name()));
			if (c.flags() >> 4 == 3) {
				put(cols, c.flags() & 0xF, c.constant(), string);
			} else if (c.flags() >> 4 == 5) {
				rowLength += size(c.flags() & 0xF);
			}
		}
		ByteBuffer body = ByteBuffer.allocate(rowLength * rows.size());
		for (List<Object> r : rows) {
			int v = 0;
			for (Column c : columns) {
				if (c.flags() >> 4 == 5) {
					put(body, c.flags() & 0xF, r.get(v++), string);
				}
			}
		}
		int rowsAt = 24 + cols.position();
		int stringsAt = rowsAt + body.capacity();
		int dataAt = stringsAt + strings.size();
		ByteBuffer t = ByteBuffer.allocate(8 + dataAt);
		t.put("@UTF".getBytes(StandardCharsets.US_ASCII)).putInt(dataAt).putInt(rowsAt).putInt(stringsAt).putInt(dataAt)
				.putInt(nameAt).putShort((short) columns.size()).putShort((short) rowLength).putInt(rows.size())
				.put(cols.array(), 0, cols.position()).put(body.array()).put(strings.toByteArray());
		return t.array();
	}

	private static int size(int type) {
		return switch (type) {
			case 4, 0xA -> 4;
			case 6 -> 8;
			default -> throw new IllegalArgumentException("type " + type);
		};
	}

	private static void put(ByteBuffer b, int type, Object v, java.util.function.Function<String, Integer> string) {
		switch (type) {
			case 4 -> b.putInt(((Number) v).intValue());
			case 6 -> b.putLong(((Number) v).longValue());
			case 0xA -> b.putInt(string.apply((String) v));
			default -> throw new IllegalArgumentException("type " + type);
		}
	}

	private static int align(int n) {
		return (n + ALIGN - 1) / ALIGN * ALIGN;
	}

	/**
	 * {@code d} (0x100 bytes or more) {@code CRILAYLA}-compressed, greedily: each byte, from the
	 * last, starts the longest copy it can of what is after it, or is a literal.
	 */
	public static byte[] crilayla(byte[] d) {
		if (d.length < 0x100) {
			throw new IllegalArgumentException("too short to compress");
		}
		int n = d.length - 0x100;
		Bits bits = new Bits();
		int j = n - 1;
		while (j >= 0) {
			int best = 0;
			int bestDistance = 0;
			for (int distance = 3; distance <= 0x1FFF + 3 && j + distance < n; distance++) {
				int length = 0;
				while (length <= j && d[0x100 + j - length] == d[0x100 + j + distance - length]) {
					length++;
				}
				if (length > best) {
					best = length;
					bestDistance = distance;
				}
			}
			if (best < 3) {
				bits.put(0, 1);
				bits.put(d[0x100 + j] & 0xFF, 8);
				j--;
				continue;
			}
			bits.put(1, 1);
			bits.put(bestDistance - 3, 13);
			int rest = best - 3;
			boolean done = false;
			for (int width : new int[] {2, 3, 5, 8}) {
				int max = (1 << width) - 1;
				if (rest < max) {
					bits.put(rest, width);
					done = true;
					break;
				}
				bits.put(max, width);
				rest -= max;
			}
			if (!done) {
				while (rest >= 0xFF) {
					bits.put(0xFF, 8);
					rest -= 0xFF;
				}
				bits.put(rest, 8);
			}
			j -= best;
		}
		byte[] packed = bits.bytes();
		ByteBuffer out = ByteBuffer.allocate(0x10 + packed.length + 0x100).order(ByteOrder.LITTLE_ENDIAN);
		out.put("CRILAYLA".getBytes(StandardCharsets.US_ASCII)).putInt(n).putInt(packed.length);
		// read from the end backwards
		for (int i = packed.length - 1; i >= 0; i--) {
			out.put(packed[i]);
		}
		out.put(d, 0, 0x100);
		return out.array();
	}

	/** Bits written top first into bytes, in the order they are read. */
	private static final class Bits {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();
		private int pool;
		private int count;

		void put(int value, int width) {
			for (int i = width - 1; i >= 0; i--) {
				pool = pool << 1 | value >> i & 1;
				if (++count == 8) {
					out.write(pool);
					pool = 0;
					count = 0;
				}
			}
		}

		byte[] bytes() {
			if (count > 0) {
				out.write(pool << 8 - count);
				pool = 0;
				count = 0;
			}
			return out.toByteArray();
		}
	}

	/**
	 * A record table ({@link psaro.format.FieldTable}) of {@code size}-byte records, its text
	 * fields at {@code fields}; each row the record's strings, one per field, its number at 0.
	 */
	public static byte[] fieldTable(int size, int[] fields, List<String[]> rows) {
		ByteArrayOutputStream text = new ByteArrayOutputStream();
		int recordsAt = 20 + 4 * fields.length;
		ByteBuffer b = ByteBuffer.allocate(recordsAt + size * rows.size()).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(size).putInt(rows.size()).putInt(recordsAt).putInt(recordsAt + size * rows.size()).putInt(fields.length);
		for (int f : fields) {
			b.putInt(f);
		}
		for (int r = 0; r < rows.size(); r++) {
			int at = recordsAt + r * size;
			b.putInt(at, r);
			for (int f = 0; f < fields.length; f++) {
				b.putInt(at + fields[f], text.size());
				text.writeBytes(SampleRomfs.shiftJis(rows.get(r)[f]));
			}
		}
		return concat(b.array(), text.toByteArray());
	}

	/** A file of texts ({@link psaro.format.TextBlocks}): each text's lines CRLF-ended, then {@code ;} NUL; each numbered by its place. */
	public static byte[] textBlocks(List<String> texts) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		ByteBuffer head = ByteBuffer.allocate(4 + 8 * texts.size()).order(ByteOrder.LITTLE_ENDIAN).putInt(texts.size());
		for (int i = 0; i < texts.size(); i++) {
			String t = texts.get(i);
			byte[] bytes = ((t.isEmpty() ? "" : t.replace("\n", "\r\n") + "\r\n") + ";\0").getBytes(SHIFT_JIS);
			head.putInt(t.isEmpty() ? -1 : i).putInt(bytes.length);
			body.writeBytes(bytes);
		}
		return concat(head.array(), body.toByteArray());
	}

	/** A map script's line ({@link psaro.format.MapScript}): 0x1D, its length, Shift-JIS, {@code %} NUL; {@code \n} written as a backslash and n. */
	public static byte[] mapLine(String text) {
		byte[] t = (text.replace("\n", "\\n") + "%\0").getBytes(SHIFT_JIS);
		return concat(new byte[] {0x1D, (byte) t.length}, t);
	}

	/**
	 * A THSS data file and its pointer list ({@link psaro.format.Thss}), {@code {dat, ofs}}: a
	 * pointer to each of {@code strings}, a second pointer to the first, a pointer to a list
	 * holding one pointer whose bytes read as a kanji ({@code 8C F7 00 00}), and a pointer to a
	 * lone half-width katakana byte; then the list, the byte and the strings.
	 */
	public static byte[][] thss(List<String> strings) {
		int pointers = 0x10;
		int list = pointers + 4 * (strings.size() + 3);
		int lone = list + 4;
		int text = lone + 4;
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<Integer> at = new ArrayList<>();
		for (String s : strings) {
			at.add(text + body.size());
			body.writeBytes(SampleRomfs.shiftJis(s));
		}
		ByteBuffer dat = ByteBuffer.allocate(text + body.size()).order(ByteOrder.LITTLE_ENDIAN);
		dat.put("THSS".getBytes(StandardCharsets.US_ASCII)).putInt(0x1000).putInt(-1).put("1DBD".getBytes(StandardCharsets.US_ASCII));
		List<Integer> sites = new ArrayList<>();
		int p = pointers;
		for (int a : at) {
			sites.add(p);
			dat.putInt(p, a);
			p += 4;
		}
		for (int target : new int[] {at.get(0), list, lone}) {
			sites.add(p);
			dat.putInt(p, target);
			p += 4;
		}
		sites.add(list);
		dat.putInt(list, 0xF78C);
		dat.put(lone, (byte) 0xB9);
		dat.put(text, body.toByteArray());
		ByteBuffer ofs = ByteBuffer.allocate(8 + 4 * sites.size()).order(ByteOrder.LITTLE_ENDIAN);
		ofs.put("FHSS".getBytes(StandardCharsets.US_ASCII)).putInt(sites.size());
		sites.forEach(ofs::putInt);
		return new byte[][] {dat.array(), ofs.array()};
	}

	public static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			out.writeBytes(p);
		}
		return out.toByteArray();
	}
}
