package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import psaro.patch.ExportSettings;
import psaro.patch.ExportSettings.Mode;
import psaro.patch.PatchExport;
import psaro.patch.PatchExport.Extra;
import psaro.patch.PatcherPage;
import psaro.patch.TitleBuild;

/**
 * File → Export patch: the game and its DLC, whichever is in view, exported one of two ways,
 * chosen first. LayeredFS gives a zip of the patched files for Luma3DS's game patching or Citra's
 * mods folder, with the project's readme and any files it adds (a code patch Luma3DS reads beside
 * the romfs folder, say), and the DLC's patcher page, a DLC having no other way to be patched. A
 * patched CIA gives the patcher download, for players to build their own patched copies, and the
 * patched copies built here from decrypted copies chosen, either or both. What is chosen is kept
 * per project ({@link ExportSettings}) whether or not it goes on to export; Export checks it first.
 */
public final class ExportWizard extends JDialog {

	private static final String PREF_FOLDER = "exportFilesFolder";
	private static final String CHOOSE = "choose", LAYERED_FS = "layeredFs", CIA = "cia";
	private static final int NOTE_WIDTH = 640;

	/** A patched copy to build: the decrypted copy it is built from, what that is, and where it goes. */
	public record Build(boolean dlc, Path base, TitleBuild.Info info, Path out) {
	}

	private final ExportSettings settings;
	private final Path patchFolder;
	private final boolean hasDlc;
	private final Preferences prefs = Preferences.userNodeForPackage(psaro.MainWindow.class);
	private final CardLayout cards = new CardLayout();
	private final JPanel pages = new JPanel(cards);
	private final JTextField titleId = new JTextField(18);
	private final JRadioButton layeredFs = new JRadioButton("LayeredFS: a zip of the patched files");
	private final JRadioButton cia = new JRadioButton("Patched CIA: the game rebuilt with the patch in it");
	private final JTextArea readme = new JTextArea(16, 86);
	private final JTextArea patcherReadme = new JTextArea(16, 86);
	private final Model model = new Model();
	private final JTable table = new JTable(model);
	private final JButton remove = new JButton("Remove");
	private final JCheckBox patcherDownload = new JCheckBox();
	private final JCheckBox buildCopies = new JCheckBox("Build patched copies now, from your own decrypted copies");
	private final JTextField gameBase = new JTextField(44);
	private final JTextField dlcBase = new JTextField(44);
	private final JButton back = new JButton("< Back");
	private final JButton next = new JButton("Next >");
	private boolean onFirst = true;
	private boolean export;
	private final List<Build> builds = new ArrayList<>();

	/**
	 * Edits {@code settings} for the game whose patch is in {@code patchFolder}; {@code id} is the
	 * title id known so far, or null; {@code hasDlc} whether a DLC is attached; {@code gameBase} and
	 * {@code dlcBase} the decrypted copies built from last, or null.
	 */
	public ExportWizard(Frame owner, ExportSettings settings, Path patchFolder, String id, boolean hasDlc, Path gameBase,
			Path dlcBase) {
		super(owner, "Export patch", true);
		this.settings = settings;
		this.patchFolder = patchFolder;
		this.hasDlc = hasDlc;
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				close();
			}
		});
		if (hasDlc) {
			cia.setText("Patched CIA: the game and its DLC rebuilt with the patch in them");
		}
		titleId.setText(id == null ? "" : id);
		this.gameBase.setText(gameBase == null ? "" : gameBase.toString());
		this.dlcBase.setText(dlcBase == null ? "" : dlcBase.toString());

		pages.add(choosePage(), CHOOSE);
		pages.add(layeredFsPage(), LAYERED_FS);
		pages.add(ciaPage(), CIA);
		pages.setBorder(new EmptyBorder(12, 16, 4, 16));

		back.addActionListener(e -> showFirst());
		next.addActionListener(e -> {
			if (onFirst) {
				showSecond();
			} else {
				export();
			}
		});
		JButton close = new JButton("Close");
		close.setToolTipText("Keep what was chosen here without exporting");
		close.addActionListener(e -> close());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.setBorder(new EmptyBorder(0, 12, 8, 12));
		buttons.add(back);
		buttons.add(next);
		buttons.add(close);
		getRootPane().setDefaultButton(next);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(pages, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke("ESCAPE"),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		showFirst();
		pack();
		setLocationRelativeTo(owner);
	}

	// ── Pages ────────────────────────────────────────────────────────────────

	/** The title id, and how players apply the patch. */
	private JPanel choosePage() {
		JPanel idRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		idRow.add(new JLabel("Title id:"));
		idRow.add(titleId);
		idRow.add(new JLabel("<html>sixteen hex digits, as Citra shows in its game list (Properties) or names its mods "
				+ "folder</html>"));

		ButtonGroup group = new ButtonGroup();
		group.add(layeredFs);
		group.add(cia);
		(settings.mode() == Mode.CIA ? cia : layeredFs).setSelected(true);
		layeredFs.setFont(layeredFs.getFont().deriveFont(Font.BOLD));
		cia.setFont(cia.getFont().deriveFont(Font.BOLD));

		JLabel how = new JLabel("How will players apply the patch?");
		for (JComponent k : List.of(idRow, how, layeredFs, cia)) {
			k.setAlignmentX(LEFT_ALIGNMENT);
		}
		JPanel page = column();
		page.add(idRow);
		page.add(Box.createVerticalStrut(14));
		page.add(how);
		page.add(Box.createVerticalStrut(8));
		page.add(layeredFs);
		page.add(note("The game reads the patched files in place of its own: Luma3DS's game patching on a 3DS, or a mods "
				+ "folder in Citra (or Azahar). Players copy the files over; nothing of the game is rebuilt."
				+ (hasDlc ? " A DLC cannot be patched this way, so the zip also holds the DLC's patcher page, for players to "
						+ "build a patched copy of their DLC." : "")));
		page.add(Box.createVerticalStrut(8));
		page.add(cia);
		page.add(note("Built from decrypted copies, to install in place of the originals: a patcher page players open in a "
				+ "browser to build their own, and patched copies built here from yours. A patched game needs no Luma3DS "
				+ "game patching."));
		return top(page);
	}

	/** LayeredFS: the readme, the DLC's patcher page's readme when it has one, and the files added to the zip. */
	private JPanel layeredFsPage() {
		JPanel gameReadme = readmeEditor(readme, settings.readme(), PatchExport.defaultReadme(),
				"Put back the readme PsaroEditor writes for any game", "Replace this project's readme with the default one?",
				PatchExport.TITLE_ID + " becomes the title id. Saved as " + ExportSettings.README_FILE
						+ " in the project folder, which can be edited elsewhere too.");
		JPanel readmePanel;
		if (hasDlc) {
			JPanel dlcReadme = readmeEditor(patcherReadme, settings.patcherReadme(), PatcherPage.defaultDlcReadme(),
					"Put back the readme PsaroEditor writes for any DLC's patcher page",
					"Replace this project's patcher readme with the default one?",
					PatchExport.TITLE_ID + " becomes the DLC's title id. Saved as " + ExportSettings.PATCHER_README_FILE
							+ " in the project folder, which can be edited elsewhere too.");
			JTabbedPane tabs = new JTabbedPane();
			tabs.addTab("README.txt", gameReadme);
			tabs.addTab(PatcherPage.FOLDER + "/README.txt (the DLC's patcher page)", dlcReadme);
			readmePanel = section("Readmes in the zip");
			readmePanel.add(tabs, BorderLayout.CENTER);
		} else {
			readmePanel = section("README.txt in the zip");
			readmePanel.add(gameReadme, BorderLayout.CENTER);
		}

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

		JPanel page = column();
		page.add(heading("LayeredFS"));
		if (hasDlc) {
			page.add(note("The DLC's patcher page goes in the zip too, in " + PatcherPage.FOLDER + "/, with a readme of its own "
					+ "(the second tab)."));
		}
		page.add(Box.createVerticalStrut(6));
		page.add(readmePanel);
		page.add(Box.createVerticalStrut(8));
		page.add(filesPanel);
		return page;
	}

	/**
	 * {@code area} holding {@code text}, with what {@code hint} says under it and a button putting
	 * back {@code byDefault}, which asks {@code confirm} first unless the text is that already.
	 */
	private JPanel readmeEditor(JTextArea area, String text, String byDefault, String resetTip, String confirm, String hint) {
		area.setText(text);
		area.setCaretPosition(0);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
		area.setTabSize(4);
		JButton reset = new JButton("Reset to default");
		reset.setToolTipText(resetTip);
		reset.addActionListener(e -> {
			if (area.getText().replace("\r\n", "\n").equals(byDefault)
					|| JOptionPane.showConfirmDialog(this, confirm, "Export patch", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
				area.setText(byDefault);
				area.setCaretPosition(0);
			}
		});
		JPanel tools = new JPanel(new BorderLayout());
		tools.add(new JLabel(hint), BorderLayout.CENTER);
		tools.add(reset, BorderLayout.EAST);
		JPanel p = new JPanel(new BorderLayout(0, 4));
		p.add(new JScrollPane(area), BorderLayout.CENTER);
		p.add(tools, BorderLayout.SOUTH);
		return p;
	}

	/** Patched CIA: the patcher download, and patched copies built here. */
	private JPanel ciaPage() {
		patcherDownload.setText(String.format(Locale.ROOT, "Patcher download for players (about %.0f MB)",
				folderSize(patchFolder) / 1048576.0));
		patcherDownload.setSelected(settings.patcherDownload());
		buildCopies.setSelected(settings.buildCopies());
		patcherDownload.setAlignmentX(LEFT_ALIGNMENT);
		buildCopies.setAlignmentX(LEFT_ALIGNMENT);

		JPanel bases = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 0, 2, 6);
		c.anchor = GridBagConstraints.WEST;
		int row = 0;
		row = baseRow(bases, c, row, "Game:", gameBase, false);
		if (hasDlc) {
			baseRow(bases, c, row, "DLC:", dlcBase, true);
		}
		buildCopies.addActionListener(e -> enableBases(bases));
		enableBases(bases);
		// kept to the left, at the size it needs
		JPanel basesRow = new JPanel(new BorderLayout());
		basesRow.add(bases, BorderLayout.WEST);
		basesRow.setBorder(new EmptyBorder(4, 24, 0, 0));
		basesRow.setAlignmentX(LEFT_ALIGNMENT);

		JPanel page = column();
		page.add(heading("Patched CIA"));
		page.add(Box.createVerticalStrut(8));
		page.add(patcherDownload);
		page.add(note("A zip holding " + PatcherPage.PAGE + ": players open it in a browser and build a patched copy of the "
				+ "game (from its CIA or .3ds)" + (hasDlc ? " or its DLC (from its CIA)" : "") + " from their own decrypted "
				+ "copy, then install it in place of the original. Nothing of the game is in it."));
		page.add(Box.createVerticalStrut(8));
		page.add(buildCopies);
		page.add(basesRow);
		page.add(note("GodMode9 can build a decrypted CIA of a title installed on a 3DS, or dump the game's card as a "
				+ "decrypted .3ds. Each patched copy is written beside its decrypted one; leave one empty to skip it."));
		return top(page);
	}

	/** A row choosing a decrypted copy, and the line under it saying where its patched copy goes; the next free row. */
	private int baseRow(JPanel into, GridBagConstraints c, int row, String label, JTextField field, boolean dlc) {
		JLabel out = new JLabel(" ");
		out.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		Runnable show = () -> {
			Path base = path(field);
			out.setText(base == null ? " " : "→ " + TitleBuild.patchedPath(base, guess(base)));
		};
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				show.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				show.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				show.run();
			}
		});
		show.run();
		JButton browse = new JButton("Browse…");
		browse.addActionListener(e -> browse(field, dlc));
		c.gridy = row;
		c.gridx = 0;
		into.add(new JLabel(label), c);
		c.gridx = 1;
		into.add(field, c);
		c.gridx = 2;
		into.add(browse, c);
		c.gridy = row + 1;
		c.gridx = 1;
		c.gridwidth = 2;
		into.add(out, c);
		c.gridwidth = 1;
		return row + 2;
	}

	private void enableBases(JPanel bases) {
		for (java.awt.Component k : bases.getComponents()) {
			k.setEnabled(buildCopies.isSelected());
		}
	}

	private void browse(JTextField field, boolean dlc) {
		Path last = path(field);
		JFileChooser chooser = new JFileChooser(last != null && last.getParent() != null ? last.getParent().toFile() : null);
		chooser.setDialogTitle("Your decrypted copy of the " + (dlc ? "DLC" : "game") + ", to build from");
		chooser.setFileFilter(dlc ? new javax.swing.filechooser.FileNameExtensionFilter("CIA (*.cia)", "cia")
				: new javax.swing.filechooser.FileNameExtensionFilter("CIA or .3ds (*.cia, *.3ds, *.cci)", "cia", "3ds", "cci"));
		if (last != null && Files.isRegularFile(last)) {
			chooser.setSelectedFile(last.toFile());
		}
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			field.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void showFirst() {
		onFirst = true;
		cards.show(pages, CHOOSE);
		back.setEnabled(false);
		next.setText("Next >");
	}

	private void showSecond() {
		if (!PatchExport.isTitleId(titleId())) {
			JOptionPane.showMessageDialog(this, "Give the game's title id first: sixteen hex digits, such as 0004000000140000.",
					"Export patch", JOptionPane.WARNING_MESSAGE);
			titleId.requestFocusInWindow();
			return;
		}
		onFirst = false;
		cards.show(pages, mode() == Mode.CIA ? CIA : LAYERED_FS);
		back.setEnabled(true);
		next.setText("Export…");
	}

	// ── What was chosen ─────────────────────────────────────────────────────

	/** Whether the dialog closed to export, everything checked. */
	public boolean exportChosen() {
		return export;
	}

	/** The title id typed, upper-cased. */
	public String titleId() {
		return titleId.getText().strip().toUpperCase(Locale.ROOT);
	}

	/** How players apply the patch. */
	public Mode mode() {
		return cia.isSelected() ? Mode.CIA : Mode.LAYERED_FS;
	}

	/** LayeredFS: the readme as it stands. */
	public String readme() {
		return settings.readme();
	}

	/** LayeredFS: the DLC's patcher page's readme as it stands. */
	public String patcherReadme() {
		return settings.patcherReadme();
	}

	/** LayeredFS: the files added to the zip as they stand. */
	public List<Extra> extras() {
		return settings.extras();
	}

	/** Patched CIA: whether the patcher download is written. */
	public boolean patcherDownload() {
		return patcherDownload.isSelected();
	}

	/** Patched CIA: the copies to build, checked, the game's first. */
	public List<Build> builds() {
		return List.copyOf(builds);
	}

	/** The decrypted copy of the game (or with {@code dlc}, of the DLC) given, built from or not; null when none is. */
	public Path base(boolean dlc) {
		return path(dlc ? dlcBase : gameBase);
	}

	// ── Closing ──────────────────────────────────────────────────────────────

	/** Keeps what was chosen; false (with the reason shown) when it could not be saved. */
	private boolean keep() {
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}
		try {
			settings.setReadme(readme.getText());
			if (hasDlc) {
				settings.setPatcherReadme(patcherReadme.getText());
			}
			settings.setExtras(model.rows);
			settings.setMode(mode(), patcherDownload.isSelected(), buildCopies.isSelected());
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
		List<String> problems = new ArrayList<>();
		List<String> doubts = new ArrayList<>();
		List<String> replaced = new ArrayList<>();
		builds.clear();
		if (mode() == Mode.LAYERED_FS) {
			try {
				problems.addAll(PatchExport.problems(patchFolder, titleId(), settings.extras()));
			} catch (IOException e) {
				problems.add("Could not read the patch folder: " + e.getMessage());
			}
		} else {
			if (buildCopies.isSelected()) {
				check(false, problems, doubts, replaced);
				if (hasDlc) {
					check(true, problems, doubts, replaced);
				}
				if (problems.isEmpty() && builds.isEmpty()) {
					problems.add("Choose your decrypted copy of the game" + (hasDlc ? " or the DLC" : "")
							+ " to build from, or untick Build patched copies.");
				}
			}
			if (!patcherDownload.isSelected() && !buildCopies.isSelected()) {
				problems.add("Choose the patcher download, patched copies, or both.");
			}
		}
		if (!problems.isEmpty()) {
			JOptionPane.showMessageDialog(this, "The patch cannot be exported yet:\n  " + String.join("\n  ", problems),
					"Export patch", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (!doubts.isEmpty() && JOptionPane.showConfirmDialog(this, String.join("\n", doubts) + "\nBuild from them anyway?",
				"Export patch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
			return;
		}
		if (!replaced.isEmpty() && JOptionPane.showConfirmDialog(this, String.join(", ", replaced) + (replaced.size() == 1
				? " exists" : " exist") + ". Replace?", "Export patch", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
			return;
		}
		export = true;
		dispose();
	}

	/**
	 * Checks the decrypted copy given for the game (or with {@code dlc}, the DLC), adding it to
	 * {@link #builds} when it can be built from; nothing when none is given.
	 */
	private void check(boolean dlc, List<String> problems, List<String> doubts, List<String> replaced) {
		Path base = path(dlc ? dlcBase : gameBase);
		String what = dlc ? "DLC" : "game";
		if (base == null) {
			return;
		}
		if (!Files.isRegularFile(base)) {
			problems.add("The " + what + "'s decrypted copy, " + base + ", is not there.");
			return;
		}
		TitleBuild.Info info;
		try {
			info = TitleBuild.info(base);
		} catch (IOException e) {
			problems.add("Could not read " + base.getFileName() + ": " + e.getMessage());
			return;
		}
		if (dlc && info.format() != TitleBuild.Format.CIA) {
			problems.add(base.getFileName() + " is a game card's image; a DLC comes only as a CIA.");
			return;
		}
		String expected = dlc ? "0004008C" + titleId().substring(8) : titleId();
		if (!expected.equals(info.id())) {
			doubts.add(base.getFileName() + " is title " + info.id() + ", but the " + what + " is " + expected + ".");
		}
		Path out = TitleBuild.patchedPath(base, info.format());
		if (Files.exists(out)) {
			replaced.add(out.getFileName().toString());
		}
		builds.add(new Build(dlc, base, info, out));
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	/** The path typed in {@code field}, or null when none (or not a path) is. */
	private static Path path(JTextField field) {
		String text = field.getText().strip();
		if (text.isEmpty()) {
			return null;
		}
		try {
			return Path.of(text);
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** What {@code base} likely is by its name, before it is read. */
	private static TitleBuild.Format guess(Path base) {
		return base.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(3ds|cci)")
				? TitleBuild.Format.THREE_DS : TitleBuild.Format.CIA;
	}

	/** The bytes of every file under {@code folder}; none when it cannot be read. */
	private static long folderSize(Path folder) {
		if (!Files.isDirectory(folder)) {
			return 0;
		}
		try (var walk = Files.walk(folder)) {
			return walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
		} catch (IOException | java.io.UncheckedIOException e) {
			return 0;
		}
	}

	private static JPanel column() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		return p;
	}

	/** {@code page} at the top of the space, not spread over it. */
	private static JPanel top(JPanel page) {
		JPanel p = new JPanel(new BorderLayout());
		p.add(page, BorderLayout.NORTH);
		return p;
	}

	private static JLabel heading(String text) {
		JLabel l = new JLabel(text);
		l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() + 2f));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	/** Text under a choice, wrapped and set in from it. */
	private static JLabel note(String text) {
		JLabel l = new JLabel("<html><div style='width:" + NOTE_WIDTH + "px'>" + text.replace("&", "&amp;").replace("<", "&lt;")
				+ "</div></html>");
		l.setBorder(new EmptyBorder(2, 24, 0, 0));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private static JPanel section(String title) {
		JPanel p = new JPanel(new BorderLayout(0, 4));
		p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT,
				TitledBorder.TOP));
		p.setAlignmentX(LEFT_ALIGNMENT);
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
