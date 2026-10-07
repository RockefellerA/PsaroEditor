package psaro.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import psaro.project.StringSearch;
import psaro.project.StringSearch.Hit;
import psaro.project.StringSearch.Result;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.text.ControlCodes;

/**
 * Searches every string for a Japanese or English phrase as it is typed, listing the matches in
 * a dropdown; picking one (click, or arrows and Enter) opens it in the editor. Disabled until a
 * romfs is open.
 */
public final class SearchBox extends JTextField {

	/** Matches listed; the rest are counted. */
	private static final int LISTED = 200;
	/** Characters of each text shown in a result. */
	private static final int EXCERPT = 48;

	private final JPopupMenu popup = new JPopupMenu();
	private final DefaultListModel<Hit> results = new DefaultListModel<>();
	private final JList<Hit> list = new JList<>(results);
	private final JLabel count = new JLabel();
	private final Timer typing = new Timer(200, e -> search());
	private StringSearch search;
	private Translations translations;
	private BiConsumer<StringTable, String> onOpen;

	public SearchBox() {
		super(18);
		putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search all strings");
		putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
		putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, new FlatSearchIcon());
		setEnabled(false);
		typing.setRepeats(false);
		getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				typing.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				typing.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				typing.restart();
			}
		});
		// back into the box with a phrase still in it: show its matches again
		addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				if (!getText().isBlank()) {
					search();
				}
			}
		});

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFocusable(false);
		list.setVisibleRowCount(12);
		list.setCellRenderer(new HitRenderer());
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int i = list.locationToIndex(e.getPoint());
				if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
					open(results.get(i));
				}
			}
		});
		count.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
		count.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		count.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		JScrollPane scroll = new JScrollPane(list);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		// as wide as the box or a readable minimum; as tall as the matches listed
		JPanel content = new JPanel(new BorderLayout()) {
			@Override
			public Dimension getPreferredSize() {
				Dimension d = super.getPreferredSize();
				return new Dimension(Math.max(SearchBox.this.getWidth(), 560), d.height);
			}
		};
		content.add(scroll, BorderLayout.CENTER);
		content.add(count, BorderLayout.SOUTH);
		popup.setFocusable(false);
		popup.setLayout(new BorderLayout());
		popup.add(content);

		bind("DOWN", "next", () -> move(1));
		bind("UP", "previous", () -> move(-1));
		bind("ENTER", "open", () -> {
			int i = Math.max(list.getSelectedIndex(), 0);
			if (popup.isVisible() && i < results.size()) {
				open(results.get(i));
			}
		});
		bind("ESCAPE", "close", () -> popup.setVisible(false));
	}

	/**
	 * Searches {@code search}, showing English from {@code translations}, and hands a picked
	 * string to {@code onOpen}; a null {@code search} disables the box.
	 */
	public void setSource(StringSearch search, Translations translations, BiConsumer<StringTable, String> onOpen) {
		this.search = search;
		this.translations = translations;
		this.onOpen = onOpen;
		setEnabled(search != null);
		popup.setVisible(false);
	}

	/** Puts the cursor in the box with its text selected, ready for a new phrase. */
	public void focus() {
		if (isEnabled()) {
			requestFocusInWindow();
			selectAll();
		}
	}

	@Override
	public Dimension getMaximumSize() {
		// a menu bar stretches what it holds; keep the box its own width
		return getPreferredSize();
	}

	private void search() {
		if (search == null || getText().isBlank()) {
			popup.setVisible(false);
			return;
		}
		Result r = search.find(getText(), LISTED);
		results.clear();
		r.hits().forEach(results::addElement);
		count.setText(r.total() == 0 ? "No string contains this."
				: r.total() > r.hits().size() ? "First " + r.hits().size() + " of " + r.total() + " matches"
				: r.total() + (r.total() == 1 ? " match" : " matches"));
		if (!results.isEmpty()) {
			list.setSelectedIndex(0);
			list.ensureIndexIsVisible(0);
		}
		list.setVisibleRowCount(Math.min(12, Math.max(results.size(), 1)));
		if (isShowing()) {
			popup.pack();
			popup.show(this, 0, getHeight());
		}
	}

	private void move(int delta) {
		if (!popup.isVisible() || results.isEmpty()) {
			return;
		}
		int i = Math.max(0, Math.min(results.size() - 1, list.getSelectedIndex() + delta));
		list.setSelectedIndex(i);
		list.ensureIndexIsVisible(i);
	}

	private void open(Hit hit) {
		popup.setVisible(false);
		onOpen.accept(hit.table(), hit.key());
	}

	private void bind(String stroke, String name, Runnable action) {
		getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), name);
		getActionMap().put(name, new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				action.run();
			}
		});
	}

	/** "table › key", then the Japanese and the English, each cut short. */
	private final class HitRenderer extends DefaultListCellRenderer {
		@Override
		public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean selected,
				boolean focus) {
			Hit h = (Hit) value;
			String en = translations.get(h.table(), h.key());
			String text = "<html><span style='color:#8a8a8a'>" + escape(h.table().name() + " › " + h.key())
					+ "</span>&nbsp;&nbsp;" + escape(excerpt(h.table().strings().get(h.key())))
					+ (en == null ? "" : "&nbsp;&nbsp;→&nbsp;&nbsp;<b>" + escape(excerpt(en)) + "</b>") + "</html>";
			return super.getListCellRendererComponent(l, text, i, selected, focus);
		}

		private static String excerpt(String raw) {
			String s = ControlCodes.summary(raw);
			return s.length() <= EXCERPT ? s : s.substring(0, EXCERPT - 1) + "…";
		}

		private static String escape(String s) {
			return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		}
	}
}
