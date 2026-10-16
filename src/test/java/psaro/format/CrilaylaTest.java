package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import psaro.romfs.SampleCpk;

class CrilaylaTest {

	/**
	 * Literals, short copies and a run long enough for every length field and the 8-bit lengths
	 * after them all come back as they were, the first 0x100 bytes too.
	 */
	@Test
	void decompressesWhatWasCompressed() {
		byte[] d = new byte[0x100 + 2000];
		Random r = new Random(7);
		r.nextBytes(d);
		byte[] phrase = "ガンダム、行きます！".getBytes(ShiftJis.CHARSET);
		for (int at = 0x180; at < 0x300; at += phrase.length + 5) {
			System.arraycopy(phrase, 0, d, at, phrase.length);
		}
		Arrays.fill(d, 0x400, 0x400 + 700, (byte) 0x2A);
		byte[] packed = SampleCpk.crilayla(d);
		assertTrue(Crilayla.isCompressed(packed));
		assertTrue(packed.length < d.length);
		assertArrayEquals(d, Crilayla.decompress(packed));
	}

	@Test
	void anythingElseIsRefused() {
		byte[] plain = new byte[0x200];
		assertFalse(Crilayla.isCompressed(plain));
		assertThrows(IllegalArgumentException.class, () -> Crilayla.decompress(plain));
		byte[] cut = Arrays.copyOf(SampleCpk.crilayla(new byte[0x300]), 0x40);
		assertThrows(IllegalArgumentException.class, () -> Crilayla.decompress(cut));
	}
}
