package psaro.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JComboBox;
import javax.swing.JSpinner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.patch.FontPatcher;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatchSettings;
import psaro.project.CodeColors;
import psaro.project.PaneLinks;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.romfs.RomfsIndex.Usage;
import psaro.romfs.SampleRomfs;

class PreviewPanelTest {

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

	/** One string on two screens: made to fit in one, it opens there again, not in the one still too long. */
	@Test
	void theStringOpensAgainOnThePaneLastPickedOrChanged() throws IOException {
		String japanese = "はい";
		String english = "いは".repeat(20);
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", japanese));
		for (String screen : List.of("up", "down")) {
			SampleRomfs.archive(romfs, "scene/menu/menu_" + screen + ".arc.lz", "blyt/menu_" + screen + ".bclyt",
					List.of("a.bcfnt"), Map.of("a.bcfnt", SampleRomfs.font("はい", 10)),
					SampleRomfs.pane("Txt_" + screen, "menu_0001"));
		}
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		PreviewPanel preview = new PreviewPanel(fonts, CodeColors.open(romfs), PaneLinks.open(index), () -> { }, () -> { });
		StringTable table = index.table("menu");
		List<Usage> usages = index.usages(table, "menu_0001");
		JComboBox<?> picker = all(preview, JComboBox.class, new ArrayList<>()).get(0);
		JSpinner boxWidth = all(preview, JSpinner.class, new ArrayList<>()).get(0);

		preview.showString(table, "menu_0001", usages, japanese, english, null);
		assertEquals(2, picker.getItemCount());
		assertTrue(picker.getSelectedItem().toString().startsWith("⚠"), "opens on a pane the English overflows");
		String changed = picker.getSelectedItem().toString();
		boxWidth.setValue(2000.0);
		String fits = picker.getSelectedItem().toString();
		assertNotEquals(changed, fits, "the change makes it fit");

		preview.showString(table, "menu_0001", usages, japanese, english, null);
		assertEquals(fits, picker.getSelectedItem().toString(), "the changed pane, though the other overflows");

		picker.setSelectedIndex(1 - picker.getSelectedIndex());
		String other = picker.getSelectedItem().toString();
		preview.showString(table, "menu_0001", usages, japanese, english, null);
		assertEquals(other, picker.getSelectedItem().toString(), "the pane picked");
	}
}
