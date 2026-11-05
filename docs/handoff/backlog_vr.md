# VR backlog (the only live backlog for the VR port)

Design and recipes: `NOTES-vr.md`. `[Opus]` = hard item. "Build none" = nothing built or run yet, so untested. Finished items move to the dated section; never delete them. Keep under ~10 KB.

## Open
- M1 real plate in stereo [Opus]: IN PROGRESS on `claude/vr-m1`, runs on the headset (results in `NOTES-vr.md`, "M1 on the headset"). Passed: bed and 36-vertex sample cube in stereo, FOCUSED, 120 fps, no crash, user saw the cube and says the bed stays in place. Open: user's answers on size, height and distance, double vision, colours, line shimmer; a heavier model for a real frame-rate test, since a cube proves nothing about plate cost; 2D regression check not done on this build. If asked, also what caused the session drop at 12:34:02 in the first run (STOPPING, then back; cause unknown). | Claude and user, CI r530 (42091237074, 12fbfb9) on Quest 3, 2026-11-05
- M2 enter, leave, passthrough: IN PROGRESS, device-free only at the user's request (2026-11-05), so unverified until M1 passes on the headset. Add the `com.oculus.feature.PASSTHROUGH` manifest feature and recheck `XR_FB_passthrough` (untested). | Claude, build none, 2026-11-05
- OVR Metrics CSV: open, low priority. ENABLE_CSV broadcast wrote no file on Quest 3 v210; no receiver in `dumpsys package`. `VrApi` logcat lines by app pid are the working substitute. | Claude, CI r528, 2026-11-05
- M3 Compose panel and ray selection [Opus]: open, blocked by M2 except its pure-Kotlin steps 1-2. Design and build order: `NOTES-vr-m3.md`, panel is a conditional go, gated by step 6's headset probe. Ends with a 2-minute user headset check. | Claude, build none, 2026-11-05

## Finished
### 2026-11-05
- Pruned the VR handoff (user): `NOTES-vr.md` cut to live facts, the full old notes and old backlog wording moved to `NOTES-vr-archive.md`. | Claude, no build needed, 2026-11-05
- M0 pipeline proof: passed on Quest 3 (OS v210), sideloaded at targetSdk 36. `XR_SESSION_STATE_FOCUSED` on GLES, 72 fps for 70 s, stereo grid seen in an adb capture. Comfort not checked. | Claude, CI r528 on headset, 2026-11-05
- M0 CI build: run 42052179738 passed on `claude/vr-m0`, both APKs. No OpenSCAD core exists for this branch, so they ship without it. | Claude, CI r528, 2026-11-05
- Privacy hook runs again: it called `python3`, the Store stub, and failed open. Added `python3.exe` beside the real Python 3.11 (outside the repo). Fake-address event blocked with exit 2, clean event exit 0. | Claude, no build needed, 2026-11-05

## Status
2026-11-05: M1 r530 installed on the Quest's `.quest` package and running, with a Bambu Lab A1 and the sample cube in its session. Quest 3 is on adb over TCP, set by the user to stay awake and ignore proximity. Quest is the only device this work may drive (user).
Pushed, Kotlin compiled and JVM-tested, native and device untested, none merged: `claude/vr-m2-passthrough` (70d146b), `claude/vr-m2-entry` (1016a6d, changes 2D code in all builds), `claude/vr-m3-input` (bb86058, M3 steps 1-2).
M2 integration done by an agent: local `claude/vr-m2` at e57af5a, not pushed, JVM tests pass (VrLinkTest 11/11), no native build, nothing run on a device; the autosave, blocked-save and "Starting…" questions are fixed there by gating on QUEST (not re-reviewed by the main session). Left: hung cold VR keeps Quest 2D on "Starting…" until force-stop (`VrLink.kt:68`); raw blend in `SceneEffects.kt:201`, `SelectionOverlay.kt:69` (M3 selection must fix); join timeout plus relaunch can overlap shim globals (`VrActivity.kt:74`). User to decide: should VR over 2D end when stopped? Now, returning to it shows a stale scene and 2D edits since the last autosave stay unsaved while it is open. Next: push it and run one CI build for M2, after M1's user answers.
