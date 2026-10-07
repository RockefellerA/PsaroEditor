package psaro.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import psaro.format.Bcfnt;
import psaro.render.TextRenderer;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Usage;

/**
 * The selected string drawn in a pane that shows it, the Japanese above the English, with what
 * does not fit or is missing from the pane's fonts.
 */
final class PreviewPanel extends JPanel {

	private static final double[] ZOOMS = {1, 2, 3, 4};

	/** A pane in the picker. */
	private record Choice(Usage usage) {
		@Override
		public String toString() {
			String archive = usage.archive().getFileName().toString().replace(".arc.lz", "");
			String layout = usage.layout().substring(usage.layout().lastIndexOf('/') + 1);
			var t = usage.pane().text();
			return String.format("%s › %s › %s  (%.0f×%.0f, %s)", archive, layout, usage.pane().name(),
					t.boxWidth(), t.boxHeight(), usage.fontName());
		}
	}

	private final RomfsIndex index;
	private final JComboBox<Choice> pane = new JComboBox<>();
	private final JComboBox<String> zoom = new JComboBox<>(new String[] {"1×", "2×", "3×", "4×"});
	private final JLabel japanese = new JLabel();
	private final JLabel english = new JLabel();
	private final JTextArea notes = new JTextArea(4, 40);
	private final JPanel content = new JPanel();
	private List<Usage> usages = List.of();
	private String japaneseRaw = "";
	private String englishRaw;

	PreviewPanel(RomfsIndex index) {
		super(new BorderLayout());
		this.index = index;
		zoom.setSelectedIndex(1);
		pane.addActionListener(e -> redraw());
		zoom.addActionListener(e -> redraw());

		// the pane picker takes whatever width is left, so a long pane name cannot push Zoom away
		JPanel bar = new JPanel(new BorderLayout(6, 0));
		bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		bar.add(new JLabel("Pane:"), BorderLayout.WEST);
		bar.add(pane, BorderLayout.CENTER);
		JPanel zoomBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		zoomBox.add(new JLabel("Zoom:"));
		zoomBox.add(zoom);
		bar.add(zoomBox, BorderLayout.EAST);
		pane.setPrototypeDisplayValue(null);
		pane.setMinimumSize(new Dimension(80, pane.getPreferredSize().height));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
		content.add(heading("Japanese"));
		content.add(left(japanese));
		content.add(Box.createVerticalStrut(10));
		content.add(heading("English"));
		content.add(left(english));

		// below the scrolling preview, so the warnings stay in view and wrap to the panel's width
		notes.setEditable(false);
		notes.setLineWrap(true);
		notes.setWrapStyleWord(true);
		notes.setOpaque(false);
		notes.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

		add(bar, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(content);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getHorizontalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
		add(notes, BorderLayout.SOUTH);
		clear();
	}

	/** Shows a string: the panes that use it, its Japanese, and its English (null if none). */
	void showString(List<Usage> all, String japaneseText, String englishText) {
		usages = all;
		japaneseRaw = japaneseText;
		englishRaw = englishText;
		pane.removeAllItems();
		for (Usage u : Fit.previewable(all)) {
			pane.addItem(new Choice(u));
		}
		pane.setEnabled(pane.getItemCount() > 1);
		redraw();
	}

	/** Redraws with new English, keeping the chosen pane. */
	void setEnglish(String englishText) {
		englishRaw = englishText;
		redraw();
	}

	void clear() {
		usages = List.of();
		pane.removeAllItems();
		pane.setEnabled(false);
		japanese.setIcon(null);
		japanese.setText("Select a string to preview it.");
		english.setIcon(null);
		english.setText(" ");
		notes.setText("");
	}

	private void redraw() {
		Choice choice = (Choice) pane.getSelectedItem();
		if (choice == null) {
			japanese.setIcon(null);
			english.setIcon(null);
			japanese.setText(usages.isEmpty() && japaneseRaw != null
					? "No layout shows this string: the game draws it from code, so there is no box to preview."
					: "Select a string to preview it.");
			english.setText(" ");
			notes.setText("");
			return;
		}
		Usage u = choice.usage();
		double z = ZOOMS[Math.max(zoom.getSelectedIndex(), 0)];
		Bcfnt font = Fit.font(index, u);

		TextRenderer.Result jp = TextRenderer.render(font, u.pane().text(), japaneseRaw, z);
		japanese.setText(null);
		japanese.setIcon(new ImageIcon(jp.image()));

		List<String> lines = new ArrayList<>();
		if (font == null) {
			lines.add(u.fontName() + " is not in this archive (the 3DS system font is not part of the romfs), "
					+ "so this pane is drawn with a stand-in typeface and its fit is approximate.");
		}
		if (englishRaw == null) {
			english.setIcon(null);
			english.setText("Not translated yet.");
		} else {
			TextRenderer.Result en = TextRenderer.render(font, u.pane().text(), englishRaw, z);
			english.setText(null);
			english.setIcon(new ImageIcon(en.image()));
			describeFit(Fit.judge(font, u.pane().text(), englishRaw, japaneseRaw), lines);
			describeMissing(lines);
		}
		notes.setText(lines.stream().map(s -> "• " + s).collect(Collectors.joining("\n")));
	}

	private static void describeFit(Fit.Judgement j, List<String> lines) {
		var bounds = j.english().textBounds();
		String approx = j.estimate() ? " (approximate: some characters are drawn with a stand-in)" : "";
		if (j.tooWide()) {
			lines.add(String.format("Too wide: the text is %.0f px wide and %s %.0f px%s. The game does not wrap; "
					+ "break lines with Enter.", bounds.getWidth(),
					j.widthRelaxed() ? "the original Japanese uses" : "the box is", j.limitWidth(), approx));
		}
		if (j.tooTall()) {
			lines.add(String.format("Too tall: the lines need %.0f px and %s %.0f px%s.", bounds.getHeight(),
					j.heightRelaxed() ? "the original Japanese uses" : "the box is", j.limitHeight(), approx));
		}
		if (!j.overflows()) {
			lines.add("Fits this pane" + approx + ".");
		}
		if (j.widthRelaxed() || j.heightRelaxed()) {
			lines.add("The original Japanese runs past this box and the game shows it, so the English is held "
					+ "to the Japanese's " + (j.widthRelaxed() && j.heightRelaxed() ? "size"
					: j.widthRelaxed() ? "width" : "height") + " instead.");
		}
	}

	/** Missing characters per font, over every pane including shadows. */
	private void describeMissing(List<String> lines) {
		Map<String, Set<Integer>> byFont = new LinkedHashMap<>();
		for (Usage u : usages) {
			Bcfnt font = Fit.font(index, u);
			if (font != null) {
				Set<Integer> m = TextRenderer.measure(font, u.pane().text(), englishRaw).missing();
				if (!m.isEmpty()) {
					byFont.computeIfAbsent(u.fontName(), k -> new LinkedHashSet<>()).addAll(m);
				}
			}
		}
		for (Map.Entry<String, Set<Integer>> e : byFont.entrySet()) {
			lines.add(e.getKey() + " lacks: " + Fit.describe(e.getValue())
					+ ". The game would show nothing for these; here they are drawn in a stand-in typeface, underlined in red.");
		}
	}

	private static JLabel heading(String text) {
		JLabel l = new JLabel(text);
		l.putClientProperty("FlatLaf.styleClass", "h4");
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** A row holding {@code label} at its own height, so the stack does not stretch it. */
	private static JPanel left(JLabel label) {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2)) {
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		p.add(label);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}
}
