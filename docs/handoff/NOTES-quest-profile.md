# Quest render-path profile (IN PROGRESS: no final numbers yet)

> Message log with the main Claude: `docs/handoff/claudechat.md` (read it first; see repo `CLAUDE.md`).

**Build tested:** none finished. Runs so far: 2D r527 (no cache), 2D r535 (cache) installed. VR r534 (no cache) installed; VR+cache build is CI run 42191304284 on `claude/vr-profile` (56848db = vr-m2 + cb454dc cherry-picked).
**Date:** 2026-11-06
**Device:** Quest 3 `<QUEST>`, Horizon OS v210. The user said the plate is throwaway.

## Method that works (verified)
- Virtual display: `adb shell settings put global overlay_display_devices 1920x1080/320` (display id changes: 18, 19; check `cmd display get-displays`). Launch the 2D app there with `am start --display <id> -n io.github.baianced.orcastudio_android/app.orcaandroid.ui.MainActivity`. The VR app keeps the headset on display 0. Undo with `settings delete global overlay_display_devices`.
- See it: `screencap -d` does NOT work (returns the VR view). Use `scrcpy -s <QUEST> --display-id=<id> --no-playback --no-audio --no-control --record=x.mp4 --time-limit=N`, then ffmpeg a frame.
- Orbit: `adb shell input -d <id> swipe x1 y1 x2 y2 700` in a loop (scratchpad `orbit.sh`). Zoom is a pinch and cannot be sent with adb `input`, so orbit only.
- A/B builds: pre-cache 2D = r527 (run 41939360557, 089ad1f; only android diff to cb454dc is the cache), cache 2D = r535 (run 42183752696). Downgrade with `adb install -r -d`.
- Capture: `metavr perf capture -m full --duration 30000 --app <pkg> -o <name>` (307 MiB), then `metavr perf load/query <name>`.

## Findings so far
- Trace `t2d_nocache` (r527, 30 s orbit) is captured. The 2D app's GL thread has NO atrace slices (only the main thread `astudio_android` has any), so frame time must come from SurfaceFlinger or FrameTimeline for the app's layer, not from app slices. Not extracted yet.
- Both apps rendered during that trace (the VR `.quest` app on display 0 plus the 2D app), so GPU numbers are contaminated. Stop the VR app (`am force-stop ...quest`) before 2D runs and relaunch it after.
- The VR path uses the same `PlateRenderer`, so cb454dc applies to it.

## TODO
1. 2D r527 and r535: frame time p50/p95/p99, dropped frames, GPU ms, bound type (SF layer or FrameTimeline), then `metavr perf simpleperf record` top 10 symbols for the GL thread (check strcmp and glGetUniformLocation).
2. VR r534 vs VR+cache (install over `.quest` with `adb install -r`, launch with `--ez plateInFront true`). Unworn, so no interaction; report it as a passive static plate, not orbit.
3. Refresh rate hold (72/90/120) and 5 min thermal log (`VrApi` lines, `dumpsys thermalservice`).
4. Quest-specific hotspots: fill rate, overdraw, MSAA, panel resolution.
5. Cleanup: delete the overlay setting, relaunch the VR app, reinstall nothing else. The worktree `.claude/worktrees/vr-profile` and branch `claude/vr-profile` are mine to delete when done.
