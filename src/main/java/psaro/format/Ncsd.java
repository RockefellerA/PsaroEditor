package psaro.format;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * A game card's image, a {@code .3ds}: an NCSD header, then up to eight NCCH partitions (0 the
 * game, 1 its manual, 2 its download-play child, 7 a system update). A copy can be written with
 * some partitions rebuilt ({@link Ncch#rebuild}): each partition after a grown or shrunk one moves
 * with it, the partition table and the filled size follow, and the image ends where its data does,
 * without the card's padding, as a trimmed dump does.
 *
 * <pre>
 * 0x100 "NCSD"   0x104 the card's size (media units)   0x108 media id
 * 0x120 partition table: 8 × (offset, size) in media units   0x188 flags: [6] media unit 0x200 &lt;&lt; n
 * 0x300 the card's filled size in bytes (where the last partition ends)
 * </pre>
 */
public final class Ncsd {

	/** One partition: its index in the table, and where it is in the image, in bytes. */
	public record Partition(int index, long offset, long size) {
	}

	private final Path file;
	/** The image's start up to its first partition: header, card info, initial data. */
	private final byte[] head;
	private final int unit;
	private final List<Partition> partitions;

	private Ncsd(Path file, byte[] head, int unit, List<Partition> partitions) {
		this.file = file;
		this.head = head;
		this.unit = unit;
		this.partitions = partitions;
	}

	/** Whether {@code start} (the image's first 0x200 bytes or more) is an NCSD's. */
	public static boolean is(byte[] start) {
		return start.length >= 0x200 && Bytes.magic(start, 0x100, "NCSD");
	}

	/** Reads {@code file}'s header and partition table. */
	public static Ncsd read(Path file) throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			Source s = Source.of(ch);
			byte[] header = s.read(0, 0x200);
			if (!is(header)) {
				throw new IOException(file.getFileName() + " is not a .3ds image");
			}
			int unit = 0x200 << (header[0x18E] & 0xFF);
			List<Partition> partitions = new ArrayList<>();
			ByteBuffer t = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < 8; i++) {
				long offset = (t.getInt(0x120 + i * 8) & 0xFFFFFFFFL) * unit;
				long size = (t.getInt(0x124 + i * 8) & 0xFFFFFFFFL) * unit;
				if (size > 0) {
					partitions.add(new Partition(i, offset, size));
				}
			}
			partitions.sort(java.util.Comparator.comparingLong(Partition::offset));
			if (partitions.isEmpty() || partitions.get(0).offset() < 0x200 || partitions.get(0).offset() > 1 << 20) {
				throw new IOException(file.getFileName() + " has no partitions where a .3ds keeps them");
			}
			if (partitions.get(partitions.size() - 1).offset() + partitions.get(partitions.size() - 1).size() > ch.size()) {
				throw new IOException(file.getFileName() + " ends before its last partition does: is it whole?");
			}
			return new Ncsd(file, s.read(0, (int) partitions.get(0).offset()), unit, List.copyOf(partitions));
		}
	}

	/** The partitions, in the order they are in the image. */
	public List<Partition> partitions() {
		return partitions;
	}

	/** The partition of index {@code index}, or null when the image has none. */
	public Partition partition(int index) {
		return partitions.stream().filter(p -> p.index() == index).findFirst().orElse(null);
	}

	/** The game's title id: partition 0's program id. */
	public long titleId() throws IOException {
		Partition game = partition(0);
		if (game == null) {
			throw new IOException(file.getFileName() + " has no game partition");
		}
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			return Ncch.programId(Source.of(ch).read(game.offset(), 0x200));
		}
	}

	/**
	 * Writes the image to {@code out} with each partition {@code replaced} has an index for rebuilt
	 * (its pieces read from this image's file), every other copied, the partitions placed one after
	 * another with the gaps the image had between them. {@code read} hears how much was read.
	 */
	public void write(OutputStream out, Map<Integer, Ncch.Rebuilt> replaced, LongConsumer read) throws IOException {
		byte[] header = head.clone();
		ByteBuffer t = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		List<List<Part>> parts = new ArrayList<>();
		long at = head.length;
		long was = head.length;
		for (Partition p : partitions) {
			// the gap before it, as the image had
			long gap = p.offset() - was;
			Ncch.Rebuilt r = replaced.get(p.index());
			long size = r != null ? r.size() : p.size();
			if (size % unit != 0) {
				throw new IllegalArgumentException("partition " + p.index() + " is not a whole number of media units");
			}
			at += gap;
			t.putInt(0x120 + p.index() * 8, (int) (at / unit));
			t.putInt(0x124 + p.index() * 8, (int) (size / unit));
			List<Part> piece = new ArrayList<>();
			if (gap > 0) {
				piece.add(new Part.Range(p.offset() - gap, p.offset()));
			}
			piece.addAll(r != null ? r.parts() : List.of(new Part.Range(p.offset(), p.offset() + p.size())));
			parts.add(piece);
			at += size;
			was = p.offset() + p.size();
		}
		long cardSize = (t.getInt(0x104) & 0xFFFFFFFFL) * unit;
		if (at > cardSize) {
			// grown past its card: the next card size up
			cardSize = Long.highestOneBit(at - 1) << 1;
			t.putInt(0x104, (int) (cardSize / unit));
		}
		if (header.length >= 0x304) {
			t.putInt(0x300, (int) at);
		}
		out.write(header);
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			Source s = Source.of(ch);
			for (List<Part> piece : parts) {
				Part.write(s, piece, out, read);
			}
		}
	}
}
