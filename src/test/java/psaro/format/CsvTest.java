package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleRomfs;

class CsvTest {

	private static final byte[] CHARA = SampleRomfs.csv(List.of(
			"dqc0101a,ロトの血を引く者,True,100",
			"dqc0102a,ローラ姫,True,200"));

	/** The Japanese cells, keyed by the row's id and the column; ids, numbers and flags are left out. */
	@Test
	void japaneseCellsAreKeyedByRowIdAndColumn() {
		assertEquals(Map.of("dqc0101a:1", "ロトの血を引く者", "dqc0102a:1", "ローラ姫"), Csv.read(CHARA));
		assertEquals(List.of("dqc0101a:1", "dqc0102a:1"), List.copyOf(Csv.read(CHARA).keySet()));
	}

	/** A first column that repeats ({@code True}) gives way to the second; with neither unique, the row's number. */
	@Test
	void theRowIdIsTheFirstColumnThatTellsRowsApart() {
		byte[] ability = SampleRomfs.csv(List.of("True,ABI001,メラ,呪文", "True,ABI002,メラミ,呪文"));
		assertEquals(List.of("ABI001:2", "ABI001:3", "ABI002:2", "ABI002:3"), List.copyOf(Csv.read(ability).keySet()));
		byte[] drops = SampleRomfs.csv(List.of("0,1,やくそう", "0,1,どくけしそう"));
		assertEquals(List.of("000:2", "001:2"), List.copyOf(Csv.read(drops).keySet()));
	}

	/** A backslash is a cell's line break. */
	@Test
	void aBackslashIsALineBreak() {
		byte[] series = SampleRomfs.csv(List.of("200,ドラゴンクエストII\\悪霊の神々,DQⅡ"));
		assertEquals("ドラゴンクエストII\n悪霊の神々", Csv.read(series).get("200:1"));
		byte[] out = Csv.write(series, Map.of("200:1", "Dragon Quest II\nLuminaries of the Legendary Line"));
		assertEquals("﻿200,Dragon Quest II\\Luminaries of the Legendary Line,DQⅡ\r\n",
				new String(out, StandardCharsets.UTF_16LE));
	}

	@Test
	void writingChangesOnlyTheCellsGiven() {
		assertSame(CHARA, Csv.write(CHARA, Csv.read(CHARA)), "unchanged text, the same file");
		byte[] out = Csv.write(CHARA, Map.of("dqc0101a:1", "Descendant of Erdrick", "dqc0101a:9", "nothing there"));
		assertEquals("﻿dqc0101a,Descendant of Erdrick,True,100\r\ndqc0102a,ローラ姫,True,200\r\n",
				new String(out, StandardCharsets.UTF_16LE));
		assertThrows(IllegalArgumentException.class, () -> Csv.read("a,b".getBytes(StandardCharsets.UTF_16LE)));
	}

	/** No cell can hold a comma, so the English shows a full-width one, taking the space after it. */
	@Test
	void aCommaIsWrittenFullWidth() {
		assertEquals("Yes，sir，no", Csv.shown("Yes, sir,no"));
		byte[] out = Csv.write(CHARA, Map.of("dqc0102a:1", "Gwaelin, Princess"));
		assertEquals("Gwaelin，Princess", Csv.read(out).get("dqc0102a:1"));
		assertEquals(4, new String(out, StandardCharsets.UTF_16LE).split("\r\n")[1].split(",").length);
	}

	/** Against a real romfs when one is supplied: every data table reads and writes back byte for byte. */
	@Test
	void realDataTablesRoundTrip() throws IOException {
		String dir = System.getProperty("psaro.romfs", System.getenv("PSARO_ROMFS"));
		assumeTrue(dir != null && Files.isDirectory(Path.of(dir, "table")), "no romfs supplied; set -Dpsaro.romfs");
		try (Stream<Path> files = Files.list(Path.of(dir, "table"))) {
			for (Path p : files.filter(f -> f.toString().endsWith(".csv")).toList()) {
				byte[] original = Files.readAllBytes(p);
				Map<String, String> text = Csv.read(original);
				assertSame(original, Csv.write(original, text), p.toString());
				// every cell changed, then changed back, rebuilds the same file
				Map<String, String> marked = new HashMap<>();
				text.forEach((k, v) -> marked.put(k, v + "!"));
				byte[] changed = Csv.write(original, marked);
				assertEquals(marked, Csv.read(changed), p.toString());
				assertArrayEquals(original, Csv.write(changed, text), p.toString());
			}
		}
	}
}
