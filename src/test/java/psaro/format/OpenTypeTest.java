package psaro.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

class OpenTypeTest {

	private static byte[] bundled(String weight) throws IOException {
		try (InputStream in = OpenTypeTest.class.getResourceAsStream("/fonts/MPLUSRounded1c-" + weight + ".ttf")) {
			return in.readAllBytes();
		}
	}

	/**
	 * The Black file names its family typographically; the Bold file only as "Rounded Mplus 1c
	 * Bold", the weight taken off so both come to one family.
	 */
	@Test
	void aFamilyOfManyWeightsReadsAsOneFamily() throws IOException {
		assertEquals(new OpenType("Rounded Mplus 1c", 900), OpenType.read(bundled("Black")));
		assertEquals(new OpenType("Rounded Mplus 1c", 700), OpenType.read(bundled("Bold")));
		assertEquals(new OpenType("Rounded Mplus 1c", 800), OpenType.read(bundled("ExtraBold")));
		assertEquals("ExtraBold", OpenType.weightName(800));
		assertEquals("650", OpenType.weightName(650));
	}

	@Test
	void otherFilesAreNotFonts() {
		assertThrows(IllegalArgumentException.class, () -> OpenType.read(new byte[] {1, 2, 3}));
		assertThrows(IllegalArgumentException.class, () -> OpenType.read("ttcf\0\1\0\0\0\0\0\0".getBytes()));
		assertThrows(IllegalArgumentException.class, () -> OpenType.read("PK\3\4 not a font at all".getBytes()));
	}
}
