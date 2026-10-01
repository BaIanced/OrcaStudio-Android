# Changelog

> Orca-Android was developed with an AI coding assistant (Claude, Anthropic); see the
> [AI disclosure](README.md#ai-disclosure) in the README.

## 0.1.0 — first release

First public version, based on OrcaSlicer 2.5.0-dev (commit `2769b12`).

- OrcaSlicer's slicing engine and all printer, filament and process profiles, running on the
  device; printers are chosen by vendor, model and nozzle.
- Full settings editor with the desktop's pages, modes and option rules; user presets, import,
  export and comparison; profile updates from the OrcaSlicer repository.
- Prepare: plates, arrange, auto-orient, lay on face, transforms, cut, split, simplify, repair,
  measure, undo/redo; modifiers, blockers, enforcers and negative volumes; per-object, per-part and
  height-range settings; painting of supports, seams, fuzzy skin and colours; variable layer
  height; pauses, filament changes and custom G-code; shapes, text and SVG.
- All calibration tests of the desktop.
- 3MF projects compatible with the desktop, STL export, G-code and `.gcode.3mf` export.
- Toolpath preview with layer and move sliders, colour schemes, statistics and G-code view;
  external G-code files.
- Printing via Klipper/Moonraker, OctoPrint, PrusaLink, Duet, Repetier, ESP3D, MKS and Bambu Lab
  LAN; the printer's web interface in the Device tab; progress notification.
- Tablet and phone layouts, light and dark theme, English and German.
- The current state is saved when the app goes to the background and restored on the next start.
