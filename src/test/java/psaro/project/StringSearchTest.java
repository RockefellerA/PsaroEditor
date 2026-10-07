package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.project.StringSearch.Hit;
import psaro.romfs.RomfsIndex;
import psaro.romfs.SampleRomfs;

class StringSearchTest {

	@TempDir
	Path dir;
	private RomfsIndex index;
	private Translations translations;
	private StringSearch search;

	@BeforeEach
	void sample() throws IOException {
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "config", Map.of("cftp_1000", "名前を\n変更", "cftp_1010", "\u0002\u0002ＨＰ\u0002\u0001を回復"));
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "ｶﾀｶﾅ", "menu_0002", "はい"));
		index = RomfsIndex.scan(romfs);
		translations = Translations.open(index);
		translations.set(index.table("config"), "cftp_1000", "Change\nName");
		search = new StringSearch(index, translations);
	}

	private List<String> keys(String query) {
		return search.find(query, 100).hits().stream().map(Hit::key).sorted().toList();
	}

	@Test
	void findsJapaneseAcrossLineBreaksAndColorCodes() {
		assertEquals(List.of("cftp_1000"), keys("名前を変更".replace("を", "を ")));
		assertEquals(List.of("cftp_1010"), keys("HPを回復"));
	}

	@Test
	void findsEnglishWhateverItsCaseAndLineBreaks() {
		assertEquals(List.of("cftp_1000"), keys("change name"));
		assertEquals(List.of("cftp_1000"), keys("  CHANGE   name "));
	}

	@Test
	void fullAndHalfWidthFormsMatchEachOther() {
		assertEquals(List.of("menu_0001"), keys("カタカナ"));
		assertEquals(List.of("cftp_1010"), keys("ｈｐ"));
	}

	@Test
	void findsKeysAndCountsBeyondTheLimit() {
		assertEquals(List.of("menu_0001", "menu_0002"), keys("menu_"));
		StringSearch.Result r = search.find("_", 1);
		assertEquals(1, r.hits().size());
		assertEquals(4, r.total());
		assertTrue(search.find("   ", 10).hits().isEmpty());
	}
}
