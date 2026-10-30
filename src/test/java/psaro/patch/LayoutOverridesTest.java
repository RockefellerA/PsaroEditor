package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Bclyt.PaneOverride;
import psaro.format.Bclyt.TextOverride;

class LayoutOverridesTest {

	private static final String LAYOUT = "blyt/common_btn_win_yes.bclyt";

	@TempDir
	Path dir;

	@Test
	void changesAreSavedReadBackAndUndone() throws IOException {
		Path romfs = dir.resolve("game");
		LayoutOverrides o = LayoutOverrides.open(romfs);
		assertTrue(o.layouts().isEmpty());
		TextOverride t = new TextOverride(40f, null, null, null, 2.1f, null, "SulaPro_B_04a_18.bcfnt");
		o.set(LAYOUT, List.of("Txt_Btn", "Txt_Btn_Shad"), t);

		Path file = dir.resolve("game.psaro/layouts.json");
		String json = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(json.contains("2.1") && !json.contains("2.09"), json);
		LayoutOverrides again = LayoutOverrides.open(romfs);
		assertEquals(Set.of(LAYOUT), again.layouts());
		assertEquals(t, again.get(LAYOUT, "Txt_Btn"));
		assertEquals(t, again.get(LAYOUT, "Txt_Btn_Shad"));
		assertEquals(TextOverride.NONE, again.get(LAYOUT, "Txt_Other"));

		again.set(LAYOUT, List.of("Txt_Btn", "Txt_Btn_Shad"), TextOverride.NONE);
		assertTrue(again.layouts().isEmpty());
		assertFalse(Files.exists(file));
	}

	/** A pane's place and size are kept beside its text settings, read back, and undone apart from them. */
	@Test
	void placesAreSavedBesideTextSettingsAndUndoneApart() throws IOException {
		Path romfs = dir.resolve("game");
		String card = "blyt/config_common.bclyt";
		LayoutOverrides o = LayoutOverrides.open(romfs);
		PaneOverride frame = new PaneOverride(52f, null, 78.5f, null);
		o.setPlace(card, "Pic_Fram_Resu", frame);
		o.setPlaces(card, Map.of("Txt_Resu", new PaneOverride(27f, null, null, null)));
		TextOverride tighter = new TextOverride(72f, null, null, null, -0.5f, null);
		o.set(card, List.of("Txt_Resu"), tighter);

		String json = Files.readString(dir.resolve("game.psaro/layouts.json"), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"width\": 78.5") && json.contains("\"boxWidth\": 72"), json);
		LayoutOverrides again = LayoutOverrides.open(romfs);
		assertEquals(Set.of(card), again.layouts());
		assertEquals(frame, again.place(card, "Pic_Fram_Resu"));
		assertEquals(new PaneOverride(27f, null, null, null), again.place(card, "Txt_Resu"));
		assertEquals(tighter, again.get(card, "Txt_Resu"));
		assertEquals(TextOverride.NONE, again.get(card, "Pic_Fram_Resu"));
		assertEquals(Map.of("Pic_Fram_Resu", frame, "Txt_Resu", new PaneOverride(27f, null, null, null)), again.placesForLayout(card));

		again.setPlace(card, "Txt_Resu", PaneOverride.NONE);
		assertEquals(tighter, LayoutOverrides.open(romfs).get(card, "Txt_Resu"), "its text settings stay");
		again.setPlace(card, "Pic_Fram_Resu", PaneOverride.NONE);
		again.set(card, List.of("Txt_Resu"), TextOverride.NONE);
		assertTrue(again.layouts().isEmpty());
		assertFalse(Files.exists(dir.resolve("game.psaro/layouts.json")));
	}

	/** A depth alone is a change of its own, saved as z and read back. */
	@Test
	void depthIsSavedAndReadBack() throws IOException {
		Path romfs = dir.resolve("game");
		String stage = "blyt/challenge_stage_select_up.bclyt";
		LayoutOverrides o = LayoutOverrides.open(romfs);
		PaneOverride flat = new PaneOverride(null, null, null, null, 0f);
		assertFalse(flat.isEmpty());
		o.setPlace(stage, "Pic_Flag", flat);

		String json = Files.readString(dir.resolve("game.psaro/layouts.json"), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"z\": 0"), json);
		assertFalse(json.contains("\"x\""), json);
		assertEquals(flat, LayoutOverrides.open(romfs).place(stage, "Pic_Flag"));
		assertEquals(Set.of(stage), LayoutOverrides.open(romfs).layouts());
	}
}
