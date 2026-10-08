package psaro.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.swing.JLabel;
import javax.swing.JTextField;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Bclyt.TextOverride;
import psaro.patch.FontPatcher;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatchSettings;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Usage;
import psaro.romfs.SampleRomfs;

class ExtraSpaceBarTest {

	@TempDir
	Path dir;

	@Test
	void lettersTypedForThePanesFontAreSavedAndFollowItsFont() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt", "b.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Yes", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		int[] changes = {0};
		ExtraSpaceBar bar = new ExtraSpaceBar(fonts, () -> changes[0]++);
		JTextField field = (JTextField) Arrays.stream(bar.getComponents()).filter(c -> c instanceof JTextField)
				.findFirst().orElseThrow();
		Usage u = index.usages(index.table("menu"), "menu_0001").get(0);

		assertFalse(field.isEnabled(), "no pane, nothing to edit");
		bar.show(u);
		assertTrue(field.isEnabled());
		assertTrue(label(bar).endsWith("a"));
		assertEquals(0, changes[0], "showing a font is not a change");

		field.setText("ty");
		field.postActionEvent();
		assertEquals("ty", fonts.settings().extraSpace("a.bcfnt"));
		assertEquals(1, changes[0]);
		assertEquals("ty", PatchSettings.open(romfs).extraSpace("a.bcfnt"), "saved for the Patch list");

		// the pane switched to the layout's other font: the bar edits that one, saving what was typed first
		field.setText("tyl");
		fonts.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), TextOverride.NONE.withFont("b.bcfnt"));
		bar.show(u);
		assertEquals("tyl", fonts.settings().extraSpace("a.bcfnt"));
		assertEquals("", field.getText());
		assertTrue(label(bar).endsWith("b"));

		// changed in the Patch list: the bar shows it
		fonts.settings().setExtraSpace("b.bcfnt", "r");
		bar.reload();
		assertEquals("r", field.getText());
	}

	private static String label(ExtraSpaceBar bar) {
		Component[] c = bar.getComponents();
		return ((JLabel) c[c.length - 1]).getText();
	}
}
