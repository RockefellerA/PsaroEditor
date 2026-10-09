package psaro.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.PaneRef;
import psaro.romfs.RomfsIndex.Usage;

/**
 * Picks the panes a string is shown in, for one the game draws from code ({@link
 * psaro.project.PaneLinks}): every text pane in the romfs, those linked first, then those whose
 * key no table has (the panes the game fills from code), filtered by what is typed.
 */
final class LinkPanesDialog extends JDialog {

	/** One pane, wherever its layout is, and how it reads in the list. */
	private record Entry(PaneRef ref, String label, boolean noString) {
	}

	private final List<Entry> all;
	private final Set<PaneRef> chosen;
	private final DefaultListModel<Entry> shown = new DefaultListModel<>();
	private final JTextField filter = new JTextField(30);
	private boolean accepted;

	private LinkPanesDialog(Window owner, RomfsIndex index, String what, Collection<PaneRef> linked) {
		super(owner, "Panes showing " + what, ModalityType.APPLICATION_MODAL);
		this.chosen = new LinkedHashSet<>(linked);
		this.all = entries(index, chosen);

		JLabel help = new JLabel("<html>Tick the panes the game shows this string in. The preview and the fit check "
				+ "then use them, and the patch gives their fonts the letters the English needs. Panes whose key no "
				+ "table has, the ones the game fills from code, come first.</html>");
		help.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter by archive, layout, pane or key");
		filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
		filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				refilter();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				refilter();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				refilter();
			}
		});

		JList<Entry> list = new JList<>(shown);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setVisibleRowCount(16);
		JCheckBox cell = new JCheckBox();
		ListCellRenderer<Entry> renderer = (l, e, i, selected, focus) -> {
			cell.setText(e.label());
			cell.setSelected(chosen.contains(e.ref()));
			cell.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
			cell.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
			cell.setOpaque(true);
			return cell;
		};
		list.setCellRenderer(renderer);
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int i = list.locationToIndex(e.getPoint());
				if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
					toggle(shown.get(i));
					list.repaint();
				}
			}
		});
		list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
		list.getActionMap().put("toggle", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (list.getSelectedValue() != null) {
					toggle(list.getSelectedValue());
					list.repaint();
				}
			}
		});

		JButton ok = new JButton("OK");
		ok.addActionListener(e -> {
			accepted = true;
			dispose();
		});
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());
		getRootPane().setDefaultButton(ok);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);

		JPanel top = new JPanel(new BorderLayout());
		top.add(help, BorderLayout.NORTH);
		top.add(filter, BorderLayout.CENTER);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(ok);
		buttons.add(cancel);
		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.add(top, BorderLayout.NORTH);
		content.add(new JScrollPane(list), BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);
		refilter();
		setSize(720, 480);
		setLocationRelativeTo(owner);
	}

	/**
	 * Asks which panes show {@code what} (the string, as the title names it), {@code linked}
	 * ticked to begin with; null when cancelled.
	 */
	static List<PaneRef> choose(Component parent, RomfsIndex index, String what, Collection<PaneRef> linked) {
		Window owner = parent == null ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
		LinkPanesDialog d = new LinkPanesDialog(owner, index, what, linked);
		d.setVisible(true);
		if (!d.accepted) {
			return null;
		}
		// in list order, so the file reads the same whatever order they were ticked in
		return d.all.stream().map(Entry::ref).filter(d.chosen::contains).toList();
	}

	private void toggle(Entry e) {
		if (!chosen.remove(e.ref())) {
			chosen.add(e.ref());
		}
	}

	private void refilter() {
		String[] words = filter.getText().toLowerCase(Locale.ROOT).trim().split("\\s+");
		shown.clear();
		for (Entry e : all) {
			String label = e.label().toLowerCase(Locale.ROOT);
			boolean match = true;
			for (String w : words) {
				match &= label.contains(w);
			}
			if (match) {
				shown.addElement(e);
			}
		}
	}

	/** Every text pane once per layout and name: linked first, then those with no string, then by layout. */
	private static List<Entry> entries(RomfsIndex index, Set<PaneRef> linked) {
		Set<PaneRef> noString = new LinkedHashSet<>();
		for (Usage u : index.unresolved()) {
			noString.add(new PaneRef(u.layout(), u.pane().name()));
		}
		Map<PaneRef, List<Usage>> byRef = new LinkedHashMap<>();
		for (Usage u : index.textPanes()) {
			byRef.computeIfAbsent(new PaneRef(u.layout(), u.pane().name()), k -> new ArrayList<>()).add(u);
		}
		List<Entry> out = new ArrayList<>();
		byRef.forEach((ref, uses) -> {
			Usage u = uses.get(0);
			var t = u.pane().text();
			String archive = u.archive().getFileName().toString().replace(".arc.lz", "")
					+ (uses.size() > 1 ? " +" + (uses.size() - 1) : "");
			String layout = ref.layout().substring(ref.layout().lastIndexOf('/') + 1).replace(".bclyt", "");
			boolean none = noString.contains(ref) || u.pane().keys().isEmpty();
			String keys = u.pane().keys().isEmpty() ? "no key" : String.join(", ", u.pane().keys());
			out.add(new Entry(ref, String.format(Locale.ROOT, "%s › %s › %s   (%s%s, %s, %.0f×%.0f)", archive, layout,
					ref.pane(), keys, none ? ", no string" : "", t.font() == null ? "system font" : t.font().replace(".bcfnt", ""),
					t.boxWidth(), t.boxHeight()), none));
		});
		out.sort(Comparator.comparing((Entry e) -> !linked.contains(e.ref())).thenComparing(e -> !e.noString())
				.thenComparing(e -> e.ref().layout()).thenComparing(e -> e.ref().pane()));
		return out;
	}
}
