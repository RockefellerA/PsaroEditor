package psaro.project;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The games opened last, newest first, for File → Recent: each one's romfs folder, kept in the
 * app's preferences. A game's DLC is not listed, as it opens again with its game.
 */
public final class RecentProjects {

	/** How many are kept. */
	public static final int MAX = 5;
	private static final String KEY = "recent";

	private RecentProjects() {
	}

	/** The romfs folders opened last, newest first; one that cannot be read as a path is left out. */
	public static List<Path> read(Preferences prefs) {
		List<Path> out = new ArrayList<>();
		for (String line : prefs.get(KEY, "").split("\n")) {
			if (!line.isBlank() && out.size() < MAX) {
				try {
					out.add(Path.of(line));
				} catch (InvalidPathException unreadable) {
					// dropped when the list is next written
				}
			}
		}
		return List.copyOf(out);
	}

	/** Puts {@code romfs} first, taking it out of wherever it was. */
	public static void add(Preferences prefs, Path romfs) {
		write(prefs, with(read(prefs), romfs));
	}

	/** Empties the list. */
	public static void clear(Preferences prefs) {
		prefs.remove(KEY);
	}

	/** {@code list} with {@code romfs} first and nowhere else, cut to {@link #MAX}. */
	static List<Path> with(List<Path> list, Path romfs) {
		Path first = romfs.toAbsolutePath().normalize();
		List<Path> out = new ArrayList<>();
		out.add(first);
		for (Path p : list) {
			if (out.size() < MAX && !p.toAbsolutePath().normalize().equals(first)) {
				out.add(p);
			}
		}
		return List.copyOf(out);
	}

	private static void write(Preferences prefs, List<Path> list) {
		prefs.put(KEY, String.join("\n", list.stream().map(Path::toString).toList()));
	}
}
