package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class CompTest {

	private static final byte[] DATA = "2NBF bundle entries, then models and textures ".repeat(200).getBytes(StandardCharsets.US_ASCII);

	@Test
	void roundTrips() {
		byte[] packed = Comp.compress(DATA);
		assertTrue(Comp.is(packed));
		assertArrayEquals("COMP".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(packed, 4));
		assertArrayEquals(DATA, Comp.decompress(packed));
	}

	/** The start of what a file holds is read without unpacking the rest, from as little of the file as holds it. */
	@Test
	void readsTheStartAlone() {
		byte[] packed = Comp.compress(DATA);
		assertArrayEquals(Arrays.copyOf(DATA, 100), Comp.decompress(Arrays.copyOf(packed, 120), 100));
	}

	@Test
	void plainLz11IsNotACompFile() {
		byte[] lz = Lz11.compress(DATA);
		assertFalse(Comp.is(lz));
		assertThrows(IllegalArgumentException.class, () -> Comp.decompress(lz));
	}
}
