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
	void noChangeLeavesTheBytesAsTheyWere() {
		assertArrayEquals(LAYOUT, Bclyt.withText(LAYOUT, Map.of("Txt_Yes", TextOverride.NONE)));
		assertArrayEquals(LAYOUT, Bclyt.withText(LAYOUT, Map.of("Txt_Gone", new TextOverride(1f, 1f, 1f, 1f, 1f, 1f))));
	}
}
