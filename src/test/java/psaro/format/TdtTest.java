package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

class TdtTest {

	@Test
	void entriesRoundTripInOrderWithControlCodes() {
		LinkedHashMap<String, String> in = new LinkedHashMap<>();
		in.put("cftp_1000", "Change Name");
		in.put("cftp_2020", "\u0002\u0001You can change the \u0002\u0002Name\u0002\u0001\nof the log.");
		in.put("cftp_1060", "？？？");
		in.put("tncm_0000", "");
		byte[] bytes = Tdt.write(in);
		LinkedHashMap<String, String> out = Tdt.read(bytes);
		assertEquals(in, out);
		assertEquals(List.copyOf(in.keySet()), List.copyOf(out.keySet()));
		assertArrayEquals(bytes, Tdt.write(out));
	}

	@Test
	void headerAndFirstEntryMatchTheGameLayout() {
		LinkedHashMap<String, String> in = new LinkedHashMap<>();
		in.put("cfss_1000", "やめる");
		byte[] d = Tdt.write(in);
		assertEquals(1, Bytes.u32(d, 0));
		assertEquals(8, Bytes.u32(d, 4));
		assertEquals(0x1E, Bytes.u32(d, 8));   // 12 + "cfss_1000\0" + 3 chars + NUL
		assertEquals(0x0C, Bytes.u32(d, 12));
		assertEquals(0x16, Bytes.u32(d, 16));
	}
}
