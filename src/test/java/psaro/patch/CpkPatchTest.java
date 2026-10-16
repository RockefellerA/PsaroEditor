package psaro.patch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Cpk;
import psaro.format.TextBlocks;
import psaro.patch.FontPatcher.Plan;
import psaro.patch.FontPatcher.Text;
import psaro.romfs.RomfsIndex;
import psaro.romfs.SampleCpk;

class CpkPatchTest {

	@TempDir
	Path dir;
	private Path romfs;
	private RomfsIndex index;
	private FontPatcher patcher;
	private PatchSettings settings;

	@BeforeEach
	void sample() throws IOException {
		romfs = dir.resolve("game");
		Files.createDirectories(romfs);
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("common/gg3d_unit.bin", SampleCpk.fieldTable(16, new int[] {4},
				List.of(new String[] {"ナシ"}, new String[] {"ガンダム"})));
		files.put("nakazawa/gg3d_damage_txt.bin", SampleCpk.textBlocks(List.of("・威力がそのままダメージ")));
		files.put("suzuki/mapscript/01/ev000.bin", SampleCpk.concat(SampleCpk.mapLine("ハッ！"), new byte[] {0x01}));
		Files.write(romfs.resolve("data.cpk"), SampleCpk.cpk(files, true, false));
		index = RomfsIndex.scan(romfs);
		settings = PatchSettings.open(romfs);
		patcher = new FontPatcher(index, settings, LayoutOverrides.open(romfs));
	}

	private Text text(String table, String key, String text) {
		return new Text(index.table(table), key, text);
	}

	private Path written() {
		return dir.resolve("game.psaro/romfs/data.cpk");
	}

	/**
	 * The plan says the CPK is behind; the patch rebuilds it into the patch folder with the
	 * English in each table, and copies it to the mods folder; after that the plan has nothing to do.
	 */
	@Test
	void patchRebuildsTheCpkOnceForTheSameEnglish() throws IOException {
		List<Text> texts = List.of(text("common/gg3d_unit", "001", "Gundam"),
				text("nakazawa/gg3d_damage_txt", "000", "・Damage as is"),
				text("suzuki/mapscript/01", "ev000:00", "Huh?!"));
		Plan plan = patcher.plan(texts);
		assertTrue(plan.hasWork());
		assertEquals(1, plan.cpksToWrite().size());
		assertEquals(3, plan.cpksToWrite().get(0).files().size());
		Path mods = dir.resolve("mods/romfs");
		settings.setCopyToMods(true, mods);
		assertTrue(patcher.write(plan, step -> { }).contains(written()));

		try (Cpk cpk = Cpk.open(written())) {
			byte[] damage = cpk.read(cpk.find("nakazawa/gg3d_damage_txt.bin"));
			assertEquals(Map.of("000", "・Damage as is"), TextBlocks.read(damage));
			byte[] script = cpk.read(cpk.find("suzuki/mapscript/01/ev000.bin"));
			assertArrayEquals(SampleCpk.concat(SampleCpk.mapLine("Huh?!"), new byte[] {0x01}), script);
			byte[] unit = cpk.read(cpk.find("common/gg3d_unit.bin"));
			assertTrue(new String(unit, Charset.forName("windows-31j")).contains("Gundam\0"));
		}
		assertTrue(Files.isRegularFile(mods.resolve("data.cpk")));
		assertFalse(patcher.plan(texts).hasWork(), "nothing changed since");

		List<Text> more = new ArrayList<>(texts);
		more.set(2, text("suzuki/mapscript/01", "ev000:00", "What?!"));
		assertTrue(patcher.plan(more).hasWork(), "a changed line puts it behind again");
	}

	/** A string the game's files cannot hold stays in Japanese and is named; the rest is written. */
	@Test
	void textTheFilesCannotHoldIsNamed() {
		Plan plan = patcher.plan(List.of(text("common/gg3d_unit", "001", "Gundám"),
				text("suzuki/mapscript/01", "ev000:00", "x".repeat(300))));
		assertEquals(2, plan.unwritable().size());
		assertTrue(plan.unwritable().get(0).startsWith("common/gg3d_unit 001"), plan.unwritable().get(0));
		assertTrue(plan.cpksToWrite().isEmpty(), "nothing writable changed, and nothing was written before");
	}

	/** Once no table has English, the patch removes the rebuilt CPK from the patch folder. */
	@Test
	void theCpkGoesWhenItsEnglishDoes() throws IOException {
		patcher.write(patcher.plan(List.of(text("common/gg3d_unit", "001", "Gundam"))), step -> { });
		assertTrue(Files.isRegularFile(written()));
		Plan none = patcher.plan(List.of());
		assertTrue(none.hasWork());
		patcher.write(none, step -> { });
		assertFalse(Files.exists(written()));
		assertFalse(patcher.plan(List.of()).hasWork());
	}
}
