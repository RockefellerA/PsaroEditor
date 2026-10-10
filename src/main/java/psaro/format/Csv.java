package psaro.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code romfs/table/*.csv} data tables: the game's records (characters, cards, items, songs),
 * one per line. Some cells are names the game shows, drawing them from code, so no layout names
 * them: the party screen's character names are {@code CharaTable.csv}'s.
 *
 * <pre>
 * UTF-16LE after a byte order mark, lines ending CRLF, cells split on commas with no quoting
 * a cell's line break is a backslash: ドラゴンクエストII\悪霊の神々
 * </pre>
 *
 * The text is every cell holding Japanese, keyed {@code <row id>:<column>} ({@code dqc0101a:1}):
 * the row's id is its cell in the first of the first two columns whose cells are all different
 * and none Japanese ({@code ABI001} after a {@code True}), else the row's number ({@code 000}).
 * A comma cannot go in a cell, so the English shows a full-width one ({@link #shown}).
 */
public final class Csv {

	private static final char BOM = '﻿';

	private Csv() {
	}

	/** The cells holding Japanese, keyed by row id and column, with {@code \n} line breaks, in file order. */
	public static LinkedHashMap<String, String> read(byte[] d) {
		Table t = Table.of(d);
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (Cell c : t.text()) {
			out.put(c.key(), t.cells.get(c.row())[c.column()].replace('\\', '\n'));
		}
		return out;
	}

	/**
	 * {@code original} with each text cell {@code texts} has a key for holding that text instead,
	 * as the game shows it ({@link #shown}); every other byte as it was.
	 */
	public static byte[] write(byte[] original, Map<String, String> texts) {
		Table t = Table.of(original);
		boolean changed = false;
		for (Cell c : t.text()) {
			String text = texts.get(c.key());
			if (text == null) {
				continue;
			}
			String cell = shown(text).replace("\r", "").replace('\n', '\\');
			String[] row = t.cells.get(c.row());
			if (!cell.equals(row[c.column()])) {
				row[c.column()] = cell;
				changed = true;
			}
		}
		if (!changed) {
			return original;
		}
		StringBuilder out = new StringBuilder().append(BOM);
		for (int i = 0; i < t.lines.size(); i++) {
			if (i > 0) {
				out.append(t.newline);
			}
			Integer row = t.rowOfLine.get(i);
			out.append(row == null ? t.lines.get(i) : String.join(",", t.cells.get(row)));
		}
		return out.toString().getBytes(StandardCharsets.UTF_16LE);
	}

	/**
	 * {@code text} as a cell shows it: with each comma full-width ({@code ，}), the space after one
	 * dropped since the full-width comma has its own, as no cell can hold a comma.
	 */
	public static String shown(String text) {
		return text.replace(", ", "，").replace(',', '，');
	}

	/** A text cell: its row (among the lines that are not empty), its column, and its key. */
	private record Cell(int row, int column, String key) {
	}

	/** A file's lines, the line break they end with, and the cells of each one that is not empty. */
	private record Table(String newline, List<String> lines, List<String[]> cells, Map<Integer, Integer> rowOfLine) {

		static Table of(byte[] d) {
			if (d.length < 2 || d.length % 2 != 0 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xFE) {
				throw new IllegalArgumentException("not a UTF-16 data table");
			}
			String all = new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE);
			String newline = all.contains("\r\n") ? "\r\n" : "\n";
			List<String> lines = Arrays.asList(all.split(newline, -1));
			List<String[]> cells = new ArrayList<>();
			Map<Integer, Integer> rowOfLine = new LinkedHashMap<>();
			for (int i = 0; i < lines.size(); i++) {
				if (!lines.get(i).isEmpty()) {
					rowOfLine.put(i, cells.size());
					cells.add(lines.get(i).split(",", -1));
				}
			}
			return new Table(newline, lines, cells, rowOfLine);
		}

		/** Every cell holding Japanese, row by row. */
		List<Cell> text() {
			String[] ids = ids();
			List<Cell> out = new ArrayList<>();
			for (int r = 0; r < cells.size(); r++) {
				String[] row = cells.get(r);
				for (int c = 0; c < row.length; c++) {
					if (japanese(row[c])) {
						out.add(new Cell(r, c, ids[r] + ":" + c));
					}
				}
			}
			return out;
		}

		/** Each row's id: its cell in the first of the first two columns that tells every row apart, else its number. */
		private String[] ids() {
			for (int c = 0; c < 2 && !cells.isEmpty(); c++) {
				Set<String> seen = new HashSet<>();
				boolean unique = true;
				for (String[] row : cells) {
					if (c >= row.length || row[c].isEmpty() || japanese(row[c]) || !seen.add(row[c])) {
						unique = false;
						break;
					}
				}
				if (unique) {
					int column = c;
					return cells.stream().map(row -> row[column]).toArray(String[]::new);
				}
			}
			String[] numbers = new String[cells.size()];
			for (int r = 0; r < numbers.length; r++) {
				numbers[r] = String.format(Locale.ROOT, "%03d", r);
			}
			return numbers;
		}
	}

	/** Whether {@code text} holds kana or kanji: a name the game shows, not an id or a number. */
	private static boolean japanese(String text) {
		return text.codePoints().anyMatch(c -> c >= 0x3040 && c < 0xA000 || c >= 0xFF00 && c < 0xFFF0);
	}
}
