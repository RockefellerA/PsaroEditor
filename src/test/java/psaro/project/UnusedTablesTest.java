package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.romfs.RomfsIndex.StringTable;

class UnusedTablesTest {

	@TempDir
	Path dir;

	private static StringTable table(String name) {
		return new StringTable(name, Path.of(name + "_Japanese.tdt"), Map.of());
	}

	private Path file() {
		return dir.resolve("romfs.psaro/unused-tables.json");
	}

	@Test
	void nothingIsUnusedUntilMarkedAndOpeningWritesNothing() throws IOException {
		UnusedTables u = UnusedTables.open(dir.resolve("romfs"));
		assertFalse(u.isUnused(table("config_select")));
		assertFalse(Files.exists(file()));
	}

	@Test
	void marksAreSavedSortedAndReadBack() throws IOException {
		UnusedTables u = UnusedTables.open(dir.resolve("romfs"));
		u.setUnused(table("config_select"), true);
		u.setUnused(table("config_naming"), true);
		assertEquals("[\n  \"config_naming\",\n  \"config_select\"\n]\n", Files.readString(file(), StandardCharsets.UTF_8));
		UnusedTables again = UnusedTables.open(dir.resolve("romfs"));
		assertTrue(again.isUnused(table("config_select")));
		assertEquals(List.of("config"),
				again.inUse(List.of(table("config"), table("config_naming"), table("config_select"))).stream()
						.map(StringTable::name).toList());
	}

	@Test
	void unmarkingTheLastTableRemovesTheFile() throws IOException {
		UnusedTables u = UnusedTables.open(dir.resolve("romfs"));
		u.setUnused(table("config_select"), true);
		u.setUnused(table("config_select"), false);
		assertFalse(Files.exists(file()));
	}

	@Test
	void unreadableFileIsReported() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{not a list", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> UnusedTables.open(dir.resolve("romfs")));
	}
}
