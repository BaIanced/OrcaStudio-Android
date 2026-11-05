# M3 design: Compose panel and controller ray selection

Tags: [S n] sourced (list at the end), [R] read in this repo at the cited file:line, [I] inferred. Paths are under `android/app/src/`, except `app/build.gradle.kts` and `app/src/testQuest` (under `android/`) and `docs/`. `main/X` is short for `main/java/app/orcaandroid/X`, `quest/X.kt` for `quest/java/app/orcaandroid/vr/X.kt`; `xr_shim.cpp` is `quest/cpp/xr_shim.cpp`. Nothing here has been run.

## Verdict
**GO, gated by step 6's probe:** one world-locked quad layer on `xrCreateSwapchainAndroidSurfaceKHR`, a private `VirtualDisplay`, a `Presentation` hosting one `ComposeView`, touch injected as `MotionEvent`s [I]. **NO-GO on hosting the existing 2D screens** and on text fields in this panel. The panel is a new small composable. Stop rule: if both drawing routes in step 6 fail, ship ray selection alone and reassess, as the archive already suggests (`docs/handoff/NOTES-vr-archive.md:188`) [R].

## 1. Panel
- **Spatial SDK panels: not reachable.** `ComposeViewPanelRegistration` and layer-mode panels are Spatial SDK APIs, and that SDK lost because it owns the renderer (`NOTES-vr-archive.md:13,16`) [R]. Its docs don't say how panel pixels reach the scene [S 2].
- **Cooperative mode: unproven from raw OpenXR, not the primary path.** It is a plain Android launch: `startActivity` with `FLAG_ACTIVITY_NEW_TASK`, no `finishAndRemoveTask()`, 2D and VR categories, OS v69+ [S 1]. The manifest already has those (`quest/AndroidManifest.xml:7-9,25,39`) [R]. The page sits in the Spatial SDK section, its only sample is Spatial SDK, and it never states a requirement either way [S 1]. I found no raw OpenXR example [I as an absence]. Two problems: the doc shows no placement control, and "interactable" doesn't say the session stays FOCUSED, while `xrSyncActions` makes every action inactive when it isn't [S 4], so plate selection may die whenever the panel has focus [I]. Keep it as a later route to text entry and settings; step 9 measures the session state.
- **Surface swapchain: works by spec.** It returns an ordinary `Surface` usable with normal Android APIs, and the swapchain is valid in a layer's sub-image [S 3]. The extension is present on the headset (`docs/handoff/NOTES-vr.md:38`) but not enabled (`quest/cpp/xr_shim.cpp:116,122`) [R].
- **Shipped precedent (partial):** Wolvic, an OpenXR browser with a Quest build, enables this extension, zeroes the create-info fields and takes a global ref on the surface, then hosts real Android views in a `Presentation` on an `OWN_CONTENT_ONLY` virtual display, feeding them synthetic `MotionEvent`s [S 5]. **But** its display is 16x16 and only attaches the views: each widget redirects `draw()` into `lockHardwareCanvas()` on its own surface [S 5]. So no source shows a virtual display writing directly into a surface swapchain on Quest, and none shows Compose there [I as an absence]. Hence two routes, chosen in step 6.
  - **Route A (try first):** `createVirtualDisplay("orca-panel", 1280, 800, 320, xrSurface, 0)`. Flags 0 gives a private display owned by this UID, with own-content behaviour implied [S 6]. Correct Compose metrics and no custom drawing [I].
  - **Route B (fallback):** the virtual display gets a dummy `SurfaceTexture` of the same size; a host `FrameLayout` overrides `draw()` as Wolvic does and paints into the XR surface with `lockHardwareCanvas()` [S 5]. Writers stay in our process, which makes the stop rule easy [I]. Compose on this path is unproven [I].
- **Hosting Compose** [I unless tagged]:
  - `VrActivity` extends plain `Activity` (`quest/VrActivity.kt:34`) [R]. Make it `ComponentActivity`, like `main/ui/MainActivity.kt:45`; `activity-compose` is already a dependency (`app/build.gradle.kts:201`) [R].
  - On the main thread: `Presentation(activity, vd.display)`, `setContentView(ComposeView)`, then `setViewTreeLifecycleOwner`, `setViewTreeSavedStateRegistryOwner` and `setViewTreeViewModelStoreOwner` from the activity on the ComposeView, before `show()`. Compose throws at attach if the first two are missing [S 7].
  - Dismiss on pause, recreate on resume, as Wolvic does [S 5]. On pause, also send `ACTION_CANCEL` if a press is open.
  - `(view.context as Activity)` in the app theme (`main/ui/theme/AppColors.kt:264`) [R] would crash, because a Presentation's context isn't the Activity. So the panel can't use that theme composable as is: add a guard or give the panel its own `MaterialTheme`.
- **Touch injection:** `MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, 0, 0f, 0f, 0, 0, SOURCE_TOUCHSCREEN, 0)` with `TOOL_TYPE_FINGER`, matching Wolvic [S 5]. Dispatch on the main thread to the Presentation's decor view and recycle. No hover events in M3; the cursor is a Compose dot driven by the hit position.
- **Layer: quad, not cylinder.** Quad is core, meant for UI, and one-sided with its normal on +Z [S 8]. The hit test is a ray-plane test. Cylinder needs another extension enabled and harder hit math for no gain at this size [I]. Size 0.64 x 0.40 m, 1280 x 800 px, opaque (no alpha flag). Pose in `g.space`: centre (-0.55, -0.05, -0.85), yaw +33 degrees to face the origin: about 1 m away, left of the plate and above it [I, numbers for the user check].
- **Blunt restrictions:**
  - **Separate windows don't receive injected touches.** Compose `Dialog`, `DropdownMenu`, `Popup` and bottom sheets open their own windows, and root-view dispatch misses them; Flutter documents the same failure [S 9] [I for Compose]. Grep of `main/ui`: about 220 matching lines in 28 files, plus about 30 text-field lines and 27 activity cast or launcher lines [R]. System-level injection needs `INJECT_EVENTS`, which a sideloaded app lacks [I]. So: no popups in the panel.
  - **No text input.** A virtual display is always unfocused and `InputConnection`s to unfocused views are discarded [S 9]. `XR_META_virtual_keyboard` (`NOTES-vr.md:38`) [R] or cooperative mode can come later.
  - **Layers draw in array order, with no depth against the scene** [S 8] [I for no depth]. Anything in the projection layer behind the panel's outline is hidden, including the ray's last stretch. So the cursor must live inside the panel, and models can't occlude it.
  - **Stopping writers is mandatory.** Before `xrEndSession` on STOPPING no thread may write, and writes outside VISIBLE or FOCUSED are undefined [S 3]. `xr_shim.cpp:278-281` ends the session inside the event poll [R], so it needs a `STOPPING` bit and a separate `nativeEndSession()`, called after `vd.setSurface(null)` on the main thread [S 10]. The wait must be bounded: `onDestroy` joins the render thread on the main thread for 2 s (`VrActivity.kt:54`) [R], so detach before that join [I].
  - **The swapchain handle only allows `xrDestroySwapchain`**: no acquire, wait or release; format, sampleCount, faceCount, arraySize and mipCount must be zero; the producer controls the size [S 3]. Submit the quad only after a first frame is posted, as Wolvic does [S 5].
  - **Colour and orientation are unknown.** Wolvic chains `XR_FB_composition_layer_image_layout` with a vertical flip on Quest ("backend flips layers vertically") [S 5]. Test with a pattern; if flipped, use that extension if listed, else flip in Compose and in the hit math [I].

## 2. View model and state owner (assumptions; M2 owns the design)
`AppViewModel` is per owner and its `init` starts the engine, autosave, autoslice and the MCP server (`main/ui/AppViewModel.kt:28,52-76`) [R]. `VrActivity` loads the scene itself and leaves if the engine is up (`VrActivity.kt:80-126`) [R]. M3 assumes that M2 delivers:
1. exactly one `AppViewModel` or equivalent per process, reachable from `VrActivity`;
2. `state: StateFlow<UiState>` and `scene: SceneController` usable there;
3. a VR feed that pushes scene and selection into `pending` (`VrActivity.kt:39`) [R], replacing `loadScene`.
The panel takes `vm` as a parameter and creates no owner. If M2 lands differently, only the `PanelHost` wiring and step 4 change [I]. Never call `by viewModels()` in `VrActivity` unless M2 says so: a second instance would run `start()` over the shared engine [I].

## 3. OpenXR actions
Action set `orca`. Enable each optional extension only if enumerated. The shim uses `XR_API_VERSION_1_0` (`xr_shim.cpp:125`) [R], so the core 1.1 path `meta/touch_plus_controller` [S 4] doesn't apply; use the extension path [I].
| Action | Type | Subactions | Binding, for both profiles below |
|---|---|---|---|
| `aim` | POSE_INPUT | left, right | `/user/hand/left/input/aim/pose`, `/user/hand/right/input/aim/pose` |
| `select` | BOOLEAN_INPUT | left, right | `/user/hand/left/input/trigger/value`, `/user/hand/right/input/trigger/value` |
| `panel_toggle` | BOOLEAN_INPUT | none | `/user/hand/left/input/menu/click` |
- **Profiles:** always `/interaction_profiles/oculus/touch_controller` [S 4]; also `/interaction_profiles/meta/touch_controller_plus` when `XR_META_touch_controller_plus` is listed [S 11]. `NOTES-vr.md:38` doesn't list it, and that list may be partial [R, I]; read the `XR_EXTENSION` log. Touch Plus is the Quest 3 and 3S controller [S 12]. The runtime may run an app in compatibility mode on any suggested profile [S 13], and Wolvic maps Quest 3 to `oculus/touch_controller` [S 5]. Log `xrGetCurrentInteractionProfile` as `VR_INPUT`.
- **Semantics:** a boolean action on a scalar path gets a runtime threshold, with hysteresis recommended [S 4]. Aim is +Y up, +X right, -Z forward [S 4].
- **Order:** create actions, suggest, `xrCreateSession`, attach once, `xrCreateActionSpace`. In `nativeBeginFrame`, after `xrWaitFrame` (`xr_shim.cpp:340`) [R]: `xrSyncActions`, then `xrLocateSpace(aim, g.space, predictedDisplayTime)`. `XR_SESSION_NOT_FOCUSED` is a success code and all states are inactive [S 4], so check `isActive` and the location valid bits, and draw no ray.
- **Hands: deferred, not cheap.** `XR_EXT_hand_tracking` is listed (`NOTES-vr.md:38`) [R], but joints alone would mean building aim and pinch ourselves [I]. Only `XR_EXT_hand_interaction` is cheap (`/interaction_profiles/ext/hand_interaction_ext`, `/input/aim/pose`, `/input/aim_activate_ext/value`) [S 14], and it isn't in our list [R]. Manifest needs unchecked. The archive also says controllers first (`NOTES-vr-archive.md:100`) [R].

## 4. Ray to plate
- **Frame:** `plateToLocal` maps (x, y, z) mm to 0.001 * (x, z, -y) plus an offset, a pure rotation (`quest/VrMath.kt:62-74`) [R]. So the origin is the existing `localToPlate(out, pose, offset, plateX, plateY)` (`VrMath.kt:77-81`) [R]. For direction, local d = q * (0, 0, -1) = -(2(xz+wy), 2(yz-wx), 1-2(x²+y²)); plate dir = (d.x, -d.z, d.y), normalised [I, derived]. Add `VrMath.aimToPlate`, allocation-free. `plateX`, `plateY` already live on the render thread (`VrActivity.kt:42-43`) [R].
- **`onViewTap` call** (`main/ui/SceneController.kt:412`) [R], on the main thread like the 2D caller (`main/ui/components/Viewport.kt:194`) [R]: `onViewTap(origin, dir, additive = false, part = false, section = null, emptyPlate = plate, pxSize = null, around = emptyList(), bed = null)`.
  - `origin` in plate mm, `dir` normalised as `Camera.ray` gives it (`main/render/Camera.kt:260-276`) [R].
  - `emptyPlate`: don't call `PlateRenderer.plateAt`, it uses the 2D camera (`main/render/PlateRenderer.kt:374-383`) [R]. Compute the z = 0 hit and test bed bounds plus plate origins in a pure function.
  - Multi-select comes from `selectMode` in the panel (`SceneController.kt:437`) [R].
  - Guards: fire only when `tool == Tool.None` and `buildObject == null`; 2D routes the Build workspace elsewhere (`Viewport.kt:192-193`) [R], and a shared tool state could otherwise place measure points [I].
  - Fire once on the press edge, panel hits winning. No per-frame `pick` for hover in M3: it is native work on the store's coroutine [I].
- **Selection to renderer:** `selectedIds` = `obj * INSTANCE_ID_STRIDE + instance` (`Viewport.kt:855-857`, `PlateRenderer.kt:12`) [R]. `selectedIds` is volatile (`PlateRenderer.kt:81`) [R], but push through `pending` for order. Move the formula into a shared function so VR can't drift.
- **Exact rendering changes.** Today `drawSelection` runs inside `drawScene` (`PlateRenderer.kt:662`) and the overlay ends on framebuffer 0 (`main/render/SelectionOverlay.kt:55`) [R].
  1. **Ship this in M3: no mask pass.** In `frameLoop`, next to `contactShadows = false` (`VrActivity.kt:146`) [R]: `renderer.selectionStyle = SelectionStyle.BOX or SelectionStyle.TINT`. BOX draws lines on the bound target (`PlateRenderer.kt:1422-1450`), TINT is in the mesh shader (`main/render/Shaders.kt:330`), and neither touches framebuffers [R]. The default includes OUTLINE (`SelectionOverlay.kt:14`) [R], so this line is required. VR's feed must not forward the 2D style (compare `Viewport.kt:694-695`) [R].
  2. **Add the deferred guard:** `SelectionOverlay.draw(targetFbo: Int, ...)` and `SelectionOverlay.kt:55` binds `targetFbo`; `PlateRenderer` gets `private var targetFbo = 0`, `drawEye` sets it to `fbo` (`:478-486`), `:1149` passes it. Line 98 stays, since line 42 rebinds [R]. Update the comments at `PlateRenderer.kt:475-476` and `VrActivity.kt:145`.
  3. **That guard alone doesn't make OUTLINE correct.** The eye target uses `GL_EXT_multisampled_render_to_texture` (`xr_shim.cpp:186-205`) [R]; binding another framebuffer mid-pass can resolve it, and depth contents become undefined afterwards [S 15]. Depth-tested drawing after line 662 would break, plus an extra per-eye pass and a second mesh draw [I]. A real outline later means splitting `draw` into `renderMask` (before `drawEye` binds at `:483`) and `composite` (at `:1149`). Not in M3.
  4. `SceneEffects`, `Accumulator`, `Supersampler`: no change, all off or bypassed on this path (`VrActivity.kt:146`, `PlateRenderer.kt:478-486`) [R].
- **Ray drawing:** a small `PointerOverlay` in `src/quest` draws a line from aim origin to the panel or z = 0 hit (else 1.5 m) after `renderer.drawEye` (`VrActivity.kt:180`), with `viewProj` rather than `eyeProj` since it is in LOCAL [R, I]. `PlateRenderer` stays untouched. Check GL state it leaves. The panel dot is a Compose overlay.

## 5. Comfort
- Every view change comes from the head. `drawEye` never reads the camera (`PlateRenderer.kt:478-486`); `camera.*` runs only in 2D paths (`:349,378,391,413,460,489-491`) [R], so panel actions can't move the view through it [I].
- **Hold the world transform fixed.** `world` is set once at load (`VrActivity.kt:121-123`) [R]. `selectPlate` only changes state (`SceneController.kt:51`) [R]; a plate change must not re-run `plateToLocal`, or the world jumps [I]. Same for undo, arrange and any scene refresh.
- The panel is world-locked: no follow, tween, billboard or auto-recentre [I]; Meta advises against content anchored in front of the camera and for one main panel, and warns against too-close items and sudden camera motion [S 16]. OS recentre stays the only recentre [S 16]. No pulsing highlights, no selection fly-to [I]. Distance and size go to the user check.

## Implementation order (each step builds in CI; steps 1, 2 and 5 are Kotlin-only)
1. `VrMath.aimToPlate`, `rayPlateZ0`, `plateAtPoint`, `rayQuad` with `PANEL_*` constants, plus tests.
2. `PointerRouter`: pure state machine (press edge, panel or plate, capture while pressed, dead zone against trigger-pull jitter, cancel on focus loss), emitting plain events; plus tests.
3. Shim: action set, gated profiles, `nativeInput(FloatArray)`, `VR_INPUT` log. No behaviour change.
4. Wire taps to `onViewTap`, the feed and `PointerOverlay`, plus the M2 assumption and `VR_PICK` logging. **Blocked by M2.**
5. Rendering: changes 1 and 2 of section 4, in one commit.
6. **Probe.** Shim: enable the extension, create the surface swapchain, quad in `layers[]` after projection, `STOPPING` bit and `nativeEndSession`. First a `lockCanvas` test pattern with corner marks; then Route A, then B, with a trivial Compose panel. Read colour and orientation from a screencap. **Conflict:** M2 edits the same `layers[]` (`xr_shim.cpp:399-418`) [R]; the target order is passthrough, projection, quad. Rebase on M2.
7. `MotionEvent` wrapper over `PointerRouter`; a test panel with a button and a list; log clicks.
8. `VrPanel`: object list, selection readout, select mode, plate switch, deselect, leave VR (M2's button). No popups or text fields. Then `panel_toggle`.
9. Optional: cooperative probe with a trivial 2D activity, logging session state (`xr_shim.cpp:270`) [R]. Then the 2-minute user check.

## JVM-testable (`app/src/testQuest`, 11 tests in `VrMathTest.kt` today) [R]
- Identity pose aims along plate +Y. A ray from the local origin to the plate centre passes through (cx, cy, 0). Dir stays unit. `aimToPlate` agrees with `plateToLocal` on round trip.
- `rayQuad`: centre maps to (640, 400), corners, top-left origin, off-rect, behind, back face. Panel normal faces the origin.
- `plateAtPoint`: multi-plate origins, gap between plates, parallel ray.
- `PointerRouter`: no repeat on hold, drag leaving the panel keeps capture, cancel on focus loss, panel beats plate, no plate tap when tool is set or Build is open.
- Selection id formula. Not testable on JVM: GL, JNI, runtime, Compose on the virtual display, injection. Meta XR Simulator doesn't support GLES (`NOTES-vr.md:32`) [R].

## Risks
- Route A may not work: the buffer format, usage or vsync between SurfaceFlinger and the Quest compositor is undocumented [I]. Route B is the mitigation; its unknown is Compose on a manually driven hardware canvas [I].
- GPU or thermal cost of a second UI path while the plate targets 72 fps, on top of the Preview risk (`NOTES-vr.md:32`) [R, I]. Idle Compose should post no frames [I].
- Quad colour space on a zero-format swapchain: may look washed out or dark [I]. The `GL_SRGB8_ALPHA8` trap is noted for the eye swapchain (`NOTES-vr.md:21`) [R].
- Sharing the `layers[]` and shim lifecycle with M2's passthrough. Merge risk, and the alpha path is untested [I].
- Aiming jitter at a distance; small targets. Use large hit areas in `VrPanel` [I].
- Main-thread stalls delay the panel but not the render thread or head tracking [I].
- GLES remains legacy on Quest (`NOTES-vr.md:32`) [R]. If a Vulkan move ever comes, the Surface path carries over and the renderer doesn't [I].

## Untested or unknown
All of it on the headset. Specifically: Compose in a Presentation on Quest; Route A; `lockHardwareCanvas` with Compose; whether `XR_META_touch_controller_plus`, `XR_EXT_hand_interaction` and `XR_FB_composition_layer_image_layout` are listed; whether cooperative mode keeps the session FOCUSED; panel legibility and placement; whether `adb screencap` includes quad layers (`NOTES-vr.md:37` says both eyes only); whether the activity stays resumed under OS menus. M1 itself hasn't run on the headset (`NOTES-vr.md:14`) [R], so M3 sits on unverified ground.

## Sources
1. Hybrid apps (2026-09-22): https://developers.meta.com/horizon/documentation/spatial-sdk/hybrid-apps-overview
2. Hybrid sample: https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-sample-hybrid ; layers: https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-layers/
3. `xrCreateSwapchainAndroidSurfaceKHR`: https://registry.khronos.org/OpenXR/specs/1.1/man/html/xrCreateSwapchainAndroidSurfaceKHR.html
4. OpenXR spec source, `input.adoc`, `semantic_paths.adoc` (bindings, `xrSyncActions`, aim pose, Touch and Touch Plus profiles): https://github.com/KhronosGroup/OpenXR-Docs/tree/main/specification/sources/chapters
5. Wolvic: https://github.com/Igalia/wolvic (`ui/OffscreenDisplay.java`, `input/MotionEventGenerator.java`, `ui/widgets/UIWidget.java`, `UISurfaceTextureRenderer.java`, `VRBrowserActivity.java:1599-1603`; `app/src/openxr/cpp/OpenXRLayers.h:183-205,239`, `OpenXRSwapChain.cpp:41-51`, `OpenXRInputMappings.h:218`)
6. `DisplayManager` flags: https://developer.android.com/reference/android/hardware/display/DisplayManager
7. Compose attach errors, `ComposeView.android.kt`: https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/ComposeView.android.kt ; Presentation: https://developer.android.com/reference/android/app/Presentation
8. Quad layer and layer order, `rendering.adoc:1143,1414-1430`: https://github.com/KhronosGroup/OpenXR-Docs/blob/main/specification/sources/chapters/rendering.adoc
9. Flutter on VirtualDisplay: https://flutter.googlesource.com/mirrors/flutter/+/HEAD/docs/platforms/android/Virtual-Display.md
10. `VirtualDisplay.setSurface`: https://developer.android.com/reference/android/hardware/display/VirtualDisplay
11. `XR_META_touch_controller_plus`: https://github.com/KhronosGroup/OpenXR-Docs/blob/main/specification/sources/chapters/extensions/meta/meta_touch_controller_plus.adoc ; https://developers.meta.com/horizon/documentation/native/pc/native-touch-plus-controllers
12. Touch Plus devices (help page, Italian edition): https://www.meta.com/it-it/help/quest/163579713458590/
13. Actions and bindings: https://developers.meta.com/horizon/documentation/native/android/mobile-openxr-actions-actionsets-bindings/
14. `XR_EXT_hand_interaction`: https://github.com/KhronosGroup/OpenXR-Docs/blob/main/specification/sources/chapters/extensions/ext/ext_hand_interaction.adoc
15. `EXT_multisampled_render_to_texture`: https://registry.khronos.org/OpenGL/extensions/EXT/EXT_multisampled_render_to_texture.txt
16. Comfort: https://developers.meta.com/horizon/design/comfort/
