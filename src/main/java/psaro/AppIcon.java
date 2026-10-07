package psaro;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/** The application icon, {@code /images/psaroeditor.png}. */
public final class AppIcon {

	private static final BufferedImage IMAGE = load();

	private AppIcon() {
	}

	/** The full-size icon, or null if the resource is missing. */
	public static BufferedImage image() {
		return IMAGE;
	}

	/**
	 * The icon smoothly scaled to {@code size} pixels square, or null. Wrapped in an
	 * {@link ImageIcon} so it is fully loaded, not still being produced, when it is used.
	 */
	public static Image scaled(int size) {
		return IMAGE == null ? null
				: new ImageIcon(IMAGE.getScaledInstance(size, size, Image.SCALE_SMOOTH)).getImage();
	}

	/** The sizes a window manager picks from for title bars, taskbars and task switchers. */
	public static List<Image> windowIcons() {
		List<Image> out = new ArrayList<>();
		if (IMAGE != null) {
			for (int size : new int[] {16, 20, 24, 32, 40, 48, 64, 128, 256}) {
				out.add(scaled(size));
			}
			out.add(IMAGE);
		}
		return out;
	}

	private static BufferedImage load() {
		try (InputStream in = AppIcon.class.getResourceAsStream("/images/psaroeditor.png")) {
			return in == null ? null : ImageIO.read(in);
		} catch (IOException e) {
			return null;
		}
	}
}
