package psaro.project;

import java.util.HashMap;
import java.util.Map;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.Kind;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * Where a DLC's strings show: in its game's panes, drawn with its game's fonts, as the DLC brings no
 * layouts of its own. Each DLC string is matched to a game string shown the same way, its stand-in,
 * whose panes it shares: so the game's font patch gives those fonts the DLC's letters too.
 *
 * <p>A DLC song's row ({@code 009a/contents}, key {@code 0501_FMS_003:12}) is a row of the game's
 * music table, so a data table's string stands in for it when it is in the same column of a row
 * whose id is shaped the same ({@code 0001_BMS_001}: digits and capitals where the DLC's are) and
 * some pane shows it; of the game's tables, the one with the most such strings shown. Any other
 * DLC string takes the string of the same key in the game's table of the same name, without the
 * content's folder.
 */
public final class DlcPanes {

	/** A game string a DLC string shows as. */
	public record StandIn(StringTable table, String key) {
	}

	private final RomfsIndex game;
	/** By row id shape and column ({@code 9999_AAA_999:12}), the shown game string of the table showing most of them. */
	private final Map<String, StandIn> byShape = new HashMap<>();

	public DlcPanes(RomfsIndex game) {
		this.game = game;
		// by shape and column, then table: how many of its strings some pane shows, and one of them
		Map<String, Map<StringTable, Integer>> counts = new HashMap<>();
		Map<String, StandIn> first = new HashMap<>();
		for (StringTable t : game.tables()) {
			if (t.kind() != Kind.DATA) {
				continue;
			}
			for (String key : t.strings().keySet()) {
				int colon = key.lastIndexOf(':');
				if (colon < 0 || game.panes(t, key).isEmpty()) {
					continue;
				}
				String shape = shape(key.substring(0, colon)) + key.substring(colon);
				counts.computeIfAbsent(shape, k -> new HashMap<>()).merge(t, 1, Integer::sum);
				first.putIfAbsent(shape + "!" + t.name(), new StandIn(t, key));
			}
		}
		counts.forEach((shape, byTable) -> byTable.entrySet().stream().max(Map.Entry.comparingByValue())
				.ifPresent(best -> byShape.put(shape, first.get(shape + "!" + best.getKey().name()))));
	}

	/**
	 * The game string {@code key} of the DLC's {@code table} shows as, or null when none is known.
	 * Only a string whose text the font patch reads for its letters alone stands in: never one of a
	 * {@code .tdt} table, whose text a layout may hold too, nor one packed in a CPK, which the patch
	 * writes back, so the DLC's text goes into no file of the game's.
	 */
	public StandIn standIn(StringTable table, String key) {
		StandIn s;
		if (table.kind() == Kind.DATA) {
			int colon = key.lastIndexOf(':');
			s = colon < 0 ? null : byShape.get(shape(key.substring(0, colon)) + key.substring(colon));
		} else {
			String name = table.name();
			StringTable same = game.table(name.substring(name.indexOf('/') + 1));
			s = same != null && same.strings().containsKey(key) && !game.panes(same, key).isEmpty() ? new StandIn(same, key)
					: null;
		}
		return s == null || s.table().kind() == Kind.TABLE || s.table().builtIn() || game.packed(s.table()) != null ? null : s;
	}

	/** A row id's shape: each digit 9, each capital A, each small letter a, the rest as it is. */
	static String shape(String row) {
		StringBuilder out = new StringBuilder(row.length());
		for (char c : row.toCharArray()) {
			out.append(Character.isDigit(c) ? '9' : Character.isUpperCase(c) ? 'A' : Character.isLowerCase(c) ? 'a' : c);
		}
		return out.toString();
	}
}
