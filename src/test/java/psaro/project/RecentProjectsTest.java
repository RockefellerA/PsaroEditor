package psaro.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The games opened last ({@link RecentProjects}). */
class RecentProjectsTest {

	@TempDir
	Path dir;

	private final Preferences prefs = Preferences.userRoot().node("psaro-test/" + UUID.randomUUID());

	@AfterEach
	void removeNode() throws BackingStoreException {
		prefs.removeNode();
	}

	/** The newest is first, one opened again moves to the top rather than showing twice, and only five are kept. */
	@Test
	void theNewestFiveAreKeptOnceEach() {
		assertEquals(List.of(), RecentProjects.read(prefs));
		for (String name : List.of("a", "b", "c", "d", "e", "f")) {
			RecentProjects.add(prefs, dir.resolve(name));
		}
		assertEquals(List.of(dir.resolve("f"), dir.resolve("e"), dir.resolve("d"), dir.resolve("c"), dir.resolve("b")),
				RecentProjects.read(prefs));

		RecentProjects.add(prefs, dir.resolve("c/../d"));
		assertEquals(List.of(dir.resolve("d"), dir.resolve("f"), dir.resolve("e"), dir.resolve("c"), dir.resolve("b")),
				RecentProjects.read(prefs));

		RecentProjects.clear(prefs);
		assertEquals(List.of(), RecentProjects.read(prefs));
	}
}
