package psaro.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LumaDumpTest {

	private static final int SP = 0x427000;

	/** An ARM11 data abort in user mode, a write just below sp, with a file path on the stack. */
	private static byte[] sample() {
		int regSize = 23 * 4;
		int codeSize = 0x60;
		int stackSize = 0x1000;
		int extra = 16;
		ByteBuffer b = ByteBuffer.allocate(40 + regSize + codeSize + stackSize + extra).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0xDEADC0DE).putInt(0xDEADCAFE);
		b.putShort((short) 3).putShort((short) 1);
		b.putShort((short) 11).putShort((short) 3);
		b.putInt(3);
		b.putInt(b.capacity()).putInt(regSize).putInt(codeSize).putInt(stackSize).putInt(extra);
		int[] regs = new int[23];
		regs[13] = SP;
		regs[14] = 0x3DC288;
		regs[15] = 0x3DC298;
		regs[16] = 0x60000010;
		regs[17] = 0x80F;
		regs[19] = 0x426FF0;
		for (int r : regs) {
			b.putInt(r);
		}
		b.position(b.position() + codeSize);
		byte[] path = "rom:/message/error_text_jp.mdt".getBytes(StandardCharsets.UTF_16LE);
		int stackAt = b.position();
		b.position(stackAt + 0x400);
		b.put(path);
		b.position(stackAt + stackSize);
		b.put("CtrApp".getBytes(StandardCharsets.US_ASCII)).put(new byte[2]);
		b.putLong(0x0004000000140000L);
		return b.array();
	}

	@Test
	void readsTheHeaderRegistersAndProcess() {
		LumaDump d = LumaDump.read(sample());
		assertEquals(11, d.processor());
		assertEquals(3, d.core());
		assertEquals("Data abort", d.typeName());
		assertEquals(0x3DC298, d.pc());
		assertEquals(SP, d.sp());
		assertEquals(0x426FF0, d.faultAddress());
		assertEquals("CtrApp", d.processName());
		assertEquals(0x0004000000140000L, d.titleId());
		assertEquals(0x1000, d.stack().length);
	}

	@Test
	void reportExplainsTheFaultAndFindsTextOnTheStack() {
		String report = LumaDump.read(sample()).report();
		assertTrue(report.contains("Title ID:   0004000000140000"), report);
		assertTrue(report.contains("Permission fault (page)"), report);
		assertTrue(report.contains("on a write at 00426FF0"), report);
		assertTrue(report.contains("16 bytes below sp"), report);
		assertTrue(report.contains("User, ARM, -ZC-"), report);
		assertTrue(report.contains("rom:/message/error_text_jp.mdt"), report);
	}

	@Test
	void cpsrAndFaultStatusDecode() {
		assertEquals("Supervisor, Thumb, N--V", LumaDump.describeCpsr(0x90000033));
		assertEquals("Translation fault (page): the address is not mapped", LumaDump.faultStatus(0x7));
		// bit 10 of the register is bit 4 of the status code
		assertEquals("Imprecise external abort", LumaDump.faultStatus(0x406));
	}

	@Test
	void rejectsFilesThatAreNotDumpsOrAreCutShort() {
		assertFalse(LumaDump.looksLike(new byte[100]));
		assertThrows(IllegalArgumentException.class, () -> LumaDump.read(new byte[100]));
		byte[] cut = java.util.Arrays.copyOf(sample(), 2000);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LumaDump.read(cut));
		assertTrue(e.getMessage().contains("cut short"), e.getMessage());
	}
}
