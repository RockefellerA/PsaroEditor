package psaro.ui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.patch.FontPatcher;
import psaro.patch.Typeface;
import psaro.romfs.RomfsIndex.Usage;

/**
 * The previewed pane's font, box and type settings, changeable in place. A change is made to
 * the layout, so it applies to that pane, and its drop-shadow twin or the panes layered with it
 * ({@link Fit#layers}), in every archive that carries the layout; the preview and the fit use it
 * at once, and the patch writes it. A value that differs from the layout's own shows in bold.
 * The font can be any the layout lists, since a pane only points into that list.
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
	private Usage usage;
	private List<String> panes = List.of();
	/** The pane's drop-shadow twins and layers, which take its changes. */
	private List<Usage> twins = List.of();
	/** Set while the spinners are filled in, so that is not taken as a change. */
	private boolean loading;

	/** {@code onChange} runs after a change is saved, to measure and draw again. */
	PaneSettingsBar(FontPatcher fonts, Runnable onChange) {
		this.fonts = fonts;
		this.onChange = onChange;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 6));

		JSpinner boxW = spinner(0, 2000, 1);
		JSpinner boxH = spinner(0, 2000, 1);
		JSpinner sizeX = spinner(0.5, 200, 0.5);
		JSpinner sizeY = spinner(0.5, 200, 0.5);
		JSpinner letters = spinner(-100, 100, 0.1);
		JSpinner lines = spinner(-100, 100, 0.1);
		fields.add(new Field(boxW, TextInfo::boxWidth, "box width"));
		fields.add(new Field(boxH, TextInfo::boxHeight, "box height"));
		fields.add(new Field(sizeX, TextInfo::fontSizeX, "font width"));
		fields.add(new Field(sizeY, TextInfo::fontSizeY, "font height"));
		fields.add(new Field(letters, TextInfo::charSpace, "letter spacing"));
		fields.add(new Field(lines, TextInfo::lineSpace, "line spacing"));

		font.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focus) {
				return super.getListCellRendererComponent(list, value == null ? null : String.valueOf(value)
						.replace(".bcfnt", ""), index, selected, focus);
			}
		});
		font.addActionListener(e -> {
			if (!loading && usage != null) {
				save(fromSpinners());
			}
		});

		JPanel first = row();
		first.add(label("Font:"));
		first.add(font);
		first.add(label("   Box:"));
		first.add(boxW);
		first.add(new JLabel("×"));
		first.add(boxH);
		first.add(label("   Font size:"));
		first.add(sizeX);
		first.add(new JLabel("×"));
		first.add(sizeY);
		drawWith.setToolTipText("<html>What this pane alone draws with. Its font's setting is the one under the "
				+ "English (\"Every pane in this font\"); a typeface here draws just this pane, and its shadow when "
				+ "that uses the same font, from it.</html>");
		drawWith.addActionListener(e -> {
			if (!loading && usage != null) {
				save(fromSpinners());
			}
		});
		JPanel second = row();
		second.add(label("Spacing: letters"));
		second.add(letters);
		second.add(label(" lines"));
		second.add(lines);
		second.add(label("   Draw with:"));
		second.add(drawWith);
		second.add(reset);
		second.add(scope);
		scope.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		scope.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		add(first);
		add(second);

		reset.setToolTipText("Back to the layout's own settings");
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
		usage = u;
		panes = new ArrayList<>(List.of(u.pane().name()));
		twins = Fit.layers(u, all);
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
	}

	/** Fills the spinners with the pane's settings as changed, marking what differs from its own. */
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
				: "The fonts this layout lists" + (switched ? "; the layout's own is " + own.font().replace(".bcfnt", "")
						: ". Patch adds the glyphs the English needs to whichever is chosen."));
		// the font's own setting first, named, then each choice for this pane alone
		String ownChoice = fonts.overrides().get(usage.layout(), usage.pane().name()).drawWith();
		drawWith.removeAllItems();
		drawWith.addItem("This font's setting (" + fonts.settings().lettersFrom(now.font()).label() + ")");
		for (Typeface t : Typeface.values()) {
			drawWith.addItem(t.label() + " for this pane");
		}
		drawWith.setSelectedIndex(Math.max(0, DRAW_IDS.indexOf(ownChoice)));
		// a font no archive carries (the system font) cannot be drawn anew
		drawWith.setEnabled(Fit.font(fonts.index(), usage) != null);
		style(drawWith, ownChoice != null);
		loading = false;
		reset.setEnabled(!fonts.overrides().get(usage.layout(), usage.pane().name()).isEmpty());
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
	 * Saves {@code t} for the pane and its twins. A twin follows a font switch, and what the pane
	 * draws with, only when it drew with the pane's font: a shadow in a blurred font of its own
	 * keeps it.
	 */
	private void save(TextOverride t) {
		try {
			List<String> same = new ArrayList<>(List.of(usage.pane().name()));
			List<String> own = new ArrayList<>();
			for (Usage twin : twins) {
				(Objects.equals(twin.fontName(), usage.fontName()) ? same : own).add(twin.pane().name());
			}
			fonts.overrides().set(usage.layout(), same, t);
			if (!own.isEmpty()) {
				fonts.overrides().set(usage.layout(), own, t.withFont(null).withDrawWith(null));
			}
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the layout change:\n" + e.getMessage(), "Layout",
					JOptionPane.ERROR_MESSAGE);
		}
		refresh();
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

	private static JPanel row() {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}
}
