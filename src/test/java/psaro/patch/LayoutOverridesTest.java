package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
