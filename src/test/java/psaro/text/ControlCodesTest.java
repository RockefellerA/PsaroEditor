package psaro.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ControlCodesTest {

	private static final String RAW = "\u0002\u0002Name\u0002\u0001 is shown\nto \u0002\u0010others\u0002\u0009.";

	@Test
	void colourCodesShowAsHexTokensAndConvertBack() {
		String shown = ControlCodes.toDisplay(RAW);
		assertEquals("{02}Name{01} is shown\nto {10}others{09}.", shown);
		assertEquals(RAW, ControlCodes.toRaw(shown));
	}

	@Test
	void tokensAreCaseInsensitiveAndOtherBracesStayText() {
		assertEquals("\u0002\u001a[name] {x} {123}", ControlCodes.toRaw("{1a}[name] {x} {123}"));
	}

	@Test
	void otherControlCharactersRoundTrip() {
		String raw = "a\u0007b";
		assertEquals("a{u0007}b", ControlCodes.toDisplay(raw));
		assertEquals(raw, ControlCodes.toRaw("a{u0007}b"));
	}

	@Test
	void summaryDropsCodesAndJoinsLines() {
		assertEquals("Name is shown ⏎ to others.", ControlCodes.summary(RAW));
	}
}
