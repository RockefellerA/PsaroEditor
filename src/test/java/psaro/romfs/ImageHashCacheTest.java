package psaro.romfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loose images' hashes kept between runs ({@link ImageHashCache}). */
class ImageHashCacheTest {

	@TempDir
	Path dir;

	private final AtomicInteger reads = new AtomicInteger();

	/** Reads a file as an entry named for its first byte; a file starting with 0 is no image. */
	private ImageHashCache.Entry read(Path file) throws IOException {
		reads.incrementAndGet();
		byte[] d = Files.readAllBytes(file);
		return d[0] == 0 ? null : new ImageHashCache.Entry("h" + d[0], d.length, 1, 2);
	}

	/** A file is read once; again only when its size or time changes. */
	@Test
	void aFileIsReadAgainOnlyWhenItChanges() throws IOException {
		Path image = dir.resolve("a.bclim");
		Files.write(image, new byte[] {7, 1, 1});
		ImageHashCache cache = ImageHashCache.open(dir.resolve("cache/images.tsv"));
		assertEquals(new ImageHashCache.Entry("h7", 3, 1, 2), cache.entry(image, this::read));
		assertEquals(new ImageHashCache.Entry("h7", 3, 1, 2), cache.entry(image, this::read));
		assertEquals(1, reads.get());

		Files.write(image, new byte[] {8, 1, 1, 1});
		assertEquals("h8", cache.entry(image, this::read).hash(), "a new size is read again");
		Files.write(image, new byte[] {9, 1, 1, 1});
		Files.setLastModifiedTime(image, FileTime.fromMillis(Files.getLastModifiedTime(image).toMillis() + 5000));
		assertEquals("h9", cache.entry(image, this::read).hash(), "a new time is read again");
		assertEquals(3, reads.get());
	}

	/**
	 * What was read is kept on disk, a file that is no image too, so another run reads neither
	 * again; a file gone since is left out, and a cache that cannot be read starts empty.
	 */
	@Test
	void entriesAreKeptBetweenRuns() throws IOException {
		Path image = dir.resolve("a.bclim");
		Path other = dir.resolve("b.bclim");
		Path gone = dir.resolve("c.bclim");
		Files.write(image, new byte[] {7, 1});
		Files.write(other, new byte[] {0});
		Files.write(gone, new byte[] {5});
		Path file = dir.resolve("cache/images.tsv");
		ImageHashCache first = ImageHashCache.open(file);
		first.entry(image, this::read);
		assertNull(first.entry(other, this::read));
		first.entry(gone, this::read);
		Files.delete(gone);
		first.save();
		assertFalse(Files.readString(file).contains("c.bclim"), "a file gone is left out");

		ImageHashCache second = ImageHashCache.open(file);
		reads.set(0);
		assertEquals(new ImageHashCache.Entry("h7", 2, 1, 2), second.entry(image, this::read));
		assertNull(second.entry(other, this::read));
		assertEquals(0, reads.get(), "both taken from the cache");

		Files.writeString(file, "nonsense\tline\n" + image.toAbsolutePath() + "\tnot-a-size\t1\th\t1\t1\t1\n");
		assertEquals("h7", ImageHashCache.open(file).entry(image, this::read).hash());
		assertTrue(reads.get() > 0, "lines that cannot be read are read again");
	}
}
