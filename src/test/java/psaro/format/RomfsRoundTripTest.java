package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every format reader and writer against a real romfs: each file must survive read then write
 * byte for byte. Game data is not part of this repository, so these tests run only when a
 * romfs is supplied ({@code -Dpsaro.romfs=<dir>} or the {@code PSARO_ROMFS} environment
 * variable) and are skipped otherwise.
 */
class RomfsRoundTripTest {

	private static Path romfs;
	private static List<Path> archives;
	/** Unique font files, keyed by content, with one archive path each for failure messages. */
	private static Map<String, byte[]> fonts;

	@BeforeAll
	static void locate() throws IOException {
		String dir = System.getProperty("psaro.romfs", System.getenv("PSARO_ROMFS"));
		assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "no romfs supplied; set -Dpsaro.romfs");
		romfs = Path.of(dir);
		try (Stream<Path> s = Files.walk(romfs)) {
			archives = s.filter(p -> p.toString().endsWith(".arc.lz")).sorted().toList();
		}
		fonts = new LinkedHashMap<>();
		Map<String, Boolean> seen = new LinkedHashMap<>();
		for (Path p : archives) {
			Darc.Node root;
			try {
				root = Archive.load(p);
			} catch (IllegalArgumentException notDarc) {
				continue;
			}
			for (Map.Entry<String, Darc.Node> e : Darc.files(root).entrySet()) {
				if (e.getKey().endsWith(".bcfnt")) {
					String key = Arrays.hashCode(e.getValue().data) + ":" + e.getValue().data.length;
					if (seen.putIfAbsent(key, true) == null) {
						fonts.put(romfs.relativize(p) + "!" + e.getKey(), e.getValue().data);
					}
				}
			}
		}
	}

	@Test
	void everyArchiveRewritesByteIdentical() throws IOException {
		List<String> mismatched = new ArrayList<>();
		int checked = 0;
		for (Path p : archives) {
			byte[] d = Lz11.decompress(Files.readAllBytes(p));
			if (!Bytes.magic(d, 0, "darc")) {
				continue;
			}
			checked++;
			if (!Arrays.equals(d, Darc.write(Darc.read(d)))) {
				mismatched.add(romfs.relativize(p).toString());
			}
		}
		assertTrue(checked > 0, "no archives under " + romfs);
		assertEquals(List.of(), mismatched);
	}

	@Test
	void everyFontRewritesByteIdentical() {
		List<String> mismatched = new ArrayList<>();
		for (Map.Entry<String, byte[]> e : fonts.entrySet()) {
			if (!Arrays.equals(e.getValue(), Bcfnt.parse(e.getValue()).toBytes())) {
				mismatched.add(e.getKey());
			}
		}
		assertTrue(!fonts.isEmpty(), "no fonts found");
		assertEquals(List.of(), mismatched);
	}

	@Test
	void everyStringTableRewritesByteIdentical() throws IOException {
		List<String> mismatched = new ArrayList<>();
		List<Path> tables;
		try (Stream<Path> s = Files.list(romfs.resolve("text"))) {
			tables = s.filter(p -> p.toString().endsWith(".tdt")).sorted().toList();
		}
		for (Path p : tables) {
			byte[] d = Files.readAllBytes(p);
			if (!Arrays.equals(d, Tdt.write(Tdt.read(d)))) {
				mismatched.add(p.getFileName().toString());
			}
		}
		assertTrue(!tables.isEmpty(), "no .tdt files under text/");
		assertEquals(List.of(), mismatched);
	}

	@Test
	void everyLayoutsTextPanesNameAFontTheLayoutLists() throws IOException {
		int panes = 0;
		for (Path p : archives) {
			Darc.Node root;
			try {
				root = Archive.load(p);
			} catch (IllegalArgumentException notDarc) {
				continue;
			}
			for (Map.Entry<String, Darc.Node> e : Darc.files(root).entrySet()) {
				if (!e.getKey().endsWith(".bclyt")) {
					continue;
				}
				Bclyt.Layout layout = Bclyt.read(e.getValue().data);
				for (Bclyt.Pane pane : layout.textPanes()) {
					panes++;
					assertTrue(pane.text().font() != null && layout.fonts().contains(pane.text().font()),
							romfs.relativize(p) + "!" + e.getKey() + " " + pane.name());
				}
			}
		}
		assertTrue(panes > 0, "no text panes found");
	}

	@Test
	void largestArchiveRecompressesLosslessly() throws IOException {
		Path largest = archives.stream().max((a, b) -> Long.compare(size(a), size(b))).orElseThrow();
		byte[] d = Lz11.decompress(Files.readAllBytes(largest));
		assertArrayEquals(d, Lz11.decompress(Lz11.compress(d)), largest.toString());
	}

	private static long size(Path p) {
		try {
			return Files.size(p);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
