package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Tdt;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;

class TranslationsTest {

	@TempDir
	Path dir;
	private Path romfs;
	private RomfsIndex index;
	private StringTable config;

	@BeforeEach
	void romfs() throws IOException {
		romfs = dir.resolve("My Romfs");
		Files.createDirectories(romfs.resolve("text"));
		LinkedHashMap<String, String> strings = new LinkedHashMap<>();
		strings.put("cftp_2000", "せってい");
		strings.put("cftp_1000", "なまえ");
		strings.put("cftp_3000", "おわる");
		Files.write(romfs.resolve("text/config_Japanese.tdt"), Tdt.write(strings));
		index = RomfsIndex.scan(romfs);
		config = index.table("config");
	}

	private Path file() {
		return dir.resolve("My Romfs.psaro/english/config.json");
	}

	@Test
	void folderSitsBesideTheRomfs() {
		assertEquals(dir.resolve("My Romfs.psaro"), Translations.folderFor(romfs));
	}

	@Test
	void openingWritesNothing() throws IOException {
		Translations t = Translations.open(index);
		assertNull(t.get(config, "cftp_1000"));
		assertFalse(t.isDirty());
		assertFalse(Files.exists(t.folder()));
	}

	@Test
	void savedTextReadsBackWithLineBreaksAndColourCodes() throws IOException {
		Translations t = Translations.open(index);
		String tricky = "\u0002\u0001Change the \u0002\u0002name\u0002\u0001\nof the log. \"Quotes\" \\ é";
		t.set(config, "cftp_1000", tricky);
		t.set(config, "cftp_3000", "Quit");
		assertEquals(Set.of("config"), t.dirtyTables());
		t.save();
		assertFalse(t.isDirty());

		Translations again = Translations.open(index);
		assertEquals(tricky, again.get(config, "cftp_1000"));
		assertEquals("Quit", again.get(config, "cftp_3000"));
		assertEquals(2, again.translatedCount(config));
	}

	@Test
	void fileHoldsOnlyTranslatedStringsInTableOrderAndNoJapanese() throws IOException {
		Translations t = Translations.open(index);
		t.set(config, "cftp_3000", "Quit");
		t.set(config, "cftp_2000", "Settings");
		t.save();
		assertEquals("{\n  \"cftp_2000\": \"Settings\",\n  \"cftp_3000\": \"Quit\"\n}\n",
				Files.readString(file(), StandardCharsets.UTF_8));
	}

	@Test
	void settingTheSameTextOrClearingNothingIsNotAChange() throws IOException {
		Translations t = Translations.open(index);
		t.set(config, "cftp_1000", "");
		assertFalse(t.isDirty());
		t.set(config, "cftp_1000", "Name");
		t.save();
		t.set(config, "cftp_1000", "Name");
		assertFalse(t.isDirty());
	}

	@Test
	void clearingTheLastTranslationRemovesTheFile() throws IOException {
		Translations t = Translations.open(index);
		t.set(config, "cftp_1000", "Name");
		t.save();
		assertTrue(Files.exists(file()));
		t.set(config, "cftp_1000", null);
		t.save();
		assertFalse(Files.exists(file()));
	}

	@Test
	void keysTheTableNoLongerHasSurviveASave() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"cftp_1000\": \"Name\", \"gone_0001\": \"Old work\"}", StandardCharsets.UTF_8);
		Translations t = Translations.open(index);
		t.set(config, "cftp_2000", "Settings");
		t.save();
		String saved = Files.readString(file(), StandardCharsets.UTF_8);
		assertTrue(saved.contains("\"gone_0001\": \"Old work\""), saved);
		assertTrue(saved.indexOf("cftp_2000") < saved.indexOf("cftp_1000"), "table order: " + saved);
		assertEquals(2, t.translatedCount(config));
	}

	@Test
	void unreadableFileIsReportedNotIgnored() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{ not json", StandardCharsets.UTF_8);
		IOException e = assertThrows(IOException.class, () -> Translations.open(index));
		assertTrue(e.getMessage().contains("config.json"), e.getMessage());
	}
}
