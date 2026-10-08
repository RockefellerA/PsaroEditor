package psaro.ui;

import java.awt.FlowLayout;
import java.io.IOException;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import com.formdev.flatlaf.FlatClientProperties;
import psaro.patch.FontPatcher;
import psaro.romfs.RomfsIndex.Usage;

/**
 * The letters that get an extra pixel of space after them in the previewed pane's font, the same
 * per-font setting the Patch dialog lists: a letter borrowed from another font can crowd the next
 * one, its outline included. Saved a moment after typing stops, and on Enter; the fit and the
 * preview follow at once.
 */
final class ExtraSpaceBar extends JPanel {

	private final FontPatcher fonts;
	private final Runnable onChange;
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
		super(new FlowLayout(FlowLayout.LEFT, 6, 2));
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
		add(label);
		add(letters);
		add(font);
		show(null);
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
		loading = false;
		letters.setEnabled(name != null);
		font.setText(name == null ? "" : "in " + name.replace(".bcfnt", ""));
	}

	/** Shows the font's letters as saved, after they were changed elsewhere (the Patch list). */
	void reload() {
		pause.stop();
		loading = true;
		letters.setText(fontName == null ? "" : fonts.settings().extraSpace(fontName));
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
