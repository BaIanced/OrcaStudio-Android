# Pins (Reshape with pins) - spec

Written 2026-11-15 against d91d55c (plan task T-060). Source roots: `S/` = `android/app/src/main/java/app/orcaandroid/`,
`J/` = `android/core/jni/`. Build tasks: T-061..T-064 (section 12). Everything here is behind the setting `reshapePins`
(Experimental, default off) until the gate T-065.

## 1. What it is

Inside Reshape the user taps the surface to drop **pins**. Dragging a pin moves the material within its radius, with a
smooth blend to zero at the rim. Every pin that is not being dragged **holds its spot**. A label shows the distance.
The case it must solve: widen one side of an insole's toe box by 3.0 mm without moving the other side.

It is a closed-form position field, not a solver: no factorisation, no bind step, cost = vertices x pins. The same
formula runs on a light proxy while dragging and on the full mesh at release.

## 2. Facts the design rests on (checked in source)

| Fact | Where |
|---|---|
| Grab already moves a region by `(1 - d²/r²)²`, absolute from the stroke start, weights and start positions cached per stroke (`grab_w`, `grab_orig`) | `J/OrcaSculpt.cpp:669-729` |
| Grab's drag plane is the plane through the first hit facing the view (`grab_normal = -d`) | `J/OrcaSculpt.cpp:677-678, 712-722` |
| Sculpt refines the **whole part** to radius / 5 with a 400k face budget at a stroke's start, saving and restoring paint around it | `J/OrcaSculpt.cpp:543-585` |
| `meshdeform::detail::refine_with_predicate(m, max_faces, marked(a, b))` splits any marked edge without T-junctions | `J/MeshDeform.hpp:116-118` |
| Live drawing = a patch file of changed face runs, uploaded as sub-ranges | `J/OrcaSculpt.cpp:755-769, 800-846` |
| Engine calls are JSON methods in one table; any method other than `sculpt` / `sculptEnd` / `meshEdit` first flushes a live sculpt | `J/EngineCalls.cpp:106-114, 250-256` |
| Plate undo = `m_undo.push_back(make_shared<Model>(*m_model))`. The copy shares each volume's mesh pointer, so the cost per sculpt stroke is the new full mesh written at the flush, not the snapshot | `J/OrcaScene.cpp:139-146`, `J/OrcaSculpt.cpp:455-458` |
| The focus room keeps undo floors on that deque | `J/RoomUndo.hpp` |
| Self-intersection count by CGAL `self_intersections` on a `Surface_mesh` is linked and used by the mesh check | `J/OrcaTools.cpp:1712-1729` |
| `its_quadric_edge_collapse` is linked (Simplify) | `J/OrcaScene.cpp:1438` |
| Native tests are standalone: header-only code, `g++ -std=c++17 -I../jni x_test.cpp && ./a.out`, no libslic3r, not run by CI | `android/core/tests/mesh_edit_test.cpp:1` |
| The fragment shader gets the world position `vWorld`; uniform arrays of 16 are in use | `S/render/Shaders.kt:59, 85, 106-112` |
| Reshape = `Tool.Sculpt(brush, radius, ...)`; brush ids in `SCULPT_BRUSH_IDS`; opened by `openReshape()` | `S/ui/AppState.kt:68`, `S/ui/prepare/BrushStrip.kt:91`, `S/ui/SceneController.kt:430` |
| Scripts can sculpt through MCP `object_sculpt` | `S/ai/McpTools.kt:194, 964` |
| Test part: `au_insole.stl`, 17,916 triangles, 250 x 89.847 x 7 mm; measuring script `measure_insole.py` | `docs/audit/raw/ui-journeys.md:258-268`, BASELINE line 54 |

## 3. The field

All distances are **world mm**. A vertex is taken to the world by the session's frame (the edited copy's matrix times
the part's), moved, and taken back by the inverse. This keeps a scaled or sheared part correct; the copy's other
instances follow because they share the mesh.

Kernel, for a distance `d` and a radius `r`:

    phi(d, r) = (1 - d²/r²)²   for d < r,   0 otherwise

A drag has a **group** G (the pins being dragged, one or more) with one displacement `delta`, and the **held** set H
(every other pin of the session). Pin `i` has centre `c_i` and radius `r_i`.

    hold radius of a held pin j:   rho_j = min(r_j, min over i in G of |c_i - c_j|)

    W(p) = [ 1 - product over i in G of (1 - phi(|p - c_i|, r_i)) ]  *  product over j in H of (1 - phi(|p - c_j|, rho_j))

    p' = p + delta * W(p)

`p`, `c_i` and `c_j` are the positions **at the start of the drag**; the drag is absolute from there (no drift), as Grab.
After the drag the group's pins have moved by `delta`; the others have not.

Properties (each is asserted by the native test):
- One pin, nothing held: `W = phi`, exactly Grab's falloff.
- `0 <= W <= 1` everywhere: nothing moves further than the finger.
- `W(c_i) = 1` for every dragged pin: the pin lands exactly where it was dragged. This is why `rho_j` is clipped to the
  distance: a held pin never reduces the weight at a dragged pin's centre.
- `W(c_j) = 0` for every held pin: it does not move at all.
- Grouped pins do not push each other down: the first factor is a smooth union, 1 at each of their centres.
- `W` is C1 (the kernel's slope is 0 at `d = 0` and at `d = r`), so there is no crease at any rim.
- Steepest slope: `|grad W| <= k * (sum over G of 1/r_i + sum over H of 1/rho_j)` with `k = 8 / (3 * sqrt(3)) = 1.5396`.

The field is purely spatial: everything inside the ball moves, both skins of a thin part together (wanted for the insole,
7 mm thick). A separate shell that happens to lie inside the ball moves too. That is accepted for v1; the colour wash
shows it before the drag.

Deviation from the audit's wording ("held pins subtract their own weight"): the plain form `1 - phi(d, r_j)` lowers the
weight at a dragged pin's centre whenever that centre lies inside the held pin's radius, so the pin would lag behind the
finger. Clipping the hold radius to the distance fixes that and changes nothing when the pins are further apart than `r_j`.

### Limits
- Radius 1..150 mm. Default for a new pin: the last radius used, first time `clamp(longest bbox side / 4, 5, 60)`.
- At most 16 pins per session (the shader's array size).
- Two pins are never closer than `s_min = max(1 mm, 0.1 * min(r_a, r_b))`. A tap closer than that to a pin selects that pin.

## 4. Drag direction and the number

- Free drag: the ray is cut with the plane through the dragged pin's start centre facing the view (Grab's rule).
  `delta_f` = that point minus the start centre. With a group, the pin under the finger is the reference.
- Axis inference: candidates are world X, Y, Z and the pin's surface normal `n` (the hit face's normal when the pin was
  dropped). A candidate is skipped when it is within 10 degrees of the view direction (`1 - (a.d)² < 0.03`).
  With `|delta_f| >= 1 mm`, the candidate with the smallest angle to `+-delta_f` locks if that angle is `<= 8` degrees; a
  lock is released when the angle to the locked axis passes 12 degrees (hysteresis). Under 1 mm nothing locks.
- Locked: the pin slides on the line `c + t * a`, with `t` from the closest point of that line to the ray:
  `w0 = c - o`, `b = a.d`, `t = (b * (w0.d) - w0.a) / (1 - b²)`, `delta = t * a`.
- The mode is a call parameter: `auto` (inference), `free`, or a fixed `x` / `y` / `z` / `n`.
- The label rides at the pin: `3.0 mm` plus the axis letter when locked. Tapping it opens a number field; the value
  is applied along the locked axis, or along the last drag's direction when free (`pinMove`).

## 5. Refinement

At a drag's start, before the weights are cached: split every edge that is longer than `e` and whose midpoint lies
within `1.05 * r_i` of a dragged pin, with `refine_with_predicate`.

    e = max(r_i / 12, 0.4 mm)        cap: faces before the session's first refine + 200,000

Only the dragged pins' balls are refined (the field is zero outside them). The cap wins on huge parts: the drag then runs
on the mesh as it is. Paint is saved and restored around the refine as sculpt does (`sculpt_save_paint` /
`sculpt_restore_paint`). A refine is not undone by an undo of the drag: the new vertices are edge midpoints, so the surface
is the same.

Faces inside one ball on one skin are about `2 * pi * r² / (0.433 * e²)` = 2,100 at `e = r / 12`, whatever the radius. The
insole (two skins) gains a few thousand faces per pin and stays under 30k.

Faceting: a triangle's sag is about `delta * (e/r)² ` = 0.021 mm for a 3 mm move at `e = r / 12`.

## 6. Live drag, proxy, release

`F` = the part's faces when the session opens.

| Part | While dragging | At release |
|---|---|---|
| `F <= 150,000` | the field runs on the session mesh; the moved faces go out as a patch (sculpt's path) | nothing more |
| `150,000 < F <= 2,000,000` | the part is drawn as a **proxy**: `its_quadric_edge_collapse` to 60,000 faces, made once when the session opens (busy state), refined by section 5 like any mesh. The field runs on the proxy | the same drag is applied to the full mesh held by the session: refine (section 5), then one pass over its vertices. Budget `<= 300 ms` at 1M faces |
| `F > 2,000,000` | as above, after a notice "Large model: reshaping may be slow" (a notice, not a dialog) | as above, no time promise |

- The proxy is correct by construction: the field depends on position only, so the proxy and the full mesh get the same
  displacement with no correspondence step. The proxy is never re-made during a session.
- The full mesh is written into the model once, when the session ends (section 7). The 204-276 ms plate rewrite the audit
  measured is paid once per session, not per drag.
- Frame budget: `<= 16 ms` of engine time per drag call on the 17,916-triangle insole (weights are cached at the drag's
  start; a frame is one multiply-add per vertex in the support plus the patch).
- Pin drops and drags hit-test the mesh that is drawn (the proxy when there is one).

## 7. Session and undo

- `pinBegin` opens a session on one part (as `sculpt_build`); a live sculpt is flushed first. The first drag pushes **one**
  plate undo step (`push_undo()`), which costs no mesh copy.
- Each drag is one **session step**: the moved vertex indices with their positions before (and the same for the proxy),
  and the pins' centres before. Undo restores them; redo re-applies. Memory per step = 28 bytes per moved vertex; the
  session keeps at most 64 MB of steps and drops the oldest beyond that.
- While the session is open, the engine's `undo` / `redo` act on session steps first; with none left, `undo` ends the
  session without changes and falls through to the plate history.
- `pinEnd`, or any engine method outside the `pin*` set (the rule at `J/EngineCalls.cpp:250`), ends the session: the mesh is
  written to the part (`set_mesh`, paint restore, hull job as sculpt), `drop_to_bed`, `commit()`.
- Deviation, stated: after the session has ended, its drags are **one** plate undo step, not one per drag. Per-drag undo
  across the session's end would need either a full mesh copy per drag (what the budget forbids) or a second kind of
  history entry in the plate deque and the room floors. Not worth it for v1.
- Pins are session state only. They are gone after Done (re-opening a warp is "later" in the audit).

## 8. Self-intersection notice

After each release (and at the session's end) a worker counts self-intersections with the CGAL call at
`J/OrcaTools.cpp:1712-1729` on a copy of the drawn mesh (proxy or session mesh), off the engine lock, the way hull jobs run.
A newer release cancels the older job's result. The app asks with `pinCheck` 300 ms after the release, then every 500 ms
while `pending` (at most 10 times). A count above the count at the session's start shows a snackbar "This move makes the
surface cross itself", with Undo. Never a dialog, never a block.

## 9. Engine surface

New methods in the `EngineCalls` table (JSON in, JSON out), wrapped 1:1 in `S/core/Engine.kt`. Points, rays and deltas
are world mm; a ray is `[ox, oy, oz, dx, dy, dz]`. A pin in a reply is `{id, point[3], normal[3], radius, hold}` where
`hold` is its `rho` for the current selection (= `radius` when it is selected or nothing is).

| Method | Arguments | Reply |
|---|---|---|
| `pinBegin` | `object, instance, volume` | `{faces, proxy, proxyFaces, large, pins: []}` (+ scene json when the proxy replaced the part's triangles) |
| `pinAdd` | `ray, radius` | `{hit, existing, pin, pins}`; `existing` = the tap was within `s_min` of a pin |
| `pinSet` | `id, radius` | `{pins}` |
| `pinRemove` | `id` | `{pins}` |
| `pinSelect` | `ids` | `{pins}` (new `hold` values, for the wash) |
| `pinDrag` | `ids, ray, axis, start` | `{patch, mesh_version, delta[3], distance, axis, pins}`; `start` = first call of a drag (refine + cache) |
| `pinMove` | `ids, delta[3]` | as `pinDrag`; a whole drag in one call (number field, scripts) |
| `pinRelease` | none | `{ms, check: "pending"}`; applies to the full mesh when there is a proxy; closes the session step |
| `pinCheck` | none | `{pending, crossings, before}` |
| `pinEnd` | none | scene json |

`undo` / `redo` reply with `pins` and a patch while a session is open.
MCP: one tool `object_pins` (ops `begin, add, set, select, move, release, check, end`) so scripts can run the acceptance.

Code layout: the field, the axis inference and the refine predicate are header-only in `J/MeshPins.hpp` (namespace
`meshpins`, no libslic3r, on `meshdeform::Mesh`). The session (`PinSession`: frame, mesh, proxy, pins, steps) and the
methods live in a new `J/OrcaPins.cpp`.

## 10. Overlay and gestures

- Entry: with `reshapePins` on, "Pins" is the first entry of the Reshape brush list (strip and Sculpt card), id `pins`, and
  the default when Reshape opens. The other brushes stay.
- Tap the surface: a pin drops (dot, 12 dp, primary) and is selected. Tap a pin: selects it alone. Tap further pins with
  the Group toggle on: adds to / removes from the selection. Tap empty space: clears the selection.
- A selected pin shows its radius ring: a circle of the pin's radius facing the camera, with a round handle (touch 48 dp)
  on its right side. Dragging the handle sets the radius (1..150 mm, label in mm). The size slider / strip does the same.
- Drag a selected pin (or any pin of the selected group): the drag of section 4. Unselected pins show a small lock mark.
- Long press a pin: Remove.
- Wash: the fragment shader computes the field from uniforms (`uPinCount`, `uPins[16]` = centre + radius, `uPinHold[16]` =
  rho, `uPinRole[16]` = 1 selected / 0 held). Where `W > 0` the surface is tinted with the primary colour at `0.35 * W`;
  inside a held pin's hold radius a neutral tint at `0.25 * phi(d, rho)`. It costs no buffer and follows a radius drag
  live. No selection: every pin shows its own `phi` in the neutral tint.
- Hold **Compare**: the original shape as a ghost (the x-ray pass of Cut). Optional in T-062; if it does not fit, it goes
  to the backlog.
- Undo / Redo: the room strip's or the toolbar's buttons, as for sculpt. Done ends the session.
- TalkBack: pins are nodes ("Pin 2, radius 50 mm, held"), with actions Select, Remove, and Move by 1 mm along each axis.

## 11. Worked example (the native test's numbers)

Flat grid in the plane z = 0: x and y from 0 to 100 mm, 5 mm steps (21 x 21 vertices, 800 triangles). No refine in the
test (it checks the field). `delta = (0, 0, 3)`.

Case 1, one pin A at (50, 50, 0), `r = 25`:

| Vertex | d | W = (1 - d²/625)² | z after |
|---|---|---|---|
| (50, 50) | 0 | 1 | 3.0000 |
| (55, 50) | 5 | 0.9216 | 2.7648 |
| (55, 55) | 7.0711 | 0.8464 | 2.5392 |
| (60, 50) | 10 | 0.7056 | 2.1168 |
| (65, 50) | 15 | 0.4096 | 1.2288 |
| (70, 50) | 20 | 0.1296 | 0.3888 |
| (75, 50) | 25 | 0 | 0 |

Case 2, A dragged, B held at (70, 50, 0) with `r = 25`: `rho_B = min(25, 20) = 20`.

| Vertex | phi_A | d to B | 1 - phi(d, 20) | W | z after |
|---|---|---|---|---|---|
| (50, 50) | 1 | 20 | 1 | 1 | 3.0000 |
| (45, 50) | 0.9216 | 25 | 1 | 0.9216 | 2.7648 |
| (55, 50) | 0.9216 | 15 | 0.80859375 | 0.7452 | 2.2356 |
| (60, 50) | 0.7056 | 10 | 0.4375 | 0.3087 | 0.9261 |
| (65, 50) | 0.4096 | 5 | 0.12109375 | 0.0496 | 0.1488 |
| (70, 50) | 0.1296 | 0 | 0 | 0 | 0 |

Case 3, A and C grouped, C at (50, 70, 0), `r = 25`, nothing held: at (50, 60) both `phi = 0.7056`,
`W = 1 - 0.2944² = 0.91332864`, z = 2.73998592; at (50, 50) and (50, 70) `W = 1`, z = 3.

Case 4, axis inference, view straight down -Y, candidates X and Z: `delta_f = (0.3, 0, 3)` is 5.71 degrees from Z: locks
Z, `delta = (0, 0, 3)`. `delta_f = (0.5, 0, 3)` is 9.46 degrees: free. `delta_f = (0.05, 0, 0.5)`: under 1 mm, free.

Test tolerances: 1e-9 mm on positions. Also asserted over every vertex of cases 1-3: `0 <= W <= 1`, x and y unchanged,
and a vertex outside every dragged ball has not moved.

## 12. Tasks

Written in full in `docs/audit/MASTER-PLAN.md` (T-061..T-064). Summary:

| Task | What | Model |
|---|---|---|
| T-061 | `MeshPins.hpp` + `tests/mesh_pins_test.cpp`; `OrcaPins.cpp` session, live path (`F <= 150k`), local refine, session undo; `Engine.kt` wrappers; MCP `object_pins` | opus |
| T-062 | Setting `reshapePins`; Pins entry in Reshape; pins, ring, handle, wash, label, number field, group toggle, TalkBack | sonnet |
| T-063 | Proxy path, release onto the full mesh, the 2M notice, self-intersection job + snackbar | opus |
| T-064 | Acceptance on the WSA VD with `au_insole.stl`, measured by script; timing numbers into BASELINE | sonnet |

Acceptance numbers (T-064), measured from saved meshes with the same topology (a zero-length `pinMove` first, so the
refine has happened before the "before" save): pin A on the toe's left edge, pin B on its right edge, both `r = 50`,
A moved 3.0 mm outward along X.
- The vertex nearest A moved 3.0 +- 0.1 mm; the vertex nearest B moved < 0.05 mm.
- No ledge: for every vertex, its displacement differs from the mean displacement of its edge neighbours by <= 0.10 mm.
- Slope: for every edge, the difference of its ends' displacements is <= `3.0 * 1.5396 * (1/50 + 1/rho_B) * length + 0.01` mm.
- `pinDrag` engine time <= 16 ms per call (median of 60 calls, from the engine log).

The plan's stub asked for "no step larger than 0.2 mm between neighbouring vertices". That number cannot hold in general:
a 3 mm move blended over 50 mm has a slope of up to 9 %, which is 0.39 mm across a 4.2 mm edge, and it is the intended
shape, not a ledge. The two checks above (ledge and slope) test what the stub meant.

## 13. Open points

- Untested: every number in sections 5-8 is a design number until T-061 / T-063 measure it.
- CGAL's time on a 150k-face mesh is not measured; if it is above 1 s the check runs at the session's end only.
- Whether `its_quadric_edge_collapse` to 60k on a 1M-face part is fast enough for the session's opening (busy state) is
  not measured; T-063 reports it.
- Native tests are not run by CI today; T-061 adds the new one the same way (standalone). A CI step for all three tests
  would be a small separate task.
