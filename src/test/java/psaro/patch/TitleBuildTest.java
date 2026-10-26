package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Cia;
import psaro.format.Ncch;
import psaro.format.Ncsd;
import psaro.format.RomfsImage;
import psaro.format.SampleCia;
import psaro.format.Smdh;

/** A patched copy of a title built from a decrypted one ({@link TitleBuild}), and the players' page for it ({@link PatcherPage}). */
class TitleBuildTest {

	private static final long GAME = 0x0004000000140000L;
	private static final long DLC = 0x0004008C00140000L;

	@TempDir
	Path dir;

	private static Map<String, byte[]> song(String title) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("contents.csv.lz", title.getBytes(StandardCharsets.UTF_16LE));
		files.put("music.dspadpcm.bcstm", new byte[3000]);
		return files;
	}

	private static Map<String, byte[]> gameFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("text/menu_Japanese.tdt", "はい".getBytes(StandardCharsets.UTF_16LE));
		files.put("font/main.arc.lz", new byte[6000]);
		files.put("movie/opening.moflex", new byte[9000]);
		return files;
	}

	/** A decrypted DLC of an index content and two songs, 009a (id 9e) and 009b (id 9f). */
	private Path dlc(boolean encrypted) throws IOException {
		Path file = dir.resolve(encrypted ? "dlc.cia" : "dlc decrypted.cia");
		Files.write(file, SampleCia.cia(DLC, List.of(
				new SampleCia.Content(0, 0, 0x4000, SampleCia.ncch(DLC, SampleCia.romfs(Map.of("index.bin", new byte[4])), false)),
				new SampleCia.Content(0x9e, 0x9a, encrypted ? 0x4001 : 0x4000,
						SampleCia.ncch(DLC, SampleCia.romfs(song("王宮のトランペット")), false)),
				new SampleCia.Content(0x9f, 0x9b, 0x4000, SampleCia.ncch(DLC, SampleCia.romfs(song("馬車のマーチ")), false))),
				new byte[0]));
		return file;
	}

	private Path patch(String folder, String path, String text) throws IOException {
		Path f = dir.resolve("DLC/romfs").resolve(folder).resolve(path);
		Files.createDirectories(f.getParent());
		Files.write(f, text.getBytes(StandardCharsets.UTF_16LE));
		return dir.resolve("DLC/romfs");
	}

	private static String text(byte[] d) {
		return new String(d, StandardCharsets.UTF_16LE);
	}

	/** Each patched DLC content is rebuilt with its folder's files; the others are copied as they were. */
	@Test
	void aDlcsPatchedContentsAreRebuilt() throws IOException {
		Path base = dlc(false);
		Path folder = patch("009a", "contents.csv.lz", "Royal Trumpet");
		// a content can be named by its id too
		patch("0000009f", "contents.csv.lz", "Wagon March");
		Path out = dir.resolve("dlc patched.cia");
		double[] last = {0};
		TitleBuild.Result r = TitleBuild.build(base, TitleBuild.dlcFiles(folder), out, share -> last[0] = share);
		assertEquals(new TitleBuild.Result(2, 2, Files.size(out), TitleBuild.Format.CIA), r);
		assertEquals(1.0, last[0]);

		Cia before = Cia.read(base);
		Cia after = Cia.read(out);
		assertArrayEquals(before.read(before.content(0)), after.read(after.content(0)));
		Map<String, byte[]> trumpet = RomfsImage.files(Ncch.romfs(after.read(after.content(0x9a))));
		assertEquals("Royal Trumpet", text(trumpet.get("contents.csv.lz")));
		assertArrayEquals(new byte[3000], trumpet.get("music.dspadpcm.bcstm"));
		assertEquals("Wagon March", text(RomfsImage.files(Ncch.romfs(after.read(after.content(0x9b)))).get("contents.csv.lz")));
		assertFalse(Files.exists(dir.resolve("dlc patched.cia.tmp")));
		assertEquals(new TitleBuild.Info(TitleBuild.Format.CIA, DLC, 0), TitleBuild.info(out));
	}

	/** A content still encrypted, a folder naming no content and a file the content lacks are all refused, and nothing is written. */
	@Test
	void whatCannotBeBuiltIsRefusedAtOnce() throws IOException {
		Path folder = patch("009a", "contents.csv.lz", "Royal Trumpet");
		patch("00ff", "contents.csv.lz", "Nothing");
		patch("009b", "extra.bin", "Not there");
		Path out = dir.resolve("out.cia");
		IOException e = assertThrows(IOException.class, () -> TitleBuild.build(dlc(true), TitleBuild.dlcFiles(folder), out, s -> { }));
		assertTrue(e.getMessage().contains("009a: content 009a of dlc.cia is encrypted"), e.getMessage());
		assertTrue(e.getMessage().contains("00ff: dlc.cia has no content"), e.getMessage());
		assertFalse(Files.exists(out));
		// with the content decrypted, the missing file is what stands in the way
		IOException missing = assertThrows(IOException.class,
				() -> TitleBuild.build(dlc(false), Map.of("009b", Map.of("extra.bin", new byte[1])), out, s -> { }));
		assertTrue(missing.getMessage().contains("content 009b: the romfs has no file extra.bin"), missing.getMessage());
		assertThrows(IOException.class, () -> TitleBuild.build(dlc(false), Map.of(), out, s -> { }));
	}

	/**
	 * A game card's image is rebuilt with its game partition grown: the manual and update after it move
	 * on with the gap before the update kept, the partition table and filled size follow, the card's
	 * size stays, and the card's padding is left off.
	 */
	@Test
	void aGameCardsImageIsRebuilt() throws IOException {
		byte[] game = SampleCia.ncch(GAME, SampleCia.romfs(gameFiles()), false);
		byte[] manual = SampleCia.ncch(0x0004000000140001L, SampleCia.romfs(Map.of("manual.bcma", new byte[100])), false);
		byte[] update = SampleCia.ncch(0x000400DB00017102L, SampleCia.romfs(Map.of("cup.bin", new byte[100])), false);
		Path base = dir.resolve("game.3ds");
		Files.write(base, SampleCia.ncsd(Map.of(0, game, 1, manual, 7, update), 0x1000, 1 << 20));
		assertEquals(new TitleBuild.Info(TitleBuild.Format.THREE_DS, GAME, null), TitleBuild.info(base));

		Path patchFolder = dir.resolve("game.psaro/romfs");
		Files.createDirectories(patchFolder.resolve("font"));
		Files.write(patchFolder.resolve("font/main.arc.lz"), new byte[20000]);
		Path out = dir.resolve("game patched.3ds");
		TitleBuild.Result r = TitleBuild.build(base, TitleBuild.gameFiles(patchFolder), out, s -> { });
		assertEquals(TitleBuild.Format.THREE_DS, r.format());
		assertEquals(1, r.files());

		Ncsd card = Ncsd.read(out);
		byte[] d = Files.readAllBytes(out);
		Ncsd.Partition p0 = card.partition(0), p1 = card.partition(1), p7 = card.partition(7);
		assertEquals(0x4000, p0.offset());
		assertTrue(p0.size() > game.length);
		assertEquals(p0.offset() + p0.size(), p1.offset());
		assertEquals(p1.offset() + p1.size() + 0x1000, p7.offset());
		assertArrayEquals(manual, Arrays.copyOfRange(d, (int) p1.offset(), (int) (p1.offset() + p1.size())));
		assertArrayEquals(update, Arrays.copyOfRange(d, (int) p7.offset(), (int) (p7.offset() + p7.size())));
		assertEquals(p7.offset() + p7.size(), d.length);
		ByteBuffer h = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(d.length, h.getInt(0x300));
		assertEquals((1 << 20) / 0x200, h.getInt(0x104));
		Map<String, byte[]> files = RomfsImage.files(Ncch.romfs(Arrays.copyOfRange(d, (int) p0.offset(), (int) (p0.offset() + p0.size()))));
		assertArrayEquals(new byte[20000], files.get("font/main.arc.lz"));
		assertArrayEquals(new byte[9000], files.get("movie/opening.moflex"));
		assertEquals(new TitleBuild.Info(TitleBuild.Format.THREE_DS, GAME, null), TitleBuild.info(out));

		// grown past its card: the next card size up
		Files.write(patchFolder.resolve("font/main.arc.lz"), new byte[1 << 20]);
		TitleBuild.build(base, TitleBuild.gameFiles(patchFolder), out, s -> { });
		assertEquals((2 << 20) / 0x200, ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN).getInt(0x104));
	}

	/** A game's CIA is rebuilt as its content 0, the rest of it copied. */
	@Test
	void aGamesCiaIsRebuilt() throws IOException {
		byte[] game = SampleCia.ncch(GAME, SampleCia.romfs(gameFiles()), false);
		byte[] manual = SampleCia.ncch(GAME, SampleCia.romfs(Map.of("manual.bcma", new byte[100])), false);
		Path base = dir.resolve("game.cia");
		Files.write(base, SampleCia.cia(GAME, List.of(new SampleCia.Content(0, 0, 0, game), new SampleCia.Content(1, 1, 0x4000, manual)),
				new byte[0x100]));
		Path out = dir.resolve("game patched.cia");
		TitleBuild.build(base, Map.of(TitleBuild.GAME, Map.of("text/menu_Japanese.tdt", "Yes".getBytes(StandardCharsets.UTF_16LE))),
				out, s -> { });
		Cia cia = Cia.read(out);
		assertEquals("Yes", text(RomfsImage.files(Ncch.romfs(cia.read(cia.content(0)))).get("text/menu_Japanese.tdt")));
		assertArrayEquals(manual, cia.read(cia.content(1)));
	}

	/** A game's NCCH with an ExeFS of code and its SMDH, as a game has. */
	private static byte[] gameWithExefs() {
		Map<String, byte[]> exefs = new LinkedHashMap<>();
		byte[] code = new byte[0x300];
		Arrays.fill(code, (byte) 7);
		exefs.put(".code", code);
		exefs.put("icon", SampleCia.smdh("シアトリズム ドラゴンクエスト", "SQUARE ENIX"));
		return SampleCia.ncch(GAME, SampleCia.romfs(gameFiles()), false, exefs);
	}

	/** The ExeFS of {@code ncch}: its file {@code name}'s hash, and the header's in the NCCH's, are those of what it holds. */
	private static void assertExefsHashed(byte[] ncch, int index, String name) throws IOException {
		byte[] file = Ncch.exefsFile(psaro.format.Source.of(ncch), 0, name);
		assertArrayEquals(sha(file), Arrays.copyOfRange(ncch, 0x200 + 0x1E0 - 0x20 * index, 0x200 + 0x200 - 0x20 * index), name);
		assertArrayEquals(sha(Arrays.copyOfRange(ncch, 0x200, 0x400)), Arrays.copyOfRange(ncch, 0x1C0, 0x1E0), "the ExeFS header");
	}

	private static byte[] sha(byte[] d) throws IOException {
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(d);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	/**
	 * A game's CIA given a HOME Menu title has it in every language of its SMDH but Japanese, its
	 * ExeFS's hashes following, its code and icons kept; with no files of its own patched, its
	 * content 0 is rebuilt for the title alone.
	 */
	@Test
	void aGamesCiaIsGivenItsHomeMenuTitle() throws IOException {
		byte[] game = gameWithExefs();
		Path base = dir.resolve("game.cia");
		Files.write(base, SampleCia.cia(GAME, List.of(new SampleCia.Content(0, 0, 0, game)), new byte[0]));
		byte[] own = TitleBuild.smdh(base);
		assertEquals(new Smdh.Title("シアトリズム ドラゴンクエスト", "シアトリズム ドラゴンクエスト", "SQUARE ENIX"), Smdh.title(own, 1));

		Path out = dir.resolve("game patched.cia");
		TitleBuild.Result r = TitleBuild.build(base, Map.of(), new Smdh.Title("Theatrhythm DQ", "Theatrhythm Dragon Quest", ""),
				out, s -> { });
		assertEquals(1, r.contents());
		assertEquals(0, r.files());
		Cia cia = Cia.read(out);
		byte[] after = cia.read(cia.content(0));
		byte[] smdh = TitleBuild.smdh(out);
		for (int language = Smdh.ENGLISH; language < Smdh.LANGUAGES; language++) {
			assertEquals("Theatrhythm Dragon Quest", Smdh.title(smdh, language).longTitle());
		}
		assertEquals(Smdh.title(own, Smdh.JAPANESE), Smdh.title(smdh, Smdh.JAPANESE), "Japanese keeps the game's");
		assertEquals("SQUARE ENIX", Smdh.title(smdh, Smdh.ENGLISH).publisher(), "a blank publisher keeps the game's");
		assertArrayEquals(Arrays.copyOfRange(own, 0x2040, Smdh.SIZE), Arrays.copyOfRange(smdh, 0x2040, Smdh.SIZE));
		assertArrayEquals(Ncch.exefsFile(psaro.format.Source.of(game), 0, ".code"),
				Ncch.exefsFile(psaro.format.Source.of(after), 0, ".code"));
		assertExefsHashed(after, 1, "icon");
		assertExefsHashed(after, 0, ".code");
		assertArrayEquals(new byte[9000], RomfsImage.files(Ncch.romfs(after)).get("movie/opening.moflex"));

		// none, or a blank one, leaves the SMDH as it was
		TitleBuild.build(base, Map.of(TitleBuild.GAME, Map.of("font/main.arc.lz", new byte[1])), new Smdh.Title("", null, " "),
				out, s -> { });
		assertArrayEquals(own, TitleBuild.smdh(out));
	}

	/** A game card's image is given its HOME Menu title in its game partition, with its files. */
	@Test
	void aGameCardsImageIsGivenItsHomeMenuTitle() throws IOException {
		Path base = dir.resolve("game.3ds");
		Files.write(base, SampleCia.ncsd(Map.of(0, gameWithExefs()), 0, 1 << 20));
		Path out = dir.resolve("game patched.3ds");
		TitleBuild.build(base, Map.of(TitleBuild.GAME, Map.of("font/main.arc.lz", new byte[20000])),
				new Smdh.Title("Theatrhythm DQ", null, null), out, s -> { });
		assertEquals("Theatrhythm DQ", Smdh.title(TitleBuild.smdh(out), 1).shortTitle());
		Ncsd.Partition p = Ncsd.read(out).partition(0);
		byte[] d = Files.readAllBytes(out);
		byte[] after = Arrays.copyOfRange(d, (int) p.offset(), (int) (p.offset() + p.size()));
		assertExefsHashed(after, 1, "icon");
		assertArrayEquals(new byte[20000], RomfsImage.files(Ncch.romfs(after)).get("font/main.arc.lz"));
	}

	/** A title too long for the SMDH, or a game with no SMDH, stops the build before anything is written. */
	@Test
	void aHomeMenuTitleThatCannotBeWrittenStopsTheBuild() throws IOException {
		Path base = dir.resolve("game.cia");
		Files.write(base, SampleCia.cia(GAME, List.of(new SampleCia.Content(0, 0, 0, gameWithExefs())), new byte[0]));
		Path out = dir.resolve("out.cia");
		IOException tooLong = assertThrows(IOException.class,
				() -> TitleBuild.build(base, Map.of(), new Smdh.Title("x".repeat(64), null, null), out, s -> { }));
		assertTrue(tooLong.getMessage().contains("room for 63"), tooLong.getMessage());

		Path bare = dir.resolve("bare.cia");
		Files.write(bare, SampleCia.cia(GAME, List.of(new SampleCia.Content(0, 0, 0, SampleCia.ncch(GAME,
				SampleCia.romfs(gameFiles()), false))), new byte[0]));
		assertNull(TitleBuild.smdh(bare));
		IOException none = assertThrows(IOException.class,
				() -> TitleBuild.build(bare, Map.of(), new Smdh.Title("TDQ", null, null), out, s -> { }));
		assertTrue(none.getMessage().contains("the ExeFS has no icon"), none.getMessage());
		assertFalse(Files.exists(out));
	}

	/** Nothing patched for a game, no files; a game's patch folder is its content 0's. */
	@Test
	void aGamesFilesAreItsContentZeros() throws IOException {
		assertEquals(Map.of(), TitleBuild.gameFiles(dir.resolve("none")));
		Files.createDirectories(dir.resolve("p/text"));
		Files.write(dir.resolve("p/text/a.tdt"), new byte[] {1});
		Files.write(dir.resolve("p/text/b.tdt.tmp"), new byte[] {2});
		assertEquals(List.of("0000"), List.copyOf(TitleBuild.gameFiles(dir.resolve("p")).keySet()));
		assertEquals(List.of("text/a.tdt"), List.copyOf(TitleBuild.gameFiles(dir.resolve("p")).get("0000").keySet()));
	}

	/** A patched copy goes beside its decrypted one, "decrypted" giving way to "patched", in its format. */
	@Test
	void aPatchedCopyIsNamedForItsDecryptedOne() {
		assertEquals(dir.resolve("TDQDLC patched.cia"), TitleBuild.patchedPath(dir.resolve("TDQDLC decrypted.cia"),
				TitleBuild.Format.CIA));
		assertEquals(dir.resolve("TDQ patched.3ds"), TitleBuild.patchedPath(dir.resolve("TDQ.CCI"), TitleBuild.Format.THREE_DS));
		assertEquals(dir.resolve("game patched.cia"), TitleBuild.patchedPath(dir.resolve("game"), TitleBuild.Format.CIA));
	}

	/**
	 * The players' page holds each title's patched files with its title id and version, a title with
	 * nothing patched left out; nothing in it can end its data early.
	 */
	@Test
	void thePlayersPageHoldsEachTitle() throws IOException {
		PatcherPage.Written written = PatcherPage.write(dir.resolve("export"), "Songs </script> patcher", List.of(
				new PatcherPage.Title(PatcherPage.Kind.GAME, "0004000000140000", null, Map.of()),
				new PatcherPage.Title(PatcherPage.Kind.DLC, "0004008C00140000", 48,
						Map.of("009a", Map.of("contents.csv.lz", "Royal Trumpet".getBytes(StandardCharsets.UTF_16LE))))));
		List<PatchExport.Extra> extras = PatcherPage.inFolder(written);
		assertEquals(List.of("DLC Patcher/Patcher.html", "DLC Patcher/README.txt"), extras.stream().map(PatchExport.Extra::destination).toList());
		String html = Files.readString(extras.get(0).source());
		String data = html.substring(html.indexOf("id=\"patch\">") + "id=\"patch\">".length());
		data = data.substring(0, data.indexOf("</script>"));
		org.json.JSONObject patch = new org.json.JSONObject(data);
		assertEquals("Songs </script> patcher", patch.getString("name"));
		assertEquals(1, patch.getJSONArray("titles").length());
		org.json.JSONObject title = patch.getJSONArray("titles").getJSONObject(0);
		assertEquals("dlc", title.getString("kind"));
		assertEquals("0004008C00140000", title.getString("titleId"));
		assertEquals(48, title.getInt("version"));
		byte[] file = java.util.Base64.getDecoder().decode(title.getJSONObject("contents").getJSONObject("009a").getString("contents.csv.lz"));
		assertEquals("Royal Trumpet", text(file));
		assertFalse(html.contains("{{PATCH}}"));
		assertTrue(html.contains("delete the game's Ext Save Data with FBI"), "the page's DLC note");
		String readme = Files.readString(extras.get(1).source());
		assertTrue(readme.contains("The DLC (title id 0004008C00140000)"), readme);
		assertTrue(readme.contains("delete the game's Ext Save Data with FBI"), readme);
		assertFalse(readme.contains("The game (title id"), readme);
		String own = Files.readString(PatcherPage.inFolder(written, "The DLC ({titleId})\n  NOTE: Japan.\n", "0004008c00140000")
				.get(1).source());
		assertEquals("The DLC (0004008C00140000)\r\n  NOTE: Japan.\r\n", own, "a project's own, its title id filled in");

		assertNull(PatcherPage.write(dir.resolve("export2"), "x",
				List.of(new PatcherPage.Title(PatcherPage.Kind.GAME, "0004000000140000", null, Map.of()))));
	}

	/** A game with only its HOME Menu title to give still has a page, its blank parts left out; its readme says so. */
	@Test
	void thePlayersPageHoldsTheHomeMenuTitle() throws IOException {
		PatcherPage.Written written = PatcherPage.write(dir.resolve("export"), "TDQ patcher", List.of(
				new PatcherPage.Title(PatcherPage.Kind.GAME, "0004000000140000", null, Map.of(),
						new Smdh.Title("Theatrhythm DQ", "Theatrhythm Dragon Quest", " "))));
		String html = Files.readString(written.page());
		String data = html.substring(html.indexOf("id=\"patch\">") + "id=\"patch\">".length());
		org.json.JSONObject title = new org.json.JSONObject(data.substring(0, data.indexOf("</script>")))
				.getJSONArray("titles").getJSONObject(0);
		org.json.JSONObject home = title.getJSONObject("homeTitle");
		assertEquals("Theatrhythm DQ", home.getString("short"));
		assertEquals("Theatrhythm Dragon Quest", home.getString("long"));
		assertFalse(home.has("publisher"));
		assertTrue(written.readme().contains("its English title on the HOME Menu"), written.readme());
	}

	/**
	 * The patcher download holds the page and its readme, and nothing of the Luma3DS patch; its readme
	 * tells the game's patched copy from the Luma3DS patch.
	 */
	@Test
	void thePatcherDownloadHoldsOnlyThePage() throws IOException {
		PatcherPage.Written written = PatcherPage.write(dir.resolve("export"), "Songs patcher", List.of(
				new PatcherPage.Title(PatcherPage.Kind.GAME, "0004000000140000", null,
						Map.of(TitleBuild.GAME, Map.of("text/a.tdt", new byte[] {1})))));
		Path zip = dir.resolve("Songs patcher.zip");
		PatchExport.Result r = PatchExport.writeFiles("0004000000140000", written.readme(),
				List.of(new PatchExport.Extra(written.page(), PatcherPage.PAGE)), zip, (step, share) -> { });
		assertEquals(1, r.extras());
		List<String> names = new java.util.ArrayList<>();
		String readme;
		try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
			z.stream().forEach(e -> names.add(e.getName()));
			readme = new String(z.getInputStream(z.getEntry("README.txt")).readAllBytes(), StandardCharsets.UTF_8);
		}
		assertEquals(List.of("README.txt", "Patcher.html"), names);
		assertTrue(readme.contains("The game (title id 0004000000140000)"), readme);
		assertTrue(readme.contains("instead of the Luma3DS patch"), readme);
		assertTrue(readme.endsWith(PatchExport.FOOTER + "\r\n"), readme);
	}
}
