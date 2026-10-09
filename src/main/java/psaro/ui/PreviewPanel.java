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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import psaro.dialog.ColorCodesDialog;
import psaro.format.Bcfnt;
import psaro.patch.FontPatcher;
import psaro.patch.FreeFont;
import psaro.patch.Typeface;
import psaro.project.CodeColors;
import psaro.render.TextRenderer;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Usage;

/**
 * The selected string drawn in a pane that shows it, the Japanese above the English, with what
 * does not fit or is missing from the pane's fonts.
 */
final class PreviewPanel extends JPanel {

	private static final double[] ZOOMS = {1, 2, 3, 4};

	/** A pane in the picker, with its box as changed. */
	private final class Choice {
		private final Usage usage;

		Choice(Usage usage) {
			this.usage = usage;
		}

		Usage usage() {
			return usage;
		}

		/** Marked when the English overflows this pane, so the picker shows which panes need work. */
		@Override
		public String toString() {
			var t = fonts.text(usage);
			return String.format("%s%s  (%.0f×%.0f, %s)", overflowing.contains(usage) ? "⚠ " : "", name(usage),
					t.boxWidth(), t.boxHeight(), t.font());
		}
	}

	private final RomfsIndex index;
	private final FontPatcher fonts;
	private final CodeColors colors;
	private final JComboBox<Choice> pane = new JComboBox<>();
	private final JComboBox<String> zoom = new JComboBox<>(new String[] {"1×", "2×", "3×", "4×"});
	private final JLabel japanese = new JLabel();
	private final JLabel english = new JLabel();
	private final JLabel japaneseHeading = heading("Japanese");
	private final JLabel englishHeading = heading("English");
	private final JTextArea notes = new JTextArea(4, 40);
	private final JPanel content = new JPanel();
	private final PaneSettingsBar settings;
	private List<Usage> usages = List.of();
	/** The previewable panes the English does not fit, as the Fits column counts them. */
	private Set<Usage> overflowing = Set.of();
	private String japaneseRaw = "";
	private String englishRaw;
	/** The table whose copy of the key the panes show, when they are not matched to this one's. */
	private String sharedFrom;

	/** Told the pane drawn after each redraw, null when none is. */
	private Consumer<Usage> onPaneShown = u -> { };

	/** Run after a color code is renamed or recolored, so the editor can show the new tags. */
	private final Runnable onCodesChanged;
	private ColorCodesDialog colorDialog;

	/**
	 * {@code onCodesChanged} runs after a color code is renamed or recolored; {@code onLayoutChanged}
	 * after a pane's box or type settings change, so the editor can measure every string again.
	 */
	PreviewPanel(FontPatcher fonts, CodeColors colors, Runnable onCodesChanged, Runnable onLayoutChanged) {
		super(new BorderLayout());
		this.index = fonts.index();
		this.fonts = fonts;
		this.settings = new PaneSettingsBar(fonts, () -> {
			measure();
			pane.repaint();
			redraw();
			onLayoutChanged.run();
		});
		this.colors = colors;
		this.onCodesChanged = onCodesChanged;
		zoom.setSelectedIndex(1);
		pane.addActionListener(e -> redraw());
		zoom.addActionListener(e -> redraw());
		JButton colorButton = new JButton("Colors…");
		colorButton.setToolTipText("Match the game's color codes to the colors it shows");
		colorButton.addActionListener(e -> showColors());

		// the pane picker takes whatever width is left, so a long pane name cannot push Zoom away
		JPanel bar = new JPanel(new BorderLayout(6, 0));
		bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		bar.add(new JLabel("Pane:"), BorderLayout.WEST);
		bar.add(pane, BorderLayout.CENTER);
		JPanel zoomBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		zoomBox.add(new JLabel("Zoom:"));
		zoomBox.add(zoom);
		zoomBox.add(colorButton);
		bar.add(zoomBox, BorderLayout.EAST);
		pane.setPrototypeDisplayValue(null);
		pane.setMinimumSize(new Dimension(80, pane.getPreferredSize().height));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
		content.add(japaneseHeading);
		content.add(left(japanese));
		content.add(Box.createVerticalStrut(10));
		content.add(englishHeading);
		content.add(left(english));

		// below the scrolling preview, so the warnings stay in view and wrap to the panel's width
		notes.setEditable(false);
		notes.setLineWrap(true);
		notes.setWrapStyleWord(true);
		notes.setOpaque(false);
		notes.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

		JPanel top = new JPanel(new BorderLayout());
		top.add(bar, BorderLayout.NORTH);
		top.add(settings, BorderLayout.CENTER);
		add(top, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(content);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getHorizontalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
		add(notes, BorderLayout.SOUTH);
		clear();
	}

	/**
	 * Shows a string: the panes that use it, its Japanese, and its English (null if none).
	 * {@code sharedFrom} names the table whose copy of the same key those panes are matched to,
	 * when none is matched to this one's; else null. The first pane the English does not fit is
	 * chosen, so the preview shows what the Fits column flags.
	 */
	void showString(List<Usage> all, String japaneseText, String englishText, String sharedFrom) {
		usages = all;
		this.sharedFrom = sharedFrom;
		japaneseRaw = japaneseText;
		englishRaw = englishText;
		measure();
		pane.removeAllItems();
		Choice first = null;
		for (Usage u : Fit.previewable(all)) {
			Choice c = new Choice(u);
			pane.addItem(c);
			if (first == null && overflowing.contains(u)) {
				first = c;
			}
		}
		if (first != null) {
			pane.setSelectedItem(first);
		}
		pane.setEnabled(pane.getItemCount() > 1);
		redraw();
	}

	/** Tells {@code listener} the pane drawn after each redraw, so it can follow the chosen pane's font. */
	void onPaneShown(Consumer<Usage> listener) {
		onPaneShown = listener;
	}

	/** Measures and draws again, keeping the chosen pane, after the fonts' settings change. */
	void remeasure() {
		measure();
		pane.repaint();
		redraw();
	}

	/** Redraws with new English, keeping the chosen pane. */
	void setEnglish(String englishText) {
		englishRaw = englishText;
		measure();
		pane.repaint();
		redraw();
	}

	/** Works out which panes the English does not fit. */
	private void measure() {
		Set<Usage> out = new LinkedHashSet<>();
		if (englishRaw != null) {
			for (Usage u : Fit.previewable(usages)) {
				if (Fit.judge(fonts, u, englishRaw, japaneseRaw).overflows()) {
					out.add(u);
				}
			}
		}
		overflowing = out;
	}

	/** Opens the color mapping, or brings it forward if it is already open. */
	private void showColors() {
		if (colorDialog == null || !colorDialog.isDisplayable()) {
			colorDialog = new ColorCodesDialog(SwingUtilities.getWindowAncestor(this), index, colors, onCodesChanged);
		}
		colorDialog.setVisible(true);
		colorDialog.toFront();
	}

	void clear() {
		usages = List.of();
		sharedFrom = null;
		overflowing = Set.of();
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
			settings.clear();
			onPaneShown.accept(null);
			return;
		}
		Usage u = choice.usage();
		settings.show(u, usages);
		double z = ZOOMS[Math.max(zoom.getSelectedIndex(), 0)];
		fonts.prepare(u, englishRaw);
		Bcfnt font = fonts.current(u);

		var donors = Fit.donors(fonts, u);
		// a changed pane: the Japanese as the game ships it, the English with the changes, to compare
		boolean changed = Fit.changed(fonts, u);
		TextRenderer.Result jp = changed
				? TextRenderer.render(Fit.font(index, u), List::of, u.pane().text(), japaneseRaw, z, colors.palette())
				: TextRenderer.render(font, donors, fonts.text(u), japaneseRaw, z, colors.palette());
		japaneseHeading.setText(changed ? "Japanese — as the game ships it" : "Japanese");
		englishHeading.setText(changed ? "English — with this pane's changes" : "English");
		japanese.setText(null);
		japanese.setIcon(new ImageIcon(jp.image()));

		List<String> lines = new ArrayList<>();
		if (sharedFrom != null) {
			lines.add("No layout is matched to this table's copy of the key; these are the boxes that show the same "
					+ "key from " + sharedFrom + ". The game may read either copy, so Patch gives these fonts the "
					+ "characters of both.");
		}
		Typeface face = fonts.drawnWith(u);
		if (face.bundled() && font != null) {
			boolean ownChoice = fonts.overrides().get(u.layout(), u.pane().name()).drawWith() != null;
			lines.add("Drawn from " + face.label() + (ownChoice ? " for this pane" : " for every pane in " + fonts.fontName(u))
					+ ": Patch adds " + FreeFont.name(fonts.fontName(u), face) + " beside " + fonts.fontName(u)
					+ " and points " + (ownChoice ? "this pane" : "the layouts") + " at it, leaving the game's font as it is.");
		}
		if (font == null) {
			lines.add(fonts.fontName(u) + " is not in this archive (the 3DS system font is not part of the romfs), "
					+ "so this pane is drawn with a stand-in typeface and its fit is approximate.");
		}
		if (englishRaw == null) {
			english.setIcon(null);
			english.setText("Not translated yet.");
		} else {
			TextRenderer.Result en = TextRenderer.render(font, donors, fonts.text(u), englishRaw, z, colors.palette());
			english.setText(null);
			english.setIcon(new ImageIcon(en.image()));
			describeFit(Fit.judge(fonts, u, englishRaw, japaneseRaw), lines);
			describeOthers(u, lines);
			describeMissing(lines);
		}
		notes.setText(lines.stream().map(s -> "• " + s).collect(Collectors.joining("\n")));
		onPaneShown.accept(u);
	}

	private static void describeFit(Fit.Judgement j, List<String> lines) {
		var bounds = j.english().textBounds();
		String approx = j.estimate() ? " (approximate: some characters are drawn with a stand-in typeface)" : "";
		if (j.tooWide()) {
			List<String> breaks = j.english().breaks();
			lines.add(String.format("Too wide: the box is %.0f px across, so the game breaks %s, mid-word if need be%s. "
					+ "Shorten it, or break the line yourself with Enter.", j.boxWidth(),
					breaks.isEmpty() ? "the line" : breaks.stream().map(b -> "after “" + b.strip() + "”")
							.collect(Collectors.joining(" and ")), approx));
		}
		if (j.tooTall()) {
			lines.add(String.format("Too tall: the lines need %.0f px and %s %.0f px%s.", bounds.getHeight(),
					limitSource(j.heightBy()), j.limitHeight(), approx));
		}
		if (!j.overflows()) {
			lines.add("Fits this pane" + approx + ".");
		}
		if (j.heightBy() == Fit.Limit.JAPANESE) {
			lines.add("The original Japanese runs below this box and the game shows it, so the English is held to "
					+ "the Japanese's height instead.");
		} else if (j.heightBy() == Fit.Limit.SAME_SHAPE) {
			lines.add("Japanese in another pane of this size and font runs below its box and the game shows it, so "
					+ "the English is held to that height instead.");
		}
	}

	/** The other panes showing this string that the English does not fit. */
	private void describeOthers(Usage current, List<String> lines) {
		List<String> others = overflowing.stream().filter(o -> !o.equals(current)).map(PreviewPanel::name).toList();
		if (others.isEmpty()) {
			return;
		}
		int shown = Math.min(others.size(), 4);
		String list = String.join(", ", others.subList(0, shown))
				+ (others.size() > shown ? " and " + (others.size() - shown) + " more" : "");
		lines.add(String.format("Does not fit %d other pane%s showing this string (marked ⚠ under Pane): %s.",
				others.size(), others.size() == 1 ? "" : "s", list));
	}

	/** A pane as "archive › layout › pane". */
	private static String name(Usage u) {
		String archive = u.archive().getFileName().toString().replace(".arc.lz", "");
		String layout = u.layout().substring(u.layout().lastIndexOf('/') + 1);
		return archive + " › " + layout + " › " + u.pane().name();
	}

	private static String limitSource(Fit.Limit by) {
		return switch (by) {
			case BOX -> "the box is";
			case JAPANESE -> "the original Japanese uses";
			case SAME_SHAPE -> "Japanese in a pane of the same size uses";
		};
	}

	/** Missing characters per font, over every pane including shadows. */
	private void describeMissing(List<String> lines) {
		Map<String, Set<Integer>> byFont = new LinkedHashMap<>();
		Map<String, Set<Integer>> noDonor = new LinkedHashMap<>();
		for (Usage u : usages) {
			fonts.prepare(u, englishRaw);
			Bcfnt font = fonts.current(u);
			if (font != null) {
				TextRenderer.Result r = TextRenderer.measure(font, Fit.donors(fonts, u), fonts.text(u), englishRaw);
				if (!r.missing().isEmpty()) {
					byFont.computeIfAbsent(fonts.fontName(u), k -> new LinkedHashSet<>()).addAll(r.missing());
				}
				if (!r.standIn().isEmpty()) {
					noDonor.computeIfAbsent(fonts.fontName(u), k -> new LinkedHashSet<>()).addAll(r.standIn());
				}
			}
		}
		for (Map.Entry<String, Set<Integer>> e : byFont.entrySet()) {
			Set<Integer> none = noDonor.getOrDefault(e.getKey(), Set.of());
			lines.add(e.getKey() + " lacks: " + Fit.describe(e.getValue()) + ". The game shows nothing for these until "
					+ "Patch adds them; here they are underlined in red and drawn as the patch would add them."
					+ (none.isEmpty() ? "" : " No font in the romfs has " + Fit.describe(none)
							+ ", so the patch cannot add it; it is drawn in a stand-in typeface."));
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
