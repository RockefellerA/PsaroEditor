package psaro.format;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/** Strict Shift-JIS reads, for formats that find their text by whether it reads as text. */
final class ShiftJis {

	static final Charset CHARSET = Charset.forName("windows-31j");

	private ShiftJis() {
	}

	/** {@code d[from, to)} as text, or null when it is not Shift-JIS or holds a control byte. */
	static String decode(byte[] d, int from, int to) {
		for (int i = from; i < to; i++) {
			if ((d[i] & 0xFF) < 0x20 && d[i] != '\r' && d[i] != '\n') {
				return null;
			}
		}
		try {
			return CHARSET.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(d, from, to - from)).toString();
		} catch (CharacterCodingException notText) {
			return null;
		}
	}

	/** The text from {@code at} up to its NUL, before {@code limit}, or null when it is not Shift-JIS or has no NUL. */
	static String terminated(byte[] d, int at, int limit) {
		int e = at;
		while (e < limit && d[e] != 0) {
			e++;
		}
		return e < limit ? decode(d, at, e) : null;
	}

	/** {@code text} as Shift-JIS; refused, naming them, when it holds characters Shift-JIS has not. */
	static byte[] encode(String text) {
		String missing = unencodable(text);
		if (missing != null) {
			throw new IllegalArgumentException("Shift-JIS has no " + missing);
		}
		return text.getBytes(CHARSET);
	}

	/** The characters of {@code text} Shift-JIS cannot hold, quoted, or null when it can hold them all. */
	static String unencodable(String text) {
		CharsetEncoder encoder = CHARSET.newEncoder();
		StringBuilder missing = new StringBuilder();
		text.codePoints().distinct().filter(c -> !encoder.canEncode(new String(Character.toChars(c))))
				.forEach(c -> missing.append(missing.isEmpty() ? "" : " ").append('"').appendCodePoint(c).append('"'));
		return missing.isEmpty() ? null : missing.toString();
	}

	/** Whether {@code text} holds kana or kanji, so it reads as text rather than a label or a number. */
	static boolean japanese(String text) {
		return text.codePoints().anyMatch(c -> c >= 0x3040 && c < 0xA000 || c >= 0xFF00 && c < 0xFFF0);
	}
}
