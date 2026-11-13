# Backlog (open work only). Grep, do not read up front.
Rules: ONE line per item: what is open + who last checked, build, when. No commits or run IDs; history goes to HANDOFF-ARCHIVE.md
(old lines: heading "2026-11-12 Backlog triage T-013"). Verified items move to the archive. User feel items live in SIGNOFF-CHECKLIST.md.
Tags: [Opus] = hard native/math/design; untagged = Sonnet is fine. User 2026-10-15: do NOT bring up the X2D again.

## Workable now
- Paint layout C "Straight" chips: one layout check on the VD (strings only, shipped).
- Failed OpenSCAD run should clear the old log: force a failing run on the VD.
- Tower "no free spot" message: committed (OrcaEngine.cpp), needs one device check.
- Pinch injection on the VD (multi-pointer injector) for automated gesture checks.
- Assemble "Update on plate": check on the VD (blocked while the VD is broken).
- Measure Alt+click, ring-to-ring, hole rim: VD checks.
- Bulk filament Shift/Ctrl+click (mouse) VD check; group Scale % field seen only in unit tests.
- Muse readers (meshopt, 3DS, DAE, FBX) and quantized glTF: wire in only what Muse delivered.
- [Opus] Rebase patch 0001-paint-depth-layers onto current upstream (fails at MultiMaterialSegmentation.cpp:1254, T-003), then retry 0002-0005.
- [Opus/native] Cancel ignored after ~7 s of a huge slice: write_preview (OrcaEngine.cpp) lacks an m_interrupt check (T-005).
- [Opus] 59 UNCONFIRMED rows in THIRD_PARTY_NOTICES.md (T-010): confirm against dependency licence files; android-v tag link dead on local builds.
- "The last session could not be opened. It was kept." bar seen once after force-stops (T-018): check whether force-stop mid-save leaves a bad session file.

## Core, waits on the UI direction
- [Opus] Sculpt + symmetry: the gate feature (SIGNOFF S-05).
- [Opus] Build workspace (SIGNOFF S-01).
- [Opus] Snapseed interaction plan steps 1-9 (NOTES-interaction-plan.md; SIGNOFF S-07, S-10).
- [Opus] OrcaMesh edit M1-M4 (NOTES-orcamesh-edit.md; SIGNOFF S-17).
- [Opus] Assemble A3 cut suggestion (designed, uncoded) and A4 connectors: hold until the CAD scope audit.

## Needs the printer
- One line: see SIGNOFF S-15 (Device tab controls, paint depth print, fan hold, Extrude F150, G6/G7 checks, Sync swatch, 3MF in desktop Orca).

## Needs a user decision
- Rename + new repo (NOT locked, LOW): user leans OrcaMesh; Discussions #17269 0 replies (2026-11-04). Open: name, history vs snapshot, app ID, launcher icon colour. Detail: archive "2026-11-04 Rename backlog detail".
- Mouse right-click fallback (r463+): which device showed the problem? Drop if no answer.
- Paint guard plane misbehaves, no repro (user 2026-10-25): hide the tool if no repro by the next sign-off.
- MCP token-efficiency build rule (NOTES-mcp-token-efficiency.md): proposed 2026-10-27, not adopted.
- Upstream PR for patches 0002-0004: no go yet.
- AppBackup.kt PREFS = app_settings only (T-007): should export include secure_settings?
- Cut list of 2026-10-28 NOT approved (waits on the lighting review): corner resize gate, pie menu / stylus, view cube, PanelEdge/OpenSCAD grip fling, mismatch guard, SHIELD sign-ins, element blocker, EGL loss, nozzle match, Muse Task 12 JNI leftovers. Features and code stay as they are.
- Cut-tool options "Flip upper part" / "Lay on cut face", and push/pull (fold into resize or remove).
- Ideas parked pending the user (all user ideas): off-LAN camera, AI print-failure watch, HD plate extras, proportional slider mode.

## Needs the pen device
- One line: palm rejection, pen buttons/eraser, S Pen pressure (built, UI checked on the VD); Note 10+ session when the user says.

## Known limits
- Real keyboard Ctrl+1 = system hotkey; inactive-plate double tap re-frames (fine); badges ~23 dp at plate zoom; HMS texts fetched at runtime; Flatpak, upstream bump, obn follow-ups: archive.
- Audit-phase items (no action here): Handy/MakerWorld study, Chromebook Play listing, colour icon set, mobilecad takeaways, Draco decoder (not started), VR/Quest input (VR agent backlog).
- smoke.sh step 8 failed once of 3 on 5bcef51 (first run after a fresh debug install: no MCP reply after the restart, 193 s); watch for a repeat (Claude, 2026-11-12).
- Move docs/handoff to a private repo before T-038 (user answer 4; push allowed before that, user 2026-11-12).
- History holds a LAN address in old commit cf75a81 (HANDOFF.md, now in main via PR #84); rewrite needs a force-push of main: user decides (told 2026-11-12). Also: check PR checks before `gh pr merge`.
- smoke.sh `plate_undo` flaked once in 4 runs on 2026-11-12 (2 objects, want 1; Ctrl+Z loop with 1.5 s sleeps); not seen by the coordinator. Check the step timing if it recurs. Claude, T-022 build.
