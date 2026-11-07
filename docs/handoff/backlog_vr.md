# VR backlog (the only live backlog for the VR port)

One or two lines per item: state, next step, the note section to read, who last checked it. Evidence goes in the note, not here. Finished items move to `backlog_vr-done.md`; never delete them. `[Opus]` = hard item. "Build none" = nothing built or run yet, so untested. Keep under about 4 KB.

## Open
- M2 enter, leave, passthrough: IN PROGRESS on `claude/vr-m2` (bba25dc). Passthrough, round trip and the `session.3mf` hash (SHARED and COLD) passed. Left: "Leave VR" button on the busy dialog, untested on a device. r540 (run 42344291630, passed) is installed over `.quest`, stopped. SHARED (via `am start`) and COLD repeated on r540, no regressions. The panel can't be read over adb, so the "View in VR" tap is not retested. Next: ask the wearer, Quest cool first: View in VR, Meta button, any app in multitask mode, tap Leave VR, does the panel take touches? Headset notes, "M2 on the headset". | Claude, CI r540 on Quest 3, 2026-11-06
- Frame rate setting: open, user asked. Lists every rate the device can do, no hard cap. Only the log is built (r540). `VR_REFRESH` read: 72 to 207 listed. It says current 72 while `VR_FPS` reads about 118, not traced. Next: trace that, then build the setting (`xrRequestDisplayRefreshRateFB`). Design notes, "Frame rate setting". | Claude, CI r540, 2026-11-06
- Thermal: Quest shut down from heat 2026-11-06 after a long unattended 120 Hz run. Rule: no long VR runs, stop VR after each run, check temperature first. Last read 46.0 C, status 1 at 23:57 after two short runs; wait for status 0, about 41 C. Headset notes, "Thermal". | Claude, r540, 2026-11-06
- Passthrough off switch: open, user reports heat (not measured). Offered a `vr_tuning.json` toggle, not answered. Headset notes, "Thermal". | Claude, r539, 2026-11-06
- VR plate placement and zoom, one controller: first real use passed, user placed the plate (r534). Open: tuning only if they ask; whether working scale stays a manual zoom; diagonal drift on the stick, not confirmed. Design notes, "Plate placement". | Claude and user, CI r534, 2026-11-06
- VR info panel: Claude's checks passed. Needs the user: judge size and readability, try placement from its instructions. Design notes, "Info panel". | Claude, CI r533, 2026-11-05
- VR-first and panels in 3D: open, user's concept, Claude's plan awaits their OK. First steps: 16:9 panel size, then launch into VR. Design notes, "VR-first concept". | Claude, build none, 2026-11-06
- VR dev panel with sliders: open, needs M3's VR panel; `vr_tuning.json` until then. Design notes, "Info panel". | Claude, build none, 2026-11-05
- Second-controller extras: open, proposal not agreed. Design notes, "Plate placement". | Claude, build none, 2026-11-05
- Plate placement by hand tracking: open, the user's real target. Muse Task 20 (PR #78) got changes on #46, not merged; Task 21 waits on it. Design notes, "Hand tracking". | Claude, build none, 2026-11-06
- OVR Metrics CSV: open, low priority; `VrApi` logcat by pid works instead. Headset notes, "Meta tools". | Claude, CI r528, 2026-11-05
- M3 Compose panel and ray selection [Opus]: open, blocked by M2 except pure-Kotlin steps 1-2. `NOTES-vr-m3.md`; ends with a 2-minute user check. | Claude, build none, 2026-11-05

## Status
r540 on the Quest's `.quest`, stopped. Quest is on adb over TCP and is the only device this work may drive (user); find its `transport_id` with `adb devices -l`. Worktree for `claude/vr-m2`: `.claude/worktrees/agent-a24c55a63c71affd1`. Other branches: design notes, "Branch state".
