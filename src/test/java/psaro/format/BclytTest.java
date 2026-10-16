package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	/**
	 * A frame widened and moved left, and the label in it moved with it: only those panes' place
	 * and size change, any kind of pane alike, every other byte as it was.
	 */
	@Test
	void panesOfAnyKindAreMovedAndSized() {
		byte[] layout = SampleRomfs.layout(List.of("a.bcfnt"), SampleRomfs.group("NL_Card",
				SampleRomfs.picture("Pic_Fram_Resu", 56, -105, 66, 19), SampleRomfs.pane("Txt_Resu", "fise_0040", 31, -105),
				SampleRomfs.pane("Txt_Time", "fise_0030", 31, -86)));
		Bclyt.Pane frame = pane(layout, "Pic_Fram_Resu");
		assertEquals(66f, frame.width());
		assertEquals(19f, frame.height());
		assertTrue(frame.isPicture());
		assertEquals("NL_Card", frame.parent());

		Bclyt.PaneOverride wider = new Bclyt.PaneOverride(52f, null, 78f, null);
		byte[] changed = Bclyt.withPanes(layout, Map.of("Pic_Fram_Resu", wider, "Txt_Resu", new Bclyt.PaneOverride(27f, null, null, null)));
		assertEquals(layout.length, changed.length);
		assertEquals(wider.apply(frame), pane(changed, "Pic_Fram_Resu"));
		assertEquals(78f, pane(changed, "Pic_Fram_Resu").width());
		assertEquals(-105f, pane(changed, "Pic_Fram_Resu").y());
		assertEquals(27f, pane(changed, "Txt_Resu").x());
		assertEquals(120f, pane(changed, "Txt_Resu").width(), "a text pane's box is left alone");
		assertEquals(pane(layout, "Txt_Time"), pane(changed, "Txt_Time"));
		int differing = 0;
		for (int i = 0; i < layout.length; i++) {
			differing += layout[i] != changed[i] ? 1 : 0;
		}
		assertTrue(differing <= 12, differing + " bytes changed");
		assertArrayEquals(layout, Bclyt.withPanes(layout, Map.of()));
	}

	private static Bclyt.Pane pane(byte[] layout, String name) {
		return Bclyt.read(layout).panes().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
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
	void readsWhereEachPaneSitsAndWhatItHangsUnder() {
		byte[] layout = SampleRomfs.layout(List.of("a.bcfnt"), SampleRomfs.pane("Txt_Top", "menu_0001", 50, 6),
				SampleRomfs.group("NL_Wind", SampleRomfs.pane("Txt_Titl_02", "menu_0002", 0, 40),
						SampleRomfs.pane("Txt_Titl_01", "menu_0002", 0, 38.5f)),
				SampleRomfs.pane("Txt_After", "menu_0003"));
		var panes = Bclyt.read(layout).textPanes();
		assertEquals(null, panes.get(0).parent());
		assertEquals(50f, panes.get(0).x());
		assertEquals(6f, panes.get(0).y());
		assertEquals("NL_Wind", panes.get(1).parent());
		assertEquals(40f, panes.get(1).y());
		assertEquals("NL_Wind", panes.get(2).parent());
		assertEquals(null, panes.get(3).parent(), "back at the root after the group closes");
		assertTrue(panes.get(1).stacksOn(panes.get(2)), "a shadow's offset apart");
		assertFalse(panes.get(0).stacksOn(panes.get(3)), "apart");
	}

	@Test
	void noChangeLeavesTheBytesAsTheyWere() {
		assertArrayEquals(LAYOUT, Bclyt.withText(LAYOUT, Map.of("Txt_Yes", TextOverride.NONE)));
		assertArrayEquals(LAYOUT, Bclyt.withText(LAYOUT, Map.of("Txt_Gone", new TextOverride(1f, 1f, 1f, 1f, 1f, 1f))));
	}

	/**
	 * Each picture's image is the first texture its material maps: a window's from its content;
	 * a material mapping none, or a layout with no materials, gives none.
	 */
	@Test
	void picturesDrawTheTextureTheirMaterialMaps() {
		byte[] layout = SampleRomfs.layout(List.of("a.bcfnt"), SampleRomfs.textures("frame.bclim", "bar.bclim"),
				SampleRomfs.materials(1, -1, 0), SampleRomfs.picture("Pic_Bar", 274, 26, 0),
				SampleRomfs.picture("Pic_Plain", 10, 10, 1), SampleRomfs.window("Win_Help", 512, 234, 2),
				SampleRomfs.pane("Txt_Main", "help_0001"));
		assertEquals(Map.of("Pic_Bar", "bar.bclim", "Win_Help", "frame.bclim"), Bclyt.pictureImages(layout));
		assertEquals(3, Bclyt.read(layout).panes().stream().filter(Bclyt.Pane::isPicture).count(), "every picture is still a pane");
		byte[] noMaterials = SampleRomfs.layout(List.of("a.bcfnt"), SampleRomfs.picture("Pic_Bar", 0, 0, 274, 26));
		assertTrue(Bclyt.pictureImages(noMaterials).isEmpty());
	}

	/** A pane's own text replaced: longer text grows its buffer and section; the panes around it keep theirs. */
	@Test
	void aPanesOwnTextIsReplacedAndItsBufferGrows() {
		byte[] layout = SampleRomfs.layout(List.of("a.bcfnt"), SampleRomfs.pane("Txt_Btn", null, "はい"),
				SampleRomfs.pane("Txt_Other", "menu_0001"));
		byte[] out = Bclyt.withPaneText(layout, Map.of("Txt_Btn", "Practice Again"));
		Bclyt.Layout read = Bclyt.read(out);
		TextInfo btn = read.textPanes().get(0).text();
		assertEquals("Practice Again", btn.placeholder());
		assertEquals(("Practice Again".length() + 1) * 2, btn.bufferBytes(), "room for the English and its NUL");
		assertEquals("*", read.textPanes().get(1).text().placeholder());
		assertEquals(List.of("menu_0001"), read.textPanes().get(1).keys(), "the next pane's user data still follows it");
		assertEquals(out.length, Bytes.u32(out, 0x0C), "the file size follows");
		assertEquals(0, (Bytes.u32(out, Bytes.u16(out, 6) + Bytes.u32(out, Bytes.u16(out, 6) + 4) + 4)) % 4, "sections stay four-byte aligned");

		// shorter text keeps the buffer the layout gave it
		TextInfo shorter = Bclyt.read(Bclyt.withPaneText(out, Map.of("Txt_Btn", "Yes"))).textPanes().get(0).text();
		assertEquals("Yes", shorter.placeholder());
		assertEquals(btn.bufferBytes(), shorter.bufferBytes());
		assertArrayEquals(out, Bclyt.withPaneText(out, Map.of("Txt_Btn", "Practice Again")), "the same text, the same bytes");
		assertArrayEquals(layout, Bclyt.withPaneText(layout, Map.of()));
	}
}
