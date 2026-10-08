package psaro.ui;

import java.awt.FlowLayout;
import java.io.IOException;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.patch.FontPatcher;
import psaro.patch.Typeface;
import psaro.romfs.RomfsIndex.Usage;

/**
 * How Patch treats the previewed pane's font, both per-font settings: what it draws with (the
 * game font, given what it lacks from the game's others; or a font drawn from a bundled
 * {@link Typeface} in its look, which Patch adds in its place), and which letters get an extra
 * pixel of space after them, the same letters the Patch dialog lists (a letter from another font
 * can crowd the next one, its outline included). The letters are saved a moment after typing
 * stops, and on Enter; the typeface at once. The fit and the preview follow.
 */
final class ExtraSpaceBar extends JPanel {

	private final FontPatcher fonts;
	private final Runnable onChange;
	private final JComboBox<Typeface> source = new JComboBox<>(Typeface.values());
	private final JTextField letters = new JTextField(10);
	private final JLabel font = new JLabel();
	/** Saves what was typed once typing pauses. */
	private final Timer pause = new Timer(400, e -> save());
	/** The font being edited, or null. */
	private String fontName;
	/** Set while the field is filled in, so that is not taken as an edit. */
	private boolean loading;

	/** {@code onChange} runs after the letters are saved, to measure and draw again. */
	ExtraSpaceBar(FontPatcher fonts, Runnable onChange) {
		super(new WrapLayout(6, 2));
		this.fonts = fonts;
		this.onChange = onChange;
		pause.setRepeats(false);
		letters.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "none");
		letters.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				edited();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				edited();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				edited();
			}
		});
		letters.addActionListener(e -> save());
		font.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		font.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		JLabel label = new JLabel("Extra space after:");
		String tip = "Letters that get one more pixel of space after them when Patch adds them to this font, "
				+ "where a borrowed letter crowds the next. Applies to this font everywhere it is used, "
				+ "and shows in the Patch list.";
		label.setToolTipText(tip);
		letters.setToolTipText(tip);
		JLabel from = new JLabel("Draw with:");
		String fromTip = "<html>Game font: Patch adds the letters this font lacks to it, copied from the game's other "
				+ "fonts.<br>A bundled typeface (SIL Open Font License): Patch leaves the game's font as it is and adds a "
				+ "new font drawn from the typeface, in this font's size and outline, beside it in every archive that "
				+ "carries it, then points the layouts at the new one. It holds what the game's font holds plus the "
				+ "English; Noto Sans has no Japanese, so that is drawn from M PLUS Rounded 1c.</html>";
		from.setToolTipText(fromTip);
		source.setToolTipText(fromTip);
		source.addActionListener(e -> sourceChosen());
		// each label with its control, so a narrow panel wraps them onto a new line together
		add(pair(from, source));
		add(pair(label, letters));
		add(font);
		show(null);
	}

	private static JPanel pair(JComponent label, JComponent control) {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		p.setOpaque(false);
		p.add(label);
		p.add(control);
		return p;
	}

	/** Saves the chosen source for the font, then measures again. */
	private void sourceChosen() {
		Typeface t = (Typeface) source.getSelectedItem();
		if (loading || fontName == null || t == null || t == fonts.settings().lettersFrom(fontName)) {
			return;
		}
		try {
			fonts.settings().setLettersFrom(fontName, t);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the font patch settings:\n" + e.getMessage(),
					"Letters from", JOptionPane.ERROR_MESSAGE);
			loading = true;
			source.setSelectedItem(fonts.settings().lettersFrom(fontName));
			loading = false;
			return;
		}
		fonts.settingsChanged();
		onChange.run();
	}

	/** Edits the letters of {@code u}'s font as drawn now; none when null. */
	void show(Usage u) {
		String name = u == null ? null : fonts.fontName(u);
		if (name != null && name.equals(fontName)) {
			return; // the same font: leave what is being typed alone
		}
		pause.stop();
		String previous = fontName;
		String typed = letters.getText();
		fontName = name;
		if (previous != null) {
			save(previous, typed);
		}
		loading = true;
		letters.setText(name == null ? "" : fonts.settings().extraSpace(name));
		source.setSelectedItem(name == null ? Typeface.GAME : fonts.settings().lettersFrom(name));
		loading = false;
		letters.setEnabled(name != null);
		source.setEnabled(name != null);
		font.setText(name == null ? "" : "in " + name.replace(".bcfnt", ""));
	}

	/** Shows the font's settings as saved, after they were changed elsewhere (the Patch list). */
	void reload() {
		pause.stop();
		loading = true;
		letters.setText(fontName == null ? "" : fonts.settings().extraSpace(fontName));
		source.setSelectedItem(fontName == null ? Typeface.GAME : fonts.settings().lettersFrom(fontName));
		loading = false;
	}

	private void edited() {
		if (!loading && fontName != null) {
			pause.restart();
		}
	}

	private void save() {
		pause.stop();
		if (fontName != null) {
			save(fontName, letters.getText());
		}
	}

	/** Saves {@code text} as {@code font}'s letters when they differ, then measures again. */
	private void save(String font, String text) {
		if (text.equals(fonts.settings().extraSpace(font))) {
			return;
		}
		try {
			fonts.settings().setExtraSpace(font, text);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not save the font patch settings:\n" + e.getMessage(),
					"Extra space", JOptionPane.ERROR_MESSAGE);
			return;
		}
		fonts.settingsChanged();
		onChange.run();
	}
}
