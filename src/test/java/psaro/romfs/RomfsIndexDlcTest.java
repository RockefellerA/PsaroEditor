package psaro.romfs;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.romfs.RomfsIndex.Kind;
import psaro.romfs.RomfsIndex.StringTable;

/** A DLC read from its folder of content romfs folders ({@link RomfsIndex#scanDlc}). */
class RomfsIndexDlcTest {

	@TempDir
	Path dir;

	private Path dlc() throws IOException {
		Path dlc = dir.resolve("TDQDLC_romfs");
		SampleRomfs.content(dlc, "009a", List.of("0501_FMS_003,0,True,王宮のトランペット,ドラゴンクエストV 天空の花嫁"));
		SampleRomfs.content(dlc, "009b", List.of("0401_FMS_003,0,True,馬車のマーチ,ドラゴンクエストIV 導かれし者たち"));
		// a placeholder content, its romfs empty, as most of a DLC's are
		Files.createDirectories(dlc.resolve("0001"));
		return dlc;
	}

	/** Each content's compressed data table is read, named for its folder so the like-named files stay apart. */
	@Test
	void eachContentsTableIsNamedForItsFolder() throws IOException {
		Path dlc = dlc();
		RomfsIndex index = RomfsIndex.scanDlc(dlc, List.of("009a", "009b"));
		assertEquals(List.of("009a/contents", "009b/contents"), index.tables().stream().map(StringTable::name).toList());
		StringTable song = index.table("009a/contents");
		assertEquals(Kind.DATA, song.kind());
		assertEquals(Map.of("0501_FMS_003:3", "王宮のトランペット", "0501_FMS_003:4", "ドラゴンクエストV 天空の花嫁"),
				song.strings());
		assertEquals(dlc.resolve("009a/contents.csv.lz"), song.path());
		assertTrue(index.dlc());
		assertEquals(List.of(dlc.resolve("009a"), dlc.resolve("009b")), index.folders());
	}

	/** A decompressed copy left beside a content's compressed table is not read: the compressed one is the game's. */
	@Test
	void theCompressedTableWinsOverADecompressedCopy() throws IOException {
		Path dlc = dlc();
		Files.write(dlc.resolve("009a/contents.csv"), RomfsIndex.readTable(dlc.resolve("009a/contents.csv.lz")));
		RomfsIndex index = RomfsIndex.scanDlc(dlc, List.of("009a"));
		assertEquals(List.of("009a/contents"), index.tables().stream().map(StringTable::name).toList());
		assertEquals(dlc.resolve("009a/contents.csv.lz"), index.table("009a/contents").path());
	}

	/** Only the contents chosen are read. */
	@Test
	void onlyTheChosenContentsAreRead() throws IOException {
		RomfsIndex index = RomfsIndex.scanDlc(dlc(), List.of("009b"));
		assertEquals(List.of("009b/contents"), index.tables().stream().map(StringTable::name).toList());
		assertThrows(IllegalArgumentException.class, () -> RomfsIndex.scanDlc(dir, List.of()));
	}

	/** The content folders are those with something to read; a placeholder's empty romfs is not one. */
	@Test
	void contentFoldersLeaveOutEmptyOnes() throws IOException {
		Path dlc = dlc();
		assertEquals(List.of("009a", "009b"), RomfsIndex.contentFolders(dlc));
		assertTrue(RomfsIndex.looksLikeContent(dlc.resolve("009a")));
		assertFalse(RomfsIndex.looksLikeContent(dlc.resolve("0001")));
		assertEquals(List.of(), RomfsIndex.contentFolders(dir.resolve("nowhere")));
	}

	/** A game's romfs is read as it always was: a data table at its top is not one of its tables. */
	@Test
	void aGamesTopLevelDataTableIsNotRead() throws IOException {
		Path game = dir.resolve("game");
		SampleRomfs.data(game, "MusicTable", List.of("0001_BMS_001,0,True,戦闘のテーマ"));
		SampleRomfs.content(dir, "game", List.of("0501_FMS_003,0,True,王宮のトランペット"));
		RomfsIndex index = RomfsIndex.scan(game);
		assertEquals(List.of("table/MusicTable"), index.tables().stream().map(StringTable::name).toList());
		assertNull(index.table("contents"));
		assertFalse(index.dlc());
		assertEquals(List.of(game), index.folders());
	}

	/** A compressed table reads as its plain bytes; a plain one as it is. */
	@Test
	void readTableDecompresses() throws IOException {
		Path dlc = dlc();
		byte[] plain = SampleRomfs.csv(List.of("0501_FMS_003,0,True,王宮のトランペット,ドラゴンクエストV 天空の花嫁"));
		assertArrayEquals(plain, RomfsIndex.readTable(dlc.resolve("009a/contents.csv.lz")));
		assertTrue(RomfsIndex.compressed(dlc.resolve("009a/contents.csv.lz")));
		Files.write(dir.resolve("plain.csv"), plain);
		assertArrayEquals(plain, RomfsIndex.readTable(dir.resolve("plain.csv")));
		assertFalse(RomfsIndex.compressed(dir.resolve("plain.csv")));
	}
}
