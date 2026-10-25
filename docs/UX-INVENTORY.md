# UX inventory and proposals (from crawler reports, 2026-10-24)

Source: 6 crawl runs (seeds 1/7/11/23, ~560 steps, ~270 distinct screen hashes, WSA). Evidence is the text on each screen;
nothing here was judged visually. Proposals are untested ideas, ordered by value / effort.

## 1. Screen inventory (what the crawl reached)

| Area | Screens seen | Notes |
|---|---|---|
| Prepare (plate view) | Plate tabs 1-7, Add / Add plate / Arrange / Auto orient / Cut / Measure, selection state ("1 selected", "· modified") | Main hub; most hashes differ only by plate count or "modified" badges |
| Add menu | Import model / project, Shape, Shape builder, Text, SVG, OpenSCAD model, Sample models, Calibration | Shape dialog: Cube/Sphere/Cylinder/Cone + size X/Y/Z |
| Calibration | Calibration list (temp tower, flow, PA lines/tower, retraction, VFA, input shaping, cornering, max volumetric speed), per-test From/To/Step form | Form states the test replaces the plate |
| Plate menu | Arrange plate, Delete plate, Pauses & color changes, Plate type, Slice plate | Plate type picker: 7 plate types |
| Preview | Layer slider (Layer up/down, "151 / 151 · 30.20 mm"), Line type, Color scheme, stats (time, filament m/g) | Per-plate "not sliced yet" empty state |
| Device | Printer status ("Waiting for status…"), Controls (bed/nozzle temps, A1-A4 AMS, Camera), Connection dialog | Camera error text shown when printer unreachable |
| View | Fit plate, Front/Back/Left/Right/Top, Isometric, Layer lines, Section view, Realistic render | Realistic render has a finish list (basic, matte, satin, glossy, silk, translucent) |
| Preset editor | Process / Filament / Printer tabs; sub-pages Basic information, Cooling, Dependencies, Advanced, Expert, Capabilities; "Changed only" toggle; per-field help dialogs; G-code editors; Compare / Export / Reset all / Delete / Save as user preset | By far the largest area (~half of all hashes), mostly per-field help popups |
| Filament | Flushing volumes matrix, filament picker with 9+ variants per spec, material type list, tiers | Picker entries are long ("260 A1 eSUN TPU-LW Flow Rate & Vol Calibrated") |
| File menu | New project, Open…, Save project…, Export plate as STL, Recent files; project-vs-models prompt; filament conflict prompt (Keep mine / Project / Both) | |
| Painting | Color / Fuzzy skin / Seam / Support painting | |
| Dialogs | Quit? ("plates are kept"), Error dialogs | |

## 2. Findings from the crawl

1. **Recent files list shows dead entries.** Tapping a stale Recent entry gives raw system text:
   "<package> has no access to content://media/external/file/…" and
   "…/cache/downloads/….zip: open failed: ENOENT". Both are dead ends with a bare OK. Stale-Recent fix is built locally, not yet in a CI build.
2. **Recent files shows opaque names** (UUID .zip files next to real names) with no date/size/location, so they can't be told apart.
3. **Quit loop.** Back at the root toggles Prepare <-> "Quit?" (seed 1: 60 steps, no way further). Expected design, but it means Back never reaches anything new; fine.
4. **App death after "Yes" on Quit** (seed 7) is the intended exit, not a crash. The crawler should treat it as a clean exit (tooling fix).
5. **Seed 11 step 3-4: empty UI dump after tapping "Edit", app not in foreground** and a logged "Bad file descriptor" in the crash handler. The crash buffer in that report is the tail of a process kill (pid 25257), not an app exception; cause unconfirmed. Worth one targeted repro of the Edit button on the plate-type screen path (untested).
6. **Preset editor is deep and flat.** ~40 distinct hashes are per-field help popups (e.g. `filament_retraction_length`). Popups show the raw config key in the body ("filament_is_support"), which is developer text.
7. **Duplicated-looking states:** same tab bar with only "· modified" or plate count changing; no bug, but screen-reader users get no distinct title per screen.
8. **Privacy:** the Device connection dialog text includes the saved printer IP and serial in the UI dump. Crawl reports must stay out of the repos (kept in `~/orca-android-tools/crawl-out`, not committed).

## 3. Proposals

| # | Proposal | Value | Effort |
|---|---|---|---|
| P1 | Recent files: drop entries whose URI can't be opened (already built locally), show "File no longer available" instead of raw system errors, show folder/date under each name | high | small |
| P2 | Friendly error dialog text for open failures (no package name, no `content://`, no cache path); offer "Remove from Recent" | high | small |
| P3 | Hide raw config keys in per-field help popups (strip the trailing `key_name` line, or show it small as "Setting: …") | medium | small |
| P4 | Preset editor: search box across all options + "Changed only" shown as chip with count | medium | medium |
| P5 | Filament picker: group variants by temperature as sub-labels ("Flow rate calibrated" as a badge instead of in the name) | medium | small |
| P6 | Add content descriptions / titles per screen (accessibility, and makes the crawler hashes distinct) | low-med | small |
| P7 | Crawler: treat Quit->Yes as clean exit; skip "Quit?" loops by not pressing Back at root; add Edit-button repro | tooling | small |

## 4. Not covered by the crawl

Anything needing GL looks (painting tools, Realistic render quality, gizmos), printer-connected flows, S Pen, Quest, and the Muse/lab screens.
The crawl only proves reachability and no-crash, not that screens look right.
