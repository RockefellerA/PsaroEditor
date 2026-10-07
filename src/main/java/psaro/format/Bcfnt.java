package psaro.format;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * BCFNT, the 3DS bitmap font (version 3), with per-glyph access so glyphs can be merged
 * between fonts and the result written back.
 *
 * <pre>
 * 0x00 CFNT header (0x14): magic, BOM, header size, version 0x03000000, file size, block count
 * 0x14 FINF (0x20): font type, line feed, alternate glyph index, default widths,
 *      encoding (1 = UTF-16), pointers (+8) to TGLP / CWDH / first CMAP, height, width, ascent
 * 0x34 TGLP: cell size, baseline, max char width, sheet size / count / format, grid,
 *      sheet pixel size, sheet data pointer (0x80; the header is padded up to it)
 * then one CWDH (left bearing, glyph width, advance per glyph) and a chain of CMAPs.
 * </pre>
 *
 * Glyph cells sit on a (cell + 1)-pixel grid starting at (1, 1) of each sheet. Pixels outside
 * the cells hold {@link #pad} (transparent white in RGBA8 sheets, zero elsewhere).
 *
 * <p>The layout engine draws a font scaled by (font size / {@link #width}, font size /
 * {@link #height}), puts each cell's {@link #baseline} row on the line's baseline, and
 * advances by the glyph's char width. A character the font lacks draws as glyph
 * {@link #altIndex}, which in this game's fonts is a space, so missing letters vanish.
 */
public final class Bcfnt {

	/** One cell: raw pixels ({@code cellW * cellH}, row-major, in the sheet format) and widths. */
	public static final class Glyph {
		public int[] pixels;
		public int left;
		public int glyphWidth;
		public int charWidth;

		public Glyph(int[] pixels, int left, int glyphWidth, int charWidth) {
			this.pixels = pixels;
			this.left = left;
			this.glyphWidth = glyphWidth;
			this.charWidth = charWidth;
		}
	}

	/** A CMAP block as stored: method 0 direct, 1 table, 2 scan. */
	public record CmapBlock(int method, int begin, int end, Map<Integer, Integer> map) {
	}

	public int fontType = 1;
	public int lineFeed;
	public int altIndex;
	public int defaultLeft;
	public int defaultGlyphWidth;
	public int defaultCharWidth;
	public int encoding = 1;
	public int height;
	public int width;
	public int ascent;
	public int cellW;
	public int cellH;
	public int baseline;
	public int maxCharWidth;
	public int format = Texture.LA8;
	public int sheetW = 256;
	public int sheetH = 256;
	public int cols = 1;
	public int rows = 1;
	public int pad;
	public final List<Glyph> glyphs = new ArrayList<>();
	/** Code point to glyph index. */
	public final Map<Integer, Integer> cmap = new TreeMap<>();
	/** The CMAP blocks as read, reused on write while {@link #cmap} still matches them. */
	private List<CmapBlock> cmapBlocks;

	// ------------------------------------------------------------------ read

	public static Bcfnt parse(byte[] d) {
		if (!Bytes.magic(d, 0, "CFNT")) {
			throw new IllegalArgumentException("not a CFNT font");
		}
		Bcfnt f = new Bcfnt();
		f.cmapBlocks = new ArrayList<>();
		List<int[]> widths = new ArrayList<>();
		int sheetPtr = 0;
		int sheetSize = 0;
		int o = Bytes.u16(d, 6);
		while (o < d.length - 8) {
			int size = Bytes.u32(d, o + 4);
			if (Bytes.magic(d, o, "FINF")) {
				f.fontType = Bytes.u8(d, o + 8);
				f.lineFeed = Bytes.u8(d, o + 9);
				f.altIndex = Bytes.u16(d, o + 10);
				f.defaultLeft = Bytes.s8(d, o + 12);
				f.defaultGlyphWidth = Bytes.u8(d, o + 13);
				f.defaultCharWidth = Bytes.u8(d, o + 14);
				f.encoding = Bytes.u8(d, o + 15);
				f.height = Bytes.u8(d, o + 28);
				f.width = Bytes.u8(d, o + 29);
				f.ascent = Bytes.u8(d, o + 30);
			} else if (Bytes.magic(d, o, "TGLP")) {
				f.cellW = Bytes.u8(d, o + 8);
				f.cellH = Bytes.u8(d, o + 9);
				f.baseline = Bytes.u8(d, o + 10);
				f.maxCharWidth = Bytes.u8(d, o + 11);
				sheetSize = Bytes.u32(d, o + 12);
				f.format = Bytes.u16(d, o + 18);
				f.cols = Bytes.u16(d, o + 20);
				f.rows = Bytes.u16(d, o + 22);
				f.sheetW = Bytes.u16(d, o + 24);
				f.sheetH = Bytes.u16(d, o + 26);
				sheetPtr = Bytes.u32(d, o + 28);
			} else if (Bytes.magic(d, o, "CWDH")) {
				int start = Bytes.u16(d, o + 8);
				int end = Bytes.u16(d, o + 10);
				for (int k = 0; k <= end - start; k++) {
					int p = o + 16 + 3 * k;
					widths.add(new int[] {Bytes.s8(d, p), Bytes.u8(d, p + 1), Bytes.u8(d, p + 2)});
				}
			} else if (Bytes.magic(d, o, "CMAP")) {
				int begin = Bytes.u16(d, o + 8);
				int end = Bytes.u16(d, o + 10);
				int method = Bytes.u16(d, o + 12);
				int p = o + 20;
				Map<Integer, Integer> m = new LinkedHashMap<>();
				switch (method) {
					case 0 -> {
						int first = Bytes.u16(d, p);
						for (int c = begin; c <= end; c++) {
							m.put(c, c - begin + first);
						}
					}
					case 1 -> {
						for (int c = begin; c <= end; c++) {
							int g = Bytes.u16(d, p + 2 * (c - begin));
							if (g != 0xFFFF) {
								m.put(c, g);
							}
						}
					}
					case 2 -> {
						int n = Bytes.u16(d, p);
						for (int k = 0; k < n; k++) {
							m.put(Bytes.u16(d, p + 2 + 4 * k), Bytes.u16(d, p + 4 + 4 * k));
						}
					}
					default -> throw new IllegalArgumentException("unknown CMAP method " + method);
				}
				f.cmapBlocks.add(new CmapBlock(method, begin, end, m));
				f.cmap.putAll(m);
			}
			if (size == 0) {
				break;
			}
			o += size;
		}

		int per = f.cols * f.rows;
		int[][] decoded = new int[(widths.size() + per - 1) / Math.max(per, 1) + 1][];
		for (int i = 0; i < widths.size(); i++) {
			int s = i / per;
			int r = i % per;
			if (decoded[s] == null) {
				decoded[s] = Texture.unswizzle(d, sheetPtr + s * sheetSize, f.sheetW, f.sheetH, f.format);
				if (s == 0) {
					f.pad = decoded[0][0];
				}
			}
			int x0 = (r % f.cols) * (f.cellW + 1) + 1;
			int y0 = (r / f.cols) * (f.cellH + 1) + 1;
			int[] cell = new int[f.cellW * f.cellH];
			for (int y = 0; y < f.cellH; y++) {
				System.arraycopy(decoded[s], (y0 + y) * f.sheetW + x0, cell, y * f.cellW, f.cellW);
			}
			int[] w = widths.get(i);
			f.glyphs.add(new Glyph(cell, w[0], w[1], w[2]));
		}
		return f;
	}

	// ----------------------------------------------------------------- write

	public byte[] toBytes() {
		int per = cols * rows;
		int sheets = Math.max(1, (glyphs.size() + per - 1) / per);
		int sheetSize = sheetW * sheetH * Texture.bitsPerPixel(format) / 8;

		Buf out = new Buf(0x80 + sheets * sheetSize + glyphs.size() * 8);
		out.padTo(0x14 + 0x20);
		int tglpAt = out.size();
		out.padTo(0x80);
		for (int s = 0; s < sheets; s++) {
			int[] px = new int[sheetW * sheetH];
			Arrays.fill(px, pad);
			for (int r = 0; r < per && s * per + r < glyphs.size(); r++) {
				Glyph g = glyphs.get(s * per + r);
				int x0 = (r % cols) * (cellW + 1) + 1;
				int y0 = (r / cols) * (cellH + 1) + 1;
				for (int y = 0; y < cellH; y++) {
					System.arraycopy(g.pixels, y * cellW, px, (y0 + y) * sheetW + x0, cellW);
				}
			}
			out.put(Texture.swizzle(px, sheetW, sheetH, format));
		}
		int tglpEnd = out.size();

		int cwdhAt = out.size();
		out.put('C').put('W').put('D').put('H').putInt(0).putShort(0).putShort(glyphs.size() - 1).putInt(0);
		for (Glyph g : glyphs) {
			out.put(g.left).put(g.glyphWidth).put(g.charWidth);
		}
		out.align(4);
		out.setInt(cwdhAt + 4, out.size() - cwdhAt);

		List<CmapBlock> blocks = cmapBlocks != null && blocksMatch() ? cmapBlocks : planCmap();
		List<Integer> cmapAts = new ArrayList<>();
		for (CmapBlock b : blocks) {
			int at = out.size();
			cmapAts.add(at);
			out.put('C').put('M').put('A').put('P').putInt(0)
					.putShort(b.begin).putShort(b.end).putShort(b.method).putShort(0).putInt(0);
			switch (b.method) {
				case 0 -> out.putShort(b.map.get(b.begin));
				case 1 -> {
					for (int c = b.begin; c <= b.end; c++) {
						out.putShort(b.map.getOrDefault(c, 0xFFFF));
					}
				}
				default -> {
					out.putShort(b.map.size());
					for (Map.Entry<Integer, Integer> e : new TreeMap<>(b.map).entrySet()) {
						out.putShort(e.getKey()).putShort(e.getValue());
					}
				}
			}
			out.align(4);
			out.setInt(at + 4, out.size() - at);
		}
		for (int k = 0; k + 1 < cmapAts.size(); k++) {
			out.setInt(cmapAts.get(k) + 16, cmapAts.get(k + 1) + 8);
		}

		Buf head = new Buf(0x80);
		head.put('C').put('F').put('N').put('T').putShort(0xFEFF).putShort(0x14)
				.putInt(0x03000000).putInt(out.size()).putInt(3 + cmapAts.size());
		head.put('F').put('I').put('N').put('F').putInt(0x20)
				.put(fontType).put(lineFeed).putShort(altIndex)
				.put(defaultLeft).put(defaultGlyphWidth).put(defaultCharWidth).put(encoding)
				.putInt(tglpAt + 8).putInt(cwdhAt + 8).putInt(cmapAts.isEmpty() ? 0 : cmapAts.get(0) + 8)
				.put(height).put(width).put(ascent).put(0);
		head.put('T').put('G').put('L').put('P').putInt(tglpEnd - tglpAt)
				.put(cellW).put(cellH).put(baseline).put(maxCharWidth)
				.putInt(sheetSize).putShort(sheets).putShort(format).putShort(cols).putShort(rows)
				.putShort(sheetW).putShort(sheetH).putInt(0x80);
		byte[] bytes = out.toArray();
		byte[] h = head.toArray();
		System.arraycopy(h, 0, bytes, 0, h.length);
		return bytes;
	}

	private boolean blocksMatch() {
		Map<Integer, Integer> merged = new TreeMap<>();
		for (CmapBlock b : cmapBlocks) {
			merged.putAll(b.map);
		}
		return merged.equals(cmap);
	}

	/** Direct blocks for runs of 8+ consecutive codes on consecutive glyphs, one scan block for the rest. */
	private List<CmapBlock> planCmap() {
		List<Integer> codes = new ArrayList<>(cmap.keySet());
		List<CmapBlock> blocks = new ArrayList<>();
		Map<Integer, Integer> scan = new TreeMap<>();
		int i = 0;
		while (i < codes.size()) {
			int j = i;
			while (j + 1 < codes.size() && codes.get(j + 1) == codes.get(j) + 1
					&& cmap.get(codes.get(j + 1)) == cmap.get(codes.get(j)) + 1) {
				j++;
			}
			Map<Integer, Integer> run = new LinkedHashMap<>();
			for (int k = i; k <= j; k++) {
				run.put(codes.get(k), cmap.get(codes.get(k)));
			}
			if (j - i + 1 >= 8) {
				blocks.add(new CmapBlock(0, codes.get(i), codes.get(j), run));
			} else {
				scan.putAll(run);
			}
			i = j + 1;
		}
		if (!scan.isEmpty()) {
			blocks.add(new CmapBlock(2, 0, 0xFFFF, scan));
		}
		return blocks;
	}

	// --------------------------------------------------------------- editing

	public boolean has(int codePoint) {
		return cmap.containsKey(codePoint);
	}

	public Glyph glyph(int codePoint) {
		Integer i = cmap.get(codePoint);
		return i == null ? null : glyphs.get(i);
	}

	/** Resizes every cell, keeping each glyph's baseline row on the new baseline. */
	public void recell(int newW, int newH, int newBaseline) {
		int shift = newBaseline - baseline;
		for (Glyph g : glyphs) {
			g.pixels = move(g.pixels, cellW, cellH, newW, newH, shift, transparent());
		}
		cellW = newW;
		cellH = newH;
		baseline = newBaseline;
	}

	/**
	 * Copies the glyphs for {@code codes} out of {@code donor}, widening cells to fit and
	 * re-seating the donor's cells on this font's baseline; pixel formats are converted if they
	 * differ. Existing glyphs are kept unless {@code replace}. Returns the code points added.
	 */
	public List<Integer> addGlyphsFrom(Bcfnt donor, Collection<Integer> codes, boolean replace) {
		List<Integer> take = new ArrayList<>();
		for (int c : codes) {
			if (donor.has(c) && (replace || !has(c))) {
				take.add(c);
			}
		}
		if (take.isEmpty()) {
			return take;
		}
		int above = Math.max(baseline, donor.baseline);
		int below = Math.max(cellH - baseline, donor.cellH - donor.baseline);
		int w = Math.max(cellW, donor.cellW);
		if (w != cellW || above + below != cellH || above != baseline) {
			recell(w, above + below, above);
		}
		int shift = baseline - donor.baseline;
		for (int c : take) {
			Glyph src = donor.glyph(c);
			int[] px = move(src.pixels, donor.cellW, donor.cellH, cellW, cellH, shift, transparent());
			if (donor.format != format) {
				for (int k = 0; k < px.length; k++) {
					px[k] = Texture.fromRgba(Texture.toRgba(px[k], donor.format), format);
				}
			}
			Glyph g = new Glyph(px, src.left, src.glyphWidth, src.charWidth);
			Integer at = cmap.get(c);
			if (at != null) {
				glyphs.set(at, g);
			} else {
				cmap.put(c, glyphs.size());
				glyphs.add(g);
			}
			maxCharWidth = Math.max(maxCharWidth, g.charWidth);
		}
		fitSheets(256, 512);
		return take;
	}

	/** Picks the power-of-two sheet size that holds every glyph in the fewest texels. */
	public void fitSheets(int maxW, int maxH) {
		int cw = cellW + 1;
		int ch = cellH + 1;
		long bestCost = Long.MAX_VALUE;
		int bestSheets = Integer.MAX_VALUE;
		int bestSkew = Integer.MAX_VALUE;
		for (int w = 8; w <= maxW; w *= 2) {
			for (int h = 8; h <= maxH; h *= 2) {
				int c = (w - 1) / cw;
				int r = (h - 1) / ch;
				if (c < 1 || r < 1) {
					continue;
				}
				int n = (glyphs.size() + c * r - 1) / (c * r);
				long cost = (long) n * w * h;
				int skew = Math.abs(w - h);
				if (cost < bestCost || cost == bestCost && (n < bestSheets || n == bestSheets && skew < bestSkew)) {
					bestCost = cost;
					bestSheets = n;
					bestSkew = skew;
					sheetW = w;
					sheetH = h;
					cols = c;
					rows = r;
				}
			}
		}
	}

	/**
	 * A font holding only {@code codes} from this one, resampled to {@code target}'s proportions.
	 *
	 * <p>Glyphs from a bigger donor must shrink by {@code target.width / width} across and
	 * {@code target.height / height} down to come out the same size on screen. Resampling is
	 * an exact-coverage box filter in premultiplied alpha, so outlines keep their colour.
	 */
	public Bcfnt scaledTo(Bcfnt target, Collection<Integer> codes) {
		return scaledTo(target, codes, (double) target.width / width, (double) target.height / height);
	}

	/**
	 * A font holding only {@code codes} from this one, with {@code target}'s proportions but its
	 * glyphs resampled by {@code sx} across and {@code sy} down, for when the two fonts' nominal
	 * sizes do not compare (fonts of different styles set them differently).
	 */
	public Bcfnt scaledTo(Bcfnt target, Collection<Integer> codes, double sx, double sy) {
		Bcfnt out = new Bcfnt();
		out.format = format;
		out.pad = pad;
		out.width = target.width;
		out.height = target.height;
		out.ascent = target.ascent;
		out.lineFeed = target.lineFeed;
		int above = (int) Math.ceil(baseline * sy);
		int below = (int) Math.ceil((cellH - baseline) * sy);
		out.cellW = (int) Math.ceil(cellW * sx);
		out.cellH = above + below;
		out.baseline = above;
		double[][] xw = boxWeights(out.cellW, cellW, sx, 0.0);
		double[][] yw = boxWeights(out.cellH, cellH, sy, baseline - out.baseline / sy);
		for (int c : codes) {
			Glyph g = glyph(c);
			if (g == null) {
				continue;
			}
			// premultiplied rows resampled across: [y][x][r, g, b, a]
			double[][][] rows = new double[cellH][out.cellW][4];
			for (int y = 0; y < cellH; y++) {
				for (int x = 0; x < out.cellW; x++) {
					double[] acc = rows[y][x];
					for (int k = 0; k < xw[x].length; k += 2) {
						int rgba = Texture.toRgba(g.pixels[y * cellW + (int) xw[x][k]], format);
						double wt = xw[x][k + 1];
						double a = (rgba & 0xFF) * wt;
						acc[0] += (rgba >>> 24) * a;
						acc[1] += (rgba >> 16 & 0xFF) * a;
						acc[2] += (rgba >> 8 & 0xFF) * a;
						acc[3] += a;
					}
				}
			}
			int[] px = new int[out.cellW * out.cellH];
			for (int y = 0; y < out.cellH; y++) {
				for (int x = 0; x < out.cellW; x++) {
					double r = 0;
					double gg = 0;
					double b = 0;
					double a = 0;
					for (int k = 0; k < yw[y].length; k += 2) {
						double[] s = rows[(int) yw[y][k]][x];
						double wt = yw[y][k + 1];
						r += s[0] * wt;
						gg += s[1] * wt;
						b += s[2] * wt;
						a += s[3] * wt;
					}
					int rgba = 0;
					if (a > 0) {
						rgba = clamp(r / a) << 24 | clamp(gg / a) << 16 | clamp(b / a) << 8 | clamp(a);
					}
					px[y * out.cellW + x] = Texture.fromRgba(rgba, format);
				}
			}
			out.cmap.put(c, out.glyphs.size());
			out.glyphs.add(new Glyph(px, (int) Math.round(g.left * sx), (int) Math.round(g.glyphWidth * sx),
					(int) Math.round(g.charWidth * sx)));
		}
		for (Glyph g : out.glyphs) {
			out.maxCharWidth = Math.max(out.maxCharWidth, g.charWidth);
		}
		return out;
	}

	/** Packed RGBA of glyph {@code index}'s pixels. */
	public int[] rgba(int index) {
		int[] px = glyphs.get(index).pixels;
		int[] out = new int[px.length];
		for (int k = 0; k < px.length; k++) {
			out[k] = Texture.toRgba(px[k], format);
		}
		return out;
	}

	private int transparent() {
		return 0;
	}

	/**
	 * For each output pixel i, the input pixels covering [i / scale + offset, (i + 1) / scale + offset)
	 * as flattened (index, share) pairs; shares sum to 1 where the span lies inside the input.
	 */
	private static double[][] boxWeights(int nOut, int nIn, double scale, double offset) {
		double span = 1 / scale;
		double[][] out = new double[nOut][];
		for (int i = 0; i < nOut; i++) {
			double lo = i * span + offset;
			double hi = (i + 1) * span + offset;
			List<Double> ws = new ArrayList<>();
			for (int j = Math.max(0, (int) Math.floor(lo)); j < Math.min(nIn, (int) Math.ceil(hi)); j++) {
				double cover = Math.min(hi, j + 1) - Math.max(lo, j);
				if (cover > 0) {
					ws.add((double) j);
					ws.add(cover / span);
				}
			}
			out[i] = ws.stream().mapToDouble(Double::doubleValue).toArray();
		}
		return out;
	}

	private static int[] move(int[] px, int w0, int h0, int w1, int h1, int dy, int fill) {
		int[] out = new int[w1 * h1];
		Arrays.fill(out, fill);
		int n = Math.min(w0, w1);
		for (int y = 0; y < h0; y++) {
			int ny = y + dy;
			if (ny >= 0 && ny < h1) {
				System.arraycopy(px, y * w0, out, ny * w1, n);
			}
		}
		return out;
	}

	private static int clamp(double v) {
		return (int) Math.min(255, Math.round(v));
	}
}
