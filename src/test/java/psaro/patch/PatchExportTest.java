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
import java.util.List;
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
		String readme = new String(entries.get("README.txt"), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(readme.contains("/luma/titles/0004000000140000/romfs/"));
		assertTrue(readme.contains("https://github.com/RockefellerA/PsaroEditor"), "credits the editor");
		assertFalse(Files.exists(zip.resolveSibling("game patch.zip.tmp")), "written beside it, then moved");
	}

	private static Map<String, byte[]> entries(Path zip) throws IOException {
		Map<String, byte[]> entries = new TreeMap<>();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null;) {
				entries.put(e.getName(), in.readAllBytes());
			}
		}
		return entries;
	}

	private Path patchFolder() throws IOException {
		Path patch = dir.resolve("game.psaro/romfs");
		Files.createDirectories(patch.resolve("message"));
		Files.write(patch.resolve("message/error_text_jp.mdt"), new byte[] {6});
		return patch;
	}

	/**
	 * A project's own readme goes in, its title id filled in and its lines ending CRLF; its added
	 * files go where they say, beside the romfs folder by default, read as they are now.
	 */
	@Test
	void aProjectsReadmeAndAddedFilesArePacked() throws IOException {
		Path patch = patchFolder();
		Files.createDirectories(dir.resolve("fix"));
		Path ips = Files.write(dir.resolve("fix/code.ips"), new byte[] {'P', 'A', 'T', 'C', 'H'});
		Path notes = Files.write(dir.resolve("fix/notes.txt"), new byte[] {1, 2});
		String readme = "Theatrhythm in English\nPut code.ips beside romfs in /luma/titles/{titleId}/\n";
		List<PatchExport.Extra> extras = List.of(new PatchExport.Extra(ips, PatchExport.defaultDestination(ips)),
				new PatchExport.Extra(notes, "docs\\notes.txt"));
		Path zip = dir.resolve("p.zip");

		PatchExport.Result r = PatchExport.write(patch, "0004000000140000", readme, extras, zip, step -> { });
		assertEquals(1, r.files());
		assertEquals(2, r.extras());
		assertEquals(1 + 5 + 2, r.bytes());
		Map<String, byte[]> entries = entries(zip);
		assertEquals(java.util.Set.of("README.txt", "luma/titles/0004000000140000/romfs/message/error_text_jp.mdt",
				"luma/titles/0004000000140000/code.ips", "docs/notes.txt"), entries.keySet());
		assertEquals("Theatrhythm in English\r\nPut code.ips beside romfs in /luma/titles/0004000000140000/\r\n\r\n"
				+ PatchExport.FOOTER + "\r\n", new String(entries.get("README.txt"), java.nio.charset.StandardCharsets.UTF_8));
		assertArrayEquals(new byte[] {'P', 'A', 'T', 'C', 'H'}, entries.get("luma/titles/0004000000140000/code.ips"));
	}

	/**
	 * Nothing is written while an added file is missing, or its path in the zip is not a plain
	 * one inside it or is taken by the readme, a patched file or another added file.
	 */
	@Test
	void anAddedFileThatCannotGoInStopsTheExport() throws IOException {
		Path patch = patchFolder();
		Path ips = Files.write(dir.resolve("code.ips"), new byte[] {1});
		Path gone = dir.resolve("exheader.bin");
		List<PatchExport.Extra> extras = List.of(new PatchExport.Extra(gone, PatchExport.defaultDestination(gone)),
				new PatchExport.Extra(ips, "../code.ips"), new PatchExport.Extra(ips, "C:/code.ips"),
				new PatchExport.Extra(ips, "readme.TXT"),
				new PatchExport.Extra(ips, "luma/titles/{titleId}/romfs/message/error_text_jp.mdt"),
				new PatchExport.Extra(ips, "a/code.ips"), new PatchExport.Extra(ips, "a/code.ips"));
		List<String> problems = PatchExport.problems(patch, "0004000000140000", extras);
		assertEquals(6, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).contains("exheader.bin is not there"));
		assertTrue(problems.stream().anyMatch(p -> p.contains("\"../code.ips\" is not")));
		assertTrue(problems.stream().anyMatch(p -> p.contains("\"C:/code.ips\" is not")));
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("readme.TXT is in the zip already")));
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("luma/titles/0004000000140000/romfs/message/error_text_jp.mdt is in")));
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("a/code.ips is in the zip already")));
		assertEquals(List.of("No title id: it has sixteen hex digits, 0-9 and A-F."), PatchExport.problems(patch, "", extras));

		Path zip = dir.resolve("p.zip");
		assertThrows(IOException.class, () -> PatchExport.write(patch, "0004000000140000", "x", extras, zip, s -> { }));
		assertFalse(Files.exists(zip));
		assertFalse(Files.exists(dir.resolve("p.zip.tmp")));
	}

	/**
	 * Every readme ends with the footer, once: the default one and a project's own, which holds
	 * none of it, even when it was written or saved with it.
	 */
	@Test
	void everyReadmeEndsWithTheFooterOnce() throws IOException {
		assertFalse(PatchExport.defaultReadme().contains(PatchExport.FOOTER), "not part of the text to edit");
		assertEquals("Mine\n\n" + PatchExport.FOOTER + "\n", PatchExport.withFooter("Mine\n"));
		assertEquals("Mine\n\n" + PatchExport.FOOTER + "\n", PatchExport.withFooter("Mine\r\n\r\n" + PatchExport.FOOTER + "\r\n\r\n"));
		assertEquals(PatchExport.FOOTER + "\n", PatchExport.withFooter(""));
		assertTrue(PatchExport.readme("0004000000140000").endsWith("copied.\r\n\r\n" + PatchExport.FOOTER + "\r\n"));

		Path romfs = dir.resolve("game");
		ExportSettings s = ExportSettings.open(romfs);
		s.setReadme("Mine\n\n" + PatchExport.FOOTER + "\n");
		assertEquals("Mine\n", s.readme());
		Files.writeString(dir.resolve("game.psaro/" + ExportSettings.README_FILE), "Edited elsewhere\r\n" + PatchExport.FOOTER);
		assertEquals("Edited elsewhere\n", ExportSettings.open(romfs).readme(), "an editor's copy with the footer keeps it once");
	}

	/** The readme and the added files are kept per project; the default readme's text is no readme of its own. */
	@Test
	void theReadmeAndAddedFilesAreKeptForTheProject() throws IOException {
		Path romfs = dir.resolve("game");
		ExportSettings s = ExportSettings.open(romfs);
		assertEquals(PatchExport.defaultReadme(), s.readme());
		assertFalse(s.ownReadme());
		assertTrue(s.extras().isEmpty());

		s.setReadme("Mine for {titleId}\r\n");
		Path ips = dir.resolve("fix/code.ips");
		s.setExtras(List.of(new PatchExport.Extra(ips, PatchExport.defaultDestination(ips))));
		ExportSettings again = ExportSettings.open(romfs);
		assertEquals("Mine for {titleId}\n", again.readme());
		assertTrue(again.ownReadme());
		assertTrue(Files.isRegularFile(dir.resolve("game.psaro/" + ExportSettings.README_FILE)), "editable on its own");
		assertEquals(List.of(new PatchExport.Extra(ips, "luma/titles/{titleId}/code.ips")), again.extras());

		again.setReadme(PatchExport.defaultReadme().replace("\n", "\r\n"));
		assertFalse(again.ownReadme());
		assertFalse(Files.exists(dir.resolve("game.psaro/" + ExportSettings.README_FILE)));
		assertFalse(ExportSettings.open(romfs).ownReadme());
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
