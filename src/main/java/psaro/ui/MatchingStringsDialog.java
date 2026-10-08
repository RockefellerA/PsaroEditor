package psaro.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;
import psaro.text.ControlCodes;

/**
 * Lists the strings with the same Japanese as the one being edited, ticked where they are
 * untranslated and every box has the same shape, for the editor to give them its English.
 */
final class MatchingStringsDialog extends JDialog {

	private final List<MatchingStrings.Match> matches;
	private final boolean[] ticked;
	private boolean applied;

	/**
	 * {@code japanese} and {@code english} are the string's, for the heading; {@code paneChanges}
	 * whether its panes have changes the same-shaped boxes would take.
	 */
	MatchingStringsDialog(Window owner, String japanese, String english, boolean paneChanges,
			List<MatchingStrings.Match> matches) {
		super(owner, "Apply to matching strings", ModalityType.APPLICATION_MODAL);
		this.matches = matches;
		this.ticked = new boolean[matches.size()];
		for (int i = 0; i < ticked.length; i++) {
			ticked[i] = matches.get(i).untranslated() && matches.get(i).sameBoxes();
		}

		JLabel intro = new JLabel("<html>Strings whose Japanese is exactly “" + escape(japanese) + "”. The ticked ones get “"
				+ escape(english) + "”" + (paneChanges ? ", and those whose boxes are the same shape get this string's "
						+ "font and box changes too" : "") + ". Ticked to begin with: those not yet translated whose boxes "
				+ "are all the same shape as this string's.</html>");
		intro.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
		intro.setPreferredSize(new Dimension(640, intro.getPreferredSize().height * 3));

		JTable table = new JTable(new Model());
		table.setFillsViewportHeight(true);
		int[] widths = {30, 170, 90, 200, 150};
		for (int i = 0; i < widths.length; i++) {
			table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		JScrollPane scroll = new JScrollPane(table);
		scroll.setPreferredSize(new Dimension(660, Math.min(400, 60 + 22 * matches.size())));
		JPanel middle = new JPanel(new BorderLayout());
		middle.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
		middle.add(scroll, BorderLayout.CENTER);

		JButton apply = new JButton("Apply");
		JButton all = new JButton("Tick all");
		JButton cancel = new JButton("Cancel");
		apply.addActionListener(e -> {
			applied = true;
			dispose();
		});
		all.addActionListener(e -> {
			java.util.Arrays.fill(ticked, true);
			table.repaint();
		});
		cancel.addActionListener(e -> dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
		buttons.add(all);
		buttons.add(apply);
		buttons.add(cancel);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(intro, BorderLayout.NORTH);
		getContentPane().add(middle, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(apply);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		pack();
		setLocationRelativeTo(owner);
	}

	/** The ticked matches once Apply was pressed; none when the dialog was closed otherwise. */
	List<MatchingStrings.Match> chosen() {
		List<MatchingStrings.Match> out = new ArrayList<>();
		if (applied) {
			for (int i = 0; i < ticked.length; i++) {
				if (ticked[i]) {
					out.add(matches.get(i));
				}
			}
		}
		return out;
	}

	private static String escape(String s) {
		return s == null ? "" : ControlCodes.summary(s).replace("&", "&amp;").replace("<", "&lt;");
	}

	private final class Model extends AbstractTableModel {
		private final String[] names = {"", "Table", "Key", "English now", "Boxes"};

		@Override
		public int getRowCount() {
			return matches.size();
		}

		@Override
		public int getColumnCount() {
			return names.length;
		}

		@Override
		public String getColumnName(int column) {
			return names[column];
		}

		@Override
		public Class<?> getColumnClass(int column) {
			return column == 0 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return column == 0;
		}

		@Override
		public Object getValueAt(int row, int column) {
			MatchingStrings.Match m = matches.get(row);
			return switch (column) {
				case 0 -> ticked[row];
				case 1 -> m.table().name();
				case 2 -> m.key();
				case 3 -> m.keepsJapanese() ? "[JP]" : m.english() == null ? "" : ControlCodes.summary(m.english());
				default -> m.sameBoxes() ? "same shape" : "other shape or none: English only";
			};
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			ticked[row] = Boolean.TRUE.equals(value);
		}
	}
}
