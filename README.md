# PsaroEditor

A translation workbench for Nintendo 3DS games. Point it at
an extracted romfs and it reads the game's string tables, layouts, archives and fonts,
and writes them back, including adding the English glyphs the game's Japanese-only
fonts lack.

**Status:** early. The file-format library is done and verified; the GUI is next.

No game data is included or distributed. You need your own extracted romfs.

## What it handles

| Format | Where | Class |
|---|---|---|
| LZ11 | the `.lz` wrapper on every archive | `Lz11` |
| DARC | the archive inside each `.arc.lz` | `Darc`, `Archive` |
| BCFNT | bitmap fonts embedded in each archive | `Bcfnt`, `Texture` |
| BCLYT | screen layouts: text panes, their fonts and box sizes | `Bclyt` |
| TDT | `romfs/text/*_Japanese.tdt` string tables | `Tdt` |

How the game puts text on screen, which shapes most of the design:

- Each archive carries its own fonts, trimmed to the glyphs its Japanese text used.
  A missing character draws as the font's fallback glyph, a space, so untouched fonts
  make English text vanish.
- A layout's text panes name their font, box size and font size; the string comes from
  the `.tdt` with the archive's name (`config.arc.lz` reads `config_Japanese.tdt`), by a
  key stored in the pane's user data.
- The game does not wrap lines; `\n` breaks them. Strings may carry colour switches
  (`\u0002\u0001` .. `\u0002\u0003`).

## Building

Requires JDK 21 or newer and Maven.

```
mvn -q compile
mvn -q test
```

`mvn test` runs the unit tests on synthetic data. To also check every reader and writer
against a real romfs (each archive, font and string table must survive read then write
byte for byte):

```
mvn -q test -Dpsaro.romfs=C:/path/to/romfs
```

or set the `PSARO_ROMFS` environment variable. Without either, those tests are skipped.

## Roadmap

1. File-format library with round-trip tests *(done)*
2. String editor: a table of keys, Japanese and English per screen, with a live preview
   in the game's font at the pane's real size, and overflow / missing-glyph warnings
3. Automatic font building from donor fonts inside the romfs
4. Glyph generation from TrueType fonts, styled to match each game font
5. Export: a LayeredFS folder for Luma3DS / Citra, and xdelta patches

## License

MIT; see [LICENSE](LICENSE).
