# Backlog (pending / untested / ideas). Grep, do not read up front.
Rules: ONE line per item: what is still open + who last checked it, on which build, when. Causes, commits, runs and history go
to HANDOFF-ARCHIVE.md (grep it; full pre-trim text under "2026-10-23 Backlog detail"). Never delete an open item; when it is
verified, move it to the archive (a "USER VERIFIED" tag is not a move). User 2026-10-15: do NOT bring up the X2D again.
Model tags: [Opus] = hard native/math/concurrency or big design; untagged = Sonnet is fine. Re-judge an item after 2 failed tries.

## Plan
- Plan (user 2026-10-14): camera LAN (done) -> off-LAN camera (cloud P2P; VPN-home fallback; not started). Print history done as G6. Own printer/account/code only; rule 7 holds.
- Device tab printer controls (built r286): needs printer + user: extrude/jog/home motion, part fan reads 0 % after a set, target-0 tile stale, speed chip mid-print (Claude 2026-10-21).

## Open bugs
- Mouse right-click (user 2026-10-28): `lateButton` fallback in r463+ (WSA no-regression only). Open: which device the user used; check there when they say.
- Paint shop friction (user 2026-10-28): fixed in r463+ (archive). Sync swatch: Device-tab Sync button verified with a connected printer on r519 (archive 2026-11-04); the ToolPanel swatch itself still untested.
- [Opus] Muse Task 12 JNI audit: 8 needs a printer connect, 5 not exercised, 6 needs 2+ printers (Claude, r392-r395, 2026-10-24); rest fixed or no change.

## Untested (needs the user or a special setup)
- Preview: shading after slice? + inner z-fight on layer scrub (fixed r285, Claude 2026-10-17): user look pending.
- [Opus if reopened] Two-finger gestures: pan->zoom fix fa1754c (user r305 "still broken"; r311 "pans feel fine" may cover it), pinch zoom-out r312, pinch lock + speeds (r280/r324), Quest stick zoom (r324): user feel pending.
- Corner resize gated behind long-press Resize / Ctrl+T (r305, 72dd138): probably superseded by the scale gizmo (user-verified r335, archive); ask before testing.
- Extended paint tools (Claude WSA 2026-10-20/21, archive): user feel untested. Unpicked ideas: NOTES-paint-survey.md.
- Paint panel layout C: modes verified by user r453 2026-10-28. Clean specks with lock Claude-verified WSA r456 2026-10-28 (archive). Chips renamed "Straight ↕/↔" (strings only, shipped; not looked at on screen).
- Paint depth (r367, Claude WSA G-code OK 2026-10-21): user feel + real print untested.
- Paint shop reopen fix (Claude WSA r374) + plate estimate line (Claude WSA r376 = Preview; one density, part-level filaments ignored): user look/feel.
- Check mesh: counts OK on real files (Claude, phone r507, 2026-11-03, archive); open: marker readability in 3D (user feel, skipped).
- Fan slider hold-until-within-4 % (r295) + Extrude F150 e13bebb: need the printer.
- Preview bead look (Chromebook), part shadows r266, light/shadow lab sliders r263: Claude-verified, user hasn't seen.
- Drag-handle fling: wide PanelEdge (WSA mouse inconclusive, r300) and OpenSCAD grips untested; portrait flick user-verified r294.
- Stylus 7d3a263 (palm rejection, pen buttons/eraser): untested on a pen; super low priority (user 2026-10-21). Pie menu LOW (user dislikes it).
- Nozzle match: newer-firmware `device.nozzle.info`, stale status after a printer-model switch, spool swap drops the pick.
- Model-mismatch guard 54b4ca6 (one printer); SHIELD sign-ins (r210); element blocker in plugin/Device WebViews; true EGL context loss; "failed OpenSCAD run clears the old log": no repro / no setup.
- View cube: phone portrait layout untested; off by default (user r352: motion sick); revisit only on request.

## Waiting on the user
- **Rename + new repo (user 2026-11-04, NOT locked, LOW):** user leans OrcaMesh; Discussions #17269 0 replies (Claude 2026-11-04). Open: name, history vs snapshot, app ID. Full detail + to-do list: archive "2026-11-04 Rename backlog detail".
- HD plate ideas (user verified r394): 3D bed frame model, dual-nozzle decal layout (Claude 2026-10-24).
- Plate switcher pick (lab stays; don't ask again). Launcher icon colour ("decide later").
- [Opus] Sculpt mode Claude-verified WSA 2026-10-28 (archive); user feel + symmetry 51ad144 untested; leftovers parked won't-do 2026-10-30.
- [Opus] Build workspace: all steps Claude-verified WSA 2026-10-29 (archive); open: user feel.

## GOAT batch (G1-G7 built, Claude-verified, merged PR #71 2026-11-03; G8 skipped; specs in archive)
- G1 coach strip, G3 shortcuts, G4 auto-slice delta text (11 sp, only small when the lab scales the FAB down): open: user look/feel only.
- G6 print history: open: card for web-UI hosts, thumbnail check on a Bambu send, end-of-print status (needs the printer).
- G7 widget + Live Update chip: open: real status test (needs the printer; mocked status checked).

## Ideas (not started)
- Parked user-feel / release items (cut-step 9 go-ahead, public-release decisions, shape builder feel, hover tooltips): moved verbatim to HANDOFF-ARCHIVE.md heading "Parked backlog items 2026-11-04".
- Self-portrait 3 (Claude r489 WSA 2026-10-31): open only real-print checks (24 open lattice edges). Tower "no free spot" message now names the filament count + scale hint (OrcaEngine.cpp, 2026-11-04, uncommitted, C++ not compiled locally, rides the next CI build).
- Study Bambu Handy's MakerWorld integration.
- Headless display: `start` stress OK 5/5 (Claude 2026-11-04); pinch on the VD parked (needs an app_process multi-pointer injector; low value, archive).
- Quest: 2D panel confirmed (user 2026-10-21); open only input beyond touch + native VR view [Opus] (on request). Phone <-> Quest link (undefined).
- Orca plugin runtime kept generic: not done: audit prompts, create_window, per-preset overrides, printerAgent. FilamentHub dropped for good.
- Colour icon set shortlist + mockup (low). Paint card fold direction by screen position (low). "Need phone" prompt (shelved).
- [Opus] Filament materials / per-slot look / Realistic render (Claude WSA 2026-10-24): user feel untested; variable layer height ignored; Quest/Chromebook GPU check open.
- Preview camera-light slider + Check mesh sharp-edge markers + Hollow: Claude-verified WSA (archive); open: user feel (sessions B/C in NOTES-merge-batch.md). Cleanup = More > Repair (exists).
- [Opus] Snapseed interaction plan (NOTES-interaction-plan.md): steps 1-9 Claude-verified WSA (r437/r438, 2026-10-27); user feel untested on all.
- UX review via Gemini (user 2026-10-31): lowest priority, act only if the user brings Gemini's answer (bundle exports/ux-review/, archive 2026-10-31).
- S Pen pressure (user 2026-10-31): built ab552fa, UI checked on WSA VD; open: feel test on the Note 10+ (user says when awake); Quest pen has no pressure.
- [Opus] Assemble space / big plate (user 2026-11-04, exploration): A1 render OK on WSA VD (Claude 2026-11-09), toggle/exit unchecked, multi-part entry not coded; A2-A4 open (archive "Assemble space backlog detail 2026-11-09"). Claude 2026-11-04.

- Chromebook-ready for a future Play listing (user 2026-11-06; nothing to do now): research per-device rules when a listing gets close (archive "Assemble space backlog detail 2026-11-09").

## Known limits (no action)
- Real keyboard Ctrl+1 = system hotkey. Inactive-plate double tap re-frames (user: fine). Badges ~23 dp at plate zoom (told the user).
- HMS texts: HmsCatalog.kt fetches at runtime; Handy's copies stay local. Flatpak r9 MakerLab, upstream bump, obn follow-ups: archive.
- Paint guard tool (guard plane) misbehaves, user can't describe yet (user report 2026-10-25, r401). Open: needs repro from user.
- CAD roadmap steps 0-7 (NOTES-cad-reuse.md) Claude-verified WSA (archive 2026-10-29); user feel untested.
- Truck holes cut preview, sculpt on cut part, hole ghost alpha: Claude-verified WSA 2026-10-29 (archive); open: user feel.
- Measure steps 1-6 + steer/loupe/Tuning: Claude-verified WSA 2026-10-26/27; open: user feel on the phone (steer vs loupe, 2nd-finger freeze, axis lock, hover, S Pen); Alt+click, ring-to-ring, hole rim untested.
- CAD user feel open: sketch Twist / Rise per turn / Loft / Sweep (r421), Bend / twist / taper (r423), Smooth blend (r429); all Claude-verified WSA/phone 2026-10-26.
- Smart guides (BUILT 005f14d + Center/Align f073445, Claude-verified WSA 2026-10-27, user feel open). Build middles Claude-verified WSA VD 2026-10-29 (archive); plate centre in Build skipped (design pose not tied to plate).

- MCP token efficiency (Opus review + analysis done 2026-10-27, `NOTES-mcp-token-efficiency.md`): proposed build rule waits for the user's OK; plan A-C (diff replies) r452 Claude-verified WSA 2026-10-28; D dropped.
- Shape builder W/D/H scrub + proportional lock: Claude-verified WSA debug + in CI r458+ (archive 2026-10-28); open: user feel (on hold); proportional slider-mode unbuilt (spec unclear). Claude 2026-10-28.
- Snapping (user 2026-10-28: toggle/hotkey, not forced on the plate, thin Adobe-style guide): 846a663 Claude-verified WSA 2026-10-28; user feel untested.
- Session B checks skipped by user 2026-10-28 ("don't want to do right now"): Sculpt, Hollow, Center/Align, Check mesh, badge fix.
- Godot review leftovers (Z lift dot, axis lock, Wireframe, orbit inertia, Light direction: all in CI r463, Claude-verified WSA 2026-10-28, archive): open: user feel; tower ignores Z code-verified 2026-10-29. [Opus]
- CAD 7d done (size slider WSA VD-verified dd8f656, archive 2026-10-29); only the group Scale % field is unit-tested, not seen on screen (Claude, 2026-10-30).
- [Opus] OrcaMesh edit mode M1-M4: Claude-verified WSA VD + r487 2026-10-30; open: user feel (NOTES-orcamesh-edit.md "Progress").

- Bulk filament edit (53578ad): long press verified by Claude on phone VD 2026-10-31 (archive); only Shift/Ctrl+click (mouse, Chromebook) and user feel untested. In r496+.
- [Opus] AI print failure watch (user 2026-10-30): local Obico-style camera detector, pause + notify; open: ask the user for the camera model (archive 2026-10-31).
- [Opus] Filament mixer redesign (0c791b3, CI 41371910606): 2- and 3-part looks checked by Claude on WSA VD 2026-11-01; user has not seen it. Vendor logo dropped (trademark).
- [idea] mobilecad (GPL-3 FreeCAD-on-Android) takeaways: XR controller-ray-as-camera + context panel (send to VR-port agent), occlusion-aware mm-tolerance picking, Numpad; archive 2026-11-06 mobilecad review. Claude 2026-11-06, nothing taken yet.
- [Opus] claude-kit `/start` (aliases `/seed`, `/bootstrap`): plan in `~/claude-kit/docs/handoff/HANDOFF.md`; skeleton only. Run `/next` from that dir.
- Preview: brim/bed contact z-fight from below: fixed (polygon offset on the under-plate window), Claude-verified phone A/B 2026-11-08 (archive "brim/bed z-fight from below fixed"); user live-drag look pending (not on the CI/release build yet).
- VD keyboard/tap fix (orca-vd.sh, archive end): awaits the user's verdict (keyboard no longer vanishes?); Claude, 2026-11-07.
- Upstream-PR idea (patches 0002-0004 vs 2.5.0 alpha): no go yet; Claude, 2026-11-07.
