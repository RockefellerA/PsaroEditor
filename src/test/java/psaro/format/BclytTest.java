package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.romfs.SampleRomfs;

class BclytTest {

	private static final byte[] LAYOUT = SampleRomfs.layout(List.of("a.bcfnt"),
			SampleRomfs.pane("Txt_Yes", "menu_0001"), SampleRomfs.pane("Txt_No", "menu_0002"));

	private static TextInfo text(byte[] layout, String pane) {
		return Bclyt.read(layout).textPanes().stream().filter(p -> p.name().equals(pane)).findFirst().orElseThrow().text();
	}

	@Test
	void changesOnlyTheNamedPanesSettings() {
		TextOverride t = new TextOverride(40f, null, 15.5f, null, 1f, -3f);
		byte[] changed = Bclyt.withText(LAYOUT, Map.of("Txt_Yes", t));
		assertEquals(LAYOUT.length, changed.length);
		TextInfo yes = text(changed, "Txt_Yes");
		assertEquals(t.apply(text(LAYOUT, "Txt_Yes")), yes);
		assertEquals(40f, yes.boxWidth());
		assertEquals(24f, yes.boxHeight());
		assertEquals(15.5f, yes.fontSizeX());
		assertEquals(text(LAYOUT, "Txt_No"), text(changed, "Txt_No"));
		// four floats of four bytes each, at most, and nothing else
		int differing = 0;
		for (int i = 0; i < LAYOUT.length; i++) {
			differing += LAYOUT[i] != changed[i] ? 1 : 0;
		}
		assertEquals(true, differing <= 16, differing + " bytes changed");
	}

	@Test
	void aPaneSwitchesToAnotherFontTheLayoutLists() {
		byte[] layout = SampleRomfs.layout(List.of("a.bcfnt", "b.bcfnt"), SampleRomfs.pane("Txt_Yes", "menu_0001"),
				SampleRomfs.pane("Txt_No", "menu_0002"));
		byte[] changed = Bclyt.withText(layout, Map.of("Txt_Yes", TextOverride.NONE.withFont("b.bcfnt")));
		assertEquals("b.bcfnt", text(changed, "Txt_Yes").font());
		assertEquals("a.bcfnt", text(changed, "Txt_No").font());
		// a font the layout does not list cannot be pointed to
		assertArrayEquals(layout, Bclyt.withText(layout, Map.of("Txt_Yes", TextOverride.NONE.withFont("c.bcfnt"))));
	}

	@Test
	void aFontIsRenamedInTheListForEveryPaneDrawingWithIt() {
		byte[] layout = SampleRomfs.layout(List.of("a.bcfnt", "b.bcfnt"), SampleRomfs.pane("Txt_Yes", "menu_0001"),
				SampleRomfs.pane("Txt_No", "menu_0002"));
		byte[] renamed = Bclyt.withFontNames(layout, Map.of("a.bcfnt", "MPLUSRounded1c_a.bcfnt"));
		assertEquals(List.of("MPLUSRounded1c_a.bcfnt", "b.bcfnt"), Bclyt.read(renamed).fonts());
		assertEquals("MPLUSRounded1c_a.bcfnt", text(renamed, "Txt_Yes").font());
		assertEquals(text(layout, "Txt_No").boxWidth(), text(renamed, "Txt_No").boxWidth());
		assertEquals(renamed.length, java.nio.ByteBuffer.wrap(renamed).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0x0C),
				"the header's file size follows");
		assertEquals(0, renamed.length % 4);
		// nothing to rename: the same bytes
		assertArrayEquals(layout, Bclyt.withFontNames(layout, Map.of("c.bcfnt", "d.bcfnt")));
	}

	@Test
	void onePaneIsPointedAtAFontTheListGainsTheOthersKeepTheirs() {
		byte[] layout = SampleRomfs.layout(List.of("a.bcfnt", "b.bcfnt"), SampleRomfs.pane("Txt_Yes", "menu_0001"),
				SampleRomfs.pane("Txt_No", "menu_0002"));
		byte[] out = Bclyt.withPaneFonts(layout, Map.of("Txt_Yes", "MPLUSRounded1c_a.bcfnt"));
		assertEquals(List.of("a.bcfnt", "b.bcfnt", "MPLUSRounded1c_a.bcfnt"), Bclyt.read(out).fonts(), "added at the end");
		assertEquals("MPLUSRounded1c_a.bcfnt", text(out, "Txt_Yes").font());
		assertEquals("a.bcfnt", text(out, "Txt_No").font());
		// a font the list has: only the index changes
		byte[] toB = Bclyt.withPaneFonts(layout, Map.of("Txt_No", "b.bcfnt"));
		assertEquals(layout.length, toB.length);
		assertEquals("b.bcfnt", text(toB, "Txt_No").font());
		assertArrayEquals(layout, Bclyt.withPaneFonts(layout, Map.of("Txt_No", "a.bcfnt")), "already so: the same bytes");
	}

	@Test
	void readsEachPanesKeyFromItsTextIdWithThreeOrFourLetters() {
		byte[] layout = SampleRomfs.layout(List.of("a.bcfnt"), SampleRomfs.pane("Txt_Mons", "cmn_0004"),
				SampleRomfs.pane("Txt_Line", "clsm_0110"));
		var panes = Bclyt.read(layout).textPanes();
		assertEquals(List.of("cmn_0004"), panes.get(0).keys());
		assertEquals(List.of("clsm_0110"), panes.get(1).keys());
	}

	@Test
	void noChangeLeavesTheBytesAsTheyWere() {
		assertArrayEquals(LAYOUT, Bclyt.withText(LAYOUT, Map.of("Txt_Yes", TextOverride.NONE)));
		assertArrayEquals(LAYOUT, Bclyt.withText(LAYOUT, Map.of("Txt_Gone", new TextOverride(1f, 1f, 1f, 1f, 1f, 1f))));
	}
}
