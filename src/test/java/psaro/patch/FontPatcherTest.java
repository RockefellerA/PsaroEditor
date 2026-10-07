package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Archive;
import psaro.format.Bclyt;
import psaro.format.Bclyt.TextOverride;
import psaro.format.Bcfnt;
import psaro.format.Darc;
import psaro.patch.FontPatcher.FontChange;
import psaro.patch.FontPatcher.LayoutChange;
import psaro.patch.FontPatcher.Plan;
import psaro.patch.FontPatcher.Text;
import psaro.render.TextRenderer;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Usage;
import psaro.romfs.SampleRomfs;

class FontPatcherTest {

	private static final String FONT = "SulaPro_B_04a_20.bcfnt";
	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	@TempDir
	Path dir;
	private Path romfs;
	private RomfsIndex index;
	private PatchSettings settings;
	private FontPatcher patcher;

	/**
	 * {@code menu.arc.lz} shows menu_0001 in a font holding only its Japanese; {@code plaza.arc.lz}
	 * carries a copy of the same font with the alphabet (advance 8), to borrow from.
	 */
	@BeforeEach
	void sample() throws IOException {
		romfs = dir.resolve("game");
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(romfs, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of(FONT),
				Map.of(FONT, SampleRomfs.font("はい", 10)), SampleRomfs.pane("Txt_Yes", "menu_0001"));
		SampleRomfs.archive(romfs, "scene/plaza/plaza.arc.lz", "blyt/plaza.bclyt", List.of(FONT),
				Map.of(FONT, SampleRomfs.font(ALPHABET + "はい", 8)));
		index = RomfsIndex.scan(romfs);
		settings = PatchSettings.open(romfs);
		patcher = new FontPatcher(index, settings, LayoutOverrides.open(romfs));
	}

	private List<Text> english(String text) {
		return List.of(new Text(index.table("menu"), "menu_0001", text));
	}

	private Path menu() {
		return romfs.resolve("scene/menu/menu.arc.lz");
	}

	private Bcfnt written() throws IOException {
		Darc.Node root = Archive.load(patcher.outputPath(menu()));
		return Bcfnt.parse(Darc.files(root).get("font/" + FONT).data);
	}

	private static Set<Integer> codes(String s) {
		return Set.copyOf(s.codePoints().boxed().toList());
	}

	@Test
	void addsWhatTheEnglishUsesThenDropsWhatItNoLongerNeeds() throws IOException {
		Plan plan = patcher.plan(english("Yes"));
		assertEquals(1, plan.fonts().size());
		FontChange f = plan.fonts().get(0);
		assertEquals(menu(), f.archive());
		assertEquals(codes("Yes"), f.add());
		assertTrue(f.remove().isEmpty());
		assertTrue(plan.hasWork());

		patcher.write(plan, step -> { });
		assertEquals(dir.resolve("game.psaro/romfs/scene/menu/menu.arc.lz"), patcher.outputPath(menu()));
		Bcfnt font = written();
		assertTrue(font.has('Y') && font.has('e') && font.has('s') && font.has('は'));
		assertFalse(font.has('N'));
		assertFalse(patcher.plan(english("Yes")).hasWork());

		Plan retranslated = patcher.plan(english("Not"));
		assertEquals(codes("Not"), retranslated.fonts().get(0).add());
		assertEquals(codes("Yes"), retranslated.fonts().get(0).remove());
		patcher.write(retranslated, step -> { });
		font = written();
		assertTrue(font.has('N') && font.has('o') && font.has('t'));
		assertFalse(font.has('Y') || font.has('e') || font.has('s'));
		// no font gets an extra pixel unless it is given letters
		assertEquals(8, font.glyph('N').charWidth);
		assertEquals(8, font.glyph('t').charWidth);

		Plan untranslated = patcher.plan(List.of());
		assertEquals(codes("Not"), untranslated.fonts().get(0).remove());
		patcher.write(untranslated, step -> { });
		assertFalse(Files.exists(patcher.outputPath(menu())));
		assertTrue(patcher.plan(List.of()).fonts().isEmpty());
	}

	@Test
	void characterNoDonorHasIsReportedAndLeftOut() {
		FontChange f = patcher.plan(english("Yes!")).fonts().get(0);
		assertEquals(Set.of((int) '!'), f.unavailable());
		assertEquals(codes("Yes"), f.lent());
		assertEquals(Set.of("plaza.arc.lz"), Set.copyOf(f.from().values().stream()
				.map(d -> d.archive().getFileName().toString()).toList()));
	}

	@Test
	void aFontsExtraSpaceLettersWidenThemAndChangingThemMarksTheFontStale() throws IOException {
		settings.setExtraSpace(FONT, "ty");
		patcher.settingsChanged();
		patcher.write(patcher.plan(english("Not")), step -> { });
		assertEquals(9, written().glyph('t').charWidth);
		assertEquals(8, written().glyph('o').charWidth);

		settings.setExtraSpace(FONT, "");
		patcher.settingsChanged();
		Plan plan = patcher.plan(english("Not"));
		assertTrue(plan.fonts().get(0).stale());
		assertTrue(plan.hasWork());
		patcher.write(plan, step -> { });
		assertEquals(8, written().glyph('t').charWidth);
		assertFalse(patcher.plan(english("Not")).hasWork());
	}

	@Test
	void anotherFontsLettersLeaveThisOneAlone() throws IOException {
		settings.setExtraSpace("SulaPro_B_04a_22.bcfnt", "ty");
		patcher.settingsChanged();
		patcher.write(patcher.plan(english("Not")), step -> { });
		assertEquals(8, written().glyph('t').charWidth);
	}

	@Test
	void theOldSharedLettersGoToTheFontsAlreadyPatchedWithThem() throws IOException {
		// patched under the old settings, where every font got "ty"
		settings.setExtraSpace(FONT, "ty");
		patcher.settingsChanged();
		patcher.write(patcher.plan(english("Not")), step -> { });
		Path file = dir.resolve("game.psaro/fonts.json");
		Files.writeString(file, "{\"extraSpace\": \"ty\"}");

		PatchSettings reopened = PatchSettings.open(romfs);
		FontPatcher again = new FontPatcher(index, reopened, LayoutOverrides.open(romfs));
		assertEquals("ty", reopened.extraSpace(FONT));
		assertEquals("", reopened.extraSpace("SulaPro_B_04a_22.bcfnt"));
		assertFalse(again.plan(english("Not")).hasWork(), "the written font is not stale");
		assertTrue(Files.readString(file).contains("\"" + FONT + "\""));
	}

	@Test
	void previewMeasuresWithTheGlyphsThePatchWouldAdd() throws IOException {
		settings.setExtraSpace(FONT, "y");
		patcher.settingsChanged();
		Bcfnt preview = patcher.preview(index.usages(index.table("menu"), "menu_0001").get(0));
		assertTrue(preview.has('Q') && preview.has('は'));
		assertEquals(9, preview.glyph('y').charWidth);
		assertEquals(8, preview.glyph('t').charWidth);
	}

	@Test
	void onceWrittenThePatchedCopyIsTheFontTheGameDrawsWith() throws IOException {
		Usage u = index.usages(index.table("menu"), "menu_0001").get(0);
		assertFalse(patcher.current(u).has('Y'));
		assertEquals(codes("Yes"), TextRenderer.measure(patcher.current(u), u.pane().text(), "Yes").missing());
		patcher.write(patcher.plan(english("Yes")), step -> { });
		assertTrue(patcher.current(u).has('Y'));
		assertTrue(TextRenderer.measure(patcher.current(u), u.pane().text(), "Yes").missing().isEmpty());
	}

	@Test
	void aChangedLayoutIsMeasuredWrittenAndPutBack() throws IOException {
		Usage u = index.usages(index.table("menu"), "menu_0001").get(0);
		assertEquals(120f, patcher.text(u).boxWidth());
		patcher.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), new TextOverride(40f, null, null, null, 1f, null));
		assertEquals(40f, patcher.text(u).boxWidth());
		assertEquals(1f, patcher.text(u).charSpace());

		Plan plan = patcher.plan(List.of());
		assertTrue(plan.hasWork());
		assertEquals(1, plan.layouts().size());
		LayoutChange l = plan.layouts().get(0);
		assertEquals(menu(), l.archive());
		assertTrue(l.changed() && l.hasWork());

		patcher.write(plan, step -> { });
		Bclyt.TextInfo written = Bclyt.read(Darc.files(Archive.load(patcher.outputPath(menu()))).get("blyt/menu.bclyt").data)
				.textPanes().get(0).text();
		assertEquals(40f, written.boxWidth());
		assertEquals(16f, written.fontSizeX());
		assertFalse(patcher.plan(List.of()).hasWork());

		// undone: the written archive, holding nothing else, goes
		patcher.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), TextOverride.NONE);
		Plan undo = patcher.plan(List.of());
		assertTrue(undo.hasWork());
		assertFalse(undo.layouts().get(0).changed());
		patcher.write(undo, step -> { });
		assertFalse(Files.exists(patcher.outputPath(menu())));
		assertFalse(patcher.plan(List.of()).hasWork());
	}

	@Test
	void anArchiveWithGlyphsAndALayoutChangeGetsBoth() throws IOException {
		patcher.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), new TextOverride(40f, null, null, null, null, null));
		patcher.write(patcher.plan(english("Yes")), step -> { });
		assertTrue(written().has('Y'));
		assertEquals(40f, Bclyt.read(Darc.files(Archive.load(patcher.outputPath(menu()))).get("blyt/menu.bclyt").data)
				.textPanes().get(0).text().boxWidth());
		// the glyphs no longer needed, the layout change still is: the archive stays, with only that
		patcher.write(patcher.plan(List.of()), step -> { });
		assertFalse(written().has('Y'));
		assertFalse(patcher.plan(List.of()).hasWork());
	}

	@Test
	void copiesToTheModsFolderWhenAsked() throws IOException {
		Path mods = dir.resolve("mods/romfs");
		settings.setCopyToMods(true, mods);
		patcher.write(patcher.plan(english("Yes")), step -> { });
		assertTrue(Files.isRegularFile(mods.resolve("scene/menu/menu.arc.lz")));
	}
}
