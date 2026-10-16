package psaro.project;

import java.nio.file.Path;
import java.util.Locale;
import psaro.patch.PatchExport;

/**
 * What a 3DS title id says about the title: its category (the first eight hex digits) and, in the
 * last eight, the game it belongs to. DLC ({@code 0004008C}) shares its game's unique id (the
 * first six of those eight) with the game itself ({@code 00040000}), so a DLC project can tell
 * which game it belongs to without being told.
 */
public final class TitleIds {

	/** The category of downloadable content. */
	public static final String DLC = "0004008C";
	/** The category of a game. */
	public static final String APPLICATION = "00040000";

	private TitleIds() {
	}

	/** Whether {@code id} is the title id of downloadable content. */
	public static boolean isDlc(String id) {
		return PatchExport.isTitleId(id) && id.toUpperCase(Locale.ROOT).startsWith(DLC);
	}

	/** Whether {@code id} is the title id of a game. */
	public static boolean isApplication(String id) {
		return PatchExport.isTitleId(id) && id.toUpperCase(Locale.ROOT).startsWith(APPLICATION);
	}

	/** The unique id {@code id} shares with the other titles of its game (six hex digits), or null if it is no title id. */
	public static String uniqueId(String id) {
		return PatchExport.isTitleId(id) ? id.substring(8, 14).toUpperCase(Locale.ROOT) : null;
	}

	/** Whether {@code a} and {@code b} are different titles of one game: a game and its DLC, say. */
	public static boolean sameGame(String a, String b) {
		String ua = uniqueId(a);
		return ua != null && ua.equals(uniqueId(b)) && !a.equalsIgnoreCase(b);
	}

	/**
	 * The title id of the project whose romfs is {@code romfs}: {@code saved} (the one the export
	 * was last given) or, failing that, a folder in its path named for one, as Citra's mods folders
	 * are; null when neither is known.
	 */
	public static String of(Path romfs, String saved) {
		return PatchExport.isTitleId(saved) ? saved.toUpperCase(Locale.ROOT) : PatchExport.titleIdFrom(romfs);
	}
}
