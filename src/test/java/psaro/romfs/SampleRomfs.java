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
import psaro.format.Msgd;
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

	/** {@code message/<name>_jp.mdt} holding {@code strings}, numbered in order. */
	public static void message(Path romfs, String name, List<String> strings) throws IOException {
		Files.createDirectories(romfs.resolve("message"));
		Files.write(romfs.resolve("message/" + name + "_jp.mdt"), msgd(strings));
	}

	/** An MSGD message file holding {@code strings}. */
	public static byte[] msgd(List<String> strings) {
		int count = strings.size();
		int text = (0x20 + 4 * count + 15) / 16 * 16;
		ByteBuffer head = le(text);
		head.put("MSGD".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(0).putInt(count).putInt(0x20).putInt(text);
		return Msgd.write(head.array(), strings);
	}

	/**
	 * A CGFX model holding one image texture, {@code name}, with {@code bclim}'s pixels (an image
	 * its full power-of-two size, in GPU format {@code format}), laid out as the game's are: the
	 * texture's record, then its name, then its pixels.
	 */
	public static byte[] cgfx(String name, byte[] bclim, int format) {
		psaro.format.Bclim b = psaro.format.Bclim.read(bclim);
		byte[] data = psaro.format.Bclim.data(bclim);
		int txob = 0x20, nameAt = 0x70, dataAt = 0x80;
		ByteBuffer out = le(dataAt + data.length);
		out.put("CGFX".getBytes(StandardCharsets.US_ASCII));
		out.position(txob);
		out.putInt(0x20000011).put("TXOB".getBytes(StandardCharsets.US_ASCII)).putInt(5).putInt(nameAt - (txob + 12))
				.putInt(0).putInt(0).putInt(b.height()).putInt(b.width()).putInt(0x6758).putInt(0).putInt(1).putInt(0).putInt(0)
				.putInt(format).putInt(4).putInt(b.height()).putInt(b.width()).putInt(data.length).putInt(dataAt - (txob + 0x48));
		out.position(nameAt);
		out.put(name.getBytes(StandardCharsets.US_ASCII)).put((byte) 0);
		out.position(dataAt);
		out.put(data);
		return out.array();
	}

	/** {@link #cgfx} at {@code path}, compressed when the path ends {@code .lz}. */
	public static void model(Path romfs, String path, String name, byte[] bclim, int format) throws IOException {
		psaro.format.Cgfx.write(cgfx(name, bclim, format), romfs.resolve(path));
	}

	/** {@code table/<name>.csv} holding {@code lines}. */
	/**
	 * A DLC content romfs, {@code <dlc>/<folder>}, holding its one row of records as a DLC song's
	 * does: {@code contents.csv.lz}, compressed, at its top.
	 */
	public static void content(Path dlc, String folder, List<String> lines) throws IOException {
		Files.createDirectories(dlc.resolve(folder));
		Files.write(dlc.resolve(folder + "/contents.csv.lz"), psaro.format.Lz11.compress(csv(lines)));
	}

	public static void data(Path romfs, String name, List<String> lines) throws IOException {
		Files.createDirectories(romfs.resolve("table"));
		Files.write(romfs.resolve("table/" + name + ".csv"), csv(lines));
	}

	/** A data table holding {@code lines} as the game's are: UTF-16LE after a byte order mark, each line ending CRLF. */
	public static byte[] csv(List<String> lines) {
		return ("﻿" + String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_16LE);
	}

	/** A record file ({@link psaro.format.Records}) holding {@code records}, in order. */
	public static byte[] records(List<byte[]> records) {
		int at = 16 + 8 * records.size();
		ByteBuffer head = le(at);
		head.put("DARC".getBytes(StandardCharsets.US_ASCII)).putInt(records.size()).putInt(0).putInt(0);
		for (byte[] r : records) {
			head.putInt(at).putInt(r.length);
			at += r.length;
		}
		return concat(head.array(), concat(records.toArray(byte[][]::new)));
	}

	/** A record of {@code size} bytes: its index, its id, then each of {@code fields} at its offset. */
	public static byte[] record(int size, int index, String id, Map<Integer, byte[]> fields) {
		ByteBuffer r = le(size).putInt(index).put(id.getBytes(StandardCharsets.US_ASCII));
		fields.forEach((offset, bytes) -> r.put(offset, bytes));
		return r.array();
	}

	/** {@code text} as UTF-16LE, NUL-terminated. */
	public static byte[] utf16(String text) {
		return (text + "\0").getBytes(StandardCharsets.UTF_16LE);
	}

	/** {@code text} as Shift-JIS, NUL-terminated. */
	public static byte[] shiftJis(String text) {
		return (text + "\0").getBytes(java.nio.charset.Charset.forName("windows-31j"));
	}

	/**
	 * An event script ({@link psaro.format.Script}): one scene, {@code name}, of 128 entries as the
	 * game's are: {@code entries} (each a command and its text), {@code <end>}, then entries
	 * holding {@code leftover} as text, as the memory the game's tool left there does.
	 */
	public static byte[] script(String name, List<String[]> entries, String leftover) {
		int entry = 0x144;
		ByteBuffer r = le(0x60 + 128 * entry).put(name.getBytes(StandardCharsets.US_ASCII));
		r.position(0x40);
		for (int i = 0; i < 8; i++) {
			r.putInt(i < 2 ? i : -1);
		}
		for (int i = 0; i < 128; i++) {
			int o = 0x60 + i * entry;
			String[] e = i < entries.size() ? entries.get(i) : i == entries.size() ? new String[] {"<end>", ""}
					: new String[] {"<Talk>", leftover};
			r.put(o, e[0].getBytes(StandardCharsets.US_ASCII)).putInt(o + 0x40, i % 2).put(o + 0x44, shiftJis(e[1]));
		}
		return records(List.of(r.array()));
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
		return pane(name, key, 0, 0);
	}

	/** {@link #pane(String, String)} at {@code x}, {@code y} from its parent. */
	public static byte[] pane(String name, String key, float x, float y) {
		ByteBuffer txt = le(0x74 - 8 + 4);
		txt.position(0x0C - 8).put(name.getBytes(StandardCharsets.US_ASCII));
		txt.position(0x24 - 8).putFloat(x).putFloat(y);
		txt.position(0x44 - 8).putFloat(120f).putFloat(24f).putShort((short) 4).putShort((short) 4);
		txt.position(0x52 - 8).putShort((short) 0);
		txt.position(0x58 - 8).putInt(0x74);
		txt.position(0x64 - 8).putFloat(16f).putFloat(16f).putFloat(0f).putFloat(0f);
		txt.position(0x74 - 8).put("*\0".getBytes(StandardCharsets.UTF_16LE));
		return concat(section("txt1", txt.array()), section("usd1", textId(key)));
	}

	/**
	 * {@link #pane(String, String)} holding {@code text} itself in place of the placeholder, and,
	 * with a null {@code key}, no usd1: a pane no table fills.
	 */
	public static byte[] pane(String name, String key, String text) {
		byte[] chars = (text + " ").getBytes(StandardCharsets.UTF_16LE);
		ByteBuffer txt = le((0x74 - 8 + chars.length + 3) / 4 * 4);
		txt.position(0x0C - 8).put(name.getBytes(StandardCharsets.US_ASCII));
		txt.position(0x44 - 8).putFloat(120f).putFloat(24f).putShort((short) chars.length).putShort((short) chars.length);
		txt.position(0x52 - 8).putShort((short) 0);
		txt.position(0x58 - 8).putInt(0x74);
		txt.position(0x64 - 8).putFloat(16f).putFloat(16f).putFloat(0f).putFloat(0f);
		txt.position(0x74 - 8).put(chars);
		byte[] section = section("txt1", txt.array());
		return key == null ? section : concat(section, section("usd1", textId(key)));
	}

	/**
	 * A picture pane {@code name} (pic1) at {@code x}, {@code y} from its parent, {@code w} by
	 * {@code h}, at scale 1: the fields every pane shares, then no vertex colours, material or
	 * texture coordinates worth reading.
	 */
	public static byte[] picture(String name, float x, float y, float w, float h) {
		ByteBuffer pic = le(0x60 - 8);
		pic.position(0x0C - 8).put(name.getBytes(StandardCharsets.US_ASCII));
		pic.position(0x24 - 8).putFloat(x).putFloat(y);
		pic.position(0x3C - 8).putFloat(1f).putFloat(1f).putFloat(w).putFloat(h);
		return section("pic1", pic.array());
	}

	/** {@link #picture(String, float, float, float, float)} drawing material {@code material} of the layout's mat1. */
	public static byte[] picture(String name, float w, float h, int material) {
		byte[] pic = picture(name, 0, 0, w, h);
		pic[0x5C] = (byte) material;
		pic[0x5D] = (byte) (material >> 8);
		return pic;
	}

	/** A window pane {@code name} (wnd1), {@code w} by {@code h}, its content drawing material {@code material}. */
	public static byte[] window(String name, float w, float h, int material) {
		ByteBuffer wnd = le(0x80 - 8);
		wnd.position(0x0C - 8).put(name.getBytes(StandardCharsets.US_ASCII));
		wnd.position(0x3C - 8).putFloat(1f).putFloat(1f).putFloat(w).putFloat(h);
		wnd.position(0x60 - 8).putInt(0x68).putInt(0x7C);
		wnd.position(0x68 - 8).putInt(-1).putInt(-1).putInt(-1).putInt(-1).putShort((short) material);
		return section("wnd1", wnd.array());
	}

	/** A txl1 texture list naming {@code names}, in order. */
	public static byte[] textures(String... names) {
		ByteArrayOutputStream strings = new ByteArrayOutputStream();
		ByteBuffer offsets = le(4 * names.length);
		for (String n : names) {
			offsets.putInt(4 * names.length + strings.size());
			strings.writeBytes((n + "\0").getBytes(StandardCharsets.US_ASCII));
		}
		return section("txl1", concat(le(4).putInt(names.length).array(), offsets.array(), strings.toByteArray()));
	}

	/** A mat1 material list: per material the index of the one texture it maps, or -1 for none. */
	public static byte[] materials(int... textures) {
		int material = 0x38;
		ByteBuffer mat = le(4 + 4 * textures.length + material * textures.length);
		mat.putInt(textures.length);
		for (int i = 0; i < textures.length; i++) {
			mat.putInt(8 + 4 + 4 * textures.length + material * i);
		}
		for (int i = 0; i < textures.length; i++) {
			int at = 4 + 4 * textures.length + material * i;
			mat.put(at, ("Mat_" + i).getBytes(StandardCharsets.US_ASCII));
			mat.putInt(at + 0x30, textures[i] < 0 ? 0 : 0x15);
			mat.putShort(at + 0x34, (short) Math.max(0, textures[i]));
		}
		return section("mat1", mat.array());
	}

	/** A null pane {@code name} (pan1) with {@code children} hung under it (pas1 .. pae1). */
	public static byte[] group(String name, byte[]... children) {
		ByteBuffer pan = le(0x4C - 8);
		pan.position(0x0C - 8).put(name.getBytes(StandardCharsets.US_ASCII));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(section("pan1", pan.array()));
		out.writeBytes(section("pas1", new byte[0]));
		for (byte[] c : children) {
			out.writeBytes(c);
		}
		out.writeBytes(section("pae1", new byte[0]));
		return out.toByteArray();
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
