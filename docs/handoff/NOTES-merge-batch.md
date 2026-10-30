# Merge batch plan (user 2026-10-28): main = user-tested code

**Update 2026-10-28:** PR #66 merged everything to main (3defffa, r461) before B-D ran, on the user's go-ahead. The sessions below are now
retroactive checks on main: run them soon, log verdicts in the archive, fix what breaks. Latest build to install: r461 or newer.

Rule: merge to main only when every non-printer item below is `ok` (user), fixed + re-checked, or cut. Printer items ride along
unverified; the RELEASE waits on them. User approves the merge itself. Verdict format: `N ok | N broken: few words | N skip`.
Log each verdict at once in HANDOFF-ARCHIVE.md (dated, build) and fix its backlog line. Install the build on the session's device first.

## Cut (user agreed 2026-10-28; never shown, leave in backlog as no-action)
Corner resize gating (superseded by scale gizmo), pie menu / stylus / S Pen on Note, view cube, wide PanelEdge + OpenSCAD grips fling,
model-mismatch guard, SHIELD sign-ins, element blocker, EGL loss, nozzle match, ideas list, Dimensions overview, Muse Task 12 leftovers.
Paint guard plane stays a known issue (no repro); not a gate.

## Printer set (out of gate, release-gated)
Paint depth real print + G-code, fan hold-until-within-4 %, Extrude F150, 3MF export opened in desktop Orca, Device tab controls
(jog/home, part fan read, speed chip).

## Claude closes (no user time)
DONE 2026-10-28: truck demo people visibility dropped (demo session gone from WSA, Hollow already verified; rebuild with truck.sh + truck2.sh only if the user wants it); upstream 2.5 compare rows all closed at source level (NOTES-upstream-2.5.md; upstream is desktop-only, no WSA side-by-side possible); Check mesh sharp-edge markers Claude-verified r450 (archive).

## User sessions (about 10 min each, one device + one flow; verdict `N ok | N broken: few words | N skip`)
- A. DONE 2026-10-28 (extended paint tools feel left open).
- B. WSA mouse: Build workspace (incl. multi-copy / mirrored); Sculpt (+ symmetry on X/Y/Z middle planes, stroke-end hull wait);
  Hollow; Center/Align + smart guides; Check mesh markers; shape builder number scrub (drag cells, 0.1 steps) + proportional size lock;
  snap toggle (magnet button, Shift+Tab, Ctrl flips per drag, magenta guide); Build Z lift dot (snaps to box tops / floor);
  axis lock (button Free/X/Y, keys X/Y/Z mid-drag, guide line); build gizmos follow a move drag.
- C. Look (WSA or phone): Preview camera light slider (More > lab); Preview shading + layer scrub z-fight; bead look + part shadows;
  Filament materials + Realistic render (layer-line contrast). Optional later: Chromebook / Quest GPU check.
- D. CAD + interaction: sketch Twist/Rise/Loft/Sweep, Bend/twist/taper, Smooth blend, Hull; Snapseed interaction plan; Measure tools;
  two-finger gestures (do pans/pinch feel fine?); two-finger tap opens the context menu; floating Slice button drag + optional plate
  switcher (Layout lab); Recent files collapsed; paint shop: view menu in the shop bar, Sync swatch (Bambu connected), entry keeps the 3D view;
  slice cancel shows no error badge; hover/long-press tooltips; mouse right-click.

## Questions to settle in-session
Cut tool: point of "Flip upper part upside down" / Lay on cut face. Push/pull: fold into resize (Claude rec) or remove.

## Status
Triage done 2026-10-28. Claude closes done. Session A done 2026-10-28. B, C, D pending (retroactive after the PR #66 merge); the printer set and release stay gated.

## User-untested in main (moved from HANDOFF.md 2026-10-29)
Plus all of sessions B-D, Sculpt, snap toggle, Z lift/axis lock: Build workspace, paint shop/depth, Filament materials,
Realistic render, UX P1-P6, Check mesh feel, 3MF export in desktop Orca, pen, fan hold, Extrude F150.
