package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
	void aPlaceholderTheGameReplacesNeedsNoGlyphs() {
		assertEquals(codes("from "), FontPatcher.characters("from [シリーズ名]"));
		assertEquals(codes("Lv []"), FontPatcher.characters("Lv [\n]"), "a bracket pair split by a line break is text");
		// the sample's donor has no space, so none here
		FontChange f = patcher.plan(english("Yes[キャラクター名]")).fonts().get(0);
		assertEquals(codes("Yes"), f.needed());
		assertTrue(f.unavailable().isEmpty());
	}

	/**
	 * A font set to a bundled typeface gets its letters drawn from it, even one no game font has,
	 * and the patch writes what the preview measured.
	 */
	@Test
	void aFontSetToABundledTypefaceGetsItsLettersDrawnFromIt() throws IOException {
		settings.setLettersFrom(FONT, Typeface.NOTO_SANS);
		patcher.settingsChanged();
		FontChange f = patcher.plan(english("Yes!")).fonts().get(0);
		assertTrue(f.unavailable().isEmpty(), "Noto Sans has the '!' no game font has");
		assertEquals(Set.of("Noto Sans"), Set.copyOf(f.from().values().stream().map(d -> d.name()).toList()));

		patcher.write(patcher.plan(english("Yes!")), step -> { });
		Bcfnt preview = patcher.preview(index.usages(index.table("menu"), "menu_0001").get(0));
		for (char c : "Yes!".toCharArray()) {
			assertTrue(written().has(c));
			assertArrayEquals(preview.glyph(c).pixels, written().glyph(c).pixels, "as previewed: " + c);
		}
		assertFalse(patcher.plan(english("Yes!")).hasWork());

		// back to the game's fonts: the drawn glyphs are rebuilt as borrowed ones
		settings.setLettersFrom(FONT, Typeface.GAME);
		patcher.settingsChanged();
		assertTrue(patcher.plan(english("Yes")).hasWork());
		assertEquals(Typeface.GAME, PatchSettings.open(romfs).lettersFrom(FONT));
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

	/**
	 * A pane switched to the layout's other font is measured in it, that font gets the English's
	 * glyphs instead of the pane's own, and the written layout points the pane at it.
	 */
	@Test
	void aPaneSwitchedToAnotherFontIsMeasuredAndPatchedInIt() throws IOException {
		String small = "SulaPro_B_04a_18.bcfnt";
		Path other = dir.resolve("other");
		SampleRomfs.table(other, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(other, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of(FONT, small),
				Map.of(FONT, SampleRomfs.font("はい", 10), small, SampleRomfs.font("い", 6)),
				SampleRomfs.pane("Txt_Yes", "menu_0001"));
		SampleRomfs.archive(other, "scene/plaza/plaza.arc.lz", "blyt/plaza.bclyt", List.of(FONT, small),
				Map.of(FONT, SampleRomfs.font(ALPHABET + "はい", 8), small, SampleRomfs.font(ALPHABET + "はい", 6)));
		RomfsIndex idx = RomfsIndex.scan(other);
		FontPatcher p = new FontPatcher(idx, PatchSettings.open(other), LayoutOverrides.open(other));
		Usage u = idx.usages(idx.table("menu"), "menu_0001").get(0);
		assertEquals(List.of(FONT, small), u.layoutFonts());
		assertEquals(FONT, p.fontName(u));

		p.overrides().set("blyt/menu.bclyt", List.of("Txt_Yes"), TextOverride.NONE.withFont(small));
		assertEquals(small, p.fontName(u));
		assertFalse(p.current(u).has('は'), "measured in the small font, which lacks it");
		List<Text> yes = List.of(new Text(idx.table("menu"), "menu_0001", "Yes"));
		Plan plan = p.plan(yes);
		assertEquals(List.of(small), plan.fonts().stream().map(FontChange::font).toList());
		assertEquals(codes("Yes"), plan.fonts().get(0).add());

		p.write(plan, step -> { });
		Map<String, Darc.Node> files = Darc.files(Archive.load(p.outputPath(other.resolve("scene/menu/menu.arc.lz"))));
		assertEquals(small, Bclyt.read(files.get("blyt/menu.bclyt").data).textPanes().get(0).text().font());
		assertTrue(Bcfnt.parse(files.get("font/" + small).data).has('Y'));
		assertFalse(Bcfnt.parse(files.get("font/" + FONT).data).has('Y'));
		assertFalse(p.plan(yes).hasWork());
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

	/**
	 * A plain {@code 02a} font whose style has no English anywhere borrows from the same family in
	 * another style; an {@code 04a} font whose own style has the alphabet keeps to it.
	 */
	@Test
	void anotherStyleLendsOnlyWhenTheFontsOwnStyleHasNoAlphabet() throws IOException {
		Path other = dir.resolve("other");
		SampleRomfs.table(other, "menu", Map.of("menu_0001", "はい", "menu_0002", "いいえ"));
		SampleRomfs.archive(other, "scene/menu/menu.arc.lz", "blyt/menu.bclyt",
				List.of("SulaPro_B_02a_18.bcfnt", "SulaPro_B_04a_18.bcfnt"),
				Map.of("SulaPro_B_02a_18.bcfnt", SampleRomfs.font("はい", 10), "SulaPro_B_04a_18.bcfnt",
						SampleRomfs.font("いえ", 10)),
				SampleRomfs.pane("Txt_Plain", "menu_0001"));
		SampleRomfs.archive(other, "scene/other/other.arc.lz", "blyt/other.bclyt", List.of("SulaPro_B_01a_18.bcfnt"),
				Map.of("SulaPro_B_01a_18.bcfnt", SampleRomfs.font(ALPHABET, 7), "SulaPro_B_04a_20.bcfnt",
						SampleRomfs.font(ALPHABET, 8)));
		RomfsIndex idx = RomfsIndex.scan(other);
		List<String> plain = idx.donors("SulaPro_B_02a_18.bcfnt").stream().map(d -> d.name()).toList();
		assertEquals(List.of("SulaPro_B_01a_18.bcfnt"), plain);
		List<String> outlined = idx.donors("SulaPro_B_04a_18.bcfnt").stream().map(d -> d.name()).toList();
		assertEquals("SulaPro_B_04a_20.bcfnt", outlined.get(0));
		assertFalse(outlined.contains("SulaPro_B_01a_18.bcfnt"), "the outlined font's own style has the alphabet");

		FontPatcher p = new FontPatcher(idx, PatchSettings.open(other), LayoutOverrides.open(other));
		FontChange f = p.plan(List.of(new Text(idx.table("menu"), "menu_0001", "Yes"))).fonts().get(0);
		assertTrue(f.unavailable().isEmpty());
		assertEquals(codes("Yes"), f.lent());
		assertEquals(Set.of("SulaPro_B_01a_18.bcfnt"), Set.copyOf(f.from().values().stream().map(d -> d.name()).toList()));
	}

	/**
	 * A family with no whole alphabet anywhere (TBMarugothic has no lowercase) borrows from another
	 * family, its own style first; a family that has one keeps to itself.
	 */
	@Test
	void anotherFamilyLendsOnlyWhenTheFontsOwnFamilyHasNoAlphabet() throws IOException {
		Path other = dir.resolve("other");
		String maru = "TBMarugothic_H_04a_16.bcfnt";
		SampleRomfs.table(other, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.archive(other, "scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of(maru),
				Map.of(maru, SampleRomfs.font("はいVC", 10)), SampleRomfs.pane("Txt_Btn", "menu_0001"));
		SampleRomfs.archive(other, "scene/other/other.arc.lz", "blyt/other.bclyt", List.of("SulaPro_B_01a_16.bcfnt"),
				Map.of("TBMarugothic_H_04a_20.bcfnt", SampleRomfs.font("ABC", 8), "SulaPro_B_01a_16.bcfnt",
						SampleRomfs.font(ALPHABET, 7), "SulaPro_DB_04a_16.bcfnt", SampleRomfs.font(ALPHABET + " はい", 8)));
		RomfsIndex idx = RomfsIndex.scan(other);
		List<String> donors = idx.donors(maru).stream().map(d -> d.name()).toList();
		assertEquals(List.of(maru, "TBMarugothic_H_04a_20.bcfnt", "SulaPro_DB_04a_16.bcfnt"), donors,
				"own family first, then the other family's same style; nothing past the first alphabet");
		assertFalse(idx.donors("SulaPro_B_01a_16.bcfnt").stream().anyMatch(d -> d.name().startsWith("TB")),
				"a family with an alphabet keeps to itself");

		FontPatcher p = new FontPatcher(idx, PatchSettings.open(other), LayoutOverrides.open(other));
		FontChange f = p.plan(List.of(new Text(idx.table("menu"), "menu_0001", "View Cards"))).fonts().get(0);
		assertTrue(f.unavailable().isEmpty(), "unavailable: " + f.unavailable());
		assertEquals(Set.of("SulaPro_DB_04a_16.bcfnt"), Set.copyOf(f.from().values().stream().map(d -> d.name()).toList()));
	}

	@Test
	void copiesToTheModsFolderWhenAsked() throws IOException {
		Path mods = dir.resolve("mods/romfs");
		settings.setCopyToMods(true, mods);
		patcher.write(patcher.plan(english("Yes")), step -> { });
		assertTrue(Files.isRegularFile(mods.resolve("scene/menu/menu.arc.lz")));
	}
}
