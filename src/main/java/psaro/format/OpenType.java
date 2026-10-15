package psaro.format;

import java.nio.charset.StandardCharsets;

/**
 * What a TrueType or OpenType font file ({@code .ttf}, {@code .otf}) says it is: its family and
 * its weight, read from its {@code name} and {@code OS/2} tables, as a typeface's files are
 * grouped by family and told apart by weight.
 *
 * <pre>
 * header:   u32 version (0x00010000, "true" or "OTTO"), u16 table count, 6 bytes, then per table
 *           4-byte tag, u32 checksum, u32 offset, u32 length         (all big-endian)
 * name:     u16 format, u16 count, u16 string offset, then per name 6 × u16: platform,
 *           encoding, language, name id, length, offset
 * OS/2:     u16 version, s16 average width, u16 weight class (400 regular, 700 bold, 900 black)
 * </pre>
 *
 * The family is the typographic family (name 16) when there is one, as a family of many weights
 * names each of its files by its own family otherwise (name 1), and that often ends with the
 * file's weight ({@code Rounded Mplus 1c Bold}), which is taken off so its weights come to one
 * family. A collection ({@code .ttc}) holds several fonts and is not read.
 */
public record OpenType(String family, int weight) {

	/** A weight's name at the end of a family name, with the space or hyphen before it. */
	private static final java.util.regex.Pattern WEIGHT_WORD = java.util.regex.Pattern.compile(
			"(?i)[ -](thin|hairline|extra ?light|ultra ?light|light|regular|normal|book|medium|semi ?bold|demi ?bold"
					+ "|bold|extra ?bold|ultra ?bold|heavy|black)$");
	private static final int TYPOGRAPHIC_FAMILY = 16;
	private static final int FAMILY = 1;
	private static final int WINDOWS = 3;
	private static final int UNICODE = 0;
	private static final int MAC = 1;
	private static final int ENGLISH_US = 0x409;

	/** Reads {@code d}'s family and weight; an exception when it is not a single font file. */
	public static OpenType read(byte[] d) {
		if (d.length < 12) {
			throw new IllegalArgumentException("not a font file");
		}
		int version = u32(d, 0);
		if (version != 0x00010000 && version != 0x74727565 && version != 0x4F54544F) {
			throw new IllegalArgumentException(version == 0x74746366 ? "a font collection (.ttc), not a single font"
					: "not a TrueType or OpenType font");
		}
		int tables = u16(d, 4);
		int name = -1;
		int os2 = -1;
		for (int i = 0; i < tables; i++) {
			int record = 12 + 16 * i;
			String tag = new String(d, record, 4, StandardCharsets.US_ASCII);
			if (tag.equals("name")) {
				name = u32(d, record + 8);
			} else if (tag.equals("OS/2")) {
				os2 = u32(d, record + 8);
			}
		}
		if (name < 0) {
			throw new IllegalArgumentException("the font has no names");
		}
		String family = name(d, name, TYPOGRAPHIC_FAMILY);
		if (family == null) {
			family = name(d, name, FAMILY);
			if (family != null) {
				String bare = WEIGHT_WORD.matcher(family.strip()).replaceFirst("").strip();
				family = bare.isEmpty() ? family : bare;
			}
		}
		if (family == null || family.isBlank()) {
			throw new IllegalArgumentException("the font names no family");
		}
		int weight = os2 >= 0 ? u16(d, os2 + 4) : 400;
		return new OpenType(family.strip(), weight < 1 || weight > 1000 ? 400 : weight);
	}

	/** Name {@code id} from the name table at {@code table}: Windows US English first, then any Windows or Unicode one, then a Mac one. */
	private static String name(byte[] d, int table, int id) {
		int count = u16(d, table + 2);
		int strings = table + u16(d, table + 4);
		String best = null;
		int bestRank = Integer.MAX_VALUE;
		for (int i = 0; i < count; i++) {
			int r = table + 6 + 12 * i;
			int platform = u16(d, r);
			int language = u16(d, r + 4);
			if (u16(d, r + 6) != id) {
				continue;
			}
			int rank = platform == WINDOWS && language == ENGLISH_US ? 0 : platform == WINDOWS || platform == UNICODE ? 1
					: platform == MAC ? 2 : -1;
			if (rank < 0 || rank >= bestRank) {
				continue;
			}
			int start = strings + u16(d, r + 10);
			int length = u16(d, r + 8);
			if (start + length > d.length) {
				continue;
			}
			best = new String(d, start, length, platform == MAC ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE);
			bestRank = rank;
		}
		return best;
	}

	/** The weight's usual name ({@code ExtraBold} for 800), or its number for one between them. */
	public static String weightName(int weight) {
		return switch (weight) {
			case 100 -> "Thin";
			case 200 -> "ExtraLight";
			case 300 -> "Light";
			case 400 -> "Regular";
			case 500 -> "Medium";
			case 600 -> "SemiBold";
			case 700 -> "Bold";
			case 800 -> "ExtraBold";
			case 900 -> "Black";
			default -> String.valueOf(weight);
		};
	}

	private static int u16(byte[] d, int i) {
		return (d[i] & 0xFF) << 8 | d[i + 1] & 0xFF;
	}

	private static int u32(byte[] d, int i) {
		return u16(d, i) << 16 | u16(d, i + 2);
	}
}
