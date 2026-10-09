package psaro.patch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import psaro.format.Bcfnt;
import psaro.format.Texture;

/**
 * How a font drawn under another in one text's layers is made from the top font's letters: an
 * outline of its own under a fill (the tutorial titles' black layers under their white letters),
 * or a soft glow (the prologue's white haze under its letters). Each lower-layer letter is the
 * top one's, every pixel as opaque, and of the colour, the game's lower layer is at that distance
 * from the top letter's ink, shifted as the game's is (a shadow cast down).
 *
 * <p>All of that is read from the letters both fonts have, so a letter the game never drew in
 * the lower font (the English, lent to the top one or drawn from a typeface) comes out as the
 * game would have drawn it, lined up with its top letter: the same advance, from the same place.
 */
public final class UnderLayer {

	/** Distances are kept in steps of 1 / {@code STEPS} pixel, out to {@link #REACH} pixels. */
	private static final int STEPS = 4;
	private static final int REACH = 16;
	/** A top letter's pixel counts as its ink from this alpha. */
	private static final int INK = 128;
	/** At most this many shared letters are read. */
	private static final int SAMPLE = 60;
	/** The mean alpha error, over the pixels either has, beyond which the two are not one text's layers. */
	private static final double MISFIT = 48;

	/** Alpha (0..255) and colour (RGB, alpha-weighted) by distance step from the top letter's ink. */
	private final double[] alpha;
	private final int[] colour;
	/** How far, in pixels, the lower letter reaches past the top one's ink. */
	private final int reach;
	/** The lower letter's offset from the top one's, in pixels, right and down. */
	private final int dx;
	private final int dy;

	private UnderLayer(double[] alpha, int[] colour, int reach, int dx, int dy) {
		this.alpha = alpha;
		this.colour = colour;
		this.reach = reach;
		this.dx = dx;
		this.dy = dy;
	}

	/**
	 * How {@code under}'s letters are made from {@code top}'s, read from the letters both have;
	 * null when the pane cannot scale the two alike (their nominal sizes differ), they share too
	 * few letters, or one rule does not fit them, so they are not one text's layers after all.
	 */
	public static UnderLayer learn(Bcfnt under, Bcfnt top) {
		if (under.width != top.width || under.height != top.height) {
			return null;
		}
		List<Integer> shared = new ArrayList<>();
		for (int c : under.cmap.keySet()) {
			if (shared.size() < SAMPLE && c > 0x20 && top.has(c) && !ink(top, c).isEmpty() && hasInk(under, c)) {
				shared.add(c);
			}
		}
		if (shared.size() < 3) {
			return null;
		}
		// the offset: how far the lower letter's weight sits from the top one's, on average
		double sx = 0;
		double sy = 0;
		for (int c : shared) {
			double[] u = centroid(under, c, false);
			double[] t = centroid(top, c, true);
			sx += u[0] - t[0];
			sy += u[1] - t[1];
		}
		int dx = (int) Math.round(sx / shared.size());
		int dy = (int) Math.round(sy / shared.size());

		int buckets = REACH * STEPS + 1;
		double[] sum = new double[buckets];
		long[] count = new long[buckets];
		double[][] rgb = new double[buckets][3];
		List<double[][]> seen = new ArrayList<>();
		for (int c : shared) {
			Bcfnt.Glyph g = under.glyph(c);
			int[] px = under.rgba(under.cmap.get(c));
			double[][] d = distances(top, c, dx, dy, g.left, under.baseline, under.cellW, under.cellH);
			seen.add(d);
			for (int y = 0; y < under.cellH; y++) {
				for (int x = 0; x < under.cellW; x++) {
					int b = bucket(d[y][x]);
					int p = px[y * under.cellW + x];
					int a = p & 0xFF;
					sum[b] += a;
					count[b]++;
					rgb[b][0] += (p >>> 24) * a;
					rgb[b][1] += (p >> 16 & 0xFF) * a;
					rgb[b][2] += (p >> 8 & 0xFF) * a;
				}
			}
		}
		double[] alpha = new double[buckets];
		int[] colour = new int[buckets];
		int last = -1;
		for (int b = 0; b < buckets; b++) {
			alpha[b] = count[b] == 0 ? 0 : sum[b] / count[b];
			if (sum[b] > 0) {
				colour[b] = (int) (rgb[b][0] / sum[b]) << 16 | (int) (rgb[b][1] / sum[b]) << 8 | (int) (rgb[b][2] / sum[b]);
			}
			if (alpha[b] >= 4) {
				last = b;
			}
		}
		// a bucket no letter reached takes the colour of the nearest one inside it
		for (int b = 1; b < buckets; b++) {
			if (sum[b] == 0) {
				colour[b] = colour[b - 1];
			}
		}
		UnderLayer layer = new UnderLayer(alpha, colour, (last + STEPS - 1) / STEPS, dx, dy);

		// one rule must draw the shared letters about as the game does
		double error = 0;
		long pixels = 0;
		for (int i = 0; i < shared.size(); i++) {
			int[] px = under.rgba(under.cmap.get(shared.get(i)));
			double[][] d = seen.get(i);
			for (int y = 0; y < under.cellH; y++) {
				for (int x = 0; x < under.cellW; x++) {
					int actual = px[y * under.cellW + x] & 0xFF;
					double predicted = alpha[bucket(d[y][x])];
					if (actual > 0 || predicted >= 1) {
						error += Math.abs(actual - predicted);
						pixels++;
					}
				}
			}
		}
		return pixels == 0 || error / pixels > MISFIT ? null : layer;
	}

	/**
	 * The lower-layer letters for each of {@code codes} {@code top} has, made from {@code top}'s
	 * as the game makes them: a font in {@code under}'s format, sizes and metrics, its cell as
	 * large as the letters need, ready to add to {@code under} or a font standing in for it.
	 */
	public Bcfnt derive(Bcfnt top, Collection<Integer> codes, Bcfnt under) {
		Bcfnt out = new Bcfnt();
		out.format = under.format;
		out.width = under.width;
		out.height = under.height;
		out.ascent = under.ascent;
		out.lineFeed = under.lineFeed;
		out.cellW = top.cellW + 2 * reach + Math.abs(dx);
		out.baseline = top.baseline + reach + Math.max(0, -dy);
		out.cellH = out.baseline + (top.cellH - top.baseline) + reach + Math.max(0, dy);
		for (int c : codes) {
			Bcfnt.Glyph g = top.glyph(c);
			if (g == null || out.has(c)) {
				continue;
			}
			int left = g.left - reach + Math.min(0, dx);
			double[][] d = distances(top, c, dx, dy, left, out.baseline, out.cellW, out.cellH);
			int[] px = new int[out.cellW * out.cellH];
			int right = -1;
			for (int y = 0; y < out.cellH; y++) {
				for (int x = 0; x < out.cellW; x++) {
					int b = bucket(d[y][x]);
					int a = (int) Math.round(alpha[b]);
					if (a > 0) {
						px[y * out.cellW + x] = Texture.fromRgba(colour[b] << 8 | a, out.format);
						right = Math.max(right, x);
					}
				}
			}
			out.cmap.put(c, out.glyphs.size());
			out.glyphs.add(new Bcfnt.Glyph(px, left, right < 0 ? 0 : right + 1, g.charWidth));
			out.maxCharWidth = Math.max(out.maxCharWidth, g.charWidth);
		}
		out.fitSheets(256, 512);
		return out;
	}

	private static int bucket(double distance) {
		return (int) Math.min(REACH * STEPS, Math.round(distance * STEPS));
	}

	/**
	 * For each pixel of a cell {@code w} x {@code h} whose letter starts {@code left} from the pen
	 * with its baseline on row {@code baseline}, how far it lies from {@code top}'s letter
	 * {@code c} moved ({@code dx}, {@code dy}): 0 on its ink.
	 */
	private static double[][] distances(Bcfnt top, int c, int dx, int dy, int left, int baseline, int w, int h) {
		Bcfnt.Glyph g = top.glyph(c);
		// this cell's top left in the top letter's cell, the offset taken back
		int ox = left - g.left - dx;
		int oy = top.baseline - baseline - dy;
		// one grid over both cells, so ink outside this one still counts
		int gx0 = Math.min(0, ox);
		int gy0 = Math.min(0, oy);
		int gw = Math.max(top.cellW, ox + w) - gx0;
		int gh = Math.max(top.cellH, oy + h) - gy0;
		double[] f = new double[gw * gh];
		java.util.Arrays.fill(f, FAR);
		for (int[] p : ink(top, c)) {
			f[(p[1] - gy0) * gw + p[0] - gx0] = 0;
		}
		squaredDistances(f, gw, gh);
		double[][] out = new double[h][w];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out[y][x] = Math.sqrt(f[(y + oy - gy0) * gw + x + ox - gx0]);
			}
		}
		return out;
	}

	/** Larger than any squared distance within a grid, for a pixel no ink is near. */
	private static final double FAR = 1e12;

	/**
	 * Replaces each cell of grid {@code f} ({@code w} x {@code h}, 0 on ink, {@link #FAR} off it)
	 * with its squared distance to the nearest ink, by columns then rows (Felzenszwalb and
	 * Huttenlocher's exact transform).
	 */
	private static void squaredDistances(double[] f, int w, int h) {
		double[] line = new double[Math.max(w, h)];
		double[] done = new double[Math.max(w, h)];
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				line[y] = f[y * w + x];
			}
			lowerEnvelope(line, h, done);
			for (int y = 0; y < h; y++) {
				f[y * w + x] = done[y];
			}
		}
		for (int y = 0; y < h; y++) {
			System.arraycopy(f, y * w, line, 0, w);
			lowerEnvelope(line, w, done);
			System.arraycopy(done, 0, f, y * w, w);
		}
	}

	/** One line's squared distances: {@code out[q]} = min over p of {@code f[p] + (q - p)²}. */
	private static void lowerEnvelope(double[] f, int n, double[] out) {
		int[] v = new int[n];
		double[] z = new double[n + 1];
		int k = 0;
		v[0] = 0;
		z[0] = Double.NEGATIVE_INFINITY;
		z[1] = Double.POSITIVE_INFINITY;
		for (int q = 1; q < n; q++) {
			double s = meet(f, q, v[k]);
			// z[0] is -infinity, so this stops at the first parabola at the latest
			while (s <= z[k]) {
				k--;
				s = meet(f, q, v[k]);
			}
			k++;
			v[k] = q;
			z[k] = s;
			z[k + 1] = Double.POSITIVE_INFINITY;
		}
		k = 0;
		for (int q = 0; q < n; q++) {
			while (z[k + 1] < q) {
				k++;
			}
			double d = q - v[k];
			out[q] = Math.min(FAR, d * d + f[v[k]]);
		}
	}

	/** Where the parabolas rooted at {@code q} and {@code p} cross. */
	private static double meet(double[] f, int q, int p) {
		return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
	}

	/** {@code c}'s ink pixels in {@code font}'s cell, as x, y. */
	private static List<int[]> ink(Bcfnt font, int c) {
		int[] px = font.rgba(font.cmap.get(c));
		List<int[]> out = new ArrayList<>();
		for (int y = 0; y < font.cellH; y++) {
			for (int x = 0; x < font.cellW; x++) {
				if ((px[y * font.cellW + x] & 0xFF) >= INK) {
					out.add(new int[] {x, y});
				}
			}
		}
		return out;
	}

	private static boolean hasInk(Bcfnt font, int c) {
		for (int p : font.rgba(font.cmap.get(c))) {
			if ((p & 0xFF) >= INK) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Where {@code c}'s weight sits from the pen, its baseline row 0: every pixel by its alpha, or
	 * with {@code inkOnly} only its ink.
	 */
	private static double[] centroid(Bcfnt font, int c, boolean inkOnly) {
		Bcfnt.Glyph g = font.glyph(c);
		int[] px = font.rgba(font.cmap.get(c));
		double x = 0;
		double y = 0;
		double w = 0;
		for (int cy = 0; cy < font.cellH; cy++) {
			for (int cx = 0; cx < font.cellW; cx++) {
				int a = px[cy * font.cellW + cx] & 0xFF;
				double weight = inkOnly ? (a >= INK ? 1 : 0) : a;
				x += (cx + g.left) * weight;
				y += (cy - font.baseline) * weight;
				w += weight;
			}
		}
		return w == 0 ? new double[2] : new double[] {x / w, y / w};
	}
}
