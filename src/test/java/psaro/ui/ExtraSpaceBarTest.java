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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
		JTextField field = (JTextField) descendants(bar).filter(c -> c instanceof JTextField)
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

		@SuppressWarnings("unchecked")
		JComboBox<Typeface> source = (JComboBox<Typeface>) descendants(bar)
				.filter(c -> c instanceof JComboBox).findFirst().orElseThrow();
		assertEquals(Typeface.GAME, source.getSelectedItem());
		source.setSelectedItem(Typeface.M_PLUS_ROUNDED);
		assertEquals(Typeface.M_PLUS_ROUNDED, PatchSettings.open(romfs).lettersFrom("a.bcfnt"));
		assertEquals(2, changes[0]);

		// the pane switched to the layout's other font: the bar edits that one, saving what was typed first
		field.setText("tyl");
		fonts.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), TextOverride.NONE.withFont("b.bcfnt"));
		bar.show(u);
		assertEquals("tyl", fonts.settings().extraSpace("a.bcfnt"));
		assertEquals("", field.getText());
		assertEquals(Typeface.GAME, source.getSelectedItem(), "b's own setting");
		assertTrue(label(bar).endsWith("b"));

		// changed in the Patch list: the bar shows it
		fonts.settings().setExtraSpace("b.bcfnt", "r");
		bar.reload();
		assertEquals("r", field.getText());
	}

	private static void layOut(Container c) {
		c.doLayout();
		for (Component k : c.getComponents()) {
			if (k instanceof Container cc) {
				layOut(cc);
			}
		}
	}

	/** Every component inside {@code c}, depth first. */
	private static Stream<Component> descendants(Container c) {
		return Arrays.stream(c.getComponents())
				.flatMap(k -> Stream.concat(Stream.of(k), k instanceof Container cc ? descendants(cc) : Stream.empty()));
	}

	/** In a panel too narrow for one line, the bar wraps: everything stays inside it, the letters box too. */
	@Test
	void aNarrowBarWrapsInsteadOfCuttingOffTheLettersBox() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Yes", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		ExtraSpaceBar bar = new ExtraSpaceBar(new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs)), () -> { });
		bar.show(index.usages(index.table("menu"), "menu_0001").get(0));
		JPanel holder = new JPanel(new BorderLayout());
		holder.add(bar, BorderLayout.NORTH);
		int oneLine = bar.getPreferredSize().height;
		holder.setSize(240, 400);
		// validate() lays out only what is on screen; lay the tree out as it would be
		layOut(holder);
		JTextField field = (JTextField) descendants(bar).filter(c -> c instanceof JTextField).findFirst().orElseThrow();
		Rectangle inBar = SwingUtilities.convertRectangle(field.getParent(), field.getBounds(), bar);
		assertTrue(bar.getHeight() > oneLine, "wrapped to more lines");
		assertTrue(inBar.x >= 0 && inBar.getMaxX() <= bar.getWidth() && inBar.getMaxY() <= bar.getHeight(),
				"the letters box " + inBar + " lies inside the bar " + bar.getSize());
	}

	private static String label(ExtraSpaceBar bar) {
		Component[] c = bar.getComponents();
		return ((JLabel) c[c.length - 1]).getText();
	}
}
