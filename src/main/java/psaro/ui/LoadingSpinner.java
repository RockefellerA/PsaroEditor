package psaro.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.UIManager;

/** A turning arc in the theme's accent colour, for something that takes a moment. Turns only while shown. */
public final class LoadingSpinner extends JComponent {

	private static final int SIZE = 40;
	private static final float STROKE = 4f;

	private int angle;
	private final Timer timer = new Timer(16, e -> {
		angle = (angle + 6) % 360;
		repaint();
	});

	public LoadingSpinner() {
		setPreferredSize(new Dimension(SIZE, SIZE));
		setMinimumSize(getPreferredSize());
	}

	@Override
	public void addNotify() {
		super.addNotify();
		timer.start();
	}

	@Override
	public void removeNotify() {
		timer.stop();
		super.removeNotify();
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		double d = Math.min(getWidth(), getHeight()) - STROKE;
		double x = (getWidth() - d) / 2;
		double y = (getHeight() - d) / 2;
		Color track = UIManager.getColor("Separator.foreground");
		if (track != null) {
			g2.setColor(track);
			g2.draw(new Ellipse2D.Double(x, y, d, d));
		}
		Color accent = UIManager.getColor("Component.accentColor");
		g2.setColor(accent != null ? accent : getForeground());
		g2.draw(new Arc2D.Double(x, y, d, d, -angle, 100, Arc2D.OPEN));
		g2.dispose();
	}
}
