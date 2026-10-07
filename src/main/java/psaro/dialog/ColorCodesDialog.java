package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;

import com.formdev.flatlaf.FlatClientProperties;

import psaro.project.CodeColors;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.text.ControlCodes;

/**
 * Names each of the game's color codes and matches it to the color the game shows. Lists every
 * code the romfs uses with a few of the phrases it colors, so they can be found in the game,
 * drawn in the color the preview currently uses. A code's name is its tag in the editor.
 * Changes apply at once and are saved. Not modal, so the editor stays usable beside it.
 */
public final class ColorCodesDialog extends JDialog {

	private static final Color SAMPLE_BACKGROUND = new Color(0x26, 0x26, 0x2B);
	private static final Color LIGHT_SAMPLE_BACKGROUND = new Color(0xE9, 0xE6, 0xDF);
	private static final int EXAMPLES = 3;

	/** How often a code appears, and some of the phrases it colors. */
	private record Use(int strings, Set<String> examples) {
	}

	/** One code's controls, updated in place so a change never discards a click elsewhere. */
	private record Row(int code, JTextField name, JLabel sample, JLabel hex, JButton reset) {
	}

	private final CodeColors colors;
	private final Runnable onChange;
	private final List<Row> rowList = new ArrayList<>();

	public ColorCodesDialog(Window owner, RomfsIndex index, CodeColors colors, Runnable onChange) {
		super(owner, "Color Codes", ModalityType.MODELESS);
		this.colors = colors;
		this.onChange = onChange;
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JLabel intro = new JLabel("<html>The game chooses these colors in its own code, which PsaroEditor cannot read. "
				+ "Check each against the game, using the phrases beside it to find where it appears. "
				+ "A code's name is its tag in the editor, as &lt;GREEN&gt;; a code with no name shows as {10}.</html>");
		intro.setBorder(new EmptyBorder(12, 14, 8, 14));
		JLabel saved = new JLabel("Saved to " + colors.file());
		saved.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		saved.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		saved.setBorder(new EmptyBorder(0, 14, 6, 14));
		JPanel top = new JPanel(new BorderLayout());
		top.add(intro, BorderLayout.CENTER);
		top.add(saved, BorderLayout.SOUTH);

		JPanel rows = new JPanel(new GridBagLayout());
		rows.setBorder(new EmptyBorder(4, 14, 8, 14));
		buildRows(rows, uses(index));
		refresh();
		JScrollPane scroll = new JScrollPane(rows);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(close);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
				JComponent.WHEN_IN_FOCUSED_WINDOW);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(top, BorderLayout.NORTH);
		getContentPane().add(scroll, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		setSize(960, 580);
		setLocationRelativeTo(owner);
	}

	private void buildRows(JPanel rows, Map<Integer, Use> uses) {
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;
		int y = 0;
		for (Map.Entry<Integer, Use> e : uses.entrySet()) {
			int code = e.getKey();
			c.gridy = y++;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;

			c.gridx = 0;
			JLabel token = new JLabel(String.format("{%02X}", code));
			token.putClientProperty(FlatClientProperties.STYLE, "font: bold");
			rows.add(token, c);

			c.gridx = 1;
			JTextField name = new JTextField(14);
			name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "no name");
			name.setToolTipText("The code's tag in the editor; capitals, digits and underscores. Enter to apply.");
			name.addActionListener(ev -> rename(code, name));
			name.addFocusListener(new FocusAdapter() {
				@Override
				public void focusLost(FocusEvent ev) {
					rename(code, name);
				}
			});
			rows.add(name, c);

			c.gridx = 2;
			c.weightx = 1;
			c.fill = GridBagConstraints.HORIZONTAL;
			JLabel sample = new JLabel(String.join("   ·   ", e.getValue().examples()));
			sample.setOpaque(true);
			sample.setBackground(SAMPLE_BACKGROUND);
			sample.setBorder(new EmptyBorder(4, 8, 4, 8));
			sample.setToolTipText(e.getValue().strings() + " strings use this code");
			rows.add(sample, c);

			c.gridx = 3;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;
			JLabel hex = new JLabel();
			hex.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
			rows.add(hex, c);

			c.gridx = 4;
			JButton change = new JButton("Color…");
			change.addActionListener(ev -> choose(code));
			rows.add(change, c);

			c.gridx = 5;
			JButton reset = new JButton("Reset");
			reset.setToolTipText("Back to the default name and color");
			reset.addActionListener(ev -> apply(() -> colors.reset(code)));
			rows.add(reset, c);

			rowList.add(new Row(code, name, sample, hex, reset));
		}
		// keeps the rows at the top when there are few of them
		c.gridy = y;
		c.weighty = 1;
		rows.add(new JLabel(), c);
	}

	/** Shows each code's current name and color. */
	private void refresh() {
		for (Row r : rowList) {
			Color color = colors.color(r.code());
			String name = colors.name(r.code());
			if (!r.name().hasFocus() || !Objects.equals(r.name().getText(), name)) {
				r.name().setText(name == null ? "" : name);
			}
			Color shown = color == null ? Color.WHITE : color;
			r.sample().setForeground(shown);
			// dark colors such as BLACK_TUTORIAL go on a light background, so they stay readable
			boolean dark = (0.299 * shown.getRed() + 0.587 * shown.getGreen() + 0.114 * shown.getBlue()) / 255 < 0.45;
			r.sample().setBackground(dark ? LIGHT_SAMPLE_BACKGROUND : SAMPLE_BACKGROUND);
			r.hex().setText(color == null ? "pane's color" : String.format("#%06X", color.getRGB() & 0xFFFFFF));
			r.reset().setEnabled(colors.isChanged(r.code()));
		}
	}

	private void rename(int code, JTextField field) {
		String typed = field.getText().strip().toUpperCase();
		String current = colors.name(code);
		if (typed.equals(current == null ? "" : current)) {
			field.setText(typed);
			return;
		}
		try {
			colors.setName(code, typed);
		} catch (IllegalArgumentException e) {
			JOptionPane.showMessageDialog(this, e.getMessage(), getTitle(), JOptionPane.WARNING_MESSAGE);
		} catch (IOException e) {
			saveFailed(e);
		}
		refresh();
		onChange.run();
	}

	private void choose(int code) {
		Color current = colors.color(code);
		Color picked = JColorChooser.showDialog(this, String.format("Color for {%02X}", code),
				current == null ? Color.WHITE : current);
		if (picked != null) {
			apply(() -> colors.setColor(code, picked));
		}
	}

	private interface Change {
		void run() throws IOException;
	}

	private void apply(Change change) {
		try {
			change.run();
		} catch (IOException e) {
			saveFailed(e);
		}
		refresh();
		onChange.run();
	}

	private void saveFailed(IOException e) {
		JOptionPane.showMessageDialog(this, "Could not save to " + colors.file() + ":\n" + e.getMessage(),
				getTitle(), JOptionPane.ERROR_MESSAGE);
	}

	/** Every code the romfs uses, with how many strings use it and the first phrases it colors. */
	private static Map<Integer, Use> uses(RomfsIndex index) {
		Map<Integer, Integer> counts = new TreeMap<>();
		Map<Integer, Set<String>> examples = new TreeMap<>();
		for (StringTable table : index.tables()) {
			for (String raw : table.strings().values()) {
				Set<Integer> seen = new LinkedHashSet<>();
				for (int i = 0; i + 1 < raw.length(); i++) {
					if (raw.charAt(i) != ControlCodes.COLOUR) {
						continue;
					}
					int code = raw.charAt(++i);
					seen.add(code);
					String phrase = phraseAfter(raw, i + 1);
					Set<String> some = examples.computeIfAbsent(code, k -> new LinkedHashSet<>());
					if (!phrase.isEmpty() && some.size() < EXAMPLES) {
						some.add(phrase);
					}
				}
				seen.forEach(code -> counts.merge(code, 1, Integer::sum));
			}
		}
		Map<Integer, Use> out = new TreeMap<>();
		counts.forEach((code, n) -> out.put(code, new Use(n, examples.getOrDefault(code, Set.of()))));
		return out;
	}

	/** The text from {@code from} to the next color code, on one line and kept short. */
	private static String phraseAfter(String raw, int from) {
		int end = raw.indexOf(ControlCodes.COLOUR, from);
		String phrase = raw.substring(from, end < 0 ? raw.length() : end).replace("\n", "").strip();
		return phrase.length() > 16 ? phrase.substring(0, 16) + "…" : phrase;
	}
}
