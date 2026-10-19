package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class NbfTest {

	/** A bundle as the game's are: two files, the second's path with backslashes, each starting on 16 bytes. */
	private static byte[] sample() {
		int data = (0x10 + 2 * 0x8C + 15) / 16 * 16;
		ByteBuffer b = ByteBuffer.allocate(data + 0x20).order(ByteOrder.LITTLE_ENDIAN);
		b.put("2NBF".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(data).putInt(0x10);
		b.put("Graph/Bat/bst.gtx".getBytes(StandardCharsets.US_ASCII));
		b.position(0x10 + 0x80);
		b.putInt(0).putInt(0).putInt(5);
		b.put("Table\\Event\\Mess.mbb".getBytes(StandardCharsets.US_ASCII));
		b.position(0x10 + 0x8C + 0x80);
		b.putInt(0x10).putInt(0).putInt(3);
		b.position(data);
		b.put(new byte[] {1, 2, 3, 4, 5});
		b.position(data + 0x10);
		b.put(new byte[] {6, 7, 8});
		return b.array();
	}

	@Test
	void readsEachFileByItsPath() {
		byte[] d = sample();
		assertTrue(Nbf.is(d));
		List<Nbf.Member> members = Nbf.read(d);
		assertEquals("Graph/Bat/bst.gtx", members.get(0).name());
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, members.get(0).data());
		assertEquals("Table\\Event\\Mess.mbb", members.get(1).name());
		assertEquals("Table/Event/Mess.mbb", members.get(1).path());
		assertArrayEquals(new byte[] {6, 7, 8}, members.get(1).data());
	}

	/** The paths are read from the entries alone, which the first 16 bytes say the size of. */
	@Test
	void readsThePathsFromTheEntriesAlone() {
		byte[] d = sample();
		int size = Nbf.headerSize(Arrays.copyOf(d, 16));
		assertEquals(0x10 + 2 * 0x8C, size);
		assertEquals(List.of("Graph/Bat/bst.gtx", "Table/Event/Mess.mbb"), Nbf.paths(Arrays.copyOf(d, size)));
	}

	@Test
	void writesABundleAsTheGamesAre() {
		byte[] d = sample();
		assertArrayEquals(d, Nbf.write(Nbf.read(d)));
	}

	@Test
	void anotherFileIsNotABundle() {
		assertFalse(Nbf.is("GFPT".getBytes(StandardCharsets.US_ASCII)));
		assertFalse(Nbf.is(new byte[16]));
	}
}
