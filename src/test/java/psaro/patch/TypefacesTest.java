package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypefacesTest {

	@TempDir
	Path dir;

	private Path romfs() {
		return dir.resolve("game");
	}

	private Path fonts() {
		return dir.resolve("game.psaro/fonts");
	}

	/**
	 * A family's files are imported into the project as one typeface beside the game font and the
	 * bundled one, a weight to a file; a game font is drawn in the nearest weight, the heavier of
	 * two as near. The project finds it when opened again.
	 */
	@Test
	void aFamilysFilesAreImportedAsOneTypeface() throws IOException {
		Typefaces t = Typefaces.open(romfs());
		assertEquals(List.of(Typeface.GAME, Typeface.M_PLUS_ROUNDED), t.all());

		Typefaces.Imported result = t.importFiles(List.of(TestFonts.renamed(dir.resolve("download"), "Bold"),
				TestFonts.renamed(dir.resolve("download"), "Black")));
		assertEquals(List.of(TestFonts.FAMILY + " (Bold, Black)"), result.added());
		assertTrue(result.refused().isEmpty());
		assertTrue(Files.isRegularFile(fonts().resolve("Renamed-Bold.ttf")), "copied into the project");

		Typeface renamed = t.of("rounded-mplus-9z");
		assertEquals(List.of(Typeface.GAME, Typeface.M_PLUS_ROUNDED, renamed), t.all());
		assertEquals(TestFonts.FAMILY, renamed.label());
		assertEquals("RoundedMplus9z", renamed.prefix());
		assertTrue(renamed.bundled());
		assertFalse(renamed.builtIn());
		assertEquals(Map.of(700, "Renamed-Bold.ttf", 900, "Renamed-Black.ttf"), renamed.weights());
		assertEquals("Renamed-Bold.ttf", renamed.fileName("Medium"));
		assertEquals("Renamed-Black.ttf", renamed.fileName("ExtraBold"), "800 is as near 700 as 900: the heavier");
		assertTrue(renamed.font("Bold").canDisplay('A'));
		assertEquals(renamed, t.ofFreeFont("RoundedMplus9z_B_04a_20.bcfnt"));

		assertEquals(renamed, Typefaces.open(romfs()).of("rounded-mplus-9z"), "found again from the project's folder");
	}

	/** A file of a family and weight already there replaces it; files that are no font, or the bundled typeface's, are refused. */
	@Test
	void aFileReplacesItsWeightAndOthersAreRefused() throws IOException {
		Typefaces t = Typefaces.open(romfs());
		t.importFiles(List.of(TestFonts.renamed(dir.resolve("a"), "Bold")));
		Path newer = Files.move(TestFonts.renamed(dir.resolve("b"), "Bold"), dir.resolve("b/Newer-Bold.ttf"));
		Path junk = Files.writeString(dir.resolve("notes.ttf"), "not a font");
		Path bundled = dir.resolve("MPLUSRounded1c-Bold.ttf");
		try (var in = TypefacesTest.class.getResourceAsStream("/fonts/MPLUSRounded1c-Bold.ttf")) {
			Files.write(bundled, in.readAllBytes());
		}

		Typefaces.Imported result = t.importFiles(List.of(newer, junk, bundled));
		assertEquals(List.of(TestFonts.FAMILY + " (Bold)"), result.added());
		assertEquals(2, result.refused().size());
		assertTrue(result.refused().get(0).startsWith("notes.ttf: "));
		assertTrue(result.refused().get(1).contains("comes with PsaroEditor"));
		assertEquals(Map.of(700, "Newer-Bold.ttf"), t.of("rounded-mplus-9z").weights());
		assertFalse(Files.exists(fonts().resolve("Renamed-Bold.ttf")), "the file it replaced is gone");
	}

	/** A removed typeface's files go; a setting naming it is shown by its id and draws with the game font. */
	@Test
	void aRemovedTypefaceIsNoLongerThere() throws IOException {
		Typefaces t = Typefaces.open(romfs());
		t.importFiles(List.of(TestFonts.renamed(dir.resolve("a"), "Bold")));
		t.remove(t.of("rounded-mplus-9z"));
		assertFalse(t.has("rounded-mplus-9z"));
		assertEquals(Typeface.GAME, t.of("rounded-mplus-9z"));
		assertEquals("rounded-mplus-9z (not imported)", t.named("rounded-mplus-9z").label());
		assertFalse(Files.exists(fonts().resolve("Renamed-Bold.ttf")));
		t.remove(Typeface.M_PLUS_ROUNDED);
		assertTrue(t.has(Typeface.M_PLUS_ROUNDED.id()), "the bundled typeface stays");
	}

	/**
	 * A font set to a typeface not imported here draws with the game font, its setting kept
	 * through other changes; importing it again brings it back.
	 */
	@Test
	void aSettingNamingATypefaceNotImportedIsKept() throws IOException {
		Files.createDirectories(dir.resolve("game.psaro"));
		Files.writeString(dir.resolve("game.psaro/fonts.json"),
				"{\"extraSpace\": {}, \"lettersFrom\": {\"a.bcfnt\": \"rounded-mplus-9z\", \"b.bcfnt\": \"m-plus-rounded-1c\"}}");
		PatchSettings s = PatchSettings.open(romfs());
		assertEquals(Typeface.GAME, s.lettersFrom("a.bcfnt"));
		assertEquals("rounded-mplus-9z", s.lettersFromId("a.bcfnt"));
		assertEquals(Map.of("b.bcfnt", Typeface.M_PLUS_ROUNDED), s.fontsDrawnFrom());

		s.setExtraSpace("b.bcfnt", "ty");
		assertEquals("rounded-mplus-9z", PatchSettings.open(romfs()).lettersFromId("a.bcfnt"), "kept when saved");

		s.typefaces().importFiles(List.of(TestFonts.renamed(dir.resolve("a"), "Bold")));
		assertEquals("rounded-mplus-9z", s.lettersFrom("a.bcfnt").id());
		assertEquals(2, s.fontsDrawnFrom().size());
	}
}
