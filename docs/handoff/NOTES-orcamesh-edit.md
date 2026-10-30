# OrcaMesh edit mode: vert / edge / face editing (design, Claude 2026-10-30; nothing built)

Origin: archive "(user, 2026-10-21, idea, discuss) OrcaMesh" + queue (user 2026-10-29). The sculpt half is done (CAD 5a/5b,
`OrcaSculpt.cpp`); this is the Blender "Tab" half. Design deviations below are pre-approved (user 2026-10-21); listed so the
user can veto them.

## Where it lives (decision)
- A Prepare tool `Tool.MeshEdit` beside `Tool.Sculpt` (`ui/AppState.kt:33`), on one part (volume) of a plate object.
  Destructive like sculpt: the part becomes a plain mesh (painting dropped when topology changes, as sculpt does,
  `OrcaSculpt.cpp` `sculpt_finish_locked`). Builder designs stay parametric until edited; "Edit in builder" still opens the
  result as a MESH shape. Rejected: doing it inside the builder (every vertex drag = an OpenSCAD re-render; the CSG
  wrapper trick of push/pull tier 2 can't express vertex moves or loop cuts).
- Native session like `SculptSession`: mesh copy + topology + hit tree kept between calls, live drags send triangle patches
  into the GL buffer (reuse `sculpt_copies` / patch path), written back at drag end, `push_undo()` once per op. New files
  `jni/OrcaMeshEdit.cpp` + `jni/MeshEdit.hpp` (kernels, standalone, JVM-free unit test target like MeshSculpt.hpp).

## Selection (deviation from Blender: feature level by default)
STLs from CAD have flat faces split into many triangles and no quads, so raw Blender selection is fiddly on a phone.
- Chips Vertex | Edge | Face. Granularity: "Features" (default) or "Triangles" (raw, for low-poly meshes).
- Face = crease-bounded region (flood fill under an edge angle, the same rule as Paint's `edgeAngle` / `region_edit`).
- Edge = crease polyline between two regions (reuse `crease_lines` logic, `OrcaTools.cpp:831`); Triangles mode = one mesh edge.
- Vertex = nearest feature corner within 24 dp of the tap, else nearest mesh vertex.
- Taps toggle (multi-select without Shift); chips All / None / Grow. Overlay: region outlines, edge polylines, vertex dots,
  sent in world mm and drawn like push/pull's outline (`PlateView.faceArrow`).

## Ops and phases (each ends on a WSA VD check)
- **M1 Move / Scale / Rotate** `Opus 5.5 (high)`: session, picking, selection overlay, the existing gizmo on the selection's
  centroid (face: arrow along the region normal first). Vertices only move: live patch like sculpt. Neighbour triangles
  stretch. Undo = one step per release.
- **M2 Extrude + Inset (faces)** `Opus 5.5 (medium)`: extrude = duplicate the region's vertices, walls along its boundary
  (stays closed), live distance arrow; d < 0 = pocket. Same result as push/pull tier 2 for flat outward pushes, but works on
  curved regions and leaves no CSG. Inset (flat regions): boundary loops -> ExPolygon in the plane, libslic3r `offset(-t)`,
  retriangulate ring + inner (`triangulate_expolygons`); curved regions: Blender-style per-vertex inset later.
  No edge/vertex extrude: on a closed part it makes non-manifold geometry (not printable).
- **M3 Loop cut -> "Cut ring"**: triangle meshes have no edge loops, so the ring is where a plane crosses the connected band
  under the tap: plane through the tap, normal = the tapped edge direction or a picked axis; split crossing edges (the
  `refine_with_predicate` split code); the new ring becomes the edge selection, ready for Scale (waist / bulge).
- **M4 Bevel (edges)** `[Opus]`: polyhedral parts only (both sides of every selected edge flat). Mesh -> B-rep
  (`GeometryEngine::mesh_to_brep`, CAD kernel) -> `ShapeUpgrade_UnifySameDomain` (as `OrcaBlend.cpp:129`) -> match the
  selected crease edges to B-rep edges by midpoint -> `BRepFilletAPI_MakeFillet` / `MakeChamfer` -> tessellate. OCCT handles
  corners, which is the hard part of Blender's bevel (~8k lines). Limits: refuse past ~20k triangles (sewing time, unmeasured);
  curved parts get "bevel needs flat sides". Fillet failure = error toast, part unchanged.
- After every op: CGAL `self_intersections` check already used by Check mesh (`OrcaTools.cpp:23`); warn, don't block.

## Not doing (say so if asked)
- Edge/vertex extrude, knife, bridge, merge-by-distance UI (Repair already covers merge), quads/n-gons.
- CGAL Euler ops: our indexed mesh + own topology (MeshSculpt `Topology`) is enough and avoids conversions per drag.

## MCP
- `mesh_edit` tool (select by ray / region index, op + amount) so Claude can test on the WSA VD without taps, like `object_sculpt`.
