package psaro.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.Icon;

/**
 * A picture pane's image as a small icon: stretched to the pane's proportions, as the game
 * stretches it to the pane's size, and fitted into a box, over a checkerboard where it is see-through.
 * The game also tints, crops and tiles some images, and a window draws a frame of several; this
 * shows the image alone.
 */
final class PictureIcon implements Icon {

	private static final Color LIGHT = new Color(0xE8E8E8);
	private static final Color DARK = new Color(0xC4C4C4);
	private static final int CHECK = 4;

	private final BufferedImage scaled;

	/**
	 * {@code image} drawn at the proportions of {@code width} × {@code height} (its own when either
	 * is not above zero), as large as fits {@code maxWidth} × {@code maxHeight}, at least a pixel each way.
	 */
	PictureIcon(BufferedImage image, double width, double height, int maxWidth, int maxHeight) {
		double w = width > 0 && height > 0 ? width : image.getWidth();
		double h = width > 0 && height > 0 ? height : image.getHeight();
		double scale = Math.min(maxWidth / w, maxHeight / h);
		int iw = Math.max(1, (int) Math.round(w * scale));
		int ih = Math.max(1, (int) Math.round(h * scale));
		scaled = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = scaled.createGraphics();
		for (int y = 0; y < ih; y += CHECK) {
			for (int x = 0; x < iw; x += CHECK) {
				g.setColor((x / CHECK + y / CHECK) % 2 == 0 ? LIGHT : DARK);
				g.fillRect(x, y, CHECK, CHECK);
			}
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, 0, 0, iw, ih, null);
		g.dispose();
	}

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y) {
		g.drawImage(scaled, x, y, null);
	}

	@Override
	public int getIconWidth() {
		return scaled.getWidth();
	}

	@Override
	public int getIconHeight() {
		return scaled.getHeight();
	}
}
