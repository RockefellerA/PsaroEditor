package psaro.romfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Tdt;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.romfs.RomfsIndex.Usage;

class RomfsIndexTest {

	@TempDir
	Path romfs;

	/**
	 * {@code menu.arc.lz} shows one string from its own table, one from {@code common} (which it
	 * lacks), and one key no table has. {@code other} also holds {@code menu_0001}, which must not
	 * steal the pane from {@code menu}.
	 */
	private RomfsIndex sample() throws IOException {
		table("menu", Map.of("menu_0001", "はい", "menu_0002", "いいえ"));
		table("common", Map.of("comm_0001", "決定"));
		table("other", Map.of("menu_0001", "べつ"));
		archive("scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"),
				pane("Txt_Yes", "menu_0001"), pane("Txt_Enter", "comm_0001"), pane("Txt_Gone", "gone_9999"));
		return RomfsIndex.scan(romfs);
	}

	@Test
	void readsEveryStringTable() throws IOException {
		RomfsIndex index = sample();
		assertEquals(List.of("common", "menu", "other"), index.tables().stream().map(StringTable::name).toList());
		assertEquals("いいえ", index.table("menu").strings().get("menu_0002"));
		assertEquals(4, index.stringCount());
		assertEquals(1, index.layoutCount());
	}

	@Test
	void paneResolvesToItsArchivesOwnTableFirst() throws IOException {
		RomfsIndex index = sample();
		List<Usage> yes = index.usages(index.table("menu"), "menu_0001");
		assertEquals(1, yes.size());
		assertEquals("blyt/menu.bclyt", yes.get(0).layout());
		assertEquals("Txt_Yes", yes.get(0).pane().name());
		assertEquals("a.bcfnt", yes.get(0).fontName());
		assertTrue(index.usages(index.table("other"), "menu_0001").isEmpty());
	}

	@Test
	void aKeyShownOnlyThroughAnotherTablesCopyBorrowsItsPanes() throws IOException {
		RomfsIndex index = sample();
		StringTable other = index.table("other");
		assertEquals("menu", index.sharedWith(other, "menu_0001").name());
		assertEquals(List.of("Txt_Yes"), index.panes(other, "menu_0001").stream().map(u -> u.pane().name()).toList());
		// the table the pane is matched to shares with nobody; a key no pane shows has nothing to borrow
		assertNull(index.sharedWith(index.table("menu"), "menu_0001"));
		assertNull(index.sharedWith(index.table("menu"), "menu_0002"));
		assertTrue(index.panes(index.table("menu"), "menu_0002").isEmpty());
	}

	@Test
	void keyTheOwnTableLacksResolvesToTheTableThatHasIt() throws IOException {
		RomfsIndex index = sample();
		assertEquals("Txt_Enter", index.usages(index.table("common"), "comm_0001").get(0).pane().name());
	}

	@Test
	void stringsNoLayoutShowsHaveNoUsagesAndUnknownKeysAreReported() throws IOException {
		RomfsIndex index = sample();
		assertTrue(index.usages(index.table("menu"), "menu_0002").isEmpty());
		assertEquals(List.of("Txt_Gone"), index.unresolved().stream().map(u -> u.pane().name()).toList());
	}

	@Test
	void fontTheArchiveDoesNotCarryIsNull() throws IOException {
		RomfsIndex index = sample();
		assertNull(index.font(index.usages(index.table("menu"), "menu_0001").get(0)));
	}

	/**
	 * A rebuilt copy in {@code build/romfs} and a test copy with its own string tables carry the
	 * same layout; only the root's own archive counts.
	 */
	@Test
	void romfsCopiesNestedInsideAreLeftOut() throws IOException {
		sample();
		archive("build/romfs/scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"), pane("Txt_Yes", "menu_0001"));
		archive("test_swap/scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"), pane("Txt_Yes", "menu_0001"));
		Files.createDirectories(romfs.resolve("test_swap/text"));
		Files.write(romfs.resolve("test_swap/text/menu_Japanese.tdt"), Tdt.write(new LinkedHashMap<>(Map.of("menu_0001", "はい"))));
		RomfsIndex index = RomfsIndex.scan(romfs);
		List<Usage> yes = index.usages(index.table("menu"), "menu_0001");
		assertEquals(1, yes.size());
		assertEquals(romfs.resolve("scene/menu/menu.arc.lz"), yes.get(0).archive());
		assertEquals(1, index.layoutCount());
	}

	@Test
	void panesOfTheSameShapeAreGroupedWithTheirJapaneseAcrossArchives() throws IOException {
		sample();
		archive("scene/common/common.arc.lz", "blyt/ok.bclyt", List.of("a.bcfnt"), pane("Txt_Ok", "comm_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		Usage yes = index.usages(index.table("menu"), "menu_0001").get(0);
		List<String> shown = index.sameShape(yes).stream().map(RomfsIndex.Shown::japanese).sorted().toList();
		assertEquals(List.of("はい", "決定", "決定"), shown);
		assertTrue(index.sameShape(yes) == index.sameShape(index.usages(index.table("common"), "comm_0001").get(0)));
	}

	/**
	 * One text drawn in three layers of three fonts, as the tutorial titles are: each lower layer's
	 * font is under the top one's. A pane of another string at the same place is not a layer.
	 */
	@Test
	void aFontDrawnUnderAnotherInOneTextsLayersKnowsTheTopOne() throws IOException {
		table("menu", Map.of("menu_0001", "はい", "menu_0002", "いいえ"));
		byte[] middle = pane("Txt_Fr", "menu_0001");
		middle[0x52] = 2;
		byte[] top = pane("Txt_Bd", "menu_0001");
		top[0x52] = 1;
		archive("scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt", "b.bcfnt", "c.bcfnt"),
				pane("Txt_Bk", "menu_0001"), middle, top, pane("Txt_Alt", "menu_0002"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		assertEquals("b.bcfnt", index.layeredUnder("a.bcfnt"));
		assertEquals("b.bcfnt", index.layeredUnder("c.bcfnt"));
		assertNull(index.layeredUnder("b.bcfnt"), "drawn on top");
	}

	/**
	 * A message file is a table of numbered strings no layout names; a pane the game fills from
	 * code (its key in no table) can be linked to one, and then shows it.
	 */
	@Test
	void messageFilesAreTablesOfNumberedStringsPanesCanBeLinkedTo() throws IOException {
		table("menu", Map.of("menu_0001", "はい"));
		SampleRomfs.message(romfs, "tutorial_and_help", List.of("チュートリアル", "はい"));
		archive("scene/music/InfoTutorialButton.arc.lz", "blyt/tutorial_btn_yes.bclyt", List.of("a.bcfnt"),
				pane("Txt_Btn", "trhl_1690"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		StringTable help = index.table("message/tutorial_and_help");
		assertTrue(help.message());
		assertFalse(index.table("menu").message());
		assertEquals(Map.of("000", "チュートリアル", "001", "はい"), help.strings());
		assertEquals(List.of("000", "001"), List.copyOf(help.strings().keySet()));
		assertTrue(index.usages(help, "001").isEmpty());
		assertNull(index.sharedWith(help, "001"));
		assertEquals(List.of("Txt_Btn"), index.unresolved().stream().map(u -> u.pane().name()).toList());
		assertEquals(1, index.textPanes().size());

		index.link(help, "001", List.of(new RomfsIndex.PaneRef("blyt/tutorial_btn_yes.bclyt", "Txt_Btn")));
		assertEquals(List.of("Txt_Btn"), index.panes(help, "001").stream().map(u -> u.pane().name()).toList());
		assertTrue(index.usages(help, "000").isEmpty(), "only the string linked");
		index.link(help, "001", List.of());
		assertTrue(index.usages(help, "001").isEmpty());
	}

	/**
	 * Text a layout holds itself, where no table fills the pane, is a string: one per text in a
	 * layout, every pane holding it showing it. Placeholders and panes a table fills are left out.
	 */
	@Test
	void textALayoutHoldsWhereNoTableFillsThePaneIsAString() throws IOException {
		table("menu", Map.of("menu_0001", "はい"));
		archive("scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"),
				SampleRomfs.pane("Txt_Filled", "menu_0001", "いいえ"), SampleRomfs.pane("Txt_Star", null, "*"));
		archive("scene/music/InfoTutorialButton.arc.lz", "blyt/tutorial_btn_yes.bclyt", List.of("a.bcfnt"),
				SampleRomfs.pane("Txt_Btn", "trhl_1690", "はい"), SampleRomfs.pane("Txt_Btn_Shad", null, "はい"),
				SampleRomfs.pane("Txt_Again", "menu_0001", "もう一度"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		StringTable held = index.table(RomfsIndex.BUILT_IN);
		assertTrue(held.builtIn());
		// the button's key is in no table, its shadow has none, and the third's archive has no table of its own
		assertEquals(Map.of("blyt/tutorial_btn_yes.bclyt:Txt_Btn", "はい", "blyt/tutorial_btn_yes.bclyt:Txt_Again",
				"もう一度"), held.strings());
		assertEquals(List.of("Txt_Btn", "Txt_Btn_Shad"),
				index.usages(held, "blyt/tutorial_btn_yes.bclyt:Txt_Btn").stream().map(u -> u.pane().name()).toList());
	}

	@Test
	void folderWithoutStringTablesIsNotARomfs() throws IOException {
		assertFalse(RomfsIndex.looksLikeRomfs(romfs));
		table("menu", Map.of("menu_0001", "はい"));
		assertTrue(RomfsIndex.looksLikeRomfs(romfs));
	}

	/** Against a real romfs when one is supplied, as RomfsRoundTripTest does. */
	@Test
	void realRomfsResolvesNearlyEveryKeyAndFindsFonts() throws IOException {
		String dir = System.getProperty("psaro.romfs", System.getenv("PSARO_ROMFS"));
		assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "no romfs supplied; set -Dpsaro.romfs");
		RomfsIndex index = RomfsIndex.scan(Path.of(dir));
		int resolved = 0;
		Usage withFont = null;
		for (StringTable table : index.tables()) {
			for (String key : table.strings().keySet()) {
				for (Usage u : index.usages(table, key)) {
					resolved++;
					if (withFont == null && !u.fontName().equals("cbf_std.bcfnt")) {
						withFont = u;
					}
				}
			}
		}
		assertTrue(resolved > 0, "no pane resolved to a string");
		assertTrue(index.unresolved().size() * 20 < resolved, index.unresolved().size() + " unresolved of " + resolved);
		assertTrue(withFont != null && index.font(withFont) != null, "no pane's font found in its archive");
	}

	// ── Synthetic romfs ──────────────────────────────────────────────────────

	private void table(String name, Map<String, String> strings) throws IOException {
		SampleRomfs.table(romfs, name, strings);
	}

	private void archive(String path, String layoutPath, List<String> fonts, byte[]... panes) throws IOException {
		SampleRomfs.archive(romfs, path, layoutPath, fonts, Map.of(), panes);
	}

	private static byte[] pane(String name, String key) {
		return SampleRomfs.pane(name, key);
	}
}
