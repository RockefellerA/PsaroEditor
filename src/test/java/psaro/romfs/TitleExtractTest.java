package psaro.romfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.SampleCia;
import psaro.patch.TitleBuild;

/** A decrypted title's romfs extracted into folders beside it ({@link TitleExtract}). */
class TitleExtractTest {

	private static final long GAME = 0x0004000000140000L;
	private static final long DLC = 0x0004008C00140000L;

	@TempDir
	Path dir;

	private static Map<String, byte[]> gameFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("text/menu_Japanese.tdt", "はい".getBytes(StandardCharsets.UTF_16LE));
		files.put("font/main.arc.lz", new byte[6000]);
		files.put("movie/opening.moflex", new byte[9000]);
		files.put("top.bin", new byte[] {1, 2, 3});
		return files;
	}

	private static byte[] game(Map<String, byte[]> files, boolean encrypted) {
		return SampleCia.ncch(GAME, SampleCia.romfs(files), encrypted);
	}

	private static byte[] manual() {
		return SampleCia.ncch(GAME | 1, SampleCia.romfs(Map.of("Manual.bcma", new byte[100])), false);
	}

	private static void assertFolderHolds(Path folder, Map<String, byte[]> files) throws IOException {
		try (var walk = Files.walk(folder)) {
			assertEquals(files.size(), walk.filter(Files::isRegularFile).count());
		}
		for (Map.Entry<String, byte[]> f : files.entrySet()) {
			assertArrayEquals(f.getValue(), Files.readAllBytes(folder.resolve(f.getKey())), f.getKey());
		}
	}

	/** A game card's image gives the game's romfs and its manual's beside it, its update left out. */
	@Test
	void aGameCardsRomfsIsExtractedBesideIt() throws IOException {
		byte[] update = SampleCia.ncch(0x000400DB00017102L, SampleCia.romfs(Map.of("cup.bin", new byte[100])), false);
		Path file = dir.resolve("TDQ decrypted.3ds");
		Files.write(file, SampleCia.ncsd(Map.of(0, game(gameFiles(), false), 1, manual(), 7, update), 0x1000, 1 << 20));

		TitleExtract.Plan plan = TitleExtract.plan(file);
		assertEquals(TitleExtract.Kind.GAME, plan.kind());
		assertEquals("0004000000140000", plan.id());
		assertEquals(dir.resolve("TDQ"), plan.opens());
		assertEquals(List.of(dir.resolve("TDQ"), dir.resolve("TDQ manual")), plan.pieces().stream().map(TitleExtract.Piece::folder).toList());
		assertEquals(List.of(), plan.occupied());

		double[] last = {0};
		TitleExtract.Result r = TitleExtract.extract(plan, share -> last[0] = share);
		assertEquals(5, r.files());
		assertEquals(6000 + 9000 + 3 + 4 + 100, r.bytes());
		assertEquals(1.0, last[0]);
		assertFolderHolds(dir.resolve("TDQ"), gameFiles());
		assertFolderHolds(dir.resolve("TDQ manual"), Map.of("Manual.bcma", new byte[100]));
		assertTrue(RomfsIndex.looksLikeRomfs(dir.resolve("TDQ")));
		assertEquals(List.of(dir.resolve("TDQ"), dir.resolve("TDQ manual")), plan.occupied());

		// again over the first: the files are replaced, anything else is left
		Files.write(dir.resolve("TDQ/top.bin"), new byte[] {9});
		Files.write(dir.resolve("TDQ/mine.txt"), new byte[] {7});
		TitleExtract.extract(plan, share -> { });
		assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(dir.resolve("TDQ/top.bin")));
		assertArrayEquals(new byte[] {7}, Files.readAllBytes(dir.resolve("TDQ/mine.txt")));
	}

	/** A game's CIA gives the same folders as its card would; a lone NCCH gives its one. */
	@Test
	void aGamesCiaAndALoneNcchAreExtracted() throws IOException {
		Path cia = dir.resolve("game.cia");
		Files.write(cia, SampleCia.cia(GAME, List.of(new SampleCia.Content(0, 0, 0, game(gameFiles(), false)),
				new SampleCia.Content(1, 1, 0x4000, manual())), new byte[0x100]));
		TitleExtract.extract(TitleExtract.plan(cia), share -> { });
		assertFolderHolds(dir.resolve("game"), gameFiles());
		assertFolderHolds(dir.resolve("game manual"), Map.of("Manual.bcma", new byte[100]));

		Path cxi = dir.resolve("other.cxi");
		Files.write(cxi, game(Map.of("a/b.bin", new byte[] {5}), false));
		TitleExtract.Plan plan = TitleExtract.plan(cxi);
		assertEquals(dir.resolve("other"), plan.opens());
		TitleExtract.extract(plan, share -> { });
		assertFolderHolds(dir.resolve("other"), Map.of("a/b.bin", new byte[] {5}));
	}

	/** A DLC's CIA gives a folder of content folders, named as Open romfs → DLC and the patched build take them. */
	@Test
	void aDlcsContentsEachGetAFolder() throws IOException {
		Path file = dir.resolve("TDQDLC decrypted.cia");
		Files.write(file, SampleCia.cia(DLC, List.of(
				new SampleCia.Content(0, 0, 0x4000, SampleCia.ncch(DLC, SampleCia.romfs(Map.of("index.bin", new byte[4])), false)),
				new SampleCia.Content(0x9e, 0x9a, 0x4000, SampleCia.ncch(DLC, SampleCia.romfs(Map.of("song.bcstm", new byte[300])), false))),
				new byte[0]));
		TitleExtract.Plan plan = TitleExtract.plan(file);
		assertEquals(TitleExtract.Kind.DLC, plan.kind());
		assertEquals(dir.resolve("TDQDLC"), plan.opens());
		TitleExtract.extract(plan, share -> { });
		assertFolderHolds(dir.resolve("TDQDLC/0000"), Map.of("index.bin", new byte[4]));
		assertFolderHolds(dir.resolve("TDQDLC/009a"), Map.of("song.bcstm", new byte[300]));

		// the folders name the contents a patched copy is built with
		TitleBuild.Result r = TitleBuild.build(file, TitleBuild.dlcFiles(dir.resolve("TDQDLC")), dir.resolve("TDQDLC patched.cia"),
				s -> { });
		assertEquals(2, r.contents());
	}

	/** An encrypted title is refused before anything is written, every reason at once. */
	@Test
	void anEncryptedTitleIsRefused() throws IOException {
		Path card = dir.resolve("enc.3ds");
		Files.write(card, SampleCia.ncsd(Map.of(0, game(gameFiles(), true), 1, manual()), 0, 1 << 20));
		IOException e = assertThrows(IOException.class, () -> TitleExtract.plan(card));
		assertTrue(e.getMessage().contains("partition 0000 (the game) is encrypted"), e.getMessage());

		Path cia = dir.resolve("enc.cia");
		Files.write(cia, SampleCia.cia(DLC, List.of(new SampleCia.Content(0, 0, 0x4001, game(gameFiles(), false)),
				new SampleCia.Content(1, 1, 0x4001, game(gameFiles(), false))), new byte[0]));
		IOException both = assertThrows(IOException.class, () -> TitleExtract.plan(cia));
		assertTrue(both.getMessage().contains("content 0000 is encrypted"), both.getMessage());
		assertTrue(both.getMessage().contains("content 0001 is encrypted"), both.getMessage());
		assertFalse(Files.exists(dir.resolve("enc")));

		Path junk = dir.resolve("junk.cia");
		Files.write(junk, new byte[0x400]);
		assertTrue(assertThrows(IOException.class, () -> TitleExtract.plan(junk)).getMessage().contains("is not a .3ds, a CIA"));
	}

	/** A romfs naming a file outside its folder is refused before anything is written. */
	@Test
	void aFileNamedOutsideItsFolderIsRefused() throws IOException {
		Path file = dir.resolve("bad.cxi");
		Files.write(file, game(Map.of("ok.bin", new byte[1], "../escape.bin", new byte[1]), false));
		TitleExtract.Plan plan = TitleExtract.plan(file);
		IOException e = assertThrows(IOException.class, () -> TitleExtract.extract(plan, s -> { }));
		assertTrue(e.getMessage().contains("../escape.bin"), e.getMessage());
		assertFalse(Files.exists(dir.resolve("escape.bin")));
		assertFalse(Files.exists(dir.resolve("bad")));
	}

	/** The folders are named for the file, without its extension or a "decrypted" at its end. */
	@Test
	void theFoldersAreNamedForTheFile() {
		assertEquals("TDQ", TitleExtract.stem("TDQ.3ds"));
		assertEquals("TDQDLC", TitleExtract.stem("TDQDLC decrypted.cia"));
		assertEquals("Theatrhythm DQ (Japan)", TitleExtract.stem("Theatrhythm DQ (Japan) (Decrypted).CCI"));
		assertEquals("game", TitleExtract.stem("game.decrypted.cxi"));
		assertEquals("romfs", TitleExtract.stem("decrypted.cia"));
	}
}
