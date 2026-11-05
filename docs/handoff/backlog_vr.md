# VR backlog (the only live backlog for the VR port)

Plan and evidence: `NOTES-vr.md`. Start each session here, then read the notes; not the main backlog or `HANDOFF.md`.

Rules (user, 2026-11-05):
- All work items live here, one line each: what is open, who last checked it, on which build, when.
- Update this file in the same edit that finishes, starts or changes an item. Finished items move to the dated section at the end; never delete them. Keep it under ~10 KB.
- Work on `claude/vr-*`; pushing there needs no ask, and native code builds only in CI. Never push to main. Merges to main and releases need the user.
- Run the privacy scan before every push. No identifying details in files or commits, no AI attribution in commit messages.
- `[Opus]` = hard item, suits Opus. "Build none" = nothing built or run yet, so the item is untested.

## Open
- M1 real plate in stereo [Opus]: open, next item, M0 passed. Start from `claude/vr-m0` on a `claude/vr-m1` branch. Framebuffer target on the 11 binds, per-eye view source, VR entry bypassing `drawFrame`. Take swapchain size from the runtime (it varied between runs). On-headset recipe: "M0 results" in `NOTES-vr.md`. Ends with a 2-minute user headset check. | Claude, build none, 2026-11-05
- M2 enter, leave, passthrough: open, blocked by M1. VR button and return on the same project, `AppViewModel` scope decision, passthrough tabletop default. `XR_FB_passthrough` was not listed at M0: add the `com.oculus.feature.PASSTHROUGH` manifest feature and recheck (untested). | Claude, build none, 2026-11-05
- OVR Metrics CSV: open, low priority. ENABLE_CSV broadcast wrote no file on Quest 3 v210; no receiver found in `dumpsys package`. `VrApi` logcat lines by app pid are the working substitute. | Claude, CI r528, 2026-11-05
- M3 Compose panel and ray selection [Opus]: open, blocked by M2. Cooperative overlay from raw OpenXR or surface swapchain fallback, aim ray to `onViewTap`. Ends with a 2-minute user headset check. | Claude, build none, 2026-11-05

## Finished
### 2026-11-05
- M0 pipeline proof: passed on Quest 3 (OS v210), sideloaded at targetSdk 36. `XR_SESSION_STATE_FOCUSED` on GLES, 72 fps for 70 s, stereo grid seen in an adb capture. Frame rate came from `VrApi` and `VR_FPS`, not the planned OVR Metrics CSV. Comfort not checked (not part of M0). Details: "M0 results" in `NOTES-vr.md`. | Claude, CI r528 on headset, 2026-11-05
- M0 CI build: run 42052179738 passed on `claude/vr-m0`, producing both APKs. It annotated that no OpenSCAD core exists for this branch, so those APKs ship without it. | Claude, CI r528, 2026-11-05
- Privacy hook runs again: it called `python3`, which was the Store stub and failed open. Added `python3.exe` beside the real Python 3.11 (outside the repo). Fake-address event blocked with exit 2, clean event exit 0. | Claude, no build needed, 2026-11-05
