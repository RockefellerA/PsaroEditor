package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Csv;
import psaro.format.Lz11;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.SampleRomfs;

/** A DLC attached to its game: its text written back compressed, and its settings inherited from the game's. */
class DlcPatchTest {

	@TempDir
	Path dir;

	@AfterEach
	void detach() {
		Translations.detachDlc(dir.resolve("TDQDLC_romfs"));
	}

	/** Each content's table is written back compressed, under its content's folder in the game project's DLC folder. */
	@Test
	void aContentsTableIsWrittenBackCompressed() throws IOException {
		Path dlc = dir.resolve("TDQDLC_romfs");
		SampleRomfs.content(dlc, "009a", List.of("0501_FMS_003,0,True,王宮のトランペット,ドラゴンクエストV 天空の花嫁"));
		SampleRomfs.content(dlc, "009b", List.of("0401_FMS_003,0,True,馬車のマーチ,ドラゴンクエストIV 導かれし者たち"));
		Translations.attachDlc(dlc, dir.resolve("game"));
		RomfsIndex index = RomfsIndex.scanDlc(dlc, List.of("009a", "009b"));
		Translations t = Translations.open(index);
		t.set(index.table("009a/contents"), "0501_FMS_003:3", "Royal Trumpet");

		Path out = dir.resolve("game.psaro/DLC/romfs/009a/contents.csv.lz").toAbsolutePath();
		assertEquals(List.of(out), TextExport.export(index, t).stream().map(Path::toAbsolutePath).toList());
		byte[] written = Files.readAllBytes(out);
		assertTrue(Lz11.isCompressed(written));
		assertEquals(Map.of("0501_FMS_003:4", "ドラゴンクエストV 天空の花嫁"), Csv.read(Lz11.decompress(written)));
		assertTrue(new String(Lz11.decompress(written), java.nio.charset.StandardCharsets.UTF_16LE).contains("Royal Trumpet"));
		// a content with no English has no copy
		assertFalse(Files.exists(dir.resolve("game.psaro/DLC/romfs/009b")));
		// unchanged, it is not written again
		assertEquals(List.of(), TextExport.export(index, t));
	}

	/** A DLC takes the game's settings for a font it sets nothing for, keeps its own, and shares the game's typefaces. */
	@Test
	void aDlcInheritsTheGamesFontSettings() throws IOException {
		Path game = dir.resolve("game");
		Path dlc = dir.resolve("TDQDLC_romfs");
		PatchSettings gameSettings = PatchSettings.open(game);
		gameSettings.setExtraSpace("SulaPro_B_04a_20.bcfnt", "ty");
		gameSettings.setLettersFrom("SulaPro_B_04a_20.bcfnt", Typeface.M_PLUS_ROUNDED);
		gameSettings.setTitleId("0004000000140000");
		Translations.attachDlc(dlc, game);
		PatchSettings s = PatchSettings.openDlc(dlc, gameSettings);
		assertTrue(s.inherits());
		assertSame(gameSettings.typefaces(), s.typefaces());
		assertEquals("ty", s.extraSpace("SulaPro_B_04a_20.bcfnt"));
		assertEquals(Typeface.M_PLUS_ROUNDED, s.lettersFrom("SulaPro_B_04a_20.bcfnt"));
		assertEquals(Map.of("SulaPro_B_04a_20.bcfnt", Typeface.M_PLUS_ROUNDED), s.fontsDrawnFrom());
		assertEquals(null, s.titleId());

		// the game changing a setting the DLC inherits is seen at once
		gameSettings.setExtraSpace("SulaPro_B_04a_20.bcfnt", "tyf");
		assertEquals("tyf", s.extraSpace("SulaPro_B_04a_20.bcfnt"));

		// the DLC's own settings, even none and the game font, stand against the game's, and are kept in its project
		s.setExtraSpace("SulaPro_B_04a_20.bcfnt", "");
		s.setLettersFrom("SulaPro_B_04a_20.bcfnt", Typeface.GAME);
		assertEquals("", s.extraSpace("SulaPro_B_04a_20.bcfnt"));
		assertEquals(Typeface.GAME, s.lettersFrom("SulaPro_B_04a_20.bcfnt"));
		assertEquals(Map.of(), s.fontsDrawnFrom());
		assertTrue(Files.isRegularFile(dir.resolve("game.psaro/DLC/fonts.json")));
		PatchSettings again = PatchSettings.openDlc(dlc, gameSettings);
		assertEquals("", again.extraSpace("SulaPro_B_04a_20.bcfnt"));
		assertEquals(Typeface.GAME, again.lettersFrom("SulaPro_B_04a_20.bcfnt"));
		// and the game's are its own still
		assertEquals("tyf", PatchSettings.open(game).extraSpace("SulaPro_B_04a_20.bcfnt"));
	}
}
