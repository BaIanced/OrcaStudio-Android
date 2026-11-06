# Task 19 research: Meta tools/docs to cut Claude token use on the VR port

Research only; nothing installed or run. Read 2026-11-05/06, updated for review
2026-11-06. Marks: VERIFIED = read in the cited source; INFERRED = my inference;
UNKNOWN = no source settles it. Project fit: sideloaded native OpenGL ES (GLES)
OpenXR APK, driven from Windows, native builds only in CI. Claims cite a source key;
its exact URL is in Sources.

## 1. Top 10 actions, ranked by tokens saved

Telemetry caveat for actions 1, 2, 4, 5, 6: all need `metavr` installed, which keeps
Essential telemetry on even after `telemetry disable` (finding 1). Their risk ratings
cover the workflow only; consent to that telemetry is the user's decision, listed as
an open question in the log.

1. Read docs via `metavr docs search` / `docs fetch` (Markdown twins) instead of HTML
   pages. Saving HIGH: no HTML furniture in context. Effort low, risk low. VERIFIED [AT].
2. Use `metavr perf context` / `perf analyze-trace` summaries (avg/p50/p90/p99, stale %,
   bottlenecks) instead of raw Perfetto traces or hand-computed logcat timing. Saving
   HIGH. Effort low, risk low. VERIFIED [AT-PERF].
3. Take feature code from [SDK] samples, which are GLES (finding 4), instead of deriving
   from doc prose: hands, hands+controllers, microgestures, passthrough, depth, space
   warp. Saving HIGH on the coming milestones. Effort low, risk low-medium (licence).
   VERIFIED [SDK].
4. Adopt the `metavr` CLI for the device loop - rated against the recipe it replaces
   (repo NOTES-vr.md, "On-headset recipe"). CORRECTED (previous version wrongly said
   the wake/proximity/Guardian steps stay manual; the full command reference had not
   been searched): `device wake` does KEYCODE_WAKEUP's job, `device proximity
   --disable` "keeps headset awake regardless of wear", `device boundary --disable`
   "pauses the boundary" via sysprop + broadcast [AT]. Whether boundary --disable is
   the same switch as the recipe's `debug.oculus.guardian_pause` is UNKNOWN - docs
   only say it is independent of the MQST `set-property --disable-guardian` path.
   Also removed: pid filtering, transport selection, push/pull plumbing. `device
   controllers` shows connected controller info but lists no tracking/pose fields, so
   replacing the recipe's `dumpsys OVRRemoteService` 6dof check is UNKNOWN. Still
   manual: asking whether anyone is wearing the headset. Saving HIGH (corrected).
   Effort low, risk low.
5. Install only the relevant skills by name (`metavr skills install metavr-cli hz-vr-debug
   hz-perfetto-debug hz-quest-verify-first`), not the full bundle. Saving MEDIUM,
   measured: these 4 skills' always-loaded frontmatter totals 2,462 bytes vs 18,834 for
   all 36 [AT-SKILLS] - ~16 KB (~4k tokens, INFERRED) kept out of every context.
   Effort low, risk low. VERIFIED [AT].
6. Default to short output: `--json` (or `config set output-format json`), `metavr log`
   with `-t TAG -l LEVEL -n N` (default 100 lines), `adb logcat --pid/-F/--regex`. Saving
   MEDIUM per device session. Effort low, risk low. VERIFIED [AT].
7. Keep `hz-quest-verify-first` installed. Its frontmatter (1,001 bytes, largest of the
   four) says it "Loads automatically when the user is in a Quest project (any reference
   to Oculus / Meta Quest / Horizon OS, ...)" and forces checking Meta sources before
   training-data answers - the failure mode that missed `metavr` last time [AT-SKILLS].
   Net saving MEDIUM (fixed pre-flight cost per question). VERIFIED text; saving INFERRED.
8. Tapedeck record/replay. Saving UNKNOWN (was "HIGH if it works"): whether the public
   binary can bundle at all is unsettled, and the bundled APK cannot install over the
   existing app (finding 5). Effort medium, risk medium-high until a device check on a
   throwaway package settles it.
9. Use `metavr capture screenshot` / `metavr ui` dumps only where they add something:
   `adb exec-out screencap` already works here, and NOTES-vr.md records that stereo
   can't be judged from pixels, so it does not replace the wearer's check. Saving LOW
   (was MEDIUM). Effort low, risk low. VERIFIED [AT].
10. Turn extra telemetry off (`metavr telemetry disable`; MCP `--no-telemetry`). Saving
    LOW (no tokens; only returns to Essential, collected either way - finding 1).
    Effort low, risk low. VERIFIED [D-ENV].

## 2. What to ignore

- XR Simulator skills/tools (`hz-xr-simulator-*`, `metavr xrsim`): cannot run this APK [D-ENV].
- Unity, Unreal, Spatial SDK, WebXR, Horizon Store skills: wrong stack; store ones also
  assume a store build, this app is sideloaded [AT-SKILLS].
- Full plugin install (`/plugin install meta-vr@meta-vr`): takes every skill; selective
  install (action 5) exists, use it [AT].
- MVDH Performance Analyzer GUI: a human GUI; `perf context` text beats graph screenshots [D-ENV].
- `metavr ssim` (Spatial Simulator): runs 2D panel apps, no OpenXR session. INFERRED [D-ENV].
- RenderDoc Meta Fork as a routine step: heavyweight (single frame, dev builds); reserve
  for per-draw GPU questions [AT-PERF].

## 3. GLES feature table

"GLES?" = works with an OpenGL ES graphics binding, per the source. SDK evidence: in
[SDK], 8 files use `XrGraphicsBindingOpenGLESAndroidKHR` (incl. the shared
SampleXrFramework `XrApp.cpp`) and 0 use a Vulkan binding.

| Feature | GLES? | Source | Date |
|---|---|---|---|
| Hand tracking (XR_EXT_hand_tracking) | yes | [SDK] XrHandsFB on GLES-bound framework | acc. 2026-11-06 |
| XR_EXT_hand_interaction | no statement found | not in [SDK] samples; device advertised v2 to this GLES app, session untried (repo backlog_vr.md) | 2026-11-05 |
| XR_FB_hand_tracking_aim | yes | [SDK] XrHandsFB extension list, GLES framework | acc. 2026-11-06 |
| Microgestures (XR_META_hand_tracking_microgestures) | yes | [SDK] XrMicrogestures, GLES framework | acc. 2026-11-06 |
| Simultaneous hands + controllers (XR_META_simultaneous_hands_and_controllers) | yes | [SDK] XrHandsAndControllers, Quest 3+, GLES framework | acc. 2026-11-06 |
| Passthrough (XR_FB_passthrough) | yes | [SDK] XrPassthrough.cpp contains the GLES binding itself | acc. 2026-11-06 |
| Environment depth (XR_META_environment_depth) | yes | [SDK] XrPassthroughOcclusion.cpp contains the GLES binding itself | acc. 2026-11-06 |
| Eye-tracked foveation (XR_META_foveation_eye_tracked) | no (target hardware) | [SDK] only eye sample targets Quest Pro; Quest 3/3S have no eye tracking, so the GLES question is moot | acc. 2026-11-06 |
| Space warp (XR_FB_space_warp) | yes (native) | [SDK] XrSpaceWarp.c has the GLES binding itself; native guide gives GLES formats [D-ASW-N]; Vulkan requirement is Unreal-guide only [D-ASW-U] | 2026-10-07 |
| Android surface swapchain (XR_KHR_android_surface_swapchain) | no statement found | no [SDK] sample; Khronos spec + Wolvic precedent per repo NOTES-vr-m3.md | 2026-11-05 |
| Composition layers | yes | [SDK] XrCompositor_NativeActivity.c has the GLES binding itself; project M1 already submitted a projection layer from GLES on device | acc. 2026-11-06 |

Net: no primary source ties any wanted feature except engine-integrated space warp to
Vulkan. Nothing found forces a renderer move.

## 4. Short findings

Item 1 - metavr / agentic-tools. One tool, two front ends (CLI + MCP server, 40+ tools);
works without an account, `metavr init` offers sign-in for Store features [D-OV].
Commands map onto the current loop: `app install/launch/stop/list`, `log`, `adb logcat`
(pid, tag:priority, regex), `files` push/pull, `capture screenshot`, `input`, `device`,
`docs search/fetch`, `perf` [AT]. Skills are Agent Skills packages (SKILL.md + files,
body on demand); the always-loaded part is the YAML frontmatter. Measured at [AT] HEAD
3a8553d: frontmatter bytes - metavr-cli 621, hz-vr-debug 418, hz-perfetto-debug 422,
hz-quest-verify-first 1,001 (four: 2,462); all 36: 18,834. Per-skill install: `metavr
skills install [NAMES]...`, or `gh skill install meta-quest/agentic-tools <name>` [AT].
MCP tool-schema standing cost INFERRED non-trivial; server has `--disable-perf-tools`,
opt-in `--enable-full-docs` [AT].
Telemetry - what it collects (unanswered in the first version). Two tiers [D-ENV]:
`disable` returns to Essential only; Essential is collected either way; preference syncs
to the account when signed in. Essential contents [D-ESS]: machine / signed-user /
project identifiers; basic system/environment configuration; settings-consistency data;
tool-use frequency (reliability, bugs); OS version; error/crash data; security data
(viruses, intrusions, policy violations); playtesting/debugging data from a headset in
developer mode. The opt-in Additional tier adds [D-ADD]: tool settings, feature-use
patterns, usage frequency detail, product/app performance analytics; Meta VR CLI is
named in that policy's tool list. `--caller <TOOL>` is recorded on telemetry events [AT].

Item 2 - frame timing. `metavr perf capture` takes a Perfetto trace (default 5 s, `--app`
auto-detects the foreground app; CPU scheduling and GPU metrics on by default; modes
standard/gpu/cpu/lightweight/full/custom, GPU render-stage and XR-runtime flags
custom-mode only). `perf context` / `analyze-trace` return the short summary (frame
avg/p50/p90/p99, stale count/%, thread/GPU utilisation, named bottlenecks);
`perf gpu-counters` needs 20+ frames [AT-PERF]. Budgets: 120 Hz 8.3 ms, 90 Hz 11.1 ms,
72 Hz 13.9 ms. GLES: no perf-doc statement excludes GLES; whether GPU render-stage
detail covers GLES passes is UNKNOWN (5).

Item 4 - code to reuse. [SDK] paths under Samples/XrSamples/: XrHandsFB (hand mesh,
capsules, aim), XrHandsAndControllers (simultaneous hands+controllers, Quest 3+),
XrHandDataSource, XrMicrogestures, XrPassthrough, XrPassthroughOcclusion (depth),
XrSpaceWarp, XrCompositor_NativeActivity (layers + foveation, single-file C). All GLES
per section 3. Licence: Oculus SDK License Agreement (repo LICENSE), NOT Apache/MIT -
read it before copying code into this public repo; patterns are safe to learn from.
Samples need `setprop debug.oculus.experimentalEnabled 1` for experimental features;
resets every reboot [SDK]. agentic-tools itself is Apache 2.0 [AT].

Item 5 - cheaper checks without a headset. Tapedeck is an OpenXR API layer that records
a session's call stream (poses, controller/hand/eye telemetry, swapchain metadata) to a
VRS file and replays it into a fresh launch as if worn [AT] [D-ENV]. Corrections:
- Signing: `bundle-apk` strips the old META-INF signatures, zipaligns, and re-signs with
  `--signing-key <PEM>` if given, else a cached debug key (~/.hzdb/signing/debug/) [AT].
  A different signer means Android will not install it over the existing app;
  installing needs an uninstall first, and uninstalling wipes the session (repo
  NOTES-vr.md: "never uninstall"). Back the session up, or use another package name.
- Can the public binary bundle at all? UNKNOWN. The docs describe it in detail (its
  `doctor` probes for the cached debug key and signer), but `verify-apk`'s help says to
  check whether an APK "will accept bundling via `metavr tapedeck bundle-apk` once that
  ships", and `--skip-build` skips a `buck2 build` of the layer and "requires an embedded
  build (`-c hzdb.embed_binaries=true`)" - whether the public binary is such a build is
  stated nowhere read [AT]. System install/record/replay needs userdebug; only
  apk-bundled mode claims to need none [D-ENV].
VrApi-only apps cannot be hooked - this app is OpenXR. XR Simulator session capture also
records/replays VRS files but the simulator cannot run this APK [D-ENV].

Item 6 - traps others hit, each with its source.
- Loader version: Meta supports only the Khronos loader >= 1.0.34; below that crashes [D-OXR].
- Doc conflict: the legacy hand-tracking page says controllers and hands are not
  tracked at the same time; on OpenXR the simultaneous hands+controllers extension
  supersedes that and must be enabled explicitly [D-HAND] [SDK].
- Hands: tracking lost / confidence low when one hand occludes another; noise from
  lighting; the hand mesh structure is about 256 KB - do not allocate several on the
  stack [D-HAND].
- Environment depth: spatial-data permission `com.oculus.permission.USE_SCENE` must be
  granted before any depth call; depth is unreliable under about 0.2 m [D-DEPTH].
- Space warp in Unreal requires Vulkan; do not read that as a native requirement [D-ASW-U].
- A session that is not FOCUSED makes actions inactive - panel/input checks must wait
  for FOCUSED (OpenXR spec, via repo NOTES-vr-m3.md).
- MQST `set-property --pin` defaults to the HZDB_MQST_PIN env var, then 1234, and is
  "ignored if logged out" - this headset is logged in, so the default-PIN path is
  live [AT]. Its `--set-proximity-close`, `--disable-guardian` and `--disable-dialogs`
  flags are the other route to the action 4 steps.

## 5. URLs not read; questions only a device can answer

Not read: the prose of `metavr telemetry about` (answered from [D-ESS]/[D-ADD] instead);
doc twins beyond the index; a Meta page for XR_EXT_hand_interaction (none found).
Device-only: (a) does `perf capture` give useful GPU render-stage data for a GLES app,
or CPU/frame only? (b) does the public binary's `tapedeck bundle-apk` run at all
(embedded layer?), on a throwaway package - never the session app, per finding 5?
(c) does a bundled build of this APK record/replay cleanly with its loader? (d) does an
XR_EXT_hand_interaction session work in this GLES build on Quest 3 v210? (e) is
XR_FB_passthrough enumerated now (open M2 question)? (f) the metavr MCP tool schema's
per-turn cost in Claude Code - measure before adopting MCP over CLI.

## Sources

[AT] https://github.com/meta-quest/agentic-tools (README, docs/metavr-cli.md, HEAD 3a8553d)
[AT-PERF] https://github.com/meta-quest/agentic-tools/blob/HEAD/skills/metavr-cli/references/metavr-perf-tools.md
[AT-SKILLS] https://github.com/meta-quest/agentic-tools/tree/HEAD/skills
[SDK] https://github.com/meta-quest/Meta-OpenXR-SDK @ bbed2f20 (accessed 2026-11-06)
[D-OV] https://developers.meta.com/vr/essentials/metavr-overview/ (Updated Aug 31 2026)
[D-ENV] https://developers.meta.com/vr/essentials/metavr-environment/ (Updated Sep 4 2026)
[D-ESS] https://developers.meta.com/vr/policy/essential-data-use-tools-sdk/ (Feb 5 2025)
[D-ADD] https://developers.meta.com/vr/policy/non-essential-developer-data-use/ (Jul 20 2026)
[D-OXR] https://developers.meta.com/horizon/llmstxt/documentation/native/android/mobile-openxr.md (Jan 23 2025)
[D-HAND] https://developers.meta.com/horizon/llmstxt/documentation/native/android/mobile-hand-tracking.md
[D-DEPTH] https://developers.meta.com/horizon/llmstxt/documentation/native/android/mobile-depth.md
[D-ASW-N] https://developers.meta.com/horizon/llmstxt/documentation/native/android/mobile-asw.md (Updated Oct 7 2026)
[D-ASW-U] https://developers.meta.com/horizon/llmstxt/documentation/unreal/unreal-asw.md
Repo-internal: docs/handoff/NOTES-vr.md, NOTES-vr-m3.md (branch claude/vr-placement).
