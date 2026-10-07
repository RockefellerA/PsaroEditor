package psaro.project;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.text.ControlCodes;

/**
 * Finds a phrase in every string table: in the keys, the Japanese and the English.
 *
 * <p>Matching ignores case and color codes, reads a line break as a space so a phrase can run
 * across one, and compares full-width and half-width forms alike (NFKC), so {@code ＨＰ} finds
 * {@code HP} and half-width katakana finds the full-width text.
 */
public final class StringSearch {

	/** One string that matches. */
	public record Hit(StringTable table, String key) {
	}

	/** The first matches, up to the limit asked for, and how many there are in all. */
	public record Result(List<Hit> hits, int total) {
	}

	private final RomfsIndex index;
	private final Translations translations;
	/** Each table's Japanese as matched, by key; it never changes, so it is worked out once. */
	private final Map<String, Map<String, String>> japanese = new HashMap<>();

	public StringSearch(RomfsIndex index, Translations translations) {
		this.index = index;
		this.translations = translations;
	}

	/** Every string matching {@code query}, in table and file order; at most {@code limit} listed. */
	public Result find(String query, int limit) {
		String needle = normalize(query);
		if (needle.isEmpty()) {
			return new Result(List.of(), 0);
		}
		List<Hit> hits = new ArrayList<>();
		int total = 0;
		for (StringTable table : index.tables()) {
			Map<String, String> jp = japanese(table);
			for (String key : table.strings().keySet()) {
				String en = translations.get(table, key);
				if (key.toLowerCase(Locale.ROOT).contains(needle) || jp.get(key).contains(needle)
						|| en != null && normalize(en).contains(needle)) {
					total++;
					if (hits.size() < limit) {
						hits.add(new Hit(table, key));
					}
				}
			}
		}
		return new Result(List.copyOf(hits), total);
	}

	private Map<String, String> japanese(StringTable table) {
		return japanese.computeIfAbsent(table.name(), n -> {
			Map<String, String> out = new HashMap<>();
			table.strings().forEach((k, v) -> out.put(k, normalize(v)));
			return out;
		});
	}

	/** Text as it is matched: no color codes, line breaks as spaces, one space at most, NFKC, lower case. */
	static String normalize(String text) {
		StringBuilder plain = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == ControlCodes.COLOUR) {
				i++;
			} else if (c == '\n') {
				plain.append(' ');
			} else if (c >= 0x20) {
				plain.append(c);
			}
		}
		String n = Normalizer.normalize(plain, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
		return n.replaceAll("\\s+", " ").strip();
	}
}
