# Task 19 research: Meta tools/docs to cut Claude token use on the VR port

Research only; nothing installed or run. Read 2026-11-05/06. Marks: VERIFIED = read in the
cited source; INFERRED = my inference; UNKNOWN = no source settles it. Facts the brief marked
already established are not repeated. Project fit assumed throughout: sideloaded native
OpenGL ES (GLES) OpenXR APK, driven from Windows, native builds only in CI.

Sources: [AT] github.com/meta-quest/agentic-tools (README, docs/metavr-cli.md,
skills/metavr-cli/references/metavr-perf-tools.md; accessed 2026-11-06, no date in files).
[SDK] github.com/meta-quest/Meta-OpenXR-SDK @ bbed2f20 (accessed 2026-11-06).
[DOC] developers.meta.com/horizon doc pages, Updated dates as cited per claim.

## 1. Top 10 actions, ranked by tokens saved

1. Adopt the `metavr` CLI for the whole device loop - `app install/launch/stop`, `files`
   push/pull (vr_tuning.json, vr_panel.txt), `capture screenshot` - instead of hand-written
   adb scripts. Saving HIGH: removes per-check adb plumbing and re-derivation. Effort low,
   risk low. VERIFIED [AT].
2. Read docs via `metavr docs search` / `docs fetch` (Markdown twins) instead of HTML doc
   pages. Saving HIGH: no HTML furniture in context. Effort low, risk low. VERIFIED [AT].
3. Use `metavr perf context` / `perf analyze-trace` summaries (avg/p50/p90/p99, stale %,
   bottlenecks) instead of raw Perfetto traces or hand-computed logcat timing. Saving HIGH.
   Effort low, risk low. VERIFIED [AT perf ref].
4. Install only the relevant skills by name (`metavr skills install metavr-cli hz-vr-debug
   hz-perfetto-debug hz-quest-verify-first`), not the full plugin bundle. Saving MEDIUM:
   ~35 other skills (Unity/Unreal/Spatial SDK/Store) stay out. Effort low, risk low.
   VERIFIED that selective install exists [AT].
5. Default to short output: `--json` (or `config set output-format json`), `metavr log`
   with `-t TAG -l LEVEL -n N` (default 100 lines), `adb logcat --pid/-F/--regex`. Saving
   MEDIUM per device session. Effort low, risk low. VERIFIED [AT].
6. Tapedeck record/replay: record a session once with a human wearing the headset, then
   `tapedeck bundle-apk` + replay for later input-dependent checks with nobody in the
   headset. Saving HIGH if it works here. Effort medium, risk medium (device check
   pending, section 5). VERIFIED as a feature [AT]; fit for this APK UNKNOWN.
7. Take feature code from [SDK] samples, which are GLES (finding 4), instead of deriving
   from doc prose: hands, hands+controllers, microgestures, passthrough, depth, space warp.
   Saving HIGH on the coming milestones. Effort low, risk low-medium (licence, finding 4).
   VERIFIED [SDK].
8. Keep `hz-quest-verify-first` installed: it auto-loads in Quest projects and forces
   checking Meta sources before training-data answers - the failure mode that missed
   `metavr` last time. Net saving MEDIUM (it adds a fixed pre-flight cost per question).
   VERIFIED behaviour [AT]; net saving INFERRED.
9. Use `metavr capture screenshot`/recording and `metavr ui` dumps for visual checks
   instead of asking the user to describe the headset view. Saving MEDIUM. Effort low,
   risk low. VERIFIED [AT].
10. Turn extra telemetry off (`metavr telemetry disable`; `metavr mcp server
    --no-telemetry`). Saving LOW (no tokens; removes a data unknown - essential telemetry
    is collected either way). Effort low, risk low. VERIFIED [DOC, Updated Sep 4 2026].

## 2. What to ignore

- XR Simulator skills/tools (`hz-xr-simulator-*`, `metavr xrsim`): cannot run this APK.
- Unity, Unreal, Spatial SDK, WebXR and Horizon Store skills in the bundle: wrong stack;
  the store ones also assume a store build, this app is sideloaded.
- Full plugin install (`/plugin install meta-vr@meta-vr`): takes every skill; selective
  install (action 4) exists, use it.
- MVDH Performance Analyzer GUI [DOC, Updated Oct 1 2026]: a human GUI; `perf context`
  text beats screenshots of graphs for an agent.
- `metavr ssim` (Spatial Simulator): runs 2D panel apps, no OpenXR session - not a check
  for the immersive activity. INFERRED from [DOC, Updated Sep 4 2026].
- RenderDoc Meta Fork as a routine step: real but heavyweight (single frame, dev builds);
  reserve for per-draw GPU questions. It does support OpenGL apps [DOC, Updated Sep 9 2026].

## 3. GLES feature table

"GLES?" = works with an OpenGL ES graphics binding, per the source. SDK evidence: in [SDK],
8 source files use `XrGraphicsBindingOpenGLESAndroidKHR` - including the shared
SampleXrFramework `XrApp.cpp` the C++ samples build on - and 0 files use a Vulkan binding.

| Feature | GLES? | Source | Date |
|---|---|---|---|
| Hand tracking (XR_EXT_hand_tracking) | yes | [SDK] XrHandsFB on GLES-bound framework | acc. 2026-11-06 |
| XR_EXT_hand_interaction | no statement found | not in [SDK] sample list; device advertised v2 to this GLES app, listed only, session untried (repo backlog_vr.md) | 2026-11-05 |
| XR_FB_hand_tracking_aim | yes | [SDK] XrHandsFB extension list, GLES framework | acc. 2026-11-06 |
| Microgestures (XR_META_hand_tracking_microgestures) | yes | [SDK] XrMicrogestures, GLES framework | acc. 2026-11-06 |
| Simultaneous hands + controllers (XR_META_simultaneous_hands_and_controllers) | yes | [SDK] XrHandsAndControllers, Quest 3+, GLES framework | acc. 2026-11-06 |
| Passthrough (XR_FB_passthrough) | yes | [SDK] XrPassthrough.cpp contains the GLES binding itself | acc. 2026-11-06 |
| Environment depth (XR_META_environment_depth) | yes | [SDK] XrPassthroughOcclusion.cpp contains the GLES binding itself | acc. 2026-11-06 |
| Eye-tracked foveation (XR_META_foveation_eye_tracked) | no (target hardware) | [SDK] only eye sample, XrBodyFaceEyeSocial, targets Quest Pro; Quest 3/3S have no eye tracking, so the GLES question is moot here | acc. 2026-11-06 |
| Space warp (XR_FB_space_warp) | yes (native) | [SDK] XrSpaceWarp.c contains the GLES binding itself; the Vulkan requirement is in the Unreal guide only [DOC, Updated Oct 7 2026] | acc. 2026-11-06 |
| Android surface swapchain (XR_KHR_android_surface_swapchain) | no statement found | no [SDK] sample; Khronos spec + Wolvic precedent per repo NOTES-vr-m3.md | 2026-11-05 |
| Composition layers | yes | [SDK] XrCompositor_NativeActivity.c contains the GLES binding itself; this project's M1 already submitted a projection layer from GLES on device | acc. 2026-11-06 |

Net: no primary source ties any wanted feature except engine-integrated space warp to
Vulkan. Nothing found forces a renderer move for the coming milestones.

## 4. Short findings

Item 1 - metavr / agentic-tools. One tool, two front ends (CLI + MCP server, 40+ tools);
works without an account, `metavr init` offers sign-in for Store features [DOC, Updated
2026-08-31]. Commands map 1:1 onto the current hand-written loop: `app install/launch/
stop/list`, `log` (tag/level/lines filters), `adb logcat` (pid, tag:priority filter,
regex), `files` push/pull, `capture screenshot`, `input`, `device`, `docs search/fetch`,
`perf`. Skills are Agent Skills packages (SKILL.md + files, loaded on demand) [AT];
per-skill install: `metavr skills install [NAMES]...`, or `gh skill install
meta-quest/agentic-tools <name>` for one. Standing context cost of the MCP server's 40+
tool schemas: INFERRED non-trivial; the server has `--disable-perf-tools` and opt-in
`--enable-full-docs` switches [AT]. Telemetry: two tiers - `disable` returns to
essential-only, essential data is collected either way; preference syncs to the account
when signed in [DOC, Updated Sep 4 2026]. `--caller <TOOL>` is recorded on telemetry
events [AT].

Item 2 - frame timing. `metavr perf capture` takes a Perfetto trace from the device
(default 5 s, `--app` auto-detects the foreground app; CPU scheduling and GPU metrics on
by default; modes standard/gpu/cpu/lightweight/full/custom, GPU render-stage and
XR-runtime flags are custom-mode only). `perf context` / `analyze-trace` return the short
summary (frame avg/p50/p90/p99, stale count/%, thread and GPU utilisation, named
bottlenecks); `perf gpu-counters` needs at least 20 frames [AT perf ref]. Budgets:
120 Hz 8.3 ms, 90 Hz 11.1 ms, 72 Hz 13.9 ms. GLES: no statement in the perf docs excludes
GLES - capture is Perfetto/Android-level; whether GPU render-stage detail covers GLES
passes is UNKNOWN (section 5).

Item 4 - code to reuse. [SDK] paths under Samples/XrSamples/: XrHandsFB (hand mesh,
capsules, aim), XrHandsAndControllers (simultaneous hands+controllers, Quest 3+),
XrHandDataSource, XrMicrogestures, XrPassthrough, XrPassthroughOcclusion (environment
depth), XrSpaceWarp, XrCompositor_NativeActivity (layers + fixed foveation, single-file
C). All GLES per section 3. Licence: Oculus SDK License Agreement (the repo LICENSE
file), NOT Apache/MIT - read it before copying code into this public repo; patterns and
structure are safe to learn from regardless. Samples need `adb shell setprop
debug.oculus.experimentalEnabled 1` for experimental features; it resets every reboot
[SDK]. The agentic-tools repo itself is Apache 2.0 [AT].

Item 5 - cheaper checks without a headset. Tapedeck is an OpenXR API layer that records
a session's call stream (poses, controller/hand/eye telemetry, swapchain metadata) to a
VRS file and replays it into a fresh launch so the app runs as if worn. System install
needs a userdebug build; the APK-bundled mode does not: `tapedeck bundle-apk` injects
the layer, zipaligns, re-signs with a debug signer and patches the APK debuggable (the
Khronos loader only discovers implicit layers in debuggable apps); `tapedeck stop` then
finalises with no root [AT; DOC, Updated Sep 4 2026]. VrApi-only apps cannot be hooked -
this app is OpenXR. XR Simulator session capture also records/replays VRS files [DOC,
Updated Nov 2 2026] but the simulator cannot run this APK, so it is no use here.

Item 6 - traps others hit.
- Loader version: Meta supports only the Khronos loader >= 1.0.34; below that crashes
  [DOC OpenXR support, Updated Jan 23 2025].
- Doc conflict: the legacy hand-tracking page says hands and controllers are not tracked
  at the same time; on OpenXR that is superseded by the simultaneous hands+controllers
  extension, which must be enabled explicitly [DOC mobile-hand-tracking; SDK].
- Hands: tracking degrades with one hand occluding the other, poor light, fast motion;
  the hand mesh structure is about 256 KB - do not allocate several on the stack [DOC].
- Environment depth: spatial-data permission `com.oculus.permission.USE_SCENE` must be
  granted before any depth call, and depth is unreliable under about 0.2 m [DOC Depth API].
- Space warp in Unreal requires Vulkan and material changes; do not read that as a
  native requirement (section 3) [DOC, Updated Oct 7 2026].
- A session that is not FOCUSED makes actions inactive - panel/input checks must wait
  for FOCUSED (OpenXR spec, via repo NOTES-vr-m3.md).

## 5. URLs not read; questions only a device can answer

Not read: the native (non-Unreal) AppSW guide page; the prose of `metavr telemetry
about` (command exists per [AT]); individual doc Markdown twins beyond the index;
a Meta page for XR_EXT_hand_interaction (none found).
Device-only: (a) does `metavr perf capture` give useful GPU render-stage data for a GLES
app, or CPU/frame data only? (b) does a tapedeck-bundled build of this APK record and
replay cleanly with its loader version? (c) does an XR_EXT_hand_interaction session work
in this GLES build on Quest 3 v210? (d) is XR_FB_passthrough enumerated now (the open M2
question)? (e) what does the metavr MCP server's tool schema actually cost per turn in
Claude Code - measure before adopting the MCP front end over the CLI.
