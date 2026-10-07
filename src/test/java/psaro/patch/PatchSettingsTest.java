package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchSettingsTest {

	@TempDir
	Path dir;

	@Test
	void defaultsUntilSomethingIsSaved() throws IOException {
		PatchSettings s = PatchSettings.open(dir.resolve("game"));
		assertEquals("", s.extraSpace("SulaPro_B_04a_20.bcfnt"));
		assertFalse(s.copyToMods());
		assertNull(s.modsFolder());
		assertFalse(Files.exists(dir.resolve("game.psaro/fonts.json")));
	}

	@Test
	void changesAreSavedAndReadBack() throws IOException {
		Path romfs = dir.resolve("game");
		PatchSettings s = PatchSettings.open(romfs);
		s.setExtraSpace("SulaPro_B_04a_20.bcfnt", " t y t f ");
		s.setCopyToMods(true, dir.resolve("mods"));
		assertEquals("tyf", s.extraSpace("SulaPro_B_04a_20.bcfnt"));

		PatchSettings again = PatchSettings.open(romfs);
		assertEquals("tyf", again.extraSpace("SulaPro_B_04a_20.bcfnt"));
		assertEquals("", again.extraSpace("SulaPro_B_04a_22.bcfnt"));
		again.setExtraSpace("SulaPro_B_04a_20.bcfnt", "");
		assertEquals("", PatchSettings.open(romfs).extraSpace("SulaPro_B_04a_20.bcfnt"));
		assertTrue(again.copyToMods());
		assertEquals(dir.resolve("mods"), again.modsFolder());
	}
}
