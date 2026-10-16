package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.romfs.SampleCpk;

class CpkTest {

	@TempDir
	Path dir;

	private static Map<String, byte[]> files() {
		byte[] big = new byte[0x500];
		Arrays.fill(big, 0x100, big.length, (byte) 7);
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("top.txt", "2011/10/21".getBytes(ShiftJis.CHARSET));
		files.put("common/database/general/gg3d_unit.bin", big);
		return new LinkedHashMap<>(files);
	}

	/** Every file is listed by its folder and name, and reads back as it was, decompressed or stored. */
	@Test
	void listsAndReadsItsFiles() throws IOException {
		Path file = dir.resolve("data.cpk");
		Files.write(file, SampleCpk.cpk(files(), true, false));
		assertTrue(Cpk.is(file));
		try (Cpk cpk = Cpk.open(file)) {
			assertEquals(List.of("top.txt", "common/database/general/gg3d_unit.bin"),
					cpk.entries().stream().map(Cpk.Entry::path).toList());
			Cpk.Entry unit = cpk.find("common/database/general/gg3d_unit.bin");
			assertTrue(unit.compressed());
			assertArrayEquals(files().get(unit.path()), cpk.read(unit));
			assertArrayEquals(files().get("top.txt"), cpk.read(cpk.find("top.txt")));
			assertNull(cpk.find("nothing.bin"));
		}
	}

	/** A game that scrambles its tables has them read all the same. */
	@Test
	void readsScrambledTables() throws IOException {
		Path file = dir.resolve("data.cpk");
		Files.write(file, SampleCpk.cpk(files(), false, true));
		try (Cpk cpk = Cpk.open(file)) {
			assertEquals(2, cpk.entries().size());
			assertArrayEquals(files().get("top.txt"), cpk.read(cpk.find("top.txt")));
		}
	}

	/**
	 * Rebuilt with one file replaced (compressed before, stored now) and one longer: every file
	 * reads back as it should, an untouched compressed one still compressed, in the same order.
	 */
	@Test
	void rebuildsWithFilesReplaced() throws IOException {
		Map<String, byte[]> files = files();
		byte[] big = new byte[0x900];
		Arrays.fill(big, (byte) 3);
		files.put("data/foundation/database.dat", big);
		Path original = dir.resolve("data.cpk");
		Files.write(original, SampleCpk.cpk(files, true, false));
		byte[] unit = "the unit table, now in English".getBytes(ShiftJis.CHARSET);
		byte[] top = new byte[0x1000];
		Arrays.fill(top, (byte) 'x');
		Path out = dir.resolve("patch/romfs/data.cpk");
		try (Cpk cpk = Cpk.open(original)) {
			cpk.write(Map.of("common/database/general/gg3d_unit.bin", unit, "top.txt", top), out, step -> { });
		}
		try (Cpk rebuilt = Cpk.open(out)) {
			assertEquals(List.copyOf(files.keySet()), rebuilt.entries().stream().map(Cpk.Entry::path).toList());
			assertArrayEquals(top, rebuilt.read(rebuilt.find("top.txt")));
			Cpk.Entry u = rebuilt.find("common/database/general/gg3d_unit.bin");
			assertFalse(u.compressed());
			assertArrayEquals(unit, rebuilt.read(u));
			Cpk.Entry db = rebuilt.find("data/foundation/database.dat");
			assertTrue(db.compressed(), "an untouched file is copied as it is stored");
			assertArrayEquals(big, rebuilt.read(db));
			for (Cpk.Entry e : rebuilt.entries()) {
				assertEquals(0, e.offset() % 0x800, e.path() + " starts on the alignment");
			}
		}
		assertFalse(Files.exists(dir.resolve("patch/romfs/data.cpk.tmp")));
	}

	/** Scrambled tables are written back scrambled, and what came after the files moves with them. */
	@Test
	void rebuildsAScrambledCpkKeepingWhatFollowsTheFiles() throws IOException {
		Path original = dir.resolve("data.cpk");
		byte[] etoc = "ETOC and the dates of the files".getBytes(ShiftJis.CHARSET);
		Files.write(original, SampleCpk.cpk(files(), false, true, etoc));
		Path out = dir.resolve("out.cpk");
		byte[] top = new byte[0x2000];
		try (Cpk cpk = Cpk.open(original)) {
			cpk.write(Map.of("top.txt", top), out, step -> { });
		}
		byte[] rebuilt = Files.readAllBytes(out);
		try (Cpk c = Cpk.open(out)) {
			assertArrayEquals(top, c.read(c.find("top.txt")));
			assertArrayEquals(files().get("common/database/general/gg3d_unit.bin"),
					c.read(c.find("common/database/general/gg3d_unit.bin")));
		}
		assertFalse(Bytes.magic(rebuilt, 0x10, "@UTF"), "the header is still scrambled");
		long etocAt = header(rebuilt, "EtocOffset");
		assertTrue(etocAt > Files.size(original) - etoc.length, "the ETOC moved after the longer file");
		assertArrayEquals(etoc, Arrays.copyOfRange(rebuilt, (int) etocAt, (int) etocAt + etoc.length));
	}

	/** {@code column} of a CPK's header, its table unscrambled when it is scrambled. */
	private static long header(byte[] cpk, String column) {
		byte[] t = Arrays.copyOfRange(cpk, 0x10, 0x10 + Bytes.u32(cpk, 8));
		if (!Bytes.magic(t, 0, "@UTF")) {
			int key = 0x655F;
			for (int i = 0; i < t.length; i++) {
				t[i] ^= (byte) key;
				key *= 0x4115;
			}
		}
		return ((Number) Cpk.utf(t).get(0).get(column)).longValue();
	}

	@Test
	void aFileNotInTheCpkCannotBeReplaced() throws IOException {
		Path original = dir.resolve("data.cpk");
		Files.write(original, SampleCpk.cpk(files(), false, false));
		try (Cpk cpk = Cpk.open(original)) {
			assertThrows(IllegalArgumentException.class, () -> cpk.write(Map.of("nothing.bin", new byte[1]), dir.resolve("o.cpk"),
					step -> { }));
		}
	}

	@Test
	void anotherFileIsNotACpk() throws IOException {
		Path file = dir.resolve("data.cpk");
		Files.write(file, "PK\3\4 not a cpk".getBytes(ShiftJis.CHARSET));
		assertFalse(Cpk.is(file));
		assertThrows(IllegalArgumentException.class, () -> Cpk.open(file));
	}
}
