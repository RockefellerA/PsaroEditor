package psaro.project;

import static java.util.Map.entry;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONException;
import org.json.JSONObject;
import psaro.text.ControlCodes;

/**
 * What each of the game's color codes is called and the color it shows. The game picks the
 * colors in its code, which PsaroEditor cannot read, so each code starts from {@link #DEFAULTS}
 * and the user corrects it against the game. A code's name is its tag in the editor
 * ({@code <GREEN>}); a code with no name shows as {@code {NN}}, and one with no color draws in
 * the pane's own color.
 *
 * <p>Only what the user changes is kept, in {@code <romfs>.psaro/colors.json} as
 * {@code {"02": {"name": "GREEN", "color": "#5FD35F"}, ...}}; it is written on every change and
 * removed when nothing differs from the defaults.
 */
public final class CodeColors {

	/** A code's tag name (null for none) and color (null for the pane's own color). */
	public record Style(String name, Color color) {
	}

	/**
	 * Where each code starts. The names are the ones TFF Text Editor gives the same codes in
	 * Theatrhythm Final Fantasy, which shares this game's engine and text format; the colors are
	 * guesses from those names until they are matched to the game. Codes it does not name start
	 * unnamed, in a stand-in color.
	 */
	public static final Map<Integer, Style> DEFAULTS = Map.ofEntries(
			entry(0x01, new Style("WHITE", new Color(0xFFFFFF))),
			entry(0x02, new Style("GREEN", new Color(0x5FD35F))),
			entry(0x03, new Style("ORANGE", new Color(0xFFA033))),
			entry(0x05, new Style("YELLOW", new Color(0xFFE04D))),
			entry(0x06, new Style("GREEN_TUTORIAL", new Color(0x2E9E3A))),
			entry(0x07, new Style("ORANGE_TUTORIAL", new Color(0xE07800))),
			entry(0x09, new Style("BLACK_TUTORIAL", new Color(0x000000))),
			entry(0x10, new Style(null, new Color(0xD9A6FF))),
			entry(0x12, new Style(null, new Color(0xFF9CD9))),
			entry(0x15, new Style(null, new Color(0x9CF0E6))));

	private static final Style NONE = new Style(null, null);

	private final Path file;
	/** What the user set; a field left null keeps the default. An empty name means "no name". */
	private final Map<Integer, Style> changed = new TreeMap<>();

	private CodeColors(Path file) {
		this.file = file;
	}

	/** Loads what was saved for {@code romfs}; nothing saved yet is not an error. */
	public static CodeColors open(Path romfs) throws IOException {
		CodeColors c = new CodeColors(Translations.folderFor(romfs).resolve("colors.json"));
		if (Files.isRegularFile(c.file)) {
			try {
				JSONObject json = new JSONObject(Files.readString(c.file, StandardCharsets.UTF_8));
				for (String key : json.keySet()) {
					int code = Integer.parseInt(key, 16);
					Object value = json.get(key);
					if (value instanceof String color) {
						c.changed.put(code, new Style(null, Color.decode(color)));
					} else {
						JSONObject o = json.getJSONObject(key);
						String name = o.has("name") ? o.getString("name") : null;
						if (name != null && !name.isEmpty() && !ControlCodes.NAME.matcher(name).matches()) {
							throw new IOException(c.file + ": \"" + name + "\" is not a valid name for code " + key);
						}
						c.changed.put(code, new Style(name, o.has("color") ? Color.decode(o.getString("color")) : null));
					}
				}
			} catch (JSONException | NumberFormatException e) {
				throw new IOException(c.file + " is not a valid color list: " + e.getMessage(), e);
			}
		}
		return c;
	}

	public Path file() {
		return file;
	}

	/** {@code code}'s tag name, or null when it shows as {@code {NN}}. */
	public String name(int code) {
		Style mine = changed.get(code);
		String name = mine != null && mine.name() != null ? mine.name() : DEFAULTS.getOrDefault(code, NONE).name();
		return name == null || name.isEmpty() ? null : name;
	}

	/** {@code code}'s color, or null when it draws in the pane's own color. */
	public Color color(int code) {
		Style mine = changed.get(code);
		return mine != null && mine.color() != null ? mine.color() : DEFAULTS.getOrDefault(code, NONE).color();
	}

	/** Every named code's name, for the editor's tags. */
	public Map<Integer, String> names() {
		Map<Integer, String> out = new HashMap<>();
		for (int code : codes()) {
			String n = name(code);
			if (n != null) {
				out.put(code, n);
			}
		}
		return Collections.unmodifiableMap(out);
	}

	/** Every code's color as the preview draws it; a code missing here uses the pane's color. */
	public Map<Integer, Color> palette() {
		Map<Integer, Color> out = new HashMap<>();
		for (int code : codes()) {
			Color c = color(code);
			if (c != null) {
				out.put(code, c);
			}
		}
		return Collections.unmodifiableMap(out);
	}

	/** Whether the user has changed {@code code}'s name or color. */
	public boolean isChanged(int code) {
		return changed.containsKey(code);
	}

	public void setColor(int code, Color color) throws IOException {
		Style mine = changed.getOrDefault(code, NONE);
		update(code, new Style(mine.name(), new Color(color.getRGB() & 0xFFFFFF)));
	}

	/**
	 * Names {@code code}; an empty name leaves it unnamed. Throws {@link IllegalArgumentException}
	 * for a name that is not {@link ControlCodes#NAME capitals, digits and underscores} or that
	 * another code already has.
	 */
	public void setName(int code, String name) throws IOException {
		String n = name == null ? "" : name.strip().toUpperCase(Locale.ROOT);
		if (!n.isEmpty() && !ControlCodes.NAME.matcher(n).matches()) {
			throw new IllegalArgumentException("A name is capital letters, digits and underscores, starting with a letter.");
		}
		for (Map.Entry<Integer, String> e : names().entrySet()) {
			if (e.getKey() != code && e.getValue().equals(n)) {
				throw new IllegalArgumentException(String.format("{%02X} is already called %s.", e.getKey(), n));
			}
		}
		Style mine = changed.getOrDefault(code, NONE);
		update(code, new Style(n, mine.color()));
	}

	/** Back to the default name and color. */
	public void reset(int code) throws IOException {
		if (changed.remove(code) != null) {
			save();
		}
	}

	/** Records a change, dropping whatever now matches the default. */
	private void update(int code, Style style) throws IOException {
		Style def = DEFAULTS.getOrDefault(code, NONE);
		String name = style.name() != null && style.name().equals(def.name() == null ? "" : def.name()) ? null : style.name();
		Color color = Objects.equals(style.color(), def.color()) ? null : style.color();
		if (name == null && color == null) {
			changed.remove(code);
		} else {
			changed.put(code, new Style(name, color));
		}
		save();
	}

	private TreeSet<Integer> codes() {
		TreeSet<Integer> all = new TreeSet<>(DEFAULTS.keySet());
		all.addAll(changed.keySet());
		return all;
	}

	private void save() throws IOException {
		if (changed.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		StringBuilder out = new StringBuilder("{\n");
		int i = 0;
		for (Map.Entry<Integer, Style> e : changed.entrySet()) {
			Style s = e.getValue();
			StringBuilder fields = new StringBuilder();
			if (s.name() != null) {
				fields.append("\"name\": ").append(JSONObject.quote(s.name()));
			}
			if (s.color() != null) {
				fields.append(fields.isEmpty() ? "" : ", ")
						.append(String.format(Locale.ROOT, "\"color\": \"#%06X\"", s.color().getRGB() & 0xFFFFFF));
			}
			out.append(String.format(Locale.ROOT, "  \"%02X\": {%s}", e.getKey(), fields));
			out.append(++i < changed.size() ? ",\n" : "\n");
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, out.append("}\n"), StandardCharsets.UTF_8);
	}
}
