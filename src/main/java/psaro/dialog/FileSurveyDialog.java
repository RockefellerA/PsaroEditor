package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import psaro.romfs.FileSurvey;
import psaro.romfs.FileSurvey.Group;
import psaro.romfs.FileSurvey.Item;

/**
 * Tools → Identify romfs files: each format found in a romfs ({@link FileSurvey}), whether
 * PsaroEditor reads it, and how many files are of it; below, the files of the format chosen.
 */
public final class FileSurveyDialog extends JDialog {

	private final List<Group> groups;

	public FileSurveyDialog(Frame owner, Path root, List<Item> items) {
		super(owner, "Romfs files: " + root.getFileName(), false);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		groups = FileSurvey.groups(items);

		JTable formats = new JTable(new GroupModel());
		formats.setAutoCreateRowSorter(true);
		formats.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		formats.setDefaultRenderer(Long.class, new SizeRenderer());
		formats.setDefaultRenderer(Integer.class, new CountRenderer());
		int[] widths = {200, 90, 60, 80, 60, 80, 360};
		for (int c = 0; c < widths.length; c++) {
			formats.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
		}

		FileModel fileModel = new FileModel();
		JTable files = new JTable(fileModel);
		files.setAutoCreateRowSorter(true);
		files.setDefaultRenderer(Long.class, new SizeRenderer());
		files.getColumnModel().getColumn(0).setPreferredWidth(760);
		files.getColumnModel().getColumn(1).setPreferredWidth(80);
		files.getColumnModel().getColumn(2).setPreferredWidth(60);
		JLabel filesHead = new JLabel(" ");
		formats.getSelectionModel().addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) {
				return;
			}
			int row = formats.getSelectedRow();
			Group g = row < 0 ? null : groups.get(formats.convertRowIndexToModel(row));
			fileModel.show(g);
			filesHead.setText(g == null ? "Choose a format to see its files." : String.format(Locale.ROOT, "%s: %,d %s. %s.",
					g.format().name(), g.items().size(), g.items().size() == 1 ? "file" : "files", g.format().about()));
		});
		if (!groups.isEmpty()) {
			formats.setRowSelectionInterval(0, 0);
		}

		JPanel lower = new JPanel(new BorderLayout(0, 4));
		lower.add(filesHead, BorderLayout.NORTH);
		lower.add(new JScrollPane(files), BorderLayout.CENTER);
		JScrollPane upper = new JScrollPane(formats);
		upper.setPreferredSize(new Dimension(960, 300));
		lower.setPreferredSize(new Dimension(960, 280));
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, lower);
		split.setResizeWeight(0.5);
		split.setBorder(null);

		JLabel summary = new JLabel(summary(items, groups));
		summary.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		JButton copy = new JButton("Copy report");
		copy.setToolTipText("Copy each format's counts, and every file of the formats PsaroEditor does not read");
		copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(report(root, items, groups)), null));
		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
		buttons.add(copy);
		buttons.add(close);

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
		content.add(summary, BorderLayout.NORTH);
		content.add(split, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);
		pack();
		setLocationRelativeTo(owner);
	}

	/** One line on what was found: how many files, how many inside archives, and how many PsaroEditor reads. */
	static String summary(List<Item> items, List<Group> groups) {
		long inArchives = items.stream().filter(Item::inArchive).count();
		long read = items.stream().filter(i -> i.format().read()).count();
		long unreadFormats = groups.stream().filter(g -> !g.format().read()).count();
		return String.format(Locale.ROOT, "%,d files (%,d of them inside archives): %,d in formats PsaroEditor reads, "
				+ "%,d in %,d %s it does not.", items.size(), inArchives, read, items.size() - read, unreadFormats,
				unreadFormats == 1 ? "format" : "formats");
	}

	/** The report Copy report gives: each format's counts, then every file of each format not read. */
	static String report(Path root, List<Item> items, List<Group> groups) {
		StringBuilder b = new StringBuilder("Romfs files in ").append(root).append('\n').append(summary(items, groups))
				.append("\n\n");
		for (Group g : groups) {
			b.append(String.format(Locale.ROOT, "%-34s %-6s %,7d files  %,7d in archives  %,7d LZ11  %s%n", g.format().name(),
					g.format().read() ? "read" : "-", g.loose(), g.inArchives(), g.compressed(), sizeText(g.bytes())));
		}
		for (Group g : groups) {
			if (g.format().read()) {
				continue;
			}
			b.append('\n').append(g.format().name()).append(": ").append(g.format().about()).append('\n');
			for (Item i : g.items()) {
				b.append("  ").append(i.path()).append(i.compressed() ? " (LZ11)" : "").append('\n');
			}
		}
		return b.toString();
	}

	private static String sizeText(long bytes) {
		return bytes < 1024 ? bytes + " B" : bytes < 1 << 20 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
				: String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
	}

	/** Sizes as KB or MB, right-aligned, sorted by the bytes they stand for. */
	private static final class SizeRenderer extends DefaultTableCellRenderer {
		SizeRenderer() {
			setHorizontalAlignment(SwingConstants.RIGHT);
		}

		@Override
		protected void setValue(Object value) {
			setText(value instanceof Long n ? sizeText(n) : "");
		}
	}

	/** Counts with thousands separators, right-aligned. */
	private static final class CountRenderer extends DefaultTableCellRenderer {
		CountRenderer() {
			setHorizontalAlignment(SwingConstants.RIGHT);
		}

		@Override
		protected void setValue(Object value) {
			setText(value instanceof Integer n ? String.format(Locale.ROOT, "%,d", n) : "");
		}
	}

	private final class GroupModel extends AbstractTableModel {
		private final String[] columns = {"Format", "PsaroEditor", "Loose", "In archives", "LZ11", "Size", "What it is"};

		@Override
		public int getRowCount() {
			return groups.size();
		}

		@Override
		public int getColumnCount() {
			return columns.length;
		}

		@Override
		public String getColumnName(int c) {
			return columns[c];
		}

		@Override
		public Class<?> getColumnClass(int c) {
			return c >= 2 && c <= 4 ? Integer.class : c == 5 ? Long.class : String.class;
		}

		@Override
		public Object getValueAt(int r, int c) {
			Group g = groups.get(r);
			return switch (c) {
				case 0 -> g.format().name();
				case 1 -> g.format().read() ? "Reads" : "—";
				case 2 -> g.loose();
				case 3 -> g.inArchives();
				case 4 -> g.compressed();
				case 5 -> g.bytes();
				default -> g.format().about();
			};
		}
	}

	private static final class FileModel extends AbstractTableModel {
		private final String[] columns = {"Path", "Size", "LZ11"};
		private List<Item> items = List.of();

		void show(Group g) {
			items = g == null ? List.of() : g.items();
			fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return items.size();
		}

		@Override
		public int getColumnCount() {
			return columns.length;
		}

		@Override
		public String getColumnName(int c) {
			return columns[c];
		}

		@Override
		public Class<?> getColumnClass(int c) {
			return c == 1 ? Long.class : String.class;
		}

		@Override
		public Object getValueAt(int r, int c) {
			Item i = items.get(r);
			return switch (c) {
				case 0 -> i.path();
				case 1 -> i.size();
				default -> i.compressed() ? "LZ11" : "";
			};
		}
	}
}
