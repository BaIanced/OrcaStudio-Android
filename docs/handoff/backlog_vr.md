# VR backlog (the only live backlog for the VR port)

Plan and evidence: `NOTES-vr.md`. Start each session here, then read the notes; not the main backlog or `HANDOFF.md`.

Rules (user, 2026-11-05):
- All work items live here, one line each: what is open, who last checked it, on which build, when.
- Update this file in the same edit that finishes, starts or changes an item. Finished items move to the dated section at the end; never delete them. Keep it under ~10 KB.
- Work on `claude/vr-*`; pushing there needs no ask, and native code builds only in CI. Never push to main. Merges to main and releases need the user.
- Run the privacy scan before every push. No identifying details in files or commits, no AI attribution in commit messages.
- `[Opus]` = hard item, suits Opus. "Build none" = nothing built or run yet, so the item is untested.

## Open
- M0 pipeline proof [Opus]: IN PROGRESS. Code written (`android/app/src/quest`, targetSdk still 36), Kotlin compile and 8 math JVM tests pass locally. C++ not yet compiled, nothing run on a headset (untested). Next: CI build, then on the Quest once on adb: `XR_SESSION_STATE_FOCUSED` in logcat, extension list, 72 fps for 60 s. | Claude, local compile only, 2026-11-05
- M1 real plate in stereo [Opus]: open, blocked by M0. Framebuffer target on the 11 binds, per-eye view source, VR entry bypassing `drawFrame`. Ends with a 2-minute user headset check. | Claude, build none, 2026-11-05
- M2 enter, leave, passthrough: open, blocked by M1. VR button and return on the same project, `AppViewModel` scope decision, passthrough tabletop default. | Claude, build none, 2026-11-05
- M3 Compose panel and ray selection [Opus]: open, blocked by M2. Cooperative overlay from raw OpenXR or surface swapchain fallback, aim ray to `onViewTap`. Ends with a 2-minute user headset check. | Claude, build none, 2026-11-05

## Finished
### 2026-11-05
- Privacy hook runs again: it called `python3`, which was the Store stub and failed open. Added `python3.exe` beside the real Python 3.11 (outside the repo). Fake-address event blocked with exit 2, clean event exit 0. | Claude, no build needed, 2026-11-05
