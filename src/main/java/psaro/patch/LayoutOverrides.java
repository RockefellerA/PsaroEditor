package psaro.patch;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.format.Bclyt.PaneOverride;
import psaro.format.Bclyt.TextInfo;
import psaro.format.Bclyt.TextOverride;
import psaro.project.Translations;

/**
 * Changes to text panes' box and type settings, and to where any pane sits, how big it is and its
 * depth, in {@code <romfs>.psaro/layouts.json} as
 * {@code {"blyt/common_btn_win_yes.bclyt": {"Txt_Btn": {"boxWidth": 40, "font": "SulaPro_B_04a_18.bcfnt"}}}}
 * and {@code {"blyt/config_common.bclyt": {"Pic_Fram_Resu": {"x": 54, "width": 78, "z": 0}}}}.
 * A change is made
 * per layout and pane, and so applies in every archive that carries that layout. The editor
 * measures with the changes at once; the patch writes them into the archives. Saved on every
 * change, and removed when nothing is changed.
 */
public final class LayoutOverrides {

	private static final String[] FIELDS = {"boxWidth", "boxHeight", "fontSizeX", "fontSizeY", "charSpace", "lineSpace"};
	/** Where a pane sits, how big it is and its depth ({@link PaneOverride}). */
	private static final String[] PLACE = {"x", "y", "width", "height", "z"};
	/** Another of the layout's fonts, by file name. */
	private static final String FONT = "font";
	/** What the pane alone draws with: a typeface's id, or "game". */
	private static final String DRAW_WITH = "drawWith";

	private final Path file;
	/** Layout path to pane name to its change; never an empty change. */
	private final Map<String, Map<String, TextOverride>> changes = new TreeMap<>();
	/** Layout path to pane name to where it is moved and how it is sized; never an empty one. */
	private final Map<String, Map<String, PaneOverride>> places = new TreeMap<>();

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
						Float[] p = new Float[PLACE.length];
						for (int i = 0; i < PLACE.length; i++) {
							p[i] = f.has(PLACE[i]) ? (float) f.getDouble(PLACE[i]) : null;
						}
						PaneOverride place = new PaneOverride(p[0], p[1], p[2], p[3], p[4]);
						if (!place.isEmpty()) {
							o.places.computeIfAbsent(layout, k -> new TreeMap<>()).put(pane, place);
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

	/** Every layout with a changed pane, its text settings or its place. */
	public synchronized Set<String> layouts() {
		Set<String> out = new TreeSet<>(changes.keySet());
		out.addAll(places.keySet());
		return Set.copyOf(out);
	}

	/** Gives each of {@code panes} in {@code layout} the change {@code t}; an empty one undoes it. */
	public synchronized void set(String layout, Collection<String> panes, TextOverride t) throws IOException {
		put(changes, layout, panes, t, t.isEmpty());
		save();
	}

	/** Where {@code pane} in {@code layout} is moved and how it is sized, or {@link PaneOverride#NONE}. */
	public synchronized PaneOverride place(String layout, String pane) {
		return places.getOrDefault(layout, Map.of()).getOrDefault(pane, PaneOverride.NONE);
	}

	/** Every moved or sized pane of {@code layout} by name; empty when none is. */
	public synchronized Map<String, PaneOverride> placesForLayout(String layout) {
		return Map.copyOf(places.getOrDefault(layout, Map.of()));
	}

	/** Moves and sizes {@code pane} in {@code layout} as {@code p} says; an empty one puts it back. */
	public synchronized void setPlace(String layout, String pane, PaneOverride p) throws IOException {
		setPlaces(layout, Map.of(pane, p));
	}

	/** Moves and sizes each pane of {@code layout} named in {@code panes}, saving once; an empty one puts it back. */
	public synchronized void setPlaces(String layout, Map<String, PaneOverride> panes) throws IOException {
		panes.forEach((pane, p) -> put(places, layout, List.of(pane), p, p.isEmpty()));
		save();
	}

	private static <T> void put(Map<String, Map<String, T>> into, String layout, Collection<String> panes, T value, boolean empty) {
		for (String pane : panes) {
			if (empty) {
				Map<String, T> m = into.get(layout);
				if (m != null) {
					m.remove(pane);
					if (m.isEmpty()) {
						into.remove(layout);
					}
				}
			} else {
				into.computeIfAbsent(layout, k -> new TreeMap<>()).put(pane, value);
			}
		}
	}

	private void save() throws IOException {
		if (changes.isEmpty() && places.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		Map<String, Map<String, JSONObject>> all = new TreeMap<>();
		changes.forEach((layout, panes) -> panes.forEach((pane, t) -> {
			JSONObject f = all.computeIfAbsent(layout, k -> new TreeMap<>()).computeIfAbsent(pane, k -> new JSONObject());
			Float[] v = {t.boxWidth(), t.boxHeight(), t.fontSizeX(), t.fontSizeY(), t.charSpace(), t.lineSpace()};
			putFloats(f, FIELDS, v);
			if (t.font() != null) {
				f.put(FONT, t.font());
			}
			if (t.drawWith() != null) {
				f.put(DRAW_WITH, t.drawWith());
			}
		}));
		places.forEach((layout, panes) -> panes.forEach((pane, p) -> {
			JSONObject f = all.computeIfAbsent(layout, k -> new TreeMap<>()).computeIfAbsent(pane, k -> new JSONObject());
			putFloats(f, PLACE, new Float[] {p.x(), p.y(), p.width(), p.height(), p.z()});
		}));
		JSONObject json = new JSONObject();
		all.forEach((layout, panes) -> {
			JSONObject p = new JSONObject();
			panes.forEach(p::put);
			json.put(layout, p);
		});
		Files.createDirectories(file.getParent());
		Files.writeString(file, json.toString(2) + "\n", StandardCharsets.UTF_8);
	}

	private static void putFloats(JSONObject f, String[] names, Float[] values) {
		for (int i = 0; i < names.length; i++) {
			if (values[i] != null) {
				// as written, so 2.1 reads 2.1 and not 2.0999999
				f.put(names[i], new BigDecimal(Float.toString(values[i])));
			}
		}
	}
}
