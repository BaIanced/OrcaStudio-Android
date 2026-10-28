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

## Untested (needs the user or a special setup)
- Preview: shading after slice? + inner z-fight on layer scrub (fixed r285, Claude 2026-10-17): user look pending.
- [Opus if reopened] Two-finger gestures: pan->zoom fix fa1754c (user r305 "still broken"; r311 "pans feel fine" may cover it), pinch zoom-out r312, pinch lock + speeds (r280/r324), Quest stick zoom (r324): user feel pending.
- Corner resize gated behind long-press Resize / Ctrl+T (r305, 72dd138): probably superseded by the scale gizmo (user-verified r335, archive); ask before testing.
- Extended paint tools (upstream set, stroke lock, overhang highlight, hold-to-preview fill, regions view, one-tap actions, fill stops + guard plane; Claude WSA 2026-10-20/21): user feel untested. Unpicked survey ideas: NOTES-paint-survey.md.
- Paint panel layout C: modes verified by user r453 2026-10-28. Open: Clean specks with lock (untested); rename stroke-axis "Lock ↕/↔" chips?
- Paint depth (r367, Claude WSA G-code OK 2026-10-21): user feel + real print untested.
- Paint shop reopen fix (Claude WSA r374) + plate estimate line (Claude WSA r376 = Preview; one density, part-level filaments ignored): user look/feel.
- Check mesh: marker readability, self-intersection count on a real file, thin walls (Claude WSA test STL, run 39657856306).
- Step 9 tap/long-press align/x-ray/upright shift Claude-verified WSA debug 2026-10-27 (archive), user feel untested. Awaiting go: radio Lay on cut face / Flip / Leave + nested "Arrange after cut" (arrange_plate always runs, OrcaScene.cpp:1046); user asked the point of "Flip upper part upside down".
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
- [Opus] Sculpt mode (More > Sculpt, r428; Claude-verified WSA debug 2026-10-26, user feel untested). Open: symmetry, ~1 s first stroke at 200k faces, paint overlay only at stroke end.
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
- DECISION open: push/pull -> fold into resize as face drag (drop chip) or remove. Claude rec: fold.
- CAD roadmap (user accepted 2026-10-25; NOTES-cad-reuse.md): steps 0-6 built and Claude-verified on WSA (Hull r445, XY edge snap r447, Blend joins r419, sweep/loft r421, bend/twist/taper r423, sculpt r428), user feel untested; 7 asset library (figures, wheels) not started.
- Truck demo (user 2026-10-28): figures visible only in Realistic + transparent filaments (slice shows them). Open: user's explanation = hole (anti-solid) shapes are drawn in their own opaque colour and cover the figures: make holes translucent by default (unverified), Realistic-by-default idea (GPU cost on phone/Chromebook untested). Session gone from WSA; rebuild with truck.sh + truck2.sh.
- Measure steps 1-6 + steer/tap-select/loupe/Tuning (Claude-verified WSA debug 2026-10-26/27); open = user feel on the phone (steer vs plain loupe, 2nd-finger freeze, axis lock, mouse hover, S Pen on the Note); Alt+click, ring-to-ring, hole bottom rim untested.
- CAD user feel open: sketch Twist / Rise per turn / Loft / Sweep (r421), Bend / twist / taper (r423), Smooth blend (r429); all Claude-verified WSA/phone 2026-10-26.
- Dimensions overview idea (user asked 2026-10-27; leaning only): per-object list of bbox, hole diameters/depths, thinnest wall; tap entry places measure points. CAD tools auto-dimension from parametric features; meshes need detection. Not started.
- Smart guides (BUILT 005f14d + Center/Align f073445, Claude-verified WSA 2026-10-27, user feel open). Open idea: plate + object centres in the shape-builder edge snap.

- MCP token efficiency (Opus review + analysis done 2026-10-27, `NOTES-mcp-token-efficiency.md`): proposed build rule waits for the user's OK; plan A-C (diff replies) r452 Claude-verified WSA 2026-10-28; D dropped.
- Hover tooltips: Build toolbar USER VERIFIED 2026-10-28 (debug 26be567+). Open: other Build panel icons / screens with bare IconButtons (~86 in app) still lack Tip(); Claude to sweep.
- Mirror button (user 2026-10-28, session B, r453): 'doesn't work, no update'. Claude: design had mirror [1,1,1] on a BOX (symmetric, flip in place is invisible); flag path reads fine (ShapeController.mirror -> placement matrix -> set_volumes). Added check marks to the mirror menu (uncommitted, compiles). Open: user retest on an asymmetric shape (wedge/L) after next build; if still no change, reproduce on WSA debug.
- [Opus] Build continuity (user brain dump 2026-10-28, session B, r453; exploration, nothing locked): (1) 'Add as new' looks like the object moved because Build shows only the design object (solo) until exit; user wants all objects shown (10 adds = 10 visible). Leaning: show the others dimmed/ghosted, not hidden. (2) Mesh object in Prepare reaches the shape builder only via an 'open SCAD model'-type entry; unclear. (3) Idea: tiny yellow 'CAD' badge (black text) on every entry leading to the builder, or a CAD | Mesh submenu; user: too many paths to the builder. Leaning: one entry point + badge; mockup first, keep user's vision as a toggle. USER AGREED with the leanings 2026-10-28 (still design-open: mockup first).
- Shape builder W/D/H scrub + lock (user 2026-10-28, session B; design agreed in chat, build after the tooltip sweep): drag a number left/right = whole units, finger moving up while scrubbing = 0.1 fine steps, tap = edit; small lock badge inside each cell toggles ONE shared proportional lock; locked: other two cells grey out as followers, update live, decimals allowed; slider mode shows one proportional slider. Open: badge clutter (fallback one lock outside the cells), live speed on groups/SCAD shapes (throttle).
- [Opus] Shape builder bugs/feedback (user 2026-10-28, session B, WSA r453 debug, all untriaged): (1) dragging an object: gizmo stays behind until release; (2) gizmo pops up by itself / too touch-sensitive: only handles on faces visible to the camera, back-facing handles hidden + untouchable (user: ask if unclear); (3) mirror/repeat: dragging an object that is half off the plate back onto it is captured as the "spacing between repeated objects" drag.
- Snapping (user 2026-10-28): make it a toggle/hotkey (Photoshop-style), not forced; not necessarily on the main plate view; alignment guide line is far too big/bulky: restyle thin Adobe-style (Figma/Blender as references). Smart guides line above is the same feature.
- Slice cancel badge (user 2026-10-28, WSA r453 debug): cancelling a slice raises the red error badge, but cancel is not an error; X must close + remove the badge (no re-slice / New project needed). Current fix (New project clears, long-press dismisses) is not enough; also don't raise it for user cancel.
- Session B checks skipped by user 2026-10-28 ("don't want to do right now"): Sculpt, Hollow, Center/Align, Check mesh, badge fix, mirror asymmetric feel.
