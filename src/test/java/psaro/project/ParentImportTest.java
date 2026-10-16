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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import psaro.format.Bclyt.PaneOverride;
import psaro.format.Bclyt.TextOverride;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatchSettings;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.PaneRef;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.romfs.SampleRomfs;

class ParentImportTest {

	private static final String LAYOUT = "blyt/menu.bclyt";
	private static final String FONT = "a.bcfnt";

	@TempDir
	Path dir;
	private Path game;
	private Path dlc;

	@BeforeEach
	void romfs() throws IOException {
		game = dir.resolve("game");
		dlc = dir.resolve("dlc");
		SampleRomfs.table(game, "menu", Map.of("menu_0001", "はい", "menu_0002", "いいえ", "menu_0003", "ぼうけん"));
		SampleRomfs.table(game, "shop", Map.of("shop_0001", "かう", "shop_0002", "ぼうけん"));
		SampleRomfs.archive(game, "scene/menu/menu.arc.lz", LAYOUT, List.of(FONT), Map.of(FONT, SampleRomfs.font("はい", 8)),
				SampleRomfs.pane("Txt_Yes", "menu_0001"), SampleRomfs.pane("Txt_No", "menu_0002"));
		// the DLC: same screens, some strings the game has, some it does not
		SampleRomfs.table(dlc, "menu", Map.of("menu_0001", "はい", "menu_0002", "ちがう", "menu_0009", "いいえ"));
		SampleRomfs.table(dlc, "extra", Map.of("extr_0001", "かう", "extr_0002", "ぼうけん", "extr_0003", "あたらしい"));
		SampleRomfs.archive(dlc, "scene/menu/menu.arc.lz", LAYOUT, List.of(FONT), Map.of(FONT, SampleRomfs.font("はい", 8)),
				SampleRomfs.pane("Txt_Yes", "menu_0001"), SampleRomfs.pane("Txt_No", "menu_0002"));
	}

	private void english(Path romfs, String table, String key, String text) throws IOException {
		RomfsIndex index = RomfsIndex.scan(romfs);
		Translations t = Translations.open(index);
		t.set(index.table(table), key, text);
		t.save();
	}

	private ParentImport.Result run() throws IOException {
		RomfsIndex index = RomfsIndex.scan(dlc);
		return ParentImport.run(index, Translations.open(index), PaneLinks.open(index), LayoutOverrides.open(dlc),
				PatchSettings.open(dlc), game);
	}

	private String got(String table, String key) throws IOException {
		RomfsIndex index = RomfsIndex.scan(dlc);
		StringTable t = index.table(table);
		return Translations.open(index).get(t, key);
	}

	@Test
	void sameKeySameJapaneseTakesTheParentsEnglish() throws IOException {
		english(game, "menu", "menu_0001", "Yes");
		ParentImport.Result r = run();
		assertEquals(1, r.translations());
		assertEquals("Yes", got("menu", "menu_0001"));
	}

	@Test
	void aSameKeyWithOtherJapaneseIsNotTaken() throws IOException {
		english(game, "menu", "menu_0002", "No");
		run();
		assertNull(got("menu", "menu_0002"), "the DLC's menu_0002 says something else");
	}

	@Test
	void japaneseThatReadsTheSameAnywhereIsTaken() throws IOException {
		english(game, "menu", "menu_0002", "No");
		english(game, "shop", "shop_0001", "Buy");
		run();
		assertEquals("No", got("menu", "menu_0009"), "another key and table, the same Japanese");
		assertEquals("Buy", got("extra", "extr_0001"));
	}

	@Test
	void japaneseTranslatedTwoWaysIsLeftAndCounted() throws IOException {
		english(game, "menu", "menu_0003", "Adventure");
		english(game, "shop", "shop_0002", "Quest");
		ParentImport.Result r = run();
		assertNull(got("extra", "extr_0002"));
		assertEquals(1, r.ambiguous());
	}

	@Test
	void whatTheProjectHasDoneItselfIsKept() throws IOException {
		english(game, "menu", "menu_0001", "Yes");
		english(dlc, "menu", "menu_0001", "Yep");
		ParentImport.Result r = run();
		assertEquals("Yep", got("menu", "menu_0001"));
		assertEquals(0, r.translations());
	}

	@Test
	void stringsKeptInJapaneseAreKept() throws IOException {
		RomfsIndex g = RomfsIndex.scan(game);
		Translations gt = Translations.open(g);
		gt.setKeepsJapanese(g.table("shop"), "shop_0001", true);
		gt.save();
		ParentImport.Result r = run();
		assertEquals(1, r.keptJapanese());
		RomfsIndex index = RomfsIndex.scan(dlc);
		assertTrue(Translations.open(index).keepsJapanese(index.table("extra"), "extr_0001"));
	}

	@Test
	void layoutChangesAreTakenWhereTheLayoutAndPaneAreThere() throws IOException {
		LayoutOverrides theirs = LayoutOverrides.open(game);
		theirs.set(LAYOUT, List.of("Txt_Yes"), new TextOverride(40f, null, null, null, 2f, null, "gone.bcfnt"));
		theirs.set(LAYOUT, List.of("Txt_Missing"), new TextOverride(10f, null, null, null, null, null));
		theirs.set("blyt/absent.bclyt", List.of("Txt_Yes"), new TextOverride(10f, null, null, null, null, null));
		theirs.setPlace(LAYOUT, "Pic_Fram", new PaneOverride(5f, null, 70f, null));
		LayoutOverrides mine = LayoutOverrides.open(dlc);
		mine.set(LAYOUT, List.of("Txt_No"), new TextOverride(99f, null, null, null, null, null));
		LayoutOverrides parentNo = LayoutOverrides.open(game);
		parentNo.set(LAYOUT, List.of("Txt_No"), new TextOverride(11f, null, null, null, null, null));

		ParentImport.Result r = run();
		LayoutOverrides after = LayoutOverrides.open(dlc);
		assertEquals(1, r.textSettings());
		assertEquals(1, r.placements());
		assertEquals(40f, after.get(LAYOUT, "Txt_Yes").boxWidth());
		assertNull(after.get(LAYOUT, "Txt_Yes").font(), "a font the DLC's layout lacks is not named");
		assertEquals(99f, after.get(LAYOUT, "Txt_No").boxWidth(), "the DLC's own setting stays");
		assertTrue(after.get(LAYOUT, "Txt_Missing").isEmpty());
		assertTrue(after.get("blyt/absent.bclyt", "Txt_Yes").isEmpty());
		assertEquals(70f, after.place(LAYOUT, "Pic_Fram").width());
	}

	@Test
	void paneLinksAreTakenForStringsWithNone() throws IOException {
		SampleRomfs.message(game, "tutorial_and_help", List.of("はい"));
		SampleRomfs.message(dlc, "tutorial_and_help", List.of("はい", "いいえ"));
		RomfsIndex g = RomfsIndex.scan(game);
		PaneRef yes = new PaneRef(LAYOUT, "Txt_Yes");
		PaneLinks.open(g).set(g.table("message/tutorial_and_help"), "000", List.of(yes));

		ParentImport.Result r = run();
		assertEquals(1, r.paneLinks());
		RomfsIndex index = RomfsIndex.scan(dlc);
		PaneLinks links = PaneLinks.open(index);
		StringTable help = index.table("message/tutorial_and_help");
		assertEquals(List.of(yes), links.get(help, "000"));
		assertTrue(links.get(help, "001").isEmpty());
	}

	@Test
	void fontSettingsAreTakenOnlyForFontsTheProjectHas() throws IOException {
		PatchSettings theirs = PatchSettings.open(game);
		theirs.setExtraSpace(FONT, "ty");
		theirs.setExtraSpace("other.bcfnt", "ab");
		PatchSettings mine = PatchSettings.open(dlc);
		mine.setTitleId("0004008C00123400");

		ParentImport.Result r = run();
		PatchSettings after = PatchSettings.open(dlc);
		assertEquals(1, r.fontSettings());
		assertEquals("ty", after.extraSpace(FONT));
		assertEquals("", after.extraSpace("other.bcfnt"));
		assertEquals("0004008C00123400", after.titleId(), "the DLC's own title id is not touched");
		assertFalse(after.copyToMods());
	}

	@Test
	void importingAgainTakesNothingMore() throws IOException {
		english(game, "menu", "menu_0001", "Yes");
		assertTrue(run().any());
		ParentImport.Result again = run();
		assertFalse(again.any());
		assertTrue(again.summary().startsWith("Nothing to import"));
	}
}
