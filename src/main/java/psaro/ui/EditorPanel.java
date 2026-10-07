package psaro.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.patch.FontPatcher;
import psaro.project.CodeColors;
import psaro.project.Translations;
import psaro.project.UnusedTables;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.text.ControlCodes;
import psaro.translate.GoogleTranslate;

/**
 * The string editor: the string tables on the left, the chosen table's strings, and below them
 * the chosen string's Japanese, its English, and the preview. Every edit goes straight into
 * {@link Translations}; saving is the window's job.
 */
public final class EditorPanel extends JPanel {

	private static final Color PROBLEM = new Color(0xE0, 0x55, 0x55);

	private final RomfsIndex index;
	private final Translations translations;
	private final Runnable onChange;
	private final CodeColors colors;
	/** Tables marked as never shown by the game: greyed and left out of the progress. */
	private final UnusedTables unused;
	/** The fonts the fit is measured in, as the font patch would leave them. */
	private final FontPatcher fonts;
	/** The window's Save and Patch buttons, shown at the right of the bar above the strings. */
	private final JComponent actions;

	private final JList<StringTable> tables;
	private final StringsModel strings = new StringsModel();
	private final JTable stringTable = new JTable(strings);
	private final TableRowSorter<StringsModel> sorter = new TableRowSorter<>(strings);
	private final JTextField filter = new JTextField(18);
	private final JCheckBox untranslatedOnly = new JCheckBox("Untranslated only");
	private final JTextArea japanese = new JTextArea();
	private final JTextArea english = new JTextArea();
	/** Marks the string as needing no translation: the game keeps its Japanese. */
	private final JCheckBox useJapanese = new JCheckBox("Use JP");
	/** Share of all strings done, beside the "String tables" heading. */
	private final JProgressBar progress = new JProgressBar(0, 1000);
	private final PreviewPanel preview;
	private static final String TRANSLATE = "Translate with Google";
	private final JButton translate = new JButton(TRANSLATE);
	/** The machine translation of the current string: shown, never saved. */
	private final JTextArea machine = new JTextArea();
	/** A machine translation is being fetched; one at a time. */
	private boolean translating;

	/** The string in the editor, or null. */
	private StringTable table;
	private String key;
	/** Set while the editor's text is replaced programmatically, so it is not taken as an edit. */
	private boolean loading;

	public EditorPanel(FontPatcher fonts, Translations translations, CodeColors colors, UnusedTables unused,
			JComponent actions, Runnable onChange) {
		super(new BorderLayout());
		this.index = fonts.index();
		this.fonts = fonts;
		this.translations = translations;
		this.onChange = onChange;
		this.colors = colors;
		this.unused = unused;
		this.actions = actions;
		this.preview = new PreviewPanel(fonts, colors, this::codesChanged, this::layoutChanged);

		DefaultListModel<StringTable> tableItems = new DefaultListModel<>();
		index.tables().forEach(tableItems::addElement);
		tables = new JList<>(tableItems);
		tables.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		tables.setCellRenderer(new TableRenderer());
		tables.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				openTable(tables.getSelectedValue());
			}
		});
		tables.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
					int i = tables.locationToIndex(e.getPoint());
					if (i >= 0 && tables.getCellBounds(i, i).contains(e.getPoint())) {
						tables.setSelectedIndex(i);
						tableMenu(tables.getModel().getElementAt(i)).show(tables, e.getX(), e.getY());
					}
				}
			}
		});
		// the keyboard's menu key and Shift+F10 open the same menu for the selected table
		for (String stroke : new String[] {"CONTEXT_MENU", "shift F10"}) {
			tables.getInputMap().put(KeyStroke.getKeyStroke(stroke), "tableMenu");
		}
		tables.getActionMap().put("tableMenu", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int i = tables.getSelectedIndex();
				if (i >= 0) {
					var cell = tables.getCellBounds(i, i);
					tableMenu(tables.getSelectedValue()).show(tables, cell.x + 20, cell.y + cell.height);
				}
			}
		});

		JSplitPane detail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, textEditors(), preview);
		detail.setResizeWeight(0.35);
		detail.setDividerLocation(360);
		JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stringList(), detail);
		right.setResizeWeight(0.4);
		right.setDividerLocation(280);
		JScrollPane tableScroll = new JScrollPane(tables);
		JPanel left = new JPanel(new BorderLayout());
		progress.setStringPainted(true);
		progress.setPreferredSize(new Dimension(110, progress.getPreferredSize().height));
		updateProgress();
		JPanel leftHeader = new JPanel(new BorderLayout(6, 0));
		leftHeader.add(caption("String tables"), BorderLayout.WEST);
		JPanel progressBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
		progressBox.add(progress);
		leftHeader.add(progressBox, BorderLayout.CENTER);
		left.add(leftHeader, BorderLayout.NORTH);
		left.add(tableScroll, BorderLayout.CENTER);
		JSplitPane root = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
		root.setDividerLocation(240);
		add(root, BorderLayout.CENTER);

		if (!tableItems.isEmpty()) {
			tables.setSelectedIndex(0);
		}
	}

	// ── Layout ───────────────────────────────────────────────────────────────

	private JComponent stringList() {
		stringTable.setRowSorter(sorter);
		stringTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		stringTable.setFillsViewportHeight(true);
		stringTable.getTableHeader().setReorderingAllowed(false);
		int[] widths = {90, 320, 320, 70, 90};
		for (int i = 0; i < widths.length; i++) {
			stringTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		DefaultTableCellRenderer status = new StatusRenderer();
		stringTable.getColumnModel().getColumn(StringsModel.FITS).setCellRenderer(status);
		stringTable.getColumnModel().getColumn(StringsModel.GLYPHS).setCellRenderer(status);
		stringTable.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				int view = stringTable.getSelectedRow();
				openString(view < 0 ? null : strings.keyAt(stringTable.convertRowIndexToModel(view)));
			}
		});

		filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter by key, Japanese or English");
		filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
		filter.getDocument().addDocumentListener(onAnyChange(this::applyFilter));
		untranslatedOnly.addActionListener(e -> applyFilter());

		JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		filters.add(filter);
		filters.add(untranslatedOnly);
		JPanel saveBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
		saveBox.add(actions);
		JPanel bar = new JPanel(new BorderLayout());
		bar.add(filters, BorderLayout.CENTER);
		bar.add(saveBox, BorderLayout.EAST);
		JPanel panel = new JPanel(new BorderLayout());
		panel.add(bar, BorderLayout.NORTH);
		panel.add(new JScrollPane(stringTable), BorderLayout.CENTER);
		return panel;
	}

	private JComponent textEditors() {
		japanese.setEditable(false);
		japanese.setRows(4);
		english.setRows(4);
		english.getDocument().addDocumentListener(onAnyChange(this::englishEdited));
		bindKey(english, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "next", () -> step(1));
		bindKey(english, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
				"previous", () -> step(-1));
		english.setEnabled(false);

		JLabel hint = new JLabel("<html>Enter breaks a line; the game breaks one only where it runs past the box, even mid-word. Tags like &lt;GREEN&gt; are color "
				+ "codes, named under Colors… in the preview; an unnamed code shows as {10}. Ctrl+Enter goes to the next "
				+ "string, Ctrl+Shift+Enter to the previous.</html>");
		hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

		JPanel jp = new JPanel(new BorderLayout());
		jp.add(caption("Japanese"), BorderLayout.NORTH);
		jp.add(new JScrollPane(japanese), BorderLayout.CENTER);
		JPanel en = new JPanel(new BorderLayout());
		en.add(machineTranslation(), BorderLayout.NORTH);
		JPanel enBody = new JPanel(new BorderLayout());
		useJapanese.setToolTipText("This string needs no translation (a name, a number, \"？？？\"): the game keeps "
				+ "the original Japanese, and the string counts as done.");
		useJapanese.setEnabled(false);
		useJapanese.addActionListener(e -> useJapaneseToggled());
		JPanel enHeader = new JPanel(new BorderLayout());
		enHeader.add(caption("English"), BorderLayout.WEST);
		enHeader.add(useJapanese, BorderLayout.EAST);
		enBody.add(enHeader, BorderLayout.NORTH);
		enBody.add(new JScrollPane(english), BorderLayout.CENTER);
		enBody.add(hint, BorderLayout.SOUTH);
		en.add(enBody, BorderLayout.CENTER);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, jp, en);
		split.setResizeWeight(0.4);
		split.setBorder(BorderFactory.createEmptyBorder());
		return split;
	}

	/** The Translate button and its read-only result, between the Japanese and the English. */
	private JComponent machineTranslation() {
		translate.setToolTipText("Sends the Japanese (without its color codes) to Google Translate, the service behind translate.google.com, for a rough "
				+ "English reading. The result is only a reference: it is never saved. (Ctrl+T)");
		translate.setEnabled(false);
		translate.addActionListener(e -> machineTranslate());
		// anywhere in the editor, not only while one field has focus
		getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), "translate");
		getActionMap().put("translate", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (translate.isEnabled()) {
					machineTranslate();
				}
			}
		});

		machine.setEditable(false);
		machine.setLineWrap(true);
		machine.setWrapStyleWord(true);
		machine.setRows(3);
		machine.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		bar.add(translate);
		JLabel note = new JLabel("Reference only, not saved");
		note.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		note.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		bar.add(note);
		JPanel panel = new JPanel(new BorderLayout());
		panel.add(bar, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(machine);
		scroll.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	// ── Behaviour ────────────────────────────────────────────────────────────

	/** Fetches a machine translation of the current string in the background. */
	private void machineTranslate() {
		if (table == null || key == null) {
			return;
		}
		String forKey = key;
		String jp = table.strings().get(key);
		translating = true;
		translate.setEnabled(false);
		translate.setText("Translating…");
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				return GoogleTranslate.translate(jp);
			}

			@Override
			protected void done() {
				translating = false;
				translate.setText(TRANSLATE);
				translate.setEnabled(key != null);
				if (!forKey.equals(key)) {
					return; // the user moved on; the result is cached for when they come back
				}
				try {
					machine.setText(get());
				} catch (Exception ex) {
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					machine.setText(cause.getMessage());
				}
				machine.setCaretPosition(0);
			}
		}.execute();
	}

	private void openTable(StringTable t) {
		table = t;
		strings.setTable(t);
		openString(null);
		if (stringTable.getRowCount() > 0) {
			stringTable.setRowSelectionInterval(0, 0);
		}
	}

	private void openString(String k) {
		key = k;
		loading = true;
		try {
			if (table == null || k == null) {
				japanese.setText("");
				english.setText("");
				english.setEnabled(false);
				useJapanese.setSelected(false);
				useJapanese.setEnabled(false);
				machine.setText("");
				translate.setEnabled(false);
				preview.clear();
				return;
			}
			String jp = table.strings().get(k);
			String en = translations.get(table, k);
			String known = GoogleTranslate.cached(jp);
			machine.setText(known == null ? "" : known);
			machine.setCaretPosition(0);
			translate.setEnabled(!translating);
			japanese.setText(ControlCodes.toDisplay(jp, colors.names()));
			japanese.setCaretPosition(0);
			boolean kept = translations.keepsJapanese(table, k);
			useJapanese.setEnabled(true);
			useJapanese.setSelected(kept);
			// a string that keeps its Japanese shows it here, greyed out, and cannot be edited
			english.setEnabled(!kept);
			english.setText(en == null ? "" : ControlCodes.toDisplay(en, colors.names()));
			english.setCaretPosition(english.getDocument().getLength());
			preview.showString(index.usages(table, k), jp, en);
		} finally {
			loading = false;
		}
	}

	private void englishEdited() {
		if (loading || table == null || key == null) {
			return;
		}
		translations.set(table, key, ControlCodes.toRaw(english.getText(), colors.names()));
		strings.changed(key);
		tables.repaint();
		updateProgress();
		preview.setEnglish(translations.get(table, key));
		onChange.run();
	}

	/** "Use JP" ticked or unticked: the string keeps its Japanese, or goes back to needing English. */
	private void useJapaneseToggled() {
		if (loading || table == null || key == null) {
			return;
		}
		boolean keep = useJapanese.isSelected();
		boolean hasEnglish = translations.get(table, key) != null && !translations.keepsJapanese(table, key);
		if (keep && hasEnglish) {
			int choice = JOptionPane.showConfirmDialog(this,
					"Replace the English for " + key + " with the original Japanese?", "Use JP",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) {
				useJapanese.setSelected(false);
				return;
			}
		}
		translations.setKeepsJapanese(table, key, keep);
		openString(key);
		strings.changed(key);
		tables.repaint();
		updateProgress();
		onChange.run();
		if (!keep) {
			english.requestFocusInWindow();
		}
	}

	/** A table's right-click menu: mark it used or unused. */
	private JPopupMenu tableMenu(StringTable t) {
		boolean isUnused = unused.isUnused(t);
		JMenuItem toggle = new JMenuItem(isUnused ? "Mark as used" : "Mark as unused");
		toggle.setToolTipText(isUnused ? "Count this table in the progress again"
				: "This table is never shown by the game: grey it out and leave it out of the progress");
		toggle.addActionListener(e -> {
			try {
				unused.setUnused(t, !isUnused);
			} catch (IOException ex) {
				JOptionPane.showMessageDialog(this, "Could not save which tables are unused:\n" + ex.getMessage(),
						"Mark as unused", JOptionPane.ERROR_MESSAGE);
			}
			tables.repaint();
			updateProgress();
			onChange.run();
		});
		JPopupMenu menu = new JPopupMenu();
		menu.add(toggle);
		return menu;
	}

	/** The share of all strings that are done: given English or marked to keep their Japanese. */
	private void updateProgress() {
		List<StringTable> inUse = unused.inUse(index.tables());
		int done = translations.translatedCountIn(inUse);
		int total = inUse.stream().mapToInt(t -> t.strings().size()).sum();
		double share = total == 0 ? 0 : (double) done / total;
		progress.setValue((int) Math.round(share * progress.getMaximum()));
		progress.setString(String.format("%.1f%%", share * 100));
		progress.setToolTipText(String.format("%,d of %,d strings translated or kept in Japanese (tables marked unused are not counted)", done, total));
	}

	/**
	 * After a code is renamed or recolored: shows the current string with the new tags (the
	 * stored text is unchanged, only how it reads) and redraws the preview.
	 */
	/**
	 * Opens string {@code k} of table {@code t}, clearing the filter if it hides it, and puts the
	 * cursor in the English.
	 */
	public void showString(StringTable t, String k) {
		if (table != t) {
			tables.setSelectedValue(t, true);
		}
		int row = strings.indexOf(k);
		if (row < 0) {
			return;
		}
		if (stringTable.convertRowIndexToView(row) < 0) {
			filter.setText("");
			untranslatedOnly.setSelected(false);
			applyFilter();
		}
		int view = stringTable.convertRowIndexToView(row);
		stringTable.setRowSelectionInterval(view, view);
		stringTable.scrollRectToVisible(stringTable.getCellRect(view, 0, true));
		english.requestFocusInWindow();
	}

	/** Measures every string again, after the font patch settings change. */
	public void fontsChanged() {
		strings.remeasure();
		if (key != null) {
			codesChanged();
		}
	}

	/** After a pane's box or type settings change: every fit again, and the window's patch check. */
	private void layoutChanged() {
		strings.remeasure();
		onChange.run();
	}

	private void codesChanged() {
		int caret = english.getCaretPosition();
		openString(key);
		english.setCaretPosition(Math.min(caret, english.getDocument().getLength()));
	}

	/** Moves the selection {@code delta} rows and puts the cursor in the English. */
	private void step(int delta) {
		int rows = stringTable.getRowCount();
		if (rows == 0) {
			return;
		}
		int next = Math.max(0, Math.min(rows - 1, stringTable.getSelectedRow() + delta));
		stringTable.setRowSelectionInterval(next, next);
		stringTable.scrollRectToVisible(stringTable.getCellRect(next, 0, true));
		english.requestFocusInWindow();
	}

	private void applyFilter() {
		String needle = filter.getText().trim().toLowerCase(Locale.ROOT);
		boolean onlyUntranslated = untranslatedOnly.isSelected();
		sorter.setRowFilter(new RowFilter<>() {
			@Override
			public boolean include(Entry<? extends StringsModel, ? extends Integer> row) {
				String k = strings.keyAt(row.getIdentifier());
				String en = translations.get(table, k);
				if (onlyUntranslated && en != null) {
					return false;
				}
				return needle.isEmpty() || k.toLowerCase(Locale.ROOT).contains(needle)
						|| ControlCodes.summary(table.strings().get(k)).toLowerCase(Locale.ROOT).contains(needle)
						|| en != null && ControlCodes.summary(en).toLowerCase(Locale.ROOT).contains(needle);
			}
		});
	}

	// ── Pieces ───────────────────────────────────────────────────────────────

	/** The chosen table's strings, with how each fares in its panes. */
	private final class StringsModel extends AbstractTableModel {

		static final int KEY = 0, JAPANESE = 1, ENGLISH = 2, FITS = 3, GLYPHS = 4;
		private static final String[] NAMES = {"Key", "Japanese", "English", "Fits", "Glyphs"};

		private List<String> keys = List.of();
		private final Map<String, Fit> fits = new HashMap<>();

		void setTable(StringTable t) {
			keys = t == null ? List.of() : new ArrayList<>(t.strings().keySet());
			fits.clear();
			fireTableDataChanged();
		}

		String keyAt(int row) {
			return keys.get(row);
		}

		int indexOf(String k) {
			return keys.indexOf(k);
		}

		void remeasure() {
			fits.clear();
			if (!keys.isEmpty()) {
				fireTableRowsUpdated(0, keys.size() - 1);
			}
		}

		void changed(String k) {
			fits.remove(k);
			int row = keys.indexOf(k);
			if (row >= 0) {
				fireTableRowsUpdated(row, row);
			}
		}

		@Override
		public int getRowCount() {
			return keys.size();
		}

		@Override
		public int getColumnCount() {
			return NAMES.length;
		}

		@Override
		public String getColumnName(int column) {
			return NAMES[column];
		}

		@Override
		public Object getValueAt(int row, int column) {
			String k = keys.get(row);
			String en = translations.get(table, k);
			return switch (column) {
				case KEY -> k;
				case JAPANESE -> ControlCodes.summary(table.strings().get(k));
				case ENGLISH -> en == null ? "" : (translations.keepsJapanese(table, k) ? "[JP] " : "") + ControlCodes.summary(en);
				case FITS -> en == null ? "" : fitsText(fit(k, en));
				case GLYPHS -> en == null ? "" : glyphsText(fit(k, en));
				default -> "";
			};
		}

		private Fit fit(String k, String en) {
			return fits.computeIfAbsent(k, x -> Fit.check(fonts, index.usages(table, k), en, table.strings().get(k)));
		}

		/** "~" marks an estimate: some characters were measured with the stand-in typeface. */
		private static String fitsText(Fit f) {
			if (!f.shown()) {
				return "—";
			}
			String verdict = f.tooWide() && f.tooTall() ? "Too big" : f.tooWide() ? "Too wide" : f.tooTall() ? "Too tall" : "✓";
			return f.estimate() ? "~" + verdict : verdict;
		}

		/** "+N": the font lacks N characters Patch can add; "N missing": no donor has them. */
		private static String glyphsText(Fit f) {
			return !f.shown() ? "—"
					: f.missing().isEmpty() ? "✓"
					: f.unpatchable().isEmpty() ? "+" + f.missing().size()
					: f.unpatchable().size() + " missing";
		}
	}

	/** Colours a problem in the Fits and Glyphs columns. */
	private static final class StatusRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
				int row, int column) {
			super.getTableCellRendererComponent(t, value, selected, focus, row, column);
			String s = String.valueOf(value);
			String verdict = s.startsWith("~") ? s.substring(1) : s;
			boolean problem = !verdict.isEmpty() && !verdict.equals("✓") && !verdict.equals("—") && !verdict.startsWith("+");
			if (problem && !selected) {
				setForeground(PROBLEM);
			} else if (!selected) {
				setForeground(t.getForeground());
			}
			setToolTipText(s.equals("—") ? "No layout shows this string; the game draws it from code."
					: s.startsWith("~") ? "An estimate: characters the font lacks were measured with a stand-in typeface."
					: s.startsWith("+") ? "The game's font lacks these characters; Patch adds them."
					: s.endsWith(" missing") ? "No font in the romfs has these characters, so Patch cannot add them."
					: null);
			return this;
		}
	}

	/** A table's name with how many of its strings have English. */
	private final class TableRenderer extends DefaultListCellRenderer {
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int i, boolean selected,
				boolean focus) {
			StringTable t = (StringTable) value;
			super.getListCellRendererComponent(list, t.name(), i, selected, focus);
			int done = translations.translatedCount(t);
			setText(t.name() + "   " + done + " / " + t.strings().size());
			if (unused.isUnused(t)) {
				// greyed, not hidden: it can still be translated, it just does not count toward progress
				if (!selected) {
					setForeground(UIManager.getColor("Label.disabledForeground"));
				}
				setToolTipText("Marked as unused: not counted in the progress, but it can still be translated. "
						+ "Right-click to change.");
			} else {
				setToolTipText(null);
			}
			return this;
		}
	}

	private static JLabel caption(String text) {
		JLabel l = new JLabel(text);
		l.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
		l.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");
		return l;
	}

	private static DocumentListener onAnyChange(Runnable r) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				r.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				r.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				r.run();
			}
		};
	}

	private static void bindKey(JComponent c, KeyStroke stroke, String name, Runnable action) {
		c.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name);
		c.getActionMap().put(name, new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				action.run();
			}
		});
	}
}
