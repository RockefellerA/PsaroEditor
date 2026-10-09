package psaro.patch;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.project.Translations;

/**
 * Changes to text panes' box and type settings, in {@code <romfs>.psaro/layouts.json} as
 * {@code {"blyt/common_btn_win_yes.bclyt": {"Txt_Btn": {"boxWidth": 40, "font": "SulaPro_B_04a_18.bcfnt"}}}}.
 * A change is made
 * per layout and pane, and so applies in every archive that carries that layout. The editor
 * measures with the changes at once; the patch writes them into the archives. Saved on every
 * change, and removed when nothing is changed.
 */
public final class LayoutOverrides {

	private static final String[] FIELDS = {"boxWidth", "boxHeight", "fontSizeX", "fontSizeY", "charSpace", "lineSpace"};
	/** Another of the layout's fonts, by file name. */
	private static final String FONT = "font";
	/** What the pane alone draws with: a typeface's id, or "game". */
	private static final String DRAW_WITH = "drawWith";

	private final Path file;
	/** Layout path to pane name to its change; never an empty change. */
	private final Map<String, Map<String, TextOverride>> changes = new TreeMap<>();

	private LayoutOverrides(Path file) {
		this.file = file;
	}

	/** Loads what was saved for {@code romfs}; nothing saved yet is not an error. */
	public static LayoutOverrides open(Path romfs) throws IOException {
		LayoutOverrides o = new LayoutOverrides(Translations.folderFor(romfs).resolve("layouts.json"));
		if (Files.isRegularFile(o.file)) {
			try {
				JSONObject json = new JSONObject(Files.readString(o.file, StandardCharsets.UTF_8));
				for (String layout : json.keySet()) {
					JSONObject panes = json.getJSONObject(layout);
					for (String pane : panes.keySet()) {
						JSONObject f = panes.getJSONObject(pane);
						Float[] v = new Float[FIELDS.length];
						for (int i = 0; i < FIELDS.length; i++) {
							v[i] = f.has(FIELDS[i]) ? (float) f.getDouble(FIELDS[i]) : null;
						}
						TextOverride t = new TextOverride(v[0], v[1], v[2], v[3], v[4], v[5],
								f.has(FONT) ? f.getString(FONT) : null, f.has(DRAW_WITH) ? f.getString(DRAW_WITH) : null);
						if (!t.isEmpty()) {
							o.changes.computeIfAbsent(layout, k -> new TreeMap<>()).put(pane, t);
						}
					}
				}
			} catch (JSONException e) {
				throw new IOException(o.file + " is not a valid list of layout changes: " + e.getMessage(), e);
			}
		}
		return o;
	}

	/** {@code pane}'s change in {@code layout}, or {@link TextOverride#NONE}. */
	public synchronized TextOverride get(String layout, String pane) {
		return changes.getOrDefault(layout, Map.of()).getOrDefault(pane, TextOverride.NONE);
	}

	/** {@code info}, the settings of {@code pane} in {@code layout}, as changed. */
	public TextInfo apply(String layout, String pane, TextInfo info) {
		return get(layout, pane).apply(info);
	}

	/** Every changed pane of {@code layout} by name; empty when none is. */
	public synchronized Map<String, TextOverride> forLayout(String layout) {
		return Map.copyOf(changes.getOrDefault(layout, Map.of()));
	}

	/** Every layout with a changed pane. */
	public synchronized Set<String> layouts() {
		return Set.copyOf(changes.keySet());
	}

	/** Gives each of {@code panes} in {@code layout} the change {@code t}; an empty one undoes it. */
	public synchronized void set(String layout, Collection<String> panes, TextOverride t) throws IOException {
		for (String pane : panes) {
			if (t.isEmpty()) {
				Map<String, TextOverride> m = changes.get(layout);
				if (m != null) {
					m.remove(pane);
					if (m.isEmpty()) {
						changes.remove(layout);
					}
				}
			} else {
				changes.computeIfAbsent(layout, k -> new TreeMap<>()).put(pane, t);
			}
		}
		save();
	}

	private void save() throws IOException {
		if (changes.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		JSONObject json = new JSONObject();
		changes.forEach((layout, panes) -> {
			JSONObject p = new JSONObject();
			panes.forEach((pane, t) -> {
				JSONObject f = new JSONObject();
				Float[] v = {t.boxWidth(), t.boxHeight(), t.fontSizeX(), t.fontSizeY(), t.charSpace(), t.lineSpace()};
				for (int i = 0; i < FIELDS.length; i++) {
					if (v[i] != null) {
						// as written, so 2.1 reads 2.1 and not 2.0999999
						f.put(FIELDS[i], new BigDecimal(Float.toString(v[i])));
					}
				}
				if (t.font() != null) {
					f.put(FONT, t.font());
				}
				if (t.drawWith() != null) {
					f.put(DRAW_WITH, t.drawWith());
				}
				p.put(pane, f);
			});
			json.put(layout, p);
		});
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}
}
