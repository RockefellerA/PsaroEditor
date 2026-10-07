package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeColorsTest {

	@TempDir
	Path dir;

	private Path romfs() {
		return dir.resolve("romfs");
	}

	private Path file() {
		return dir.resolve("romfs.psaro/colors.json");
	}

	@Test
	void startsFromTheDefaultsAndWritesNothing() throws IOException {
		CodeColors c = CodeColors.open(romfs());
		assertEquals("GREEN", c.name(0x02));
		assertEquals(CodeColors.DEFAULTS.get(0x02).color(), c.color(0x02));
		assertNull(c.name(0x10));
		assertNull(c.color(0x04));
		assertEquals("WHITE", c.names().get(0x01));
		assertFalse(c.isChanged(0x02));
		assertFalse(Files.exists(file()));
	}

	@Test
	void changesAreSavedAndReadBack() throws IOException {
		CodeColors c = CodeColors.open(romfs());
		c.setColor(0x03, new Color(0x12, 0x34, 0x56));
		c.setName(0x10, "purple");
		assertEquals("{\n  \"03\": {\"color\": \"#123456\"},\n  \"10\": {\"name\": \"PURPLE\"}\n}\n",
				Files.readString(file(), StandardCharsets.UTF_8));
		CodeColors again = CodeColors.open(romfs());
		assertEquals(new Color(0x123456), again.color(0x03));
		assertEquals("ORANGE", again.name(0x03));
		assertEquals("PURPLE", again.name(0x10));
		assertTrue(again.isChanged(0x10));
	}

	@Test
	void anEmptyNameRemovesTheDefaultName() throws IOException {
		CodeColors c = CodeColors.open(romfs());
		c.setName(0x02, "");
		assertNull(c.name(0x02));
		assertNull(CodeColors.open(romfs()).name(0x02));
	}

	@Test
	void settingTheDefaultBackOrResettingRemovesTheFile() throws IOException {
		CodeColors c = CodeColors.open(romfs());
		c.setName(0x02, "LIME");
		c.setName(0x02, "GREEN");
		assertFalse(Files.exists(file()));
		c.setColor(0x02, Color.RED);
		c.reset(0x02);
		assertFalse(Files.exists(file()));
		assertEquals(CodeColors.DEFAULTS.get(0x02).color(), c.color(0x02));
	}

	@Test
	void badOrDuplicateNamesAreRefused() throws IOException {
		CodeColors c = CodeColors.open(romfs());
		assertThrows(IllegalArgumentException.class, () -> c.setName(0x10, "ORANGE"));
		assertThrows(IllegalArgumentException.class, () -> c.setName(0x10, "2TONE"));
		assertThrows(IllegalArgumentException.class, () -> c.setName(0x10, "LIGHT BLUE"));
		assertFalse(Files.exists(file()));
	}

	@Test
	void anEarlierPlainColorFileStillReads() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"02\": \"#FF0000\"}", StandardCharsets.UTF_8);
		CodeColors c = CodeColors.open(romfs());
		assertEquals(Color.RED, c.color(0x02));
		assertEquals("GREEN", c.name(0x02));
	}

	@Test
	void unreadableFileIsReported() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"02\": {\"color\": \"not a color\"}}", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> CodeColors.open(romfs()));
	}
}
