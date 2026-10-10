package psaro.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Bclyt;
import psaro.format.Bclyt.TextOverride;
import psaro.patch.FontPatcher;
import psaro.patch.LayoutOverrides;
import psaro.patch.PatchSettings;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Usage;
import psaro.romfs.SampleRomfs;

class FitTest {

	@TempDir
	Path dir;

	/** Japanese the measuring would break across the 120-wide box, though the game shows it fine. */
	@Test
	void theOriginalJapaneseFitsUntilThePaneIsChanged() throws IOException {
		String japanese = "はい".repeat(20);
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", japanese));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"),
				Map.of("a.bcfnt", SampleRomfs.font("はい", 10)), SampleRomfs.pane("Txt_Yes", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		Usage u = index.usages(index.table("menu"), "menu_0001").get(0);

		assertTrue(Fit.judge(fonts, u, "いは".repeat(20), japanese).tooWide(), "other text is measured");
		assertFalse(Fit.judge(fonts, u, japanese, japanese).overflows());
		assertFalse(Fit.check(fonts, List.of(u), japanese, japanese).overflows());

		// a smaller font: the game's own text breaks no more than it did as shipped, so no worse
		fonts.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), new TextOverride(null, null, 12f, 12f, null, null));
		assertFalse(Fit.judge(fonts, u, japanese, japanese).tooWide(), "no worse than the game had it");
		// a box half as wide: it breaks more than the game's did
		fonts.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), new TextOverride(50f, null, null, null, null, null));
		assertTrue(Fit.judge(fonts, u, japanese, japanese).tooWide(), "a changed pane is measured");
	}

	/** The room the Japanese is given is the layout's own: a smaller font for the English leaves it. */
	@Test
	void theJapanesesRoomIsMeasuredAsTheGameShipsIt() throws IOException {
		String japanese = "はい\nはい\nはい";
		Path romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", japanese));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"),
				Map.of("a.bcfnt", SampleRomfs.font("はい", 10)), SampleRomfs.pane("Txt_Yes", "menu_0001"));
		RomfsIndex index = RomfsIndex.scan(romfs);
		FontPatcher fonts = new FontPatcher(index, PatchSettings.open(romfs), LayoutOverrides.open(romfs));
		Usage u = index.usages(index.table("menu"), "menu_0001").get(0);

		Fit.Judgement before = Fit.judge(fonts, u, "いは", japanese);
		assertEquals(Fit.Limit.JAPANESE, before.heightBy());
		fonts.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), new TextOverride(null, null, 8f, 8f, null, null));
		Fit.Judgement after = Fit.judge(fonts, u, "いは", japanese);
		assertEquals(Fit.Limit.JAPANESE, after.heightBy());
		assertEquals(before.limitHeight(), after.limitHeight(), 1e-6);
	}

	/** A list's rows show one string in one shape: a change to one is a change to all. */
	@Test
	void theRowsOfOneListTakeEachOthersChanges() {
		Bclyt.TextInfo row = new Bclyt.TextInfo("a.bcfnt", 48, 20, 4, 22, 22, 0, 0, "*", 3, 0, -1, -1);
		Bclyt.TextInfo wider = new Bclyt.TextInfo("a.bcfnt", 164, 20, 4, 22, 22, 0, 0, "*", 3, 0, -1, -1);
		Usage first = usage("blyt/record.bclyt", "Txt_beat_01", "rcrd_0062", row, 0);
		Usage second = usage("blyt/record.bclyt", "Txt_beat_02", "rcrd_0062", row, 30);
		Usage otherShape = usage("blyt/record.bclyt", "Txt_cont_01", "rcrd_0062", wider, 60);
		Usage otherString = usage("blyt/record.bclyt", "Txt_cont_02", "rcrd_0063", row, 90);
		Usage otherLayout = usage("blyt/total.bclyt", "Txt_beat_01", "rcrd_0062", row, 0);
		Usage elsewhere = usage("blyt/record.bclyt", "Txt_Elsewhere", "rcrd_0062", row, -60);
		List<Usage> all = List.of(first, second, otherShape, otherString, otherLayout, elsewhere);

		assertEquals(List.of(second), Fit.together(first, all));
		assertEquals(List.of(first), Fit.together(second, all));
		assertTrue(Fit.layers(first, all).isEmpty(), "rows are not layers of one text");
	}

	private static Usage usage(String layout, String pane, String key, Bclyt.TextInfo text, float y) {
		return new Usage(Path.of("record.arc.lz"), layout, new Bclyt.Pane("txt1", pane, List.of(key), text, null, 0, y),
				List.of(text.font()));
	}
}
