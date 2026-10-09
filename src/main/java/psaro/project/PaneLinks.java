package psaro.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.PaneRef;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * The panes a string the game draws from code is shown in, as found by looking at the game, in
 * {@code <romfs>.psaro/links.json} as
 * {@code {"message/tutorial_and_help": {"062": [{"layout": "blyt/tutorial_btn_yes.bclyt", "pane": "Txt_Btn"}]}}}.
 * No layout names such a string (a message file's, say), so without them the editor cannot
 * preview it or check its fit, and the patch cannot tell which fonts need its English.
 * Applied to the index on opening ({@link RomfsIndex#link}); saved on every change.
 */
public final class PaneLinks {

	private final RomfsIndex index;
	private final Path file;
	/** Table name to key to its panes; never an empty list. */
	private final Map<String, Map<String, List<PaneRef>>> links = new TreeMap<>();

	private PaneLinks(RomfsIndex index, Path file) {
		this.index = index;
		this.file = file;
	}

	/** Loads what was saved for {@code index}'s romfs and links it; nothing saved yet is not an error. */
	public static PaneLinks open(RomfsIndex index) throws IOException {
		PaneLinks l = new PaneLinks(index, Translations.folderFor(index.root()).resolve("links.json"));
		if (!Files.isRegularFile(l.file)) {
			return l;
		}
		try {
			JSONObject json = new JSONObject(Files.readString(l.file, StandardCharsets.UTF_8));
			for (String table : json.keySet()) {
				JSONObject keys = json.getJSONObject(table);
				for (String key : keys.keySet()) {
					JSONArray panes = keys.getJSONArray(key);
					List<PaneRef> refs = new ArrayList<>();
					for (int i = 0; i < panes.length(); i++) {
						JSONObject p = panes.getJSONObject(i);
						refs.add(new PaneRef(p.getString("layout"), p.getString("pane")));
					}
					if (!refs.isEmpty()) {
						l.links.computeIfAbsent(table, k -> new TreeMap<>()).put(key, List.copyOf(refs));
					}
				}
			}
		} catch (JSONException e) {
			throw new IOException(l.file + " is not valid: " + e.getMessage(), e);
		}
		l.links.forEach((table, keys) -> {
			StringTable t = index.table(table);
			if (t != null) {
				keys.forEach((key, refs) -> index.link(t, key, refs));
			}
		});
		return l;
	}

	/** The panes linked to {@code key} of {@code table}; none when it has no links. */
	public synchronized List<PaneRef> get(StringTable table, String key) {
		return links.getOrDefault(table.name(), Map.of()).getOrDefault(key, List.of());
	}

	/** Links {@code panes} to {@code key} of {@code table} in place of what it had, and saves; none unlinks. */
	public synchronized void set(StringTable table, String key, Collection<PaneRef> panes) throws IOException {
		Map<String, List<PaneRef>> keys = links.computeIfAbsent(table.name(), k -> new TreeMap<>());
		if (panes.isEmpty()) {
			keys.remove(key);
			if (keys.isEmpty()) {
				links.remove(table.name());
			}
		} else {
			keys.put(key, List.copyOf(panes));
		}
		index.link(table, key, panes);
		save();
	}

	private void save() throws IOException {
		if (links.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		JSONObject json = new JSONObject();
		links.forEach((table, keys) -> {
			JSONObject k = new JSONObject();
			keys.forEach((key, refs) -> {
				JSONArray panes = new JSONArray();
				refs.forEach(r -> panes.put(new JSONObject().put("layout", r.layout()).put("pane", r.pane())));
				k.put(key, panes);
			});
			json.put(table, k);
		});
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, json.toString(2) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
