package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleRomfs;

class MsgdTest {

	private static final List<String> STRINGS = List.of("チュートリアル", "#%02d/%d", "クリアおめでとうございます！\nＢＭＳの操作方法は？", "", "はい");

	@Test
	void stringsAreReadBackAsWrittenAndHidden() {
		byte[] file = SampleRomfs.msgd(STRINGS);
		assertEquals(STRINGS, Msgd.read(file));
		String raw = new String(file, StandardCharsets.UTF_16LE);
		assertFalse(raw.contains("はい"), "each unit is XORed, so the text does not show as UTF-16");
	}

	@Test
	void rewritingKeepsTheHeaderAndChangesOnlyTheText() {
		byte[] file = SampleRomfs.msgd(STRINGS);
		assertArrayEquals(file, Msgd.write(file, STRINGS), "unchanged strings, the same bytes");
		List<String> english = List.of("Tutorial", "#%02d/%d", "Congratulations!\nHave you learned the BMS controls?", "", "Yes");
		byte[] out = Msgd.write(file, english);
		assertEquals(english, Msgd.read(out));
		assertArrayEquals(java.util.Arrays.copyOf(file, 0x0C), java.util.Arrays.copyOf(out, 0x0C));
		assertThrows(IllegalArgumentException.class, () -> Msgd.write(file, List.of("one")));
		assertThrows(IllegalArgumentException.class, () -> Msgd.read(new byte[64]));
	}

	/** Against a real romfs when one is supplied: every message file reads and writes back byte for byte. */
	@Test
	void realMessageFilesRoundTrip() throws IOException {
		String dir = System.getProperty("psaro.romfs", System.getenv("PSARO_ROMFS"));
		assumeTrue(dir != null && Files.isDirectory(Path.of(dir, "message")), "no romfs supplied; set -Dpsaro.romfs");
		try (Stream<Path> files = Files.list(Path.of(dir, "message"))) {
			for (Path p : files.filter(f -> f.toString().endsWith(".mdt")).toList()) {
				byte[] original = Files.readAllBytes(p);
				assertArrayEquals(original, Msgd.write(original, Msgd.read(original)), p.toString());
			}
		}
	}
}
