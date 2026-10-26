# Open-source CAD reuse plan for detail modeling (Claude, 2026-10-25, not started; needs user go)

Goal: finer detail than primitives + booleans (rounded edges, blends between shapes, sweeps/lofts,
bend/twist, playdough sculpting) without writing code. Roadmap items 3-6 in HANDOFF-BACKLOG.md.

## What we already have (checked in source, 2026-10-25)
| Code | Where | License | Gives us |
|---|---|---|---|
| Upstream Orca 2.5 CAD kernel, ~9.6k lines, no GUI deps | `src-orca/src/libslic3r/CAD/` (CadDocument, GeometryEngine, Sketch*) | AGPL-3 | Feature tree: Extrude, Revolve, Sweep, Loft, Fillet, Chamfer, Shell, Hole, Thread, Helix, Draft, Pattern, Mirror, Boolean, Thicken (`CadDocument.hpp:26`); `GeometryEngine::apply_fillet/apply_chamfer/tessellate/mesh_to_brep`, edge/face picking helpers (`GeometryEngine.hpp:96-191`) |
| OCCT | already built for Android (`android/deps/CMakeLists.txt:160`) | LGPL-2.1 | B-rep kernel; only the ModelingAlgorithms module is off: `-DBUILD_MODULE_ModelingAlgorithms=${SLIC3R_CAD}` (`src-orca/deps/OCCT/OCCT.cmake:53`) |
| Manifold (in libopenscad.so) | `android/openscad/CMakeLists.txt:166`, rev 0edd9d5 | Apache-2 | `Warp` (bend/twist/taper), `RefineToLength`, `SmoothByNormals`, `MinkowskiSum`, `LevelSet` (SDF to mesh), `Simplify` (manifold.h:105-260) |
| OpenVDB + `OpenVDBUtils` | linked in core (`android/core/CMakeLists.txt:89`) | MPL-2 | mesh_to_grid / grid_to_mesh: offset, smooth blend (closing = dilate then erode) between any meshes, incl. imported STLs |
| libigl | linked in core (`android/core/CMakeLists.txt:50`) | MPL-2 | arap, biharmonic/harmonic deformation, bbw, cotmatrix (laplacian smoothing), loop/upsample subdivision, decimate |
| CGAL | linked in core | GPL-3 / LGPL | Polygon Mesh Processing: isotropic remesh, smooth, hole fill, repair |
| 3MF recipe round-trip | `Model::cad_recipe` -> `Metadata/orca_cad.bin` (`Model.hpp:1699`) | | designs made on Android could stay editable in desktop Orca 2.5's Design tab (untested) |

## Outside code worth taking
- **SculptGL** (stephomi/sculptgl, MIT, JS): clean brush set (grab, smooth, inflate, crease, flatten, pinch)
  plus dynamic topology. Port the algorithms to C++ for the sculpt mode (item 6). Blender sculpt (GPL-2+) only as a reference.
- **BOSL2** (BSD-2, OpenSCAD lib): rounded cubes/cylinders, offset_sweep, path_sweep, skin (loft). Users can already add it
  (`ScadService.kt:73`); bundling it would give the shape builder rounded edges with zero native work, but phone CSG of
  rounded shapes is slow. Fallback only if the OCCT spike fails.
- build123d / CadQuery (Apache-2, Python on OCCT): API design reference for sweep/loft/fillet selection, not code.
- Skip: Fornjot / Truck (Rust, immature), FreeCAD (LGPL, too big, Qt).

## Plan (each phase ends on a WSA check; one small step per session)
0. **OCCT spike** `Opus 5.5 (medium; high after 2 failed CI tries)` (1-2 sessions). Set `SLIC3R_CAD ON` in `android/deps/CMakeLists.txt:35` + `android/core/CMakeLists.txt:38`,
   add the SLVS dep (not in the Android deps list yet), rebuild deps on CI. Measure APK growth (guess +10-25 MB, unmeasured)
   and CI time. One JNI call: box -> `apply_fillet` all edges -> `tessellate` -> new object. **Go/no-go: size and build OK.**
1. **Rounded edges + shell in the shape builder** `Opus 5.5 (high)`: B-rep edge picking, fillet failures (item 5, part 1). Shape builder shapes map to `make_primitive` +
   Boolean; add per-shape "Round edges" / "Chamfer" slider (all edges, or tap an edge via `edges_of`/`edge_index_of`).
   Fillets between shapes = union in B-rep, then fillet the new intersection edges. Keep OpenSCAD for code / AI paths.
2. **Sweep, loft, revolve, helix/thread** `Opus 5.5 (medium)` (item 4) from CadDocument. Touch UI: draw a profile on the existing sketch
   plane, pick a path or a second profile. Write `cad_recipe` into the 3MF; check it in desktop Orca 2.5.
3. **Deformers bend/twist/taper** `Opus 5.5 (medium)` (item 3): mesh side, so they also work on STLs. Refine to edge length, then warp
   vertices in core (same math as Manifold `Warp`; libigl `upsample` for density). Slider + axis gizmo.
4. **Smooth blend for meshes** `Sonnet 5.5 (medium)`: OpenVDBUtils does the work (item 5, part 2): OpenVDB closing on a union of mesh shapes / imported STLs (radius slider);
   voxel size bounded by the object size so phones stay fast.
5. **Sculpt mode** `Opus 5.5 (high)` (item 6, the "playdough" wish): SculptGL brush ports in C++ on an indexed mesh with the libslic3r
   AABB tree for hits, CGAL remesh before sculpting, libigl laplacian for smooth; result is a plain mesh, repaired before slicing.
   Reuses the GL view and paint-brush touch handling. Biggest item.

Risks: APK size (OCCT toolkits), OCCT fillet failures on complex unions (show the error, keep the unfilleted shape),
B-rep params are lost once an object is baked to a mesh (deformers, sculpt).
