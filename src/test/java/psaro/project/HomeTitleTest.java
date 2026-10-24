package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Smdh;

class HomeTitleTest {

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("romfs.psaro/home-title.json");
	}

	/** A title is kept with its blank parts left out, read back with them empty; none gives null. */
	@Test
	void aTitleIsKeptAndReadBack() throws IOException {
		Path romfs = dir.resolve("romfs");
		assertNull(HomeTitle.of(romfs));
		HomeTitle.set(romfs, new Smdh.Title("Theatrhythm DQ", "Theatrhythm Dragon Quest", " "));
		assertTrue(Files.readString(file()).contains("\"long\": \"Theatrhythm Dragon Quest\""));
		assertFalse(Files.readString(file()).contains("publisher"));
		assertEquals(new Smdh.Title("Theatrhythm DQ", "Theatrhythm Dragon Quest", ""), HomeTitle.of(romfs));
	}

	/** A blank title, or none, gives the game its own back: the file goes. */
	@Test
	void aBlankTitleRemovesTheFile() throws IOException {
		Path romfs = dir.resolve("romfs");
		HomeTitle.set(romfs, new Smdh.Title("TDQ", null, null));
		assertTrue(Files.exists(file()));
		HomeTitle.set(romfs, new Smdh.Title("", " ", null));
		assertFalse(Files.exists(file()));
		HomeTitle.set(romfs, null);
		assertNull(HomeTitle.of(romfs));
	}

	@Test
	void aBrokenFileIsAnError() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{ not json");
		assertThrows(IOException.class, () -> HomeTitle.of(dir.resolve("romfs")));
	}
}
