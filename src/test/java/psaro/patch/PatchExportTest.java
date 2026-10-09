package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchExportTest {

	@TempDir
	Path dir;

	@Test
	void theTitleIdIsReadFromACitraModsFolder() {
		assertEquals("0004000000140000",
				PatchExport.titleIdFrom(Path.of("C:/Citra/load/mods/0004000000140000/romfs")));
		assertEquals("000400000014000A", PatchExport.titleIdFrom(Path.of("/mods/000400000014000a/romfs")), "upper-cased");
		assertNull(PatchExport.titleIdFrom(Path.of("C:/somewhere/romfs")));
		assertTrue(PatchExport.isTitleId("0004000000140000"));
		assertFalse(PatchExport.isTitleId("00040000001400"));
		assertFalse(PatchExport.isTitleId("000400000014000G"));
	}

	/** Every patched file under Luma's folders for the title, a readme beside them, an interrupted write's leftovers out. */
	@Test
	void thePatchFolderIsPackedInLumasLayout() throws IOException {
		Path patch = dir.resolve("game.psaro/romfs");
		Files.createDirectories(patch.resolve("scene/menu"));
		Files.createDirectories(patch.resolve("text"));
		Files.createDirectories(patch.resolve("message"));
		Files.write(patch.resolve("scene/menu/menu.arc.lz"), new byte[] {1, 2, 3});
		Files.write(patch.resolve("text/menu_Japanese.tdt"), new byte[] {4, 5});
		Files.write(patch.resolve("message/main_menu_jp.mdt"), new byte[] {6});
		Files.write(patch.resolve("text/menu_Japanese.tdt.tmp"), new byte[] {9});
		Path zip = dir.resolve("out/game patch.zip");
		Files.createDirectories(zip.getParent());

		PatchExport.Result r = PatchExport.write(patch, "0004000000140000", zip, step -> { });
		assertEquals(3, r.files());
		assertEquals(6, r.bytes());
		Map<String, byte[]> entries = new TreeMap<>();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null;) {
				entries.put(e.getName(), in.readAllBytes());
			}
		}
		String root = "luma/titles/0004000000140000/romfs/";
		assertEquals(java.util.Set.of("README.txt", root + "scene/menu/menu.arc.lz", root + "text/menu_Japanese.tdt",
				root + "message/main_menu_jp.mdt"), entries.keySet());
		assertArrayEquals(new byte[] {1, 2, 3}, entries.get(root + "scene/menu/menu.arc.lz"));
		assertTrue(new String(entries.get("README.txt"), java.nio.charset.StandardCharsets.UTF_8)
				.contains("/luma/titles/0004000000140000/romfs/"));
		assertFalse(Files.exists(zip.resolveSibling("game patch.zip.tmp")), "written beside it, then moved");
	}

	@Test
	void nothingPatchedYetIsNotExported() throws IOException {
		Path patch = Files.createDirectories(dir.resolve("game.psaro/romfs"));
		assertThrows(IOException.class, () -> PatchExport.write(patch, "0004000000140000", dir.resolve("p.zip"), s -> { }));
		assertThrows(IllegalArgumentException.class, () -> PatchExport.write(patch, "nope", dir.resolve("p.zip"), s -> { }));
	}

	@Test
	void theTitleIdIsRemembered() throws IOException {
		Path romfs = dir.resolve("game");
		PatchSettings.open(romfs).setTitleId("0004000000140000");
		assertEquals("0004000000140000", PatchSettings.open(romfs).titleId());
	}
}
