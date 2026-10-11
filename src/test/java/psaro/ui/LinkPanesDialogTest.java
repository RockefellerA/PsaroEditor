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
	 * links: not those of another column, not those linked already, not any of a message file's.
	 */
	@Test
	void theSameColumnsUnlinkedStringsComeTicked() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.data(romfs, "JobTable", List.of("JOB001,戦士,せんし", "JOB002,魔法使い,まほうつかい", "JOB003,僧侶,そうりょ"));
		SampleRomfs.message(romfs, "party_top", List.of("ガンガンいこうぜ", "いのちだいじに"));
		SampleRomfs.archive(romfs, "scene/party/party_up.arc.lz", "blyt/party_select_up.bclyt", List.of("a.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Job_Name", "x"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		PaneLinks links = PaneLinks.open(index);
		var jobs = index.table("table/JobTable");
		links.set(jobs, "JOB003:1", List.of(new PaneRef("blyt/party_select_up.bclyt", "Txt_Job_Name")));

		assertEquals(List.of("JOB002:1"), LinkPanesDialog.sameColumnUnlinked(jobs, "JOB001:1", links));
		assertEquals(List.of("JOB002:2", "JOB003:2"), LinkPanesDialog.sameColumnUnlinked(jobs, "JOB001:2", links));
		assertEquals(List.of(), LinkPanesDialog.sameColumnUnlinked(index.table("message/party_top"), "000", links));
	}
}
