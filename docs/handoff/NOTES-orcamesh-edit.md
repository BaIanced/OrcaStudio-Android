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

## Progress
- **M1a done 2026-10-30 (Claude):** `jni/MeshEdit.hpp` kernels: `face_neighbors`, `face_regions` (crease flood; angle <= 0 =
  Triangles mode), `creases`, `pick` (Face = region verts + border edges, Edge = whole crease polyline between the two
  regions nearest the hit, Vertex = corner on >= 3 regions within reach else nearest vertex of the hit face), `toggle`
  (tapping an all-selected pick removes it), `grow`, `centroid`, `selection_normal`. Tests: `android/core/tests/mesh_edit_test.cpp`
  (`wsl -e bash -c "cd android/core/tests && g++ -std=c++17 -Wall -Wextra -I../jni mesh_edit_test.cpp -o /tmp/met && /tmp/met"`), pass.
- **M1b native done 2026-10-30 (Claude, uncompiled: no local native build, CI compiles it):** `SculptSession.hpp` (struct moved out
  of OrcaSculpt.cpp, plus `hit()` / `touch()` methods and edit fields), `OrcaEngine::sculpt_patch(faces)` factored out of
  `sculpt()`, `jni/OrcaMeshEdit.cpp` `mesh_edit` (ops select / clear / grow / move / end; row-major world matrix about the
  drag-start pivot; regions cached per angle, cleared at end; overlay = crease edges with both ends selected, cap 4000),
  `meshEdit` in EngineCalls (+ no-flush list; null ray/matrix = absent), Kotlin `Engine.meshEdit` + `MeshEditStep` (compiles).
- **M1b MCP done 2026-10-30 (Claude, Kotlin compiles):** `mesh_edit` in McpTools.kt (`meshEdit()`, `editMatrix()` = T*Rz*Ry*Rx*S
  row-major, scale in percent; target remembered in `meshEditTarget` for ray-less calls; transform = move + end in one `plateEdit`).
- **M1b WSA VD check 2026-10-30 (Claude, local WSL native build `corebuild.sh` compiles; CI run 41015631540 green):**
  20 mm cube: top face select (4 verts, normal +Z) + move [0,0,5] -> 20x20x25 OK; edge pick (2 verts), grow (7), ray-less move
  of the kept selection, scale [50,50,100] + rotate z45 -> 20.071 mm footprint (matches the math). Vertex pick at an exact corner
  ray missed the object (ray on the boundary; retest slightly inside).
- **M1b next:** plate undo after mesh_edit is UNTESTED: the VD Undo at [15,717] was the shape builder's (design_set opened it;
  it undid the design and the plate object with it). Close the builder (Prepare tab, find its bounds in the ui dump), then
  select + move, tap Prepare's Undo, reselect: centroid must be back. Also: `meshEditTarget` goes stale after undo / delete
  (indices may point at another object): clear it when the scene's object count or mesh version changes, or re-pick per call.
  mcpao `plate_describe` replies looked cut (~736 bytes, "2 object(s)" listing 1): check mcpao vs the tool. Kotlin UI = M1c.
