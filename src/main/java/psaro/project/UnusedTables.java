package psaro.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * The string tables the user has marked as unused: ones the game never reads, such as a table
 * left over from the game this one was built from. An unused table can still be translated; it
 * is only left out of the progress count.
 *
 * <p>Kept beside the translations in {@code <romfs>.psaro/unused-tables.json} as a sorted list of
 * table names, written on every change and removed when no table is marked.
 */
public final class UnusedTables {

	private final Path file;
	private final Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

	private UnusedTables(Path file) {
		this.file = file;
	}

	/** Loads the marks saved for {@code romfs}; none saved yet is not an error. */
	public static UnusedTables open(Path romfs) throws IOException {
		UnusedTables u = new UnusedTables(Translations.folderFor(romfs).resolve("unused-tables.json"));
		if (Files.isRegularFile(u.file)) {
			try {
				JSONArray json = new JSONArray(Files.readString(u.file, StandardCharsets.UTF_8));
				for (int i = 0; i < json.length(); i++) {
					u.names.add(json.getString(i));
				}
			} catch (JSONException e) {
				throw new IOException(u.file + " is not a valid list of table names: " + e.getMessage(), e);
			}
		}
		return u;
	}

	public boolean isUnused(StringTable table) {
		return names.contains(table.name());
	}

	public void setUnused(StringTable table, boolean unused) throws IOException {
		boolean changed = unused ? names.add(table.name()) : names.remove(table.name());
		if (changed) {
			save();
		}
	}

	/** The tables in {@code tables} that are not marked unused, in order. */
	public List<StringTable> inUse(Collection<StringTable> tables) {
		return tables.stream().filter(t -> !isUnused(t)).toList();
	}

	private void save() throws IOException {
		if (names.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		StringBuilder out = new StringBuilder("[\n");
		int i = 0;
		for (String name : names) {
			out.append("  ").append(org.json.JSONObject.quote(name)).append(++i < names.size() ? ",\n" : "\n");
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, out.append("]\n"), StandardCharsets.UTF_8);
	}
}
