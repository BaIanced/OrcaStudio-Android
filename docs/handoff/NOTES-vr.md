# VR port: live notes

Only what the next milestone needs. Everything pruned (research, sources, perf estimates, input mapping, M4+ detail, old backlog wording) is in `NOTES-vr-archive.md`. Grep it when needed, don't read it whole; move anything pruned from here or the backlog into it. Paths are under `android/app/src/main/java/app/orcaandroid/` unless they start with `android/`.

## Approach
Hybrid app: 2D app plus one OpenXR immersive activity, drawn by the existing `PlateRenderer` on GLES, in a `quest` Gradle flavour (`-Porca.quest=true`, `android/app/src/quest`). Compose stays a real Android panel. Not full parity: settings, slicing, device, Models stay 2D.

## Decisions (user, 2026-11-05)
Accept legacy GLES, re-evaluate after M1. Sideload only. Work on `claude/vr-*`, native builds only in CI. Headset checks by the user: 2 minutes at M1 and M3 only. Quest 3 / 3S first. Default look: passthrough tabletop at 1:1.

## Comfort rules (the user gets motion sick from camera motion)
No app-driven view motion: `Camera.frame`, `fit`, `focus` must not move the world in VR. No smooth locomotion. Passthrough tabletop default, 1:1, plate not too close. Exact height and distance need a human check.

## M1 design (from reading the source, nothing built yet)
Goal: static world-locked plate plus a real model, 1:1, head-tracked. Shadows and selection outline off. Acceptance: JVM tests pass, 2D unchanged, 72 fps on the baseline plate, 2-minute user check.
- **Target framebuffer:** a `targetFbo` on `Accumulator`, `SceneEffects`, `SelectionOverlay`, `Supersampler` replacing their 11 `glBindFramebuffer(GL_FRAMEBUFFER, 0)`, default 0.
- **View source:** `interface ViewSource { eye(); pxSize(p) }`, implemented by `Camera`. `PlateRenderer` calls it instead of `camera.eye()` / `camera.pxSize` at `render/PlateRenderer.kt:603,650,1105,1285,1495,1556,1720`.
- **VR entry:** `PlateRenderer.drawEye(fbo, w, h, viewProjection, view)`: sets the target, `viewW/H`, `mvp`, `glViewport`, calls `upload()` and `drawScene()`, bypassing `drawFrame` (`:472`) and its supersampler, accumulator and loupe. `onSurfaceCreated(null, null)` builds the GL objects.
- **World transform:** plate mm Z-up to metres Y-up: (x, y, z) to 0.001 * (x, z, -y), det +1 so winding holds, plus a translation centring the plate about 0.4 m below and 0.6 m ahead of the LOCAL origin (constants, for the user check). VP = P(eye) * V(eye) * W. The eye for shaders is W inverse applied to the eye position, in mm. `pxSize` = depth mm * (tanUp - tanDown) / height px.
- **Eye target:** M0's FBO has no depth; add a depth renderbuffer per eye sized from `nativeSwapchainSize`, and `glInvalidateFramebuffer` depth after each eye.
- **sRGB trap:** the swapchain picks `GL_SRGB8_ALPHA8` first (`android/app/src/quest/cpp/xr_shim.cpp:145`), and the app's shaders write display-encoded colour, so GLES would encode twice and wash it out. `glDisable(GL_FRAMEBUFFER_SRGB_EXT = 0x8DB9)` when `GL_EXT_sRGB_write_control` is listed; log which case ran (untested).
- **Antialiasing:** no swapchain MSAA means shimmering lines, which would skew the user check. Add `GL_EXT_multisampled_render_to_texture` 4x through `eglGetProcAddress` in the shim, with a single-sample fallback (untested).
- **Scene load:** `VrActivity` runs in the app's process, so `(application as OrcaApp).container.engine`. The startup order is in `ui/AppViewModel.kt:85-98`. Project: `--es project <path>`, else `filesDir/session/session.3mf` (`ui/FileController.kt:213-214`). Engine calls go on a coroutine and post results to a queue the render thread drains. Still to find: where the 2D app calls `setMesh` / `setBed` (not in `ui/*.kt` or `render/PlateView.kt`).
- **Remove:** `vr/VrGrid.kt` and `VrMath.gridLines` plus its test, once the plate draws.

## Later milestones
- **M2:** VR button and return with `finishAndRemoveTask()`, same project; `AppViewModel` is per view-model owner (`ui/AppViewModel.kt:28`), so decide what moves to app scope. Passthrough: `XR_FB_passthrough`, the `com.oculus.feature.PASSTHROUGH` manifest feature, clear with alpha (`drawScene` clears opaque, `render/PlateRenderer.kt:571-573`). Acceptance: `session.3mf` hash unchanged across the round trip; extension enabled in logcat.
- **M3:** cooperative panel from raw OpenXR (untested), else `xrCreateSwapchainAndroidSurfaceKHR`. OpenXR actions; aim ray to `onViewTap` (`ui/SceneController.kt:412`).
- **M4-M7:** grab transforms, measure and Assemble, Preview (highest perf risk), paint. See the archive. After M2-M3, reassess whether a panel app is enough.

## Main risks
GLES is legacy on Quest and gets no new features. Interaction, not stereo, is the real cost. Preview beads may not hold 72 fps; plan with no foveation. Meta XR Simulator doesn't support GLES, so testing means JVM math tests plus the headset.

## M0 results (CI run 42052179738, r528, Quest 3, OS v210, Android 14)
- Passed: sideloaded immersive at targetSdk 36, runtime `Oculus 210.55.0`, GLES 3.0-3.2, `XR_SESSION_STATE_FOCUSED`, 72 fps for 70 s (a clear plus one grid, so no evidence on plate cost).
- Swapchain size varied between runs (1680x1760, 2184x2288). Always read it from the runtime.
- `adb exec-out screencap -p` captures both eyes.
- Extensions present: `KHR_opengl_es_enable`, `FB_foveation`, `FB_swapchain_update_state_opengl_es`, `KHR_android_surface_swapchain`, `KHR_composition_layer_cylinder`, `KHR_composition_layer_depth`, `FB_composition_layer_alpha_blend`, `FB_display_refresh_rate`, `FB_space_warp`, `META_virtual_keyboard`, `EXT_hand_tracking`, `LOGITECH_mx_ink_stylus_interaction`. **Absent: `XR_FB_passthrough`** (manifest feature not declared yet, which may be why; untested).

## On-headset recipe, nobody wearing it
`MSYS_NO_PATHCONV=1` in Git Bash, or `/sdcard` paths get rewritten.
1. Before: `input keyevent KEYCODE_WAKEUP`, `am broadcast -a com.oculus.vrpowermanager.prox_close`, `setprop debug.oculus.guardian_pause 1`.
2. `am force-stop`, `logcat -c`, `am start`. Without the pause the Guardian dialog stopped the session after about 3 s (one run, likely cause), and a launch made while it is open starts no session.
3. Read `VrApi` lines **by the app's pid**; pid-less FPS lines can be the shell's. Tag `OrcaXr`, `VR_FPS`.
4. After, always: `am force-stop`, `setprop debug.oculus.guardian_pause ''`, `am broadcast -a com.oculus.vrpowermanager.automation_disable`. Never leave Guardian paused.
