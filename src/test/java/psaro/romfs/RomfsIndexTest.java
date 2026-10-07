package psaro.romfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.format.Archive;
import psaro.format.Darc;
import psaro.format.Tdt;
import psaro.romfs.RomfsIndex.StringTable;
import psaro.romfs.RomfsIndex.Usage;

class RomfsIndexTest {

	@TempDir
	Path romfs;

	/**
	 * {@code menu.arc.lz} shows one string from its own table, one from {@code common} (which it
	 * lacks), and one key no table has. {@code other} also holds {@code menu_0001}, which must not
	 * steal the pane from {@code menu}.
	 */
	private RomfsIndex sample() throws IOException {
		table("menu", Map.of("menu_0001", "はい", "menu_0002", "いいえ"));
		table("common", Map.of("comm_0001", "決定"));
		table("other", Map.of("menu_0001", "べつ"));
		archive("scene/menu/menu.arc.lz", "blyt/menu.bclyt", List.of("a.bcfnt"),
				pane("Txt_Yes", "menu_0001"), pane("Txt_Enter", "comm_0001"), pane("Txt_Gone", "gone_9999"));
		return RomfsIndex.scan(romfs);
	}

	@Test
	void readsEveryStringTable() throws IOException {
		RomfsIndex index = sample();
		assertEquals(List.of("common", "menu", "other"), index.tables().stream().map(StringTable::name).toList());
		assertEquals("いいえ", index.table("menu").strings().get("menu_0002"));
		assertEquals(4, index.stringCount());
		assertEquals(1, index.layoutCount());
	}

	@Test
	void paneResolvesToItsArchivesOwnTableFirst() throws IOException {
		RomfsIndex index = sample();
		List<Usage> yes = index.usages(index.table("menu"), "menu_0001");
		assertEquals(1, yes.size());
		assertEquals("blyt/menu.bclyt", yes.get(0).layout());
		assertEquals("Txt_Yes", yes.get(0).pane().name());
		assertEquals("a.bcfnt", yes.get(0).fontName());
		assertTrue(index.usages(index.table("other"), "menu_0001").isEmpty());
	}

	@Test
	void keyTheOwnTableLacksResolvesToTheTableThatHasIt() throws IOException {
		RomfsIndex index = sample();
		assertEquals("Txt_Enter", index.usages(index.table("common"), "comm_0001").get(0).pane().name());
	}

	@Test
	void stringsNoLayoutShowsHaveNoUsagesAndUnknownKeysAreReported() throws IOException {
		RomfsIndex index = sample();
		assertTrue(index.usages(index.table("menu"), "menu_0002").isEmpty());
		assertEquals(List.of("Txt_Gone"), index.unresolved().stream().map(u -> u.pane().name()).toList());
	}

	@Test
	void fontTheArchiveDoesNotCarryIsNull() throws IOException {
		RomfsIndex index = sample();
		assertNull(index.font(index.usages(index.table("menu"), "menu_0001").get(0)));
	}

	@Test
	void folderWithoutStringTablesIsNotARomfs() throws IOException {
		assertFalse(RomfsIndex.looksLikeRomfs(romfs));
		table("menu", Map.of("menu_0001", "はい"));
		assertTrue(RomfsIndex.looksLikeRomfs(romfs));
	}

	/** Against a real romfs when one is supplied, as RomfsRoundTripTest does. */
	@Test
	void realRomfsResolvesNearlyEveryKeyAndFindsFonts() throws IOException {
		String dir = System.getProperty("psaro.romfs", System.getenv("PSARO_ROMFS"));
		assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "no romfs supplied; set -Dpsaro.romfs");
		RomfsIndex index = RomfsIndex.scan(Path.of(dir));
		int resolved = 0;
		Usage withFont = null;
		for (StringTable table : index.tables()) {
			for (String key : table.strings().keySet()) {
				for (Usage u : index.usages(table, key)) {
					resolved++;
					if (withFont == null && !u.fontName().equals("cbf_std.bcfnt")) {
						withFont = u;
					}
				}
			}
		}
		assertTrue(resolved > 0, "no pane resolved to a string");
		assertTrue(index.unresolved().size() * 20 < resolved, index.unresolved().size() + " unresolved of " + resolved);
		assertTrue(withFont != null && index.font(withFont) != null, "no pane's font found in its archive");
	}

	// ── Synthetic romfs ──────────────────────────────────────────────────────

	private void table(String name, Map<String, String> strings) throws IOException {
		Files.createDirectories(romfs.resolve("text"));
		Files.write(romfs.resolve("text/" + name + "_Japanese.tdt"), Tdt.write(new LinkedHashMap<>(strings)));
	}

	private void archive(String path, String layoutPath, List<String> fonts, byte[]... panes) throws IOException {
		Darc.Node root = Darc.Node.dir("");
		Darc.Node dot = Darc.Node.dir(".");
		Darc.Node blyt = Darc.Node.dir(layoutPath.substring(0, layoutPath.indexOf('/')));
		blyt.children.add(Darc.Node.file(layoutPath.substring(layoutPath.indexOf('/') + 1), layout(fonts, panes)));
		dot.children.add(blyt);
		root.children.add(dot);
		Archive.save(root, romfs.resolve(path));
	}

	/** A CLYT with an fnl1 font list, then each pane's txt1 + usd1 sections. */
	private static byte[] layout(List<String> fonts, byte[]... panes) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		ByteArrayOutputStream names = new ByteArrayOutputStream();
		ByteBuffer offsets = le(4 * fonts.size());
		for (String font : fonts) {
			offsets.putInt(4 * fonts.size() + names.size());
			names.writeBytes((font + "\0").getBytes(StandardCharsets.US_ASCII));
		}
		byte[] fnl1Body = concat(le(4).putInt(fonts.size()).array(), offsets.array(), names.toByteArray());
		body.writeBytes(section("fnl1", fnl1Body));
		for (byte[] pane : panes) {
			body.writeBytes(pane);
		}
		ByteBuffer header = le(0x14);
		header.put("CLYT".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0xFEFF).putShort((short) 0x14);
		return concat(header.array(), body.toByteArray());
	}

	/** A txt1 pane using font 0, followed by the usd1 that carries its key. */
	private static byte[] pane(String name, String key) {
		ByteBuffer txt = le(0x74 - 8 + 4);
		txt.position(0x0C - 8).put(name.getBytes(StandardCharsets.US_ASCII));
		txt.position(0x44 - 8).putFloat(120f).putFloat(24f).putShort((short) 4).putShort((short) 4);
		txt.position(0x52 - 8).putShort((short) 0);
		txt.position(0x58 - 8).putInt(0x74);
		txt.position(0x64 - 8).putFloat(16f).putFloat(16f).putFloat(0f).putFloat(0f);
		txt.position(0x74 - 8).put("*\0".getBytes(StandardCharsets.UTF_16LE));
		ByteBuffer usd = le(24);
		usd.position(8).put(key.getBytes(StandardCharsets.US_ASCII));
		return concat(section("txt1", txt.array()), section("usd1", usd.array()));
	}

	private static byte[] section(String tag, byte[] body) {
		ByteBuffer head = le(8).put(tag.getBytes(StandardCharsets.US_ASCII)).putInt(8 + body.length);
		return concat(head.array(), body);
	}

	private static ByteBuffer le(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			out.writeBytes(p);
		}
		return out.toByteArray();
	}
}
