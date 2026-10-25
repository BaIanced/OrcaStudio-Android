# Backlog (pending / untested / ideas). Grep, do not read up front.
Rules: ONE line per item: what is still open + who last checked it, on which build, when. Causes, commits, runs and history go
to HANDOFF-ARCHIVE.md (grep it; full pre-trim text under "2026-10-23 Backlog detail"). Never delete an open item; when it is
verified, move it to the archive (a "USER VERIFIED" tag is not a move). User 2026-10-15: do NOT bring up the X2D again.
Model tags: [Opus] = hard native/math/concurrency or big design; untagged = Sonnet is fine. Re-judge an item after 2 failed tries.

## Plan
- Plan (user 2026-10-14): camera LAN (done) -> off-LAN camera (cloud P2P; REA on Handy; VPN-home fallback) -> print history (Handy's call, own client, Device tab) -> REA on MakerWorld. Own printer/account/code only; rule 7 holds.
- Device tab printer controls (built r286): needs printer + user: extrude/jog/home motion, part fan reads 0 % after a set, target-0 tile stale, speed chip mid-print (Claude 2026-10-21).

## Open bugs
- 3MF export in desktop Orca GUI: fix applied (match_filament_indices, r273-era, archive); only the user can open a fresh export (ideally 4 filaments) in desktop Orca: "loads ok?" Claude check_3mf.py OK on WSA 3MF (2026-10-22).
- [Opus] Muse Task 12 JNI audit: 5/8/9 in r392 (9 WSA-checked; 8 needs a printer connect; 5 not exercised); 10/12/13 fixed 6303398, WSA r395 add text (52 chars)/SVG smoke OK (Claude 2026-10-24); 11 contract note, no change; 6 built r376, needs 2+ printers (Claude 2026-10-24).
- r197 NPE: root cause fixed 6ad69ef (r273); no in-app repro (Claude 2026-10-16).

## Untested (needs the user or a special setup)
- Preview: shading after slice? + inner z-fight on layer scrub (fixed r285, Claude 2026-10-17): user look pending.
- [Opus if reopened] Two-finger gestures: pan->zoom fix fa1754c (user r305 "still broken", untested since; r311 "pans feel fine" may cover it), pinch zoom-out over the object r312, pinch lock + Settings > Controls speeds (r280/r324), Quest stick zoom (r324; OK to merge untested): user feel pending.
- Corner resize gated behind long-press Resize / Ctrl+T (r305, 72dd138): probably superseded by the scale gizmo (user-verified r335, archive); ask before testing.
- Paint brush: fast strokes leave dots -> r324 capsule joins (Claude WSA 2026-10-19): user feel pending.
- Double tap object = frame head-on, again = return (r316, Claude Chromebook 2026-10-19): user feel pending.
- Extended paint tools (upstream set, stroke lock, overhang highlight, hold-to-preview fill, regions view, one-tap actions, fill stops + guard plane; Claude WSA 2026-10-20/21): user feel untested. Unpicked survey ideas: NOTES-paint-survey.md.
- Paint panel layout C + paint shop 1a-1e-ii (Claude WSA 2026-10-21): user feel untested; portrait dock + long-press menu, Clean specks with lock untested; rename stroke-axis "Lock ↕/↔" chips?
- Paint depth (r367, Claude WSA G-code OK 2026-10-21): user feel + real print untested.
- Paint shop reopen fix (Claude WSA r374) + plate estimate line (Claude WSA r376 = Preview; one density, part-level filaments ignored): user look/feel.
- Check mesh: marker readability, self-intersection count on a real file, thin walls (Claude WSA test STL, run 39657856306).
- Cut tool: flip keeps paint (Claude WSA 2026-10-21), user untested. Awaiting go: radio Lay on cut face / Flip / Leave + nested "Arrange after cut" (arrange_plate always runs, OrcaScene.cpp:1046); user asked the point of "Flip upper part upside down".
- Fan slider hold-until-within-4 % (r295) + Extrude F150 e13bebb: need the printer.
- Preview bead look (Chromebook), part shadows r266, light/shadow lab sliders r263: Claude-verified, user hasn't seen.
- Portrait Undo/Redo top left, File top right (Claude phone r283 2026-10-17): user look pending.
- Drag-handle fling: wide PanelEdge (WSA mouse inconclusive, r300) and OpenSCAD grips untested; portrait flick user-verified r294.
- Stylus 7d3a263 (palm rejection, mappable pen buttons/eraser, no pressure/tilt): untested on a pen (Note 10+ S Pen / Quest pseudo pen; super low priority, user 2026-10-21). Pie menu (user dislikes it): LOW, pen-button customizable tool picker, trigger untested. Sketch-mode idea undecided.
- Nozzle match: newer-firmware `device.nozzle.info`, stale status after a printer-model switch, spool swap drops the pick.
- Model-mismatch guard 54b4ca6 (one printer); SHIELD sign-ins (r210); element blocker in plugin/Device WebViews; true EGL context loss; "failed OpenSCAD run clears the old log": no repro / no setup.
- View cube: phone portrait layout untested; off by default (user r352: motion sick); revisit only on request.

## Waiting on the user
- HD plate ideas (user verified r394): 3D bed frame model, dual-nozzle decal layout (Claude 2026-10-24).
- Plate switcher pick (lab stays; don't ask again). Launcher icon colour ("decide later"). REA MCP enable (installed, not enabled).
- Edge outline in Prepare (crease lines, View toggle; regions view already has crease_lines()): waiting on GO (2026-10-20).
- [Opus] Build workspace (user GO 2026-10-23): steps 1-5 done + restart shift fixed; 6a per-shape filament Claude-verified r386 WSA 2026-10-23 (user feel untested) (Claude-verified r385 WSA 2026-10-23; user feel of 4b/4c/5 untested; multi-copy/mirrored copies in Build untested). 6b Merge bake + 6c-1 tap/drag inside groups Claude-verified WSA debug APK 2026-10-23 (in r387; Merge button/undo, user feel untested). 6c-2 gizmos inside groups Claude-verified WSA debug APK 2026-10-23 (6df309d, unbuilt; nested/repeated groups + user feel untested); Muse Task 18 audit merged 2026-10-23 (PR #64; device check of the circular-repeat scale refusal untested).

## Ideas (not started)
- Floating Theme | Layout designer (~half a day; ask first). Study Bambu Handy's MakerWorld integration.
- Headless display gaps (Reference "Headless display"): pinch on the virtual display, `start` stress test, WSA VD screenshot.
- Quest: 2D panel confirmed (user 2026-10-21); open only input beyond touch + native VR view [Opus] (on request). Phone <-> Quest link (undefined).
- Filament colour mixing (upstream FilamentMixer.cpp). Not wanted: iOS port.
- Orca plugin runtime kept generic: not done: audit prompts, create_window, per-preset overrides, printerAgent. FilamentHub dropped for good.
- Push/pull mesh (SketchUp style): tier 1 shape-builder faces (~1 day) first, tier 2 SCAD wrapper [Opus] (archive).
- [Opus] OrcaMesh direct mesh editing (CGAL Surface_mesh + PMP, Blender bmesh ports; MVP 1-2 weeks; archive).
- Public release check: built-in adblock + hard-coded Models tabs vs store policies / site terms (not researched).
- Colour icon set shortlist + mockup (low). Paint card fold direction by screen position (low). "Need phone" prompt (shelved).
- [Opus] Filament materials tiers 1+2 (layer lines r391, View menu), per-slot look, Realistic render 1+2: Claude WSA debug 2026-10-24,
  user feel untested (layer line contrast in realistic mode); variable layer height ignored; open: Quest/Chromebook GPU check. Google Filament engine not wanted (breaks Shaders.kt drags, ids, uSolo).
- MCP tools: app_state, log_tail, perf. Preview camera-light slider (no GO). Blender print3d_toolbox checks (pie menu: see stylus).

## Known limits (no action)
- Design mode hard-coded colours (delete popup #2B2C32, OverlayParts.kt:253; preview legend; Models page); dark 3D background under a light theme hides floating icons.
- Real keyboard Ctrl+1 = system hotkey. Inactive-plate double tap re-frames (user: fine). Badges ~23 dp at plate zoom (told the user).
- HMS texts: HmsCatalog.kt fetches at runtime; Handy's copies stay local. Flatpak r9 MakerLab, upstream bump, obn follow-ups: archive.
