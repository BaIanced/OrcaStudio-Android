# Backlog (pending / untested / ideas). Grep, do not read up front.
Rules: ONE line per item: what is still open + who last checked it, on which build, when. Causes, commits, runs and history go
to HANDOFF-ARCHIVE.md (grep it; full pre-trim text under "2026-10-23 Backlog detail"). Never delete an open item; when it is
verified, move it to the archive (a "USER VERIFIED" tag is not a move). User 2026-10-15: do NOT bring up the X2D again.
Model tags: [Opus] = hard native/math/concurrency or big design; untagged = Sonnet is fine. Re-judge an item after 2 failed tries.

## Plan
- Plan (user 2026-10-14): camera LAN (done) -> off-LAN camera (cloud P2P; REA on Handy; VPN-home fallback) -> print history (Handy's call, own client, Device tab) -> REA on MakerWorld. Own printer/account/code only; rule 7 holds.
- Device tab printer controls (built r286): needs printer + user: extrude/jog/home motion, part fan reads 0 % after a set, target-0 tile stale, speed chip mid-print (Claude 2026-10-21).

## Open bugs
- Mouse right-click (user 2026-10-28): `lateButton` fallback in r463+ (WSA no-regression only). Open: which device the user used; check there when they say.
- Paint shop friction (user 2026-10-28): fixed in r463+ (archive). Open: Sync swatch needs a Bambu connection (untested, Claude).
- [Opus] Muse Task 12 JNI audit: 8 needs a printer connect, 5 not exercised, 6 needs 2+ printers (Claude, r392-r395, 2026-10-24); rest fixed or no change.

## Untested (needs the user or a special setup)
- Preview: shading after slice? + inner z-fight on layer scrub (fixed r285, Claude 2026-10-17): user look pending.
- [Opus if reopened] Two-finger gestures: pan->zoom fix fa1754c (user r305 "still broken"; r311 "pans feel fine" may cover it), pinch zoom-out r312, pinch lock + speeds (r280/r324), Quest stick zoom (r324): user feel pending.
- Corner resize gated behind long-press Resize / Ctrl+T (r305, 72dd138): probably superseded by the scale gizmo (user-verified r335, archive); ask before testing.
- Extended paint tools (upstream set, stroke lock, overhang highlight, hold-to-preview fill, regions view, one-tap actions, fill stops + guard plane; Claude WSA 2026-10-20/21): user feel untested. Unpicked survey ideas: NOTES-paint-survey.md.
- Paint panel layout C: modes verified by user r453 2026-10-28. Clean specks with lock Claude-verified WSA r456 2026-10-28 (archive). Chips renamed "Straight ↕/↔" (strings only, in next build, untested).
- Paint depth (r367, Claude WSA G-code OK 2026-10-21): user feel + real print untested.
- Paint shop reopen fix (Claude WSA r374) + plate estimate line (Claude WSA r376 = Preview; one density, part-level filaments ignored): user look/feel.
- Check mesh: marker readability, self-intersection count on a real file, thin walls (Claude WSA test STL, run 39657856306).
- Step 9 (tap/long-press align, x-ray, upright shift) Claude-verified WSA 2026-10-27 (archive), user feel untested. Awaiting go: Lay on cut face / Flip / Leave radio + nested "Arrange after cut" (OrcaScene.cpp:1046); user asked the point of "Flip upper part upside down".
- Fan slider hold-until-within-4 % (r295) + Extrude F150 e13bebb: need the printer.
- Preview bead look (Chromebook), part shadows r266, light/shadow lab sliders r263: Claude-verified, user hasn't seen.
- Drag-handle fling: wide PanelEdge (WSA mouse inconclusive, r300) and OpenSCAD grips untested; portrait flick user-verified r294.
- Stylus 7d3a263 (palm rejection, mappable pen buttons/eraser, no pressure/tilt): untested on a pen (Note 10+ S Pen / Quest pseudo pen; super low priority, user 2026-10-21). Pie menu (user dislikes it): LOW, pen-button customizable tool picker, trigger untested. Sketch-mode idea undecided.
- Nozzle match: newer-firmware `device.nozzle.info`, stale status after a printer-model switch, spool swap drops the pick.
- Model-mismatch guard 54b4ca6 (one printer); SHIELD sign-ins (r210); element blocker in plugin/Device WebViews; true EGL context loss; "failed OpenSCAD run clears the old log": no repro / no setup.
- View cube: phone portrait layout untested; off by default (user r352: motion sick); revisit only on request.

## Waiting on the user
- HD plate ideas (user verified r394): 3D bed frame model, dual-nozzle decal layout (Claude 2026-10-24).
- Plate switcher pick (lab stays; don't ask again). Launcher icon colour ("decide later"). REA MCP enable (installed, not enabled).
- [Opus] Sculpt mode (More > Sculpt, r428+; Claude-verified WSA 2026-10-26..28, archive; user feel untested). Symmetry 51ad144 untested on device by user. Open (low value): paint overlay only at stroke end, exact bump positions on a face, grab on the plane.
- [Opus] Build workspace (user GO 2026-10-23): steps 1-6c-2 Claude-verified WSA (r385-r406, archive); open: user feel of all, multi-copy / mirrored copies in Build, nested/repeated groups with gizmos.

## Ideas (not started)
- Floating Theme | Layout designer (~half a day; ask first). Study Bambu Handy's MakerWorld integration.
- Headless display gaps (Reference "Headless display"): pinch on the virtual display, `start` stress test, WSA VD screenshot.
- Quest: 2D panel confirmed (user 2026-10-21); open only input beyond touch + native VR view [Opus] (on request). Phone <-> Quest link (undefined).
- Orca plugin runtime kept generic: not done: audit prompts, create_window, per-preset overrides, printerAgent. FilamentHub dropped for good.
- [Opus] Push/pull tier 2: SCAD wrapper for any face of SCAD/MESH shapes (archive 2026-10-21 plan).
- [Opus] OrcaMesh: sculpt brushes (5a/5b), smooth blend, hull, bend/twist/taper, sweep/loft are built (see CAD line below). Still open and optional: vert/edge/face edit mode (extrude, inset, loop cut, bevel via CGAL Surface_mesh + PMP); the CAD roadmap said skip it on touch.
- Public release check: built-in adblock + hard-coded Models tabs vs store policies / site terms (not researched).
- Colour icon set shortlist + mockup (low). Paint card fold direction by screen position (low). "Need phone" prompt (shelved).
- [Opus] Filament materials tiers 1+2, per-slot look, Realistic render 1+2 (Claude WSA debug 2026-10-24): user feel untested (layer line contrast in realistic mode); variable layer height ignored; open: Quest/Chromebook GPU check. Google Filament engine not wanted.
  user feel untested (layer line contrast in realistic mode); variable layer height ignored; open: Quest/Chromebook GPU check. Google Filament engine not wanted (breaks Shaders.kt drags, ids, uSolo).
- Preview camera-light slider + Check mesh sharp-edge markers + Hollow: Claude-verified WSA (archive); open: user feel (sessions B/C in NOTES-merge-batch.md). Cleanup = More > Repair (exists).
- [Opus] Snapseed interaction plan (NOTES-interaction-plan.md): steps 1-9 Claude-verified WSA (r437/r438, 2026-10-27); user feel untested on all.
## Known limits (no action)
- Real keyboard Ctrl+1 = system hotkey. Inactive-plate double tap re-frames (user: fine). Badges ~23 dp at plate zoom (told the user).
- HMS texts: HmsCatalog.kt fetches at runtime; Handy's copies stay local. Flatpak r9 MakerLab, upstream bump, obn follow-ups: archive.
- Paint guard tool (guard plane) misbehaves, user can't describe yet (user report 2026-10-25, r401). Open: needs repro from user.
- Upstream 2.5.0 alpha overlap: NOTES-upstream-2.5.md (source checks done 2026-10-25); keep ours where better. All compare rows closed at source level (2026-10-28); nothing open.
- CAD roadmap (user accepted 2026-10-25; NOTES-cad-reuse.md): steps 0-6 built and Claude-verified on WSA (Hull r445, XY edge snap r447, Blend joins r419, sweep/loft r421, bend/twist/taper r423, sculpt r428), user feel untested; 7 asset library (figures, wheels) not started.
- Truck holes cut preview: Claude WSA-verified on the debug build 2026-10-29 (chef visible through the serving window). Sculpt on a cut part OK (r464, Claude 2026-10-29). Open: hole ghost alpha 0.45 tints the figures (try ~0.25), needs a CI build.
- Measure steps 1-6 + steer/tap-select/loupe/Tuning (Claude-verified WSA debug 2026-10-26/27); open = user feel on the phone (steer vs plain loupe, 2nd-finger freeze, axis lock, mouse hover, S Pen on the Note); Alt+click, ring-to-ring, hole bottom rim untested.
- CAD user feel open: sketch Twist / Rise per turn / Loft / Sweep (r421), Bend / twist / taper (r423), Smooth blend (r429); all Claude-verified WSA/phone 2026-10-26.
- Dimensions overview idea (user asked 2026-10-27; leaning only): per-object list of bbox, hole diameters/depths, thinnest wall; tap entry places measure points. CAD tools auto-dimension from parametric features; meshes need detection. Not started.
- Smart guides (BUILT 005f14d + Center/Align f073445, Claude-verified WSA 2026-10-27, user feel open). Open idea: plate + object centres in the shape-builder edge snap.

- MCP token efficiency (Opus review + analysis done 2026-10-27, `NOTES-mcp-token-efficiency.md`): proposed build rule waits for the user's OK; plan A-C (diff replies) r452 Claude-verified WSA 2026-10-28; D dropped.
- Hover tooltips: all 81 icon buttons with a content description now wrapped in Tip() (Claude 2026-10-28, compiles, WSA debug launch OK; hover/long-press not adb-testable); 3 colour-dot buttons have no label. User feel untested.
- [Opus] Build continuity (user brain dump 2026-10-28, exploration; archive): show the other objects ghosted in Build, one builder entry + CAD badge; user agreed with the leanings; open: mockup first, keep their vision as a toggle.
- Shape builder W/D/H scrub + proportional lock: Claude-verified WSA debug + in CI r458+ (archive 2026-10-28); open: user feel (on hold); proportional slider-mode unbuilt (spec unclear). Claude 2026-10-28.
- Shape builder bugs (user 2026-10-28, session B): drag lag, rings through the object, end copy drag scaling spacing: fixed 1a94899, Claude-verified WSA debug 2026-10-28 (archive); open: user feel (on hold, no user testing now).
- Snapping (user 2026-10-28: toggle/hotkey, not forced on the plate, thin Adobe-style guide): 846a663 Claude-verified WSA 2026-10-28; user feel untested.
- Session B checks skipped by user 2026-10-28 ("don't want to do right now"): Sculpt, Hollow, Center/Align, Check mesh, badge fix.
- Godot review leftovers (Z lift dot, axis lock, Wireframe, orbit inertia, Light direction: all in CI r463, Claude-verified WSA 2026-10-28, archive): open: user feel; tower ignores Z untested (low risk). [Opus]
