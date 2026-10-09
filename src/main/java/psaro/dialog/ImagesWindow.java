package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.format.Bclim;
import psaro.format.Texture;
import psaro.patch.ImageEdits;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Image;

/**
 * The game's layout images, for those with text drawn into them (a button's label, a screen's
 * title): each one with the copies of it in other archives, shown as the game has it and as
 * replaced. Export one as a PNG, change it in any image editor, and import it back; Patch writes
 * it, in the image's own format and size, into every archive that has it.
 *
 * <p>A window of its own, not a dialog, so it can be minimized and maximized and left open
 * beside the editor.
 */
public final class ImagesWindow extends JFrame {

	/** One image and every archive's copy of it (the same file, byte for byte). */
	private record Group(String hash, List<Image> copies) {
		Image first() {
			return copies.get(0);
		}
	}

	/** The last folder a PNG was exported to or imported from, this session. */
	private static File folder;

	private final RomfsIndex index;
	private final ImageEdits edits;
	private final Runnable onChange;
	private final List<Group> groups;
	private final DefaultListModel<Group> shown = new DefaultListModel<>();
	private final JList<Group> list = new JList<>(shown);
	private final JTextField filter = new JTextField(16);
	private final JCheckBox editedOnly = new JCheckBox("Edited only");
	private final JComboBox<String> zoom = new JComboBox<>(new String[] {"1×", "2×", "3×", "4×", "6×"});
	private final Picture original = new Picture();
	private final Picture replaced = new Picture();
	private final JTextArea info = new JTextArea(5, 40);
	private final JButton exportButton = new JButton("Export PNG…");
	private final JButton importButton = new JButton("Import PNG…");
	private final JButton revertButton = new JButton("Revert");

	/** {@code onChange} runs after an image is replaced or put back, so the window checks the patch again. */
	public ImagesWindow(Frame owner, RomfsIndex index, ImageEdits edits, Runnable onChange) {
		super("Images");
		setIconImages(owner.getIconImages());
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		this.index = index;
		this.edits = edits;
		this.onChange = onChange;
		Map<String, List<Image>> byHash = new LinkedHashMap<>();
		for (Image i : index.images()) {
			byHash.computeIfAbsent(i.hash(), k -> new ArrayList<>()).add(i);
		}
		groups = byHash.entrySet().stream().map(e -> new Group(e.getKey(), List.copyOf(e.getValue())))
				.sorted((a, b) -> a.first().name().compareToIgnoreCase(b.first().name())).toList();

		JLabel intro = new JLabel("<html>Images with text drawn into them, such as the menu's button labels and screen "
				+ "titles, are changed here, not in the strings. Export one, edit it in any image editor at the same size, "
				+ "and import it back: Patch writes it into every archive that has the same image.</html>");
		intro.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
		intro.setPreferredSize(new Dimension(820, intro.getPreferredSize().height * 2));

		filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter by name, as config or title");
		filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
		filter.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				refilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				refilter();
			}
		});
		editedOnly.addActionListener(e -> refilter());
		JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		filters.add(filter);
		filters.add(editedOnly);

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new Renderer());
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				showSelected();
			}
		});
		JPanel left = new JPanel(new BorderLayout());
		left.add(filters, BorderLayout.NORTH);
		left.add(new JScrollPane(list), BorderLayout.CENTER);

		zoom.setSelectedIndex(0);
		zoom.addActionListener(e -> showSelected());
		JPanel zoomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		zoomBar.add(new JLabel("Zoom:"));
		zoomBar.add(zoom);
		JPanel pictures = new JPanel(new GridLayout(1, 2, 8, 0));
		pictures.add(titled("The game's", original));
		pictures.add(titled("Yours (what Patch writes)", replaced));
		info.setEditable(false);
		info.setLineWrap(true);
		info.setWrapStyleWord(true);
		info.setOpaque(false);
		info.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		JPanel right = new JPanel(new BorderLayout());
		right.add(zoomBar, BorderLayout.NORTH);
		right.add(pictures, BorderLayout.CENTER);
		right.add(info, BorderLayout.SOUTH);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
		split.setDividerLocation(330);
		split.setPreferredSize(new Dimension(980, 520));

		exportButton.setToolTipText("Save the image as a PNG: yours when you have replaced it, else the game's");
		importButton.setToolTipText("Replace the image with a PNG of the same size");
		revertButton.setToolTipText("Put the game's image back");
		exportButton.addActionListener(e -> exportSelected());
		importButton.addActionListener(e -> importSelected());
		revertButton.addActionListener(e -> revertSelected());
		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
		buttons.add(exportButton);
		buttons.add(importButton);
		buttons.add(revertButton);
		buttons.add(close);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(intro, BorderLayout.NORTH);
		getContentPane().add(split, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		refilter();
		pack();
		setLocationRelativeTo(owner);
	}

	private static JPanel titled(String title, JComponent body) {
		JPanel p = new JPanel(new BorderLayout());
		JLabel l = new JLabel(title);
		l.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");
		l.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 0));
		p.add(l, BorderLayout.NORTH);
		p.add(new JScrollPane(body), BorderLayout.CENTER);
		return p;
	}

	private void refilter() {
		Group was = list.getSelectedValue();
		String needle = filter.getText().trim().toLowerCase(Locale.ROOT);
		shown.clear();
		for (Group g : groups) {
			boolean matches = needle.isEmpty() || g.copies().stream().anyMatch(i -> i.path().toLowerCase(Locale.ROOT).contains(needle)
					|| i.archive().getFileName().toString().toLowerCase(Locale.ROOT).contains(needle));
			if (matches && (!editedOnly.isSelected() || edits.get(g.hash()) != null)) {
				shown.addElement(g);
			}
		}
		if (was != null && shown.contains(was)) {
			list.setSelectedValue(was, true);
		} else if (!shown.isEmpty()) {
			list.setSelectedIndex(0);
		} else {
			showSelected();
		}
	}

	private void showSelected() {
		Group g = list.getSelectedValue();
		exportButton.setEnabled(g != null);
		importButton.setEnabled(g != null);
		revertButton.setEnabled(g != null && edits.get(g.hash()) != null);
		if (g == null) {
			original.show(null, 1);
			replaced.show(null, 1);
			info.setText("");
			return;
		}
		int z = Integer.parseInt(String.valueOf(zoom.getSelectedItem()).replace("×", ""));
		BufferedImage game = gameImage(g);
		original.show(game, z);
		replaced.show(yours(g), z);
		Image first = g.first();
		String where = g.copies().stream().limit(6)
				.map(i -> index.root().relativize(i.archive()).toString().replace(".arc.lz", "")).collect(Collectors.joining(", "));
		info.setText(first.path() + "\n" + first.width() + " × " + first.height() + ", " + Texture.formatName(first.format())
				+ ((first.format() == Texture.ETC1 || first.format() == Texture.ETC1A4)
						? " (compressed: an imported image is compressed the same way, so it can lose a little detail)" : "")
				+ "\nIn " + g.copies().size() + (g.copies().size() == 1 ? " archive: " : " archives: ") + where
				+ (g.copies().size() > 6 ? ", …" : "")
				+ (edits.get(g.hash()) != null ? "\nYours: " + edits.png(g.hash()) : ""));
	}

	/** The image as the game has it, or null when its archive cannot be read. */
	private BufferedImage gameImage(Group g) {
		try {
			return Bclim.read(index.imageBytes(g.first())).image();
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	/** The replacement as Patch would write it (encoded and read back), or null when there is none. */
	private BufferedImage yours(Group g) {
		if (edits.get(g.hash()) == null) {
			return null;
		}
		byte[] file = edits.replacement(g.hash(), () -> {
			try {
				return index.imageBytes(g.first());
			} catch (IOException unreadable) {
				return null;
			}
		});
		return file == null ? null : Bclim.read(file).image();
	}

	private void exportSelected() {
		Group g = list.getSelectedValue();
		BufferedImage img = g == null ? null : edits.get(g.hash()) != null ? yours(g) : gameImage(g);
		if (img == null) {
			return;
		}
		JFileChooser chooser = new JFileChooser(folder);
		chooser.setDialogTitle("Export " + g.first().name());
		chooser.setSelectedFile(new File(folder, g.first().name() + ".png"));
		chooser.setFileFilter(new FileNameExtensionFilter("PNG images", "png"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		File target = chooser.getSelectedFile();
		if (!target.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
			target = new File(target.getParentFile(), target.getName() + ".png");
		}
		folder = target.getParentFile();
		try {
			ImageIO.write(img, "png", target);
		} catch (IOException e) {
			error("Could not write " + target + ":\n" + e.getMessage());
		}
	}

	private void importSelected() {
		Group g = list.getSelectedValue();
		if (g == null) {
			return;
		}
		JFileChooser chooser = new JFileChooser(folder);
		chooser.setDialogTitle("Replace " + g.first().name() + " (" + g.first().width() + " × " + g.first().height() + ")");
		chooser.setFileFilter(new FileNameExtensionFilter("PNG images", "png"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path png = chooser.getSelectedFile().toPath();
		folder = png.getParent().toFile();
		try {
			BufferedImage img = ImageIO.read(Files.newInputStream(png));
			if (img == null) {
				error(png.getFileName() + " is not an image this can read; save it as a PNG.");
				return;
			}
			Image first = g.first();
			if (img.getWidth() != first.width() || img.getHeight() != first.height()) {
				error(png.getFileName() + " is " + img.getWidth() + " × " + img.getHeight() + "; the image it replaces is "
						+ first.width() + " × " + first.height() + ". Resize it to that, keeping the text inside.");
				return;
			}
			edits.set(index.imageBytes(first), first.path(), img);
		} catch (IOException | RuntimeException e) {
			error("Could not replace the image:\n" + e.getMessage());
			return;
		}
		list.repaint();
		showSelected();
		onChange.run();
	}

	private void revertSelected() {
		Group g = list.getSelectedValue();
		if (g == null) {
			return;
		}
		try {
			edits.remove(g.hash());
		} catch (IOException e) {
			error("Could not put the image back:\n" + e.getMessage());
		}
		list.repaint();
		refilter();
		onChange.run();
	}

	private void error(String message) {
		JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
	}

	/** An image's line: its name, size and format, how many archives have it, and a mark when replaced. */
	private final class Renderer extends DefaultListCellRenderer {
		@Override
		public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean selected, boolean focus) {
			Group g = (Group) value;
			Image first = g.first();
			String text = (edits.get(g.hash()) != null ? "✎ " : "") + first.name() + "   " + first.width() + "×" + first.height()
					+ " " + Texture.formatName(first.format()) + (g.copies().size() > 1 ? "   ×" + g.copies().size() : "");
			super.getListCellRendererComponent(l, text, i, selected, focus);
			setToolTipText(first.path());
			return this;
		}
	}

	/** An image drawn whole-pixel at a zoom, on a checkerboard so its transparent parts show. */
	private static final class Picture extends JComponent {
		private BufferedImage image;
		private int zoom = 1;

		void show(BufferedImage img, int z) {
			image = img;
			zoom = z;
			setPreferredSize(img == null ? new Dimension(10, 10) : new Dimension(img.getWidth() * z + 16, img.getHeight() * z + 16));
			revalidate();
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(UIManager.getColor("Panel.background"));
			g2.fillRect(0, 0, getWidth(), getHeight());
			if (image == null) {
				g2.setColor(UIManager.getColor("Label.disabledForeground"));
				g2.drawString("—", 12, 20);
				g2.dispose();
				return;
			}
			int w = image.getWidth() * zoom;
			int h = image.getHeight() * zoom;
			for (int y = 0; y < h; y += 8) {
				for (int x = 0; x < w; x += 8) {
					g2.setColor((x / 8 + y / 8) % 2 == 0 ? new Color(0x99, 0x99, 0xA0) : new Color(0xC8, 0xC8, 0xCE));
					g2.fillRect(8 + x, 8 + y, Math.min(8, w - x), Math.min(8, h - y));
				}
			}
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g2.drawImage(image, 8, 8, w, h, null);
			g2.dispose();
		}
	}
}
