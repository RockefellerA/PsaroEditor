package psaro.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.project.PaneLinks;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.PaneRef;
import psaro.romfs.SampleRomfs;

class LinkPanesDialogTest {

	@TempDir
	Path dir;

	/**
	 * Applying a string's panes to others starts with its data table column's strings that have no
	 * links, or the same links it has: not those of another column, not those linked otherwise, not
	 * any of a message file's.
	 */
	@Test
	void theSameColumnsUnlinkedOrAlikeStringsComeTicked() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.data(romfs, "JobTable", List.of("JOB001,戦士,せんし", "JOB002,魔法使い,まほうつかい", "JOB003,僧侶,そうりょ"));
		SampleRomfs.message(romfs, "party_top", List.of("ガンガンいこうぜ", "いのちだいじに"));
		SampleRomfs.archive(romfs, "scene/party/party_up.arc.lz", "blyt/party_select_up.bclyt", List.of("a.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Job_Name", "x"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		PaneLinks links = PaneLinks.open(index);
		var jobs = index.table("table/JobTable");
		PaneRef name = new PaneRef("blyt/party_select_up.bclyt", "Txt_Job_Name");
		links.set(jobs, "JOB003:1", List.of(name));

		assertEquals(List.of("JOB002:1"), LinkPanesDialog.sameColumnToTick(jobs, "JOB001:1", links));
		assertEquals(List.of("JOB002:2", "JOB003:2"), LinkPanesDialog.sameColumnToTick(jobs, "JOB001:2", links));
		assertEquals(List.of(), LinkPanesDialog.sameColumnToTick(index.table("message/party_top"), "000", links));

		// a pane added to one of a column linked alike is meant for them all
		links.set(jobs, "JOB001:1", List.of(name));
		assertEquals(List.of("JOB002:1", "JOB003:1"), LinkPanesDialog.sameColumnToTick(jobs, "JOB001:1", links));
		links.set(jobs, "JOB002:1", List.of(name, new PaneRef("blyt/party_select_up.bclyt", "Txt_Other")));
		assertEquals(List.of("JOB003:1"), LinkPanesDialog.sameColumnToTick(jobs, "JOB001:1", links));
	}

	/**
	 * A message string is suggested the panes of a layout's string with the same text, placeholders
	 * aside, each naming that string; one with too little text of its own, or a layout's string
	 * itself, is suggested none.
	 */
	@Test
	void panesShowingTheSameTextAreSuggested() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "dharma_complete",
				Map.of("dsjc_1010", "[キャラクター名]は\n[職業名]に転職した！", "dsjc_0540", "×[さとり所有数]"));
		SampleRomfs.message(romfs, "dharma_complete", List.of("%lsは\n%lsに転職した！", "×%d", "転職しなかった"));
		SampleRomfs.archive(romfs, "common/dialog/dharma_complete.arc.lz", "blyt/dialog_dharma_complete_down.bclyt",
				List.of("a.bcfnt"), Map.of(), SampleRomfs.pane("Txt_Main", "dsjc_1010"),
				SampleRomfs.pane("Txt_Main_Satori", "dsjc_1010"), SampleRomfs.pane("Txt_Num", "dsjc_0540"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		var message = index.table("message/dharma_complete");

		String layout = "blyt/dialog_dharma_complete_down.bclyt";
		assertEquals(Map.of(new PaneRef(layout, "Txt_Main"), "dharma_complete › dsjc_1010",
				new PaneRef(layout, "Txt_Main_Satori"), "dharma_complete › dsjc_1010"),
				LinkPanesDialog.suggestions(index, message, "000"));
		assertEquals(Map.of(), LinkPanesDialog.suggestions(index, message, "001"));
		assertEquals(Map.of(), LinkPanesDialog.suggestions(index, message, "002"));
		assertEquals(Map.of(), LinkPanesDialog.suggestions(index, index.table("dharma_complete"), "dsjc_1010"));
	}
}
