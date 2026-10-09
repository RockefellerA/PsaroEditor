package psaro.patch;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import psaro.format.Bcfnt;
import psaro.romfs.RomfsIndex;

/**
 * A font drawn from a bundled {@link Typeface} to stand in for a game font, so the patch can
 * leave the game's fonts as they are: each archive that carries the game font gets this one
 * beside it, holding what the game font holds plus what the English adds, and its layouts are
 * pointed at it. Every glyph is the typeface's, in the game font's size and outline
 * ({@link GlyphDrawing}); what the typeface lacks (Noto Sans has no kana or kanji) is drawn from
 * M PLUS Rounded 1c, and what neither has is left out.
 *
 * <p>For a game font drawn under another in one text's layers (an outline or a glow of its own),
 * each letter is instead the top font's stand-in's, made into this layer ({@link UnderLayer}), so
 * the layers line up as the game's do.
 *
 * <p>A character the game font has keeps that font's advance, the typeface's letter centred in
 * it, so the Japanese the game lays out (a "はい" in a button made just wide enough for it) takes
 * the room it always did; only what the English adds is spaced as the typeface spaces it.
 *
 * <p>Glyphs are drawn once per game font and kept ({@link #glyphs}), measured on the copy of the
 * game font with the most glyphs, so every archive's copy comes out the same.
 */
public final class FreeFont {

	private final String gameFont;
	private final Typeface face;
	private final Bcfnt reference;
	/** The typeface ready to draw, and M PLUS Rounded 1c for what it lacks (none when it is the typeface). */
	private final GlyphDrawing.Pen pen;
	private final GlyphDrawing.Pen fallback;
	private String extraSpace;
	/** Each drawn glyph's advance before {@link #extraSpace} widens it. */
	private final Map<Integer, Integer> drawnWidth = new HashMap<>();
	/** Every glyph drawn so far, in one font; grows as more are asked for. */
	private final Bcfnt glyphs;
	/** What drew each code drawn so far. */
	private final Map<Integer, Typeface> drawnBy = new HashMap<>();
	/** Codes asked for that neither typeface has. */
	private final Set<Integer> undrawable = new TreeSet<>();
	/** For a lower layer, the font on top's stand-in its letters are made from, and how; else null. */
	private final FreeFont top;
	private final UnderLayer how;

	/**
	 * Draws for {@code gameFont} from {@code face}, measured on {@code reference} (a copy of the
	 * game font), each code in {@code extraSpace} one pixel wider.
	 */
	public FreeFont(String gameFont, Typeface face, Bcfnt reference, String extraSpace) {
		if (!face.bundled()) {
			throw new IllegalArgumentException("the game's fonts are not a typeface to draw from");
		}
		this.gameFont = gameFont;
		this.face = face;
		this.reference = reference;
		this.extraSpace = extraSpace;
		this.top = null;
		this.how = null;
		this.pen = new GlyphDrawing.Pen(face, gameFont, reference);
		this.fallback = face == Typeface.M_PLUS_ROUNDED ? null : new GlyphDrawing.Pen(Typeface.M_PLUS_ROUNDED, gameFont, reference);
		// both typefaces' cells, whatever is drawn, so a glyph comes out the same whichever others are
		this.glyphs = pen.draw(List.of());
		if (fallback != null) {
			Bcfnt other = fallback.draw(List.of());
			int above = Math.max(glyphs.baseline, other.baseline);
			int below = Math.max(glyphs.cellH - glyphs.baseline, other.cellH - other.baseline);
			glyphs.recell(Math.max(glyphs.cellW, other.cellW), above + below, above);
		}
		copyMetrics(reference, glyphs);
		draw(Set.of((int) ' '));
	}

	/**
	 * For {@code gameFont} (its copy {@code reference}), drawn under {@code top}'s game font in
	 * one text's layers: each letter is made from {@code top}'s as {@code how} says, keeping its
	 * advance, so the layers line up.
	 */
	public FreeFont(String gameFont, Bcfnt reference, FreeFont top, UnderLayer how) {
		this.gameFont = gameFont;
		this.face = top.face;
		this.reference = reference;
		this.extraSpace = "";
		this.top = top;
		this.how = how;
		this.pen = null;
		this.fallback = null;
		this.glyphs = how.derive(top.preview(), List.of(), reference);
		copyMetrics(reference, glyphs);
		draw(Set.of((int) ' '));
	}

	/** True for a lower layer's font, made from the font on top's. */
	public boolean layered() {
		return top != null;
	}

	/** The free font's file name: the typeface's in place of the game font's family, as {@code MPLUSRounded1c_B_04a_20.bcfnt}. */
	public static String name(String gameFont, Typeface face) {
		String family = RomfsIndex.family(gameFont);
		return family != null ? face.prefix() + gameFont.substring(family.length()) : face.prefix() + "_" + gameFont;
	}

	public String name() {
		return name(gameFont, face);
	}

	/** The typeface a free font's file name ({@link #name}) was drawn from; the game's fonts for another name. */
	public static Typeface typefaceOf(String freeName) {
		for (Typeface t : Typeface.values()) {
			if (t.bundled() && freeName.startsWith(t.prefix() + "_")) {
				return t;
			}
		}
		return Typeface.GAME;
	}

	public String gameFont() {
		return gameFont;
	}

	public Typeface face() {
		return face;
	}

	/** The typeface that drew {@code code}, or null when it is not drawn (yet, or at all). */
	public synchronized Typeface drawnBy(int code) {
		return drawnBy.get(code);
	}

	/** Draws whichever of {@code codes} are not drawn yet; returns those neither typeface has. */
	public synchronized Set<Integer> draw(Collection<Integer> codes) {
		if (top != null) {
			Set<Integer> missing = top.draw(codes);
			List<Integer> make = codes.stream().filter(c -> c >= 0x20 && !glyphs.has(c) && top.glyph(c) != null)
					.distinct().toList();
			if (!make.isEmpty()) {
				glyphs.addGlyphsFrom(how.derive(top.preview(), make, reference), make, false);
				make.forEach(c -> drawnBy.put(c, top.drawnBy(c)));
			}
			return missing;
		}
		Set<Integer> todo = new TreeSet<>();
		for (int c : codes) {
			if (c >= 0x20 && !glyphs.has(c) && !undrawable.contains(c)) {
				todo.add(c);
			}
		}
		if (!todo.isEmpty()) {
			add(face, pen, todo);
			if (fallback != null) {
				todo.removeIf(glyphs::has);
				add(Typeface.M_PLUS_ROUNDED, fallback, todo);
			}
			todo.removeIf(glyphs::has);
			undrawable.addAll(todo);
		}
		Set<Integer> missing = new TreeSet<>();
		for (int c : codes) {
			if (c >= 0x20 && undrawable.contains(c)) {
				missing.add(c);
			}
		}
		return missing;
	}

	private void add(Typeface t, GlyphDrawing.Pen with, Set<Integer> codes) {
		Bcfnt drawn = with.draw(codes);
		glyphs.addGlyphsFrom(drawn, drawn.cmap.keySet(), false);
		for (int c : drawn.cmap.keySet()) {
			drawnBy.put(c, t);
			Bcfnt.Glyph g = glyphs.glyph(c);
			Bcfnt.Glyph game = reference.glyph(c);
			if (game != null && c > 0x20) {
				// the game's advance, the letter centred in it
				g.left += Math.round((game.charWidth - g.charWidth) / 2f);
				g.charWidth = game.charWidth;
			}
			drawnWidth.put(c, g.charWidth);
		}
		Lending.widen(glyphs, drawn.cmap.keySet(), extraSpace);
	}

	/** Gives the letters of {@code letters} one more pixel of advance, and every other its own. */
	public synchronized void setExtraSpace(String letters) {
		if (top != null) {
			followTop();
			return;
		}
		if (letters.equals(extraSpace)) {
			return;
		}
		extraSpace = letters;
		glyphs.maxCharWidth = 0;
		drawnWidth.forEach((c, w) -> glyphs.glyph(c).charWidth = w);
		Lending.widen(glyphs, drawnWidth.keySet(), letters);
		for (Bcfnt.Glyph g : glyphs.glyphs) {
			glyphs.maxCharWidth = Math.max(glyphs.maxCharWidth, g.charWidth);
		}
	}

	/**
	 * For a lower layer, gives each letter the advance of the top font's, after that one's
	 * extra-space letters changed; a lower layer takes no extra space of its own.
	 */
	public synchronized void followTop() {
		if (top == null) {
			return;
		}
		glyphs.maxCharWidth = 0;
		for (Map.Entry<Integer, Integer> e : glyphs.cmap.entrySet()) {
			Bcfnt.Glyph above = top.glyph(e.getKey());
			Bcfnt.Glyph g = glyphs.glyphs.get(e.getValue());
			if (above != null) {
				g.charWidth = above.charWidth;
			}
			glyphs.maxCharWidth = Math.max(glyphs.maxCharWidth, g.charWidth);
		}
	}

	/** Every glyph drawn so far, for measuring; a live font, so it is not to be changed. */
	public synchronized Bcfnt preview() {
		return glyphs;
	}

	/** The glyph drawn for {@code code}, or null. */
	public synchronized Bcfnt.Glyph glyph(int code) {
		return glyphs.glyph(code);
	}

	/** A font file of {@code codes} (drawn first if need be), with a space for what it lacks to draw as. */
	public synchronized Bcfnt build(Collection<Integer> codes) {
		Set<Integer> take = new TreeSet<>(codes);
		take.add((int) ' ');
		draw(take);
		Bcfnt out = new Bcfnt();
		copyMetrics(reference, out);
		out.format = glyphs.format;
		out.pad = glyphs.pad;
		out.cellW = glyphs.cellW;
		out.cellH = glyphs.cellH;
		out.baseline = glyphs.baseline;
		for (int c : take) {
			Bcfnt.Glyph g = glyphs.glyph(c);
			if (g != null) {
				out.cmap.put(c, out.glyphs.size());
				out.glyphs.add(new Bcfnt.Glyph(g.pixels.clone(), g.left, g.glyphWidth, g.charWidth));
				out.maxCharWidth = Math.max(out.maxCharWidth, g.charWidth);
			}
		}
		// a character the font lacks draws as the space, as in the game's own fonts
		out.altIndex = out.cmap.get((int) ' ');
		out.fitSheets(256, 512);
		return out;
	}

	private static void copyMetrics(Bcfnt from, Bcfnt to) {
		to.fontType = from.fontType;
		to.encoding = from.encoding;
		to.lineFeed = from.lineFeed;
		to.height = from.height;
		to.width = from.width;
		to.ascent = from.ascent;
		to.defaultLeft = from.defaultLeft;
		to.defaultGlyphWidth = from.defaultGlyphWidth;
		to.defaultCharWidth = from.defaultCharWidth;
	}
}
