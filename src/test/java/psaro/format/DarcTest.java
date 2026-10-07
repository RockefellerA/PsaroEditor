package psaro.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DarcTest {

	private static Darc.Node sample() {
		Darc.Node root = Darc.Node.dir("");
		Darc.Node dot = Darc.Node.dir(".");
		root.children.add(dot);
		Darc.Node blyt = Darc.Node.dir("blyt");
		blyt.children.add(Darc.Node.file("a.bclyt", new byte[] {1, 2, 3}));
		Darc.Node font = Darc.Node.dir("font");
		font.children.add(Darc.Node.file("f.bcfnt", new byte[] {4, 5, 6, 7, 8}));
		font.children.add(Darc.Node.file("g.bcfnt", new byte[] {9}));
		dot.children.add(blyt);
		dot.children.add(font);
		return root;
	}

	@Test
	void writtenArchiveReadsBackWithSameFilesInOrder() {
		Map<String, Darc.Node> files = Darc.files(Darc.read(Darc.write(sample())));
		assertEquals(List.of("blyt/a.bclyt", "font/f.bcfnt", "font/g.bcfnt"), List.copyOf(files.keySet()));
		assertArrayEquals(new byte[] {4, 5, 6, 7, 8}, files.get("font/f.bcfnt").data);
	}

	@Test
	void rewriteIsByteIdentical() {
		byte[] once = Darc.write(sample());
		assertArrayEquals(once, Darc.write(Darc.read(once)));
	}

	@Test
	void fontsAreAlignedTo0x80() {
		byte[] d = Darc.write(sample());
		int tableOff = Bytes.u32(d, 0x10);
		int count = Bytes.u32(d, tableOff + 8);
		for (int i = 0; i < count; i++) {
			int nameWord = Bytes.u32(d, tableOff + i * 12);
			int off = Bytes.u32(d, tableOff + i * 12 + 4);
			String name = Bytes.utf16(d, tableOff + count * 12 + (nameWord & 0xFFFFFF), d.length);
			if (name.endsWith(".bcfnt")) {
				assertEquals(0, off % 0x80, name);
			}
		}
	}
}
