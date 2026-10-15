# <img width="64" height="64" alt="psaroeditor icon" src="https://github.com/user-attachments/assets/6f9d4a1e-4d49-48d8-8b8d-d8b4fb81adde" /> PsaroEditor 

A translation workbench for Nintendo 3DS games. Point it at
an extracted romFS and it reads the game's string tables, layouts, archives, images, models, and fonts,
and writes them back, including adding the English glyphs the game's Japanese-only fonts lack.

Also includes a Luma crash dump reader for debugging memory errors that might arise from squeezing in new glyphs and text.

No game data is included or distributed. You need your own extracted romfs.

<img width="1393" height="973" alt="Psaro2" src="https://github.com/user-attachments/assets/aefb4c9c-a433-4ff2-a04c-05b2a3030bdd" />

## How to Use:
* Open an existing romFS directory and it will scan for .tdt, .mdt, .csv, .bclyt files.
* Any changes made will be saved to a separate folder at the same level, leaving the existing romFS as-is.
* Navigate through string tables on the left, and through strings inside of those tables on the right.
* You can edit fonts, text pane attributes, the picture pane attributes (text pane borders, etc).
* For built-in text, link it to a pane to preview to assign it a font and generate missing glyphs.
* 'Save' saves all strings, whereas 'Patch' will verify whether new glyphs need to be added.
* When ready to package it up into a .zip, use File -> Export Patch..., which also adds a README for the patching process.

<img width="979" height="651" alt="Psaro3" src="https://github.com/user-attachments/assets/81d2cf61-a7ad-405b-935c-0a61464704d9" />

## Image Editing:
* In addition to string replacement, image replacement is included as well via the Images... button.
* In the Images editor, you can view all 2D and 3D model textures.
* Export any images that you need to edit and re-import them (at the same dimensions).
* You will need to use the Patch dialog to then get them re-written into the .lz's.

(Basically, if only strings were changed, use Save. If any images, panes, or fonts were edited, use the Patch window.)

### Acknowledgements
The 3DS file formats PsaroEditor reads and writes (BCFNT, BCLYT, BCLIM, DARC, CGFX) are documented by the community at [3dbrew](https://www.3dbrew.org/), and Nintendo's LZ compression by Martin Korth's [GBATEK](https://problemkaputt.de/gbatek.htm). Thanks to their contributors.

### AI Disclosure: 
Claude was used for most of the construction of this tool - a personal project to help understand how translation/romhacking works and to streamline tedious aspects of string replacement.  
Code written by Claude was reviewed manually, tested and unit tested.
