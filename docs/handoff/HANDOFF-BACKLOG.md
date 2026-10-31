# Backlog (pending / untested / ideas). Grep, do not read up front.
Rules: ONE line per item: what is still open + who last checked it, on which build, when. Causes, commits, runs and history go
to HANDOFF-ARCHIVE.md (grep it; full pre-trim text under "2026-10-23 Backlog detail"). Never delete an open item; when it is
verified, move it to the archive (a "USER VERIFIED" tag is not a move). User 2026-10-15: do NOT bring up the X2D again.
Model tags: [Opus] = hard native/math/concurrency or big design; untagged = Sonnet is fine. Re-judge an item after 2 failed tries.

## Plan
- Plan (user 2026-10-14): camera LAN (done) -> off-LAN camera (cloud P2P; VPN-home fallback) -> print history (Handy's call, own client, Device tab). Own printer/account/code only; rule 7 holds.
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
- Plate switcher pick (lab stays; don't ask again). Launcher icon colour ("decide later").
- [Opus] Sculpt mode (More > Sculpt, r428+; Claude-verified WSA 2026-10-26..28, archive; user feel untested). Symmetry 51ad144 untested on device by user. Remaining items (paint overlay mid-stroke, bump positions, grab on plane) parked as won't-do 2026-10-30: low value, native + CI only.
- [Opus] Build workspace (user GO 2026-10-23): all steps incl. multi-copy (r468) and repeated/nested group gizmos (r469) Claude-verified WSA (archive 2026-10-29); turn ring + body drag in a repeated group Claude-verified WSA 2026-10-29 (archive); open: user feel.

## Ideas (not started)
- [Opus] AI print failure watch, user "FOR SURE wants" (2026-10-30), condition: low compute per print. user's Wi-Fi camera on a printed stand (model: ask the user), RTSP via its camera account. Plan: Obico's open-source detector run locally (no LLM tokens), pause printer + notify on spaghetti; LLM only as optional 2nd opinion on a flagged frame. Claude vision at 1 frame/min ~80k tok/h = too costly as the main loop.
- Study Bambu Handy's MakerWorld integration.
- Headless display gaps (Reference "Headless display"): pinch on the virtual display, `start` stress test.
- Quest: 2D panel confirmed (user 2026-10-21); open only input beyond touch + native VR view [Opus] (on request). Phone <-> Quest link (undefined).
- Orca plugin runtime kept generic: not done: audit prompts, create_window, per-preset overrides, printerAgent. FilamentHub dropped for good.
- Public release check: built-in adblock + hard-coded Models tabs vs store policies / site terms (not researched).
- Colour icon set shortlist + mockup (low). Paint card fold direction by screen position (low). "Need phone" prompt (shelved).
- [Opus] Filament materials tiers 1+2, per-slot look, Realistic render 1+2 (Claude WSA debug 2026-10-24): user feel untested (layer line contrast in realistic mode); variable layer height ignored; open: Quest/Chromebook GPU check. Google Filament engine not wanted (breaks Shaders.kt drags, ids, uSolo).
- Preview camera-light slider + Check mesh sharp-edge markers + Hollow: Claude-verified WSA (archive); open: user feel (sessions B/C in NOTES-merge-batch.md). Cleanup = More > Repair (exists).
- [Opus] Snapseed interaction plan (NOTES-interaction-plan.md): steps 1-9 Claude-verified WSA (r437/r438, 2026-10-27); user feel untested on all.
- S Pen pressure (user 2026-10-31): BUILT ab552fa (CI run 41185835878), compiles, untested on a pen. Stylus pointer only: Sculpt strength x p (floor 5 %), round paint brush radius x (0.15+0.85p), layer-height graph tap step 0.02 x p (floor 10 %); More > Pen on/off + Soft/Linear/Firm. Not scaled: fill/height shapes, seam/support non-round shapes, brush ring cursor. Open: feel test on the Note 10+ (S Pen; user says when awake); Quest pen has no pressure.
- UX review via Gemini (user 2026-10-31): bundle `~/orca-android-tools/exports/ux-review/` = `PASTE-ME-fresh.txt` (standalone prompt) + both PDFs + `audit-ui-source.txt`. Round 2 checked (archive 2026-10-31). Fresh run checked 2026-10-31 (Claude): 3.8 Flash High (AI Studio) best; base = Flash answer minus its 2 false items (View menu checkmarks exist, NumberField has isError), plus Pro's ScrollCue 28x18, hardcoded Notices red, Objects tab width jump, silent sizeOk. User decides which to build. Pre-audit draft (View menu grouped, More one-line explainers) is in `git stash` "ux: view menu grouping...", unbuilt; drop or apply after the user picks.
## Known limits (no action)
- Real keyboard Ctrl+1 = system hotkey. Inactive-plate double tap re-frames (user: fine). Badges ~23 dp at plate zoom (told the user).
- HMS texts: HmsCatalog.kt fetches at runtime; Handy's copies stay local. Flatpak r9 MakerLab, upstream bump, obn follow-ups: archive.
- Paint guard tool (guard plane) misbehaves, user can't describe yet (user report 2026-10-25, r401). Open: needs repro from user.
- Upstream 2.5.0 alpha overlap: NOTES-upstream-2.5.md (source checks done 2026-10-25); keep ours where better. All compare rows closed at source level (2026-10-28); nothing open.
- CAD roadmap (user accepted 2026-10-25; NOTES-cad-reuse.md): steps 0-6 built and Claude-verified on WSA (Hull r445, XY edge snap r447, Blend joins r419, sweep/loft r421, bend/twist/taper r423, sculpt r428), user feel untested; 7 asset library 7a-7d Claude-verified WSA VD (archive 2026-10-29); 7c built-ins (Wheel, Figure, Peg and hole, Hinge, Gear) done.
- Truck holes cut preview: Claude WSA-verified on the debug build 2026-10-29 (chef visible through the serving window). Sculpt on a cut part OK (r464, Claude 2026-10-29). Hole ghost alpha 0.25 done (r470, Claude WSA VD 2026-10-29).
- Measure steps 1-6 + steer/tap-select/loupe/Tuning (Claude-verified WSA debug 2026-10-26/27); open = user feel on the phone (steer vs plain loupe, 2nd-finger freeze, axis lock, mouse hover, S Pen on the Note); Alt+click, ring-to-ring, hole bottom rim untested.
- CAD user feel open: sketch Twist / Rise per turn / Loft / Sweep (r421), Bend / twist / taper (r423), Smooth blend (r429); all Claude-verified WSA/phone 2026-10-26.
- Smart guides (BUILT 005f14d + Center/Align f073445, Claude-verified WSA 2026-10-27, user feel open). Build middles Claude-verified WSA VD 2026-10-29 (archive); plate centre in Build skipped (design pose not tied to plate).

- MCP token efficiency (Opus review + analysis done 2026-10-27, `NOTES-mcp-token-efficiency.md`): proposed build rule waits for the user's OK; plan A-C (diff replies) r452 Claude-verified WSA 2026-10-28; D dropped.
- Hover tooltips: all 81 icon buttons with a content description now wrapped in Tip() (Claude 2026-10-28, compiles, WSA debug launch OK; hover/long-press not adb-testable); 3 colour-dot buttons have no label. User feel untested.
- Shape builder W/D/H scrub + proportional lock: Claude-verified WSA debug + in CI r458+ (archive 2026-10-28); open: user feel (on hold); proportional slider-mode unbuilt (spec unclear). Claude 2026-10-28.
- Shape builder bugs (user 2026-10-28, session B): drag lag, rings through the object, end copy drag scaling spacing: fixed 1a94899, Claude-verified WSA debug 2026-10-28 (archive); open: user feel (on hold, no user testing now).
- Snapping (user 2026-10-28: toggle/hotkey, not forced on the plate, thin Adobe-style guide): 846a663 Claude-verified WSA 2026-10-28; user feel untested.
- Session B checks skipped by user 2026-10-28 ("don't want to do right now"): Sculpt, Hollow, Center/Align, Check mesh, badge fix.
- Godot review leftovers (Z lift dot, axis lock, Wireframe, orbit inertia, Light direction: all in CI r463, Claude-verified WSA 2026-10-28, archive): open: user feel; tower ignores Z code-verified 2026-10-29. [Opus]
- CAD 7d done (size slider WSA VD-verified dd8f656, archive 2026-10-29); only the group Scale % field is unit-tested, not seen on screen (Claude, 2026-10-30).
- [Opus] OrcaMesh edit mode: M1-M4 (move/scale/rotate, extrude+inset, cut ring, fillet/chamfer) Claude-verified on WSA VD, M4 also on release r487 (2026-10-30); no M5 planned; user feel untested (NOTES-orcamesh-edit.md "Progress").
- Self-portrait 3 gaps (Claude, r489 WSA, 2026-10-31; untested fixes): MCP tools added 2026-10-31, Claude-verified on WSA debug build (slots 4->2, prime_tower move/reset, preview_set layers/scheme/moves + errors): filament_slots, prime_tower (plate, x, y | reset), preview_set (layer_from, layer_to, scheme, moves). Note: the release app also owns the MCP socket, so force-stop it before testing the debug app. Bugs: design_to_plate triangle count: whole design counted from STL headers, Claude-checked on WSA debug 2026-10-31 (count shows only when a piece is freshly rendered; design_set pre-renders so reply is "Placed on the plate", by design); slice result lost after view_set + Preview tab (NOT reproducible r489 WSA 2026-10-31: slice, view_set x2, Preview tab tap, result kept; likely a filament-slot/preset edit or a scene op between, they clear results by design; drop unless it recurs) ("not sliced yet", slice_result empty); 16-filament tower "no free spot" for a 172 mm object (physical limit: the tower box is 165x641 px on screen, ~70% fits; not a bug, maybe offer a clearer message or a tower-size hint); design_set sketch without size: fits the drawing, Claude-verified WSA debug 2026-10-31 (30x26); primitive groups: top-level solids are separate OpenSCAD statements (implicit union, ShapeModel.kt group()); Lattice 52 shells = pieces mode (one part per piece, colours) so by design; 24 open edges unexplained (probably rod ends), only matters for a real print.
- AI print failure watch feasibility note: parked behind the UX audit (needs the user's camera model).
- [Opus] User asks 2026-10-31 + HD plate slider/tower text: all coded, compile + unit tests pass (Claude 2026-10-31, pushed, r492 installed). Danger dialog + Cancel Claude-verified on WSA VD 2026-10-31. Open: WSA VD test of notice Fix links (tower/spiral/organic -> editor searched for key; mixed temps -> More; printer -> Device), danger dialog (nozzle >300, bed >110, printer geometry, G-code; I understand + mute per project via AppSettings.dangerMutes), coloured headings, HD plate slider, tower text.
