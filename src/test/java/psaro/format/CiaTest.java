package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
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

/** A romfs image, the NCCH holding it and the CIA holding those, read and rebuilt ({@link RomfsImage}, {@link Ncch}, {@link Cia}). */
class CiaTest {

	private static final long DLC = 0x0004008C00140000L;

	@TempDir
	Path dir;

	private static Map<String, byte[]> songFiles(String csv) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("contents.csv.lz", csv.getBytes(StandardCharsets.UTF_16LE));
		files.put("music.bcstm", new byte[5000]);
		files.put("icons/1.icn", new byte[] {1, 2, 3});
		files.put("trigger000.bytes.lz", new byte[] {9, 9, 9, 9, 9});
		return files;
	}

	/** Every level hashes the blocks of the next, the master hash level 1's, as an image must. */
	private static void assertHashTree(byte[] image) {
		ByteBuffer h = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
		int master = h.getInt(8);
		long[] size = new long[3];
		for (int k = 0; k < 3; k++) {
			size[k] = h.getLong(0x0C + k * 0x18 + 8);
		}
		int lv3 = align(0x60 + master, 0x1000);
		int lv1 = align(lv3 + (int) size[2], 0x1000);
		int lv2 = align(lv1 + (int) size[0], 0x1000);
		assertHashes(Arrays.copyOfRange(image, lv1, lv1 + (int) size[0]), Arrays.copyOfRange(image, 0x60, 0x60 + master));
		assertHashes(Arrays.copyOfRange(image, lv2, lv2 + (int) size[1]), Arrays.copyOfRange(image, lv1, lv1 + (int) size[0]));
		assertHashes(Arrays.copyOfRange(image, lv3, lv3 + (int) size[2]), Arrays.copyOfRange(image, lv2, lv2 + (int) size[1]));
		assertEquals(0, image.length % 0x1000);
	}

	private static void assertHashes(byte[] level, byte[] hashes) {
		int blocks = (level.length + 0xFFF) / 0x1000;
		assertEquals(blocks * 32, hashes.length);
		for (int b = 0; b < blocks; b++) {
			byte[] block = Arrays.copyOf(Arrays.copyOfRange(level, b * 0x1000, Math.min(level.length, (b + 1) * 0x1000)), 0x1000);
			assertArrayEquals(SampleCia.sha(block), Arrays.copyOfRange(hashes, b * 32, b * 32 + 32), "block " + b);
		}
	}

	private static int align(int x, int a) {
		return (x + a - 1) / a * a;
	}

	/** An image's files are read by path, and rebuilt with nothing replaced it is the same image. */
	@Test
	void anImageIsReadAndRebuiltAsItWas() {
		Map<String, byte[]> files = songFiles("0501_FMS_003,王宮のトランペット");
		byte[] image = SampleCia.romfs(files);
		assertHashTree(image);
		Map<String, byte[]> read = RomfsImage.files(image);
		assertEquals(List.of("contents.csv.lz", "music.bcstm", "trigger000.bytes.lz", "icons/1.icn"), List.copyOf(read.keySet()));
		files.forEach((path, data) -> assertArrayEquals(data, read.get(path), path));
		assertArrayEquals(image, RomfsImage.replace(image, Map.of()));
	}

	/** A replaced file, longer than it was, moves the files after it; the rest read as they were and the hashes hold. */
	@Test
	void aReplacedFileIsReadBack() {
		Map<String, byte[]> files = songFiles("0501_FMS_003,王宮のトランペット");
		byte[] english = "0501_FMS_003,Royal Trumpet of the Palace Courtyard".getBytes(StandardCharsets.UTF_16LE);
		byte[] image = RomfsImage.replace(SampleCia.romfs(files), Map.of("contents.csv.lz", english));
		assertHashTree(image);
		Map<String, byte[]> read = RomfsImage.files(image);
		assertArrayEquals(english, read.get("contents.csv.lz"));
		for (String other : List.of("music.bcstm", "icons/1.icn", "trigger000.bytes.lz")) {
			assertArrayEquals(files.get(other), read.get(other), other);
		}
		assertThrows(IllegalArgumentException.class, () -> RomfsImage.replace(image, Map.of("nowhere.bin", new byte[1])));
		assertThrows(IllegalArgumentException.class, () -> RomfsImage.files(new byte[0x100]));
	}

	/** An NCCH's romfs is replaced, its sizes and superblock hash following; an encrypted one is refused. */
	@Test
	void anNcchsRomfsIsReplaced() {
		byte[] romfs = SampleCia.romfs(songFiles("a"));
		byte[] ncch = SampleCia.ncch(DLC, romfs, false);
		assertTrue(Ncch.decrypted(ncch));
		assertEquals(DLC, Ncch.programId(ncch));
		assertArrayEquals(romfs, Ncch.romfs(ncch));
		assertArrayEquals(ncch, Ncch.withRomfs(ncch, romfs));

		byte[] bigger = RomfsImage.replace(romfs, Map.of("music.bcstm", new byte[9000]));
		byte[] out = Ncch.withRomfs(ncch, bigger);
		assertEquals(0x1000 + bigger.length, out.length);
		assertArrayEquals(SampleCia.ncch(DLC, bigger, false), out);

		byte[] encrypted = SampleCia.ncch(DLC, romfs, true);
		assertFalse(Ncch.decrypted(encrypted));
		assertThrows(IllegalArgumentException.class, () -> Ncch.romfs(encrypted));
	}

	/** A CIA is written with one content replaced: its TMD's size and hash match, every other byte as it was. */
	@Test
	void aCiaIsWrittenWithAContentReplaced() throws IOException {
		byte[] first = SampleCia.ncch(DLC, SampleCia.romfs(Map.of("index.bin", new byte[] {1})), false);
		byte[] song = SampleCia.ncch(DLC, SampleCia.romfs(songFiles("a")), false);
		byte[] meta = "META".getBytes(StandardCharsets.US_ASCII);
		Path file = dir.resolve("dlc.cia");
		Files.write(file, SampleCia.cia(DLC, List.of(new SampleCia.Content(0, 0, 0x4000, first),
				new SampleCia.Content(0x9e, 0x9a, 0x4000, song)), meta));

		Cia cia = Cia.read(file);
		assertEquals(DLC, cia.titleId());
		assertEquals(List.of(0, 0x9a), cia.contents().stream().map(Cia.Content::index).toList());
		assertArrayEquals(song, cia.read(cia.content(0x9a)));

		// nothing replaced: the very same file
		ByteArrayOutputStream same = new ByteArrayOutputStream();
		cia.write(same, Map.of(), n -> { });
		assertArrayEquals(Files.readAllBytes(file), same.toByteArray());

		byte[] patched = Ncch.withRomfs(song, RomfsImage.replace(Ncch.romfs(song), Map.of("music.bcstm", new byte[9000])));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		cia.write(out, Map.of(0x9a, Cia.whole(patched)), n -> { });
		byte[] expected = SampleCia.cia(DLC, List.of(new SampleCia.Content(0, 0, 0x4000, first),
				new SampleCia.Content(0x9e, 0x9a, 0x4000, patched)), meta);
		assertArrayEquals(expected, out.toByteArray());
	}

	/** A content flagged encrypted in the TMD is decrypted once replaced: its flag is cleared. */
	@Test
	void aReplacedContentIsNoLongerFlaggedEncrypted() throws IOException {
		byte[] song = SampleCia.ncch(DLC, SampleCia.romfs(songFiles("a")), false);
		Path file = dir.resolve("dlc.cia");
		Files.write(file, SampleCia.cia(DLC, List.of(new SampleCia.Content(0x9e, 0x9a, 0x4001, song)), new byte[0]));
		Cia cia = Cia.read(file);
		assertTrue(cia.content(0x9a).encrypted());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		cia.write(out, Map.of(0x9a, Cia.whole(song)), n -> { });
		Path again = dir.resolve("again.cia");
		Files.write(again, out.toByteArray());
		assertFalse(Cia.read(again).content(0x9a).encrypted());
	}

	/**
	 * Every content of a real decrypted DLC CIA, rebuilt with nothing replaced, is the same bytes,
	 * so the rebuild lays the romfs out as the game's tools did. Run only when one is supplied
	 * ({@code -Dpsaro.dlcCia=<file>} or {@code PSARO_DLC_CIA}).
	 */
	@Test
	void aRealDlcsContentsRebuildAsTheyWere() throws IOException {
		String supplied = System.getProperty("psaro.dlcCia", System.getenv("PSARO_DLC_CIA"));
		assumeTrue(supplied != null && Files.isRegularFile(Path.of(supplied)), "no decrypted DLC CIA supplied; set -Dpsaro.dlcCia");
		Cia cia = Cia.read(Path.of(supplied));
		for (Cia.Content c : cia.contents()) {
			byte[] ncch = cia.read(c);
			assertArrayEquals(ncch, Ncch.withRomfs(ncch, RomfsImage.replace(Ncch.romfs(ncch), Map.of())),
					String.format("content %04x", c.index()));
		}
	}
}
