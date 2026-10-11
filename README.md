# <img width="64" height="64" alt="psaroeditor icon" src="https://github.com/user-attachments/assets/6f9d4a1e-4d49-48d8-8b8d-d8b4fb81adde" /> PsaroEditor 

A translation workbench for Nintendo 3DS games. Point it at
an extracted romfs and it reads the game's string tables, layouts, archives, images, models, and fonts,
and writes them back, including adding the English glyphs the game's Japanese-only fonts lack.

No game data is included or distributed. You need your own extracted romfs.

<img width="1392" height="971" alt="Psaro1" src="https://github.com/user-attachments/assets/4d9e16c1-3ce2-4b84-8362-005da7ca12c4" />

### Acknowledgements
The 3DS file formats PsaroEditor reads and writes (BCFNT, BCLYT, BCLIM, DARC, CGFX) are documented by the community at [3dbrew](https://www.3dbrew.org/), and Nintendo's LZ compression by Martin Korth's [GBATEK](https://problemkaputt.de/gbatek.htm). Thanks to their contributors.

### AI Disclosure: 
Claude was used for most of the construction of this tool - a personal project to help understand how translation/romhacking works and to streamline tedious aspects of string replacement.  
Code written by Claude was reviewed manually, tested and unit tested.
