package psaro.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BCLYT layouts: the font list and every pane, with the details of text panes.
 *
 * <p>Every pane (pan1, pic1, txt1, wnd1, bnd1, prt1) begins alike: 0x0C name (16 bytes), 0x24
 * translation xyz, 0x30 rotation xyz, 0x3C scale xy, 0x44 size w h ({@link #withPanes}).
 *
 * <p>txt1 layout, offsets from the section start: 0x0C name (16 bytes), 0x24 translation xyz,
 * 0x30 rotation xyz, 0x3C scale xy, 0x44 box size w h, 0x4C text buffer bytes u16, string
 * bytes u16, material u16, font index u16 (into fnl1), 0x54 origin u8, line alignment u8,
 * 0x58 text offset, 0x5C top / bottom colour, 0x64 font size x y, 0x6C char spacing,
 * 0x70 line spacing.
 *
 * <p>The text a layout stores is mostly a placeholder ({@code *}); the game fills panes from
 * the {@code .tdt} that shares the archive's name, keyed by ids held in the pane's user data
 * (usd1). A pane no table fills shows the text it holds ({@link #withPaneText}). Lines break at {@code \n}, and wherever the next character would cross the box's
 * right edge, mid-word or not.
 */
public final class Bclyt {

	private static final Pattern KEY = Pattern.compile("[a-z]{3,4}_\\d{4}");

	/**
	 * Any pane. Text-pane fields are null / zero for other kinds. {@code parent} is the pane it
	 * hangs under (null at the root), {@code x} and {@code y} where it sits from there, its centre
	 * by default; {@code width} and {@code height} its size (a text pane's box).
	 */
	public record Pane(String kind, String name, List<String> keys, TextInfo text, String parent, float x, float y,
			float width, float height) {

		/** A pane whose size does not matter here. */
		public Pane(String kind, String name, List<String> keys, TextInfo text, String parent, float x, float y) {
			this(kind, name, keys, text, parent, x, y, 0, 0);
		}

		/** A picture: an image, or a window drawn from images, which {@link PaneOverride} can move and size. */
		public boolean isPicture() {
			return kind.equals("pic1") || kind.equals("wnd1");
		}

		public boolean isText() {
			return text != null;
		}

		/**
		 * Whether {@code other} sits where this does: under the same parent, at the same place give
		 * or take a drop shadow's offset of a pixel or two.
		 */
		public boolean stacksOn(Pane other) {
			return java.util.Objects.equals(parent, other.parent) && Math.abs(x - other.x) <= 4 && Math.abs(y - other.y) <= 4;
		}
	}

	/**
	 * A text pane's box and type settings. {@code textPosition} places the text block in the
	 * box: horizontal = position % 3 (0 left, 1 centre, 2 right), vertical = position / 3 (0
	 * top, 1 centre, 2 bottom). {@code lineAlignment} aligns each line within the block: 0
	 * follows the horizontal position, 1 left, 2 centre, 3 right. Colours are RGBA, red in
	 * the top byte.
	 */
	public record TextInfo(String font, float boxWidth, float boxHeight, int bufferBytes,
			float fontSizeX, float fontSizeY, float charSpace, float lineSpace, String placeholder,
			int textPosition, int lineAlignment, int topColor, int bottomColor) {
	}

	public record Layout(List<String> fonts, List<Pane> panes) {
		public List<Pane> textPanes() {
			return panes.stream().filter(Pane::isText).toList();
		}
	}

	/**
	 * New values for a text pane's box and type settings; a null field keeps the layout's own.
	 * Character spacing and line spacing are in the same units as the box. {@code font} is
	 * another of the fonts the layout lists (fnl1), by file name. {@code drawWith} is what this
	 * pane alone draws with (a typeface's id, or the game font's), whatever its font is set to;
	 * null follows the font.
	 */
	public record TextOverride(Float boxWidth, Float boxHeight, Float fontSizeX, Float fontSizeY, Float charSpace,
			Float lineSpace, String font, String drawWith) {

		public static final TextOverride NONE = new TextOverride(null, null, null, null, null, null, null, null);

		/** A change that keeps the pane's font. */
		public TextOverride(Float boxWidth, Float boxHeight, Float fontSizeX, Float fontSizeY, Float charSpace,
				Float lineSpace) {
			this(boxWidth, boxHeight, fontSizeX, fontSizeY, charSpace, lineSpace, null, null);
		}

		/** A change that draws with what the pane's font is set to. */
		public TextOverride(Float boxWidth, Float boxHeight, Float fontSizeX, Float fontSizeY, Float charSpace,
				Float lineSpace, String font) {
			this(boxWidth, boxHeight, fontSizeX, fontSizeY, charSpace, lineSpace, font, null);
		}

		public boolean isEmpty() {
			return equals(NONE);
		}

		/** This change with {@code font} in place of its own. */
		public TextOverride withFont(String font) {
			return new TextOverride(boxWidth, boxHeight, fontSizeX, fontSizeY, charSpace, lineSpace, font, drawWith);
		}

		/** This change with {@code drawWith} in place of its own. */
		public TextOverride withDrawWith(String drawWith) {
			return new TextOverride(boxWidth, boxHeight, fontSizeX, fontSizeY, charSpace, lineSpace, font, drawWith);
		}

		/** {@code info} with this override's values in place of its own. */
		public TextInfo apply(TextInfo info) {
			return new TextInfo(font != null ? font : info.font(), or(boxWidth, info.boxWidth()), or(boxHeight, info.boxHeight()),
					info.bufferBytes(), or(fontSizeX, info.fontSizeX()), or(fontSizeY, info.fontSizeY()),
					or(charSpace, info.charSpace()), or(lineSpace, info.lineSpace()), info.placeholder(),
					info.textPosition(), info.lineAlignment(), info.topColor(), info.bottomColor());
		}

		private static float or(Float value, float original) {
			return value != null ? value : original;
		}
	}

	/**
	 * New values for where a pane sits and how big it is; a null field keeps the layout's own.
	 * {@code x} and {@code y} are its translation from its parent (y runs up), {@code width} and
	 * {@code height} its size: a picture's stretches its image to fit, a text pane's is its box.
	 */
	public record PaneOverride(Float x, Float y, Float width, Float height) {

		public static final PaneOverride NONE = new PaneOverride(null, null, null, null);

		public boolean isEmpty() {
			return equals(NONE);
		}

		/** {@code pane} where this override puts it, at the size it gives it. */
		public Pane apply(Pane pane) {
			return new Pane(pane.kind(), pane.name(), pane.keys(), pane.text(), pane.parent(),
					TextOverride.or(x, pane.x()), TextOverride.or(y, pane.y()), TextOverride.or(width, pane.width()),
					TextOverride.or(height, pane.height()));
		}
	}

	private Bclyt() {
	}

	public static Layout read(byte[] d) {
		if (!Bytes.magic(d, 0, "CLYT")) {
			throw new IllegalArgumentException("not a CLYT layout");
		}
		List<String> fonts = new ArrayList<>();
		List<Pane> panes = new ArrayList<>();
		// pas1 opens the children of the pane before it, pae1 closes them
		java.util.Deque<String> parents = new java.util.ArrayDeque<>();
		String last = null;
		int o = Bytes.u16(d, 6);
		while (o < d.length - 8) {
			int size = Bytes.u32(d, o + 4);
			if (size == 0) {
				break;
			}
			String tag = new String(d, o, 4, StandardCharsets.US_ASCII);
			switch (tag) {
				case "pas1" -> parents.push(last == null ? "" : last);
				case "pae1" -> parents.poll();
				case "fnl1" -> {
					int n = Bytes.u32(d, o + 8);
					for (int i = 0; i < n; i++) {
						fonts.add(Bytes.ascii(d, o + 12 + Bytes.u32(d, o + 12 + 4 * i)));
					}
				}
				case "pan1", "pic1", "txt1", "wnd1", "bnd1", "prt1" -> {
					String name = Bytes.ascii(d, o + 12);
					if (name.length() > 16) {
						name = name.substring(0, 16);
					}
					TextInfo info = null;
					if (tag.equals("txt1")) {
						int fontIndex = Bytes.u16(d, o + 0x52);
						int textOff = Bytes.u32(d, o + 0x58);
						info = new TextInfo(
								fontIndex < fonts.size() ? fonts.get(fontIndex) : null,
								Bytes.f32(d, o + 0x44), Bytes.f32(d, o + 0x48), Bytes.u16(d, o + 0x4C),
								Bytes.f32(d, o + 0x64), Bytes.f32(d, o + 0x68),
								Bytes.f32(d, o + 0x6C), Bytes.f32(d, o + 0x70),
								Bytes.utf16(d, o + textOff, o + size),
								Bytes.u8(d, o + 0x54), Bytes.u8(d, o + 0x55),
								rgba(d, o + 0x5C), rgba(d, o + 0x60));
					}
					panes.add(new Pane(tag, name, new ArrayList<>(), info, parents.peek(), Bytes.f32(d, o + 0x24),
							Bytes.f32(d, o + 0x28), Bytes.f32(d, o + 0x44), Bytes.f32(d, o + 0x48)));
					last = name;
				}
				case "usd1" -> {
					if (!panes.isEmpty()) {
						panes.get(panes.size() - 1).keys().addAll(textIds(d, o, size));
					}
				}
				default -> {
				}
			}
			o += size;
		}
		return new Layout(fonts, panes);
	}

	/**
	 * The image each picture of layout {@code d} draws ({@link Pane#isPicture}), by pane name: the
	 * file name, in the texture list, of the first texture its material maps. A window gives its
	 * content's. A picture whose material maps none is left out.
	 *
	 * <pre>
	 * txl1:     u32 count, then per texture u32 where its NUL-terminated name is, from after the count
	 * mat1:     u32 count, then per material u32 where it is, from the section's start
	 * material: 20-byte name, colours, 0x30 u32 flags (the low two bits: how many textures it maps),
	 *           0x34 per texture map u16 the texture's index in txl1, u8 u8 wrapping
	 * pic1:     0x5C u16 material
	 * wnd1:     0x60 u32 where its content is, from the section's start; the content's 0x10 u16 material
	 * </pre>
	 */
	public static Map<String, String> pictureImages(byte[] d) {
		if (!Bytes.magic(d, 0, "CLYT")) {
			throw new IllegalArgumentException("not a CLYT layout");
		}
		List<String> textures = new ArrayList<>();
		List<Integer> materials = new ArrayList<>();
		Map<String, String> out = new java.util.LinkedHashMap<>();
		int o = Bytes.u16(d, 6);
		while (o < d.length - 8) {
			int size = Bytes.u32(d, o + 4);
			if (size == 0) {
				break;
			}
			String tag = new String(d, o, 4, StandardCharsets.US_ASCII);
			switch (tag) {
				case "txl1" -> {
					int n = Bytes.u32(d, o + 8);
					for (int i = 0; i < n; i++) {
						textures.add(Bytes.ascii(d, o + 12 + Bytes.u32(d, o + 12 + 4 * i)));
					}
				}
				case "mat1" -> {
					int n = Bytes.u32(d, o + 8);
					for (int i = 0; i < n; i++) {
						materials.add(o + Bytes.u32(d, o + 12 + 4 * i));
					}
				}
				case "pic1", "wnd1" -> {
					int material = tag.equals("pic1") ? Bytes.u16(d, o + 0x5C) : Bytes.u16(d, o + Bytes.u32(d, o + 0x60) + 0x10);
					if (material < materials.size()) {
						int m = materials.get(material);
						int maps = Bytes.u32(d, m + 0x30) & 3;
						int texture = maps > 0 ? Bytes.u16(d, m + 0x34) : -1;
						if (texture >= 0 && texture < textures.size()) {
							String name = Bytes.ascii(d, o + 12);
							out.put(name.length() > 16 ? name.substring(0, 16) : name, textures.get(texture));
						}
					}
				}
				default -> {
				}
			}
			o += size;
		}
		return out;
	}

	/**
	 * The string keys in user data section {@code o}: the values of its {@code TextID} entries.
	 * The section is a u16 count, then 12-byte entries of name offset u32 and data offset u32
	 * (both from the entry), length u16, type u8 (0 string, 1 int, 2 float). Keys have three or
	 * four letters ({@code cmn_0004}, {@code clsm_0110}); a {@code TextIDSet} instead names
	 * lists the game fills the pane from at run time.
	 */
	private static List<String> textIds(byte[] d, int o, int size) {
		List<String> out = new ArrayList<>();
		int end = o + size;
		int n = Bytes.u16(d, o + 8);
		for (int i = 0; i < n; i++) {
			int entry = o + 12 + 12 * i;
			if (entry + 12 > end) {
				break;
			}
			int name = entry + Bytes.u32(d, entry);
			int data = entry + Bytes.u32(d, entry + 4);
			int length = Bytes.u16(d, entry + 8);
			if (Bytes.u8(d, entry + 10) == 0 && name < end && data + length <= end
					&& Bytes.ascii(d, name).equals("TextID")) {
				String key = new String(d, data, length, StandardCharsets.US_ASCII);
				if (KEY.matcher(key).matches()) {
					out.add(key);
				}
			}
		}
		return out;
	}

	/**
	 * A copy of layout {@code d} with each text pane named in {@code overrides} given its new
	 * settings. Only those floats and the font index change; every other byte stays as it was.
	 * A font the layout does not list is left as it was, since the pane can only point into
	 * fnl1.
	 */
	public static byte[] withText(byte[] d, Map<String, TextOverride> overrides) {
		byte[] out = d.clone();
		ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
		List<String> fonts = List.of();
		int o = Bytes.u16(d, 6);
		while (o < d.length - 8) {
			int size = Bytes.u32(d, o + 4);
			if (size == 0) {
				break;
			}
			if (Bytes.magic(d, o, "fnl1")) {
				fonts = read(d).fonts();
			} else if (Bytes.magic(d, o, "txt1")) {
				String name = Bytes.ascii(d, o + 12);
				TextOverride t = overrides.get(name.length() > 16 ? name.substring(0, 16) : name);
				if (t != null) {
					put(b, o + 0x44, t.boxWidth());
					put(b, o + 0x48, t.boxHeight());
					put(b, o + 0x64, t.fontSizeX());
					put(b, o + 0x68, t.fontSizeY());
					put(b, o + 0x6C, t.charSpace());
					put(b, o + 0x70, t.lineSpace());
					int font = t.font() == null ? -1 : fonts.indexOf(t.font());
					if (font >= 0) {
						b.putShort(o + 0x52, (short) font);
					}
				}
			}
			o += size;
		}
		return out;
	}

	/**
	 * A copy of layout {@code d} with each pane named in {@code overrides}, of any kind, moved and
	 * sized as it says: its translation x and y (0x24, 0x28) and its size (0x44, 0x48). Only those
	 * floats change; every other byte stays as it was.
	 */
	public static byte[] withPanes(byte[] d, Map<String, PaneOverride> overrides) {
		if (overrides.isEmpty()) {
			return d;
		}
		byte[] out = d.clone();
		ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
		int o = Bytes.u16(d, 6);
		while (o < d.length - 8) {
			int size = Bytes.u32(d, o + 4);
			if (size == 0) {
				break;
			}
			String tag = new String(d, o, 4, StandardCharsets.US_ASCII);
			if (List.of("pan1", "pic1", "txt1", "wnd1", "bnd1", "prt1").contains(tag)) {
				String name = Bytes.ascii(d, o + 12);
				PaneOverride p = overrides.get(name.length() > 16 ? name.substring(0, 16) : name);
				if (p != null) {
					put(b, o + 0x24, p.x());
					put(b, o + 0x28, p.y());
					put(b, o + 0x44, p.width());
					put(b, o + 0x48, p.height());
				}
			}
			o += size;
		}
		return out;
	}

	/**
	 * A copy of layout {@code d} with each text pane named in {@code texts} holding that text in
	 * place of its own: the text a pane shows when the game does not fill it from a table. Each
	 * such txt1 is rebuilt from its text offset on (UTF-16, a NUL, padding to four bytes), its
	 * string size following and its buffer size grown when the text needs more room; the file size
	 * in the header follows, every other byte staying as it was. The same array when nothing changes.
	 */
	public static byte[] withPaneText(byte[] d, Map<String, String> texts) {
		if (texts.isEmpty()) {
			return d;
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(d.length + 64);
		int o = Bytes.u16(d, 6);
		out.write(d, 0, o);
		boolean changed = false;
		while (o < d.length - 8) {
			int size = Bytes.u32(d, o + 4);
			if (size == 0) {
				break;
			}
			String text = null;
			if (Bytes.magic(d, o, "txt1")) {
				String name = Bytes.ascii(d, o + 12);
				text = texts.get(name.length() > 16 ? name.substring(0, 16) : name);
			}
			int textOff = text == null ? 0 : Bytes.u32(d, o + 0x58);
			if (text == null || text.equals(Bytes.utf16(d, o + textOff, o + size))) {
				out.write(d, o, size);
			} else {
				byte[] chars = text.getBytes(StandardCharsets.UTF_16LE);
				int strBytes = chars.length + 2;
				int newSize = (textOff + strBytes + 3) / 4 * 4;
				ByteBuffer section = ByteBuffer.allocate(newSize).order(ByteOrder.LITTLE_ENDIAN);
				section.put(d, o, textOff).put(chars);
				section.putInt(4, newSize);
				section.putShort(0x4C, (short) Math.max(Bytes.u16(d, o + 0x4C), strBytes));
				section.putShort(0x4E, (short) strBytes);
				out.writeBytes(section.array());
				changed = true;
			}
			o += size;
		}
		out.write(d, o, d.length - o);
		if (!changed) {
			return d;
		}
		byte[] bytes = out.toByteArray();
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x0C, bytes.length);
		return bytes;
	}

	/**
	 * A copy of layout {@code d} with the fonts its list (fnl1) names by a key of {@code renames}
	 * named by its value instead, so every pane drawing with one draws with the other. Font
	 * indices keep their places; only fnl1 is rebuilt, and the file size with it, every other
	 * section's bytes staying as they were. The same array when nothing is renamed.
	 *
	 * <p>fnl1: count u32, then per font an offset u32 from the start of that offset table to its
	 * name, then the names, NUL-terminated; padded to four bytes.
	 */
	public static byte[] withFontNames(byte[] d, Map<String, String> renames) {
		List<String> names = new ArrayList<>(read(d).fonts());
		boolean any = false;
		for (int i = 0; i < names.size(); i++) {
			String to = renames.get(names.get(i));
			if (to != null && !to.equals(names.get(i))) {
				names.set(i, to);
				any = true;
			}
		}
		return any ? withFontList(d, names) : d;
	}

	/**
	 * A copy of layout {@code d} with each text pane named in {@code fonts} drawing with the font
	 * it maps to: the index of that name in the font list, the name added at the end when the
	 * list lacks it, so the fonts already there keep their places. The same array when nothing
	 * changes.
	 */
	public static byte[] withPaneFonts(byte[] d, Map<String, String> fonts) {
		if (fonts.isEmpty()) {
			return d;
		}
		List<String> names = new ArrayList<>(read(d).fonts());
		for (String font : new java.util.TreeSet<>(fonts.values())) {
			if (!names.contains(font)) {
				names.add(font);
			}
		}
		byte[] out = names.size() == read(d).fonts().size() ? d.clone() : withFontList(d, names);
		ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
		int o = Bytes.u16(out, 6);
		while (o < out.length - 8) {
			int size = Bytes.u32(out, o + 4);
			if (size == 0) {
				break;
			}
			if (Bytes.magic(out, o, "txt1")) {
				String name = Bytes.ascii(out, o + 12);
				String font = fonts.get(name.length() > 16 ? name.substring(0, 16) : name);
				if (font != null) {
					b.putShort(o + 0x52, (short) names.indexOf(font));
				}
			}
			o += size;
		}
		return java.util.Arrays.equals(out, d) ? d : out;
	}

	/**
	 * Layout {@code d} with its font list (fnl1) holding {@code names}: the section rebuilt, every
	 * other section's bytes as they were, the file size in the header following.
	 */
	private static byte[] withFontList(byte[] d, List<String> names) {
		int o = Bytes.u16(d, 6);
		while (o < d.length - 8) {
			int size = Bytes.u32(d, o + 4);
			if (size == 0) {
				break;
			}
			if (Bytes.magic(d, o, "fnl1")) {
				int n = names.size();
				java.io.ByteArrayOutputStream strings = new java.io.ByteArrayOutputStream();
				ByteBuffer table = ByteBuffer.allocate(4 * n).order(ByteOrder.LITTLE_ENDIAN);
				for (String name : names) {
					table.putInt(4 * n + strings.size());
					strings.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
					strings.write(0);
				}
				int body = 4 + 4 * n + strings.size();
				int newSize = (8 + body + 3) / 4 * 4;
				ByteBuffer section = ByteBuffer.allocate(newSize).order(ByteOrder.LITTLE_ENDIAN);
				section.put("fnl1".getBytes(StandardCharsets.US_ASCII)).putInt(newSize).putInt(n).put(table.array())
						.put(strings.toByteArray());
				byte[] out = new byte[d.length - size + newSize];
				System.arraycopy(d, 0, out, 0, o);
				System.arraycopy(section.array(), 0, out, o, newSize);
				System.arraycopy(d, o + size, out, o + newSize, d.length - o - size);
				ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(0x0C, out.length);
				return out;
			}
			o += size;
		}
		throw new IllegalArgumentException("the layout has no font list");
	}

	private static void put(ByteBuffer b, int at, Float value) {
		if (value != null) {
			b.putFloat(at, value);
		}
	}

	/** Four bytes r, g, b, a as RGBA with red in the top byte. */
	private static int rgba(byte[] d, int i) {
		return Bytes.u8(d, i) << 24 | Bytes.u8(d, i + 1) << 16 | Bytes.u8(d, i + 2) << 8 | Bytes.u8(d, i + 3);
	}
}
