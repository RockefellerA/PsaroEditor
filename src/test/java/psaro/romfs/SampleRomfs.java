package psaro.romfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import psaro.format.Archive;
import psaro.format.Bcfnt;
import psaro.format.Darc;
import psaro.format.Etc1;
import psaro.format.Tdt;
import psaro.format.Texture;

/** Builds a small synthetic romfs for tests: string tables, and archives of layouts and fonts. */
public final class SampleRomfs {

	private SampleRomfs() {
	}

	/** {@code text/<name>_Japanese.tdt}. */
	public static void table(Path romfs, String name, Map<String, String> strings) throws IOException {
		Files.createDirectories(romfs.resolve("text"));
		Files.write(romfs.resolve("text/" + name + "_Japanese.tdt"), Tdt.write(new LinkedHashMap<>(strings)));
	}

	/**
	 * An archive at {@code path} with one layout at {@code layoutPath} listing {@code fonts} and
	 * holding {@code panes}, and {@code fontFiles} under {@code font/}.
	 */
	public static void archive(Path romfs, String path, String layoutPath, List<String> fonts,
			Map<String, Bcfnt> fontFiles, byte[]... panes) throws IOException {
		Darc.Node root = Darc.Node.dir("");
		Darc.Node dot = Darc.Node.dir(".");
		Darc.Node blyt = Darc.Node.dir(layoutPath.substring(0, layoutPath.indexOf('/')));
		blyt.children.add(Darc.Node.file(layoutPath.substring(layoutPath.indexOf('/') + 1), layout(fonts, panes)));
		dot.children.add(blyt);
		if (!fontFiles.isEmpty()) {
			Darc.Node font = Darc.Node.dir("font");
			fontFiles.forEach((name, f) -> font.children.add(Darc.Node.file(name, f.toBytes())));
			dot.children.add(font);
		}
		root.children.add(dot);
		Archive.save(root, romfs.resolve(path));
	}

	/** Adds {@code images} (file name to BCLIM) under {@code timg/} in the archive at {@code path}. */
	public static void images(Path romfs, String path, Map<String, byte[]> images) throws IOException {
		Darc.Node root = Archive.load(romfs.resolve(path));
		Darc.Node dot = root.children.get(0);
		Darc.Node timg = Darc.Node.dir("timg");
		images.forEach((name, bytes) -> timg.children.add(Darc.Node.file(name, bytes)));
		dot.children.add(timg);
		Archive.save(root, romfs.resolve(path));
	}

	/**
	 * A BCLIM of {@code argb} ({@code w} x {@code h}, row-major) in BCLIM format {@code format}
	 * (9 RGBA8, 11 ETC1A4), its data padded to powers of two (8 at least) with transparent black.
	 */
	public static byte[] bclim(int w, int h, int format, int[] argb) {
		int pw = 8;
		while (pw < w) {
			pw *= 2;
		}
		int ph = 8;
		while (ph < h) {
			ph *= 2;
		}
		int[] all = new int[pw * ph];
		for (int y = 0; y < h; y++) {
			System.arraycopy(argb, y * w, all, y * pw, w);
		}
		byte[] data;
		if (format == 11) {
			data = Etc1.encode(all, pw, ph, true);
		} else if (format == 9) {
			int[] raw = new int[all.length];
			for (int i = 0; i < raw.length; i++) {
				raw[i] = all[i] << 8 | all[i] >>> 24;
			}
			data = Texture.swizzle(raw, pw, ph, Texture.RGBA8);
		} else {
			throw new IllegalArgumentException("format " + format);
		}
		ByteBuffer tail = le(0x28);
		tail.put("CLIM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0xFEFF).putShort((short) 0x14)
				.putInt(0x02020000).putInt(data.length + 0x28).putShort((short) 1).putShort((short) 0);
		tail.put("imag".getBytes(StandardCharsets.US_ASCII)).putInt(0x10).putShort((short) w).putShort((short) h)
				.putInt(format).putInt(data.length);
		return concat(data, tail.array());
	}

	/**
	 * An LA8 font drawn 1:1 at font size 16, each of {@code chars} a solid block {@code advance}
	 * wide.
	 */
	public static Bcfnt font(String chars, int advance) {
		Bcfnt f = new Bcfnt();
		f.format = Texture.LA8;
		f.cellW = 12;
		f.cellH = 14;
		f.baseline = 11;
		f.height = 16;
		f.width = 16;
		f.ascent = 11;
		f.lineFeed = 16;
		chars.codePoints().forEach(c -> {
			int[] px = new int[f.cellW * f.cellH];
			for (int y = 2; y < f.baseline; y++) {
				for (int x = 1; x < f.cellW - 1; x++) {
					px[y * f.cellW + x] = 0xFF << 8 | 0xFF;
				}
			}
			f.cmap.put(c, f.glyphs.size());
			f.glyphs.add(new Bcfnt.Glyph(px, 0, f.cellW - 2, advance));
			f.maxCharWidth = Math.max(f.maxCharWidth, advance);
		});
		f.fitSheets(256, 512);
		return f;
	}

	/** A CLYT with an fnl1 font list, then each pane's txt1 + usd1 sections. */
	public static byte[] layout(List<String> fonts, byte[]... panes) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		ByteArrayOutputStream names = new ByteArrayOutputStream();
		ByteBuffer offsets = le(4 * fonts.size());
		for (String font : fonts) {
			offsets.putInt(4 * fonts.size() + names.size());
			names.writeBytes((font + "\0").getBytes(StandardCharsets.US_ASCII));
		}
		byte[] fnl1Body = concat(le(4).putInt(fonts.size()).array(), offsets.array(), names.toByteArray());
		body.writeBytes(section("fnl1", fnl1Body));
		for (byte[] pane : panes) {
			body.writeBytes(pane);
		}
		ByteBuffer header = le(0x14);
		header.put("CLYT".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0xFEFF).putShort((short) 0x14);
		return concat(header.array(), body.toByteArray());
	}

	/** A 120x24 txt1 pane at font size 16 using font 0, followed by the usd1 that carries its key. */
	public static byte[] pane(String name, String key) {
		ByteBuffer txt = le(0x74 - 8 + 4);
		txt.position(0x0C - 8).put(name.getBytes(StandardCharsets.US_ASCII));
		txt.position(0x44 - 8).putFloat(120f).putFloat(24f).putShort((short) 4).putShort((short) 4);
		txt.position(0x52 - 8).putShort((short) 0);
		txt.position(0x58 - 8).putInt(0x74);
		txt.position(0x64 - 8).putFloat(16f).putFloat(16f).putFloat(0f).putFloat(0f);
		txt.position(0x74 - 8).put("*\0".getBytes(StandardCharsets.UTF_16LE));
		return concat(section("txt1", txt.array()), section("usd1", textId(key)));
	}

	/**
	 * A usd1 body as the game writes it: one entry, named TextID, whose string value is
	 * {@code key}; the key follows the entry, then the name, padded to four bytes.
	 */
	private static byte[] textId(String key) {
		byte[] k = key.getBytes(StandardCharsets.US_ASCII);
		int nameAt = 12 + k.length + 1;
		ByteBuffer usd = le((4 + nameAt + "TextID".length() + 1 + 3) / 4 * 4);
		usd.putShort((short) 1).putShort((short) 0);
		usd.putInt(nameAt).putInt(12).putShort((short) k.length).put((byte) 0).put((byte) 0);
		usd.put(k).put((byte) 0).put("TextID".getBytes(StandardCharsets.US_ASCII));
		return usd.array();
	}

	private static byte[] section(String tag, byte[] body) {
		ByteBuffer head = le(8).put(tag.getBytes(StandardCharsets.US_ASCII)).putInt(8 + body.length);
		return concat(head.array(), body);
	}

	private static ByteBuffer le(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			out.writeBytes(p);
		}
		return out.toByteArray();
	}
}
