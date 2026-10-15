package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A crash dump written by Luma3DS's exception handler ({@code crash_dump_*.dmp}), read from the
 * dump's own layout and described here from the files themselves:
 *
 * <pre>
 * header (40 bytes), little-endian
 *   u32 magic 0xDEADC0DE, u32 magic 0xDEADCAFE
 *   u16 first version field, u16 second version field
 *   u16 processor (9 = ARM9, 11 = ARM11), u16 core
 *   u32 exception type
 *   u32 total size, then the sizes of the four sections that follow
 * register dump   r0-r12, sp, lr, pc, cpsr, then (ARM11) dfsr, ifsr, far, fpexc, fpinst, fpinst2
 * code dump       the instructions the crash happened around
 * stack dump      memory from sp upward
 * additional data ARM11 only: the process name (8 bytes) and its title ID (u64)
 * </pre>
 *
 * Nothing here is written back; the dump is only read and described by {@link #report()}.
 */
public final class LumaDump {

	private static final int MAGIC_1 = 0xDEADC0DE;
	private static final int MAGIC_2 = 0xDEADCAFE;
	private static final int HEADER = 40;
	/** r0-r12, sp, lr, pc, cpsr: the registers every dump has. */
	private static final int CORE_REGS = 17;
	private static final int ARM9 = 9;
	private static final int ARM11 = 11;

	/** Exception kinds by the number the header stores. */
	private static final String[] TYPES = {"FIQ", "Undefined instruction", "Prefetch abort", "Data abort"};
	private static final int DATA_ABORT = 3;
	private static final int PREFETCH_ABORT = 2;

	private final int versionA;
	private final int versionB;
	private final int processor;
	private final int core;
	private final int type;
	private final int[] regs;
	private final byte[] code;
	private final byte[] stack;
	private final String processName;
	private final long titleId;
	private final boolean hasProcessInfo;

	private LumaDump(int versionA, int versionB, int processor, int core, int type, int[] regs, byte[] code,
			byte[] stack, String processName, long titleId, boolean hasProcessInfo) {
		this.versionA = versionA;
		this.versionB = versionB;
		this.processor = processor;
		this.core = core;
		this.type = type;
		this.regs = regs;
		this.code = code;
		this.stack = stack;
		this.processName = processName;
		this.titleId = titleId;
		this.hasProcessInfo = hasProcessInfo;
	}

	/** True if {@code data} starts the way a Luma3DS crash dump does. */
	public static boolean looksLike(byte[] data) {
		return data.length >= HEADER && Bytes.u32(data, 0) == MAGIC_1 && Bytes.u32(data, 4) == MAGIC_2;
	}

	/** Reads a dump; throws {@link IllegalArgumentException} with a reason if it is not one or is cut short. */
	public static LumaDump read(byte[] d) {
		if (!looksLike(d)) {
			throw new IllegalArgumentException("Not a Luma3DS crash dump (the magic numbers are missing).");
		}
		int total = Bytes.u32(d, 20);
		int regSize = Bytes.u32(d, 24);
		int codeSize = Bytes.u32(d, 28);
		int stackSize = Bytes.u32(d, 32);
		int extraSize = Bytes.u32(d, 36);
		long sum = (long) HEADER + regSize + codeSize + stackSize + extraSize;
		if (regSize < CORE_REGS * 4 || regSize % 4 != 0 || codeSize < 0 || stackSize < 0 || extraSize < 0) {
			throw new IllegalArgumentException("The dump's section sizes make no sense: it is damaged or from an "
					+ "unknown version.");
		}
		if (sum > d.length) {
			throw new IllegalArgumentException("The dump is cut short: its header describes " + sum
					+ " bytes but the file has " + d.length + ".");
		}
		if (total != sum) {
			throw new IllegalArgumentException("The dump's size (" + total + ") does not match its sections (" + sum + ").");
		}

		int at = HEADER;
		int[] regs = new int[regSize / 4];
		for (int i = 0; i < regs.length; i++) {
			regs[i] = Bytes.u32(d, at + i * 4);
		}
		at += regSize;
		byte[] code = slice(d, at, codeSize);
		at += codeSize;
		byte[] stack = slice(d, at, stackSize);
		at += stackSize;
		boolean info = extraSize >= 16;
		String name = info ? Bytes.ascii(slice(d, at, 8), 0) : "";
		long titleId = info ? (Bytes.u32(d, at + 8) & 0xFFFFFFFFL) | (long) Bytes.u32(d, at + 12) << 32 : 0;
		return new LumaDump(Bytes.u16(d, 8), Bytes.u16(d, 10), Bytes.u16(d, 12), Bytes.u16(d, 14), Bytes.u32(d, 16),
				regs, code, stack, name, titleId, info);
	}

	private static byte[] slice(byte[] d, int from, int len) {
		byte[] out = new byte[len];
		System.arraycopy(d, from, out, 0, len);
		return out;
	}

	// ── The fields ───────────────────────────────────────────────────────────

	public int processor() {
		return processor;
	}

	public int core() {
		return core;
	}

	public int type() {
		return type;
	}

	/** r0-r12 are 0-12, then sp 13, lr 14, pc 15, cpsr 16. */
	public int register(int n) {
		return regs[n];
	}

	public int pc() {
		return regs[15];
	}

	public int sp() {
		return regs[13];
	}

	public String processName() {
		return processName;
	}

	public long titleId() {
		return titleId;
	}

	/** The fault address, or null when the dump has no fault registers (ARM9). */
	public Integer faultAddress() {
		return regs.length > 19 ? regs[19] : null;
	}

	public String typeName() {
		return type >= 0 && type < TYPES.length ? TYPES[type] : "Unknown exception (" + type + ")";
	}

	public byte[] stack() {
		return stack.clone();
	}

	// ── Describing it ────────────────────────────────────────────────────────

	/** The dump as readable text: what crashed and where, the registers, the code and the stack. */
	public String report() {
		StringBuilder out = new StringBuilder();
		out.append("Luma3DS crash dump (format ").append(versionA).append('.').append(versionB).append(")\n\n");
		if (hasProcessInfo) {
			out.append(String.format("Process:    %s%n", processName.isEmpty() ? "(unnamed)" : processName));
			out.append(String.format("Title ID:   %016X%n", titleId));
		}
		out.append(String.format("Processor:  %s, core %d%n", processorName(), core));
		out.append(String.format("Exception:  %s%n", typeName()));
		if (regs.length > 19 && (type == DATA_ABORT || type == PREFETCH_ABORT)) {
			out.append(explainAbort());
		}
		out.append('\n');

		out.append("Registers\n");
		String[] names = {"r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10", "r11", "r12", "sp", "lr",
				"pc", "cpsr", "dfsr", "ifsr", "far", "fpexc", "fpinst", "fpinst2"};
		for (int i = 0; i < regs.length; i++) {
			String name = i < names.length ? names[i] : "reg" + i;
			out.append(String.format("  %-7s %08X", name, regs[i]));
			if (i == 16) {
				out.append("   ").append(describeCpsr(regs[16]));
			}
			out.append('\n');
		}

		if (code.length > 0) {
			out.append("\nCode (").append(code.length).append(" bytes the dump holds, offsets from its start)\n");
			hexDump(out, code, 0, 16, false);
		}
		out.append(String.format("%nStack (%d bytes from sp = %08X)%n", stack.length, regs[13]));
		hexDump(out, stack, regs[13], 16, true);

		List<String> strings = stackStrings();
		if (!strings.isEmpty()) {
			out.append("\nText found on the stack\n");
			strings.forEach(s -> out.append("  ").append(s).append('\n'));
		}
		return out.toString();
	}

	private String processorName() {
		return processor == ARM9 ? "ARM9" : processor == ARM11 ? "ARM11" : "unknown (" + processor + ")";
	}

	/** What a data or prefetch abort says about the fault, from the status register and the address. */
	private String explainAbort() {
		boolean data = type == DATA_ABORT;
		int status = regs[data ? 17 : 18];
		StringBuilder out = new StringBuilder();
		out.append(String.format("Fault:      %s", faultStatus(status)));
		if (data) {
			out.append((status & 0x800) != 0 ? ", on a write" : ", on a read");
		}
		out.append(String.format(" at %08X%n", data ? regs[19] : regs[15]));
		long below = (regs[13] & 0xFFFFFFFFL) - (regs[19] & 0xFFFFFFFFL);
		if (data && below > 0 && below <= 0x1000) {
			out.append(String.format("            The address is %d bytes below sp: most likely the stack ran out.%n", below));
		}
		return out.toString();
	}

	/** The ARMv6 fault status code, from bit 10 and bits 3-0 of the register. */
	static String faultStatus(int fsr) {
		return switch ((fsr >> 6 & 0x10) | (fsr & 0xF)) {
			case 0x01 -> "Alignment fault";
			case 0x02 -> "Debug event";
			case 0x04 -> "Instruction cache maintenance fault";
			case 0x05 -> "Translation fault (section): the address is not mapped";
			case 0x07 -> "Translation fault (page): the address is not mapped";
			case 0x08 -> "Precise external abort";
			case 0x09 -> "Domain fault (section)";
			case 0x0B -> "Domain fault (page)";
			case 0x0C -> "External abort on the first-level translation";
			case 0x0D -> "Permission fault (section)";
			case 0x0E -> "External abort on the second-level translation";
			case 0x0F -> "Permission fault (page): the address is not allowed to be touched this way";
			case 0x16 -> "Imprecise external abort";
			default -> String.format("Fault status %#x", fsr);
		};
	}

	/** The mode, instruction set and condition flags a CPSR value holds. */
	static String describeCpsr(int cpsr) {
		String mode = switch (cpsr & 0x1F) {
			case 0x10 -> "User";
			case 0x11 -> "FIQ";
			case 0x12 -> "IRQ";
			case 0x13 -> "Supervisor";
			case 0x17 -> "Abort";
			case 0x1B -> "Undefined";
			case 0x1F -> "System";
			default -> "mode " + (cpsr & 0x1F);
		};
		String flags = (cpsr < 0 ? "N" : "-") + ((cpsr & 0x40000000) != 0 ? "Z" : "-")
				+ ((cpsr & 0x20000000) != 0 ? "C" : "-") + ((cpsr & 0x10000000) != 0 ? "V" : "-");
		return mode + ", " + ((cpsr & 0x20) != 0 ? "Thumb" : "ARM") + ", " + flags;
	}

	/**
	 * Hex and ASCII rows. With {@code skipZeros}, a run of all-zero rows shows as one line, since
	 * most of a stack dump is empty.
	 */
	private static void hexDump(StringBuilder out, byte[] data, int base, int width, boolean skipZeros) {
		boolean skipping = false;
		for (int row = 0; row < data.length; row += width) {
			int n = Math.min(width, data.length - row);
			boolean zero = true;
			for (int i = 0; i < n && zero; i++) {
				zero = data[row + i] == 0;
			}
			if (skipZeros && zero && row > 0 && row + width < data.length) {
				if (!skipping) {
					out.append("  ...\n");
				}
				skipping = true;
				continue;
			}
			skipping = false;
			out.append(String.format("  %08X ", base + row));
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < width; i++) {
				if (i < n) {
					int b = data[row + i] & 0xFF;
					out.append(String.format(" %02X", b));
					text.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
				} else {
					out.append("   ");
				}
			}
			out.append("  ").append(text).append('\n');
		}
	}

	/**
	 * Readable text left in the stack: UTF-16 and plain ASCII runs of at least six characters,
	 * such as the path of the file the game was loading. Stack contents are what they are, so this
	 * is a hint, not a guarantee.
	 */
	List<String> stackStrings() {
		List<String> found = new ArrayList<>();
		int minLength = 6;
		for (int i = 0; i + 1 < stack.length; ) {
			int start = i;
			StringBuilder s = new StringBuilder();
			while (i + 1 < stack.length && stack[i + 1] == 0 && printable(stack[i])) {
				s.append((char) stack[i]);
				i += 2;
			}
			if (s.length() >= minLength) {
				addUnlessRepeat(found, s.toString());
			} else {
				i = start + 1;
			}
		}
		for (int i = 0; i < stack.length; ) {
			int start = i;
			while (i < stack.length && printable(stack[i])) {
				i++;
			}
			if (i - start >= minLength) {
				String s = new String(stack, start, i - start, StandardCharsets.US_ASCII);
				addUnlessRepeat(found, s);
			}
			i = Math.max(i, start) + 1;
		}
		return found;
	}

	/**
	 * Adds {@code s} unless the list has the same text already, give or take a stray character or
	 * two in front (the stack's neighbouring bytes), in which case the shorter is kept.
	 */
	private static void addUnlessRepeat(List<String> found, String s) {
		for (int i = 0; i < found.size(); i++) {
			String f = found.get(i);
			if (f.endsWith(s) && f.length() - s.length() <= 2) {
				found.set(i, s);
				return;
			}
			if (s.endsWith(f) && s.length() - f.length() <= 2 || f.contains(s) || s.contains(f)) {
				return;
			}
		}
		found.add(s);
	}

	private static boolean printable(byte b) {
		return b >= 0x20 && b < 0x7F;
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "%s %s at pc=%08X", processName, typeName(), regs[15]);
	}
}
