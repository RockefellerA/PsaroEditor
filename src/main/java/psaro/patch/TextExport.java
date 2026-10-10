package psaro.patch;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import psaro.format.Csv;
import psaro.format.Msgd;
import psaro.format.Tdt;
import psaro.project.Translations;
import psaro.romfs.RomfsIndex;
import psaro.romfs.RomfsIndex.StringTable;

/**
 * Writes the game's string tables with the English in them to {@code <romfs>.psaro/romfs/text},
 * its message files to {@code .../message} and its data tables to {@code .../table}, beside the
 * patched archives, so that folder is a romfs patch as it stands.
 *
 * <p>A table with English becomes a copy of its {@code .tdt} with each translated string
 * replaced and every other string left in Japanese; a table with none has no copy. The JSON in
 * {@code .psaro/english} stays the record of the work, since a {@code .tdt} cannot tell a string
 * kept in Japanese on purpose from one not translated yet; these copies are rebuilt from it.
 */
public final class TextExport {

	private TextExport() {
	}

	/**
	 * Brings every table's copy up to date with {@code translations}, removing the copies of
	 * tables left without English. Returns the files written; an unchanged one is not rewritten.
	 */
	public static List<Path> export(RomfsIndex index, Translations translations) throws IOException {
		Path output = FontPatcher.outputFor(index.root());
		List<Path> written = new ArrayList<>();
		for (StringTable table : index.tables()) {
			if (table.builtIn()) {
				// written into the layouts by the patch, having no file of its own
				continue;
			}
			Path out = output.resolve(index.root().relativize(table.path()).toString());
			Map<String, String> strings = new LinkedHashMap<>(table.strings());
			boolean english = false;
			for (String key : table.strings().keySet()) {
				if (translations.get(table, key) != null && !translations.keepsJapanese(table, key)) {
					strings.put(key, translations.get(table, key));
					english = true;
				}
			}
			if (!english) {
				Files.deleteIfExists(out);
				continue;
			}
			byte[] bytes = switch (table.kind()) {
				case MESSAGE -> Msgd.write(Files.readAllBytes(table.path()), new ArrayList<>(strings.values()));
				case DATA -> Csv.write(Files.readAllBytes(table.path()), strings);
				default -> Tdt.write(strings);
			};
			if (Files.isRegularFile(out) && Arrays.equals(bytes, Files.readAllBytes(out))) {
				continue;
			}
			Files.createDirectories(out.getParent());
			write(out, bytes);
			written.add(out);
		}
		return written;
	}

	/** Writes beside the target, then moves it into place, so a crash cannot leave half a file. */
	private static void write(Path file, byte[] bytes) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
