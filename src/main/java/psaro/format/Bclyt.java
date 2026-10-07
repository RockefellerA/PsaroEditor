package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BCLYT layouts: the font list and every pane, with the details of text panes.
 *
 * <p>txt1 layout, offsets from the section start: 0x0C name (16 bytes), 0x24 translation xyz,
 * 0x30 rotation xyz, 0x3C scale xy, 0x44 box size w h, 0x4C text buffer bytes u16, string
 * bytes u16, material u16, font index u16 (into fnl1), 0x54 origin u8, line alignment u8,
 * 0x58 text offset, 0x5C top / bottom colour, 0x64 font size x y, 0x6C char spacing,
 * 0x70 line spacing.
 *
 * <p>The text a layout stores is a placeholder ({@code *}); the game fills panes from the
 * {@code .tdt} that shares the archive's name, keyed by ids held in the pane's user data
 * (usd1). The game does not wrap: lines break only at {@code \n}.
 */
public final class Bclyt {

	private static final Pattern KEY = Pattern.compile("[a-z]{4}_\\d{4}");

	/** Any pane. Text-pane fields are null / zero for other kinds. */
	public record Pane(String kind, String name, List<String> keys, TextInfo text) {
		public boolean isText() {
			return text != null;
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

	private Bclyt() {
	}

	public static Layout read(byte[] d) {
		if (!Bytes.magic(d, 0, "CLYT")) {
			throw new IllegalArgumentException("not a CLYT layout");
		}
		List<String> fonts = new ArrayList<>();
		List<Pane> panes = new ArrayList<>();
		int o = Bytes.u16(d, 6);
		while (o < d.length - 8) {
			int size = Bytes.u32(d, o + 4);
			if (size == 0) {
				break;
			}
			String tag = new String(d, o, 4, StandardCharsets.US_ASCII);
			switch (tag) {
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
					panes.add(new Pane(tag, name, new ArrayList<>(), info));
				}
				case "usd1" -> {
					if (!panes.isEmpty()) {
						Matcher m = KEY.matcher(new String(d, o, size, StandardCharsets.ISO_8859_1));
						while (m.find()) {
							panes.get(panes.size() - 1).keys().add(m.group());
						}
					}
				}
				default -> {
				}
			}
			o += size;
		}
		return new Layout(fonts, panes);
	}

	/** Four bytes r, g, b, a as RGBA with red in the top byte. */
	private static int rgba(byte[] d, int i) {
		return Bytes.u8(d, i) << 24 | Bytes.u8(d, i + 1) << 16 | Bytes.u8(d, i + 2) << 8 | Bytes.u8(d, i + 3);
	}
}
