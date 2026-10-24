package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

/**
 * A bar of how far along something long is, and what it is doing, over the window until it is
 * done: shown modal while a worker runs ({@link #step} from its {@code process}), disposed from its
 * {@code done}. It cannot be closed meanwhile.
 */
public final class ProgressDialog extends JDialog {

	/** What is being done, and the share of it that is, 0 to 1; below 0 when that is not known. */
	public record Step(String what, double share) {

		public static Step unknown(String what) {
			return new Step(what, -1);
		}
	}

	private static final int MAX = 1000;

	private final JLabel what = new JLabel(" ");
	private final JProgressBar bar = new JProgressBar(0, MAX);

	public ProgressDialog(Window owner, String title) {
		super(owner, title, ModalityType.APPLICATION_MODAL);
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		bar.setStringPainted(true);
		bar.setIndeterminate(true);
		bar.setString("");
		JPanel body = new JPanel(new BorderLayout(0, 8));
		body.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
		body.add(what, BorderLayout.NORTH);
		body.add(bar, BorderLayout.CENTER);
		add(body);
		pack();
		setSize(new Dimension(460, getHeight()));
		setResizable(false);
		setLocationRelativeTo(owner);
	}

	/** Shows {@code step}: its text above the bar, and its share in it. */
	public void step(Step step) {
		what.setText(step.what());
		// a file's path can be longer than the label is wide
		what.setToolTipText(step.what());
		if (step.share() < 0) {
			bar.setIndeterminate(true);
			bar.setString("");
			return;
		}
		bar.setIndeterminate(false);
		int value = (int) Math.round(Math.min(1, step.share()) * MAX);
		bar.setValue(value);
		bar.setString(value / 10 + "%");
	}
}
