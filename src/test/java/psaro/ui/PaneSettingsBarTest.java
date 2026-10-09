package psaro.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JSpinner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Bclyt.TextOverride;
import psaro.patch.FontPatcher;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatchSettings;
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
}
