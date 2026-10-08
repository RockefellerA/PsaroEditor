package psaro.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A left-aligned {@link FlowLayout} whose preferred height counts the rows it wraps to, so a bar
 * in a narrow panel grows a line instead of cutting off what does not fit.
 */
final class WrapLayout extends FlowLayout {

	WrapLayout(int hgap, int vgap) {
		super(LEFT, hgap, vgap);
	}

	@Override
	public Dimension preferredLayoutSize(Container target) {
		return size(target, true);
	}

	@Override
	public Dimension minimumLayoutSize(Container target) {
		Dimension d = size(target, false);
		d.width -= getHgap() + 1;
		return d;
	}

	/** The size laid out in rows no wider than the space the container is given. */
	private Dimension size(Container target, boolean preferred) {
		synchronized (target.getTreeLock()) {
			int width = target.getSize().width;
			if (width == 0) {
				// not laid out yet: take the parent's width, as the bar will fill it
				Container parent = target.getParent();
				width = parent == null || parent.getSize().width == 0 ? Integer.MAX_VALUE : parent.getSize().width;
			}
			Insets in = target.getInsets();
			int room = width - in.left - in.right - 2 * getHgap();
			int w = 0;
			int h = 0;
			int rowW = 0;
			int rowH = 0;
			for (Component c : target.getComponents()) {
				if (!c.isVisible()) {
					continue;
				}
				Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
				if (rowW > 0 && rowW + getHgap() + d.width > room) {
					w = Math.max(w, rowW);
					h += rowH + getVgap();
					rowW = 0;
					rowH = 0;
				}
				rowW += (rowW > 0 ? getHgap() : 0) + d.width;
				rowH = Math.max(rowH, d.height);
			}
			w = Math.max(w, rowW);
			h += rowH;
			Dimension out = new Dimension(w + in.left + in.right + 2 * getHgap(), h + in.top + in.bottom + 2 * getVgap());
			// inside a scroll pane the width is the viewport's, so do not ask for more
			if (SwingUtilities.getAncestorOfClass(JScrollPane.class, target) != null && target.isValid()) {
				out.width -= getHgap() + 1;
			}
			return out;
		}
	}
}
