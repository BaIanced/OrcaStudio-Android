# VR port: live notes

Only what the next milestone needs. Everything pruned (research, sources, perf estimates, input mapping, M4+ detail, old backlog wording) is in `NOTES-vr-archive.md`. Grep it when needed, don't read it whole; move anything pruned from here or the backlog into it. Paths are under `android/app/src/main/java/app/orcaandroid/` unless they start with `android/`.

## Approach
Hybrid app: 2D app plus one OpenXR immersive activity, drawn by the existing `PlateRenderer` on GLES, in a `quest` Gradle flavour (`-Porca.quest=true`, `android/app/src/quest`). Compose stays a real Android panel. Not full parity: settings, slicing, device, Models stay 2D.

## Decisions (user, 2026-11-05)
Accept legacy GLES, re-evaluate after M1. Sideload only. Work on `claude/vr-*`, native builds only in CI. Headset checks by the user: 2 minutes at M1 and M3 only. Quest 3 / 3S first. Default look: passthrough tabletop at 1:1.

## Comfort rules (the user gets motion sick from camera motion)
No app-driven view motion: `Camera.frame`, `fit`, `focus` must not move the world in VR. No smooth locomotion. Passthrough tabletop default, 1:1, plate not too close. Exact height and distance need a human check.

## M1 on the headset (CI r530, Quest 3, OS v210, 2026-11-05)
- Empty bed: `XR_SESSION_STATE_FOCUSED`, `VR_SRGB write control present, encoding off`, `VR_MSAA samples 4`, swapchain 1680x1760 format 0x8c43. 120 fps (compositor 120/120, 0 stale, app about 0.75 ms).
- Sample cube: `VR_SCENE project true plates 1 active 0 vertices 36`, 118-120 fps for 30 s, no errors or crash. User saw the cube and says the bed stays in place. The runtime ran at 120 Hz here, where M0 ran at 72 (cause not checked).
- The screencap shows raw per-eye images, each rotated and offset. System panels also show large offsets between the eyes, so don't judge stereo from pixel differences; ask the wearer.
- A fresh `.quest` install has no printer: VR logs `VR_SCENE no printer installed` and leaves in about 1 s. Pick a printer in its 2D app first.
- Saving for VR: closing the panel with its X left no session (one run, not confirmed why). Back key doesn't stop the panel. What worked: start `VrActivity` over the 2D app (VR leaves at once, `the 2D app owns the engine`), wait 8 s, force-stop, start `VrActivity` again.
- `adb input tap` at `uiautomator dump` bounds works on the panel. Address the Quest by `adb -t <transport_id>`.
- With a wearer the Guardian pause is not needed. Without one and without the pause, a "Finding position in room" dialog covers everything.

## M1 as built
Goal: static world-locked plate plus a real model, 1:1, head-tracked. Shadows and selection outline off. Acceptance: JVM tests pass, 2D unchanged, 72 fps on the baseline plate, 2-minute user check.
- **View source:** `ViewSource` (`render/Camera.kt`), implemented by `Camera` and `VrActivity.EyeView`. `PlateRenderer.viewSource` replaces its `camera.eye()` / `camera.pxSize` calls.
- **VR entry:** `PlateRenderer.drawEye(fbo, w, h, viewProjection, source)`, bypassing `drawFrame` and its supersampler, accumulator and loupe.
- **Target framebuffer, deviation from the design:** no `targetFbo` on the four effect classes. Nothing on the VR path binds framebuffer 0: `Accumulator`, `Supersampler` and the loupe are bypassed, and `SceneEffects` and `SelectionOverlay` only run with shadows on or a selection, both off in M1. Add it when a milestone turns either on (M3 selection at the latest), or the pass lands on framebuffer 0.
- **World transform:** `VrMath.plateToLocal`: (x, y, z) mm to 0.001 * (x, z, -y), active plate centre 0.4 m below and 0.6 m ahead of the LOCAL origin (`TABLE_Y`, `TABLE_AHEAD`, for the user check). VP = P * V * W. `localToPlate` gives the shaders' eye in mm.
- **Eye target (shim):** one FBO per swapchain image, one depth renderbuffer per eye, depth invalidated on release. `nativeAcquireImage` now returns the FBO.
- **sRGB trap (untested):** the swapchain picks `GL_SRGB8_ALPHA8` first and the app's shaders write display-encoded colour, so the shim disables `GL_FRAMEBUFFER_SRGB_EXT` when `GL_EXT_sRGB_write_control` is listed. Logcat: `VR_SRGB`.
- **Antialiasing (untested):** 4x `GL_EXT_multisampled_render_to_texture`, single sample if absent. Logcat: `VR_MSAA`.
- **Scene load:** `VrActivity.loadScene`, same order as `ui/AppViewModel.kt:85-98`: prepare, init, `loadPresets`, select last printer, `loadProject`, select the project's printer, `scene()`. The 2D sets mesh and bed in `ui/components/Viewport.kt:741,761`. Project: `--es project <path>` inside the app's storage, else `filesDir/session/session.3mf`. Logcat: `VR_SCENE`. Colours come from the project; surface materials stay basic.
- **2D and VR share one engine:** `Engine.initialized` makes VR leave if the 2D app is already up in the process, so it never loads over the 2D scene. The reverse (opening 2D while VR runs) is not guarded. M2 must settle both, so force-stop first when testing M1.

## Later milestones
- **M2:** VR button and return with `finishAndRemoveTask()`, same project; `AppViewModel` is per view-model owner (`ui/AppViewModel.kt:28`), so decide what moves to app scope. Passthrough: `XR_FB_passthrough`, the `com.oculus.feature.PASSTHROUGH` manifest feature, clear with alpha (`drawScene` clears opaque, `render/PlateRenderer.kt:571-573`). Acceptance: `session.3mf` hash unchanged across the round trip; extension enabled in logcat.
- **M3:** full design in `NOTES-vr-m3.md` (nothing run). Quad layer on `xrCreateSwapchainAndroidSurfaceKHR` plus a `Presentation` hosting one small new Compose panel, gated by an on-headset probe; existing 2D screens, popups and text fields can't work there. OpenXR actions; aim ray to `onViewTap` (`ui/SceneController.kt:412`). Selection look is BOX and TINT, since OUTLINE breaks under MSAA.
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
