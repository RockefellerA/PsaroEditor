package psaro.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.text.ControlCodes;

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

	private final JList<StringTable> tables;
	private final StringsModel strings = new StringsModel();
	private final JTable stringTable = new JTable(strings);
	private final TableRowSorter<StringsModel> sorter = new TableRowSorter<>(strings);
	private final JTextField filter = new JTextField(18);
	private final JCheckBox untranslatedOnly = new JCheckBox("Untranslated only");
	private final JTextArea japanese = new JTextArea();
	private final JTextArea english = new JTextArea();
	private final PreviewPanel preview;

	/** The string in the editor, or null. */
	private StringTable table;
	private String key;
	/** Set while the editor's text is replaced programmatically, so it is not taken as an edit. */
	private boolean loading;

	public EditorPanel(RomfsIndex index, Translations translations, Runnable onChange) {
		super(new BorderLayout());
		this.index = index;
		this.translations = translations;
		this.onChange = onChange;
		this.preview = new PreviewPanel(index);

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

		JSplitPane detail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, textEditors(), preview);
		detail.setResizeWeight(0.35);
		detail.setDividerLocation(360);
		JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stringList(), detail);
		right.setResizeWeight(0.4);
		right.setDividerLocation(280);
		JScrollPane tableScroll = new JScrollPane(tables);
		JPanel left = new JPanel(new BorderLayout());
		left.add(caption("String tables"), BorderLayout.NORTH);
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

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		bar.add(filter);
		bar.add(untranslatedOnly);
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

		JLabel hint = new JLabel("<html>Enter breaks a line (the game never wraps). {NN} is a colour code: {01} and {09} "
				+ "return to the normal colour. Ctrl+Enter goes to the next string, Ctrl+Shift+Enter to the previous.</html>");
		hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

		JPanel jp = new JPanel(new BorderLayout());
		jp.add(caption("Japanese"), BorderLayout.NORTH);
		jp.add(new JScrollPane(japanese), BorderLayout.CENTER);
		JPanel en = new JPanel(new BorderLayout());
		en.add(caption("English"), BorderLayout.NORTH);
		en.add(new JScrollPane(english), BorderLayout.CENTER);
		en.add(hint, BorderLayout.SOUTH);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, jp, en);
		split.setResizeWeight(0.4);
		split.setBorder(BorderFactory.createEmptyBorder());
		return split;
	}

	// ── Behaviour ────────────────────────────────────────────────────────────

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
				preview.clear();
				return;
			}
			String jp = table.strings().get(k);
			String en = translations.get(table, k);
			japanese.setText(ControlCodes.toDisplay(jp));
			japanese.setCaretPosition(0);
			english.setEnabled(true);
			english.setText(en == null ? "" : ControlCodes.toDisplay(en));
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
		translations.set(table, key, ControlCodes.toRaw(english.getText()));
		strings.changed(key);
		tables.repaint();
		preview.setEnglish(translations.get(table, key));
		onChange.run();
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
				case ENGLISH -> en == null ? "" : ControlCodes.summary(en);
				case FITS -> en == null ? "" : fitsText(fit(k, en));
				case GLYPHS -> en == null ? "" : glyphsText(fit(k, en));
				default -> "";
			};
		}

		private Fit fit(String k, String en) {
			return fits.computeIfAbsent(k, x -> Fit.check(index, index.usages(table, k), en, table.strings().get(k)));
		}

		/** "~" marks an estimate: some characters were measured with the stand-in typeface. */
		private static String fitsText(Fit f) {
			if (!f.shown()) {
				return "—";
			}
			String verdict = f.tooWide() && f.tooTall() ? "Too big" : f.tooWide() ? "Too wide" : f.tooTall() ? "Too tall" : "✓";
			return f.estimate() ? "~" + verdict : verdict;
		}

		private static String glyphsText(Fit f) {
			return !f.shown() ? "—" : f.missing().isEmpty() ? "✓" : f.missing().size() + " missing";
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
			boolean problem = !verdict.isEmpty() && !verdict.equals("✓") && !verdict.equals("—");
			if (problem && !selected) {
				setForeground(PROBLEM);
			} else if (!selected) {
				setForeground(t.getForeground());
			}
			setToolTipText(s.equals("—") ? "No layout shows this string; the game draws it from code."
					: s.startsWith("~") ? "An estimate: characters the font lacks were measured with a stand-in typeface."
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
