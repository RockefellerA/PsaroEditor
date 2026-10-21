package psaro.romfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Darc;
import psaro.format.Lz11;
import psaro.romfs.FileSurvey.Item;

/** What a romfs's files are, told by what they hold ({@link FileSurvey}). */
class FileSurveyTest {

	@TempDir
	Path dir;

	private void write(String path, byte[] data) throws IOException {
		Path f = dir.resolve(path);
		Files.createDirectories(f.getParent());
		Files.write(f, data);
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	/** A string table of {@code count} entries, as far as its start shows: the first at 8, its key at 12 into it. */
	private static byte[] tdt(int count) {
		return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(count).putInt(8).putInt(24).putInt(12).array();
	}

	/** A BCLIM: pixels, then its footer, "CLIM" 0x28 from the end and "imag" 0x14 on. */
	private static byte[] bclim() {
		byte[] d = new byte[0x100 + 0x28];
		java.util.Arrays.fill(d, 0, 0x100, (byte) 0x11);
		System.arraycopy(ascii("CLIM"), 0, d, 0x100, 4);
		System.arraycopy(ascii("imag"), 0, d, 0x114, 4);
		return d;
	}

	private static byte[] archive() {
		Darc.Node root = Darc.Node.dir("");
		Darc.Node dot = Darc.Node.dir(".");
		root.children.add(dot);
		Darc.Node blyt = Darc.Node.dir("blyt");
		blyt.children.add(Darc.Node.file("menu.bclyt", ascii("CLYT and the rest")));
		Darc.Node timg = Darc.Node.dir("timg");
		timg.children.add(Darc.Node.file("bg.bclim", bclim()));
		Darc.Node anim = Darc.Node.dir("anim");
		anim.children.add(Darc.Node.file("in.bclan", ascii("CLAN and the rest")));
		dot.children.addAll(List.of(blyt, timg, anim));
		return Darc.write(root);
	}

	private Map<String, Item> survey() throws IOException {
		return FileSurvey.scan(dir, s -> { }).stream().collect(Collectors.toMap(Item::path, i -> i));
	}

	/**
	 * Files are told by what they hold, compressed or not, and an archive's files are told too; a
	 * name only tells a CSV from other UTF-16 text.
	 */
	@Test
	void filesAreToldByWhatTheyHold() throws IOException {
		write("text/menu_Japanese.tdt", tdt(3));
		write("ui/menu.arc.lz", Lz11.compress(archive()));
		write("img/title.bclim.lz", Lz11.compress(bclim()));
		write("img/plain.bclim", bclim());
		write("table/items.csv", ascii("ÿþa\0,\0b\0"));
		write("notes.txt", ascii("ÿþa\0"));
		write("message/help_jp.mdt", ascii("MSGD\0\0\0\0"));
		write("movie/op.moflex", new byte[] {'L', '2', (byte) 0xAA, (byte) 0xAB, 0, 0});
		write("shader/a.csdr", ascii("<NintendoWare>"));
		Map<String, Item> items = survey();

		assertEquals(FileSurvey.TDT, items.get("text/menu_Japanese.tdt").format());
		Item arc = items.get("ui/menu.arc.lz");
		assertEquals(FileSurvey.DARC, arc.format());
		assertTrue(arc.compressed());
		assertFalse(arc.inArchive());
		Item layout = items.get("ui/menu.arc.lz/blyt/menu.bclyt");
		assertEquals(FileSurvey.BCLYT, layout.format());
		assertTrue(layout.inArchive());
		assertEquals(FileSurvey.BCLIM, items.get("ui/menu.arc.lz/timg/bg.bclim").format());
		assertEquals("Layout animation (BCLAN)", items.get("ui/menu.arc.lz/anim/in.bclan").format().name());
		assertEquals(FileSurvey.BCLIM, items.get("img/title.bclim.lz").format());
		assertTrue(items.get("img/title.bclim.lz").compressed());
		assertEquals(FileSurvey.BCLIM, items.get("img/plain.bclim").format());
		assertFalse(items.get("img/plain.bclim").compressed());
		assertEquals(FileSurvey.CSV, items.get("table/items.csv").format());
		assertEquals("UTF-16 text", items.get("notes.txt").format().name());
		assertEquals(FileSurvey.MSGD, items.get("message/help_jp.mdt").format());
		assertEquals("Video (Moflex)", items.get("movie/op.moflex").format().name());
		assertEquals("XML", items.get("shader/a.csdr").format().name());
		assertEquals(12, items.size());
	}

	/** A string table of seventeen entries starts with 0x11, LZ11's mark, and is still a string table. */
	@Test
	void aStringTableIsNotTakenForLz11() throws IOException {
		write("text/big_Japanese.tdt", tdt(0x11));
		assertEquals(FileSurvey.TDT, survey().get("text/big_Japanese.tdt").format());
		assertFalse(survey().get("text/big_Japanese.tdt").compressed());
	}

	/**
	 * A file known by nothing here is grouped by its first four bytes when they spell a signature,
	 * else by its extension (the one before .lz when compressed); a project folder is left out.
	 */
	@Test
	void unknownFilesAreGroupedBySignatureOrExtension() throws IOException {
		write("scene/MAP01.bin", ascii("sugo\1\0\0\0"));
		write("scene/MAP02.bin", ascii("sugo\2\0\0\0"));
		write("common/ok_btn.btn", new byte[] {(byte) 0x88, 1, 0, 0, 5});
		write("music/trigger.bytes.lz", Lz11.compress(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
		write("game.psaro/romfs/text/x.tdt", tdt(1));
		Map<String, Item> items = survey();
		assertEquals(4, items.size());
		assertEquals("Unknown \"sugo\"", items.get("scene/MAP01.bin").format().name());
		assertEquals(items.get("scene/MAP01.bin").format(), items.get("scene/MAP02.bin").format());
		assertEquals("Unknown .btn", items.get("common/ok_btn.btn").format().name());
		assertEquals("Unknown .bytes", items.get("music/trigger.bytes.lz").format().name());
		assertTrue(items.get("music/trigger.bytes.lz").compressed());

		List<FileSurvey.Group> groups = FileSurvey.groups(List.copyOf(items.values()));
		assertEquals("Unknown \"sugo\"", groups.get(0).format().name());
		assertEquals(2, groups.get(0).loose());
		assertTrue(groups.stream().noneMatch(g -> g.format().read()));
	}

	/** Formats PsaroEditor does not read are listed before those it does. */
	@Test
	void unreadFormatsComeFirst() throws IOException {
		write("text/a_Japanese.tdt", tdt(1));
		write("text/b_Japanese.tdt", tdt(2));
		write("sound/se.bcsar", ascii("CSAR...."));
		List<FileSurvey.Group> groups = FileSurvey.groups(FileSurvey.scan(dir, s -> { }));
		assertEquals(List.of("Sound archive (BCSAR)", "String table (TDT)"), groups.stream().map(g -> g.format().name()).toList());
		assertEquals(2, groups.get(1).loose());
	}

	/** Nothing known shows in bytes that start no format here. */
	@Test
	void plainBytesAreNoFormat() {
		assertNull(FileSurvey.identify(new byte[] {1, 2, 3, 4}, new byte[0], "x.bin"));
		assertEquals("Unknown (no extension)", FileSurvey.unknown(new byte[] {1, 2, 3, 4}, "").name());
	}
}
