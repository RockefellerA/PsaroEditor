package psaro.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

import com.formdev.flatlaf.FlatClientProperties;

import psaro.format.Bclyt.TextOverride;
import psaro.patch.FontPatcher;
import psaro.patch.FontPatcher.FontChange;
import psaro.patch.FontPatcher.LayoutChange;
import psaro.patch.FontPatcher.Plan;
import psaro.patch.FontPatcher.Text;
import psaro.patch.Lending;
import psaro.patch.PatchSettings;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Donor;
import psaro.ui.Fit;

/**
 * Patch: lists what each archive's fonts need for the English and which layouts are changed,
 * what a patch would add or drop against the archives last written, and writes them. Its
 * settings are saved as they change.
 */
public final class FontPatchDialog extends JDialog {

	private final FontPatcher patcher;
	private final Supplier<List<Text>> texts;
	private final Runnable onFontsChanged;
	private final PlanModel model = new PlanModel();
	private final JLabel status = new JLabel(" ");
	private final JButton patch = new JButton("Patch");
	private final JButton close = new JButton("Close");
	private final JCheckBox copy = new JCheckBox("Also copy the patched archives to a mods folder for testing:");
	private final JLabel modsLabel = new JLabel();
	private Plan plan;
	private boolean busy;

	/**
	 * {@code texts} gives every string with English as the game would show it, read when needed;
	 * {@code onFontsChanged} runs after a patch or a settings change, so the editor and the
	 * window can measure again.
	 */
	public FontPatchDialog(Frame owner, FontPatcher patcher, Supplier<List<Text>> texts, Runnable onFontsChanged) {
		super(owner, "Patch", true);
		this.patcher = patcher;
		this.texts = texts;
		this.onFontsChanged = onFontsChanged;
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		PatchSettings settings = patcher.settings();

		JLabel intro = new JLabel("<html>Adds the characters your English uses to each archive's fonts, copying the "
				+ "glyphs from another copy or size of the same font, drops added characters the English no longer "
				+ "uses, and writes the box and type changes made under the preview into every archive with that "
				+ "layout. Patched archives are written to " + patcher.output() + "; the romfs is never changed.</html>");
		intro.setBorder(new EmptyBorder(12, 14, 8, 14));
		intro.setPreferredSize(new Dimension(760, intro.getPreferredSize().height * 3));

		JTable table = new JTable(model);
		table.setFillsViewportHeight(true);
		// a typed value is kept when the cell loses focus, not only on Enter
		table.putClientProperty("terminateEditOnFocusLost", true);
		int[] widths = {160, 140, 90, 130, 115, 70, 200};
		for (int i = 0; i < widths.length; i++) {
			table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		JScrollPane scroll = new JScrollPane(table);
		scroll.setPreferredSize(new Dimension(900, 300));

		// ── Settings ─────────────────────────────────────────────────────────
		copy.setSelected(settings.copyToMods() && settings.modsFolder() != null);
		copy.addActionListener(e -> {
			Path folder = settings.modsFolder();
			if (copy.isSelected() && folder == null) {
				folder = chooseMods(null);
			}
			saveMods(copy.isSelected() && folder != null, folder);
		});
		JButton chooseMods = new JButton("Choose…");
		chooseMods.addActionListener(e -> {
			Path folder = chooseMods(settings.modsFolder());
			if (folder != null) {
				saveMods(true, folder);
			}
		});
		modsLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		JPanel modsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		modsRow.add(copy);
		modsRow.add(modsLabel);
		modsRow.add(chooseMods);
		JLabel modsNote = new JLabel("The mods folder is only written to.");
		modsNote.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		modsNote.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
		modsNote.setBorder(new EmptyBorder(0, 10, 0, 0));
		showMods();

		JPanel settingsBox = new JPanel();
		settingsBox.setLayout(new BoxLayout(settingsBox, BoxLayout.Y_AXIS));
		settingsBox.setBorder(new EmptyBorder(8, 8, 0, 8));
		for (JComponent c : new JComponent[] {modsRow, modsNote}) {
			c.setAlignmentX(LEFT_ALIGNMENT);
			settingsBox.add(c);
		}

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(new EmptyBorder(0, 14, 0, 14));
		content.add(scroll, BorderLayout.CENTER);
		content.add(settingsBox, BorderLayout.SOUTH);

		// ── Buttons ──────────────────────────────────────────────────────────
		patch.setEnabled(false);
		patch.addActionListener(e -> runPatch());
		close.addActionListener(e -> dispose());
		JPanel buttons = new JPanel(new BorderLayout());
		buttons.setBorder(BorderFactory.createEmptyBorder(8, 14, 10, 10));
		buttons.add(status, BorderLayout.CENTER);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		right.add(patch);
		right.add(close);
		buttons.add(right, BorderLayout.EAST);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(intro, BorderLayout.NORTH);
		getContentPane().add(content, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		getRootPane().registerKeyboardAction(e -> {
			if (!busy) {
				dispose();
			}
		}, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		pack();
		setLocationRelativeTo(owner);
		recompute();
	}

	/** Works out the plan again in the background. */
	private void recompute() {
		List<Text> snapshot = texts.get();
		busy = true;
		updateButtons();
		status.setText("Checking the fonts…");
		new SwingWorker<Plan, Void>() {
			@Override
			protected Plan doInBackground() {
				return patcher.plan(snapshot);
			}

			@Override
			protected void done() {
				busy = false;
				try {
					plan = get();
					model.show(plan);
					status.setText(summary(plan));
				} catch (Exception e) {
					status.setText("Could not check the fonts: " + e.getMessage());
				}
				updateButtons();
			}
		}.execute();
	}

	private void runPatch() {
		Plan current = plan;
		busy = true;
		updateButtons();
		new SwingWorker<List<Path>, String>() {
			@Override
			protected List<Path> doInBackground() throws IOException {
				return patcher.write(current, this::publish);
			}

			@Override
			protected void process(List<String> steps) {
				status.setText(steps.get(steps.size() - 1) + "…");
			}

			@Override
			protected void done() {
				busy = false;
				try {
					List<Path> wrote = get();
					onFontsChanged.run();
					recompute();
					JOptionPane.showMessageDialog(FontPatchDialog.this,
							wrote.isEmpty() ? "The patched archives are up to date."
									: "Wrote " + wrote.size() + " archive" + (wrote.size() == 1 ? "" : "s") + " to "
											+ patcher.output() + ".",
							getTitle(), JOptionPane.INFORMATION_MESSAGE);
				} catch (Exception e) {
					Throwable cause = e.getCause() != null ? e.getCause() : e;
					error("Could not patch the fonts:\n" + cause.getMessage());
					recompute();
				}
			}
		}.execute();
	}

	private void updateButtons() {
		PatchSettings s = patcher.settings();
		boolean copying = s.copyToMods() && s.modsFolder() != null;
		patch.setEnabled(!busy && plan != null && (plan.hasWork() || copying && !plan.fonts().isEmpty()));
		close.setEnabled(!busy);
	}

	/** One line on what a patch would do, for the dialog and the button's tooltip. */
	public static String summary(Plan plan) {
		if (plan.fonts().isEmpty() && plan.layouts().isEmpty()) {
			return "No font needs any glyphs, and no layout is changed.";
		}
		StringBuilder s = new StringBuilder();
		if (plan.hasWork()) {
			List<String> parts = new ArrayList<>();
			if (plan.toAdd() > 0 || plan.toRemove() > 0) {
				parts.add(plan.toAdd() + " glyph" + (plan.toAdd() == 1 ? "" : "s") + " to add, " + plan.toRemove()
						+ " to remove");
			}
			if (plan.stale() > 0) {
				parts.add(plan.stale() + " font" + (plan.stale() == 1 ? "" : "s") + " to rebuild");
			}
			if (plan.layoutsToWrite() > 0) {
				parts.add(plan.layoutsToWrite() + " layout" + (plan.layoutsToWrite() == 1 ? "" : "s") + " to write");
			}
			s.append(String.join(", ", parts)).append(".");
		} else {
			s.append("The patched files are up to date.");
		}
		Set<Integer> none = plan.unavailable();
		if (!none.isEmpty()) {
			s.append(" No font in the romfs has ").append(Fit.describe(none)).append(".");
		}
		return s.toString();
	}

	private Path chooseMods(Path current) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Mods folder");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (current != null) {
			chooser.setCurrentDirectory(current.toFile());
		}
		return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
	}

	private void saveMods(boolean on, Path folder) {
		try {
			patcher.settings().setCopyToMods(on, folder);
		} catch (IOException e) {
			error("Could not save the font patch settings:\n" + e.getMessage());
		}
		showMods();
		updateButtons();
	}

	private void showMods() {
		PatchSettings s = patcher.settings();
		copy.setSelected(s.copyToMods() && s.modsFolder() != null);
		modsLabel.setText(s.modsFolder() == null ? "(none chosen)" : s.modsFolder().toString());
	}

	private void error(String message) {
		JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * One row per font (what it gets, what changes, what cannot be had, and from where) and per
	 * changed layout (its changes, and whether they are written).
	 */
	private final class PlanModel extends AbstractTableModel {

		private static final String[] NAMES = {"Archive", "Font or layout", "Gets", "Change", "Extra space after",
				"No donor", "From"};
		/** The column whose letters can be typed in. */
		private static final int EXTRA_SPACE = 4;
		private List<FontChange> rows = List.of();
		private List<LayoutChange> layouts = List.of();

		void show(Plan plan) {
			rows = plan.fonts();
			layouts = plan.layouts();
			fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return rows.size() + layouts.size();
		}

		@Override
		public int getColumnCount() {
			return NAMES.length;
		}

		@Override
		public String getColumnName(int column) {
			return NAMES[column];
		}

		@Override
		public Object getValueAt(int row, int column) {
			if (row >= rows.size()) {
				LayoutChange l = layouts.get(row - rows.size());
				return switch (column) {
					case 0 -> patcher.index().root().relativize(l.archive()).toString().replace(".arc.lz", "");
					case 1 -> l.layout().substring(l.layout().lastIndexOf('/') + 1);
					case 2 -> l.panes().entrySet().stream().map(e -> e.getKey() + ": " + describe(e.getValue()))
							.collect(Collectors.joining("; "));
					case 3 -> !l.hasWork() ? "up to date" : l.changed() ? "write" : "put back as it was";
					default -> "";
				};
			}
			FontChange f = rows.get(row);
			return switch (column) {
				case 0 -> patcher.index().root().relativize(f.archive()).toString().replace(".arc.lz", "");
				case 1 -> f.font().replace(".bcfnt", "");
				case 2 -> f.lent().size() + (f.lent().isEmpty() ? "" : ": " + text(f.lent()));
				case 3 -> change(f);
				case EXTRA_SPACE -> patcher.settings().extraSpace(f.font());
				case 5 -> f.unavailable().isEmpty() ? "" : Fit.describe(f.unavailable());
				case 6 -> f.from().values().stream().map(d -> donorName(d, f.font()))
						.collect(Collectors.toCollection(LinkedHashSet::new)).stream().collect(Collectors.joining(", "));
				default -> "";
			};
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return column == EXTRA_SPACE && row < rows.size() && !busy;
		}

		/**
		 * New letters for a font: they apply to its every copy, so the fonts are measured and
		 * checked again.
		 */
		@Override
		public void setValueAt(Object value, int row, int column) {
			String font = rows.get(row).font();
			String chars = String.valueOf(value);
			if (chars.equals(patcher.settings().extraSpace(font))) {
				return;
			}
			try {
				patcher.settings().setExtraSpace(font, chars);
			} catch (IOException e) {
				error("Could not save the font patch settings:\n" + e.getMessage());
				return;
			}
			patcher.settingsChanged();
			onFontsChanged.run();
			recompute();
		}

		private String change(FontChange f) {
			if (!f.hasWork()) {
				return "up to date";
			}
			StringBuilder s = new StringBuilder();
			if (!f.add().isEmpty()) {
				s.append("+").append(text(f.add()));
			}
			if (!f.remove().isEmpty()) {
				s.append(s.isEmpty() ? "" : "  ").append("−").append(text(f.remove()));
			}
			if (f.stale()) {
				s.append(s.isEmpty() ? "" : "  ").append("rebuild");
			}
			return s.toString();
		}

		private String text(Set<Integer> codes) {
			StringBuilder s = new StringBuilder();
			codes.forEach(c -> s.appendCodePoint(c == ' ' ? '␣' : c));
			return s.toString();
		}
	}

	/** A layout change as "SulaPro_B_04a_18, box 40×24, letters 1". */
	private static String describe(TextOverride t) {
		List<String> parts = new ArrayList<>();
		if (t.font() != null) {
			parts.add(t.font().replace(".bcfnt", ""));
		}
		if (t.boxWidth() != null || t.boxHeight() != null) {
			parts.add("box " + number(t.boxWidth()) + "×" + number(t.boxHeight()));
		}
		if (t.fontSizeX() != null || t.fontSizeY() != null) {
			parts.add("font " + number(t.fontSizeX()) + "×" + number(t.fontSizeY()));
		}
		if (t.charSpace() != null) {
			parts.add("letters " + number(t.charSpace()));
		}
		if (t.lineSpace() != null) {
			parts.add("lines " + number(t.lineSpace()));
		}
		return String.join(", ", parts);
	}

	/** A changed value, or "–" for one left as the layout has it. */
	private static String number(Float v) {
		return v == null ? "–" : v == Math.rint(v) ? String.valueOf(v.intValue()) : Float.toString(v);
	}

	/**
	 * Where a donor is, marked when its family or style is not the font's own, so its glyphs only
	 * approximate.
	 */
	private static String donorName(Donor d, String font) {
		if (d.archive().toString().endsWith(".ttf")) {
			// drawn from a bundled typeface, not borrowed from the game
			return d.name() + " (" + d.archive() + ")";
		}
		String family = RomfsIndex.family(font);
		boolean otherFamily = family != null && !family.equals(RomfsIndex.family(d.name()));
		return d.archive().getFileName().toString().replace(".arc.lz", "") + " › " + d.name().replace(".bcfnt", "")
				+ (otherFamily ? " (other font)" : Lending.otherLook(font, d.name()) ? " (other style)" : "");
	}
}
