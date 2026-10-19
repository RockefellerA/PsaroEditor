package psaro.project;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * Gives a DLC's strings the English its game already has for them: a DLC string with nothing yet
 * whose Japanese reads exactly as game strings that were all done one way (the same English, or
 * all kept in Japanese) is done that way too. A DLC song's game title, ドラゴンクエストV 天空の花嫁,
 * reads as it does in the game's own music table. A Japanese text the game did two ways is left
 * alone, and counted. Strings the DLC has done itself are never changed, so it is safe to do
 * again after the game has moved on; nothing is saved, and the game is never changed.
 */
public final class DlcInherit {

	/** What was filled in: strings given English, strings kept in Japanese, and strings the game did more than one way. */
	public record Result(int english, int kept, int ambiguous) {

		public int filled() {
			return english + kept;
		}

		/** A line or two for the user. */
		public String summary() {
			String out = filled() == 0 ? "No DLC string without English reads the same as a game string with it."
					: String.format("Filled %,d DLC strings from the game's English%s.", filled(),
							kept == 0 ? "" : String.format(" (%,d of them kept in Japanese, as the game keeps them)", kept));
			if (ambiguous > 0) {
				out += String.format("%n%,d more read the same as game strings translated more than one way, so were left for you.",
						ambiguous);
			}
			return out;
		}
	}

	/** One way the game did a Japanese text: its English, or kept in Japanese. */
	private record Done(String english, boolean keep) {
	}

	private DlcInherit() {
	}

	/** Fills {@code dlcText}'s strings without English from {@code gameText}'s, as the class says; nothing is saved. */
	public static Result fill(RomfsIndex game, Translations gameText, RomfsIndex dlc, Translations dlcText) {
		Map<String, Set<Done>> byJapanese = new HashMap<>();
		for (StringTable table : game.tables()) {
			for (Map.Entry<String, String> s : table.strings().entrySet()) {
				Done d = done(gameText, table, s.getKey());
				if (d != null && !s.getValue().isBlank()) {
					byJapanese.computeIfAbsent(s.getValue(), k -> new HashSet<>()).add(d);
				}
			}
		}
		int english = 0;
		int kept = 0;
		int ambiguous = 0;
		for (StringTable table : dlc.tables()) {
			for (Map.Entry<String, String> s : table.strings().entrySet()) {
				if (s.getValue().isBlank() || dlcText.get(table, s.getKey()) != null) {
					continue;
				}
				Set<Done> ways = byJapanese.getOrDefault(s.getValue(), Set.of());
				if (ways.size() > 1) {
					ambiguous++;
				} else if (ways.size() == 1) {
					Done d = ways.iterator().next();
					if (d.keep()) {
						dlcText.setKeepsJapanese(table, s.getKey(), true);
						kept++;
					} else {
						dlcText.set(table, s.getKey(), d.english());
						english++;
					}
				}
			}
		}
		return new Result(english, kept, ambiguous);
	}

	private static Done done(Translations t, StringTable table, String key) {
		if (t.keepsJapanese(table, key)) {
			return new Done(null, true);
		}
		String english = t.get(table, key);
		return english == null ? null : new Done(english, false);
	}
}
