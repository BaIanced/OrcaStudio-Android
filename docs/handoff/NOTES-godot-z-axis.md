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

## Plan
1. Z handle: draw a dot above the selection box top (`ZRail.gap`), hit-test it before the body drag at DOWN :436; drag = `liftMm` math
   -> `renderer.dragOffset = (0, 0, dz)`; UP -> new `PlateListener.onLifted(dz)` (default no-op) -> Build: moveOnPlate with dz; slicer: per above.
2. Axis lock: `axisLock: Int` (-1 free, 0 X, 1 Y, 2 Z); in `dragOffsetAt` zero the other axis; Z switches to the lift math.
   Keys X/Y/Z toggle during a drag; a lock button (Compose overlay near the selection tools) cycles Free/X/Y. Axis guide line in the lock colour.
3. JVM test for the lock/lift math (ZRailTest.kt exists), WSA debug test, then CI.
