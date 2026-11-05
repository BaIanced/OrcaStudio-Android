# VR backlog (the only live backlog for the VR port)

Design and recipes: `NOTES-vr.md`. `[Opus]` = hard item. "Build none" = nothing built or run yet, so untested. Finished items move to the dated section; never delete them. Keep under ~10 KB.

## Open
- M1 real plate in stereo [Opus]: IN PROGRESS on `claude/vr-m1`, code done, Kotlin compiled and VR JVM tests pass, native and on-headset untested. See "M1 as built" in `NOTES-vr.md`. Review fixes in 12fbfb9. CI 42089335352 on 77824ea passed, so the shim compiles natively (r529, in `~/builds/vr-m1-42089335352`); not installed, the Quest's adb entry dropped mid-install. Next: check CI 42091237074 (12fbfb9), download it, reconnect the Quest (`adb mdns services`, `adb connect <ip:port>`, then address it by ip:port from `adb devices -l`; the mDNS name went stale), install to the `.quest` package (its data is separate, so a printer or project may be missing), then force-stop, launch, read `VR_SCENE`, `VR_SRGB`, `VR_MSAA`, `VR_FPS`, take a screencap. Quest was not on adb 2026-11-05. Ends with a 2-minute user headset check. | Claude, Kotlin compile only, 2026-11-05
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
2026-11-05: M1 and review fixes pushed, CI 42091237074 running. Quest 3 is on adb over TCP, set by the user to stay awake and ignore proximity; nothing of M1 has run on it yet. Quest is the only device this work may drive (user).
Pushed, Kotlin compiled and JVM-tested, native and device untested, none merged: `claude/vr-m2-passthrough` (70d146b), `claude/vr-m2-entry` (1016a6d, changes 2D code in all builds), `claude/vr-m3-input` (bb86058, M3 steps 1-2).
M2 integration: an agent was merging the two M2 branches onto `claude/vr-m1` as local `claude/vr-m2`. Check `git log claude/vr-m2`; if missing or without a commit, redo it. Don't merge `claude/vr-m2-entry` until checked: autosave skipping a write in all builds (can it lose work? gate on QUEST or remove), saves staying blocked if VR crashes, "Starting…" unreachable outside Quest builds. Keep the 12fbfb9 fixes. Then one CI build for M2, after M1 passes on the headset.
