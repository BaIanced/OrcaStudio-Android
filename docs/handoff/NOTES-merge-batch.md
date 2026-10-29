# Merge batch plan (user 2026-10-28): main = user-tested code

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

## User sessions (about 10 min each, one device + one flow)
- A. Phone portrait: Undo/Redo top left + File top right; paint shop panel layout C, dock + long-press menu; paint brush fast strokes;
  extended paint tools; edge bands on painted rounded edges (r403); double tap object frames, again returns; Cut tool flip keeps paint.
- B. WSA mouse: Build workspace (incl. multi-copy / mirrored); Sculpt; Hollow; Center/Align + smart guides; Check mesh markers.
- C. Look (WSA or phone): Preview camera light slider (More > lab); Preview shading + layer scrub z-fight; bead look + part shadows;
  Filament materials + Realistic render (layer-line contrast). Optional later: Chromebook / Quest GPU check.
- D. CAD + interaction: sketch Twist/Rise/Loft/Sweep, Bend/twist/taper, Smooth blend, Hull; Snapseed interaction plan; Measure tools;
  two-finger gestures (one line: do pans/pinch feel fine on the latest build?).

## Questions to settle in-session
Cut tool: point of "Flip upper part upside down" / Lay on cut face. Push/pull: fold into resize (Claude rec) or remove.

## Status
Triage done 2026-10-28. Claude closes done. Session A done 2026-10-28 (extended paint tools feel left). Next: B, C, D.
