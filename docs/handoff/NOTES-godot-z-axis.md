# Godot review (a) Z drag handle + (b) axis lock: source map (Claude, 2026-10-28, nothing built)

Spec: backlog "Godot review". One implementation in `render/PlateView.kt` covers both views: the shape builder
IS the Build workspace on the main view (`vm.state.value.buildObject != null`), not MeshPreviewView any more.

## Where the body drag lives (PlateView.kt)
- DOWN :436 starts a body drag when the ray hits `selectedBounds` (anchor `dragAnchor`, `dragStart = dragAt()` :979);
  towers :443. MOVE :575 -> `dragOffsetAt()` :934 -> `renderer.dragOffset = (x, y, 0)`. UP :617 -> `listener.onMoved(dx, dy)`.
- Shift = fine (0.1 mm) and Ctrl = flip snap, read from `event.metaState` at :575/:618. Keep Shift = precision (spec);
  so axis lock on keyboard = X / Y / Z keys during a drag (Blender style), not Shift. Keys reach the view via `onKey` :304.
- Snap: `ZRail.lift()` (render/ZRail.kt) per axis with `dragSnap` (Build workspace only); guides `snapGuides()` :953.
- Two-finger POINTER_DOWN :456 cancels the drag (pan/pinch); touch lock = an on-screen lock button (spec), not a 2nd finger.

## Live preview path (already 3D)
- `PlateRenderer.dragOffset` is a vec3: mesh `uDrag` (PlateRenderer.kt:942), lines `uOffset` :1268, points :1564, box :991.
  BUT `dragging` at PlateRenderer.kt:535 tests only [0]/[1]: add [2] or a z-only drag won't draw as dragging.
- Viewport.kt:307 `onMoved(dx, dy)`: Build -> `vm.shapes.moveOnPlate(dx, dy)` (ShapeController.kt:125, plate -> design frame
  via the copy's rotation/scale/mirror; dz needs the r[6+i] row added), else `vm.scene.moveSelected` -> `engine.moveItems(items, dx, dy)`
  (SceneController.kt:128). Engine has no dz: grep how the typed Z field applies (OrcaScene.cpp:691 set_transform, volume >= 0);
  slicer-view objects drop to the bed in Orca, so Z there is meaningful for parts (Alt-tap) / design shapes; decide object behaviour (likely: parts only, or keep "drop to bed" off-limits = handle hidden for whole objects).
- Old shape-builder Z rail (MeshPreviewView.kt:223/249/279-296/392) has the handle geometry + `liftMm()` projection math
  (screen drag projected on the projected +Z axis, snaps to floor 0 and boxes overlapping in XY). Port that into PlateView.

## Read 2026-10-28 (session 2, nothing edited; don't re-read these)
- PlateView: DOWN branch order :407-454 (paint, push, body drag :436, towers :443): put the Z-dot hit test right before :436
  (24 dp reach like MeshPreviewView.handleAt :254). Gizmo starts (cut/resize/rotate) fire on first MOVE :485-492 and cancel the
  body drag: the Z dot must win over them too (set a `liftDrag` flag at DOWN, skip `startResize` etc. when set).
- MOVE body drag :575 sends `floatArrayOf(o.x, o.y, 0f)`; UP :617-632; `cancelDrag` :917; `dragOffsetAt` :934 (snap via
  `ZRail.lift` per axis, px/mm from `camera.project`); `dragAt` :979.
- `liftMm` (MeshPreviewView.kt:279-296) to port: project handle and handle+10 mm Z, dot the finger delta onto that, `len2 < 16` =
  edge-on (keep last value); targets = floor 0 + min/max z of boxes overlapping in XY; reach 10 dp.
- Handle position: `MeshPreviewView.handles` :249 = box top centre + `ZRail.gap(min, max)`; draw it with the renderer's handle
  dots (`handlePx`, grep how ScaleGizmo handles are drawn in PlateRenderer).
- Host: Viewport.kt:307 `onMoved` (Build -> `vm.shapes.moveOnPlate`, ShapeController.kt:125; slicer -> `vm.scene.moveSelected`
  -> `engine.moveItems` dx/dy only, SceneController.kt:128 / Engine.kt:115). Drag offset cleared on next mesh (Viewport.kt:682).
- Build selection boxes: Viewport.kt:730-742 (`selectedBounds` = design-pose boxes, `dragSnap = vm.shapes.buildSnapBoxes()`);
  slicer: :743-769 (whole instances; Orca keeps objects on the bed, so the slicer Z handle needs an engine check first:
  grep OrcaScene.cpp set_transform / ensure_on_bed. Start with Build only, slicer second).

## Read 2026-10-28 session 3 (nothing edited; don't re-read these, go straight to coding)
- PlateRenderer: `dragOffset` setter :91-94 resets `dragTower` when x,y == 0 (fine for z-only, tower drags are xy). Shadow
  `dragging` :535 -> add `|| dragOffset[2] != 0f`. Handle dots: copy `drawGizmo` :1207-1246 (identity mvp on lineProgram,
  clip = mvp * pos, dark rim quad then colour quad, size `handlePx`); add `var liftHandle: FloatArray?` (world xyz, null = hidden)
  drawn after `drawSelection()` :587 (models && !loupePass), offset by dragOffset[2] while lifting. Colour = ScaleGizmo.color(2).
- PlateView fields :140-161 (`selectedBounds`, `dragSnap` (DragSnap :117 lo/hi/others), `dragAnchor`), density via
  `resources.displayMetrics.density`. DOWN body-drag branch :436; gizmo-start guard :485-493; MOVE `dragging ->` :575;
  UP `dragging` :617-632; `cancelDrag` :917-927; `dragOffsetAt` :934-950 (returns Vec3, z = 0). Listener `onMoved` :46.
- Lift box in Build = `gizmoBox()` :767 (mesh box when `gizmoOnMesh`) / `dragSnap.lo/hi`; targets = floor 0 + `dragSnap.others`
  min/max z overlapping in XY. MeshPreviewView liftMm :279-296 uses `handleValue`/`liftSnap` as "keep" values -> PlateView
  needs `liftMm`/`liftAt` fields.

## Plan
1. Z handle: draw a dot above the selection box top (`ZRail.gap`), hit-test it before the body drag at DOWN :436; drag = `liftMm` math
   -> `renderer.dragOffset = (0, 0, dz)`; UP -> new `PlateListener.onLifted(dz)` (default no-op) -> Build: moveOnPlate with dz; slicer: per above.
2. Axis lock: `axisLock: Int` (-1 free, 0 X, 1 Y, 2 Z); in `dragOffsetAt` zero the other axis; Z switches to the lift math.
   Keys X/Y/Z toggle during a drag; a lock button (Compose overlay near the selection tools) cycles Free/X/Y. Axis guide line in the lock colour.
3. JVM test for the lock/lift math (ZRailTest.kt exists), WSA debug test, then CI.
