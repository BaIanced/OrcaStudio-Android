# Push/pull tier 2: face ops on MESH shapes (design, Claude 2026-10-29; data layer done 4425889)

Origin: archive "(user, 2026-10-21, wishlist) Push/pull" (tier 2 plan). Tier 1 (box faces) was folded into resize on
2026-10-29 because "it just resizes". Tier 2 has to show what resize can't do. On a real mesh (bracket tab, imported
part with holes) it lengthens ONE flat region and leaves the holes alone. On a box-shaped mesh it equals resize, so
don't demo it on a cube.

## Scope
- MESH shapes only. Since build continuity part 2, any plain plate object opens in the builder as MESH shapes
  ("Edit in builder" -> `ScadController.editMesh`), so this covers every imported model. Primitive flat faces = resize.
  Code-only (OpenSCAD) designs: later, same wrapper at code level.
- Flat faces only (coplanar region). Curved = sculpt, not CSG.

## Data (ShapeModel.kt)
- `Shape.faces: List<FaceOp>` (JSON "faces"). `FaceOp(origin: D3, normal: D3, u: D3, outline: List<List<P2>>, d: Double)`.
  The frame is in the MESH FILE's own coordinates (before the bounds translate/scale in `primitive` MESH, ShapeModel.kt:781).
  outline = outer loop + hole loops in (u, n x u) plane coords. d > 0 push, d < 0 pull.
- Emission (MESH branch): `difference() { union() { import(...); <pushes> } <pulls> }` inside the existing scale/translate.
  Push: `multmatrix(F) translate([0, 0, -0.01]) linear_extrude(d + 0.01) polygon(pts, paths)`. Pull: `multmatrix(F)
  translate([0, 0, d]) linear_extrude(-d + 0.01) ...`. The 0.01 overlap avoids coplanar seams (same trick as the 0.02 hole growth).
- Bounds: a push can grow the mesh box. Recompute MESH_BOUNDS = file bounds + prism corners, size = scale x new extents,
  and solve pos so the unmoved geometry stays put (pivot is the box middle: use `placement()`; JVM test "untouched vertex
  keeps its design position after a push on a turned, mirrored MESH").

## Face pick
- `engine.pick(origin, dir, only = buildObject)` -> volume -> piece (`volumeShapes`) -> hit point -> design
  (`BuildTransform.toDesign`) -> shape frame (inverse of parentFrame x placement x scale x bounds translate) = file coords.
- Kotlin STL loader (extend `scad/Stl.kt`, cache per file; files are `mesh-<sha1>.stl`, content-stable): find the triangle
  under the point, flood fill edge neighbours with |n.n'| > cos 0.5 deg and the same plane offset (0.01 mm), boundary edges
  -> loops (largest = outer, rest = holes).
- Re-push: if the hit lies on an existing op's cap plane (origin + n d) inside its outline, the drag edits that op's d
  (no op stacking on caps). Limit: outlines are baked; fine because mesh files never change under a shape.

## UI (deviation room: pre-approved)
- Selected MESH shape: toolbar/long-press "Push/pull face" mode (MESH only, so it can't be confused with resize).
  Tap a flat face = outline drawn on it + one arrow handle along its normal; drag = d in 0.5 mm steps, label "+6.0 mm";
  mesh re-renders on release (live: outline prism as overlay lines only).
- Shape panel: "Face edits" list (distance field, delete per op). One undo step per drag.

## Tests
- JVM: flood fill on an L-bracket STL fixture (tab face = 1 region, outline 4 pts; face with a hole = 2 loops);
  toScad emission; bounds/pos invariance (above).
- WSA VD: import an L-bracket with a hole, Edit in builder, push the tab 10 mm: tab longer, hole round and unmoved.

## Done (2026-10-29, 4425889, JVM only; suite 264/264)
- `scad/FaceOps.kt`: `FaceOp` (json, `at(p, t)`), `FaceOps.scad` (push = union, pull = difference with `offset(delta = 0.01)`
  so cut walls don't share planes), `FaceOps.bounds`, `FaceOps.capAt` (re-push), `FaceOps.region` (seed triangle, weld
  1e-4, flood fill 0.5 deg / 0.01 mm, boundary loops, straight-run corners dropped, largest loop first).
- `Stl.triangles(file)` (binary + ASCII, LRU 2 per path). `Shape.faces` (JSON "faces"), MESH emission wraps the import.
- `ShapeDesign.withFaces(id, faces)`: keeps file bounds in `FILE_BOUNDS` (fx0..), MESH_BOUNDS = file + push prisms,
  same scale, pos solved so untouched file points stay put; empty faces restores. `meshToParent` / `parentToMesh`.
- `FaceOpsTest` (5): bracket end/tab-top regions (4 pts), plate with hole (2 loops), emission, capAt, invariance under
  3 turn/mirror cases incl. z-mirror, binary STL.
- Untested: OpenSCAD actually rendering the emitted code (needs a device / CI run), the pull's 0.01 grow on real meshes.

## Done 2 (2026-10-29, JVM 6/6 FaceOpsTest; UI not wired yet)
- `ShapeDesign.designToMesh / meshToDesign(id, p, copy)`: design point <-> file point through parentFrame + own repeat copy.
- `ScadController.faceAt(obj, origin, dir)` -> `FacePick(shapeId, index (-1 = new), op, copy, copies, outline (plate, at cap), normal (plate per file mm))`:
  engine.pick -> hitAt -> innermost MESH on the path -> capAt (re-push) else Stl + region (IO thread). Untested on device.
- `ShapeController.faceAt(origin, dir, result)` (sets buildCopy / buildGroupCopies), `pushFace(id, index, op, d)` (d = 0 removes; one
  commit = one undo step; `held` keeps groups put), `removeFace(id, index)`.

## Done 3 (2026-10-29, compiles; untested on device)
- Shape panel (MESH): "Push/pull a face" chip (`ShapeController.faceMode` / `setPushPull`) + hint; "Face edits" list (NumberField d in
  file mm, delete) -> pushFace / removeFace (`ShapeBuilder.FaceEditor`).
- Face mode: a main-view tap -> `pickFace` (`facePick` flow; selects the face's shape). `PlateView.faceArrow` (FaceArrow base = outer
  outline centroid, normal, d) + `faceArrowScreen` (56 dp arrow); a touch within 24 dp of the arrow drags it (`startFace` / `faceAt`:
  travel along the projected normal, plate distance snapped 0.5 mm, 0.1 with Shift, tick haptic) -> `onFacePush(d, done)`;
  release -> `pushPicked(d)` (one undo step; the pick follows the cap). Overlay in Viewport: outline + arrow + "+6.0 mm" label.
  Undo/redo and removeFace clear the pick; leaving the build workspace turns the mode off.

## Next
1. WSA VD test: L-bracket with a hole (make one in the builder, export or use an STL), Edit in builder, chip on, tap the tab end, drag
   the arrow 10 mm: tab longer, hole round and unmoved; Face edits shows +10; undo = one step. Check the OpenSCAD render of the
   emitted code (first real run) and the pull's 0.01 grow.
