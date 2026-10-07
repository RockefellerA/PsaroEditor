package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class Lz11Test {

	@Test
	void randomDataRoundTrips() {
		byte[] data = new byte[50_000];
		new Random(1).nextBytes(data);
		assertArrayEquals(data, Lz11.decompress(Lz11.compress(data)));
	}

	@Test
	void repetitiveDataCompressesAndRoundTrips() {
		byte[] data = new byte[200_000];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) ("darc font glyph ".charAt(i % 16) + (i / 5000));
		}
		byte[] packed = Lz11.compress(data);
		assertTrue(packed.length < data.length / 10, "packed to " + packed.length);
		assertArrayEquals(data, Lz11.decompress(packed));
	}

	@Test
	void runsLongerThanTheLongestTokenRoundTrip() {
		byte[] data = new byte[0x10110 * 3 + 7];
		data[data.length - 1] = 9;
		assertArrayEquals(data, Lz11.decompress(Lz11.compress(data)));
	}

	@Test
	void headerCarriesTypeAndSizeAndOutputIsWordAligned() {
		byte[] packed = Lz11.compress(new byte[] {1, 2, 3, 4, 5});
		assertEquals(0x11, packed[0] & 0xFF);
		assertEquals(5, packed[1] & 0xFF);
		assertEquals(0, packed.length % 4);
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, Lz11.decompress(packed));
	}

	@Test
	void emptyInputRoundTrips() {
		assertArrayEquals(new byte[0], Lz11.decompress(Lz11.compress(new byte[0])));
	}
}
