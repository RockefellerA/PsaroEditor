package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.romfs.SampleRomfs;

/** A DLC's project, kept in its game's ({@link Translations#attachDlc}, {@link DlcLink}, {@link DlcInherit}). */
class DlcTest {

	@TempDir
	Path dir;

	@AfterEach
	void detach() {
		Translations.detachDlc(dir.resolve("TDQDLC_romfs"));
	}

	/** An attached DLC's project is the game project's DLC folder; detached, it is beside the DLC again. */
	@Test
	void anAttachedDlcsProjectIsInTheGames() {
		Path game = dir.resolve("game");
		Path dlc = dir.resolve("TDQDLC_romfs");
		Translations.attachDlc(dlc, game);
		assertEquals(dir.resolve("game.psaro/DLC").toAbsolutePath(), Translations.folderFor(dlc));
		assertEquals(game.toAbsolutePath(), Translations.gameOf(dlc));
		assertEquals(dir.resolve("game.psaro").toAbsolutePath(), Translations.folderFor(game));
		Translations.detachDlc(dlc);
		assertEquals(dir.resolve("TDQDLC_romfs.psaro").toAbsolutePath(), Translations.folderFor(dlc));
		assertNull(Translations.gameOf(dlc));
	}

	/** The DLC's English is saved in the game's project. */
	@Test
	void theDlcsEnglishIsSavedInTheGamesProject() throws IOException {
		Path dlc = dir.resolve("TDQDLC_romfs");
		SampleRomfs.content(dlc, "009a", List.of("0501_FMS_003,0,True,王宮のトランペット"));
		Translations.attachDlc(dlc, dir.resolve("game"));
		RomfsIndex index = RomfsIndex.scanDlc(dlc, List.of("009a"));
		Translations t = Translations.open(index);
		t.set(index.table("009a/contents"), "0501_FMS_003:3", "Royal Trumpet");
		t.save();
		assertTrue(Files.isRegularFile(dir.resolve("game.psaro/DLC/english/009a/contents.json")));
		assertFalse(Files.exists(dir.resolve("TDQDLC_romfs.psaro")));
		assertEquals("Royal Trumpet", Translations.open(index).get(index.table("009a/contents"), "0501_FMS_003:3"));
	}

	/** The link keeps the DLC's folder and the contents chosen, and finds a folder moved with the game's. */
	@Test
	void theLinkIsReadBack() throws IOException {
		Path game = dir.resolve("work/game");
		Path dlc = dir.resolve("work/TDQDLC_romfs");
		Files.createDirectories(dlc);
		assertNull(DlcLink.read(game));
		DlcLink.write(game, new DlcLink.Dlc(dlc, List.of("009a", "009b")));
		assertEquals(dir.resolve("work/game.psaro/DLC/dlc.json"), DlcLink.file(game));
		DlcLink.Dlc read = DlcLink.read(game);
		assertEquals(dlc.toAbsolutePath(), read.folder().toAbsolutePath());
		assertEquals(List.of("009a", "009b"), read.contents());

		// the whole working folder moved: the absolute path is gone, the relative one still finds it
		Path moved = dir.resolve("moved");
		Files.move(dir.resolve("work"), moved);
		DlcLink.Dlc again = DlcLink.read(moved.resolve("game"));
		assertEquals(moved.resolve("TDQDLC_romfs").toAbsolutePath().normalize(), again.folder().toAbsolutePath().normalize());
	}

	/**
	 * A DLC song's title shows as the game's song titles do: it stands in as a string of the game's
	 * table that shows the most of that column, in rows shaped as its own; a column no pane shows has none.
	 */
	@Test
	void aDlcStringShowsInTheGamesPanesForItsColumn() throws IOException {
		Path game = dir.resolve("game");
		SampleRomfs.data(game, "MusicTable", List.of("0001_BMS_001,0,戦闘のテーマ,ドラゴンクエスト", "0001_FMS_001,0,足どり,ドラゴンクエスト"));
		// rows shaped the same, but fewer of them shown
		SampleRomfs.data(game, "ChallengeTable", List.of("0009_BMS_001,0,挑戦,ドラゴンクエスト"));
		SampleRomfs.archive(game, "music_select.arc.lz", "blyt/music_select.bclyt", List.of("font.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Musi_Titl", ""), SampleRomfs.pane("Txt_Chal", ""));
		RomfsIndex index = RomfsIndex.scan(game);
		StringTable music = index.table("table/MusicTable");
		StringTable challenge = index.table("table/ChallengeTable");
		RomfsIndex.PaneRef title = new RomfsIndex.PaneRef("blyt/music_select.bclyt", "Txt_Musi_Titl");
		index.link(music, "0001_BMS_001:2", List.of(title));
		index.link(music, "0001_FMS_001:2", List.of(title));
		index.link(challenge, "0009_BMS_001:2", List.of(new RomfsIndex.PaneRef("blyt/music_select.bclyt", "Txt_Chal")));

		Path dlc = dir.resolve("TDQDLC_romfs");
		SampleRomfs.content(dlc, "009a", List.of("0501_FMS_003,0,王宮のトランペット,ドラゴンクエストV"));
		RomfsIndex dlcIndex = RomfsIndex.scanDlc(dlc, List.of("009a"));
		StringTable song = dlcIndex.table("009a/contents");
		DlcPanes panes = new DlcPanes(index);
		DlcPanes.StandIn s = panes.standIn(song, "0501_FMS_003:2");
		assertEquals(music, s.table());
		assertEquals(List.of(title), index.panes(s.table(), s.key()).stream()
				.map(u -> new RomfsIndex.PaneRef(u.layout(), u.pane().name())).toList());
		// no pane shows the game's column 3, nor a row shaped otherwise
		assertNull(panes.standIn(song, "0501_FMS_003:3"));
		assertNull(panes.standIn(song, "x:2"));
		assertEquals("9999_AAA_999", DlcPanes.shape("0501_FMS_003"));
	}

	/** A DLC string takes the game's English for the same Japanese, unless the game did it two ways or the DLC did it. */
	@Test
	void theDlcTakesTheGamesEnglishForTheSameJapanese() throws IOException {
		Path game = dir.resolve("game");
		SampleRomfs.data(game, "MusicTable", List.of(
				"0001_BMS_001,0,True,戦闘のテーマ,ドラゴンクエストV 天空の花嫁,ロト",
				"0001_FMS_001,0,True,足どりも軽やかに,ドラゴンクエストV 天空の花嫁,ロト",
				"0001_EMS_001,0,True,王宮のロンド,ドラゴンクエストIII そして伝説へ…,ロト"));
		RomfsIndex gameIndex = RomfsIndex.scan(game);
		Translations gameText = Translations.open(gameIndex);
		StringTable music = gameIndex.table("table/MusicTable");
		gameText.set(music, "0001_BMS_001:4", "Dragon Quest V: Hand of the Heavenly Bride");
		gameText.set(music, "0001_FMS_001:4", "Dragon Quest V: Hand of the Heavenly Bride");
		gameText.set(music, "0001_EMS_001:3", "Courtly Rondo");
		// translated two ways, so a DLC string reading the same is left alone
		gameText.set(music, "0001_BMS_001:5", "Loto");
		gameText.set(music, "0001_FMS_001:5", "Roto");

		Path dlc = dir.resolve("TDQDLC_romfs");
		SampleRomfs.content(dlc, "009a", List.of("0501_FMS_003,0,True,王宮のトランペット,ドラゴンクエストV 天空の花嫁,ロト"));
		SampleRomfs.content(dlc, "009c", List.of("0301_FMS_002,0,True,王宮のロンド,ドラゴンクエストIII そして伝説へ…,ロト"));
		Translations.attachDlc(dlc, game);
		RomfsIndex dlcIndex = RomfsIndex.scanDlc(dlc, List.of("009a", "009c"));
		Translations dlcText = Translations.open(dlcIndex);
		StringTable rondo = dlcIndex.table("009c/contents");
		dlcText.set(rondo, "0301_FMS_002:3", "Palace Rondo");

		DlcInherit.Result r = DlcInherit.fill(gameIndex, gameText, dlcIndex, dlcText);
		StringTable trumpet = dlcIndex.table("009a/contents");
		assertEquals("Dragon Quest V: Hand of the Heavenly Bride", dlcText.get(trumpet, "0501_FMS_003:4"));
		assertNull(dlcText.get(trumpet, "0501_FMS_003:3"));
		assertNull(dlcText.get(trumpet, "0501_FMS_003:5"));
		// the DLC's own English stands
		assertEquals("Palace Rondo", dlcText.get(rondo, "0301_FMS_002:3"));
		assertEquals(new DlcInherit.Result(1, 0, 2), r);
		assertTrue(dlcText.isDirty());
		assertFalse(Files.exists(dir.resolve("game.psaro/DLC/english")), "nothing is saved");

		// done again, nothing more is taken
		assertEquals(0, DlcInherit.fill(gameIndex, gameText, dlcIndex, dlcText).filled());
	}
}
