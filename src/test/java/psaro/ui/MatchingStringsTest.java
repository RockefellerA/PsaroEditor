package psaro.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Bclyt.TextOverride;
import psaro.patch.LayoutOverrides;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.SampleRomfs;

class MatchingStringsTest {

	@TempDir
	Path dir;

	/**
	 * Three 戻る buttons: menu's, the one translated; plaza's in a box of the same shape; shop's in a
	 * box drawn in another font.
	 */
	@Test
	void theSameJapaneseTakesTheEnglishAndSameShapedBoxesTakeThePaneChanges() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "戻る"));
		SampleRomfs.table(romfs, "plaza", Map.of("plza_0001", "戻る", "plza_0002", "進む"));
		SampleRomfs.table(romfs, "shop", Map.of("shop_0001", "戻る"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu_btn.bclyt", List.of("a.bcfnt", "b.bcfnt"),
				Map.of(), SampleRomfs.pane("Txt_Back", "menu_0001"));
		SampleRomfs.archive(romfs, "scene/plaza/plaza.arc.lz", "blyt/plaza_btn.bclyt", List.of("a.bcfnt", "b.bcfnt"),
				Map.of(), SampleRomfs.pane("Txt_Btn", "plza_0001"), SampleRomfs.pane("Txt_Next", "plza_0002"));
		SampleRomfs.archive(romfs, "scene/shop/shop.arc.lz", "blyt/shop_btn.bclyt", List.of("c.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_Back", "shop_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		Translations translations = Translations.open(index);
		LayoutOverrides overrides = LayoutOverrides.open(romfs);
		var menu = index.table("menu");
		translations.set(menu, "menu_0001", "Back");
		TextOverride smaller = new TextOverride(null, null, 14f, 14f, null, null, "b.bcfnt");
		overrides.set("blyt/menu_btn.bclyt", List.of("Txt_Back"), smaller);

		List<MatchingStrings.Match> found = MatchingStrings.find(index, translations, overrides, menu, "menu_0001");
		assertEquals(List.of("plza_0001", "shop_0001"), found.stream().map(MatchingStrings.Match::key).toList());
		assertTrue(found.get(0).sameBoxes() && found.get(0).untranslated());
		assertFalse(found.get(1).sameBoxes(), "drawn in another font");

		MatchingStrings.apply(index, translations, overrides, menu, "menu_0001", found);
		assertEquals("Back", translations.get(index.table("plaza"), "plza_0001"));
		assertEquals("Back", translations.get(index.table("shop"), "shop_0001"));
		assertEquals(null, translations.get(index.table("plaza"), "plza_0002"), "other Japanese is left alone");
		assertEquals(smaller, overrides.get("blyt/plaza_btn.bclyt", "Txt_Btn"), "the same-shaped box takes the change");
		assertTrue(overrides.get("blyt/shop_btn.bclyt", "Txt_Back").isEmpty(), "another shape takes only the English");
		assertTrue(overrides.get("blyt/plaza_btn.bclyt", "Txt_Next").isEmpty());

		// all read the same now: nothing left to offer
		assertTrue(MatchingStrings.find(index, translations, overrides, menu, "menu_0001").isEmpty());
	}

	@Test
	void aStringKeptInJapaneseHandsOnTheMark() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "ＯＫ", "menu_0002", "ＯＫ"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"), Map.of(),
				SampleRomfs.pane("Txt_A", "menu_0001"), SampleRomfs.pane("Txt_B", "menu_0002"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		Translations translations = Translations.open(index);
		LayoutOverrides overrides = LayoutOverrides.open(romfs);
		var menu = index.table("menu");
		translations.setKeepsJapanese(menu, "menu_0001", true);

		List<MatchingStrings.Match> found = MatchingStrings.find(index, translations, overrides, menu, "menu_0001");
		assertEquals(1, found.size());
		MatchingStrings.apply(index, translations, overrides, menu, "menu_0001", found);
		assertTrue(translations.keepsJapanese(menu, "menu_0002"));
	}
}
