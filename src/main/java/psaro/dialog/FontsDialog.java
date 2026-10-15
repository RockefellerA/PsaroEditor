package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import psaro.format.OpenType;
import psaro.patch.Typeface;
import psaro.patch.Typefaces;

/**
 * File → Fonts: the typefaces the project's text can be drawn with. M PLUS Rounded 1c comes with
 * PsaroEditor; others are imported from .ttf or .otf files, which are copied into the project
 * ({@link Typefaces}) and then offered in the preview's "draws with" choices beside it. Several
 * files of one family (its weights) can be imported at once.
 */
public final class FontsDialog extends JDialog {

	private static final String PREF_FOLDER = "fontsFolder";
	/** Starts a label's HTML, wrapping it to the table's width. */
	private static final String WRAP = "<html><body style='width: 680px'>";

	private final Typefaces typefaces;
	private final Set<String> missing;
	private final Runnable onChange;
	private final Preferences prefs = Preferences.userNodeForPackage(psaro.MainWindow.class);
	private final Model model = new Model();
	private final JTable table = new JTable(model);
	private final JButton remove = new JButton("Remove");
	private final JLabel notes = new JLabel();

	/**
	 * Lists {@code typefaces}; {@code missing} are the ids of typefaces the project's settings name
	 * that are not imported here. {@code onChange} runs after each import or removal.
	 */
	public FontsDialog(Frame owner, Typefaces typefaces, Set<String> missing, Runnable onChange) {
		super(owner, "Fonts", true);
		this.typefaces = typefaces;
		this.missing = missing;
		this.onChange = onChange;
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JLabel intro = new JLabel(WRAP + "The typefaces this project's text can be drawn with, besides the game's own fonts. "
				+ "Each is offered in the preview's \"draws with\" choices.<br>Imported fonts are copied into this project, "
				+ "in " + Html.escape(String.valueOf(typefaces.folder())) + ", and are this project's alone. Choose all of a family's files (its "
				+ "weights) at once; a game font is drawn in the weight nearest its own.</html>");
		intro.setBorder(new EmptyBorder(0, 0, 8, 0));

		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setFillsViewportHeight(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.getColumnModel().getColumn(0).setPreferredWidth(170);
		table.getColumnModel().getColumn(1).setPreferredWidth(200);
		table.getColumnModel().getColumn(2).setPreferredWidth(300);
		table.getColumnModel().getColumn(3).setPreferredWidth(160);
		table.getSelectionModel().addListSelectionListener(e -> updateRemove());
		JScrollPane scroll = new JScrollPane(table);
		scroll.setPreferredSize(new java.awt.Dimension(720, 180));

		notes.setBorder(new EmptyBorder(8, 0, 0, 0));

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(new EmptyBorder(12, 16, 8, 16));
		content.add(intro, BorderLayout.NORTH);
		content.add(scroll, BorderLayout.CENTER);
		content.add(notes, BorderLayout.SOUTH);

		JButton importFonts = new JButton("Import…");
		importFonts.setToolTipText("Copy .ttf or .otf files into this project, a typeface per family");
		importFonts.addActionListener(e -> importFonts());
		remove.setToolTipText("Delete the imported typeface's files from this project");
		remove.addActionListener(e -> removeSelected());
		JButton folder = new JButton("Show folder");
		folder.setToolTipText("Open the folder the imported fonts are kept in");
		folder.setEnabled(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN));
		folder.addActionListener(e -> showFolder());
		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		left.add(importFonts);
		left.add(remove);
		left.add(folder);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		right.add(close);
		JPanel buttons = new JPanel(new BorderLayout());
		buttons.setBorder(new EmptyBorder(4, 12, 10, 12));
		buttons.add(left, BorderLayout.WEST);
		buttons.add(right, BorderLayout.EAST);
		getRootPane().setDefaultButton(close);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(content, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
				JComponent.WHEN_IN_FOCUSED_WINDOW);

		reload();
		pack();
		setLocationRelativeTo(owner);
	}

	/** Lists the typefaces again, with what is missing and what could not be read. */
	private void reload() {
		model.rows = typefaces.all().stream().filter(Typeface::bundled).toList();
		model.fireTableDataChanged();
		List<String> lines = new ArrayList<>();
		List<String> stillMissing = missing.stream().filter(id -> !typefaces.has(id)).toList();
		if (!stillMissing.isEmpty()) {
			lines.add("This project's settings name typefaces not imported here: " + String.join(", ", stillMissing)
					+ ". Their panes draw with the game font until they are imported.");
		}
		if (!typefaces.unreadable().isEmpty()) {
			lines.add("Not read from the fonts folder: " + String.join("; ", typefaces.unreadable()));
		}
		notes.setText(lines.isEmpty() ? "" : WRAP + lines.stream().map(Html::escape).collect(Collectors.joining("<br>"))
				+ "</html>");
		notes.setVisible(!lines.isEmpty());
		updateRemove();
	}

	private void updateRemove() {
		int row = table.getSelectedRow();
		remove.setEnabled(row >= 0 && !model.rows.get(row).builtIn());
	}

	private void importFonts() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Import fonts");
		chooser.setMultiSelectionEnabled(true);
		chooser.setFileFilter(new FileNameExtensionFilter("Fonts (*.ttf, *.otf)", "ttf", "otf"));
		String last = prefs.get(PREF_FOLDER, null);
		if (last != null && Files.isDirectory(Path.of(last))) {
			chooser.setCurrentDirectory(new File(last));
		}
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		File[] chosen = chooser.getSelectedFiles();
		if (chosen.length == 0) {
			return;
		}
		prefs.put(PREF_FOLDER, chosen[0].getParent());
		Typefaces.Imported result;
		try {
			result = typefaces.importFiles(Arrays.stream(chosen).map(File::toPath).toList());
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not import the fonts:\n" + e.getMessage(), "Fonts",
					JOptionPane.ERROR_MESSAGE);
			return;
		}
		reload();
		if (!result.added().isEmpty()) {
			onChange.run();
		}
		StringBuilder message = new StringBuilder();
		if (!result.added().isEmpty()) {
			message.append("Imported:\n  ").append(String.join("\n  ", result.added()));
		}
		if (!result.refused().isEmpty()) {
			message.append(message.isEmpty() ? "" : "\n\n").append("Not imported:\n  ")
					.append(String.join("\n  ", result.refused()));
		}
		JOptionPane.showMessageDialog(this, message.toString(), "Fonts",
				result.refused().isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
	}

	private void removeSelected() {
		int row = table.getSelectedRow();
		if (row < 0 || model.rows.get(row).builtIn()) {
			return;
		}
		Typeface t = model.rows.get(row);
		int answer = JOptionPane.showConfirmDialog(this, "Remove " + t.label() + " from this project?\n"
				+ "Its files are deleted from the project's fonts folder. Fonts and panes set to it keep the setting and "
				+ "draw with the game font until it is imported again.", "Fonts", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE);
		if (answer != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			typefaces.remove(t);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not remove " + t.label() + ":\n" + e.getMessage(), "Fonts",
					JOptionPane.ERROR_MESSAGE);
		}
		reload();
		onChange.run();
	}

	private void showFolder() {
		try {
			Files.createDirectories(typefaces.folder());
			Desktop.getDesktop().open(typefaces.folder().toFile());
		} catch (IOException | RuntimeException e) {
			JOptionPane.showMessageDialog(this, "Could not open " + typefaces.folder() + ":\n" + e.getMessage(), "Fonts",
					JOptionPane.ERROR_MESSAGE);
		}
	}

	/** One typeface per row: its name, its weights, its files, and where it comes from. */
	private static final class Model extends AbstractTableModel {
		private static final String[] COLUMNS = {"Typeface", "Weights", "Files", "From"};
		List<Typeface> rows = List.of();

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMNS[column];
		}

		@Override
		public Object getValueAt(int row, int column) {
			Typeface t = rows.get(row);
			return switch (column) {
				case 0 -> t.label();
				case 1 -> t.weights().keySet().stream().map(OpenType::weightName).collect(Collectors.joining(", "));
				case 2 -> String.join(", ", t.weights().values());
				default -> t.builtIn() ? "Comes with PsaroEditor" : "Imported";
			};
		}
	}

	/** Text made safe to show in an HTML label. */
	private static final class Html {
		static String escape(String s) {
			return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		}
	}
}
