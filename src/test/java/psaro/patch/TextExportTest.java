package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Tdt;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;

class TextExportTest {

	@TempDir
	Path dir;
	private Path romfs;
	private RomfsIndex index;
	private Translations translations;
	private StringTable menu;

	@BeforeEach
	void sample() throws IOException {
		romfs = dir.resolve("game");
		LinkedHashMap<String, String> strings = new LinkedHashMap<>();
		strings.put("menu_0001", "はい");
		strings.put("menu_0002", "いいえ");
		strings.put("menu_0003", "？？？");
		Files.createDirectories(romfs.resolve("text"));
		Files.write(romfs.resolve("text/menu_Japanese.tdt"), Tdt.write(strings));
		index = RomfsIndex.scan(romfs);
		translations = Translations.open(index);
		menu = index.table("menu");
	}

	private Path output() {
		return dir.resolve("game.psaro/romfs/text/menu_Japanese.tdt");
	}

	@Test
	void writesTheTableWithItsEnglishAndTheRestInJapanese() throws IOException {
		translations.set(menu, "menu_0001", "Yes");
		translations.setKeepsJapanese(menu, "menu_0003", true);
		assertEquals(List.of(output()), TextExport.export(index, translations));

		Map<String, String> written = Tdt.read(Files.readAllBytes(output()));
		assertEquals(List.of("menu_0001", "menu_0002", "menu_0003"), List.copyOf(written.keySet()));
		assertEquals("Yes", written.get("menu_0001"));
		assertEquals("いいえ", written.get("menu_0002"));
		assertEquals("？？？", written.get("menu_0003"));

		assertTrue(TextExport.export(index, translations).isEmpty(), "an unchanged table is not rewritten");
	}

	@Test
	void aTableWithoutEnglishHasNoCopy() throws IOException {
		translations.set(menu, "menu_0001", "Yes");
		TextExport.export(index, translations);
		translations.set(menu, "menu_0001", null);
		translations.setKeepsJapanese(menu, "menu_0003", true);
		assertTrue(TextExport.export(index, translations).isEmpty());
		assertFalse(Files.exists(output()));
	}

	@Test
	void changedTablesAreCopiedToTheModsFolderWhenAsked() throws IOException {
		PatchSettings settings = PatchSettings.open(romfs);
		FontPatcher patcher = new FontPatcher(index, settings, LayoutOverrides.open(romfs));
		translations.set(menu, "menu_0001", "Yes");
		List<Path> wrote = TextExport.export(index, translations);

		patcher.copyToMods(wrote, step -> { });
		Path mods = dir.resolve("mods/romfs");
		assertFalse(Files.exists(mods));
		settings.setCopyToMods(true, mods);
		patcher.copyToMods(wrote, step -> { });
		assertEquals("Yes", Tdt.read(Files.readAllBytes(mods.resolve("text/menu_Japanese.tdt"))).get("menu_0001"));
	}
}
