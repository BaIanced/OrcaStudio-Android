# VR port: feasibility plan (2026-11-05, plan only, no code written)

Tags: **[R]** verified in this repo (paths below are under `android/app/src/main/java/app/orcaandroid/` unless they start with `android/`, `docs/` or `.github/`). **[D n]** read in an official doc or registry, source n at the end; each comes from one read of the page unless marked "3-vote". **[I]** inferred. **[U]** unverified.

Research coverage: the research run was stopped early on request. 28 fetch results came back, but only 8 claims got adversarial votes (0 refuted). Nothing was tested on a headset, and the Quest was not on adb during this work. Effort: S = 1-2 sessions, M = several, L = many.

## Recommendation
**Build a hybrid app: the existing 2D app plus one OpenXR immersive activity, drawn by the existing `PlateRenderer` on GLES, in a `quest` Gradle flavour. Compose stays a real Android panel.**
- The renderer is Kotlin on `GLES30`, not C++ (`render/PlateRenderer.kt:3,73`) [R], so stereo is mostly Kotlin work. OpenXR needs only a small new JNI shim [I], and `src-orca` needs no patch.
- Horizon OS supports one app switching between 2D panel activities and OpenXR immersive activities, declared by intent category [D 1, 3-vote].
- Picking already takes origin + direction (`ui/SceneController.kt:412,620,772`) [R], so a controller ray can feed it directly.

**Runner-up: Meta Spatial SDK (lost).** It has the best Compose panels (`ComposeViewPanelRegistration`, compositor layers) [D 2,3], but it owns the renderer:
- Content is entities with glTF or `mesh://` meshes, and its coordinates are left-handed metres [D 4].
- Custom shaders are "advanced, experimental", take only vector4 and texture material parameters, and must match its predefined layout [D 5].
- No doc I read shows a way to host an app's own GL renderer. The stereo panel API is Android `Canvas` drawing [D 6,7].
- Moving would mean rewriting `PlateRenderer` (1,800 lines) and `Shaders.kt` (1,049 lines) [R], including the ids, drags and overlays the app relies on. The archive already rejected another engine for that reason (`docs/handoff/HANDOFF-ARCHIVE.md:3860`) [R].
- It is pre-1.0 (v0.15.0, 2026-10-29) and still changes defaults between minor releases [D 7].

| Other paths | Verdict |
|---|---|
| Panel features only (multi-panel, spatialised 2D) | No stereo 3D view, so it misses the goal. It is what the app is today (`docs/handoff/HANDOFF-BACKLOG.md:50`) [R] |
| Unity / Godot / Unreal | Would mean a second renderer and embedding the Kotlin + JNI app in an engine [I]. Worst fit |
| WebXR | Needs the scene and renderer rebuilt in a browser, with JNI picking across a bridge [I]. No gain |
| Vulkan rewrite + OpenXR | The future-proof path [D 8], but L+ work and two renderers to maintain. Revisit only if GLES blocks us |

## What "native VR view" means here
Stereo, head-tracked, world-locked plates and models, with ray-based selection and manipulation. It is not full feature parity in 3D. Settings, slicing, device, filament and the Models browser stay 2D.

## Findings by question

### 1. Platform state
- **Manifest:** immersive activities use category `com.oculus.intent.category.VR`, panels use `com.oculus.intent.category.2D` [D 1,9, 3-vote]. Immersive apps declare `android.hardware.vr.headtracking` as required [D 9, 3-vote].
- **Horizon OS version:** hybrid needs v69+ through `metavr:uses-metavr-sdk` [D 1, 3-vote]. Immersive activities need a specific `configChanges` on v85+ [D 10].
- **targetSdk (conflict):** Meta's release rules allow 32-34 for immersive and 32-37 for 2D, and apps created since 2026-03-01 must use 34 [D 9, 3-vote; the ranges came through a tool summary of the table]. This app is on 36 (`android/app/build.gradle.kts:39`) [R]. Those are store rules. Whether a sideloaded immersive activity runs at 36 is [U], and M0 tests it.
- **Loader:** `org.khronos.openxr:openxr_loader_for_android` 1.1.65 is on Maven Central, about 2026-10. It is an AAR, and the app still sets its own application and activity manifest entries [D 11].
- **Device support:** Quest 2 is still a valid manifest target [D 9], but Meta's native samples list only Quest 3 / 3S [D 12]. Treat Quest 2 as best effort.

### 2. Rendering reuse
**Yes, the renderer can be driven in stereo, but only part of it, and GLES is the main long-term risk.**

Changes the repo needs [R]:
- 11 hard-coded `glBindFramebuffer(GL_FRAMEBUFFER, 0)` calls must return to the swapchain's framebuffer: `render/Accumulator.kt:65,120`, `render/SceneEffects.kt:97,123,156,237,245`, `render/SelectionOverlay.kt:55,98`, `render/Supersampler.kt:58,100`.
- `Camera` is a single orbit camera with symmetric `perspectiveM` and Z-up mm (`render/Camera.kt:14,238-251`). VR needs per-eye view and an asymmetric projection from OpenXR. Eye-dependent uses also need the per-eye value: `camera.eye()` (`render/PlateRenderer.kt:603,1105,1495,1556,1720`) and `camera.pxSize` (`:1285`).
- `GLSurfaceView` owns the context and thread (`render/PlateView.kt:120,343-347`). VR needs its own EGL context and frame loop, so the renderer must run without `PlateView`.
- `drawFrame` has features that assume a still 2D camera. VR should call `drawScene` directly (`render/PlateRenderer.kt:570`), with no supersampler, accumulator or loupe (`:488,495`; `:468`).
- Near and far planes are tied to orbit distance, and toolpath depth ordering depends on them (`render/Camera.kt:230-235`). Expect z-fighting work in Preview [I].

Plan choices:
- **Two-pass first.** Multiview would touch the 16 `gl_Position` lines (`render/Shaders.kt`) [R] and needs `GL_OVR_multiview2` [U on Quest GLES]. It is an optimisation for later.
- **Foveation is unconfirmed on GLES.** Meta's native page describes `XR_FB_foveation` only with Vulkan's density map [D 13]. Another page says two-pass OpenGL under Unity gets no FFR and full-screen passes can turn it off [D 14]. Plan with no FFR.
- **MSAA:** use at most 4x and never store subsamples to RAM [D 14,15]. Meta's cost figures come from older hardware [D 15].
- **SpaceWarp:** documented for GLES, but needs a motion-vector pass and material changes [D 16]. Not early work.

Budgets from Meta [D 17]:

| | Quest 3 | Quest 2 |
|---|---|---|
| Frame budget at 72 Hz | 13.8 ms | 13.8 ms |
| Triangles per frame | under 1.5M | under 750K |
| Draw calls | under 200 | under 100 |
| GPU | about 2.5x Quest 2 | baseline |

Perf risk estimates [I, nothing measured]:

| Scene | Risk | Why |
|---|---|---|
| Typical meshes | Low to medium | The baseline plate is about 228K triangles (`docs/PERFORMANCE.md`) [R], but `MESH_FS` is about 350 lines (`render/Shaders.kt:100-454`) [R], so fill is the limit, not triangles |
| Shadows | Medium | The shadow pass can be shared by both eyes, but the map is redrawn when its key changes (`render/PlateRenderer.kt:581,590`) [R] |
| Selection outline | Medium | A full-screen pass per eye (`render/SelectionOverlay.kt`) [R]; such passes can disable FFR [D 14] |
| Preview beads | **High** | Large vertex and fragment shaders (`render/Shaders.kt:645-885`) [R], with up to whole-print segment counts. Expect to limit the layer range (`previewRange`, `render/PlateRenderer.kt:608`) or simplify |
| GC hitches | Medium | Per-frame allocation (`listOf`, `toList`, `copyOf`, `render/PlateRenderer.kt:566-568,581,591`) [R] costs more with a hard frame deadline |

### 3. UI: Compose in VR
1. **Try first: cooperative mode.** A panel activity launched from an immersive activity stays visible and interactive beside it [D 1]. It needs no panel rendering work, and real Compose text fields should behave normally [I]. The doc sits in the Spatial SDK section, so whether it works from raw OpenXR is [U]; M3 tests it.
2. **Fallback: surface swapchain.** `xrCreateSwapchainAndroidSurfaceKHR` returns an Android `Surface` for a quad or cylinder layer [D 18]. Compose would go through a `VirtualDisplay` or `Presentation` [I]. Costs:
   - The app must stop the writers in step with session state [D 18].
   - Touch must be injected as our own `MotionEvent`s, and text input is known to be awkward; Flutter's write-up is a good warning [D 19].

| Stays 2D Compose | Becomes 3D |
|---|---|
| Settings, slicing, device tab, filament pickers, presets, Models WebView, OpenSCAD code, file pickers | Plate, models, selection, move / rotate / scale, measure, paint, cut plane, Preview |

Meta advises one main control panel rather than many floating windows [D 20].

### 4. Input
A 2D panel gets standard motion events from controllers, hands, mouse and stylus while hovering. Look and Pinch gives no gaze or hover data [D 21]. In immersive mode the panel path no longer applies to the 3D view, so input goes through OpenXR actions [I].

| Today | VR mapping | Verdict |
|---|---|---|
| Tap to select | Aim pose gives origin + dir to `onViewTap` (`ui/SceneController.kt:412`) [R] | Direct reuse |
| Paint / sculpt hover | Same ray to `paintHover`, `sculptHover`, `paintAt` (`ui/SceneController.kt:772,936,620`) [R]. `paintAt` also takes a camera position, so pass the head position [I] | Reuse, but aiming at a distance will be shaky [I] |
| Measure | `measurePick` is ray-based (`ui/SceneController.kt:542`) [R]; the loupe is a 2D inset (`render/PlateRenderer.kt:506`) [R] | Reuse picking; drop the loupe, use snapping plus moving your head close |
| Move | `Camera.dragPoint` uses screen x, y (`render/Camera.kt:285`) [R] | **Redesign:** grab with the controller and apply its pose delta |
| Rotate / scale gizmos | Picked in screen pixels via `RotateGizmo.pick` (`render/PlateView.kt:992,1035`) [R] | **Redesign** with a 3D ray or direct grab. Keep the math where possible |
| Orbit, pan, zoom | One and two finger gestures (`render/PlateView.kt:471`) [R] | **Replace:** grab the world, two hands to scale. Never app-driven |
| Pie menu | The user dislikes it (`docs/handoff/HANDOFF-ARCHIVE.md:3866`) [R] | Don't port |
| S Pen | Pressure already scales strokes (`render/PlateView.kt:444`) [R]; Quest pen in 2D has no pressure (`docs/handoff/HANDOFF-ARCHIVE.md:3804`) [R] | MX Ink has an OpenXR profile with tip force and tip pose through a vendor extension [D 22]. Optional, later |

`PlateView` (1,206 lines) mixes gestures with renderer calls [R], and most of it can't be reused. Start with controllers. Hands are a later milestone: fine CAD input by pinch is likely poor [I].

### 5. Comfort
The user gets motion sick from camera motion: the view cube is off by default for that reason (`docs/handoff/HANDOFF-BACKLOG.md:31`) [R]. Hard rules:
- **No app-driven view motion at all.** `Camera.frame`, `fit` and `focus` (`render/Camera.kt:43,56,71`) [R] must not move the world while in VR. Meta warns against sudden acceleration and unnatural camera movement [D 20].
- **Default to passthrough with a tabletop plate.** The real room stays still around it [I]. It needs `XR_FB_passthrough`, the `com.oculus.feature.PASSTHROUGH` manifest feature and an opaque blend mode with layer alpha [D 23]. `drawScene` clears to an opaque background (`render/PlateRenderer.kt:571-573`) [R], which must change. The app gets no camera frames [D 23].
- **Default scale 1:1 for a true-size print check.** Meta notes some objects are best seen at real size [D 20]. Keep the plate out of too-close range [D 20]. The exact height and distance need a human test.
- **No smooth locomotion.** The user walks, or grabs the world by hand. If more is needed, use teleport or snap turns [D 20].

### 6. Build and test
- **Flavour rather than a single APK.** Meta's own conversion guide uses `mobile` and `quest` flavours [D 10], and the immersive manifest differs from a panel's [D 9]. The phone APK stays unchanged, which also suits the size rule (`docs/PERFORMANCE.md`) [R]. No flavours exist today, and I saw no `externalNativeBuild` in `android/app/build.gradle.kts`; native libraries arrive as staged jniLibs (`:159,163`) [R].
- **Shim build:** add one small CMake library plus the loader AAR. There is no NDK under `D:/android_sdk/ndk` on this PC [R], but WSL has one (`docs/handoff/HANDOFF-REFERENCE.md:138`) [R].
- **Local loop:** `stage-from-apk.sh` plus `:app:assembleDebug` (`docs/handoff/HANDOFF-REFERENCE.md:109-111`) [R] avoids CI until a release-style build is needed.
- **CI conflict:** CI runs only on `main` and `claude/*` (user 2026-11-05). `android-apk.yml` uses the `release` environment limited to those branches (`.github/workflows/android-apk.yml:11,45`) [R], and `personal/work` is not on origin [R]. Only the privacy scan triggers on every push (`.github/workflows/privacy-scan.yml:6`) [R].
- **Meta XR Simulator can't test this path.** It doesn't support OpenGL ES and has no Android layer [D 24]. There is no emulator or useful headless GL path either [I]. The fallback is JVM tests for the math (`CameraTest` and the like exist, `android/app/src/test/.../render/`) [R] and on-device checks.
- **adb tools on the Quest:**
  - OVR Metrics: `adb shell am start omms://app`, CSV reports under `/sdcard/Android/data/com.oculus.ovrmonitormetricsservice/files/CapturedMetrics/`, broadcasts to toggle them [D 25].
  - `VrApi` logcat lines report performance once a second [D 26].
  - Meta's developer hub can disable the proximity sensor and capture screenshots and video [D 26]. An adb-only way to do those is [U].
- **Testing limit:** Claude can check frame rate, logs and maybe screenshots. Claude can't judge comfort, scale or feel, which conflicts with "no user testing" (`docs/handoff/HANDOFF.md:20`) [R].

### 7. Prior art (thin, mostly listings)
- **VR slicer: none found** in one targeted search [U as an absence].
- **Glimpse GLB:** Quest viewer with STL, local files and a VR / passthrough switch. It validates the mode switch; its store listing has only 10 reviews [D 27].
- **QuestSTL:** a basic passthrough STL viewer MVP, sideloaded. **VR-GCODE:** an open-source VR G-code preview, with several GitHub copies. Search-result summaries only, not inspected [U].
- **A Quest-to-Bambu experiment** goes through a PC server. One developer, low authority [U].
- **Gravity Sketch, Shapelab, Open Brush, SculptrVR, Arkio:** I verified nothing useful about them. Claims omitted.
- **Reusable:** `meta-quest/Meta-OpenXR-SDK` builds with Gradle + CMake and has `XrCompositor_NativeActivity` (quad and cylinder layers), `XrPassthrough`, `XrInput`, `XrHandsAndControllers`, `XrSpaceWarp` [D 12]. Its graphics API isn't stated on the page; I recall GLES, but that's [U]. Check its licence before copying code.

## Phased plan

### M0: prove the pipeline (S to M)
- **Goal:** answer the biggest unknowns before touching the renderer.
- **Tasks:** `quest` flavour; `android/app/src/quest/AndroidManifest.xml`; new `vr/VrActivity.kt`; small JNI shim (instance, session, GLES binding, swapchains, frame loop); clear per eye and draw one static grid with its own tiny shader; log the runtime's extension list.
- **Acceptance (adb):** `am start` the activity; logcat shows `XR_SESSION_STATE_FOCUSED`; OVR Metrics CSV shows a steady 72 fps for 60 s; extension list logged. Records whether targetSdk 36 runs, or lowers it for the flavour.
- **Unblocks:** everything. **Stop and rethink if GLES can't get a session here.**

### M1: real plate in stereo (M)
- **Goal:** a static, world-locked plate and a real model, 1:1, head-tracked.
- **Tasks:**
  - Add a target framebuffer to the 11 binds and replace `viewW/viewH` use where needed (`render/PlateRenderer.kt`).
  - Split a view source out of `Camera`: per-eye matrix, eye and pxSize, with the mm Z-up to metres Y-up transform.
  - Add a VR entry that bypasses `drawFrame`. Load from the app's session, using the app-level `Engine` (`OrcaApp.kt:23`) [R].
  - Keep shadows and the selection outline off.
- **Acceptance:** JVM tests pass; the 2D app looks unchanged (WSA VD, with your OK since it's shared); OVR Metrics at 72 fps with the baseline plate; one **2-minute headset check by you** for stereo correctness and comfort.
- **Unblocks:** all visual work, and a go / no-go on the project.

### M2: enter, leave, passthrough (S to M)
- **Goal:** button in the 2D app to enter VR and back, on the same project; passthrough tabletop as default.
- **Tasks:** exclusive-mode switching with `finishAndRemoveTask()` [D 1]; `AppViewModel` is created per Android view-model owner (`ui/AppViewModel.kt:28`) [R], so decide what moves to app scope or goes through the session; clear with alpha, add passthrough layer.
- **Acceptance:** intent round trip with no session loss (`session.3mf` hash before and after); `XR_FB_passthrough` enabled in logcat.
- **Unblocks:** daily use, and M3 and M4.

### M3: Compose panel and selection (M)
- **Goal:** the real panel beside the plate, and controller ray select.
- **Tasks:** cooperative overlay [D 1], or the surface swapchain fallback if it doesn't work from raw OpenXR; OpenXR actions; aim ray to `onViewTap`; draw the ray and a hit marker; keep the selection state shared with the panel.
- **Acceptance:** debug log of hit id equals the panel's selected object for a scripted set of poses if possible (can't be fully automated [I]); panel responds; you confirm once.
- **Unblocks:** all tools.

### M4: move, rotate, scale (M to L)
- **Goal:** direct-grab object transforms and world grab, committed through existing engine calls.
- **Tasks:** new `vr/VrInteraction.kt`; reuse `RotateGizmo` and `ScaleGizmo` math where it isn't tied to pixels; world grab with no inertia; two-handed scale.
- **Acceptance:** undo works; the transform in `session.3mf` matches after returning to 2D; 72 fps while dragging.
- **Unblocks:** a useful assemble and arrange flow.

### M5: measure, then Assemble space (M)
- **Goal:** ray and tip measuring, and the zero-g workspace as VR content.
- **Tasks:** `measurePick` with a VR marker; reuse `buildSolo` floor (`render/PlateRenderer.kt:601-602`) [R]. Assemble A1 is already built (`docs/handoff/HANDOFF.md:36`) [R]. It's a natural VR scene but not a prerequisite, despite the backlog's "first VR step" (`docs/handoff/HANDOFF-BACKLOG.md:58`) [R].
- **Acceptance:** a measured known-size test cube reads within tolerance through the app's values; you confirm feel.

### M6: Preview (L, highest perf risk)
- **Goal:** stereo toolpaths for a limited layer range.
- **Tasks:** depth ordering fixes for VR near and far planes; layer range defaults; possibly simplified beads; maybe multiview or SpaceWarp if measurement says so.
- **Acceptance:** OVR Metrics on the fixed baseline slice (541 layers, `docs/PERFORMANCE.md`) [R]; state how many layers hold 72 fps.

### M7: paint, optional stylus, hands (L, optional)
- **Goal:** paint in VR if it proves worthwhile; MX Ink pressure; hand tracking.
- **Acceptance:** stroke on a model survives to 2D and to the slicer. Decide after M4; this is the likeliest cut.

## Risks, worst first
1. **GLES is legacy on Quest.** New features come only to Vulkan, and Vulkan is recommended [D 8]. One native page goes further and says Vulkan is "required" with GLES content kept for history [D 14]; others still document GLES [D 8,16]. I found no announced removal or store rule [U as an absence]. Likely result: it works but never improves, and a future OS could force a rewrite.
2. **Interaction is the real cost, not stereo.** Screen-space gestures don't port, and M4 onward is mostly new code. Full parity is impractical; don't promise it.
3. **Preview and heavy shaders may not hold the frame rate,** and cheap FFR may not be available. Real-time realistic mode in VR: assume no [I].
4. **Weak test loop.** No simulator and a headset that must be awake. Comfort needs your eyes.
5. **targetSdk 36 vs 34** for immersive. A flavour fixes it, but flavour libraries and code may then need checking against 34 [I].
6. **Shared state across activities.** `AppViewModel` and a Compose tree weren't built for a second renderer host.
7. **Compose overlay unknown from raw OpenXR.** The fallback works by spec but has real input costs [D 18,19].
8. **CI excludes this branch.** Local debug builds cover M0 to M3 but aren't the shipping build.
9. **Small payoff vs a far simpler alternative.** The panel app works today. If the real need is seeing true-size models on a desk, M2 to M3 is the point to stop and reassess.

**Impractical, don't plan on it:** fine paint or small-feature measuring by hand tracking; Models WebView or OpenSCAD editing as 3D UI; a loupe; `Camera`-style auto framing; Vulkan-only features on this renderer.

## Decisions (user, 2026-11-05: all accepted, #3 changed)
1. **Accept a legacy GLES renderer for VR?** **Yes, then re-evaluate after M1.** The alternative is a Vulkan rewrite first (L+).
2. **Distribution?** **Sideload only for now.** The store means targetSdk 34 for the flavour and a performance review [D 9,17].
3. **Which branch for CI?** **Work on a `claude/vr-*` branch; native code builds only in CI. Pushing there needs no ask; never push to main.** This replaces the `personal/work` local-build default and the CI conflict and risk 8 above.
4. **Headset checks by you?** **Two minutes at M1 and M3 only.** This is an exception to "no user testing".
5. **Which devices?** **Quest 3 / 3S first, Quest 2 best effort.**
6. **Default look?** **Passthrough tabletop at 1:1**, with a plain background as an option.

## Open questions and cheap checks
| Question | How to find out |
|---|---|
| Does immersive run at targetSdk 36 when sideloaded? | M0 |
| Does the runtime expose GLES binding, `XR_FB_foveation` on GLES, `XR_KHR_android_surface_swapchain`? | M0 extension log, then one foveation test |
| Which graphics API do Meta's samples use, and what's the licence? | Search the repo for the GLES define; read `LICENSE` |
| Does cooperative mode work from raw OpenXR without Spatial SDK? | Small test in M3, or read the hybrid sample |
| Can a surface be attached to a Spatial SDK panel for GL? | Read the panel docs and samples; only matters if GLES fails |
| Can adb alone disable the proximity sensor and capture a VR frame? | Try on the Quest once it's connected (no install needed); developer hub otherwise |
| Which Quest model and OS version is connected? | `adb shell getprop`, once it's on adb. Don't record the serial or IP |
| Is multiview worth doing? | OVR Metrics two-pass numbers at M1 |

## Noticed, not fixed (out of VR scope)
- `docs/handoff/HANDOFF-BACKLOG.md:58` says Assemble A1 is untested, while `docs/handoff/HANDOFF.md:36` says A1 + A2 are verified.
- `docs/handoff/HANDOFF.md:14` names `claude/handoff-continuation-rseql3` as the work branch; this work uses `personal/work`.
- `docs/handoff/HANDOFF-REFERENCE.md:124` calls `android-apk.yml` "dispatch only", but it also triggers on `main` pushes (`.github/workflows/android-apk.yml:23-24`).

## Sources
Accessed 2026-11-05. Dates are the page's last update where one was shown. Meta also serves some of these under `/vr/` or `/horizon/` paths.

1. Hybrid apps (2026-09-22): https://developers.meta.com/vr/documentation/spatial-sdk/hybrid-apps-overview/
2. Spatial SDK Compose panels (2026-04-02): https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-compose/
3. Spatial SDK panel layers (2026-04-22): https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-layers/
4. Spatial SDK 3D objects (2025-10-01, possibly outdated): https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-3dobjects/
5. Spatial SDK custom shaders (2026-06-25): https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-custom-shaders/
6. Stereo canvas panel API (search summary only): https://developers.meta.com/horizon/reference/spatial-sdk/v0.13.2/com_meta_spatial_toolkit_stereocanvaspanelsettings
7. Spatial SDK release notes (2026-10-29): https://developers.meta.com/horizon/release-notes/spatial-sdk/
8. Vulkan and OpenGL ES on Quest (undated): https://developers.meta.com/horizon/documentation/native/android/os-vulkan-opengl/
9. Release manifest rules (2026-10-15): https://developers.meta.com/vr/distribute/publish-mobile-manifest/
10. Add Spatial SDK to an app (2026-09-22): https://latest.developers.meta.com/horizon/documentation/spatial-sdk/add-spatial-sdk-to-app/
11. OpenXR loader AAR (about 2026-10): https://central.sonatype.com/artifact/org.khronos.openxr/openxr_loader_for_android
12. Meta OpenXR samples (2026-05-07): https://developers.meta.com/horizon/documentation/native/native-openxr-sdk-sample/
13. Fixed foveated rendering (2026-10-07): https://developers.meta.com/horizon/documentation/native/android/os-fixed-foveated-rendering/
14. Advanced GPU pipelines (undated): https://developers.meta.com/horizon/documentation/native/android/po-advanced-gpu-pipelines/
15. MSAA analysis (2026-11-04, content looks older): https://developers.meta.com/horizon/documentation/native/android/mobile-msaa-analysis/
16. Application SpaceWarp (2026-10-07): https://developers.meta.com/horizon/documentation/native/android/mobile-asw/
17. Device optimization comparison (2026-05-11): https://developers.meta.com/horizon/resources/device-optimization-comparison/
18. `xrCreateSwapchainAndroidSurfaceKHR` (spec 1.1): https://registry.khronos.org/OpenXR/specs/1.1/man/html/xrCreateSwapchainAndroidSurfaceKHR.html
19. Flutter on VirtualDisplay: https://flutter.googlesource.com/mirrors/flutter/+/HEAD/docs/platforms/android/Virtual-Display.md
20. Comfort guidance (2025-12-17): https://developers.meta.com/horizon/design/comfort/
21. Panel app input (2026-09-08): https://developers.meta.com/horizon/documentation/android-apps/input
22. Logitech MX Ink OpenXR: https://logitech.github.io/mxink/OpenXR.html
23. Passthrough (undated): https://developers.meta.com/vr/documentation/native/android/mobile-passthrough/
24. Meta XR Simulator (2026-11-02): https://developers.meta.com/vr/documentation/native/xrsim-intro/
25. OVR Metrics Tool (2026-10-02): https://developers.meta.com/horizon/documentation/native/android/ts-ovrmetricstool/
26. Performance tools (2026-04-17): https://developers.meta.com/horizon/documentation/unreal/ts-book-tools/
27. Glimpse GLB listing: https://www.meta.com/experiences/glimpse-glb/25467602739525995/

Search results only, not read: QuestSTL https://ebubar.itch.io/questl, VR-GCODE https://github.com/chrissbarr/VR-GCODE (via a mirror), Quest-to-Bambu experiment https://5thscape.com/blog/quest-3d-object-printing-integration/, GLES status on Unreal docs https://developers.meta.com/horizon/documentation/unreal/os-vulkan-opengl/
