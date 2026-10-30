# Push/pull tier 2: face ops on MESH shapes (design, Claude 2026-10-29; not started)

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
