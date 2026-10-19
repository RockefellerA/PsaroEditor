package psaro.romfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import psaro.romfs.RomfsIndex.Kind;
import psaro.romfs.RomfsIndex.StringTable;

/** A romfs laid out as Lost Heroes' is: message tables, and bundles carrying copies of them. */
class MessageTablesTest {

	@TempDir
	Path romfs;

	/** Each message table file is a table named by its path, and each bundle carrying a copy of one is known. */
	@Test
	void readsMessageTablesAndTheBundlesCarryingThem() throws IOException {
		SampleRomfs.messageTables(romfs);
		assertTrue(RomfsIndex.looksLikeRomfs(romfs));
		RomfsIndex index = RomfsIndex.scan(romfs);
		StringTable mess = index.table("Table/Event/Base/MainEventMess0901");
		assertEquals(Kind.MESSAGE_TABLE, mess.kind());
		assertTrue(mess.message());
		assertEquals(Map.of("000", "はい", "001", "いいえ"), mess.strings());
		assertEquals(Map.of(romfs.resolve("Event/Base/Command/BASE_EVENT_Command0901_pk.cmp"), List.of(mess)), index.bundles());
	}

	/** A romfs with no message tables has no bundles read for them. */
	@Test
	void bundlesAloneAreNotRead() throws IOException {
		SampleRomfs.bundle(romfs, "Battle/Btl_Normal_pk.cmp", Map.of("Table/Event/Base/Gone.mbb", new byte[] {4}));
		SampleRomfs.table(romfs, "menu", Map.of("menu_0001", "はい"));
		assertEquals(Map.of(), RomfsIndex.scan(romfs).bundles());
	}
}
