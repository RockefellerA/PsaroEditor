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

	/** Strings shown in the same panes (every character's name) are linked in one go, and unlinked so. */
	@Test
	void severalStringsAreLinkedAtOnce() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.data(romfs, "CharaTable", List.of("dqc0101a,ロトの血を引く者,True", "dqc0102a,ローラ姫,True", "dqc0201a,ローレシアの王子,True"));
		SampleRomfs.archive(romfs, "scene/party/party_up.arc.lz", "blyt/party_select_up.bclyt", List.of("a.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Name", "x"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		PaneLinks links = PaneLinks.open(index);
		var chara = index.table("table/CharaTable");
		PaneRef name = new PaneRef("blyt/party_select_up.bclyt", "Txt_Name");

		links.set(chara, List.of("dqc0101a:1", "dqc0102a:1"), List.of(name));
		assertEquals(List.of(name), links.get(chara, "dqc0101a:1"));
		assertEquals(List.of(name), links.get(chara, "dqc0102a:1"));
		assertTrue(links.get(chara, "dqc0201a:1").isEmpty(), "only those named");
		assertEquals(1, index.usages(chara, "dqc0102a:1").size());
		assertEquals(List.of(name), PaneLinks.open(RomfsIndex.scan(romfs)).get(chara, "dqc0102a:1"), "saved");

		links.set(chara, List.of("dqc0101a:1", "dqc0102a:1"), List.of());
		assertFalse(Files.exists(dir.resolve("game.psaro/links.json")));
	}
}
