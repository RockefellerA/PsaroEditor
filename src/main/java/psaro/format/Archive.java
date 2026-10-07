package psaro.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A romfs {@code *.arc.lz}: an LZ11-compressed DARC. */
public final class Archive {

	private Archive() {
	}

	public static Darc.Node load(Path path) throws IOException {
		byte[] raw = Files.readAllBytes(path);
		return Darc.read(Lz11.isCompressed(raw) ? Lz11.decompress(raw) : raw);
	}

	public static void save(Darc.Node root, Path path) throws IOException {
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(path, Lz11.compress(Darc.write(root)));
	}
}
