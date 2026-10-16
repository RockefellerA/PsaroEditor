package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import psaro.romfs.SampleRomfs;

class ParentLinkTest {

	@TempDir
	Path dir;
	private Path game;
	private Path dlc;

	@BeforeEach
	void romfs() throws IOException {
		game = dir.resolve("game");
		dlc = dir.resolve("dlc");
		SampleRomfs.table(game, "menu", Map.of("menu_0001", "はい"));
		SampleRomfs.table(dlc, "menu", Map.of("menu_0001", "はい"));
	}

	@Test
	void openingWritesNothing() throws IOException {
		ParentLink link = ParentLink.open(dlc);
		assertNull(link.parent());
		assertFalse(link.dismissed());
		assertFalse(Files.exists(Translations.folderFor(dlc)));
	}

	@Test
	void theLinkIsKeptBesideTheRomfsAndRead() throws IOException {
		ParentLink.open(dlc).link(game);
		ParentLink again = ParentLink.open(dlc);
		assertEquals(game.toAbsolutePath().normalize(), again.parent());
		assertTrue(again.parentExists());
		assertTrue(Files.isRegularFile(dir.resolve("dlc.psaro/parent.json")));

		again.unlink();
		assertNull(ParentLink.open(dlc).parent());
		assertFalse(Files.exists(dir.resolve("dlc.psaro/parent.json")), "nothing linked, nothing kept");
	}

	@Test
	void aMovedPairIsFoundByTheRelativePath() throws IOException {
		ParentLink.open(dlc).link(game);
		Path moved = dir.resolve("moved");
		Files.createDirectories(moved);
		Files.move(game, moved.resolve("game"));
		Files.move(dlc, moved.resolve("dlc"));
		Files.move(dir.resolve("dlc.psaro"), moved.resolve("dlc.psaro"));
		assertEquals(moved.resolve("game"), ParentLink.open(moved.resolve("dlc")).parent());
	}

	@Test
	void dismissingIsRememberedAndLinkingClearsIt() throws IOException {
		ParentLink link = ParentLink.open(dlc);
		link.dismiss();
		assertTrue(ParentLink.open(dlc).dismissed());
		link.link(game);
		assertFalse(ParentLink.open(dlc).dismissed());
	}

	@Test
	void aParentMustBeAnotherRomfs() throws IOException {
		ParentLink link = ParentLink.open(dlc);
		assertThrows(IOException.class, () -> link.link(dlc));
		Path empty = Files.createDirectories(dir.resolve("empty"));
		assertThrows(IOException.class, () -> link.link(empty));
		assertNull(link.parent());
	}
}
