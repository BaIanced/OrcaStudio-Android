# VR backlog: finished items

Moved here from `backlog_vr.md`, newest first. Never delete. Not read at startup.

### 2026-11-06
- Chromebook-ready wishlist item: not VR, passed to the main agent in `claudechat.md` section 10, at the user's request. | Claude, no build needed, 2026-11-06
### 2026-11-06
- M1 real plate in stereo: closed, user placed the plate on r534. Bed and cube in stereo, no double vision, colour right, no shimmer; 256,568 triangles held 120 fps unworn (fill barely tested). Never explained: the session drop at 12:34:02 in the first run (STOPPING, then back). Evidence in `NOTES-vr.md`, "M1 on the headset"; old wording in `NOTES-vr-archive.md`. | Claude and user, CI r530 and r534 on Quest 3, 2026-11-06
- Guardian switch: `metavr device boundary --disable` sets `debug.oculus.guardian_pause` to 1 (set 0, status said enabled, disable, prop back to 1, status "disabled (paused)"). `device wake` and `device proximity --disable` worked (status: overridden). Details in `NOTES-vr.md`, "Meta tools". | Claude, Quest 3 r533, 2026-11-06
### 2026-11-06
- Muse Task 19, Meta tools research: done. Second changes request fixed by Muse at 251caf4 (action 4 now says `metavr device` has wake, proximity and boundary commands; the docs only say `boundary --disable` is independent of `set-property`, so whether it equals `debug.oculus.guardian_pause` is unknown, and `device controllers` shows no pose fields). Diff read, PR #75 merged by the user 2026-11-06 06:54 UTC (93054d4). Telemetry consent was answered (Essential stays on, user knows). `metavr` is usable read-only; `perf monitor`, `wake`, `proximity --disable`, `boundary --disable` and `set-property` still untried (`NOTES-vr.md`, "Meta tools"). | Claude, no build needed, 2026-11-06
### 2026-11-05
- Pruned the VR handoff (user): `NOTES-vr.md` cut to live facts, the full old notes and old backlog wording moved to `NOTES-vr-archive.md`. | Claude, no build needed, 2026-11-05
- M0 pipeline proof: passed on Quest 3 (OS v210), sideloaded at targetSdk 36. `XR_SESSION_STATE_FOCUSED` on GLES, 72 fps for 70 s, stereo grid seen in an adb capture. Comfort not checked. | Claude, CI r528 on headset, 2026-11-05
- M0 CI build: run 42052179738 passed on `claude/vr-m0`, both APKs. No OpenSCAD core exists for this branch, so they ship without it. | Claude, CI r528, 2026-11-05
- Privacy hook runs again: it called `python3`, the Store stub, and failed open. Added `python3.exe` beside the real Python 3.11 (outside the repo). Fake-address event blocked with exit 2, clean event exit 0. | Claude, no build needed, 2026-11-05
