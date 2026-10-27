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
- [Opus] Sculpt mode (CAD 5b, More > Sculpt; the user's playdough wish from push/pull r414): Claude-verified WSA debug 2026-10-26 (archive), user feel untested. Brush ring + stroke-end split Claude-verified WSA debug 2026-10-26 (r428, archive), user feel untested. Open: symmetry, ~1 s first stroke at 200k faces, paint overlay only at stroke end.
- [Opus] Build workspace (user GO 2026-10-23): steps 1-5 done + restart shift fixed; 6a per-shape filament Claude-verified r386 WSA 2026-10-23 (user feel untested) (Claude-verified r385 WSA 2026-10-23; user feel of 4b/4c/5 untested; multi-copy/mirrored copies in Build untested). 6b Merge bake + 6c-1 tap/drag inside groups Claude-verified WSA debug APK 2026-10-23 (in r387; Merge button/undo, user feel untested). 6c-2 gizmos inside groups Claude-verified WSA debug APK 2026-10-23 (6df309d, unbuilt; nested/repeated groups + user feel untested); Muse Task 18 audit merged 2026-10-23 (PR #64; circular-repeat scale refusal Claude-verified WSA r406 2026-10-25).

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
- [Opus] Snapseed-inspired interaction ideas: PLAN in NOTES-interaction-plan.md (user GO 2026-10-26; step 1 Measure snap highlight built, Claude-verified WSA debug 2026-10-26, user feel untested; step 2 next). Original brain dump below:
  only a remembered example of friendly UI; keep every idea open, prefer shared engines so no early choice blocks a later one. Not started.
  - Engines (features = combinations): (E1) surface picker / region grow over faces, pluggable stop rules (crease angle, coplanar,
    cylinder fit, geodesic radius); welded adjacency, off UI thread on huge meshes; desktop Orca smart fill may already cover the core
    (check src-orca). (E2) overlay renderer: per-face mask, feature highlight, x-ray/ghost, two-tone. (E3) precision pointer: sharp 2nd-view
    loupe (not framebuffer zoom), offset or steered cursor, per tool type. (E4) camera director: framing, easing, speed rules, freeze.
    (E5) gesture scrubber: vertical drag picks a setting, horizontal changes it; clashes with one-finger orbit, so tool-mode only, 2 fingers = camera.
  - Measure (user tried WSA, gear edge bottom-to-top was hard): biggest gap = no snap highlight (can't see what it caught). Highlight the
    whole feature (face, edge line, hole ring) + dot at the snap point, in view and loupe (E1+E2). Feature-to-feature results: parallel
    faces = separation (no slant error), point-face, hole diameter / centre distance; bed pickable as a face; always show dX/dY/dZ.
    Input type: mouse = exact cursor, hover preview, no offset (today the point jumps away from the mouse as if it were a finger);
    S Pen = hover shows highlight/loupe; finger = loupe/offset. Snapseed-style select point, then drag anywhere (relative, 1:1).
    Snapping: magnetic, priority vertex > edge > face, release distance > capture (no flicker), radii in screen px (zooming in = finer
    reach); Alt / magnet toggle = snap off. Axis inference: near vertical/bed axis the line locks + axis colour; Shift = 15 deg steps;
    touch = toggle (2nd finger reserved for camera). Lock = measure along the direction (B never stuck); "force B onto the line" maybe later.
  - Camera (user's own vision, keep): live camera during the drag, camera as the loupe. Viable form = steered point: finger steers the
    point on the surface, camera follows it, drag speed sets zoom (slow = in, fast = out); zoom-in slower than zoom-out so a wide slide
    aborts; 2nd finger = freeze/zoom out; framing anywhere, double-tap framing. Risk: feedback loop / seasick if the finger IS the point.
    Prototype behind a toggle next to the plain loupe, compare on phone; 2-3 non-converging tuning rounds = rethink.
  - Mask as explanation: live red preview of what an angle means: support overhang angle (global, per-face normal in shader, cheap) and
    smart-fill crease angle from a seed (E1); brush-size circle on the surface. User rarely paints, values it for understanding regions.
  - Cut: user likes Bambu's gizmo + two-colour halves; wants x-ray of the model while placing; current slider can't be dragged in the view.
    Ideas: drag in view moves the plane (E5), tap a feature to cut there (E3), long-press face = align plane. User rarely cuts (low priority).
  - Open: per-region edits (colour/material/smoothing) via E1+E5; which engine to start with (feature highlight for Measure looks first).
## Known limits (no action)
- Real keyboard Ctrl+1 = system hotkey. Inactive-plate double tap re-frames (user: fine). Badges ~23 dp at plate zoom (told the user).
- HMS texts: HmsCatalog.kt fetches at runtime; Handy's copies stay local. Flatpak r9 MakerLab, upstream bump, obn follow-ups: archive.
- Paint guard tool (guard plane) misbehaves, user can't describe yet (user report 2026-10-25, r401). Open: needs repro from user.
- Edge bands on painted rounded edges + realistic ignoring paint: fixed r403 (Claude phone debug OK 2026-10-25); user check open.
- Upstream 2.5.0 alpha overlaps our work: NOTES-upstream-2.5.md (compare table); check src-orca before building any feature, keep ours where better (user 2026-10-25). Open: side-by-side checks -> Sonnet 5.5 (medium); CAD/OCCT decision -> Opus.
- DECISION (user, asked 2026-10-25): push/pull -> fold into resize as face drag (drop chip) or remove; and whether to plan the CAD roadmap below. Claude's rec: fold + roadmap in order.
- CAD roadmap (Claude rec 2026-10-25, not started, needs user go): 1 hull/blend of selected shapes in the builder (small, scad hull exists), 2 snap-to-surface placement (small-med), 3 [Opus] deformers bend/twist/taper/lattice (med), 4 [Opus] sweep + loft (med), 5 [Opus] fillets between shapes (med-big; part 1 "Blend joins" Claude-verified WSA r419 2026-10-26, user feel untested), 6 [Opus] sculpt mode grab/smooth/inflate/crease on subdivided mesh, result = plain mesh (big; user's "playdough" wish), 7 asset library (figures, wheels). Skip Blender-style mesh edit mode on touch. Reuse plan 2026-10-25: NOTES-cad-reuse.md (upstream libslic3r/CAD + OCCT already in tree, SLIC3R_CAD off; phase 0 = [Opus] OCCT spike). User accepted 2026-10-25; steps tagged model/effort; step 0 next.
- Truck demo people visibility: check through the windows with the builder closed (WSA plate 6, r416); user or Claude.
- 3MF writes effective bed type per plate + project (plate 4 / printer "unknown"), label "Cool Plate (SuperTack)": built, untested (moved from handoff 2026-10-26).
- Measure step 1 (458e888; r416+): snap checked by Claude on WSA r429 2026-10-26 (archive); open = user feel (mouse/finger/pen), 2nd point + distance on a model edge not read yet.
- CAD user feel open: 2a-2c sketch Twist / Rise per turn / Loft / Sweep (r421), 3 Bend / twist / taper (More menu, r423); Claude-verified WSA 2026-10-26. Smooth blend (More, r429: stall/cancel/size/inside-out fixed, Claude WSA 2026-10-26): phone run Claude-verified r429; user feel untested.
