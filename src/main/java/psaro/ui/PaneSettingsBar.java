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
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.patch.FontPatcher;
import psaro.patch.Typeface;
import psaro.romfs.RomfsIndex.Usage;

/**
 * The previewed pane's font, box and type settings, changeable in place, and those of the font it
 * draws with, in three rows:
 * <ol>
 * <li>which of the layout's fonts the pane points at, and what this pane alone draws with;
 * <li>what every pane in that font draws with (the game font, given what it lacks from the game's
 * others; or a font drawn from a bundled {@link Typeface} in its look, which Patch adds in its
 * place), and which letters get an extra pixel of space after them, the same letters the Patch
 * dialog lists (a letter from another font can crowd the next one, its outline included);
 * <li>the pane's box, font size and spacing.
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
	 * and each bundled typeface, by {@link #DRAW_IDS}.
	 */
	private final JComboBox<String> drawWith = new JComboBox<>();
	private static final List<String> DRAW_IDS = new ArrayList<>();

	static {
		DRAW_IDS.add(null);
		for (Typeface t : Typeface.values()) {
			DRAW_IDS.add(t.id());
		}
	}
	private final JButton reset = new JButton("Reset");
	private final JLabel scope = new JLabel();
	/** What every pane in the font draws with. */
	private final JComboBox<Typeface> fontDrawsWith = new JComboBox<>(Typeface.values());
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

	/** {@code onChange} runs after a change is saved, to measure and draw again. */
	PaneSettingsBar(FontPatcher fonts, Runnable onChange) {
		this.fonts = fonts;
		this.onChange = onChange;
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
				+ "English; Noto Sans has no Japanese, so that is drawn from M PLUS Rounded 1c.</html>";
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
		box.add(group(reset, scope));
		scope.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		scope.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		add(pane);
		add(every);
		add(box);

		reset.setToolTipText("Back to the layout's own settings for this pane");
		reset.addActionListener(e -> save(TextOverride.NONE));
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
		refresh();
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
		loading = false;
		reset.setEnabled(false);
		scope.setText(" ");
		showFont(null);
	}

	/** Shows the font's settings as saved, after they were changed elsewhere (the Patch list). */
	void reload() {
		pause.stop();
		loading = true;
		letters.setText(fontName == null ? "" : fonts.settings().extraSpace(fontName));
		fontDrawsWith.setSelectedItem(fontName == null ? Typeface.GAME : fonts.settings().lettersFrom(fontName));
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
		drawWith.addItem("Same as " + shortName(now.font()) + " (" + fonts.settings().lettersFrom(now.font()).label() + ")");
		for (Typeface t : Typeface.values()) {
			drawWith.addItem(t.label());
		}
		drawWith.setSelectedIndex(Math.max(0, DRAW_IDS.indexOf(ownChoice)));
		// a font no archive carries (the system font) cannot be drawn anew
		drawWith.setEnabled(Fit.font(fonts.index(), usage) != null);
		style(drawWith, ownChoice != null);
		loading = false;
		reset.setEnabled(!fonts.overrides().get(usage.layout(), usage.pane().name()).isEmpty());
		showFont(now.font());
	}

	/** Edits {@code name}'s settings in the second row; none when null. */
	private void showFont(String name) {
		// the same font: leave what is being typed alone
		if (!Objects.equals(name, fontName)) {
			fontName = name;
			loading = true;
			letters.setText(name == null ? "" : fonts.settings().extraSpace(name));
			fontDrawsWith.setSelectedItem(name == null ? Typeface.GAME : fonts.settings().lettersFrom(name));
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
				chosen == null || chosen.equals(own.font()) ? null : chosen, draw <= 0 ? null : DRAW_IDS.get(draw));
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

	/** Saves what every pane in the font draws with, then measures again. */
	private void fontDrawsWithChosen() {
		Typeface t = (Typeface) fontDrawsWith.getSelectedItem();
		if (loading || fontName == null || t == null || t == fonts.settings().lettersFrom(fontName)) {
			return;
		}
		try {
			fonts.settings().setLettersFrom(fontName, t);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the font patch settings:\n" + e.getMessage(),
					"Draw with", JOptionPane.ERROR_MESSAGE);
			loading = true;
			fontDrawsWith.setSelectedItem(fonts.settings().lettersFrom(fontName));
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
	 * is given the width before it is asked its height, as its own layout cannot see the bar's.
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
			for (Component row : parent.getComponents()) {
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
			for (Component row : parent.getComponents()) {
				w = Math.max(w, row.getMinimumSize().width);
			}
			return new Dimension(w + in.left + in.right, preferredLayoutSize(parent).height);
		}

		@Override
		public void layoutContainer(Container parent) {
			Insets in = parent.getInsets();
			int width = parent.getWidth() - in.left - in.right;
			int y = in.top;
			for (Component row : parent.getComponents()) {
				row.setSize(width, row.getHeight());
				int h = row.getPreferredSize().height;
				row.setBounds(in.left, y, width, h);
				y += h;
			}
		}
	}
}
