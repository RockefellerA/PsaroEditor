package psaro.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A romfs {@code *.arc.lz}: an LZ11-compressed DARC; or a {@code *.arc}, one some games keep uncompressed. */
public final class Archive {

	private Archive() {
	}

	public static Darc.Node load(Path path) throws IOException {
		byte[] raw = Files.readAllBytes(path);
		return Darc.read(Lz11.isCompressed(raw) ? Lz11.decompress(raw) : raw);
	}

	/** Writes {@code root} to {@code path}, compressed when its name ends {@code .lz}, as the game's was. */
	public static void save(Darc.Node root, Path path) throws IOException {
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		byte[] darc = Darc.write(root);
		Files.write(path, path.getFileName().toString().endsWith(".lz") ? Lz11.compress(darc) : darc);
	}
}
