package psaro;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.ButtonGroup;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.dialog.CrashDumpDialog;
import psaro.dialog.FontPatchDialog;
import psaro.dialog.ImagesWindow;
import psaro.dialog.OpenRomfsDialog;
import psaro.patch.ImageEdits;
import psaro.dialog.ExportPatchDialog;
import psaro.dialog.FontsDialog;
import psaro.dialog.PreferencesDialog;
import psaro.format.Csv;
import psaro.format.LumaDump;
import psaro.menu.HelpMenu;
import psaro.patch.FontPatcher;
import psaro.patch.FontPatcher.Plan;
import psaro.patch.FontPatcher.Text;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatcherPage;
import psaro.patch.TitleBuild;
import psaro.patch.ExportSettings;
import psaro.patch.PatchExport;
import psaro.patch.PatchSettings;
import psaro.patch.TextExport;
import psaro.project.BuildBase;
import psaro.project.CodeColors;
import psaro.project.DlcInherit;
import psaro.project.DlcLink;
import psaro.project.DlcPanes;
import psaro.project.PaneLinks;
import psaro.project.StringSearch;
import psaro.project.Translations;
import psaro.project.UnusedTables;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.ui.EditorPanel;
import psaro.ui.LoadingSpinner;
import psaro.ui.SearchBox;

/** The application window: open a romfs, edit its English, save. */
public final class MainWindow {

	private static final String TITLE = "PsaroEditor";
	private static final String PREF_ROMFS = "romfs";
	private static final String PREF_DUMPS = "crashDumps";
	/** Save's and Patch's look while they have something to do: filled with the theme's accent. */
	private static final String HIGHLIGHT = "background: $Component.accentColor;"
			+ " foreground: $List.selectionForeground;"
			+ " borderColor: $Component.accentColor;"
			+ " hoverBackground: darken($Component.accentColor,6%);"
			+ " pressedBackground: darken($Component.accentColor,12%);"
			+ " hoverBorderColor: darken($Component.accentColor,6%);"
			+ " font: bold";

	private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);
	private final JFrame frame = new JFrame(TITLE);
	private final JLabel empty = new JLabel("Open an extracted romfs to begin (File → Open romfs…)", SwingConstants.CENTER);
	private final JLabel status = new JLabel(" ");
	private final JMenuItem save = new JMenuItem("Save");
	/** The same as {@link #save}, always in view above the strings table. */
	private final JButton saveButton = new JButton("Save");
	/** Between File and Help: finds a phrase in every string. */
	private final SearchBox search = new SearchBox();
	/** Opens the font patch; lit while the patched fonts are behind the English. */
	private final JButton patchButton = new JButton("Patch");
	/** Packs the patch folder into a zip to hand out; once a romfs is open. */
	private final JMenuItem exportPatch = new JMenuItem("Export patch…");
	private final JMenuItem fontsItem = new JMenuItem("Fonts…");
	/** Builds a patched copy of the title in view from a decrypted copy of it ({@link TitleBuild}). */
	private final JMenuItem buildItem = new JMenuItem("Build patched copy…");
	/** Gives the DLC the game's English for its strings with the same Japanese; while the DLC is in view. */
	private final JMenuItem fillFromGame = new JMenuItem("Fill DLC from game");
	/** Set while a patch is being packed, one at a time. */
	private boolean exporting;
	private final JButton imagesButton = new JButton("Images…");
	/** The images window while it is open; one at a time, for the romfs that is open. */
	private ImagesWindow imagesWindow;
	/** Patch and Save, which the editor shows above its strings. */
	private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
	/** Checks the fonts against the English a moment after the typing stops. */
	private final Timer planTimer = new Timer(600, e -> startPlan());
	/** What fills the window: {@link #empty} or the editor. */
	private Component body = empty;
	/** The open romfs and its English, or null. */
	private RomfsIndex index;
	private Translations translations;
	/** Tables marked as never shown by the game, left out of the count in the status bar. */
	private UnusedTables unused;
	/** Counts romfs openings, so a background one that another overtook is dropped. */
	private int opening;
	private FontPatcher patcher;
	private EditorPanel editor;
	/** What the font patch would do for the English as last checked, or null before the first check. */
	private Plan plan;
	/** A check is running; {@link #replan} asks for another once it ends. */
	private boolean planning;
	private boolean replan;

	/**
	 * One project on the workbench: the game's, or the DLC's attached to it, each with its own
	 * editor. The fields above ({@link #index}, {@link #translations} and the rest) are the one in
	 * view's ({@link #active}); {@link #plan} is kept here while it is out of view.
	 */
	private static final class Project {
		final Path dir;
		final RomfsIndex index;
		final Translations translations;
		final UnusedTables unused;
		final FontPatcher patcher;
		final CodeColors colors;
		final EditorPanel editor;
		Plan plan;

		Project(Path dir, RomfsIndex index, Translations translations, UnusedTables unused, FontPatcher patcher,
				CodeColors colors, EditorPanel editor) {
			this.dir = dir;
			this.index = index;
			this.translations = translations;
			this.unused = unused;
			this.patcher = patcher;
			this.colors = colors;
			this.editor = editor;
		}
	}

	/** The game open, its DLC when one is attached and read, and which of the two is in view. */
	private Project game;
	private Project dlc;
	private Project active;
	/** Counts DLC readings, so a background one that another overtook (or a game opened since) is dropped. */
	private int dlcOpening;
	/** Set while the DLC is read in the background. */
	private boolean dlcLoading;
	/** Above the search box: which of the game and its DLC is in view. */
	private final JToggleButton gameTab = new JToggleButton("Game");
	private final JToggleButton dlcTab = new JToggleButton("DLC");
	/** The tabs over the search box, which the editor in view shows above its tables. */
	private final JPanel searchHead = new JPanel(new BorderLayout(0, 4));

	public static void main(String[] args) {
		EventQueue.invokeLater(() -> {
			Theme.saved().apply();
			new MainWindow().show();
		});
	}

	private MainWindow() {
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				close();
			}
		});
		frame.setIconImages(AppIcon.windowIcons());
		setDockIcon();
		frame.setJMenuBar(menuBar());

		// a FlatLaf style, not a fixed colour, so it follows a theme change
		empty.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		frame.add(body, BorderLayout.CENTER);
		frame.add(status, BorderLayout.SOUTH);

		frame.setSize(1410, 985);
		frame.setLocationRelativeTo(null);
		refresh();
	}

	private void show() {
		frame.setVisible(true);
		String last = prefs.get(PREF_ROMFS, null);
		if (last != null && Files.isDirectory(Path.of(last))) {
			reopen(Path.of(last));
		}
	}

	private JMenuBar menuBar() {
		int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

		JMenuItem open = new JMenuItem("Open romfs…");
		open.setMnemonic(KeyEvent.VK_O);
		open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut));
		open.setToolTipText("Open a game's extracted romfs, or a DLC's content romfs folders beside the game open");
		open.addActionListener(e -> chooseRomfs(OpenRomfsDialog.Kind.GAME));

		buildItem.setMnemonic(KeyEvent.VK_B);
		buildItem.setEnabled(false);
		buildItem.setToolTipText("Build the game or DLC in view, patched, from your decrypted CIA (or the game's .3ds), to "
				+ "install or play in place of the original");
		buildItem.addActionListener(e -> buildCopy(active));

		fillFromGame.setMnemonic(KeyEvent.VK_I);
		fillFromGame.setEnabled(false);
		fillFromGame.setToolTipText("Give the DLC's strings without English the game's English for the same Japanese");
		fillFromGame.addActionListener(e -> fillDlcFromGame());

		save.setMnemonic(KeyEvent.VK_S);
		save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut));
		save.addActionListener(e -> save());

		exportPatch.setMnemonic(KeyEvent.VK_E);
		exportPatch.setEnabled(false);
		exportPatch.setToolTipText("Pack the patched files into a zip for players: Luma3DS's layout, with this project's "
				+ "readme and any files it adds");
		exportPatch.addActionListener(e -> exportPatch());

		fontsItem.setMnemonic(KeyEvent.VK_N);
		fontsItem.setEnabled(false);
		fontsItem.setToolTipText("The typefaces this project's text can be drawn with: import .ttf or .otf files");
		fontsItem.addActionListener(e -> openFonts());

		JMenuItem crashDump = new JMenuItem("Open Luma crash dump…");
		crashDump.setMnemonic(KeyEvent.VK_L);
		crashDump.setToolTipText("Read a crash_dump_*.dmp written by Luma3DS after a game crash");
		crashDump.addActionListener(e -> openCrashDump());

		JMenuItem preferences = new JMenuItem("Preferences…");
		preferences.setMnemonic(KeyEvent.VK_P);
		preferences.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, shortcut));
		preferences.addActionListener(e -> new PreferencesDialog(frame).setVisible(true));

		JMenuItem exit = new JMenuItem("Exit");
		exit.setMnemonic(KeyEvent.VK_X);
		exit.addActionListener(e -> close());

		JMenu file = new JMenu("File");
		file.setMnemonic(KeyEvent.VK_F);
		file.add(open);
		file.add(save);
		file.add(exportPatch);
		file.add(buildItem);
		file.addSeparator();
		file.add(fontsItem);
		file.add(fillFromGame);
		file.addSeparator();
		file.add(preferences);
		file.addSeparator();
		file.add(exit);

		// not focusable, so clicking it leaves the caret in the string being edited
		saveButton.setFocusable(false);
		saveButton.setToolTipText("Save the English (" + KeyEvent.getModifiersExText(shortcut) + "+S)");
		saveButton.addActionListener(e -> save());
		patchButton.setFocusable(false);
		patchButton.addActionListener(e -> openPatch());
		imagesButton.setFocusable(false);
		imagesButton.setToolTipText("The game's images, for those with text drawn in: export one to edit, import it back");
		imagesButton.addActionListener(e -> openImages());
		actions.setOpaque(false);
		actions.add(imagesButton);
		actions.add(patchButton);
		actions.add(saveButton);
		planTimer.setRepeats(false);

		KeyStroke find = KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcut | InputEvent.SHIFT_DOWN_MASK);
		search.setToolTipText("Find a Japanese or English phrase in every string ("
				+ KeyEvent.getModifiersExText(find.getModifiers()) + "+F)");
		frame.getRootPane().registerKeyboardAction(e -> search.focus(), find, JComponent.WHEN_IN_FOCUSED_WINDOW);

		// tabs as FlatLaf draws them, underlined when chosen, side by side across the column of tables
		ButtonGroup tabs = new ButtonGroup();
		JPanel tabRow = new JPanel(new java.awt.GridLayout(1, 2));
		for (JToggleButton tab : new JToggleButton[] {gameTab, dlcTab}) {
			tab.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TAB);
			tab.setFocusable(false);
			tabs.add(tab);
			tabRow.add(tab);
		}
		gameTab.setToolTipText("The game's strings");
		gameTab.addActionListener(e -> showProject(game));
		dlcTab.addActionListener(e -> showDlcTab());
		searchHead.setOpaque(false);
		searchHead.add(tabRow, BorderLayout.NORTH);
		searchHead.add(search, BorderLayout.CENTER);

		JMenu tools = new JMenu("Tools");
		tools.setMnemonic(KeyEvent.VK_T);
		tools.add(crashDump);

		JMenuBar bar = new JMenuBar();
		bar.add(file);
		bar.add(tools);
		bar.add(Box.createHorizontalStrut(6));
		bar.add(new HelpMenu(frame));
		return bar;
	}

	private void openCrashDump() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Open Luma crash dump");
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Luma3DS crash dumps (*.dmp)", "dmp"));
		String last = prefs.get(PREF_DUMPS, null);
		if (last != null && Files.isDirectory(Path.of(last))) {
			chooser.setCurrentDirectory(Path.of(last).toFile());
		}
		if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path file = chooser.getSelectedFile().toPath();
		try {
			LumaDump dump = LumaDump.read(Files.readAllBytes(file));
			if (file.getParent() != null) {
				prefs.put(PREF_DUMPS, file.getParent().toString());
			}
			new CrashDumpDialog(frame, file.getFileName().toString(), dump).setVisible(true);
		} catch (IOException | IllegalArgumentException ex) {
			JOptionPane.showMessageDialog(frame, "Could not read " + file.getFileName() + ":\n" + ex.getMessage(),
					"Open Luma crash dump", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * File → Open romfs: asks whether it is a game's or a DLC's ({@link OpenRomfsDialog}), starting
	 * on {@code start}. A game is then chosen as before. A DLC is attached to the game chosen with
	 * it, which is opened first when it is not the one open.
	 */
	private void chooseRomfs(OpenRomfsDialog.Kind start) {
		DlcLink.Dlc current = null;
		if (game != null) {
			try {
				current = DlcLink.read(game.dir);
			} catch (IOException unreadable) {
				// chosen afresh
			}
		}
		OpenRomfsDialog dialog = new OpenRomfsDialog(frame, start, game == null ? null : game.dir, current);
		dialog.setVisible(true);
		if (dialog.chosen() == OpenRomfsDialog.Kind.GAME) {
			chooseGame();
		} else if (dialog.chosen() == OpenRomfsDialog.Kind.DLC) {
			Path gameDir = dialog.gameRoot();
			if (game == null || !same(game.dir, gameDir)) {
				if (!confirmUnsaved(projects())) {
					return;
				}
				open(gameDir, true);
				if (game == null || !same(game.dir, gameDir)) {
					return;
				}
			} else if (dlc != null && !confirmUnsaved(List.of(dlc))) {
				return;
			}
			attachDlc(new DlcLink.Dlc(dialog.dlcFolder(), dialog.contents()));
		}
	}

	private static boolean same(Path a, Path b) {
		return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
	}

	/** Chooses a game's romfs and opens it, in place of the game and DLC open. */
	private void chooseGame() {
		if (!confirmUnsaved(projects())) {
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Open romfs");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		String last = prefs.get(PREF_ROMFS, null);
		if (last != null) {
			chooser.setCurrentDirectory(Path.of(last).toFile());
		}
		if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			open(chooser.getSelectedFile().toPath(), true);
		}
	}

	/**
	 * Opens {@code dir} if it looks like an extracted romfs: a {@code text} folder of
	 * {@code *.tdt} string tables. {@code explain} reports a rejection; a romfs
	 * reopened at launch fails quietly instead.
	 */
	private void open(Path dir, boolean explain) {
		opening++;
		try {
			install(read(dir));
		} catch (OpenFailure f) {
			if (explain || f.always) {
				error(f.getMessage());
			}
			if (index == null) {
				setBody(empty);
			}
		}
	}

	/** The projects open: the game's, then its DLC's when there is one. */
	private List<Project> projects() {
		List<Project> out = new ArrayList<>();
		if (game != null) {
			out.add(game);
		}
		if (dlc != null) {
			out.add(dlc);
		}
		return out;
	}

	/**
	 * Opens {@code dir}, the romfs open last time, in the background, a spinner showing in the
	 * window meanwhile. It fails quietly, back to the empty window, unless its translations
	 * cannot be read.
	 */
	private void reopen(Path dir) {
		int ticket = ++opening;
		setBody(loading(dir));
		new SwingWorker<Opened, Void>() {
			@Override
			protected Opened doInBackground() throws OpenFailure {
				return read(dir);
			}

			@Override
			protected void done() {
				if (ticket != opening) {
					// another romfs was opened meanwhile
					return;
				}
				try {
					install(get());
				} catch (ExecutionException e) {
					setBody(empty);
					if (e.getCause() instanceof OpenFailure f && f.always) {
						error(f.getMessage());
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}.execute();
	}

	/** The window's content while a romfs opens: a spinner, and which one. */
	private static JPanel loading(Path dir) {
		JLabel what = new JLabel("Opening " + dir.getFileName() + "…", SwingConstants.CENTER);
		JLabel where = new JLabel(dir.toString(), SwingConstants.CENTER);
		where.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		where.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		for (JComponent c : new JComponent[] {new LoadingSpinner(), (JComponent) Box.createRigidArea(new Dimension(0, 12)), what,
				(JComponent) Box.createRigidArea(new Dimension(0, 4)), where}) {
			c.setAlignmentX(Component.CENTER_ALIGNMENT);
			stack.add(c);
		}
		JPanel centred = new JPanel(new GridBagLayout());
		centred.add(stack);
		return centred;
	}

	/** Everything read from a romfs and its .psaro folder, ready for the editor. */
	private record Opened(Path dir, RomfsIndex index, Translations translations, CodeColors colors,
			UnusedTables unused, PatchSettings patchSettings, LayoutOverrides layoutOverrides, ImageEdits imageEdits,
			PaneLinks links) {
	}

	/** Why a romfs could not be opened; {@code always} when it must be reported even when reopening quietly. */
	private static final class OpenFailure extends Exception {
		final boolean always;

		OpenFailure(String message, boolean always) {
			super(message);
			this.always = always;
		}
	}

	/**
	 * Reads {@code dir} if it looks like an extracted romfs ({@link RomfsIndex#looksLikeRomfs})
	 * and holds text to translate. Touches nothing in the window, so it can run in the background.
	 */
	private static Opened read(Path dir) throws OpenFailure {
		// opened as a game, its project is beside it again, whatever it was attached to before
		Translations.detachDlc(dir);
		RomfsIndex scanned;
		String lookedFor = "PsaroEditor looks inside an extracted romfs for string tables (text/*.tdt), message files "
				+ "(message/*_jp.mdt), data tables (table/*.csv), record files and scripts (*.dat), layout archives "
				+ "(*.arc, *.arc.lz) and CRI archives (*.cpk).";
		try {
			if (!RomfsIndex.looksLikeRomfs(dir)) {
				throw new OpenFailure("No string tables were found in " + dir + ".\n" + lookedFor, false);
			}
			scanned = RomfsIndex.scan(dir);
		} catch (IOException | UncheckedIOException | IllegalArgumentException e) {
			throw new OpenFailure("Could not read " + dir + ":\n" + e.getMessage(), false);
		}
		if (scanned.tables().isEmpty()) {
			throw new OpenFailure("No Japanese text was found in " + dir + ".\n" + lookedFor, false);
		}
		try {
			return new Opened(dir, scanned, Translations.open(scanned), CodeColors.open(dir), UnusedTables.open(dir),
					PatchSettings.open(dir), LayoutOverrides.open(dir), ImageEdits.open(dir), PaneLinks.open(scanned));
		} catch (IOException e) {
			// always reported: opening anyway could later save over the unreadable file
			throw new OpenFailure("Could not read the translations for " + dir + ":\n" + e.getMessage(), true);
		}
	}

	/**
	 * Reads the content folders {@code dlc} chose of its DLC, its project kept in the project of the
	 * game whose romfs is {@code gameDir} ({@link Translations#attachDlc}), inheriting
	 * {@code gameSettings} and the game's code colours. Touches nothing in the window.
	 */
	private static Opened readDlc(DlcLink.Dlc dlc, Path gameDir, PatchSettings gameSettings, CodeColors colors)
			throws OpenFailure {
		Path dir = dlc.folder();
		List<String> contents = dlc.contents().stream().filter(c -> Files.isDirectory(dir.resolve(c))).toList();
		if (contents.isEmpty()) {
			throw new OpenFailure("None of the DLC's content folders were found in " + dir
					+ ".\nChoose them again: File → Open romfs…, DLC.", false);
		}
		Translations.attachDlc(dir, gameDir);
		RomfsIndex scanned;
		try {
			scanned = RomfsIndex.scanDlc(dir, contents);
		} catch (IOException | UncheckedIOException | IllegalArgumentException e) {
			throw new OpenFailure("Could not read the DLC in " + dir + ":\n" + e.getMessage(), false);
		}
		if (scanned.tables().isEmpty()) {
			throw new OpenFailure("No Japanese text was found in the DLC's " + contents.size() + " content folders in "
					+ dir + ".", false);
		}
		try {
			return new Opened(dir, scanned, Translations.open(scanned), colors, UnusedTables.open(dir),
					PatchSettings.openDlc(dir, gameSettings), LayoutOverrides.open(dir), ImageEdits.open(dir),
					PaneLinks.open(scanned));
		} catch (IOException e) {
			throw new OpenFailure("Could not read the DLC's translations in " + Translations.folderFor(dir) + ":\n"
					+ e.getMessage(), true);
		}
	}

	/** Shows the game {@link #read} found in the editor, in place of the game and DLC open, then reads its DLC if it has one. */
	private void install(Opened o) {
		if (dlc != null) {
			Translations.detachDlc(dlc.dir);
		}
		dlc = null;
		// a DLC still being read is the last game's
		dlcOpening++;
		dlcLoading = false;
		prefs.put(PREF_ROMFS, o.dir().toString());
		game = project(o);
		active = null;
		showProject(game);
		// English saved before the game text was written alongside it
		exportText(game, false);
		javax.swing.SwingUtilities.invokeLater(this::warnMissingTypefaces);
		try {
			DlcLink.Dlc attached = DlcLink.read(game.dir);
			if (attached != null) {
				loadDlc(attached, false, false);
			}
		} catch (IOException e) {
			error("Could not read which DLC is attached to this game:\n" + e.getMessage());
		}
		refresh();
	}

	/** A project of what {@code o} read, with its own editor. */
	private Project project(Opened o) {
		FontPatcher p = new FontPatcher(o.index(), o.patchSettings(), o.layoutOverrides(), o.imageEdits());
		EditorPanel e = new EditorPanel(p, o.translations(), o.colors(), o.unused(), o.links(), actions, searchHead,
				this::refresh);
		return new Project(o.dir(), o.index(), o.translations(), o.unused(), p, o.colors(), e);
	}

	/**
	 * Puts {@code p} in view: its editor, with the tabs, search box and buttons, and the fields the
	 * window works on set to its parts. Nothing happens for a project already in view, or none.
	 */
	private void showProject(Project p) {
		if (p == null || p == active) {
			selectTab();
			return;
		}
		commitTyping();
		if (active != null) {
			active.plan = plan;
		}
		active = p;
		index = p.index;
		translations = p.translations;
		unused = p.unused;
		patcher = p.patcher;
		editor = p.editor;
		plan = p.plan;
		if (imagesWindow != null) {
			// it lists the romfs that was in view
			imagesWindow.dispose();
			imagesWindow = null;
		}
		editor.reclaim();
		setBody(editor);
		search.setSource(new StringSearch(index, translations), translations, editor::showString);
		selectTab();
		showPlan();
		refresh();
	}

	/** The DLC tab: the DLC when it is read; otherwise, choosing one to attach. */
	private void showDlcTab() {
		if (dlc != null) {
			showProject(dlc);
			return;
		}
		selectTab();
		if (!dlcLoading && game != null) {
			chooseRomfs(OpenRomfsDialog.Kind.DLC);
		}
	}

	/** Marks the tab of the project in view, and says on the DLC tab what is attached. */
	private void selectTab() {
		(active == dlc && dlc != null ? dlcTab : gameTab).setSelected(true);
		dlcTab.setText(dlcLoading ? "DLC…" : "DLC");
		if (dlc != null) {
			dlcTab.setToolTipText("<html>The DLC's strings: " + dlc.index.folders().size() + " content folders in "
					+ dlc.dir + "<br>Its project is kept in " + Translations.folderFor(dlc.dir) + "</html>");
		} else {
			dlcTab.setToolTipText(dlcLoading ? "Reading the DLC…" : "Attach a DLC to this game: its content romfs folders, "
					+ "worked on beside the game");
		}
	}

	/**
	 * Attaches {@code chosen} to the game open, in place of any DLC it had, and reads it into view.
	 * A DLC attached for the first time (or from another folder) is given the game's English for its
	 * strings with the same Japanese ({@link DlcInherit}).
	 */
	private void attachDlc(DlcLink.Dlc chosen) {
		boolean fresh;
		try {
			DlcLink.Dlc before = DlcLink.read(game.dir);
			fresh = before == null || !same(before.folder(), chosen.folder());
			DlcLink.write(game.dir, chosen);
		} catch (IOException e) {
			error("Could not attach the DLC to " + game.dir + ":\n" + e.getMessage());
			return;
		}
		if (dlc != null) {
			Translations.detachDlc(dlc.dir);
			if (active == dlc) {
				showProject(game);
			}
			dlc = null;
		}
		loadDlc(chosen, true, fresh);
	}

	/**
	 * Reads {@code attached} in the background as the game's DLC, the DLC tab saying so meanwhile,
	 * then puts it in view when {@code show}, giving it the game's English first when {@code inherit}.
	 */
	private void loadDlc(DlcLink.Dlc attached, boolean show, boolean inherit) {
		int ticket = ++dlcOpening;
		Project of = game;
		PatchSettings settings = game.patcher.settings();
		dlcLoading = true;
		selectTab();
		new SwingWorker<Opened, Void>() {
			@Override
			protected Opened doInBackground() throws OpenFailure {
				return readDlc(attached, of.dir, settings, of.colors);
			}

			@Override
			protected void done() {
				if (ticket != dlcOpening || of != game) {
					return;
				}
				dlcLoading = false;
				try {
					installDlc(get(), show, inherit);
				} catch (ExecutionException e) {
					selectTab();
					if (e.getCause() instanceof OpenFailure f && (show || f.always)) {
						error(f.getMessage());
					} else if (e.getCause() instanceof OpenFailure f) {
						dlcTab.setToolTipText("<html>The DLC could not be opened:<br>" + f.getMessage().replace("\n", "<br>")
								+ "</html>");
					} else {
						error("Could not open the DLC:\n" + e.getCause());
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}.execute();
	}

	/** Makes the DLC {@link #readDlc} found the game's, in view when {@code show}. */
	private void installDlc(Opened o, boolean show, boolean inherit) {
		DlcInherit.Result inherited = inherit ? DlcInherit.fill(game.index, game.translations, o.index(), o.translations())
				: null;
		dlc = project(o);
		exportText(dlc, false);
		if (show) {
			showProject(dlc);
		} else {
			// building its editor took the search box and buttons from the one in view
			active.editor.reclaim();
			selectTab();
		}
		refresh();
		if (inherited != null && inherited.filled() > 0) {
			JOptionPane.showMessageDialog(frame, inherited.summary() + "\nSave to keep them.", "DLC",
					JOptionPane.INFORMATION_MESSAGE);
		}
	}

	/** File → Fill DLC from game: the game's English for the DLC's strings without, where the Japanese is the same. */
	private void fillDlcFromGame() {
		if (dlc == null || game == null) {
			return;
		}
		commitTyping();
		DlcInherit.Result r = DlcInherit.fill(game.index, game.translations, dlc.index, dlc.translations);
		dlc.editor.translationsChanged();
		refresh();
		JOptionPane.showMessageDialog(frame, r.summary() + (r.filled() > 0 ? "\nSave to keep them." : ""), "Fill DLC from game",
				JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Writes {@code p}'s string tables with the English into its patch folder, and with
	 * {@code toMods} copies the changed ones to the mods folder when the font patch settings say
	 * so. The English itself is already saved, so a failure here loses nothing.
	 */
	private void exportText(Project p, boolean toMods) {
		try {
			List<Path> wrote = TextExport.export(p.index, p.translations);
			if (toMods) {
				p.patcher.copyToMods(wrote, step -> { });
			}
		} catch (IOException e) {
			error("The English is saved, but the game text could not be written to " + p.patcher.output() + ":\n"
					+ e.getMessage());
		}
	}

	/** Every string with English, as the game would show it: a data table's with its commas full-width. */
	private List<Text> texts() {
		Project p = fontProject();
		List<Text> out = texts(p);
		if (p == game && dlc != null) {
			// the DLC's strings show in the game's panes, so the game's fonts need their letters too
			DlcPanes panes = new DlcPanes(game.index);
			for (Text t : texts(dlc)) {
				DlcPanes.StandIn s = panes.standIn(t.table(), t.key());
				if (s != null) {
					out.add(new Text(s.table(), s.key(), t.text()));
				}
			}
		}
		return out;
	}

	private static List<Text> texts(Project p) {
		List<Text> out = new ArrayList<>();
		for (StringTable t : p.index.tables()) {
			for (String key : t.strings().keySet()) {
				String text = p.translations.get(t, key);
				if (text != null) {
					out.add(new Text(t, key, t.kind() == RomfsIndex.Kind.DATA ? Csv.shown(text) : text));
				}
			}
		}
		return out;
	}

	/**
	 * The project whose fonts the font patch changes: the one in view, but the game's for a DLC
	 * with no layouts of its own, as its text is drawn in the game's.
	 */
	private Project fontProject() {
		return active == dlc && dlc != null && dlc.index.layoutCount() == 0 ? game : active;
	}

	/** Checks the fonts and layouts against what was written, in the background, then lights Patch if needed. */
	private void startPlan() {
		if (patcher == null) {
			return;
		}
		if (planning) {
			replan = true;
			return;
		}
		planning = true;
		FontPatcher checking = fontProject().patcher;
		List<Text> snapshot = texts();
		new SwingWorker<Plan, Void>() {
			@Override
			protected Plan doInBackground() {
				return checking.plan(snapshot);
			}

			@Override
			protected void done() {
				planning = false;
				if (active != null && checking == fontProject().patcher) {
					try {
						plan = get();
					} catch (Exception e) {
						plan = null;
					}
					showPlan();
				}
				if (replan) {
					replan = false;
					startPlan();
				}
			}
		}.execute();
	}

	private void showPlan() {
		boolean work = plan != null && plan.hasWork();
		patchButton.putClientProperty(FlatClientProperties.STYLE, work ? HIGHLIGHT : null);
		patchButton.setToolTipText(plan == null ? "Add the characters the English uses to the game's fonts"
				: FontPatchDialog.summary(plan));
	}

	/** File → Fonts: after an import or a removal, the fonts are measured and the patch checked again. */
	private void openFonts() {
		commitTyping();
		new FontsDialog(frame, patcher.settings().typefaces(), patcher.missingTypefaces(), () -> {
			// a DLC draws from its game's typefaces, so both are measured again
			for (Project p : projects()) {
				p.patcher.typefacesChanged();
				p.editor.fontsChanged();
			}
			startPlan();
		}).setVisible(true);
	}

	/** Says which typefaces the project names that are not imported here, if any. */
	private void warnMissingTypefaces() {
		java.util.SortedSet<String> missing = patcher.missingTypefaces();
		if (!missing.isEmpty()) {
			JOptionPane.showMessageDialog(frame, "This project's font settings name typefaces that are not imported here:\n  "
					+ String.join("\n  ", missing) + "\n\nTheir panes draw with the game font until they are imported "
					+ "(File → Fonts). Their settings are kept.", "Fonts", JOptionPane.WARNING_MESSAGE);
		}
	}

	private void openPatch() {
		commitTyping();
		new FontPatchDialog(frame, fontProject().patcher, this::texts, () -> {
			// the game's fonts are the DLC's too
			projects().forEach(p -> p.editor.fontsChanged());
			startPlan();
		}).setVisible(true);
		startPlan();
	}

	/**
	 * Commits a number still being typed (a pane's box or font size in the preview), as the
	 * buttons that take no focus would otherwise act without it; it is saved as it commits.
	 * Opening a dialog only moves the focus for a while, which commits nothing.
	 */
	private static void commitTyping() {
		if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JFormattedTextField f
				&& f.isEditValid()) {
			try {
				f.commitEdit();
			} catch (ParseException invalid) {
				// left as it was, as leaving the field would
			}
		}
	}

	/** Opens the images window, or brings it forward when it is open already. */
	private void openImages() {
		if (patcher == null) {
			return;
		}
		commitTyping();
		if (imagesWindow == null || !imagesWindow.isDisplayable()) {
			imagesWindow = new ImagesWindow(frame, index, patcher.images(), this::startPlan);
		}
		imagesWindow.setVisible(true);
		imagesWindow.setExtendedState(imagesWindow.getExtendedState() & ~JFrame.ICONIFIED);
		imagesWindow.toFront();
	}

	/** Saves the English of the game and of its DLC, whichever is in view; false when one could not be saved. */
	private boolean save() {
		commitTyping();
		for (Project p : projects()) {
			if (!p.translations.isDirty()) {
				continue;
			}
			try {
				p.translations.save();
			} catch (IOException e) {
				error("Could not save the translations to " + p.translations.folder() + ":\n" + e.getMessage());
				refresh();
				return false;
			}
			exportText(p, true);
		}
		refresh();
		return true;
	}

	/**
	 * Export patch with the DLC in view. Its English is written into its contents' own files,
	 * compressed as the DLC keeps them, in its patch folder; neither Luma3DS nor Citra lays those
	 * over a DLC as it does a game's romfs, so it is patched by building its CIA again with them in
	 * it ({@link #buildCopy}). Players get the same from the game's zip ({@link PatcherPage}).
	 */
	private void exportDlc() {
		if (!save()) {
			return;
		}
		exportText(dlc, false);
		Path folder = dlc.patcher.output();
		Object[] options = {"Build patched copy…", "Open folder", "Cancel"};
		int choice = JOptionPane.showOptionDialog(frame, "The DLC's English is written into its contents' own files, "
				+ "compressed as the DLC keeps them, in\n  " + folder
				+ "\n\nLuma3DS patches only a game's own romfs, and Citra lays one mods folder over every content of a DLC "
				+ "alike,\nso the DLC is patched by installing it again with these files in it, built from a decrypted CIA of "
				+ "it\n(GodMode9 can build one from the DLC installed on a 3DS). Players get it from the game's patch: Export "
				+ "patch\non the Game tab puts the patcher page in its zip.",
				"Export patch", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
		if (choice == 1) {
			try {
				Files.createDirectories(folder);
				java.awt.Desktop.getDesktop().open(folder.toFile());
			} catch (IOException | UnsupportedOperationException e) {
				error("Could not open " + folder + ":\n" + e.getMessage());
			}
		} else if (choice == 0) {
			buildCopy(dlc);
		}
	}

	/** The version of the title in {@code base} where it says (a CIA's TMD), else null; null for no base too. */
	private static Integer versionOf(Path base) {
		if (base == null || !Files.isRegularFile(base)) {
			return null;
		}
		try {
			return TitleBuild.info(base).version();
		} catch (IOException unreadable) {
			return null;
		}
	}

	/** The game's title id as the export was given it, upper-cased, or null when none was. */
	private String gameTitleId() {
		String id = game == null ? null : game.patcher.settings().titleId();
		return PatchExport.isTitleId(id) ? id.toUpperCase(java.util.Locale.ROOT) : null;
	}

	/** The DLC's title id: the game's with DLC's category ({@code 0004008C}), or null when the game's is not known. */
	private String dlcTitleId() {
		String id = gameTitleId();
		return id == null ? null : "0004008C" + id.substring(8);
	}

	/**
	 * File → Build patched copy: {@code p}'s title (the game, or its DLC) built from a decrypted
	 * copy of it with the patch in it ({@link TitleBuild}), to install or play in place of the
	 * original: a CIA from a CIA, a .3ds from the game's .3ds.
	 */
	private void buildCopy(Project p) {
		if (p == null || exporting || !save()) {
			return;
		}
		exportText(p, false);
		boolean isDlc = p == dlc;
		String what = isDlc ? "DLC" : "game";
		if (!isDlc && plan != null && plan.hasWork()) {
			Object[] options = {"Patch first", "Build anyway", "Cancel"};
			int choice = JOptionPane.showOptionDialog(frame, "The fonts or layouts have changed since the last patch, so the "
					+ "files to build with are behind.\n" + FontPatchDialog.summary(plan), "Build patched copy",
					JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
			if (choice == 0) {
				openPatch();
				return;
			}
			if (choice != 1) {
				return;
			}
		}
		Path base = chooseBase(p, what);
		if (base == null) {
			return;
		}
		TitleBuild.Info info;
		try {
			info = TitleBuild.info(base);
		} catch (IOException e) {
			error("Could not read " + base.getFileName() + ":\n" + e.getMessage());
			return;
		}
		if (isDlc && info.format() != TitleBuild.Format.CIA) {
			error(base.getFileName() + " is a game card's image; a DLC comes only as a CIA.");
			return;
		}
		String expected = isDlc ? dlcTitleId() : gameTitleId();
		if (expected != null && !expected.equals(info.id()) && JOptionPane.showConfirmDialog(frame, base.getFileName()
				+ " is title " + info.id() + ", but the " + what + " is " + expected + ".\nBuild from it anyway?",
				"Build patched copy", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
			return;
		}
		String ext = info.format().extension;
		String stem = base.getFileName().toString().replaceFirst("(?i)\\.(cia|3ds|cci)$", "").replaceFirst("(?i)\\s*decrypted$", "");
		JFileChooser chooser = new JFileChooser(base.getParent() == null ? null : base.getParent().toFile());
		chooser.setDialogTitle("Build patched copy of the " + what);
		chooser.setSelectedFile(new java.io.File(stem + " patched" + ext));
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("*" + ext, ext.substring(1)));
		if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path out = chooser.getSelectedFile().toPath();
		if (!out.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
			out = out.resolveSibling(out.getFileName() + ext);
		}
		if (same(out, base)) {
			error("Build the patched copy beside the decrypted one, not over it: it is what the next build starts from.");
			return;
		}
		if (Files.exists(out) && JOptionPane.showConfirmDialog(frame, out.getFileName() + " exists. Replace it?",
				"Build patched copy", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
			return;
		}
		Path target = out;
		Path folder = p.patcher.output();
		exporting = true;
		refresh();
		status.setText("Building " + target.getFileName() + "…");
		new SwingWorker<TitleBuild.Result, Integer>() {
			@Override
			protected TitleBuild.Result doInBackground() throws IOException {
				var files = isDlc ? TitleBuild.dlcFiles(folder) : TitleBuild.gameFiles(folder);
				int[] shown = {-1};
				return TitleBuild.build(base, files, target, share -> {
					int percent = (int) (share * 100);
					if (percent != shown[0]) {
						shown[0] = percent;
						publish(percent);
					}
				});
			}

			@Override
			protected void process(List<Integer> percents) {
				status.setText("Building " + target.getFileName() + "… " + percents.get(percents.size() - 1) + "%");
			}

			@Override
			protected void done() {
				exporting = false;
				refresh();
				try {
					TitleBuild.Result r = get();
					String use = r.format() == TitleBuild.Format.THREE_DS
							? "Play it in Citra, or have GodMode9 build a CIA from it to install on a 3DS."
							: "Install it in place of the " + what + ": with FBI on a 3DS with Luma3DS, or in Citra with File → Install CIA.";
					JOptionPane.showMessageDialog(frame, String.format(java.util.Locale.ROOT,
							"Built %s (%.1f MB): %,d %s rebuilt with %,d patched %s.%n%n%s%n%s",
							target, r.bytes() / 1048576.0, r.contents(), r.contents() == 1 ? "content" : "contents", r.files(),
							r.files() == 1 ? "file" : "files", use,
							isDlc ? "Keep the decrypted CIA to put the DLC back as it was."
									: "It needs no Luma3DS game patching: remove the game's luma/titles romfs folder when you use it."),
							"Build patched copy", JOptionPane.INFORMATION_MESSAGE);
				} catch (ExecutionException e) {
					error("Could not build the patched copy:\n" + e.getCause().getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}.execute();
	}

	/**
	 * The decrypted copy of {@code p}'s title to build from: the one chosen last time, or one chosen
	 * now and remembered in its project ({@link BuildBase}). Null when none is chosen.
	 */
	private Path chooseBase(Project p, String what) {
		Path last = BuildBase.of(p.dir);
		JFileChooser chooser = new JFileChooser(last != null && last.getParent() != null ? last.getParent().toFile() : null);
		chooser.setDialogTitle("Your decrypted copy of the " + what + ", to build from");
		chooser.setFileFilter(p == dlc ? new javax.swing.filechooser.FileNameExtensionFilter("CIA (*.cia)", "cia")
				: new javax.swing.filechooser.FileNameExtensionFilter("CIA or .3ds (*.cia, *.3ds, *.cci)", "cia", "3ds", "cci"));
		if (last != null && Files.isRegularFile(last)) {
			chooser.setSelectedFile(last.toFile());
		}
		if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
			return null;
		}
		Path base = chooser.getSelectedFile().toPath();
		try {
			BuildBase.set(p.dir, base);
		} catch (IOException e) {
			// chosen again next time; the build goes ahead
		}
		return base;
	}

	/**
	 * Packs the patch folder into a zip for players ({@link PatchExport}): the English saved and
	 * its tables written first, a patch that is behind offered first, then the project's title id,
	 * readme and added files shown to change ({@link ExportPatchDialog}), which are kept whether or
	 * not it goes on to export.
	 */
	private void exportPatch() {
		if (patcher == null) {
			return;
		}
		if (active == dlc) {
			exportDlc();
			return;
		}
		if (!save()) {
			return;
		}
		exportText(active, false);
		if (plan != null && plan.hasWork()) {
			Object[] options = {"Patch first", "Export anyway", "Cancel"};
			int choice = JOptionPane.showOptionDialog(frame, "The fonts or layouts have changed since the last patch, "
					+ "so the files to export are behind.\n" + FontPatchDialog.summary(plan), "Export patch",
					JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
			if (choice == 0) {
				openPatch();
				return;
			}
			if (choice != 1) {
				return;
			}
		}
		PatchSettings settings = patcher.settings();
		String known = settings.titleId();
		if (known == null && settings.modsFolder() != null) {
			known = PatchExport.titleIdFrom(settings.modsFolder());
		}
		ExportSettings exportSettings;
		try {
			exportSettings = ExportSettings.open(index.root());
		} catch (IOException e) {
			error("Could not read the export settings:\n" + e.getMessage());
			return;
		}
		ExportPatchDialog dialog = new ExportPatchDialog(frame, exportSettings, patcher.output(), known);
		dialog.setVisible(true);
		String id = dialog.titleId();
		if (PatchExport.isTitleId(id) && !id.equals(settings.titleId())) {
			try {
				settings.setTitleId(id);
			} catch (IOException e) {
				// remembered next time only; the export goes ahead
			}
		}
		if (!dialog.exportChosen()) {
			return;
		}
		String readme = dialog.readme();
		List<PatchExport.Extra> extras = new ArrayList<>(dialog.extras());
		// the patcher page: the DLC's files whenever it has English, the game's when the export says so
		boolean withGame = exportSettings.gamePatcher();
		Path dlcFolder = null;
		if (dlc != null) {
			exportText(dlc, false);
			dlcFolder = dlc.patcher.output();
		}
		Path dlcBase = dlc == null ? null : BuildBase.of(dlc.dir);
		Path gameBase = BuildBase.of(game.dir);
		Path pageFolder = Translations.folderFor(game.dir).resolve("export");
		String pageName = index.root().getFileName() + " patcher";
		JFileChooser chooser = new JFileChooser(Translations.folderFor(index.root()).getParent().toFile());
		chooser.setDialogTitle("Export patch");
		chooser.setSelectedFile(new java.io.File(index.root().getFileName() + " patch.zip"));
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Zip archive (*.zip)", "zip"));
		if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path zip = chooser.getSelectedFile().toPath();
		if (!zip.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
			zip = zip.resolveSibling(zip.getFileName() + ".zip");
		}
		if (Files.exists(zip) && JOptionPane.showConfirmDialog(frame, zip.getFileName() + " exists. Replace it?",
				"Export patch", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
			return;
		}
		Path target = zip;
		// the patcher download beside it: "<name> patch.zip" gives "<name> patcher.zip"
		String stem = zip.getFileName().toString().replaceFirst("(?i)\\.zip$", "").replaceFirst("(?i)\\s*patch$", "");
		Path patcherZip = withGame ? zip.resolveSibling(stem + " patcher.zip") : null;
		if (patcherZip != null && Files.exists(patcherZip) && !same(patcherZip, zip)
				&& JOptionPane.showConfirmDialog(frame, patcherZip.getFileName() + " exists. Replace it?", "Export patch",
						JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
			return;
		}
		String titleId = id.toUpperCase(java.util.Locale.ROOT);
		// the game's, whichever tab is in view by the time it runs
		Path patchFolder = patcher.output();
		Path dlcPatch = dlcFolder;
		exporting = true;
		refresh();
		status.setText("Exporting " + target.getFileName() + "…");
		boolean[] dlcPage = {false};
		long[] patcherBytes = {0};
		new SwingWorker<PatchExport.Result, Void>() {
			@Override
			protected PatchExport.Result doInBackground() throws IOException {
				PatcherPage.Title dlcTitle = dlcPatch == null ? null : new PatcherPage.Title(PatcherPage.Kind.DLC,
						"0004008C" + titleId.substring(8), versionOf(dlcBase), TitleBuild.dlcFiles(dlcPatch));
				// the Luma3DS download, with the DLC's page, the DLC having no other way to be patched
				List<PatchExport.Extra> all = new ArrayList<>(extras);
				PatcherPage.Written forDlc = dlcTitle == null ? null
						: PatcherPage.write(pageFolder.resolve("luma"), pageName, List.of(dlcTitle));
				if (forDlc != null) {
					all.addAll(PatcherPage.inFolder(forDlc));
					dlcPage[0] = true;
				}
				String text = PatchExport.withoutFooter(readme).stripTrailing();
				if (patcherZip != null) {
					text += "\n\nThere is also a patcher download, " + patcherZip.getFileName() + ", for building a patched copy of "
							+ "the game to install or play instead of using Luma3DS game patching.";
				}
				if (forDlc != null) {
					text += "\n\nThe DLC is patched by building a patched copy of it: see " + PatcherPage.FOLDER + "/README.txt.";
				}
				PatchExport.Result r = PatchExport.write(patchFolder, titleId, text + "\n", all, target, step -> { });
				// the patcher download: the page, for the game and the DLC, and its readme
				if (patcherZip != null) {
					List<PatcherPage.Title> titles = new ArrayList<>();
					titles.add(new PatcherPage.Title(PatcherPage.Kind.GAME, titleId, versionOf(gameBase), TitleBuild.gameFiles(patchFolder)));
					if (dlcTitle != null) {
						titles.add(dlcTitle);
					}
					PatcherPage.Written page = PatcherPage.write(pageFolder.resolve("patcher"), pageName, titles);
					if (page != null) {
						PatchExport.writeFiles(titleId, page.readme(), List.of(new PatchExport.Extra(page.page(), PatcherPage.PAGE)),
								patcherZip);
						patcherBytes[0] = Files.size(patcherZip);
					}
				}
				return r;
			}

			@Override
			protected void done() {
				exporting = false;
				refresh();
				try {
					PatchExport.Result r = get();
					String also = (patcherBytes[0] > 0 ? String.format(java.util.Locale.ROOT,
							"%n%nAnd the patcher download, %s (%.1f MB): players open its %s in a browser to build a patched copy of "
									+ "%s from their own.", patcherZip.getFileName(), patcherBytes[0] / 1048576.0, PatcherPage.PAGE,
							dlcPatch != null ? "the game or its DLC" : "the game") : "")
							+ (dlcPage[0] ? "\n\nThe DLC's page is in the Luma3DS zip too, in " + PatcherPage.FOLDER + "/." : "");
					JOptionPane.showMessageDialog(frame, String.format(java.util.Locale.ROOT,
							"Packed %,d files%s (%.1f MB) into %s,%nlaid out for Luma3DS under luma/titles/%s/romfs/, "
									+ "with the readme.", r.files(),
							r.extras() == 0 ? "" : String.format(java.util.Locale.ROOT, " and %,d added %s", r.extras(),
									r.extras() == 1 ? "file" : "files"),
							r.bytes() / 1048576.0, target, titleId) + also, "Export patch", JOptionPane.INFORMATION_MESSAGE);
				} catch (java.util.concurrent.ExecutionException e) {
					error("Could not export the patch:\n" + e.getCause().getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}.execute();
	}

	/**
	 * Asks what to do with the unsaved English of {@code of} (the game, its DLC, or both), a DLC's
	 * tables named as its; true when it is safe to go on.
	 */
	private boolean confirmUnsaved(List<Project> of) {
		List<String> tables = new ArrayList<>();
		for (Project p : of) {
			for (String t : p.translations.dirtyTables()) {
				tables.add(p == dlc ? "DLC " + t : t);
			}
		}
		if (tables.isEmpty()) {
			return true;
		}
		int choice = JOptionPane.showConfirmDialog(frame, "Save your changes to " + String.join(", ", tables) + " first?",
				TITLE, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.YES_OPTION ? save() : choice == JOptionPane.NO_OPTION;
	}

	private void close() {
		if (confirmUnsaved(projects())) {
			// a window of its own, not owned by the frame, so it would outlive it (and keep the app running)
			if (imagesWindow != null) {
				imagesWindow.dispose();
				imagesWindow = null;
			}
			frame.dispose();
		}
	}

	private void setBody(Component next) {
		frame.remove(body);
		body = next;
		frame.add(body, BorderLayout.CENTER);
		frame.revalidate();
		frame.repaint();
	}

	/** Title, status bar and Save item and button after anything that changes what is open or unsaved. */
	private void refresh() {
		// Save saves the game and its DLC alike, whichever is in view
		boolean dirty = projects().stream().anyMatch(p -> p.translations.isDirty());
		boolean dirtyHere = translations != null && translations.isDirty();
		save.setEnabled(dirty);
		saveButton.setEnabled(dirty);
		exportPatch.setEnabled(patcher != null && !exporting);
		buildItem.setEnabled(patcher != null && !exporting);
		fontsItem.setEnabled(patcher != null);
		fillFromGame.setEnabled(dlc != null && active == dlc);
		saveButton.putClientProperty(FlatClientProperties.STYLE, dirty ? HIGHLIGHT : null);
		if (index != null) {
			planTimer.restart();
		}
		if (index == null) {
			frame.setTitle(TITLE);
			status.setText(" ");
			return;
		}
		frame.setTitle((dirty ? "• " : "") + TITLE + " — " + game.dir + (active == dlc ? " — DLC " + dlc.dir : ""));
		List<StringTable> inUse = unused.inUse(index.tables());
		String elsewhere = dirty && !dirtyHere ? " · Unsaved changes in the " + (active == dlc ? "game" : "DLC") : "";
		status.setText(String.format("%s%,d of %,d strings translated · Saved to %s%s%s", active == dlc ? "DLC: " : "",
				translations.translatedCountIn(inUse), inUse.stream().mapToInt(t -> t.strings().size()).sum(),
				translations.folder(), dirtyHere ? " · Unsaved changes" : "", elsewhere));
	}

	private void error(String message) {
		JOptionPane.showMessageDialog(frame, message, TITLE, JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * Sets the macOS dock icon, which a packaged app gets from its bundle but a run from Maven
	 * does not.
	 */
	private static void setDockIcon() {
		if (AppIcon.image() != null && Taskbar.isTaskbarSupported()
				&& Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
			Taskbar.getTaskbar().setIconImage(AppIcon.image());
		}
	}
}
