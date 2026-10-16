package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import psaro.patch.PatchSettings;
import psaro.project.ParentLink;
import psaro.project.TitleIds;

/**
 * File → Parent game: the project this one borrows from, such as the game a DLC belongs to. Says
 * what the project's title id makes of it, links or unlinks the parent's romfs, and lets the user
 * choose to import from it, which the caller then does ({@link #importChosen}); see
 * {@link psaro.project.ParentImport} for what an import takes.
 */
public final class ParentGameDialog extends JDialog {

	private static final String PREF_FOLDER = "parentRomfsFolder";

	private final ParentLink link;
	private final String titleId;
	private final Preferences prefs = Preferences.userNodeForPackage(psaro.MainWindow.class);
	private final JLabel parentLabel = new JLabel();
	private final JButton unlink = new JButton("Unlink");
	private final JButton importNow = new JButton("Import from parent…");
	private boolean importChosen;

	/** Edits {@code link}, the parent link of the project whose title id is {@code titleId}, or null if not known. */
	public ParentGameDialog(Frame owner, ParentLink link, String titleId) {
		super(owner, "Parent game", true);
		this.link = link;
		this.titleId = titleId;

		JTextArea about = new JTextArea(kind(titleId) + "\n\nA project can borrow from another, such as DLC from its game: "
				+ "after linking the game's romfs, Import takes the game's English for strings that read the same, "
				+ "the panes they are linked to, its text and placement changes to layouts the DLC shares, its font "
				+ "settings and its imported typefaces. Only what this project has not set itself is taken, and the "
				+ "parent is never changed.", 8, 60);
		about.setLineWrap(true);
		about.setWrapStyleWord(true);
		about.setEditable(false);
		about.setOpaque(false);
		about.setBorder(null);

		JButton choose = new JButton("Choose parent romfs…");
		choose.addActionListener(e -> choose());
		unlink.addActionListener(e -> unlink());
		importNow.addActionListener(e -> {
			importChosen = true;
			dispose();
		});
		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());

		JPanel parentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		parentRow.add(new JLabel("Parent romfs:"));
		parentRow.add(parentLabel);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.add(choose);
		buttons.add(unlink);
		buttons.add(importNow);
		buttons.add(close);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		for (JComponent c : new JComponent[] {about, parentRow, buttons}) {
			c.setAlignmentX(LEFT_ALIGNMENT);
			body.add(c);
			body.add(Box.createVerticalStrut(10));
		}
		add(body, BorderLayout.CENTER);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().setDefaultButton(close);
		show(link.parent());
		pack();
		setLocationRelativeTo(owner);
	}

	/** Whether the dialog closed to import from the parent now. */
	public boolean importChosen() {
		return importChosen;
	}

	/** What the title id says: a DLC, a game, or nothing known. */
	static String kind(String id) {
		if (id == null) {
			return "This project's title id is not known yet (it is set when the patch is exported, or read from a "
					+ "romfs folder named for it).";
		}
		if (TitleIds.isDlc(id)) {
			return "Title id " + id + ": downloadable content. It shares the unique id " + id.substring(8, 14)
					+ " with its game, whose title id starts 00040000.";
		}
		return "Title id " + id + " is not downloadable content, but a project can still borrow from another.";
	}

	private void show(Path parent) {
		parentLabel.setText(parent == null ? "none" : parent + (Files.isDirectory(parent) ? "" : "  (not found)"));
		unlink.setEnabled(parent != null);
		importNow.setEnabled(link.parentExists());
		pack();
	}

	private void choose() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Choose the parent game's extracted romfs folder");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		String last = prefs.get(PREF_FOLDER, null);
		if (last != null && Files.isDirectory(Path.of(last))) {
			chooser.setCurrentDirectory(Path.of(last).toFile());
		}
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path parent = chooser.getSelectedFile().toPath();
		if (parent.getParent() != null) {
			prefs.put(PREF_FOLDER, parent.getParent().toString());
		}
		String parentId = idOf(parent);
		if (titleId != null && parentId != null && !TitleIds.sameGame(titleId, parentId)
				&& JOptionPane.showConfirmDialog(this, "That project's title id, " + parentId + ", does not look like the same "
						+ "game as " + titleId + ".\nLink it anyway?", "Parent game", JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			link.link(parent);
			show(link.parent());
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, e.getMessage(), "Parent game", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void unlink() {
		try {
			link.unlink();
			show(null);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not unlink:\n" + e.getMessage(), "Parent game", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** The title id a project's patch settings (or its folder's name) give, or null. */
	private static String idOf(Path romfs) {
		String saved = null;
		try {
			saved = PatchSettings.open(romfs).titleId();
		} catch (IOException unreadable) {
			// its folder's name may still say
		}
		return TitleIds.of(romfs, saved);
	}
}
