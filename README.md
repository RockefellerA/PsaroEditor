# <img width="64" height="64" alt="psaroeditor icon" src="https://github.com/user-attachments/assets/6f9d4a1e-4d49-48d8-8b8d-d8b4fb81adde" /> PsaroEditor 


A translation workbench for Nintendo 3DS games. Point it at
an extracted romfs and it reads the game's string tables, layouts, archives and fonts,
and writes them back, including adding the English glyphs the game's Japanese-only
fonts lack.

**Status:** This is still very much in the early stages.

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
