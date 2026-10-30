package psaro.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.format.Bclyt;
import psaro.format.Bclyt.PaneOverride;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.patch.FontPatcher;
import psaro.patch.Typeface;
import psaro.patch.Typefaces;
import psaro.romfs.RomfsIndex.Usage;

/**
 * The previewed pane's font, box and type settings, changeable in place, and those of the font it
 * draws with, in three areas, each under a header that folds it away and opens it again, the app
 * remembering which are open ({@link #FONT_AREA} and the others), with a line between them:
 * <ol>
 * <li>Font: which of the layout's fonts the pane points at, and what this pane alone draws with;
 * what every pane in that font draws with (the game font, given what it lacks from the game's
 * others; or a font drawn from a bundled {@link Typeface} in its look, which Patch adds in its
 * place), and which letters get an extra pixel of space after them, the same letters the Patch
 * dialog lists (a letter from another font can crowd the next one, its outline included);
 * <li>Text pane: the pane's box, font size, spacing and where it sits;
 * <li>Picture pane: a picture of the same layout (a label's frame, a plate), nearest first, with
 * where it sits, how big it is (a picture stretches its image to its size) and its depth, which
 * sets it apart from the rest of the layout in stereoscopic 3D.
 * </ol>
 * A pane's change is made to the layout, so it applies to that pane, and its drop-shadow twin, the
 * panes layered with it and the other rows of a list it is one row of ({@link Fit#together}), in
 * every archive that carries the layout; a
 * pane value that differs from the layout's own shows in bold. The font can be any the layout
 * lists, since a pane only points into that list. A font's letters are saved a moment after
 * typing stops, and on Enter; everything else at once. The preview and the fit follow, and the
 * patch writes it.
 */
final class PaneSettingsBar extends JPanel {

	/** One setting: its spinner, how to read it from a pane's settings, and what it is called. */
	private record Field(JSpinner spinner, java.util.function.Function<TextInfo, Float> get, String name) {
	}

	private final FontPatcher fonts;
	private final Runnable onChange;
	private final List<Field> fields = new ArrayList<>();
	/** The fonts the layout lists, any of which the pane can draw with. */
	private final JComboBox<String> font = new JComboBox<>();
	/**
	 * What this pane alone draws with: first the font's own setting (null), then the game font
	 * and each typeface there is, by {@link #drawIds}.
	 */
	private final JComboBox<String> drawWith = new JComboBox<>();
	/** The id of each of {@link #drawWith}'s choices, the first null. */
	private final List<String> drawIds = new ArrayList<>();
	private final JButton reset = new JButton("Reset");
	private final JLabel scope = new JLabel();
	/** What every pane in the font draws with. */
	private final JComboBox<Typeface> fontDrawsWith = new JComboBox<>();
	/** Names the font the second row is about. */
	private final JLabel fontDrawsLabel = new JLabel();
	/** The font's extra-space letters. */
	private final JTextField letters = new JTextField(10);
	/** Saves the letters typed once typing pauses. */
	private final Timer pause = new Timer(400, e -> saveLetters());
	private Usage usage;
	private List<String> panes = List.of();
	/** The pane's drop-shadow twins, layers and fellow rows, which take its changes. */
	private List<Usage> twins = List.of();
	/** The font the second row edits, or null. */
	private String fontName;
	/** Set while the controls are filled in, so that is not taken as a change. */
	private boolean loading;
	/** Where the pane sits from its parent; its twins move with it. */
	private final JSpinner posX = spinner(-2000, 2000, 1);
	private final JSpinner posY = spinner(-2000, 2000, 1);
	/** The layout's pictures, nearest the pane first. */
	private final JComboBox<Bclyt.Pane> picture = new JComboBox<>();
	/** Names the layout the pictures are from, as they need not be part of the pane's text. */
	private final JLabel pictureLabel = new JLabel();
	private final JSpinner picX = spinner(-2000, 2000, 1);
	private final JSpinner picY = spinner(-2000, 2000, 1);
	private final JSpinner picW = spinner(0, 2000, 1);
	private final JSpinner picH = spinner(0, 2000, 1);
	private final JSpinner picZ = spinner(-2000, 2000, 0.1);
	private final JButton pictureReset = new JButton("Reset");
	/** The chosen picture's image at the size given it ({@link PictureIcon}). */
	private final JLabel picturePreview = new JLabel();

	/** The three areas' keys in {@link #prefs}, each true while the area is shown. */
	static final String FONT_AREA = "paneSettings.font.shown";
	static final String TEXT_AREA = "paneSettings.textPane.shown";
	static final String PICTURE_AREA = "paneSettings.picturePane.shown";
	/** Where whether each area is shown is remembered for the next run. */
	private final Preferences prefs;

	/** {@code onChange} runs after a change is saved, to measure and draw again. */
	PaneSettingsBar(FontPatcher fonts, Runnable onChange) {
		this(fonts, onChange, Preferences.userNodeForPackage(PaneSettingsBar.class));
	}

	/** As {@link #PaneSettingsBar(FontPatcher, Runnable)}, remembering which areas are shown in {@code prefs}. */
	PaneSettingsBar(FontPatcher fonts, Runnable onChange, Preferences prefs) {
		this.fonts = fonts;
		this.onChange = onChange;
		this.prefs = prefs;
		setLayout(new Rows());
		setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 6));

		JSpinner boxW = spinner(0, 2000, 1);
		JSpinner boxH = spinner(0, 2000, 1);
		JSpinner sizeX = spinner(0.5, 200, 0.5);
		JSpinner sizeY = spinner(0.5, 200, 0.5);
		JSpinner charSpace = spinner(-100, 100, 0.1);
		JSpinner lineSpace = spinner(-100, 100, 0.1);
		fields.add(new Field(boxW, TextInfo::boxWidth, "box width"));
		fields.add(new Field(boxH, TextInfo::boxHeight, "box height"));
		fields.add(new Field(sizeX, TextInfo::fontSizeX, "font width"));
		fields.add(new Field(sizeY, TextInfo::fontSizeY, "font height"));
		fields.add(new Field(charSpace, TextInfo::charSpace, "letter spacing"));
		fields.add(new Field(lineSpace, TextInfo::lineSpace, "line spacing"));

		font.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focus) {
				return super.getListCellRendererComponent(list, value == null ? null : shortName(String.valueOf(value)),
						index, selected, focus);
			}
		});
		font.addActionListener(e -> {
			if (!loading && usage != null) {
				save(fromSpinners());
			}
		});
		drawWith.setToolTipText("<html>What this pane alone draws with. \"Same as\" follows the row below, the "
				+ "setting for every pane in its font; a typeface here draws just this pane, and its shadow and the "
				+ "panes layered with it, from it.</html>");
		drawWith.addActionListener(e -> {
			if (!loading && usage != null) {
				save(fromSpinners());
			}
		});

		String fontTip = "<html>What every pane in this font draws with; a pane can choose its own in the row above.<br>"
				+ "Game font: Patch adds the letters this font lacks to it, copied from the game's other "
				+ "fonts.<br>A bundled typeface (SIL Open Font License): Patch leaves the game's font as it is and adds a "
				+ "new font drawn from the typeface, in this font's size and outline, beside it in every archive that "
				+ "carries it, then points the layouts at the new one. It holds what the game's font holds plus the "
				+ "English.</html>";
		fontDrawsLabel.setToolTipText(fontTip);
		fontDrawsWith.setToolTipText(fontTip);
		fontDrawsWith.addActionListener(e -> fontDrawsWithChosen());
		letters.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "none");
		letters.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				lettersEdited();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				lettersEdited();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				lettersEdited();
			}
		});
		letters.addActionListener(e -> saveLetters());
		pause.setRepeats(false);
		JLabel lettersLabel = label("Extra space after:");
		String lettersTip = "Letters that get one more pixel of space after them when Patch adds them to this font, "
				+ "where a borrowed letter crowds the next. Applies to this font everywhere it is used, "
				+ "and shows in the Patch list.";
		lettersLabel.setToolTipText(lettersTip);
		letters.setToolTipText(lettersTip);

		// each label with its controls, so a narrow panel wraps them onto a new line together
		JPanel pane = row();
		pane.add(group(label("Layout font:"), font));
		pane.add(group(label("This pane draws with:"), drawWith));
		JPanel every = row();
		every.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		every.add(group(fontDrawsLabel, fontDrawsWith));
		every.add(group(lettersLabel, letters));
		JPanel box = row();
		box.add(group(label("Box:"), boxW, new JLabel("×"), boxH));
		box.add(group(label("Font size:"), sizeX, new JLabel("×"), sizeY));
		box.add(group(label("Spacing: letters"), charSpace, label("lines"), lineSpace));
		box.add(group(label("Position:"), posX, new JLabel(","), posY));
		box.add(group(reset, scope));
		scope.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		scope.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		JPanel place = row();
		place.add(group(pictureLabel, picture, picturePreview));
		place.add(group(label("at"), picX, new JLabel(","), picY));
		place.add(group(label("size"), picW, new JLabel("×"), picH));
		place.add(group(label("depth"), picZ));
		place.add(group(pictureReset));
		section(FONT_AREA, "Font", pane, every);
		add(separator());
		section(TEXT_AREA, "Text pane", box);
		add(separator());
		section(PICTURE_AREA, "Picture pane", place);

		reset.setToolTipText("Back to the layout's own settings and position for this pane");
		reset.addActionListener(e -> {
			savePosition(true);
			save(TextOverride.NONE);
		});
		String posTip = "Where the pane sits from its parent (x right, y up); its shadow and layers move with it";
		posX.setToolTipText(posTip);
		posY.setToolTipText(posTip);
		for (JSpinner s : List.of(posX, posY)) {
			s.addChangeListener(e -> {
				if (!loading && usage != null) {
					savePosition(false);
				}
			});
		}
		picture.setToolTipText("<html>Any picture of the same layout, nearest this pane first: a label's frame, a plate, "
				+ "or one that has nothing to do with this text. Its image stretches to the size given here.</html>");
		picture.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focus) {
				String text = value instanceof Bclyt.Pane p
						? p.name() + "  (" + format(current(p).width()) + "×" + format(current(p).height()) + ")"
						: "";
				super.getListCellRendererComponent(list, text, index, selected, focus);
				setIcon(value instanceof Bclyt.Pane p ? pictureIcon(p, 28, 16) : null);
				return this;
			}
		});
		picturePreview.setToolTipText("<html>The picture's image at the size given it. The game may also tint, crop or "
				+ "tile it, and a window draws a frame of several images; this shows the image alone.</html>");
		picture.addActionListener(e -> {
			if (!loading) {
				refreshPicture();
			}
		});
		picZ.setToolTipText("<html>The picture's depth from its parent. In the game's stereoscopic 3D a picture away "
				+ "from 0 stands out from the rest of the layout; 0 keeps it flat with it.</html>");
		for (JSpinner s : List.of(picX, picY, picW, picH, picZ)) {
			s.addChangeListener(e -> {
				if (!loading && usage != null && picture.getSelectedItem() != null) {
					savePicture(false);
				}
			});
		}
		pictureReset.setToolTipText("Back to the layout's own place, size and depth for this picture");
		pictureReset.addActionListener(e -> savePicture(true));
		for (Field f : fields) {
			f.spinner().addChangeListener(e -> {
				if (!loading && usage != null) {
					save(fromSpinners());
				}
			});
		}
		clear();
	}

	/**
	 * Shows {@code u}'s settings for change; {@code all} are every pane showing the string, among
	 * which its drop-shadow twins and layers.
	 */
	void show(Usage u, List<Usage> all) {
		flushLetters();
		// shown again after a change (the preview redraws): the picture being changed stays chosen
		Bclyt.Pane chosen = usage != null && usage.layout().equals(u.layout()) ? (Bclyt.Pane) picture.getSelectedItem() : null;
		usage = u;
		panes = new ArrayList<>(List.of(u.pane().name()));
		twins = Fit.together(u, all);
		twins.forEach(o -> panes.add(o.pane().name()));
		loading = true;
		font.removeAllItems();
		u.layoutFonts().forEach(font::addItem);
		loading = false;
		int archives = fonts.index().archivesWithLayout(u.layout()).size();
		String layout = u.layout().substring(u.layout().lastIndexOf('/') + 1);
		scope.setText(archives <= 1 ? "Applies to " + layout : "Applies to " + layout + " in " + archives + " archives");
		setToolTipText("Changes " + String.join(" and ", panes) + " of " + u.layout()
				+ " in every archive that has it. Patch writes the changed layout.");
		pictureLabel.setText("Picture panes in " + layout.replace(".bclyt", "") + ":");
		fillPictures(u, chosen == null ? null : chosen.name());
		refresh();
	}

	/**
	 * The layout's pictures in the picker, those under the pane's own parent first (where its x and
	 * y mean the same), nearest it first; the one named {@code keep} chosen, else the nearest.
	 */
	private void fillPictures(Usage u, String keep) {
		Bclyt.Layout layout = fonts.layout(u.archive(), u.layout());
		List<Bclyt.Pane> pics = new ArrayList<>();
		if (layout != null) {
			layout.panes().stream().filter(Bclyt.Pane::isPicture).forEach(pics::add);
		}
		Bclyt.Pane text = u.pane();
		pics.sort(java.util.Comparator.comparing((Bclyt.Pane p) -> Objects.equals(p.parent(), text.parent()) ? 0 : 1)
				.thenComparingDouble(p -> Math.hypot(p.x() - text.x(), p.y() - text.y())));
		loading = true;
		picture.removeAllItems();
		pics.forEach(picture::addItem);
		pics.stream().filter(p -> p.name().equals(keep)).findFirst().ifPresent(picture::setSelectedItem);
		loading = false;
		refreshPicture();
	}

	void clear() {
		flushLetters();
		usage = null;
		panes = List.of();
		twins = List.of();
		loading = true;
		for (Field f : fields) {
			f.spinner().setEnabled(false);
			f.spinner().setValue(0.0);
			style(f, false, null);
		}
		font.removeAllItems();
		font.setEnabled(false);
		style(font, false);
		font.setToolTipText(null);
		drawWith.removeAllItems();
		drawWith.setEnabled(false);
		style(drawWith, false);
		for (JSpinner s : List.of(posX, posY, picX, picY, picW, picH, picZ)) {
			s.setEnabled(false);
			s.setValue(0.0);
			style(s, false);
		}
		picture.removeAllItems();
		picture.setEnabled(false);
		pictureLabel.setText("Picture panes:");
		picturePreview.setIcon(null);
		loading = false;
		reset.setEnabled(false);
		pictureReset.setEnabled(false);
		scope.setText(" ");
		showFont(null);
	}

	/** Shows the font's settings as saved, after they were changed elsewhere (the Patch list). */
	void reload() {
		pause.stop();
		loading = true;
		letters.setText(fontName == null ? "" : fonts.settings().extraSpace(fontName));
		showFontSetting(fontName);
		loading = false;
		if (usage != null) {
			refresh();
		}
	}

	/** Fills the controls with the pane's settings as changed, marking what differs from its own. */
	private void refresh() {
		TextInfo own = usage.pane().text();
		TextInfo now = fonts.text(usage);
		loading = true;
		for (Field f : fields) {
			float value = f.get().apply(now);
			float original = f.get().apply(own);
			f.spinner().setEnabled(true);
			f.spinner().setValue((double) value);
			style(f, differs(value, original), original);
		}
		font.setSelectedItem(now.font());
		// the system font, or a layout with one font, leaves nothing to switch to
		font.setEnabled(usage.layoutFonts().size() > 1 && usage.layoutFonts().contains(own.font()));
		boolean switched = !Objects.equals(now.font(), own.font());
		style(font, switched);
		font.setToolTipText(usage.layoutFonts().size() <= 1 ? "The layout lists no other font"
				: "Which of the fonts this layout lists the pane points at"
						+ (switched ? "; the layout's own is " + shortName(own.font())
								: ". Patch adds the glyphs the English needs to whichever is chosen."));
		// the font's own setting first, named, then each choice for this pane alone
		String ownChoice = fonts.overrides().get(usage.layout(), usage.pane().name()).drawWith();
		drawWith.removeAllItems();
		Typefaces typefaces = fonts.settings().typefaces();
		drawIds.clear();
		drawIds.add(null);
		drawWith.addItem("Same as " + shortName(now.font()) + " ("
				+ typefaces.named(fonts.settings().lettersFromId(now.font())).label() + ")");
		for (Typeface t : typefaces.all()) {
			drawIds.add(t.id());
			drawWith.addItem(t.label());
		}
		// a typeface not imported here stays chosen, so changing the pane otherwise keeps it
		if (ownChoice != null && !typefaces.has(ownChoice)) {
			drawIds.add(ownChoice);
			drawWith.addItem(typefaces.named(ownChoice).label());
		}
		drawWith.setSelectedIndex(Math.max(0, drawIds.indexOf(ownChoice)));
		// a font no archive carries (the system font) cannot be drawn anew
		drawWith.setEnabled(Fit.font(fonts.index(), usage) != null);
		style(drawWith, ownChoice != null);
		Bclyt.Pane placed = current(usage.pane());
		posX.setEnabled(true);
		posY.setEnabled(true);
		posX.setValue((double) placed.x());
		posY.setValue((double) placed.y());
		style(posX, differs(placed.x(), usage.pane().x()));
		style(posY, differs(placed.y(), usage.pane().y()));
		loading = false;
		boolean moved = !fonts.overrides().place(usage.layout(), usage.pane().name()).isEmpty();
		reset.setEnabled(moved || !fonts.overrides().get(usage.layout(), usage.pane().name()).isEmpty());
		showFont(now.font());
	}

	/** {@code p} where the layout changes put it, at the size they give it. */
	private Bclyt.Pane current(Bclyt.Pane p) {
		return usage == null ? p : fonts.overrides().place(usage.layout(), p.name()).apply(p);
	}

	/** Fills the picture's place, size and depth as changed, marking what differs from the layout's own. */
	private void refreshPicture() {
		Bclyt.Pane own = (Bclyt.Pane) picture.getSelectedItem();
		boolean any = own != null && usage != null;
		picture.setEnabled(picture.getItemCount() > 0);
		loading = true;
		for (JSpinner s : List.of(picX, picY, picW, picH, picZ)) {
			s.setEnabled(any);
		}
		if (any) {
			Bclyt.Pane now = current(own);
			picX.setValue((double) now.x());
			picY.setValue((double) now.y());
			picW.setValue((double) now.width());
			picH.setValue((double) now.height());
			picZ.setValue((double) now.z());
			style(picX, differs(now.x(), own.x()));
			style(picY, differs(now.y(), own.y()));
			style(picW, differs(now.width(), own.width()));
			style(picH, differs(now.height(), own.height()));
			style(picZ, differs(now.z(), own.z()));
		}
		loading = false;
		pictureReset.setEnabled(any && !fonts.overrides().place(usage.layout(), own.name()).isEmpty());
		picturePreview.setIcon(any ? pictureIcon(own, 72, 36) : null);
		picture.repaint();
	}

	/**
	 * Picture {@code p}'s image at the size the layout changes give it, fitted into
	 * {@code maxWidth} × {@code maxHeight}; null when it draws none this can read.
	 */
	private PictureIcon pictureIcon(Bclyt.Pane p, int maxWidth, int maxHeight) {
		if (usage == null) {
			return null;
		}
		java.awt.image.BufferedImage image = fonts.pictureImage(usage.archive(), usage.layout(), p.name());
		if (image == null) {
			return null;
		}
		Bclyt.Pane now = current(p);
		return new PictureIcon(image, now.width(), now.height(), maxWidth, maxHeight);
	}

	/**
	 * Moves the pane where the position spinners say, and its twins by as much, keeping each where
	 * the layout has it in what does not move; back where the layout has them when {@code back}.
	 */
	private void savePosition(boolean back) {
		Bclyt.Pane own = usage.pane();
		float dx = back ? 0 : ((Number) posX.getValue()).floatValue() - own.x();
		float dy = back ? 0 : ((Number) posY.getValue()).floatValue() - own.y();
		java.util.Map<String, PaneOverride> moves = new java.util.LinkedHashMap<>();
		moves.put(own.name(), moved(own, dx, dy));
		for (Usage twin : twins) {
			moves.put(twin.pane().name(), moved(twin.pane(), dx, dy));
		}
		try {
			fonts.overrides().setPlaces(usage.layout(), moves);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the layout change:\n" + e.getMessage(), "Layout",
					JOptionPane.ERROR_MESSAGE);
		}
		if (!back) {
			refresh();
			onChange.run();
		}
	}

	/** {@code p} moved {@code dx}, {@code dy} from where the layout has it, its size and depth as changed. */
	private PaneOverride moved(Bclyt.Pane p, float dx, float dy) {
		PaneOverride now = fonts.overrides().place(usage.layout(), p.name());
		return new PaneOverride(differs(dx, 0) ? p.x() + dx : null, differs(dy, 0) ? p.y() + dy : null, now.width(),
				now.height(), now.z());
	}

	/** Places, sizes and sets the depth of the chosen picture as the spinners say; as the layout has it when {@code back}. */
	private void savePicture(boolean back) {
		Bclyt.Pane own = (Bclyt.Pane) picture.getSelectedItem();
		if (own == null) {
			return;
		}
		PaneOverride p = back ? PaneOverride.NONE
				: new PaneOverride(changed(picX, own.x()), changed(picY, own.y()), changed(picW, own.width()),
						changed(picH, own.height()), changed(picZ, own.z()));
		try {
			fonts.overrides().setPlace(usage.layout(), own.name(), p);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the layout change:\n" + e.getMessage(), "Layout",
					JOptionPane.ERROR_MESSAGE);
		}
		refreshPicture();
		onChange.run();
	}

	/** The spinner's value when it differs from the layout's own, else null. */
	private static Float changed(JSpinner s, float original) {
		// to a thousandth, so stepping -0.6 up by 0.1 saves 0 and not -1.5E-9
		float value = Math.round(((Number) s.getValue()).doubleValue() * 1000) / 1000f;
		return differs(value, original) ? value : null;
	}

	/** Edits {@code name}'s settings in the second row; none when null. */
	private void showFont(String name) {
		// the same font: leave what is being typed alone
		if (!Objects.equals(name, fontName)) {
			fontName = name;
			loading = true;
			letters.setText(name == null ? "" : fonts.settings().extraSpace(name));
			showFontSetting(name);
			loading = false;
		}
		letters.setEnabled(name != null);
		fontDrawsWith.setEnabled(name != null);
		fontDrawsLabel.setText(name == null ? "Every pane in its font draws with:"
				: "Every pane in " + shortName(name) + " draws with:");
	}

	/** The spinners as a change: each value that differs from the layout's own. */
	private TextOverride fromSpinners() {
		TextInfo own = usage.pane().text();
		Float[] v = new Float[fields.size()];
		for (int i = 0; i < v.length; i++) {
			Field f = fields.get(i);
			float value = ((Number) f.spinner().getValue()).floatValue();
			v[i] = differs(value, f.get().apply(own)) ? value : null;
		}
		String chosen = (String) font.getSelectedItem();
		int draw = drawWith.getSelectedIndex();
		return new TextOverride(v[0], v[1], v[2], v[3], v[4], v[5],
				chosen == null || chosen.equals(own.font()) ? null : chosen, draw <= 0 ? null : drawIds.get(draw));
	}

	/**
	 * Saves {@code t} for the pane and its twins. A twin follows a font switch only when it drew
	 * with the pane's font: a shadow in a blurred font of its own keeps it. What the pane draws
	 * with every twin follows, as one text's layers left in a game font without the English's
	 * letters would show nothing of them.
	 */
	private void save(TextOverride t) {
		flushLetters();
		try {
			List<String> same = new ArrayList<>(List.of(usage.pane().name()));
			List<String> own = new ArrayList<>();
			for (Usage twin : twins) {
				(Objects.equals(twin.fontName(), usage.fontName()) ? same : own).add(twin.pane().name());
			}
			fonts.overrides().set(usage.layout(), same, t);
			if (!own.isEmpty()) {
				fonts.overrides().set(usage.layout(), own, t.withFont(null));
			}
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the layout change:\n" + e.getMessage(), "Layout",
					JOptionPane.ERROR_MESSAGE);
		}
		refresh();
		onChange.run();
	}

	/**
	 * Fills the font's choices with every typeface there is, and one not imported here when the
	 * font is set to it, and selects what {@code name} is set to (the game font for none).
	 */
	private void showFontSetting(String name) {
		Typefaces typefaces = fonts.settings().typefaces();
		String id = name == null ? null : fonts.settings().lettersFromId(name);
		List<Typeface> choices = new ArrayList<>(typefaces.all());
		if (!typefaces.has(id)) {
			choices.add(typefaces.named(id));
		}
		fontDrawsWith.removeAllItems();
		choices.forEach(fontDrawsWith::addItem);
		fontDrawsWith.setSelectedItem(typefaces.named(id));
	}

	/** Saves what every pane in the font draws with, then measures again. */
	private void fontDrawsWithChosen() {
		Typeface t = (Typeface) fontDrawsWith.getSelectedItem();
		String now = fontName == null ? null : fonts.settings().lettersFromId(fontName);
		// choosing what it is set to (one not imported here among them) changes nothing
		if (loading || fontName == null || t == null || t.id().equals(now == null ? Typeface.GAME.id() : now)) {
			return;
		}
		try {
			fonts.settings().setLettersFrom(fontName, t);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the font patch settings:\n" + e.getMessage(),
					"Draw with", JOptionPane.ERROR_MESSAGE);
			loading = true;
			showFontSetting(fontName);
			loading = false;
			return;
		}
		fonts.settingsChanged();
		if (usage != null) {
			refresh(); // "Same as" names the new setting
		}
		onChange.run();
	}

	private void lettersEdited() {
		if (!loading && fontName != null) {
			pause.restart();
		}
	}

	/** Saves letters still waiting for typing to pause, before the font they belong to changes. */
	private void flushLetters() {
		if (pause.isRunning()) {
			saveLetters();
		}
	}

	/** Saves the letters typed as the font's when they differ, then measures again. */
	private void saveLetters() {
		pause.stop();
		if (fontName == null || letters.getText().equals(fonts.settings().extraSpace(fontName))) {
			return;
		}
		try {
			fonts.settings().setExtraSpace(fontName, letters.getText());
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the font patch settings:\n" + e.getMessage(),
					"Extra space", JOptionPane.ERROR_MESSAGE);
			return;
		}
		fonts.settingsChanged();
		onChange.run();
	}

	private static boolean differs(float a, float b) {
		return Math.abs(a - b) > 1e-4;
	}

	private void style(Field f, boolean changed, Float original) {
		JComponent editor = ((JSpinner.DefaultEditor) f.spinner().getEditor()).getTextField();
		editor.putClientProperty(FlatClientProperties.STYLE, changed ? "font: bold" : null);
		f.spinner().setToolTipText(original == null ? null
				: "The " + f.name() + (changed ? "; the layout's own is " + format(original) : ", as the layout has it"));
	}

	private static void style(JComponent c, boolean changed) {
		c.putClientProperty(FlatClientProperties.STYLE, changed ? "font: bold" : null);
	}

	/** A spinner's value in bold when it differs from the layout's own, as the box and type fields. */
	private static void style(JSpinner s, boolean changed) {
		style(((JSpinner.DefaultEditor) s.getEditor()).getTextField(), changed);
	}

	private static String shortName(String font) {
		return font == null ? "" : font.replace(".bcfnt", "");
	}

	private static String format(float v) {
		return v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
	}

	private static JSpinner spinner(double min, double max, double step) {
		JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(min, 0.0), min, max, step));
		s.setEditor(new JSpinner.NumberEditor(s, "0.##"));
		((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(4);
		return s;
	}

	private static JLabel label(String text) {
		return new JLabel(text);
	}

	/** One line of the bar, wrapping its groups when the panel is narrow. */
	private static JPanel row() {
		return new JPanel(new WrapLayout(8, 2));
	}

	/**
	 * An area of the bar: a header naming it, which shows and hides {@code rows} when clicked, then
	 * the rows. Whether it is shown is kept under {@code key} for the next run.
	 */
	private void section(String key, String title, JComponent... rows) {
		JButton header = new JButton();
		header.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
		header.putClientProperty(FlatClientProperties.STYLE, "font: bold; margin: 1,2,1,2");
		header.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
		header.setFocusable(false);
		header.setName(key);
		JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		line.add(header);
		add(line);
		for (JComponent r : rows) {
			add(r);
		}
		Runnable show = () -> {
			boolean shown = prefs.getBoolean(key, true);
			header.setText((shown ? "▾ " : "▸ ") + title);
			header.setToolTipText((shown ? "Hide" : "Show") + " the " + title.toLowerCase(Locale.ROOT) + " settings");
			for (JComponent r : rows) {
				r.setVisible(shown);
			}
			revalidate();
			repaint();
		};
		header.addActionListener(e -> {
			prefs.putBoolean(key, !prefs.getBoolean(key, true));
			show.run();
		});
		show.run();
	}

	/** A line between two areas of the bar. */
	private static JComponent separator() {
		JSeparator line = new JSeparator();
		line.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		return line;
	}

	/** A label with its controls, kept on one line. */
	private static JPanel group(JComponent... parts) {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		p.setOpaque(false);
		for (JComponent c : parts) {
			p.add(c);
		}
		return p;
	}

	/**
	 * The rows stacked at the bar's full width, each as tall as it wraps to at that width: each row
	 * is given the width before it is asked its height, as its own layout cannot see the bar's. A
	 * hidden row (an area folded away) takes no room.
	 */
	private static final class Rows implements LayoutManager {

		@Override
		public void addLayoutComponent(String name, Component c) {
		}

		@Override
		public void removeLayoutComponent(Component c) {
		}

		@Override
		public Dimension preferredLayoutSize(Container parent) {
			Insets in = parent.getInsets();
			int width = parent.getWidth() - in.left - in.right;
			int w = 0;
			int h = 0;
			for (Component row : shown(parent)) {
				if (width > 0) {
					row.setSize(width, row.getHeight());
				}
				Dimension d = row.getPreferredSize();
				w = Math.max(w, d.width);
				h += d.height;
			}
			return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
		}

		@Override
		public Dimension minimumLayoutSize(Container parent) {
			Insets in = parent.getInsets();
			int w = 0;
			for (Component row : shown(parent)) {
				w = Math.max(w, row.getMinimumSize().width);
			}
			return new Dimension(w + in.left + in.right, preferredLayoutSize(parent).height);
		}

		@Override
		public void layoutContainer(Container parent) {
			Insets in = parent.getInsets();
			int width = parent.getWidth() - in.left - in.right;
			int y = in.top;
			for (Component row : shown(parent)) {
				row.setSize(width, row.getHeight());
				int h = row.getPreferredSize().height;
				row.setBounds(in.left, y, width, h);
				y += h;
			}
		}

		/** The rows not folded away. */
		private static List<Component> shown(Container parent) {
			return java.util.Arrays.stream(parent.getComponents()).filter(Component::isVisible).toList();
		}
	}
}
