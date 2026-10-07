package psaro.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bcfnt;
import psaro.format.Texture;
import psaro.text.ControlCodes;

/**
 * Lays out and draws a string the way the game's text panes do, to show where it lands in its
 * box and what does not fit.
 *
 * <p>The font is scaled by (font size x / {@link Bcfnt#width}, font size y / {@link Bcfnt#height}).
 * Each character advances by its char width, with the pane's character spacing between
 * characters; each glyph cell sits with its baseline row on the line's baseline, which is the
 * font's ascent below the top of the line. Lines advance by the line feed plus the pane's line
 * spacing and break only at {@code \n}. The block of lines is placed in the box by the pane's
 * text position, and each line within the block by its line alignment. Color codes take no
 * space; the game picks their colors in code, so the caller supplies them.
 *
 * <p>A character the font lacks would draw in the game as the font's fallback, a space. Here it
 * draws in a stand-in typeface at a matching size, underlined in red, so its width still counts
 * toward the fit. A null font (the 3DS system font, which no romfs carries) draws everything in the
 * stand-in, so measurements with it are estimates.
 */
public final class TextRenderer {

	/**
	 * {@code textBounds} is the block of lines in box coordinates (the box spans 0..width,
	 * 0..height). {@code image} is null from {@link #measure}.
	 */
	public record Result(BufferedImage image, Set<Integer> missing, boolean tooWide, boolean tooTall,
			Rectangle2D textBounds, boolean standInOnly) {

		public boolean overflows() {
			return tooWide || tooTall;
		}
	}

	private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
	private static final Font STAND_IN = new Font(Font.SANS_SERIF, Font.BOLD, 100);
	private static final float STAND_IN_ASCENT = STAND_IN.getLineMetrics("Ag", FRC).getAscent();
	private static final Color MISSING = new Color(0xFF, 0x55, 0x55);
	/** Slack before a block counts as not fitting, for float rounding. */
	private static final double TOLERANCE = 0.5;

	private record Placed(double x, int codePoint, Bcfnt.Glyph glyph, int colourCode) {
	}

	private record Line(List<Placed> chars, double width) {
	}

	private record Layout(List<Line> lines, double sx, double sy, double ascent, double lineFeed, Font standIn,
			Set<Integer> missing, Rectangle2D bounds, double[] lineX, boolean tooWide, boolean tooTall) {
	}

	private TextRenderer() {
	}

	/** Where {@code raw} lands in the pane and what does not fit, without drawing it. */
	public static Result measure(Bcfnt font, TextInfo info, String raw) {
		Layout l = layout(font, info, raw);
		return new Result(null, l.missing, l.tooWide, l.tooTall, l.bounds, font == null);
	}

	/** Draws {@code raw} in its pane at {@code zoom} times the pane's size, every code in the pane's color. */
	public static Result render(Bcfnt font, TextInfo info, String raw, double zoom) {
		return render(font, info, raw, zoom, Map.of());
	}

	/**
	 * Draws {@code raw} in its pane at {@code zoom} times the pane's size. {@code colors} maps a
	 * color code to its color; a code it lacks draws in the pane's own color.
	 */
	public static Result render(Bcfnt font, TextInfo info, String raw, double zoom, Map<Integer, Color> colors) {
		Layout l = layout(font, info, raw);
		return new Result(draw(font, info, l, zoom, colors), l.missing, l.tooWide, l.tooTall, l.bounds, font == null);
	}

	private static Layout layout(Bcfnt font, TextInfo info, String raw) {
		double sx = font == null ? 1 : info.fontSizeX() / font.width;
		double sy = font == null ? 1 : info.fontSizeY() / font.height;
		double ascent = font == null ? info.fontSizeY() * 0.8 : font.ascent * sy;
		double lineFeed = font == null ? info.fontSizeY() * 1.2 : font.lineFeed * sy;
		Font standIn = STAND_IN.deriveFont((float) (100 * ascent / STAND_IN_ASCENT));

		List<Line> lines = new ArrayList<>();
		Set<Integer> missing = new LinkedHashSet<>();
		for (String text : raw.split("\n", -1)) {
			List<Placed> chars = new ArrayList<>();
			double x = 0;
			int colour = 0x01;
			boolean first = true;
			for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
				int cp = text.codePointAt(i);
				if (cp == ControlCodes.COLOUR && i + 1 < text.length()) {
					colour = text.charAt(++i);
					continue;
				}
				if (cp < 0x20) {
					continue;
				}
				if (!first) {
					x += info.charSpace();
				}
				first = false;
				Bcfnt.Glyph glyph = font == null ? null : font.glyph(cp);
				if (font != null && glyph == null) {
					missing.add(cp);
				}
				chars.add(new Placed(x, cp, glyph, colour));
				x += glyph != null ? glyph.charWidth * sx : standIn.getStringBounds(Character.toString(cp), FRC).getWidth();
			}
			lines.add(new Line(chars, x));
		}

		double blockW = lines.stream().mapToDouble(Line::width).max().orElse(0);
		double blockH = lines.size() * lineFeed + (lines.size() - 1) * info.lineSpace();
		int h = Math.floorMod(info.textPosition(), 3);
		int v = Math.min(info.textPosition() / 3, 2);
		double bx = h == 0 ? 0 : h == 1 ? (info.boxWidth() - blockW) / 2 : info.boxWidth() - blockW;
		double by = v == 0 ? 0 : v == 1 ? (info.boxHeight() - blockH) / 2 : info.boxHeight() - blockH;
		// 0 follows the horizontal position; 1..3 are left, centre, right
		int align = info.lineAlignment() == 0 ? h : Math.min(info.lineAlignment() - 1, 2);
		double[] lineX = new double[lines.size()];
		for (int i = 0; i < lines.size(); i++) {
			double slack = blockW - lines.get(i).width;
			lineX[i] = bx + (align == 0 ? 0 : align == 1 ? slack / 2 : slack);
		}
		return new Layout(lines, sx, sy, ascent, lineFeed, standIn, missing,
				new Rectangle2D.Double(bx, by, blockW, blockH), lineX,
				blockW > info.boxWidth() + TOLERANCE, blockH > info.boxHeight() + TOLERANCE);
	}

	private static BufferedImage draw(Bcfnt font, TextInfo info, Layout l, double zoom, Map<Integer, Color> colors) {
		Rectangle2D box = new Rectangle2D.Double(0, 0, info.boxWidth(), info.boxHeight());
		Rectangle2D all = box.createUnion(l.bounds);
		double pad = 6 / zoom;
		double ox = (pad - all.getMinX()) * zoom;
		double oy = (pad - all.getMinY()) * zoom;
		int w = (int) Math.ceil((all.getWidth() + 2 * pad) * zoom);
		int h = (int) Math.ceil((all.getHeight() + 2 * pad) * zoom);
		BufferedImage img = new BufferedImage(Math.max(w, 1), Math.max(h, 1), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		Color base = textColour(info);
		boolean lightText = luminance(base) > 0.5;
		g.setColor(lightText ? new Color(0x26, 0x26, 0x2B) : new Color(0xE9, 0xE6, 0xDF));
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		Rectangle2D.Double boxPx = new Rectangle2D.Double(ox, oy, info.boxWidth() * zoom, info.boxHeight() * zoom);
		g.setColor(lightText ? new Color(0x3A, 0x3A, 0x42) : new Color(0xFF, 0xFF, 0xFF));
		g.fill(boxPx);

		Map<String, BufferedImage> cells = new HashMap<>();
		for (int i = 0; i < l.lines.size(); i++) {
			double lineTop = l.bounds.getY() + i * (l.lineFeed + info.lineSpace());
			double baseline = lineTop + l.ascent;
			for (Placed p : l.lines.get(i).chars) {
				double x = l.lineX[i] + p.x;
				Color colour = colors.getOrDefault(p.colourCode, base);
				if (p.glyph != null) {
					BufferedImage cell = cells.computeIfAbsent(System.identityHashCode(p.glyph) + ":" + colour.getRGB(),
							k -> cell(font, p.glyph, colour));
					double dx = ox + (x + p.glyph.left * l.sx) * zoom;
					double dy = oy + (baseline - font.baseline * l.sy) * zoom;
					AffineTransform at = new AffineTransform(l.sx * zoom, 0, 0, l.sy * zoom, dx, dy);
					g.drawImage(cell, at, null);
				} else {
					Font f = l.standIn.deriveFont((float) (l.standIn.getSize2D() * zoom));
					String s = Character.toString(p.codePoint);
					float sx = (float) (ox + x * zoom);
					float sy = (float) (oy + baseline * zoom);
					g.setFont(f);
					g.setColor(colour);
					g.drawString(s, sx, sy);
					if (font != null) {
						// missing from the game's font: underline it, keeping its colour visible
						g.setColor(MISSING);
						g.fill(new Rectangle2D.Double(sx, sy + 2 * zoom, g.getFontMetrics().stringWidth(s), Math.max(1.5, zoom)));
					}
				}
			}
		}

		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 3f}, 0f));
		g.setColor(lightText ? new Color(0x8A, 0x8A, 0x99) : new Color(0x99, 0x99, 0x99));
		g.draw(new Rectangle2D.Double(boxPx.x - 0.5, boxPx.y - 0.5, boxPx.width + 1, boxPx.height + 1));
		g.dispose();
		return img;
	}

	/** One glyph cell as ARGB, its grey level multiplied by {@code colour}. */
	private static BufferedImage cell(Bcfnt font, Bcfnt.Glyph glyph, Color colour) {
		BufferedImage img = new BufferedImage(font.cellW, font.cellH, BufferedImage.TYPE_INT_ARGB);
		int[] argb = new int[font.cellW * font.cellH];
		for (int i = 0; i < argb.length && i < glyph.pixels.length; i++) {
			int rgba = Texture.toRgba(glyph.pixels[i], font.format);
			int lum = rgba >>> 24;
			int a = (rgba & 0xFF) * colour.getAlpha() / 255;
			int r = lum * colour.getRed() / 255;
			int gr = lum * colour.getGreen() / 255;
			int b = lum * colour.getBlue() / 255;
			argb[i] = a << 24 | r << 16 | gr << 8 | b;
		}
		img.setRGB(0, 0, font.cellW, font.cellH, argb, 0, font.cellW);
		return img;
	}

	/** The pane's top colour, or white when it is fully transparent. */
	private static Color textColour(TextInfo info) {
		int c = info.topColor();
		int a = c & 0xFF;
		return a == 0 ? Color.WHITE : new Color(c >>> 24, c >> 16 & 0xFF, c >> 8 & 0xFF, a);
	}

	private static double luminance(Color c) {
		return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255;
	}
}
