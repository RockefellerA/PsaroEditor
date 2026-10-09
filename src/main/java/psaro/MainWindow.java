package psaro;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
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
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.dialog.FontPatchDialog;
import psaro.dialog.ImagesWindow;
import psaro.patch.ImageEdits;
import psaro.dialog.PreferencesDialog;
import psaro.menu.HelpMenu;
import psaro.patch.FontPatcher;
import psaro.patch.FontPatcher.Plan;
import psaro.patch.FontPatcher.Text;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatchSettings;
import psaro.patch.TextExport;
import psaro.project.CodeColors;
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
		open.addActionListener(e -> chooseRomfs());

		save.setMnemonic(KeyEvent.VK_S);
		save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut));
		save.addActionListener(e -> save());

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

		JMenuBar bar = new JMenuBar();
		bar.add(file);
		bar.add(Box.createHorizontalStrut(6));
		bar.add(search);
		bar.add(Box.createHorizontalStrut(6));
		bar.add(new HelpMenu(frame));
		return bar;
	}

	private void chooseRomfs() {
		if (!confirmUnsaved()) {
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
	 * {@code *_Japanese.tdt} string tables. {@code explain} reports a rejection; a romfs
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
			UnusedTables unused, PatchSettings patchSettings, LayoutOverrides layoutOverrides, ImageEdits imageEdits) {
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
	 * Reads {@code dir} if it looks like an extracted romfs: a {@code text} folder of
	 * {@code *_Japanese.tdt} string tables. Touches nothing in the window, so it can run in the
	 * background.
	 */
	private static Opened read(Path dir) throws OpenFailure {
		RomfsIndex scanned;
		try {
			if (!RomfsIndex.looksLikeRomfs(dir)) {
				throw new OpenFailure("No string tables were found in " + dir + ".\n"
						+ "PsaroEditor looks for text/*_Japanese.tdt inside an extracted romfs.", false);
			}
			scanned = RomfsIndex.scan(dir);
		} catch (IOException | UncheckedIOException | IllegalArgumentException e) {
			throw new OpenFailure("Could not read " + dir + ":\n" + e.getMessage(), false);
		}
		try {
			return new Opened(dir, scanned, Translations.open(scanned), CodeColors.open(dir), UnusedTables.open(dir),
					PatchSettings.open(dir), LayoutOverrides.open(dir), ImageEdits.open(dir));
		} catch (IOException e) {
			// always reported: opening anyway could later save over the unreadable file
			throw new OpenFailure("Could not read the translations for " + dir + ":\n" + e.getMessage(), true);
		}
	}

	/** Shows what {@link #read} found in the editor. */
	private void install(Opened o) {
		Path dir = o.dir();
		index = o.index();
		translations = o.translations();
		CodeColors colors = o.colors();
		prefs.put(PREF_ROMFS, dir.toString());
		unused = o.unused();
		patcher = new FontPatcher(index, o.patchSettings(), o.layoutOverrides(), o.imageEdits());
		if (imagesWindow != null) {
			// it lists the romfs that was open
			imagesWindow.dispose();
			imagesWindow = null;
		}
		plan = null;
		editor = new EditorPanel(patcher, translations, colors, unused, actions, this::refresh);
		setBody(editor);
		search.setSource(new StringSearch(index, translations), translations, editor::showString);
		refresh();
		// English saved before the game text was written alongside it
		exportText(false);
	}

	/**
	 * Writes the string tables with the English into the patch folder, and with {@code toMods}
	 * copies the changed ones to the mods folder when the font patch settings say so. The English
	 * itself is already saved, so a failure here loses nothing.
	 */
	private void exportText(boolean toMods) {
		try {
			List<Path> wrote = TextExport.export(index, translations);
			if (toMods) {
				patcher.copyToMods(wrote, step -> { });
			}
		} catch (IOException e) {
			error("The English is saved, but the game text could not be written to " + patcher.output() + ":\n"
					+ e.getMessage());
		}
	}

	/** Every string with English, as the game would show it. */
	private List<Text> texts() {
		List<Text> out = new ArrayList<>();
		for (StringTable t : index.tables()) {
			for (String key : t.strings().keySet()) {
				String text = translations.get(t, key);
				if (text != null) {
					out.add(new Text(t, key, text));
				}
			}
		}
		return out;
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
		FontPatcher checking = patcher;
		List<Text> snapshot = texts();
		new SwingWorker<Plan, Void>() {
			@Override
			protected Plan doInBackground() {
				return checking.plan(snapshot);
			}

			@Override
			protected void done() {
				planning = false;
				if (checking == patcher) {
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

	private void openPatch() {
		new FontPatchDialog(frame, patcher, this::texts, () -> {
			editor.fontsChanged();
			startPlan();
		}).setVisible(true);
		startPlan();
	}

	/** Opens the images window, or brings it forward when it is open already. */
	private void openImages() {
		if (patcher == null) {
			return;
		}
		if (imagesWindow == null || !imagesWindow.isDisplayable()) {
			imagesWindow = new ImagesWindow(frame, index, patcher.images(), this::startPlan);
		}
		imagesWindow.setVisible(true);
		imagesWindow.setExtendedState(imagesWindow.getExtendedState() & ~JFrame.ICONIFIED);
		imagesWindow.toFront();
	}

	private boolean save() {
		if (translations == null || !translations.isDirty()) {
			return true;
		}
		try {
			translations.save();
		} catch (IOException e) {
			error("Could not save the translations to " + translations.folder() + ":\n" + e.getMessage());
			return false;
		}
		refresh();
		exportText(true);
		return true;
	}

	/** Asks what to do with unsaved English; true when it is safe to go on. */
	private boolean confirmUnsaved() {
		if (translations == null || !translations.isDirty()) {
			return true;
		}
		int choice = JOptionPane.showConfirmDialog(frame,
				"Save your changes to " + String.join(", ", translations.dirtyTables()) + " first?",
				TITLE, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.YES_OPTION ? save() : choice == JOptionPane.NO_OPTION;
	}

	private void close() {
		if (confirmUnsaved()) {
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
		boolean dirty = translations != null && translations.isDirty();
		save.setEnabled(dirty);
		saveButton.setEnabled(dirty);
		saveButton.putClientProperty(FlatClientProperties.STYLE, dirty ? HIGHLIGHT : null);
		if (index != null) {
			planTimer.restart();
		}
		if (index == null) {
			frame.setTitle(TITLE);
			status.setText(" ");
			return;
		}
		frame.setTitle((dirty ? "• " : "") + TITLE + " — " + index.root());
		List<StringTable> inUse = unused.inUse(index.tables());
		status.setText(String.format("%,d of %,d strings translated · Saved to %s%s",
				translations.translatedCountIn(inUse), inUse.stream().mapToInt(t -> t.strings().size()).sum(),
				translations.folder(),
				dirty ? " · Unsaved changes" : ""));
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
