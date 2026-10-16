package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TitleIdsTest {

	private static final String GAME = "0004000000123400";
	private static final String DLC = "0004008C00123400";

	@Test
	void categoriesAreToldApart() {
		assertTrue(TitleIds.isDlc(DLC));
		assertTrue(TitleIds.isDlc(DLC.toLowerCase()));
		assertFalse(TitleIds.isDlc(GAME));
		assertTrue(TitleIds.isApplication(GAME));
		assertFalse(TitleIds.isDlc(null));
		assertFalse(TitleIds.isDlc("0004008C"), "sixteen digits make a title id");
	}

	@Test
	void aGameAndItsDlcShareAUniqueId() {
		assertEquals("001234", TitleIds.uniqueId(DLC));
		assertEquals("001234", TitleIds.uniqueId(GAME));
		assertTrue(TitleIds.sameGame(GAME, DLC));
		assertTrue(TitleIds.sameGame(DLC, "0004000000123401"), "another region's variation of the game");
		assertFalse(TitleIds.sameGame(GAME, "0004008C00999900"));
		assertFalse(TitleIds.sameGame(GAME, GAME), "a title is not its own sibling");
		assertFalse(TitleIds.sameGame(GAME, null));
		assertNull(TitleIds.uniqueId("nope"));
	}

	@Test
	void theIdIsTheSavedOneOrThePathsFolder() {
		Path romfs = Path.of("mods", DLC, "romfs");
		assertEquals(DLC, TitleIds.of(romfs, null));
		assertEquals(GAME, TitleIds.of(romfs, GAME.toLowerCase()), "the saved one wins");
		assertEquals(DLC, TitleIds.of(romfs, "garbage"));
		assertNull(TitleIds.of(Path.of("extracted", "romfs"), null));
	}
}
