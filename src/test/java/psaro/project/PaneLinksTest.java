package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.PaneRef;
import psaro.romfs.SampleRomfs;

class PaneLinksTest {

	@TempDir
	Path dir;

	@Test
	void linksAreSavedAndLinkedAgainOnOpening() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.message(romfs, "tutorial_and_help", List.of("はい"));
		SampleRomfs.archive(romfs, "scene/music/InfoTutorialButton.arc.lz", "blyt/tutorial_btn_yes.bclyt",
				List.of("a.bcfnt"), Map.of(), SampleRomfs.pane("Txt_Btn", "trhl_1690"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		PaneLinks links = PaneLinks.open(index);
		var help = index.table("message/tutorial_and_help");
		PaneRef button = new PaneRef("blyt/tutorial_btn_yes.bclyt", "Txt_Btn");
		Path file = dir.resolve("game.psaro/links.json");
		assertFalse(Files.exists(file), "nothing linked, nothing written");

		links.set(help, "000", List.of(button));
		assertEquals(List.of(button), links.get(help, "000"));
		assertEquals(1, index.usages(help, "000").size());
		assertTrue(Files.isRegularFile(file));

		RomfsIndex again = RomfsIndex.scan(romfs);
		PaneLinks reopened = PaneLinks.open(again);
		assertEquals(List.of(button), reopened.get(again.table("message/tutorial_and_help"), "000"));
		assertEquals("Txt_Btn", again.usages(again.table("message/tutorial_and_help"), "000").get(0).pane().name());

		reopened.set(again.table("message/tutorial_and_help"), "000", List.of());
		assertFalse(Files.exists(file), "the last link gone, the file goes");
	}
}
