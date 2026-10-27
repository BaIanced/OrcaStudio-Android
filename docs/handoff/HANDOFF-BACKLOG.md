# Backlog (pending / untested / ideas). Grep, do not read up front.
Rules: ONE line per item: what is still open + who last checked it, on which build, when. Causes, commits, runs and history go
to HANDOFF-ARCHIVE.md (grep it; full pre-trim text under "2026-10-23 Backlog detail"). Never delete an open item; when it is
verified, move it to the archive (a "USER VERIFIED" tag is not a move). User 2026-10-15: do NOT bring up the X2D again.
Model tags: [Opus] = hard native/math/concurrency or big design; untagged = Sonnet is fine. Re-judge an item after 2 failed tries.

## Plan
- Plan (user 2026-10-14): camera LAN (done) -> off-LAN camera (cloud P2P; REA on Handy; VPN-home fallback) -> print history (Handy's call, own client, Device tab) -> REA on MakerWorld. Own printer/account/code only; rule 7 holds.
- Device tab printer controls (built r286): needs printer + user: extrude/jog/home motion, part fan reads 0 % after a set, target-0 tile stale, speed chip mid-print (Claude 2026-10-21).

## Open bugs
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
- Cut tool: flip keeps paint (Claude WSA 2026-10-21), user untested. Step 9 tap/long-press align/x-ray/upright shift Claude-verified WSA debug 2026-10-27 (archive), user feel untested. Awaiting go: radio Lay on cut face / Flip / Leave + nested "Arrange after cut" (arrange_plate always runs, OrcaScene.cpp:1046); user asked the point of "Flip upper part upside down".
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
- [Opus] Sculpt mode (CAD 5b, More > Sculpt; the user's playdough wish from push/pull r414): Claude-verified WSA debug 2026-10-26 (archive), user feel untested. Brush ring + stroke-end split Claude-verified WSA debug 2026-10-26 (r428, archive), user feel untested. Open: symmetry, ~1 s first stroke at 200k faces, paint overlay only at stroke end.
- [Opus] Build workspace (user GO 2026-10-23): steps 1-6c-2 Claude-verified WSA (r385-r406, archive); open: user feel of all, multi-copy / mirrored copies in Build, nested/repeated groups with gizmos.

## Ideas (not started)
- Floating Theme | Layout designer (~half a day; ask first). Study Bambu Handy's MakerWorld integration.
- Headless display gaps (Reference "Headless display"): pinch on the virtual display, `start` stress test, WSA VD screenshot.
- Quest: 2D panel confirmed (user 2026-10-21); open only input beyond touch + native VR view [Opus] (on request). Phone <-> Quest link (undefined).
- Filament colour mixing: Kotlin-only (core already resolves mixed slots from filament_is_mixed/_mixed_components/_sublayer_ratios; NOTES-upstream-2.5.md 2026-10-25). Untested.
- Orca plugin runtime kept generic: not done: audit prompts, create_window, per-preset overrides, printerAgent. FilamentHub dropped for good.
- [Opus] Push/pull tier 2: SCAD wrapper for any face of SCAD/MESH shapes (archive 2026-10-21 plan).
- [Opus] OrcaMesh direct mesh editing (CGAL Surface_mesh + PMP, Blender bmesh ports; MVP 1-2 weeks; archive).
- Public release check: built-in adblock + hard-coded Models tabs vs store policies / site terms (not researched).
- Colour icon set shortlist + mockup (low). Paint card fold direction by screen position (low). "Need phone" prompt (shelved).
- [Opus] Filament materials tiers 1+2 (layer lines r391, View menu), per-slot look, Realistic render 1+2: Claude WSA debug 2026-10-24,
  user feel untested (layer line contrast in realistic mode); variable layer height ignored; open: Quest/Chromebook GPU check. Google Filament engine not wanted (breaks Shaders.kt drags, ids, uSolo).
- MCP tools: app_state, log_tail, perf. Preview camera-light slider (no GO). Blender print3d_toolbox checks (pie menu: see stylus).
- [Opus] Snapseed interaction plan (NOTES-interaction-plan.md): steps 1-7, 8a scrubber, 8b slices 1-2 (region edits, Smooth, Modifier) Claude-verified (8a+8b-1 on CI r437, 8b-2 on r438, 2026-10-27), user feel untested on all; step 9 Cut Claude-verified WSA debug 2026-10-27 (CI after r438).
## Known limits (no action)
- Real keyboard Ctrl+1 = system hotkey. Inactive-plate double tap re-frames (user: fine). Badges ~23 dp at plate zoom (told the user).
- HMS texts: HmsCatalog.kt fetches at runtime; Handy's copies stay local. Flatpak r9 MakerLab, upstream bump, obn follow-ups: archive.
- Paint guard tool (guard plane) misbehaves, user can't describe yet (user report 2026-10-25, r401). Open: needs repro from user.
- Edge bands on painted rounded edges + realistic ignoring paint: fixed r403 (Claude phone debug OK 2026-10-25); user check open.
- Upstream 2.5.0 alpha overlaps our work: NOTES-upstream-2.5.md (compare table); check src-orca before building any feature, keep ours where better (user 2026-10-25). Open: side-by-side checks -> Sonnet 5.5 (medium); CAD/OCCT decision -> Opus.
- DECISION (user, asked 2026-10-25): push/pull -> fold into resize as face drag (drop chip) or remove; and whether to plan the CAD roadmap below. Claude's rec: fold + roadmap in order.
- CAD roadmap (Claude rec 2026-10-25, not started, needs user go): 1 DONE 2026-10-27 (group "Hull" switch; Claude-verified WSA r445, user feel untested), 2 BUILT 2026-10-27 (XY edge snap: legacy pane 8f83f9b, Build workspace 657e897; untested on device), 3 [Opus] deformers bend/twist/taper/lattice (med), 4 [Opus] sweep + loft (med), 5 [Opus] fillets between shapes (med-big; part 1 "Blend joins" Claude-verified WSA r419 2026-10-26, user feel untested), 6 [Opus] sculpt mode grab/smooth/inflate/crease on subdivided mesh, result = plain mesh (big; user's "playdough" wish), 7 asset library (figures, wheels). Skip Blender-style mesh edit mode on touch. Reuse plan 2026-10-25: NOTES-cad-reuse.md (upstream libslic3r/CAD + OCCT already in tree, SLIC3R_CAD off; phase 0 = [Opus] OCCT spike). User accepted 2026-10-25; steps tagged model/effort; step 0 next.
- Truck demo people visibility: check through the windows with the builder closed (WSA plate 6, r416); user or Claude.
- 3MF writes effective bed type per plate + project (plate 4 / printer "unknown"), label "Cool Plate (SuperTack)": built, untested (moved from handoff 2026-10-26).
- Measure steps 1-6 (r430-r434 + steered camera toggle 015b132): Claude-verified WSA debug 2026-10-26 (archive); open = user feel (steer vs plain loupe on the phone, 2nd-finger freeze, axis lock, mouse hover + click, finger grab 1:1, S Pen hover/tap on the Note); Alt+click, ring-to-ring between two holes, hole bottom rim from a bed aim untested. 2026-10-27: steer framing, tap-select, loupe, Tuning built (Claude-verified WSA debug); user feel on the phone open.
- CAD user feel open: 2a-2c sketch Twist / Rise per turn / Loft / Sweep (r421), 3 Bend / twist / taper (More menu, r423); Claude-verified WSA 2026-10-26. Smooth blend (More, r429: stall/cancel/size/inside-out fixed, Claude WSA 2026-10-26): phone run Claude-verified r429; user feel untested.
- Dimensions overview idea (user asked 2026-10-27; leaning only): per-object list of bbox, hole diameters/depths, thinnest wall; tap entry places measure points. CAD tools auto-dimension from parametric features; meshes need detection. Not started.
- Center / Align / smart guides (user idea 2026-10-27, leaning only): Prepare long-press Center (selection as group on plate, overlap OK), Align row L/C/R T/M/B on multi-select, snapping to plate centre/edges + object edges/centres in Prepare; add plate centre + object centres to the shape-builder edge snap. Roadmap 2 XY edge snap built 8f83f9b (untested on device).
