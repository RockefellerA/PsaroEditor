package psaro.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
import psaro.project.PaneLinks;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.PaneRef;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.romfs.RomfsIndex.Usage;
import psaro.text.ControlCodes;

/**
 * Picks the panes a string is shown in, for one the game draws from code ({@link
 * psaro.project.PaneLinks}): every text pane in the romfs, those suggested first (the panes of a
 * layout's string with the same text, {@link #suggestions}), unticked, then those linked, then
 * those whose key no table has (the panes the game fills from code), filtered by what is typed.
 *
 * <p>The ticks can be copied and pasted into another string's, for strings shown in the same
 * panes (every character's name in a data table): the copy is kept while the editor runs. Or
 * they can be given to the table's other strings in one go ({@link OtherStrings}).
 */
final class LinkPanesDialog extends JDialog {

	/** One pane, wherever its layout is, how it reads in the list, and whether it is suggested. */
	private record Entry(PaneRef ref, String label, boolean noString, boolean suggested) {
	}

	/** A printf placeholder ({@code %ls}, {@code %d}) or a layout string's ({@code [職業名]}). */
	private static final Pattern PLACEHOLDER = Pattern.compile("%[-0-9.]*l?[sdiuxXfc]|\\[[^\\]]*\\]");
	/** Fewer letters than this besides placeholders match too much ({@code Lv %d}) to suggest. */
	private static final int SUGGEST_MIN_LETTERS = 2;

	/** What was chosen: the panes, and the strings to link them to (the one asked about first). */
	record Choice(List<PaneRef> panes, List<String> keys) {
	}

	/** The ticks last copied, in list order, and the string they were copied from. */
	private static List<PaneRef> copied = List.of();
	private static String copiedFrom;

	private final List<Entry> all;
	private final Set<PaneRef> chosen;
	private final DefaultListModel<Entry> shown = new DefaultListModel<>();
	private final JTextField filter = new JTextField(30);
	private final JButton copy = new JButton("Copy selections");
	private final JButton paste = new JButton("Paste selections");
	private final JButton others = new JButton();
	/** Other strings of the table to link the same panes to, in the table's order; null until picked. */
	private List<String> alsoFor;
	private boolean accepted;

	private LinkPanesDialog(Window owner, RomfsIndex index, StringTable table, String key, PaneLinks links) {
		super(owner, "Panes showing " + table.name() + " › " + key, ModalityType.APPLICATION_MODAL);
		String what = table.name() + " › " + key;
		this.chosen = new LinkedHashSet<>(links.get(table, key));
		Map<PaneRef, String> suggested = suggestions(index, table, key);
		this.all = entries(index, chosen, suggested);

		JLabel help = new JLabel("<html>Tick the panes the game shows this string in. The preview and the fit check "
				+ "then use them, and the patch gives their fonts the letters the English needs. "
				+ (suggested.isEmpty() ? "" : "Panes that show the same text from a layout's own string come first, "
						+ "suggested but not ticked: the same words can be on more than one screen, so check the game. ")
				+ "Panes whose key no table has, the ones the game fills from code, come next.</html>");
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

		copy.addActionListener(e -> copySelections(what));
		paste.addActionListener(e -> {
			pasteSelections();
			list.repaint();
		});
		// in the list the keys copy and paste the ticks; the filter keeps them for its text
		list.getInputMap().put(KeyStroke.getKeyStroke("ctrl C"), "copyTicks");
		list.getActionMap().put("copyTicks", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				copy.doClick();
			}
		});
		list.getInputMap().put(KeyStroke.getKeyStroke("ctrl V"), "pasteTicks");
		list.getActionMap().put("pasteTicks", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				paste.doClick();
			}
		});

		others.addActionListener(e -> {
			List<String> picked = OtherStrings.pick(this, table, key, links, alsoFor);
			if (picked != null) {
				alsoFor = picked;
				updateOthers();
			}
		});
		updateOthers();

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
		JPanel clipboard = new JPanel(new FlowLayout(FlowLayout.LEFT));
		clipboard.add(copy);
		clipboard.add(paste);
		clipboard.add(others);
		JPanel close = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		close.add(ok);
		close.add(cancel);
		JPanel buttons = new JPanel(new BorderLayout());
		buttons.add(clipboard, BorderLayout.WEST);
		buttons.add(close, BorderLayout.EAST);
		updateClipboard();
		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.add(top, BorderLayout.NORTH);
		content.add(new JScrollPane(list), BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);
		refilter();
		setSize(860, 480);
		// the window's width less its frame and the content's margins
		wrapAt(help, 860 - 40);
		setLocationRelativeTo(owner);
	}

	/**
	 * Asks which panes show {@code key} of {@code table}, those {@code links} has ticked to begin
	 * with, and which of the table's other strings to link them to as well; null when cancelled.
	 */
	static Choice choose(Component parent, RomfsIndex index, StringTable table, String key, PaneLinks links) {
		Window owner = parent == null ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
		LinkPanesDialog d = new LinkPanesDialog(owner, index, table, key, links);
		d.setVisible(true);
		if (!d.accepted) {
			return null;
		}
		List<String> keys = new ArrayList<>();
		keys.add(key);
		if (d.alsoFor != null) {
			keys.addAll(d.alsoFor);
		}
		return new Choice(d.ticked(), keys);
	}

	private void updateOthers() {
		int n = alsoFor == null ? 0 : alsoFor.size();
		others.setText(n == 0 ? "Apply to other strings…" : "Apply to " + n + " other string" + (n == 1 ? "" : "s") + "…");
		others.setToolTipText("<html>Link these panes to more of this table's strings when you press OK, for strings "
				+ "the game shows in the same places (every character's name, say). What they had is replaced; "
				+ "no ticks unlinks them.</html>");
	}

	/** The ticked panes in list order, so the file reads the same whatever order they were ticked in. */
	private List<PaneRef> ticked() {
		return all.stream().map(Entry::ref).filter(chosen::contains).toList();
	}

	private void toggle(Entry e) {
		if (!chosen.remove(e.ref())) {
			chosen.add(e.ref());
		}
		updateClipboard();
	}

	/** Keeps the ticks as they stand, {@code what}'s, for {@link #pasteSelections} here or in another string's. */
	private void copySelections(String what) {
		copied = ticked();
		copiedFrom = what;
		updateClipboard();
	}

	/** Ticks the panes copied, and only them, bringing them to the top of the list below the suggestions. */
	private void pasteSelections() {
		chosen.clear();
		chosen.addAll(copied);
		all.sort(order(chosen));
		refilter();
		updateClipboard();
	}

	private void updateClipboard() {
		copy.setEnabled(!chosen.isEmpty());
		copy.setToolTipText("Copy the " + chosen.size() + " ticked pane" + (chosen.size() == 1 ? "" : "s")
				+ ", to paste into another string's (Ctrl+C in the list)");
		paste.setEnabled(!copied.isEmpty());
		paste.setToolTipText(copied.isEmpty() ? "Copy a string's ticks first, then paste them here"
				: "Tick the " + copied.size() + " pane" + (copied.size() == 1 ? "" : "s") + " copied from " + copiedFrom
						+ " instead of these (Ctrl+V in the list)");
	}

	private void refilter() {
		String[] words = filter.getText().toLowerCase(Locale.ROOT).trim().split("\\s+");
		List<Entry> matching = new ArrayList<>();
		for (Entry e : all) {
			String label = e.label().toLowerCase(Locale.ROOT);
			boolean match = true;
			for (String w : words) {
				match &= label.contains(w);
			}
			if (match) {
				matching.add(e);
			}
		}
		// added all at once: added one by one, each addition had the list measure every line again
		shown.clear();
		shown.addAll(matching);
	}

	/**
	 * Every text pane once per layout and name: suggested first, each naming the string it was
	 * suggested by, then linked, then those with no string, then by layout.
	 */
	private static List<Entry> entries(RomfsIndex index, Set<PaneRef> linked, Map<PaneRef, String> suggested) {
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
			String by = suggested.get(ref);
			out.add(new Entry(ref, String.format(Locale.ROOT, "%s › %s › %s   (%s%s, %s, %.0f×%.0f)%s", archive, layout,
					ref.pane(), keys, none ? ", no string" : "", t.font() == null ? "system font" : t.font().replace(".bcfnt", ""),
					t.boxWidth(), t.boxHeight(), by == null ? "" : "   suggested: same text as " + by), none, by != null));
		});
		out.sort(order(linked));
		return out;
	}

	/** Those suggested first, then those in {@code linked}, then those with no string, then by layout and pane. */
	private static Comparator<Entry> order(Set<PaneRef> linked) {
		return Comparator.comparing((Entry e) -> !e.suggested()).thenComparing(e -> !linked.contains(e.ref()))
				.thenComparing(e -> !e.noString()).thenComparing(e -> e.ref().layout()).thenComparing(e -> e.ref().pane());
	}

	/**
	 * The panes to suggest for {@code key} of {@code table}, a string drawn from code: those that
	 * show a layout's own string ({@code text/*.tdt}) with the same text, its placeholders and
	 * spaces aside ({@code %lsは\n%lsに転職した！} and {@code [キャラクター名]は\n[職業名]に転職した！}),
	 * each with the string it shows ({@code dharma_complete › dsjc_1010}). The same words can be on
	 * more than one screen, so these are suggestions, never ticked; a text of fewer than
	 * {@link #SUGGEST_MIN_LETTERS} letters besides placeholders suggests none.
	 */
	static Map<PaneRef, String> suggestions(RomfsIndex index, StringTable table, String key) {
		String text = table.strings().get(key);
		if (table.kind() == RomfsIndex.Kind.TABLE || table.builtIn() || text == null) {
			return Map.of();
		}
		String same = sameText(text);
		if (same.replace("\0", "").length() < SUGGEST_MIN_LETTERS) {
			return Map.of();
		}
		Map<PaneRef, String> out = new LinkedHashMap<>();
		for (StringTable t : index.tables()) {
			if (t.kind() != RomfsIndex.Kind.TABLE) {
				continue;
			}
			t.strings().forEach((k, v) -> {
				if (sameText(v).equals(same)) {
					for (Usage u : index.usages(t, k)) {
						out.putIfAbsent(new PaneRef(u.layout(), u.pane().name()), t.name() + " › " + k);
					}
				}
			});
		}
		return out;
	}

	/** {@code text} with each placeholder made one mark, {@code %%} the {@code %} it prints, and no spaces. */
	private static String sameText(String text) {
		return PLACEHOLDER.matcher(text).replaceAll("\0").replace("%%", "%").replaceAll("[ 　]", "");
	}

	/**
	 * The strings of {@code table} besides {@code key} in its column that nothing is linked to yet,
	 * in the table's order: for a data table, the cells of the same column ({@code dqc0102a:1}
	 * beside {@code dqc0101a:1}); for any other table, none.
	 */
	static List<String> sameColumnUnlinked(StringTable table, String key, PaneLinks links) {
		if (!table.data()) {
			return List.of();
		}
		String column = column(key);
		return table.strings().keySet().stream()
				.filter(k -> !k.equals(key) && column(k).equals(column) && links.get(table, k).isEmpty()).toList();
	}

	private static String column(String key) {
		return key.substring(key.lastIndexOf(':') + 1);
	}

	/**
	 * Gives a label of HTML the height its text takes wrapped at {@code width}: on its own it is
	 * given one line's height before it knows its width, and its second line runs into what is
	 * below it.
	 */
	static void wrapAt(JLabel label, int width) {
		if (!(label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) instanceof javax.swing.text.View v)) {
			return;
		}
		v.setSize(width, 0);
		java.awt.Insets in = label.getInsets();
		label.setPreferredSize(new java.awt.Dimension(width + in.left + in.right,
				(int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + in.top + in.bottom));
	}

	/**
	 * Picks other strings of a table to link the same panes to: each with its Japanese and how
	 * many panes it is linked to already, filtered by what is typed. A data table's strings in the
	 * same column that nothing is linked to yet come ticked.
	 */
	private static final class OtherStrings extends JDialog {

		private record Row(String key, String label) {
		}

		private final List<Row> rows = new ArrayList<>();
		private final Set<String> picked = new LinkedHashSet<>();
		private final DefaultListModel<Row> shown = new DefaultListModel<>();
		private final JTextField filter = new JTextField(30);
		private final JLabel count = new JLabel();
		private boolean accepted;

		private OtherStrings(Window owner, StringTable table, String key, PaneLinks links, List<String> before) {
			super(owner, "Link the same panes to…", ModalityType.APPLICATION_MODAL);
			table.strings().forEach((k, jp) -> {
				if (!k.equals(key)) {
					int n = links.get(table, k).size();
					rows.add(new Row(k, k + "   " + ControlCodes.summary(jp)
							+ (n == 0 ? "" : "   (linked to " + n + " pane" + (n == 1 ? "" : "s") + ")")));
				}
			});
			picked.addAll(before != null ? before : sameColumnUnlinked(table, key, links));

			JLabel help = new JLabel("<html>Tick the strings the game shows in the same panes as " + key + ". Pressing OK "
					+ "in the panes window links its ticked panes to them too, in place of what they had."
					+ (table.data() ? " The strings of the same column that are not linked yet come ticked." : "") + "</html>");
			help.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter by key or Japanese");
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

			JList<Row> list = new JList<>(shown);
			list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			list.setVisibleRowCount(16);
			JCheckBox cell = new JCheckBox();
			list.setCellRenderer((l, r, i, selected, focus) -> {
				cell.setText(r.label());
				cell.setSelected(picked.contains(r.key()));
				cell.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
				cell.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
				cell.setOpaque(true);
				return cell;
			});
			list.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					int i = list.locationToIndex(e.getPoint());
					if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
						toggle(shown.get(i).key());
						list.repaint();
					}
				}
			});
			list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
			list.getActionMap().put("toggle", new AbstractAction() {
				@Override
				public void actionPerformed(java.awt.event.ActionEvent e) {
					if (list.getSelectedValue() != null) {
						toggle(list.getSelectedValue().key());
						list.repaint();
					}
				}
			});

			// the quick picks act on the strings the filter shows
			JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT));
			if (table.data()) {
				JButton column = new JButton("Same column");
				column.setToolTipText("Tick every string in column " + column(key) + ", linked already or not");
				column.addActionListener(e -> {
					for (int i = 0; i < shown.size(); i++) {
						if (column(shown.get(i).key()).equals(column(key))) {
							picked.add(shown.get(i).key());
						}
					}
					changed(list);
				});
				quick.add(column);
			}
			JButton everything = new JButton("All");
			everything.addActionListener(e -> {
				for (int i = 0; i < shown.size(); i++) {
					picked.add(shown.get(i).key());
				}
				changed(list);
			});
			JButton none = new JButton("None");
			none.addActionListener(e -> {
				for (int i = 0; i < shown.size(); i++) {
					picked.remove(shown.get(i).key());
				}
				changed(list);
			});
			quick.add(everything);
			quick.add(none);
			quick.add(count);

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
			JPanel close = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			close.add(ok);
			close.add(cancel);
			JPanel buttons = new JPanel(new BorderLayout());
			buttons.add(quick, BorderLayout.WEST);
			buttons.add(close, BorderLayout.EAST);

			JPanel top = new JPanel(new BorderLayout());
			top.add(help, BorderLayout.NORTH);
			top.add(filter, BorderLayout.CENTER);
			JPanel content = new JPanel(new BorderLayout(0, 6));
			content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
			content.add(top, BorderLayout.NORTH);
			content.add(new JScrollPane(list), BorderLayout.CENTER);
			content.add(buttons, BorderLayout.SOUTH);
			setContentPane(content);
			refilter();
			changed(list);
			setSize(640, 480);
			wrapAt(help, 640 - 40);
			setLocationRelativeTo(owner);
		}

		/** The strings picked, in the table's order; null when cancelled. */
		static List<String> pick(Component parent, StringTable table, String key, PaneLinks links, List<String> before) {
			OtherStrings d = new OtherStrings(javax.swing.SwingUtilities.getWindowAncestor(parent), table, key, links, before);
			d.setVisible(true);
			return d.accepted ? d.rows.stream().map(Row::key).filter(d.picked::contains).toList() : null;
		}

		private void toggle(String k) {
			if (!picked.remove(k)) {
				picked.add(k);
			}
			count.setText(picked.size() + " ticked");
		}

		private void changed(JList<Row> list) {
			count.setText(picked.size() + " ticked");
			list.repaint();
		}

		private void refilter() {
			String needle = filter.getText().trim().toLowerCase(Locale.ROOT);
			List<Row> matching = rows.stream().filter(r -> needle.isEmpty() || r.label().toLowerCase(Locale.ROOT).contains(needle))
					.toList();
			// all at once, as the panes list is
			shown.clear();
			shown.addAll(matching);
		}
	}
}
