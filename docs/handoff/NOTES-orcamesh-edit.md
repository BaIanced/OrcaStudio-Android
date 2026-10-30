# OrcaMesh edit mode: vert / edge / face editing (design, Claude 2026-10-30; M1-M3 built)

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
- **M1b done 2026-10-30 (Claude, WSA VD, debug build on the local native lib):** `meshEditTarget` now carries the scene
  mesh version (`meshEditVersion`); a ray-less call after undo / delete / any other edit says "select again". Cube top face
  +5 then +1 (ray-less chain) -> 26 mm; Prepare Undo (VD tap 27,78) -> 25 (one step per op), ray-less call refused; second
  Undo -> 20, reselect centroid z 20. Vertex pick 1 mm inside the face -> nearest corner OK (its normal is triangulation-biased,
  [-0.33,-0.67,0.67]: fine for now, only face moves use the normal). Cut MCP replies were `mcptest.sh` `MAX=700`: pass `MAX=4000`.
- **M1c slice 1 written 2026-10-30 (Claude, Kotlin compiles, UNTESTED on device):** `Tool.MeshEdit(kind, triangles)` (More menu
  "Edit mesh"), panel chips Vertex | Edge | Face + Triangles (angle 0, else 30), None / Grow + count (`ToolPanel.MeshEditOptions`);
  `InteractionMode.EDIT` (taps to host, drags orbit, face-arrow drag allowed); `SceneController.meshEditTap/Op/Drag`
  (`meshEditSel` flow, version-checked; drag = coalesced live "move" translate along the normal + "end" on release, patches
  via `meshPatches`); Viewport overlay shares push/pull's Canvas (crease edges + vertex dots shifted with the drag, arrow at the
  centroid via `PlateView.FaceArrow`). Deviations: no "All" (no native op yet); arrow-along-normal only, no 3-axis gizmo yet.
- **M1c slice 1 verified 2026-10-30 (Claude, WSA VD, debug build; script `~/orca-android-tools/wsa-meshedit-test.sh`):** More >
  Edit mesh on the 20 mm cube (moved to [40,40]: the tall Shape design occluded it, a tap there edits that object instead);
  face tap 4 verts, arrow drag 20 -> 26.5 mm (no orbit), one Undo -> 20, off-arrow drag orbits; Edge tap 2, Grow 7, None 0,
  Vertex tap 1. Fix: the panel count kept "4 vertices selected" after undo (stale selection); now version-checked like the
  overlay. **Not seen:** the Compose selection overlay and arrow (view_screenshot draws only GL; no VD shot on WSA).
- **M1c slice 2 done 2026-10-30 (Claude, WSA VD, debug build on the local native lib):** native op `all` (+ MCP `op:"all"`),
  panel "All" button (needs a tapped part first) and typed relative Move (mm) / Rotation (deg) / Scale (%) fields + Apply
  (`SceneController.meshEditTransform`, one undo step, fields reset after Apply; shared `core/MeshEditMatrix.kt`). Cube: top
  face Move Z 5 -> 20x20x25; All + Scale X 50 -> 10x20x25; Undo -> 20x20x25; top face Rotate Z 45 -> 28.284 footprint (20*sqrt2).
  Deviation: relative amounts + Apply instead of Blender's live G/R/S; no 3-axis gizmo. CI not run yet (batch with M2).
- **M2 native written 2026-10-30 (Claude; kernel unit-tested, native compile = local corebuild, device UNTESTED):** `MeshEdit.hpp`
  `split_cap` (cap = faces with all 3 verts selected; border-edge verts copied, cap remapped, wall quad per border edge; also
  per-copy inset direction = mean inward edge direction / min cos, capped x4). Test: cube top extrude +0.5 -> vol 1.5, pocket
  -0.25 -> 0.75, inset 0.1 -> copies 0.1 in, all closed. `mesh_edit` ops `extrude` (matrix; split + commit + session rebuilt
  with the cap selected, then the move path: later `move` calls drag it, `end` closes; an end at zero height restores the
  undo snapshot and the old selection) and `inset` (`amount` mm, new arg through EngineCalls; ends in one call).
  **Deviation:** inset is Blender-style per-vertex on all regions (no ExPolygon offset/retriangulate): same topology as
  extrude, works on curved regions; large insets on concave outlines can fold (no clamp yet).
- **M2 Kotlin/MCP/panel written 2026-10-30 (Claude, Kotlin compiles, local native lib):** `Engine.meshEdit(amount)`, MCP `op:"extrude"|"inset"`
  + `amount` (extrude = zero-height extrude for the normal, then move + end), panel Extrude chip (arrow drag extrudes:
  first step sends `extrude`, applies its scene + selection) and Extrude / Inset (mm) fields + Apply (`SceneController.meshEditFaces`).
  **MCP verified on WSA VD:** cube top extrude +5 -> 25 mm, inset 3, extrude -4 (pocket) -> vol 9216 (exact), side face +6 -> 12216
  (exact), all "No problems found"; inset 0 / extrude without amount refused.
  **Panel arrow-drag extrude failure solved 2026-10-30 (Claude, WSA VD):** not the panel. A plain cube top drag-extrude worked
  (20 -> 26.5, 20 faces, vol exact); the bad case was a push *into* the part where the border's outside faces run along the
  extrude direction (pocket wall, cube side): the walls lay inside those faces (MCP repro: side face -2 -> 95 self-intersecting
  pairs, width unchanged). Fix: `split_cap(m, sel, slide)` (extrude passes the selection normal): a border vertex whose outside
  faces all have |n.dir| <= 0.02 keeps no copy, those faces stretch (push/pull style); edges with one kept end get a triangle.
  Deviation from Blender: extruding a cube top is now a plain move (no edge loop at the old height); after an inset the ring
  is flat, so walls stay. Unit tests added (slide no-copy + shorten, inset ring keeps 4 copies / 8 walls), pass.
  **WSA VD verified (debug, local native lib):** MCP top -3 -> 17 mm 12 faces; side -2 -> 18 wide; inset 3 + extrude -5 -> 5280
  (exact); pocket wall -2 / +4 -> 5140 / 5420 (exact); all "No problems found". Panel: typed Extrude 3 / Inset 4 / Extrude -5
  Apply exact (11800 / 11800 / 11080); arrow drag down on a fresh cube top 20 -> 17 clean, one Undo -> 20.
- **M3 written 2026-10-30 (Claude; kernel unit-tested, Kotlin compiles, native compile + device UNTESTED):** `MeshEdit.hpp`
  `cut_ring(m, start, point, normal, eps)` (band = crossing faces flooded through shared vertices from the hit face, or a
  crossing face round it; crossing edges split once, faces retriangulated; near-plane vertices snap onto it). Tests: cube cut
  z 0.3 -> ring 12, 48 faces, closed, vol 1; waist scale closed; start on top found; z 0.5 through side middles -> ring 8;
  miss -> empty. Native op `cut` (ray + world `axis`, default Z; new `axis` arg through EngineCalls / `Engine.meshEdit`; one
  undo step, ring selected) and MCP `op:"cut"` + `axis` (+ optional scale/move/rotate on the ring in the same call).
- **M3 done 2026-10-30 (Claude, WSA VD, debug build on the local native lib):** MCP cube cut z 10 + scale [50,50,100] -> 28 faces,
  vol 4667 (two frustums 20^2 -> 10^2, exact), "No problems found"; X cut through slanted waist faces, Z cut at z 3 + scale 120,
  diagonal axis [1,1,0] cut: all closed, no problems. Panel: "Cut ring" chip + X | Y | Z (default Z); a tap cuts (28-vertex
  ring, 190 faces, closed); one Undo -> 134 faces, selection cleared. A tap on a face lying in the cut plane says "crosses
  nothing, try another axis" (correct). Fix: ring edges in flat faces aren't creases, so the overlay showed dots only; the
  session keeps the cut's ring (`edit_ring`) and, while it is the selection, the overlay draws its mesh edges.
  Deviation: world axes X/Y/Z only (no "tapped edge direction" axis). CI run 41100791348 (M3) dispatched.
- **M4 written 2026-10-30 (Claude; native compiles locally (corebuild), Kotlin compiles, device UNTESTED, not pushed):**
  `OrcaMeshEdit.cpp` ops `fillet` / `chamfer` (`amount` = radius / size mm): creases with both ends selected (face selection =
  its border); both regions must be flat (0.5 deg) and closed, <= 20k triangles; `mesh_to_brep(tol 1e-5 diag, merge 0.5 deg)`,
  crease midpoint -> nearest B-rep edge (within 10 tol), MakeFillet / MakeChamfer, `tessellate(0.02/scale, 0.3)`, one undo
  step, painting dropped, drop to bed, selection cleared. MCP `op:"fillet"|"chamfer"` + amount (optional ray selects first).
  **Next:** local debug build (`wsa-debug-rebuild.sh native`) + WSA VD MCP check: 20 mm cube top face fillet 2 (vol should be
  8000 - 4*20*(4-pi)... compute: 4 edges x (r^2 - pi r^2/4) x 20 = 274.6 less, corners aside), edge chamfer 2, cylinder refused,
  Check mesh clean; then panel Fillet / Chamfer fields + Apply.
