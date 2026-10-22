package psaro.romfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What loose images were found to be, kept between runs so an image file whose size and time are
 * as they were is not decompressed and hashed again: a game can have thousands. Kept by each
 * file's full path, one tab-separated line per file: its path, size and time (ms), then the
 * BCLIM's hash, width, height and format, or {@code -} for a file that is no BCLIM this can read.
 */
public final class ImageHashCache {

	/** What an image file was found to hold. */
	public record Entry(String hash, int width, int height, int format) {
	}

	/** How an image file is read when it is not known: its entry, or null when it is no image. */
	@FunctionalInterface
	public interface Reader {
		Entry read(Path file) throws IOException;
	}

	/** A file's entry, null for one that is no image, as of the size and time it had. */
	private record Kept(long size, long time, Entry entry) {
	}

	private static final String UNREADABLE = "-";

	private final Path file;
	private final Map<String, Kept> kept = new ConcurrentHashMap<>();
	private volatile boolean dirty;

	private ImageHashCache(Path file) {
		this.file = file;
	}

	/** The cache kept in {@code file}; empty when there is none yet or it cannot be read. */
	public static ImageHashCache open(Path file) {
		ImageHashCache c = new ImageHashCache(file);
		if (!Files.isRegularFile(file)) {
			return c;
		}
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String[] f = line.split("\t");
				try {
					if (f.length == 4 && f[3].equals(UNREADABLE)) {
						c.kept.put(f[0], new Kept(Long.parseLong(f[1]), Long.parseLong(f[2]), null));
					} else if (f.length == 7) {
						c.kept.put(f[0], new Kept(Long.parseLong(f[1]), Long.parseLong(f[2]),
								new Entry(f[3], Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]))));
					}
				} catch (NumberFormatException bad) {
					// read again when asked for
				}
			}
		} catch (IOException unreadable) {
			// every image is read again, and the cache written afresh
		}
		return c;
	}

	/** {@code image}'s entry: as kept when the file's size and time are unchanged, else read with {@code reader}. */
	public Entry entry(Path image, Reader reader) throws IOException {
		BasicFileAttributes a = Files.readAttributes(image, BasicFileAttributes.class);
		long size = a.size();
		long time = a.lastModifiedTime().toMillis();
		String key = image.toAbsolutePath().normalize().toString();
		Kept k = kept.get(key);
		if (k != null && k.size() == size && k.time() == time) {
			return k.entry();
		}
		Entry e = reader.read(image);
		kept.put(key, new Kept(size, time, e));
		dirty = true;
		return e;
	}

	/** Writes what changed since the cache was read or last saved, leaving out files no longer there. */
	public synchronized void save() throws IOException {
		if (!dirty) {
			return;
		}
		dirty = false;
		List<String> lines = new ArrayList<>();
		new TreeMap<>(kept).forEach((path, k) -> {
			if (Files.exists(Path.of(path))) {
				Entry e = k.entry();
				lines.add(path + "\t" + k.size() + "\t" + k.time() + "\t" + (e == null ? UNREADABLE
						: e.hash() + "\t" + e.width() + "\t" + e.height() + "\t" + e.format()));
			}
		});
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, lines, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
