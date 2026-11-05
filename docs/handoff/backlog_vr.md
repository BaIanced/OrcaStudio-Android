# VR backlog (the only live backlog for the VR port)

Design and recipes: `NOTES-vr.md`. `[Opus]` = hard item. "Build none" = nothing built or run yet, so untested. Finished items move to the dated section; never delete them. Keep under ~10 KB.

## Open
- M1 real plate in stereo [Opus]: IN PROGRESS on `claude/vr-m1`, code done, Kotlin compiled and VR JVM tests pass, native and on-headset untested. See "M1 as built" in `NOTES-vr.md`. Next: check CI run 42089335352 (commit 77824ea; first native compile of the new shim code), then with the Quest on adb: force-stop, launch, read `VR_SCENE`, `VR_SRGB`, `VR_MSAA`, `VR_FPS`, take a screencap. Quest was not on adb 2026-11-05. Ends with a 2-minute user headset check. | Claude, Kotlin compile only, 2026-11-05
- M2 enter, leave, passthrough: open, blocked by M1. Add the `com.oculus.feature.PASSTHROUGH` manifest feature and recheck `XR_FB_passthrough` (untested). | Claude, build none, 2026-11-05
- OVR Metrics CSV: open, low priority. ENABLE_CSV broadcast wrote no file on Quest 3 v210; no receiver in `dumpsys package`. `VrApi` logcat lines by app pid are the working substitute. | Claude, CI r528, 2026-11-05
- M3 Compose panel and ray selection [Opus]: open, blocked by M2. Ends with a 2-minute user headset check. | Claude, build none, 2026-11-05

## Finished
### 2026-11-05
- Pruned the VR handoff (user): `NOTES-vr.md` cut to live facts, the full old notes and old backlog wording moved to `NOTES-vr-archive.md`. | Claude, no build needed, 2026-11-05
- M0 pipeline proof: passed on Quest 3 (OS v210), sideloaded at targetSdk 36. `XR_SESSION_STATE_FOCUSED` on GLES, 72 fps for 70 s, stereo grid seen in an adb capture. Comfort not checked. | Claude, CI r528 on headset, 2026-11-05
- M0 CI build: run 42052179738 passed on `claude/vr-m0`, both APKs. No OpenSCAD core exists for this branch, so they ship without it. | Claude, CI r528, 2026-11-05
- Privacy hook runs again: it called `python3`, the Store stub, and failed open. Added `python3.exe` beside the real Python 3.11 (outside the repo). Fake-address event blocked with exit 2, clean event exit 0. | Claude, no build needed, 2026-11-05
