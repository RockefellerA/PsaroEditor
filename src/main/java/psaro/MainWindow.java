package psaro;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.dialog.PreferencesDialog;
import psaro.menu.HelpMenu;

/** The application window. For now: open a romfs and report what it holds. */
public final class MainWindow {

	private static final String TITLE = "PsaroEditor";
	private static final String PREF_ROMFS = "romfs";

	private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);
	private final JFrame frame = new JFrame(TITLE);
	private final JLabel body = new JLabel("", SwingConstants.CENTER);
	private final JLabel status = new JLabel(" ");

	public static void main(String[] args) {
		EventQueue.invokeLater(() -> {
			Theme.saved().apply();
			new MainWindow().show();
		});
	}

	private MainWindow() {
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		frame.setIconImages(AppIcon.windowIcons());
		setDockIcon();
		frame.setJMenuBar(menuBar());

		// a FlatLaf style, not a fixed colour, so it follows a theme change
		body.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		frame.add(body, BorderLayout.CENTER);
		frame.add(status, BorderLayout.SOUTH);

		frame.setSize(1100, 720);
		frame.setLocationRelativeTo(null);
		showEmpty();
	}

	private void show() {
		frame.setVisible(true);
		String last = prefs.get(PREF_ROMFS, null);
		if (last != null && Files.isDirectory(Path.of(last))) {
			open(Path.of(last), false);
		}
	}

	private JMenuBar menuBar() {
		int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

		JMenuItem open = new JMenuItem("Open romfs…");
		open.setMnemonic(KeyEvent.VK_O);
		open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut));
		open.addActionListener(e -> chooseRomfs());

		JMenuItem preferences = new JMenuItem("Preferences…");
		preferences.setMnemonic(KeyEvent.VK_P);
		preferences.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, shortcut));
		preferences.addActionListener(e -> new PreferencesDialog(frame).setVisible(true));

		JMenuItem exit = new JMenuItem("Exit");
		exit.setMnemonic(KeyEvent.VK_X);
		exit.addActionListener(e -> frame.dispose());

		JMenu file = new JMenu("File");
		file.setMnemonic(KeyEvent.VK_F);
		file.add(open);
		file.addSeparator();
		file.add(preferences);
		file.addSeparator();
		file.add(exit);

		JMenuBar bar = new JMenuBar();
		bar.add(file);
		bar.add(new HelpMenu(frame));
		return bar;
	}

	private void chooseRomfs() {
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
		List<Path> tables;
		List<Path> archives;
		try {
			tables = list(dir.resolve("text"), "_Japanese.tdt");
			archives = list(dir, ".arc.lz");
		} catch (IOException | UncheckedIOException e) {
			if (explain) {
				error("Could not read " + dir + ":\n" + e.getMessage());
			}
			return;
		}
		if (tables.isEmpty()) {
			if (explain) {
				error("No string tables were found in " + dir + ".\n"
						+ "PsaroEditor looks for text/*_Japanese.tdt inside an extracted romfs.");
			}
			return;
		}
		prefs.put(PREF_ROMFS, dir.toString());
		frame.setTitle(TITLE + " — " + dir);
		body.setText(archives.size() + " archives, " + tables.size() + " string tables");
		status.setText(dir.toString());
	}

	private void showEmpty() {
		body.setText("Open an extracted romfs to begin (File → Open romfs…)");
		status.setText(" ");
	}

	private void error(String message) {
		JOptionPane.showMessageDialog(frame, message, TITLE, JOptionPane.ERROR_MESSAGE);
	}

	/** Files under {@code dir}, at any depth, whose names end with {@code suffix}. */
	private static List<Path> list(Path dir, String suffix) throws IOException {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
		}
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
