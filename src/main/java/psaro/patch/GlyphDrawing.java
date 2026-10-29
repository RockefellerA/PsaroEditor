package psaro.patch;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import psaro.format.Bcfnt;
import psaro.format.Texture;

/**
 * Draws letters from a bundled {@link Typeface} the way a game font draws its own: at its size,
 * on its baseline, in its fill colour and with its outline.
 *
 * <p>Both are read from the game font's glyphs. The look: opaque pixels split into a light and a
 * dark kind, and when the dark kind is the one that borders the transparent background, it is an
 * outline, as thick on average as its pixels divided by the edge it runs along. The size: the
 * font's kana and kanji, less that outline, against M PLUS Rounded 1c's at a known size, so a
 * letter takes the share of the Japanese a Japanese font gives it. The game's fonts set their
 * nominal size their own way, so it cannot say.
 *
 * <p>Like the game's own, an outline overhangs the letter's advance rather than widening it.
 * Every glyph sits in a cell of one size per font, so the same letter comes out the same
 * whichever others are drawn with it.
 */
public final class GlyphDrawing {

	/** A game font's look: fill and outline colours (RGBA) and the outline's thickness, 0 for none. */
	record Look(int fill, int outline, double radius) {
	}

	private static final FontRenderContext FRC = new FontRenderContext(new AffineTransform(), true, true);
	/** The size the reference typeface is measured at. */
	private static final double REFERENCE = 100;

	private GlyphDrawing() {
	}

	/**
	 * A typeface made ready to draw for one game font: sized, in its look, with its cell. Making
	 * one reads the game font's glyphs, so a font drawn in several goes keeps one.
	 */
	public static final class Pen {
		private final Font sized;
		private final Look look;
		private final Bcfnt target;
		private final int pad;
		private final int above;
		private final int below;
		private final int width;

		Pen(Typeface face, String targetName, Bcfnt target) {
			String weight = Typeface.weightFor(targetName);
			this.target = target;
			this.look = look(target);
			this.sized = face.font(weight).deriveFont((float) emSize(target, look, weight));
			// one cell size per font: tall enough for the typeface, wide enough for its ASCII
			this.pad = (int) Math.ceil(look.radius) + 1;
			var lm = sized.getLineMetrics("Ag", FRC);
			this.above = Math.max(target.baseline, (int) Math.ceil(lm.getAscent()) + pad);
			this.below = Math.max(target.cellH - target.baseline, (int) Math.ceil(lm.getDescent()) + pad);
			int w = target.cellW;
			for (int c = 0x21; c <= 0x7E; c++) {
				w = Math.max(w, (int) Math.ceil(outline(sized, c, 0, 0).getBounds2D().getWidth()) + 2 * pad);
			}
			this.width = w;
		}

		/** A font in the game font's format holding {@code codes} as drawn; a code the typeface lacks is left out. */
		public Bcfnt draw(Collection<Integer> codes) {
			Bcfnt out = new Bcfnt();
			out.format = target.format;
			out.pad = target.pad;
			out.width = target.width;
			out.height = target.height;
			out.ascent = target.ascent;
			out.lineFeed = target.lineFeed;
			out.cellW = width;
			out.cellH = above + below;
			out.baseline = above;
			for (int c : codes) {
				if (sized.canDisplay(c)) {
					out.cmap.put(c, out.glyphs.size());
					out.glyphs.add(glyph(sized, c, look, target.format, width, above, below, pad));
					out.maxCharWidth = Math.max(out.maxCharWidth, out.glyphs.get(out.glyphs.size() - 1).charWidth);
				}
			}
			return out;
		}
	}

	/**
	 * A font in {@code target}'s format holding {@code codes} as {@code face} draws them for the
	 * game font {@code targetName}; a code the typeface lacks is left out.
	 */
	public static Bcfnt draw(Typeface face, String targetName, Bcfnt target, Collection<Integer> codes) {
		return new Pen(face, targetName, target).draw(codes);
	}

	/** One letter, its pen at x = {@code pad} and its baseline on row {@code above}, cropped to its ink. */
	private static Bcfnt.Glyph glyph(Font font, int c, Look look, int format, int cellW, int above, int below, int pad) {
		GlyphVector gv = font.createGlyphVector(FRC, Character.toChars(c));
		int advance = (int) Math.round(gv.getGlyphMetrics(0).getAdvanceX());
		int canvasW = cellW + 2 * pad + (int) Math.ceil(gv.getVisualBounds().getMaxX());
		int h = above + below;
		Shape shape = gv.getOutline(pad, above);
		int[] core = coverage(shape, null, canvasW, h);
		int[] outer = look.radius > 0 ? coverage(shape, look.radius, canvasW, h) : core;

		int x0 = canvasW;
		int x1 = -1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < canvasW; x++) {
				if (outer[y * canvasW + x] > 0) {
					x0 = Math.min(x0, x);
					x1 = Math.max(x1, x);
				}
			}
		}
		int[] px = new int[cellW * h];
		if (x1 < 0) {
			// a space: no ink, only its advance
			return new Bcfnt.Glyph(px, 0, 0, advance);
		}
		int w = Math.min(x1 - x0 + 1, cellW);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int o = outer[y * canvasW + x0 + x];
				if (o == 0) {
					continue;
				}
				int f = Math.min(core[y * canvasW + x0 + x], o);
				px[y * cellW + x] = Texture.fromRgba(blend(look, f, o), format);
			}
		}
		return new Bcfnt.Glyph(px, Math.max(-128, x0 - pad), w, advance);
	}

	/** The fill over the outline: {@code fill} of {@code outer} coverage is fill, the rest outline. */
	private static int blend(Look look, int fill, int outer) {
		int rgba = 0;
		for (int shift = 24; shift >= 8; shift -= 8) {
			int f = look.fill >>> shift & 0xFF;
			int o = look.outline >>> shift & 0xFF;
			int v = (f * fill + o * (outer - fill) + outer / 2) / outer;
			rgba |= v << shift;
		}
		return rgba | outer;
	}

	/** {@code shape}'s anti-aliased coverage, 0..255 per pixel; with {@code radius}, grown by it. */
	private static int[] coverage(Shape shape, Double radius, int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setColor(Color.WHITE);
		g.fill(shape);
		if (radius != null) {
			g.setStroke(new BasicStroke((float) (2 * radius), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(shape);
		}
		g.dispose();
		int[] out = new int[w * h];
		img.getRaster().getPixels(0, 0, w, h, out);
		return out;
	}

	private static Shape outline(Font font, int c, float x, float y) {
		return font.createGlyphVector(FRC, Character.toChars(c)).getOutline(x, y);
	}

	// ------------------------------------------------------------------ look

	/**
	 * {@code font}'s fill, outline and outline thickness, from its glyphs. The colours are those of
	 * its clearly light and clearly dark opaque pixels, so the blend between them does not grey
	 * them; the thickness counts the outline's faint outer pixels by how opaque they are. Whichever
	 * kind borders the transparent background is the outline: dark round light letters (04a), or
	 * light round dark ones (10a), where the dark letters hardly meet the background.
	 */
	static Look look(Bcfnt font) {
		long[] light = new long[4];
		long[] dark = new long[4];
		long[] lightest = new long[4];
		long[] darkest = new long[4];
		long lightEdge = 0;
		long darkEdge = 0;
		// light pixels' sides against dark ones, and dark pixels' against light: where fill meets outline
		long lightBoundary = 0;
		long darkBoundary = 0;
		double darkArea = 0;
		double lightArea = 0;
		int step = Math.max(1, font.glyphs.size() / 300);
		for (int i = 0; i < font.glyphs.size(); i += step) {
			int[] px = font.rgba(i);
			for (int y = 0; y < font.cellH; y++) {
				for (int x = 0; x < font.cellW; x++) {
					int p = px[y * font.cellW + x];
					int lum = luminance(p);
					if ((p & 0xFF) >= 40) {
						if (lum < 128) {
							darkArea += (p & 0xFF) / 255.0;
						} else {
							lightArea += (p & 0xFF) / 255.0;
						}
					}
					if ((p & 0xFF) < 200) {
						continue;
					}
					boolean isLight = lum >= 128;
					long[] sum = isLight ? light : dark;
					add(sum, p);
					if (lum >= 200) {
						add(lightest, p);
					} else if (lum <= 60) {
						add(darkest, p);
					}
					boolean edge = false;
					for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						int nx = x + d[0];
						int ny = y + d[1];
						int q = nx < 0 || ny < 0 || nx >= font.cellW || ny >= font.cellH ? 0 : px[ny * font.cellW + nx];
						edge |= (q & 0xFF) < 40;
						if ((q & 0xFF) >= 40 && isLight != luminance(q) >= 128) {
							if (isLight) {
								lightBoundary++;
							} else {
								darkBoundary++;
							}
						}
					}
					if (edge) {
						if (isLight) {
							lightEdge++;
						} else {
							darkEdge++;
						}
					}
				}
			}
		}
		long all = light[3] + dark[3];
		if (all == 0) {
			return new Look(0xFFFFFFFF, 0xFFFFFFFF, 0);
		}
		if (dark[3] > 0.15 * all && light[3] > 0.15 * all) {
			int lightColour = mean(lightest[3] > 0 ? lightest : light);
			int darkColour = mean(darkest[3] > 0 ? darkest : dark);
			if (darkEdge > lightEdge && lightBoundary > 0) {
				return new Look(lightColour, darkColour, Math.max(0.5, darkArea / lightBoundary));
			}
			// light round dark letters only when the dark all but never meets the background: a drop
			// shadow (02a) or a light-to-dark gradient meets it about as often as the light does
			if (lightEdge > darkEdge && darkEdge < 0.2 * (lightEdge + darkEdge) && darkBoundary > 0) {
				return new Look(darkColour, lightColour, Math.max(0.5, lightArea / darkBoundary));
			}
		}
		// a font of dark letters (no light pixels at all) is drawn dark: each colour only from pixels it has
		int colour = light[3] >= dark[3] ? mean(lightest[3] > 0 ? lightest : light) : mean(dark);
		return new Look(colour, colour, 0);
	}

	private static void add(long[] sum, int rgba) {
		sum[0] += rgba >>> 24;
		sum[1] += rgba >> 16 & 0xFF;
		sum[2] += rgba >> 8 & 0xFF;
		sum[3]++;
	}

	private static int mean(long[] sum) {
		return (int) (sum[0] / sum[3]) << 24 | (int) (sum[1] / sum[3]) << 16 | (int) (sum[2] / sum[3]) << 8 | 0xFF;
	}

	private static int luminance(int rgba) {
		return ((rgba >>> 24) + (rgba >> 16 & 0xFF) + (rgba >> 8 & 0xFF)) / 3;
	}

	// ------------------------------------------------------------------ size

	/**
	 * The size to draw at for {@code target}: the median, over its kanji and kana, of their inked
	 * height (less the outline) against M PLUS Rounded 1c's at {@link #REFERENCE}. A font with too
	 * few of those (some hold little more than digits and a few capitals) is measured by its
	 * capitals and digits instead, and one with neither falls back to 60% of its nominal height.
	 */
	static double emSize(Bcfnt target, Look look, String weight) {
		Font reference = Typeface.M_PLUS_ROUNDED.font(weight).deriveFont((float) REFERENCE);
		// kanji first, then kana; small kana and dashes are too short to measure well
		List<Integer> japanese = new ArrayList<>(target.cmap.keySet().stream().filter(c -> c >= 0x4E00).toList());
		japanese.addAll(target.cmap.keySet().stream().filter(c -> c >= 0x3041 && c < 0x4E00).toList());
		List<Double> sizes = sizes(target, look, reference, japanese);
		if (sizes.size() < 3) {
			sizes = sizes(target, look, reference,
					target.cmap.keySet().stream().filter(c -> c >= 'A' && c <= 'Z' || c >= '0' && c <= '9').toList());
		}
		if (sizes.size() < 3) {
			return target.height * 0.6;
		}
		sizes.sort(null);
		return sizes.get(sizes.size() / 2);
	}

	/** For up to 40 of {@code chars}, the size at which the reference draws each as tall as the target. */
	private static List<Double> sizes(Bcfnt target, Look look, Font reference, List<Integer> chars) {
		List<Double> sizes = new ArrayList<>();
		for (int c : chars) {
			if (sizes.size() >= 40 || !reference.canDisplay(c)) {
				continue;
			}
			double ink = inkHeight(target, c) - 2 * look.radius;
			double ref = outline(reference, c, 0, 0).getBounds2D().getHeight();
			if (ink > 2 && ref > REFERENCE / 2) {
				sizes.add(REFERENCE * ink / ref);
			}
		}
		return sizes;
	}

	/** Rows of {@code c}'s cell holding a clearly visible pixel, top to bottom; 0 for a blank glyph. */
	private static int inkHeight(Bcfnt font, int c) {
		int[] px = font.rgba(font.cmap.get(c));
		int top = -1;
		int bottom = -1;
		for (int y = 0; y < font.cellH; y++) {
			for (int x = 0; x < font.cellW; x++) {
				if ((px[y * font.cellW + x] & 0xFF) > 64) {
					if (top < 0) {
						top = y;
					}
					bottom = y;
					break;
				}
			}
		}
		return top < 0 ? 0 : bottom - top + 1;
	}
}
