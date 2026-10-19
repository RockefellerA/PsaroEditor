package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.project.DlcLink;
import psaro.romfs.RomfsIndex;

/**
 * File → Open romfs: whether the romfs is a game's or a DLC's. A game's is then chosen as it always
 * was. A DLC is worked on beside its game, its project kept in the game's ({@code <game>.psaro/DLC}):
 * this chooses the game (the one open, unless another is chosen), the DLC's folder of content
 * romfs folders (one per content of its CIA, as extracted), and which of those to work on, all of
 * them unless fewer are ticked.
 */
public final class OpenRomfsDialog extends JDialog {

	/** What was chosen: a game, a DLC, or nothing. */
	public enum Kind {
		GAME, DLC
	}

	private final JRadioButton game = new JRadioButton("Game");
	private final JRadioButton dlc = new JRadioButton("DLC");
	private final JLabel gameLabel = new JLabel();
	private final JLabel dlcLabel = new JLabel();
	private final JButton chooseGame = new JButton("Choose…");
	private final JButton chooseDlc = new JButton("Choose…");
	private final JPanel contentList = new JPanel();
	private final JLabel contentNote = new JLabel(" ");
	private final JButton all = new JButton("All");
	private final JButton none = new JButton("None");
	private final JButton open = new JButton("Open");
	private final List<JCheckBox> contents = new ArrayList<>();
	private final List<JComponent> dlcOnly = new ArrayList<>();
	private Path gameRoot;
	private Path dlcFolder;
	private Kind chosen;

	/**
	 * Opens on {@code start} (DLC when the user asked for one, say by the DLC tab), with the game
	 * open ({@code openGame}, or null) and its DLC as last attached ({@code current}, or null)
	 * filled in.
	 */
	public OpenRomfsDialog(Frame owner, Kind start, Path openGame, DlcLink.Dlc current) {
		super(owner, "Open romfs", true);
		gameRoot = openGame;

		ButtonGroup kinds = new ButtonGroup();
		kinds.add(game);
		kinds.add(dlc);
		(start == Kind.DLC ? dlc : game).setSelected(true);
		game.addActionListener(e -> update());
		dlc.addActionListener(e -> update());
		chooseGame.addActionListener(e -> chooseGame());
		chooseDlc.addActionListener(e -> chooseDlc());
		all.addActionListener(e -> tickAll(true));
		none.addActionListener(e -> tickAll(false));
		open.addActionListener(e -> {
			chosen = game.isSelected() ? Kind.GAME : Kind.DLC;
			dispose();
		});
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());

		JPanel body = new JPanel(new GridBagLayout());
		body.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		body.add(game, c);
		c.gridy++;
		c.insets = new Insets(0, 24, 6, 0);
		body.add(hint("An extracted romfs of the game itself, chosen next."), c);
		c.gridy++;
		c.insets = new Insets(4, 0, 0, 0);
		body.add(dlc, c);
		c.gridy++;
		c.insets = new Insets(0, 24, 8, 0);
		body.add(hint("<html>The content romfs folders extracted from a DLC's CIA, one folder per content, worked on<br>"
				+ "beside its game: the DLC's project is kept in the game's, under DLC, and inherits its settings.</html>"), c);

		c.gridwidth = 1;
		c.insets = new Insets(2, 24, 2, 6);
		c.gridy++;
		row(body, c, "Game:", gameLabel, chooseGame);
		c.gridy++;
		row(body, c, "DLC folder:", dlcLabel, chooseDlc);

		contentList.setLayout(new BoxLayout(contentList, BoxLayout.Y_AXIS));
		JScrollPane scroll = new JScrollPane(contentList);
		scroll.setPreferredSize(new Dimension(460, 220));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		JPanel listHead = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		JLabel listLabel = new JLabel("Content romfs folders to work on:");
		listHead.add(listLabel);
		listHead.add(all);
		listHead.add(none);
		c.gridy++;
		c.gridx = 0;
		c.gridwidth = 3;
		c.insets = new Insets(8, 20, 2, 0);
		body.add(listHead, c);
		c.gridy++;
		c.insets = new Insets(0, 24, 2, 0);
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 1;
		c.weighty = 1;
		body.add(scroll, c);
		c.gridy++;
		c.weighty = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		body.add(contentNote, c);
		contentNote.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		dlcOnly.addAll(List.of(chooseGame, chooseDlc, listLabel, all, none, scroll, contentList, contentNote));

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
		buttons.add(open);
		buttons.add(cancel);
		add(body, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(open);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		showGame();
		if (current != null && Files.isDirectory(current.folder())) {
			setDlcFolder(current.folder(), current.contents());
		} else {
			showDlc(List.of());
		}
		update();
		pack();
		setLocationRelativeTo(owner);
	}

	/** What was chosen, or null when the dialog was closed without. */
	public Kind chosen() {
		return chosen;
	}

	/** The game the DLC goes with. */
	public Path gameRoot() {
		return gameRoot;
	}

	/** The DLC's folder of content romfs folders. */
	public Path dlcFolder() {
		return dlcFolder;
	}

	/** The content folders ticked, by name, in order. */
	public List<String> contents() {
		return contents.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList();
	}

	private static JLabel hint(String text) {
		JLabel l = new JLabel(text);
		l.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		return l;
	}

	private void row(JPanel body, GridBagConstraints c, String label, JLabel value, JButton choose) {
		JLabel l = new JLabel(label);
		c.gridx = 0;
		c.weightx = 0;
		body.add(l, c);
		c.gridx = 1;
		c.weightx = 1;
		c.insets = new Insets(2, 0, 2, 6);
		body.add(value, c);
		c.gridx = 2;
		c.weightx = 0;
		body.add(choose, c);
		c.insets = new Insets(2, 24, 2, 6);
		dlcOnly.add(l);
		dlcOnly.add(value);
	}

	/** Enables the DLC's choices while DLC is chosen, and Open once there is something to open. */
	private void update() {
		boolean isDlc = dlc.isSelected();
		dlcOnly.forEach(c -> c.setEnabled(isDlc));
		contents.forEach(b -> b.setEnabled(isDlc));
		open.setEnabled(!isDlc || gameRoot != null && dlcFolder != null && !contents().isEmpty());
	}

	private void showGame() {
		gameLabel.setText(gameRoot == null ? "none open: choose the game's romfs" : gameRoot.toString());
	}

	private void showDlc(List<String> found) {
		dlcLabel.setText(dlcFolder == null ? "none chosen" : dlcFolder.toString());
		contentNote.setText(dlcFolder == null ? "Choose the folder that holds the DLC's content romfs folders."
				: found.isEmpty() ? "No content romfs folders were found in it: choose the folder that holds them."
				: found.size() + " content romfs folders found.");
	}

	private void tickAll(boolean on) {
		contents.forEach(b -> b.setSelected(on));
		update();
	}

	private void chooseGame() {
		JFileChooser chooser = new JFileChooser(gameRoot == null ? null : gameRoot.toFile());
		chooser.setDialogTitle("Choose the game's extracted romfs");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			gameRoot = chooser.getSelectedFile().toPath();
			showGame();
			update();
		}
	}

	private void chooseDlc() {
		Path start = dlcFolder != null ? dlcFolder : gameRoot;
		JFileChooser chooser = new JFileChooser(start == null ? null : start.toFile());
		chooser.setDialogTitle("Choose the folder of the DLC's content romfs folders");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			setDlcFolder(chooser.getSelectedFile().toPath(), null);
			update();
		}
	}

	/** Lists the content folders of {@code folder}, ticking those of {@code ticked}, or every one when it is null. */
	private void setDlcFolder(Path folder, List<String> ticked) {
		List<String> found;
		try {
			found = RomfsIndex.contentFolders(folder);
		} catch (IOException e) {
			found = List.of();
		}
		dlcFolder = folder;
		contents.clear();
		contentList.removeAll();
		for (String name : found) {
			JCheckBox box = new JCheckBox(name, ticked == null || ticked.contains(name));
			box.addActionListener(e -> update());
			contents.add(box);
			contentList.add(box);
		}
		contentList.add(Box.createVerticalGlue());
		contentList.revalidate();
		contentList.repaint();
		showDlc(found);
	}
}
