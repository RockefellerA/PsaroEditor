package psaro.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Bclyt.TextOverride;
import psaro.patch.FontPatcher;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatchSettings;
import psaro.patch.Typeface;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Usage;
import psaro.romfs.SampleRomfs;

class PaneSettingsBarTest {

	@TempDir
	Path dir;

	private static <T extends Component> List<T> all(Container c, Class<T> type, List<T> out) {
		for (Component k : c.getComponents()) {
			if (type.isInstance(k)) {
				out.add(type.cast(k));
			} else if (k instanceof Container cc) {
				all(cc, type, out);
			}
		}
		return out;
	}

	@Test
	void aChangedValueIsSavedForThePaneAndResetUndoesIt() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Yes", "menu_0001"), SampleRomfs.pane("Txt_Yes_Shad", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		int[] changes = {0};
		PaneSettingsBar bar = new PaneSettingsBar(fonts, () -> changes[0]++);
		List<Usage> usages = index.usages(index.table("menu"), "menu_0001");

		bar.show(usages.get(0), usages);
		List<JSpinner> spinners = all(bar, JSpinner.class, new ArrayList<>());
		assertEquals(6, spinners.size());
		assertEquals(120.0, spinners.get(0).getValue());
		assertEquals(16.0, spinners.get(2).getValue());
		assertEquals(0, changes[0], "showing a pane is not a change");

		spinners.get(0).setValue(140.0);
		assertEquals(1, changes[0]);
		TextOverride t = fonts.overrides().get("blyt/menu.bclyt", "Txt_Yes");
		assertEquals(140f, t.boxWidth());
		assertEquals(null, t.fontSizeX());
		// the drop-shadow twin follows
		assertEquals(t, fonts.overrides().get("blyt/menu.bclyt", "Txt_Yes_Shad"));

		all(bar, JButton.class, new ArrayList<>()).stream().filter(b -> b.getText().equals("Reset")).findFirst()
				.orElseThrow().doClick();
		assertTrue(fonts.overrides().layouts().isEmpty());
		assertEquals(120.0, spinners.get(0).getValue());
	}

	@Test
	void panesLayeredIntoOneTextTakeItsChangesButNotACopyElsewhere() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "なまえ"));
		// a drop shadow 1.5 below, a fill, and an outline in a font of its own, as title_naming_up's title
		byte[] titl01 = SampleRomfs.pane("Txt_Titl_01", "menu_0001", 0, 40);
		titl01[0x52] = 1;
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt", "b.bcfnt"), Map.of(),
				SampleRomfs.group("NL_Wind", SampleRomfs.pane("Txt_Titl_03", "menu_0001", 0, 38.5f),
						SampleRomfs.pane("Txt_Titl_02", "menu_0001", 0, 40), titl01),
				SampleRomfs.pane("Txt_Elsewhere", "menu_0001", 0, -60));
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		PaneSettingsBar bar = new PaneSettingsBar(fonts, () -> { });
		List<Usage> usages = index.usages(index.table("menu"), "menu_0001");
		// of the stack, the pane drawn on top is previewed
		assertEquals(List.of("Txt_Titl_01", "Txt_Elsewhere"), Fit.previewable(usages).stream().map(u -> u.pane().name()).toList());

		bar.show(usages.get(0), usages);
		@SuppressWarnings("unchecked")
		JComboBox<String> font = all(bar, JComboBox.class, new ArrayList<>()).get(0);
		font.setSelectedItem("b.bcfnt");
		all(bar, JSpinner.class, new ArrayList<>()).get(4).setValue(0.5);
		assertEquals(new TextOverride(null, null, null, null, 0.5f, null, "b.bcfnt"), fonts.overrides().get("blyt/menu.bclyt", "Txt_Titl_03"));
		assertEquals(new TextOverride(null, null, null, null, 0.5f, null, "b.bcfnt"), fonts.overrides().get("blyt/menu.bclyt", "Txt_Titl_02"));
		assertEquals(new TextOverride(null, null, null, null, 0.5f, null), fonts.overrides().get("blyt/menu.bclyt", "Txt_Titl_01"),
				"the outline keeps its own font");
		assertTrue(fonts.overrides().get("blyt/menu.bclyt", "Txt_Elsewhere").isEmpty());

		// what the text draws with every layer takes, the outline in its own font too, or it would show nothing
		JComboBox<?> drawWith = all(bar, JComboBox.class, new ArrayList<>()).get(1);
		drawWith.setSelectedIndex(2); // M PLUS Rounded 1c
		assertEquals("m-plus-rounded-1c", fonts.overrides().get("blyt/menu.bclyt", "Txt_Titl_02").drawWith());
		assertEquals("m-plus-rounded-1c", fonts.overrides().get("blyt/menu.bclyt", "Txt_Titl_01").drawWith());
		assertEquals(null, fonts.overrides().get("blyt/menu.bclyt", "Txt_Titl_01").font(), "and keeps its font");
	}

	@Test
	void anotherOfTheLayoutsFontsCanBeChosen() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt", "b.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Yes", "menu_0001"), SampleRomfs.pane("Txt_Yes_Shad", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		int[] changes = {0};
		PaneSettingsBar bar = new PaneSettingsBar(fonts, () -> changes[0]++);
		List<Usage> usages = index.usages(index.table("menu"), "menu_0001");

		bar.show(usages.get(0), usages);
		@SuppressWarnings("unchecked")
		JComboBox<String> font = all(bar, JComboBox.class, new ArrayList<>()).get(0);
		assertEquals("a.bcfnt", font.getSelectedItem());
		assertTrue(font.isEnabled());
		assertEquals(0, changes[0], "showing a pane is not a change");

		font.setSelectedItem("b.bcfnt");
		assertEquals(1, changes[0]);
		assertEquals("b.bcfnt", fonts.overrides().get("blyt/menu.bclyt", "Txt_Yes").font());
		assertEquals("b.bcfnt", fonts.fontName(usages.get(0)));
		// the twin drew with the same font, so it follows
		assertEquals("b.bcfnt", fonts.overrides().get("blyt/menu.bclyt", "Txt_Yes_Shad").font());

		font.setSelectedItem("a.bcfnt");
		assertTrue(fonts.overrides().layouts().isEmpty());
	}

	@Test
	void aPaneCanDrawWithATypefaceOfItsOwn() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"),
				Map.of("a.bcfnt", SampleRomfs.font("はい", 10)), SampleRomfs.pane("Txt_Yes", "menu_0001"),
				SampleRomfs.pane("Txt_Yes_Shad", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		PaneSettingsBar bar = new PaneSettingsBar(fonts, () -> { });
		List<Usage> usages = index.usages(index.table("menu"), "menu_0001");
		bar.show(usages.get(0), usages);
		@SuppressWarnings("unchecked")
		List<JComboBox<String>> combos = (List<JComboBox<String>>) (List<?>) all(bar, JComboBox.class, new ArrayList<>());
		JComboBox<String> drawWith = combos.get(1);
		assertEquals(0, drawWith.getSelectedIndex(), "the font's own setting to begin with");
		assertTrue(drawWith.getItemAt(0).contains("Game font"));

		drawWith.setSelectedIndex(2); // M PLUS Rounded 1c for this pane
		assertEquals("m-plus-rounded-1c", fonts.overrides().get("blyt/menu.bclyt", "Txt_Yes").drawWith());
		assertEquals("m-plus-rounded-1c", fonts.overrides().get("blyt/menu.bclyt", "Txt_Yes_Shad").drawWith(), "the shadow in the same font follows");
		assertEquals(psaro.patch.Typeface.M_PLUS_ROUNDED, fonts.drawnWith(usages.get(0)));
		assertEquals("m-plus-rounded-1c", LayoutOverrides.open(romfs).get("blyt/menu.bclyt", "Txt_Yes").drawWith(), "saved");

		drawWith.setSelectedIndex(0);
		assertTrue(fonts.overrides().layouts().isEmpty());
	}

	@Test
	void theFontsSettingsAreSavedForTheFontAndFollowThePanesFont() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt", "b.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Yes", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		int[] changes = {0};
		PaneSettingsBar bar = new PaneSettingsBar(fonts, () -> changes[0]++);
		JTextField field = all(bar, JTextField.class, new ArrayList<>()).stream()
				.filter(f -> SwingUtilities.getAncestorOfClass(JSpinner.class, f) == null).findFirst().orElseThrow();
		@SuppressWarnings("unchecked")
		List<JComboBox<?>> combos = (List<JComboBox<?>>) (List<?>) all(bar, JComboBox.class, new ArrayList<>());
		JComboBox<?> font = combos.get(0);
		JComboBox<?> drawWith = combos.get(1);
		JComboBox<?> everyPane = combos.get(2);
		List<Usage> usages = index.usages(index.table("menu"), "menu_0001");

		assertFalse(field.isEnabled(), "no pane, nothing to edit");
		bar.show(usages.get(0), usages);
		assertTrue(field.isEnabled());
		assertTrue(labels(bar).contains("Every pane in a draws with:"));
		assertEquals(0, changes[0], "showing a font is not a change");

		field.setText("ty");
		field.postActionEvent();
		assertEquals("ty", fonts.settings().extraSpace("a.bcfnt"));
		assertEquals(1, changes[0]);
		assertEquals("ty", PatchSettings.open(romfs).extraSpace("a.bcfnt"), "saved for the Patch list");

		assertEquals(Typeface.GAME, everyPane.getSelectedItem());
		everyPane.setSelectedItem(Typeface.M_PLUS_ROUNDED);
		assertEquals(Typeface.M_PLUS_ROUNDED, PatchSettings.open(romfs).lettersFrom("a.bcfnt"));
		assertEquals(2, changes[0]);
		assertEquals("Same as a (M PLUS Rounded 1c)", drawWith.getItemAt(0), "the pane's default names the font's setting");
		assertTrue(fonts.overrides().layouts().isEmpty(), "a font setting, not the pane's");

		// the pane switched to the layout's other font: the row edits that one, saving what was typed first
		field.setText("tyl");
		font.setSelectedItem("b.bcfnt");
		assertEquals("tyl", fonts.settings().extraSpace("a.bcfnt"));
		assertEquals("", field.getText());
		assertEquals(Typeface.GAME, everyPane.getSelectedItem(), "b's own setting");
		assertTrue(labels(bar).contains("Every pane in b draws with:"));
		assertEquals("Same as b (Game font)", drawWith.getItemAt(0));

		// changed in the Patch list: the bar shows it
		fonts.settings().setExtraSpace("b.bcfnt", "r");
		bar.reload();
		assertEquals("r", field.getText());
	}

	/** In a panel too narrow for a row on one line, the bar wraps: everything stays inside it, the letters box too. */
	@Test
	void aNarrowBarWrapsInsteadOfCuttingOffTheLettersBox() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Yes", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		PaneSettingsBar bar = new PaneSettingsBar(new FontPatcher(index, PatchSettings.open(romfs),
				LayoutOverrides.open(romfs)), () -> { });
		List<Usage> usages = index.usages(index.table("menu"), "menu_0001");
		bar.show(usages.get(0), usages);
		JPanel holder = new JPanel(new BorderLayout());
		holder.add(bar, BorderLayout.NORTH);
		int unwrapped = bar.getPreferredSize().height;
		// room for the widest label and its controls but not for every row on one line, whatever the platform's font
		Container[] rows = Arrays.stream(bar.getComponents()).map(Container.class::cast).toArray(Container[]::new);
		int widest = Arrays.stream(rows).flatMap(r -> Arrays.stream(r.getComponents()))
				.mapToInt(c -> c.getPreferredSize().width).max().orElseThrow();
		int width = widest + 2 * ((WrapLayout) rows[0].getLayout()).getHgap() + bar.getInsets().left + bar.getInsets().right;
		assertTrue(width < bar.getPreferredSize().width, "too narrow for one line");
		holder.setSize(width, 600);
		// validate() lays out only what is on screen; lay the tree out as it would be
		layOut(holder);
		JTextField field = all(bar, JTextField.class, new ArrayList<>()).stream()
				.filter(f -> SwingUtilities.getAncestorOfClass(JSpinner.class, f) == null).findFirst().orElseThrow();
		Rectangle inBar = SwingUtilities.convertRectangle(field.getParent(), field.getBounds(), bar);
		assertTrue(bar.getHeight() > unwrapped, "wrapped to more lines");
		assertTrue(inBar.x >= 0 && inBar.getMaxX() <= bar.getWidth() && inBar.getMaxY() <= bar.getHeight(),
				"the letters box " + inBar + " lies inside the bar " + bar.getSize());
	}

	private static void layOut(Container c) {
		c.doLayout();
		for (Component k : c.getComponents()) {
			if (k instanceof Container cc) {
				layOut(cc);
			}
		}
	}

	private static List<String> labels(Container c) {
		return all(c, JLabel.class, new ArrayList<>()).stream().map(JLabel::getText).toList();
	}
}
