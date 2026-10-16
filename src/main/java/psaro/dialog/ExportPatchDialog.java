package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import psaro.patch.ExportSettings;
import psaro.patch.PatchExport;
import psaro.patch.PatchExport.Extra;

/**
 * File → Export patch: what goes in the zip besides the patched files, kept per project
 * ({@link ExportSettings}): the game's title id, the readme, and files added anywhere in the zip
 * (a code patch Luma3DS reads beside the romfs folder, say), each read from where it was chosen
 * every time the patch is exported. What is changed here is kept whether or not the patch is
 * exported; Export checks it first ({@link PatchExport#problems}).
 */
public final class ExportPatchDialog extends JDialog {

	private static final String PREF_FOLDER = "exportFilesFolder";

	private final ExportSettings settings;
	private final Path patchFolder;
	private final Preferences prefs = Preferences.userNodeForPackage(psaro.MainWindow.class);
	private final JTextField titleId = new JTextField(18);
	private final JTextArea readme = new JTextArea(18, 86);
	private final Model model = new Model();
	private final JTable table = new JTable(model);
	private final JButton remove = new JButton("Remove");
	private boolean export;

	/**
	 * Edits {@code settings} for the patch in {@code patchFolder}; {@code id} is the title id
	 * known so far, or null.
	 */
	public ExportPatchDialog(Frame owner, ExportSettings settings, Path patchFolder, String id) {
		super(owner, "Export patch", true);
		this.settings = settings;
		this.patchFolder = patchFolder;
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				close();
			}
		});

		// ── Title id ─────────────────────────────────────────────────────────
		titleId.setText(id == null ? "" : id);
		JPanel idRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		idRow.add(new JLabel("Title id:"));
		idRow.add(titleId);
		idRow.add(new JLabel("<html>sixteen hex digits, as Citra shows in its game list (Properties) or names its mods "
				+ "folder</html>"));

		// ── Readme ───────────────────────────────────────────────────────────
		readme.setText(settings.readme());
		readme.setCaretPosition(0);
		readme.setFont(new Font(Font.MONOSPACED, Font.PLAIN, readme.getFont().getSize()));
		readme.setTabSize(4);
		JButton reset = new JButton("Reset to default");
		reset.setToolTipText("Put back the readme PsaroEditor writes for any game");
		reset.addActionListener(e -> {
			if (readme.getText().replace("\r\n", "\n").equals(PatchExport.defaultReadme())
					|| JOptionPane.showConfirmDialog(this, "Replace this project's readme with the default one?", "Export patch",
							JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
				readme.setText(PatchExport.defaultReadme());
				readme.setCaretPosition(0);
			}
		});
		JPanel readmeTools = new JPanel(new BorderLayout());
		readmeTools.add(new JLabel(PatchExport.TITLE_ID + " becomes the title id. Saved as " + ExportSettings.README_FILE
				+ " in the project folder, which can be edited elsewhere too."), BorderLayout.CENTER);
		readmeTools.add(reset, BorderLayout.EAST);
		JPanel readmePanel = section("README.txt in the zip");
		readmePanel.add(new JScrollPane(readme), BorderLayout.CENTER);
		readmePanel.add(readmeTools, BorderLayout.SOUTH);

		// ── Added files ──────────────────────────────────────────────────────
		model.rows = new ArrayList<>(settings.extras());
		table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setFillsViewportHeight(true);
		table.getColumnModel().getColumn(0).setPreferredWidth(420);
		table.getColumnModel().getColumn(1).setPreferredWidth(320);
		table.getSelectionModel().addListSelectionListener(e -> remove.setEnabled(table.getSelectedRowCount() > 0));
		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setPreferredSize(new Dimension(740, 110));
		JButton add = new JButton("Add…");
		add.setToolTipText("Choose files to add to every export of this project");
		add.addActionListener(e -> addFiles());
		remove.setEnabled(false);
		remove.addActionListener(e -> removeSelected());
		JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		fileButtons.add(add);
		fileButtons.add(remove);
		fileButtons.add(new JLabel("Each is read from where it was chosen at every export. Double-click a path in the zip "
				+ "to change it; " + PatchExport.TITLE_ID + " becomes the title id."));
		JPanel filesPanel = section("Additional files added to the zip");
		filesPanel.add(tableScroll, BorderLayout.CENTER);
		filesPanel.add(fileButtons, BorderLayout.SOUTH);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(new EmptyBorder(12, 16, 4, 16));
		idRow.setAlignmentX(LEFT_ALIGNMENT);
		readmePanel.setAlignmentX(LEFT_ALIGNMENT);
		filesPanel.setAlignmentX(LEFT_ALIGNMENT);
		content.add(idRow);
		content.add(Box.createVerticalStrut(8));
		content.add(readmePanel);
		content.add(Box.createVerticalStrut(8));
		content.add(filesPanel);

		// ── Buttons ──────────────────────────────────────────────────────────
		JButton exportButton = new JButton("Export…");
		exportButton.setToolTipText("Choose where to write the zip");
		exportButton.addActionListener(e -> export());
		JButton close = new JButton("Close");
		close.setToolTipText("Keep what was changed here without exporting");
		close.addActionListener(e -> close());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.setBorder(new EmptyBorder(0, 12, 8, 12));
		buttons.add(exportButton);
		buttons.add(close);
		getRootPane().setDefaultButton(exportButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(content, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke("ESCAPE"),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		pack();
		setLocationRelativeTo(owner);
	}

	/** Whether the dialog closed to export: then {@link #titleId} is a title id and every file is in place. */
	public boolean exportChosen() {
		return export;
	}

	/** The title id typed, upper-cased. */
	public String titleId() {
		return titleId.getText().strip().toUpperCase(Locale.ROOT);
	}

	/** The readme as it stands. */
	public String readme() {
		return settings.readme();
	}

	/** The files added to the zip as they stand. */
	public List<Extra> extras() {
		return settings.extras();
	}

	private static JPanel section(String title) {
		JPanel p = new JPanel(new BorderLayout(0, 4));
		p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT,
				TitledBorder.TOP));
		return p;
	}

	private void addFiles() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Add files to the patch");
		chooser.setMultiSelectionEnabled(true);
		String last = prefs.get(PREF_FOLDER, null);
		if (last != null && Files.isDirectory(Path.of(last))) {
			chooser.setCurrentDirectory(new File(last));
		}
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFiles().length == 0) {
			return;
		}
		prefs.put(PREF_FOLDER, chooser.getSelectedFiles()[0].getParent());
		for (File f : chooser.getSelectedFiles()) {
			Path source = f.toPath().toAbsolutePath();
			if (model.rows.stream().noneMatch(e -> e.source().equals(source))) {
				model.rows.add(new Extra(source, PatchExport.defaultDestination(source)));
			}
		}
		model.fireTableDataChanged();
	}

	private void removeSelected() {
		if (table.isEditing()) {
			table.getCellEditor().cancelCellEditing();
		}
		int[] rows = table.getSelectedRows();
		for (int i = rows.length - 1; i >= 0; i--) {
			model.rows.remove(rows[i]);
		}
		model.fireTableDataChanged();
	}

	/** Keeps what was changed; false (with the reason shown) when it could not be saved. */
	private boolean keep() {
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}
		try {
			settings.setReadme(readme.getText());
			settings.setExtras(model.rows);
			return true;
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the export settings:\n" + e.getMessage(), "Export patch",
					JOptionPane.ERROR_MESSAGE);
			return false;
		}
	}

	private void close() {
		if (keep()) {
			dispose();
		}
	}

	private void export() {
		if (!keep()) {
			return;
		}
		List<String> problems;
		try {
			problems = PatchExport.problems(patchFolder, titleId(), settings.extras());
		} catch (IOException e) {
			problems = List.of("Could not read the patch folder: " + e.getMessage());
		}
		if (!problems.isEmpty()) {
			JOptionPane.showMessageDialog(this, "The patch cannot be exported yet:\n  " + String.join("\n  ", problems),
					"Export patch", JOptionPane.WARNING_MESSAGE);
			return;
		}
		export = true;
		dispose();
	}

	/** Each added file: where it is read from, and its path in the zip, which can be changed. */
	private static final class Model extends AbstractTableModel {
		private static final String[] COLUMNS = {"File", "Path in the zip"};
		List<Extra> rows = new ArrayList<>();

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
		public boolean isCellEditable(int row, int column) {
			return column == 1;
		}

		@Override
		public Object getValueAt(int row, int column) {
			Extra e = rows.get(row);
			if (column == 1) {
				return e.destination();
			}
			return e.source() + (Files.isRegularFile(e.source()) ? "" : "   (not found)");
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			String destination = String.valueOf(value).strip();
			if (column == 1 && !destination.isEmpty()) {
				rows.set(row, new Extra(rows.get(row).source(), destination));
				fireTableCellUpdated(row, column);
			}
		}
	}
}
