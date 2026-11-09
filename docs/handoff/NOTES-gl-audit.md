# GL state / upload / draw-call audit of `render/` (2026-11-06, Claude, Opus; reading only, nothing measured yet)

Baseline: r535 phone VD draw median 5.6-5.8 ms (PERFORMANCE.md). ULoc cache (~30 name lookups/pass) moved it <=0.1 ms,
so per-call CPU overhead (JNI + uniform setting) is already proven to be below noise on this device.

## Findings (PlateRenderer.kt)
1. `drawMesh` (:1061) re-sets ~30 uniforms on every pass (2-5 passes per frame: shadow, 0, 7x2, 1, 9) and allocates
   4 small arrays per call (`ids.copyOf` :1095, `sm` :1105, rot :1111, `floatArrayOf(1,1,1)` :1103). Cost estimate ~0.05-0.1 ms:
   same order as ULoc, so expect noise. Low priority.
2. `floats()` (:1790) does `ByteBuffer.allocateDirect` on every STREAM_DRAW upload (14 call sites; per frame in drawWideLines
   :1709, drawDots :1404, drawSelectionBox :1439, drawRings :1270, drawOverlays :1766, floor :1012, :1224). allocateDirect is slow
   and adds GC pressure, but only while those overlays show (selection, measure, gizmo). Fix: one reusable scratch FloatBuffer grown on demand.
   Cheap, safe; measure with `perf` with a selection + gizmo shown (not plate-1 fit, where they are off).
3. `superKey()` (:577) builds a list of ~22 items (mvp.toList(), measurePoints.map ...) every Preview frame when supersampling,
   and the shadow key (:592) every Prepare frame when contact shadows are on. GC churn only; under render-on-demand
   frames are now rare at idle, so low priority.
4. No GPU timer queries anywhere (`grep TIME_ELAPSED` = 0). The 5.6 ms draw is GL-thread CPU incl. driver; which pass costs
   what on the GPU is unknown. Per the PERFORMANCE.md rule (profile first), the next real step is a per-pass
   `GL_TIME_ELAPSED_EXT` (EXT_disjoint_timer_query) breakdown in `FrameStats`/MCP `perf`, then fix the top pass.
5. No redundant glBufferData of static data per frame found: crease/marker VBOs already guarded (:1615, :1736, e28ad0c).

## Suggested order
a. Timer-query per pass in `perf` (debug-only), measure plate 1 orbit + Preview still/accumulate.
b. Item 2 (scratch buffer) if a selection/gizmo frame shows allocation stalls.
c. Items 1/3 only if a) shows CPU-bound frames.

## Item 4 done in code (2026-11-06, 1963674, untested on a device)
- `render/GpuTimer.kt`: GL_TIME_ELAPSED_EXT queries per labelled section, 4-frame ring (never waits on the GPU), disjoint/unready frames dropped.
- Sections in PlateRenderer: clear, shadow map, shadow, floor/beds, preview, mesh, paint, creases+guides, clear filaments,
  translucent, selection, overlays, loupe, 2x resolve, accum show/mix. Nested sections aren't timed (queries can't nest).
- MCP `perf`: first call turns timers on; each later call prints GPU ms per pass (median/p95) since the previous call, then resets.
- Caveat: on tiled GPUs (Adreno/Mali) per-pass times inside one render pass may show binning only; if the medians don't sum
  near the frame time, fall back to ablation (turn passes off, compare frame time).
- Next: debug build on the phone VD (Adreno; WSA may lack the extension), perf twice around plate-1 orbit and Preview still/accumulate.

## Item 4 measured (2026-11-06, phone VD 1080x2340, Adreno, local debug build of f0ffe5d)
Scene: plate 1 (lattice vase "Shape design.stl" + prime tower), shadows on, supersample still. Preview = 541 layers, 56.9 m.
- Prepare orbit: 166 fps, frame gap 6.0 ms; GPU medians sum 2.36 ms: mesh 1.52, translucent 0.49, shadow 0.30, beds 0.03. Not GPU-bound.
- Preview still (accumulating): preview 24.3 ms median (p95 70), accum mix 0.21, beds 0.25.
- Preview orbit: preview 48.8 ms median (p95 65) per frame, frame gap 49 ms (~20 fps): **GPU-bound on the preview pass**
  (`PlateRenderer.kt:621`, `gpuTimer.section("preview")`). Everything else < 0.6 ms.
- Timer sums match frame gaps here, so the tiled-GPU caveat didn't bite; no ablation needed.
- Next: find why the toolpath draw costs ~49 ms (vertex count per frame, overdraw, fragment shader cost, LOD/culling of
  hidden layers); fix it, then re-measure with the same recipe.
Recipe: install debug + copy session (wsa-debug-setup.sh minus its last 2 lines, `W=<phone>`), `PKG=...debug orca-vd.sh start`,
`MCP_DEV=<phone> PKG=...debug mcpao.sh 'perf:{}'` (first call arms timers), `orca-vd.sh swipe` 3 s each way, perf again.
Debug app needs More > Printers & presets > "Remove mixed temperature restriction" on to slice plate 1 (its own prefs copy).

## Item 5: preview pass analysis (2026-11-06, code read only, no device run yet)
- Plate 1 `cache/out/plate_1/extrusions.bin` = 78,061,980 B / 60 B (stride 15 floats) = **1.30 M segments**, all drawn every frame
  (`PlateRenderer.drawExtrusions` :1495, one `glDrawElementsInstanced`, box = 12 shared corners / 26 triangles / 78 indices, `uploadBox` :867).
- Per frame: ~15.6 M PATH_VS runs (Shaders.kt:645) if Adreno reuses the 12 corners per instance, up to 101 M if not; binning pass
  re-runs positions. Each run fetches 11 attributes (60 B record + prev/next neighbours) and does 3 normalize, atan, cross. 49 ms /
  15.6 M = ~320 M verts/s: consistent with **vertex-bound** (hypothesis, untested).
- First experiment (no build): perf in Preview orbit zoomed far out (few pixels, same vertices) vs zoomed in. Same ms = vertex-bound;
  scales with coverage = fragment-bound (then: draw top layers first for early-Z, cheaper PATH_FS regimes).
- If vertex-bound, candidate cuts (cheapest first): (a) per-segment mitre/right/sideNormal precomputed on the CPU at upload, PATH_VS
  just places corners (no neighbour fetch, no normalize/atan); (b) 8-corner box when the overlap ring is sub-pixel (zoomed out);
  (c) skip enclosed segments: sparse/internal-solid infill (roles 5-10?) below the top shown layer during orbit only, full draw when
  still (visual change through lattice openings, check A/B); (d) screen-size LOD: merge collinear runs whose joints are < 1 px.
- Re-measure with the item-4 recipe; log before -> after in docs/PERFORMANCE.md.

## Item 5 measured (2026-11-07, phone VD, debug build of 5363133 = same code as item 4)
Zoom experiment, Preview orbit 3 s each way, plate 1 (541 layers), preview pass GPU median: **distance 6000 mm 3.1 ms,
1138 mm (default) 61 ms, 300 mm 95.5 ms**. Same 1.30 M instances every time (no CPU LOD, `drawExtrusions`), so vertex work
<= ~3 ms: **fragment-bound, the vertex-bound hypothesis was wrong**; cuts (a)-(d) above are dropped.
Cause (code read): segments drawn bottom layer first, so from above each layer passes the depth test and runs PATH_FS again
(heavy: up to ~13 `shade()` calls, 2 `discard`s). Fix order: (1) draw chunks nearest-first for early-Z (WIP commit, unpushed
at write time -> see git: `EXTRUSION_CHUNK` 16384, reversed when eye.z > 0, `PlateRenderer.drawExtrusions`); compiled, NOT
measured. (2) if Adreno skips early-Z because of `discard`: shader variant without the section discard when the cut is off,
or a depth pre-pass. (3) cheaper PATH_FS (beadMean calls). Check joints for new z-fight after any order change (A/B shot).
Recipe this run: `~/orca-android-tools/preview-zoom-perf.sh <distance>` = view_set distance, preview_set {}, perf, orca-vd swipes, perf.
Setup: debug app on the phone needs `allow_mixed_temps` true in its app_settings.xml (sed via su, app stopped) to slice plate 1.

## Item 5 fix 1 measured (2026-11-07, phone VD, debug build of 1698961)
Nearest-first chunks: preview GPU median 1138 mm 61 -> 44.3 ms, 300 mm 95.5 -> 59.3 ms, 6000 mm 3.1 -> 2.35 ms (PERFORMANCE.md Done).
Close shot 250 mm / pitch 25: no visible joint z-fight. Still ~44 ms at default zoom = still overdraw-bound: Adreno LRZ is likely
off for this draw because PATH_FS has `discard` (fix 2: no-discard variant when the section cut is off, or a depth-only pre-pass
with colour masked, then the colour pass with GL_LEQUAL / depth write off). Then fix 3 (cheaper PATH_FS).

## Item 5 fix 2 measured (2026-11-07, phone VD, debug build of the fix-2 commit)
No discard in PATH_FS while the section cut is off: the top/bottom-from-inside discard is now GL_FRONT culling (+ reversed side
triangles), the section discard lives only in `pathProgram` (cut on); `pathProgramNoCut` otherwise. Preview GPU median: 1138 mm
44.3 -> ~43 ms (no real gain), 300 mm 59.3 -> 43.5 ms. Both zooms now ~43 ms: close-up overdraw is gone, but the default zoom
didn't move, so it is not overdraw there. Next suspects (unmeasured): PATH_FS cost per visible pixel (fix 3: beadMean x2-3 =
8-12 shade() + normalize per fragment; precompute the regime means per face in the VS or cut beadMean to 2 taps), and
small-triangle/raster cost (1.30 M boxes at ~1-2 px each at 1138 mm). Measure fix 3 the same way.

## Item 5 floor measured (2026-11-07, phone VD, temp local build, not committed)
Floor experiment: `pathProgramNoCut` with a flat `color = vColor` FS. Preview GPU median: 1138 mm 29.7 ms, 300 mm 29.6 ms
(PATH_FS: ~43 / 43.5). So **PATH_FS costs ~13 ms at most; ~30 ms is triangle/raster cost** (1.30 M boxes x 26 tris = ~34 M
triangles, 6000 mm = 3 ms so the VS isn't it; it is per-triangle setup/binning of on-screen triangles).
Fix 3 options checked: 2-tap Gauss beadMean dropped (numeric check vs exact integral: worst 8.6% brightness error vs 2.3% for
the 4 midpoint taps; would shift the level where the regimes fade, user r335). Remaining cheap FS cuts (<= 13 ms total,
untested): normalize in the beadMean/beadShade taps -> constant 1/sqrt(1+(u*tilt)^2) (axis is ~perpendicular to n);
`shade(n)` at the top of main only for end caps; skip the far branch's beadMean when far == 0.
Bigger lever = triangle count (old cuts (b)/(c) revive, now for raster, not VS): (b) 8-corner box (no overlap ring) when the
ring is sub-pixel, 26 -> ~12 tris; (c) skip enclosed sparse/internal-solid infill under the top shown layer while orbiting;
(e) drop side/bottom faces hidden by neighbours. Each needs an A/B look check (cracks, z-fight, brim-from-below report).

## Item 5 cut (b) plan (2026-11-07, code read only, nothing built)
- The box is **36 triangles now, not 26** (`uploadBox` PlateRenderer.kt:872): top 2 + bottom 2 + side walls 8 + overlap walls 8
  + a reversed copy of all 16 side triangles (fix 2, :902; one of each pair is always culled). 1.30 M x 36 = 47 M tris submitted.
- Doubt: 6000 mm = 3 ms would mean 47 M tris rejected in 3 ms (~15 G/s), which is implausible; the far plane may clip the whole plate
  there (check `Camera.clip` at 6000 mm). So "the VS isn't it" is unproven; the cost may be setup of *all* submitted triangles, culled ones included.
- Next, before any visual cut: a temp diagnostic build (not committed). Order the indices as [top, bottom, layer walls, overlap walls,
  reversed layer, reversed overlap] so prefixes are variants (12 / 20 / 28 / 36 tris), choose the draw count at runtime (e.g.
  `SystemProperties.get("debug.orca.boxtris")` via reflection, set with `setprop` as root on the phone), then run
  `preview-zoom-perf.sh 1138` / `300` per variant with the flat FS and with PATH_FS. If 36 -> 20 (drop the reversed copies) already
  cuts most of it, the cheap fix is dropping or narrowing the reversed copies (look check: cracks between layers, r339/r340 notes);
  only then the sub-pixel overlap ring (cut b) and (c)/(e).

## Item 5 triangle diagnostic measured (2026-11-07, phone VD, temp build of f85457d + `~/orca-android-tools/diag-boxtris.patch`)
Patch (not committed; tree reverted): indices reordered [top, bottom, layer walls, overlap walls, reversed layer, reversed overlap],
draw count = `setprop debug.orca.boxtris 12|20|28|36` (root), `debug.orca.flatfs 1` = flat FS. Runner: `~/orca-android-tools/boxtris-sweep.sh`.
Preview GPU median ms (raw: same script output), tris 36 / 28 / 20 / 12:
- PATH_FS: 1138 mm 44.4 / 38.8 / 24.0 / 16.1; 300 mm 45.5 / 40.2 / 28.7 / 17.7; 6000 mm 3.65 / 3.72 / 1.56 / 1.48.
- flat FS: 1138 mm 29.8 / 26.3 / 20.5 / 13.8; 300 mm 31.5 / 26.3 / 21.5 / 11.8; 6000 mm 3.24 / 3.31 / 1.54 / 1.47.
Read: **the reversed side copies cost ~20 of 44 ms at default zoom** (36 -> 20), the reversed overlap walls alone ~15 ms (28 -> 20).
They cost FS time too (PATH_FS gap 20 ms vs flat 9 ms), so their fragments pass the depth test a lot: the far walls' insides show
through the cracks between beads (their stated job, uploadBox comment) or win where the overlap is pushed back in depth.
6000 mm is not clipped (perspective far = distance x 20), and it too scales with submitted tris (3.65 -> 1.56): small fixed cost.
Next: look A/B on the installed phone debug app (still the diag build) with setprop 36 vs 28 vs 20: plate 1 from above, side and
below (brim-from-below report), close 250 mm. If 20 looks the same, drop the reversed copies (-45%); if only the reversed overlap
shows cracks, try keeping just the reversed layer walls, or fill the cracks another way. Then the overlap ring itself (20 -> 12, -8 ms).

## Item 5 look A/B measured (2026-11-07, phone VD, same diag build; `~/orca-android-tools/boxtris-ab.sh` + `boxtris-cmp.py`)
**Label fix for the section above:** with prefix ordering, 36 -> 28 drops the *reversed overlap* walls (44.4 -> 38.8, ~5.6 ms) and
28 -> 20 drops the *reversed layer* walls (38.8 -> 24.0, ~15 ms); "reversed overlap walls ~15 ms" above was mislabeled.
Plate 1 Preview, 4 views (above 1138 mm, side 600, below 600, close 250), full-res shots, pixels changed (any channel > 24) vs 36:
- 28: **0 px in all 4 views** -> the reversed overlap copies are pure cost. Drop them (safe, ~-5.6 ms default zoom, -13%).
- 20: above 0.057%, side 0.007%, below 0, close 0.229%. Close crop (`shots/btd-close-20.png`): the bright orange rim line where a
  layer wall meets the top surface turns thinner/olive; the wall insides showing at the bead edge are what the reversed layer walls draw.
  Subtle but visible at 250 mm. Below view: no change (so not related to the brim-from-below z-fight).
- Gotcha: a repeated identical `view_set` doesn't redraw; the script nudges distance +7 first (the first run gave 0 px for everything).
Next: commit "drop reversed overlap copies" (box 36 -> 28 tris), measure 1138/300. Then decide the reversed layer walls: try a cheaper
way to keep the rim (e.g. reversed copies only of the top edge band, or darken/brighten the rim in the FS) vs accept the thinner rim for -15 ms.

## Item 5 reversed overlap copies dropped (2026-11-07, commit "Preview: box 36 -> 28 triangles")
`uploadBox` now orders [top, bottom, layer walls, overlap walls, reversed layer walls] = 28 tris (same as diag prefix 28). Compiled,
not re-measured (diag numbers stand: 1138 mm 44.4 -> 38.8, 300 mm 45.5 -> 40.2). Rim decision (reversed layer walls, ~15 ms), from
the close crop: the orange outer-wall top lines turn thinner/olive without them, i.e. the bead behind shows through a crack at the
wall's top edge (they fill cracks, not just "draw insides"). Also needed with the section cut on (a cut bead shows its far walls'
insides). Options, unmeasured: (1) draw 28 only while the cut is on, 20 otherwise, accept the thinner rim; (2) close the crack instead:
lift each layer wall's top ring a hair (or widen the top face by ~0.5 px) so the wall's own outside covers it, then 20 tris;
(3) reversed copies of a thin top band only (new vertex ring, same tri count, less fill; flat-FS data says setup is ~6 of the 15 ms).
Try (2) first with a boxtris-ab.sh close shot vs 28.

## Item 5 wall-top crack: analysis before option 2 (2026-11-07, code read only, nothing built)
- PATH_FS has no discard (other than the section cut) and no gl_FragDepth, so the 20-vs-28 rim change is geometry: some pixels at
  the wall-top edge are covered by no outside face, and the reversed layer walls fill them.
- Why bright orange: a reversed copy keeps its face's provoking vertex, so it is shaded with the *outward* normal of a wall that faces
  away from the camera, i.e. often towards the key light (0.35,-0.55,0.75): a far wall's inside comes out brighter than the near faces.
  Without it the darker bead behind shows (olive). So the "rim line" is partly an artefact (inside lit as outside), not true shading.
- Within one box the top face and walls share the top ring's corners and depth bias (rank = label at aCorner.z = 1 for both), so no
  crack inside a box; it must be between boxes (layer N+1's pushed-back overlap ring vs layer N's top edge, or neighbouring beads).
- Option 2 ("lift the wall top ring") only helps if the crack is the box's own top edge, which the shared corners argue against. So first
  find which crack: **diag mode `debug.orca.boxtris 20` + a second draw of index range 20..28 (reversed layer walls only) in flat magenta
  with GL_LEQUAL**, close view (boxtris-ab.sh "close -30 -35 250"). Magenta at the rim = where they win. Then pick: (2) if it is the
  box's own edge; widen the overlap ring / lower the push (7.2e-5) if it is layer N+1's foot; (1) 28 only with the cut on otherwise.

## Item 5 wall-top crack: magenta diag, LEQUAL run (2026-11-07, phone VD, temp debug build of d30c46f + `~/orca-android-tools/diag-rimdiag.patch`)
Patch (not committed; tree reverted): `setprop debug.orca.rimdiag 1` = boxes draw 20 tris, then per chunk the reversed layer walls
(index offset 120 B, 24 indices) in flat magenta, GL_LEQUAL, depth write off. Runner `rimdiag-shots.sh` (views close 250 / closer 120 /
above 600, shots `shots/rd-<view>-<0|1>.png`), counter + 600 px crops `rimdiag-cmp.py` (`shots/rdc-<view>-<0|1>.png`).
Gotcha: the first `slice` right after `orca-vd.sh start` returned non-JSON (app still starting) and the shots showed Prepare; rerun.
- Magenta px: close 0.075% (1883), above 0.007%, closer 0 (that view missed the model; reaim).
- Where (close crop): (a) continuous lines along bead top edges at the purple band's borders (yellow/olive lines in the 28 shot);
  (b) dashes on the outer rim exactly where the 28 shot has its orange dashes; (c) short dashes at regular spacing across the
  green skin = segment joints (end/start walls, coincident internal faces).
- Caveat: LEQUAL also passes exact ties: a far wall's top ring shares its corners (same depth, same rank) with the top face, so
  (a) may be ties, not real wins. The real draw is GL_LESS (top drawn before the walls in the same instance).
Next: same diag with GL_LESS (patch: `glDepthFunc(GL_LEQUAL)` -> a prop switch, e.g. rimdiag 2 = LESS), same views + closer
reaimed at the crop's spot. If (a) vanishes under LESS and only (b)/(c) remain: (c) = joint end walls -> drop reversed copies of the
end/start walls only (the 8 reversed tris = 4 walls x 2; keep right/left = 4 tris, 28 -> 24) and A/B; (b) decides option 2 vs (1).

## Item 5 wall-top crack: GL_LESS rerun + 24 tris (2026-11-07, phone VD, diag build of 179fae6 + `diag-rimdiag.patch` v2)
Patch v2 (not committed): `debug.orca.rimdiag` 1 = LEQUAL all magenta (old), 2 = GL_LESS with right/left reversed walls magenta and
end/start cyan, 3 = draw 24 tris, 4 = 20 tris. Runner `MODES="0 2" rimdiag-shots.sh`; counts `rimdiag-cmp.py <mode> [views]`;
A/B strip + changed px `rimdiag-ab.py <view> <x> <y> <modes>` (`shots/rdab-<view>.png`). Restart the VD if MCP stops answering ("not resumed").
- LESS close: 1699 px (magenta 1356, cyan 343) vs LEQUAL 1883: ~90% are real wins, not ties. Same-box corners tie and lose under LESS,
  so every hit is a crack *between* beads: magenta = continuous lines at bead/region borders (purple band edges, skin/perimeter seam,
  outer wall top edges = the orange rim dashes); cyan = regular dashes across the skin at segment joints. Option 2 (lift a box's own top
  ring) can't help; ruled out.
- Look A/B vs 28: 24 tris close 393 px (0.016%), above 2 px: no visible change. 20 tris close 0.605%: rim dashes go, purple-band lines thin.
- Perf (GPU medians sum, two rounds each): 1138 mm 33.68/33.61 -> 29.66/30.17; 300 mm 49.56/49.84 -> 44.32/46.93.
- Shipped: reversed copies of right/left layer walls only (28 -> 24 tris), commit "Preview: box 28 -> 24 triangles".
Left open: the right/left copies (~4 ms more) fill real between-bead cracks with an inside-lit-as-outside colour. Options: (1) 24 only with
the section cut on, 20 otherwise (accept thinner rim); (4) close the crack: widen layer walls sideways by a hair in the VS (risk: coplanar
neighbour tops z-fight unless rank bias separates them; check rank per role first).

## Item 5 rim option 4: depth rank per role/neighbour (2026-11-07, code read only, nothing built)
- PATH_VS (Shaders.kt:761-778): order = lift x 7 + rank, bias 2e-6 x w a step; lift also raises the bead 0.01 mm x lift in world z.
  rank is per vertex: top ring = depth label 0..4 (DEPTH_LABELS = 5, Preview.cpp:71), wall bottom + overlap rings = 5 + parity.
  So a wall's bias slides from its top's label to 5/6 going down; top face and own walls share top-ring vertices (watertight).
- Labels (Preview.cpp label_layer :110): greedy colouring per layer of beads of one depth class (= lift group, :81) on one plane
  that come within (wa + wb) / 2 + 0.01 mm. Over 5 used neighbours it falls back to j % 5 (can tie). Different classes: lift
  separates them (0.01 mm z + 7 ranks), so cross-class overlaps never z-fight.
- So widening every box sideways stays safe only while the extra overlap is under the 0.01 mm touch margin: a widened bead must not
  reach a bead that wasn't labelled as its neighbour (same label possible -> z-fight). A screen-space widening is out: at 1138 mm
  a pixel is ~0.4 mm (Camera.kt:230), so 0.5 px = 0.2 mm, far past the margin, and it would fatten beads visibly up close.
- World-space hair (e.g. 0.004 mm on `across`) only closes numeric T-junction cracks. If the magenta lines at region borders
  (skin/perimeter seam, purple band edges) are real geometric gaps (infill/wall overlap settings, lift steps), a hair won't close
  them; then the 20-tris look is the true geometry and the reversed copies paint an inside-lit-as-outside colour into real gaps.
- Next: diag prop `debug.orca.widen` (mm, default 0) scaling `across` by (w + widen) / w, build on the phone, rimdiag-ab.py close view
  modes 20 tris + widen 0.004 vs 24 tris. If the magenta-spot pixels match 24: ship 20 + widen (-4 ms). If not: option 1 (24 only
  with the section cut on, 20 otherwise) as the default, since 20 is the truer image.

## Item 5 rim: widen diag -> option 1 shipped (2026-11-07, phone VD, diag build `~/orca-android-tools/diag-rimdiag-widen.patch`)
- `debug.orca.widen` (mm, uniform uWiden, `across *= (w + widen) / w`); runner `VIEWS="close above" MODES="0 4 4_0.004 4_0.01" rimdiag-shots.sh`
  (mode `M_W` = rimdiag M + widen W). Close view vs 24 tris: 20 = 14811 px, 20+0.004 = 14799, 20+0.01 = 14832; vs plain 20: 0 px changed
  at 0.004 and 0.01 (0.2 mm changes 80456 px, so the uniform works). The rim gaps are real geometry, not numeric cracks: option 4 out.
- Shipped option 1: reversed right/left wall copies only while the section cut is on (`boxOutsideCount` = 60 indices otherwise).
  New build vs diag 20 tris: 0 px (close, above). Perf NOT measured: VD swipes stopped producing frames this session (input to display
  36 accepted, yaw unchanged, "last frame N s ago"). Old numbers 20 vs 24 tris: ~-4 ms at 1138 mm (diag, 2026-11-07).

## Findings 1+2 done in code (2026-11-08, Claude, compiled only, NOT run on a device)
- Item 2: `PlateRenderer.floats()` reuses one direct scratch FloatBuffer (power-of-two growth, <= `SCRATCH_MAX_FLOATS` 65536 floats);
  bigger one-off static uploads still allocate their own. Safe because glBuffer(Sub)Data copies before returning; only
  `uploadPadded` holds a result across two calls with no other floats() in between.
- Item 1: drawMesh `ids.copyOf`, `floatArrayOf(1,1,1)`, scale and rot matrices -> reused `selScratch`/`m3Scratch`/`ONE3`.
- Already done (backlog was stale): PERFORMANCE.md idle-mA row (line "Idle vs orbit battery current", r537 -> r538). Item 4's top pass
  (preview 48.8 ms) was the item 5 target, closed 2026-11-08 at 15.3 ms (view_set orbit; methods differ, see PERFORMANCE.md).
- Left: device check (debug build on the phone VD: select an object + gizmo, perf before/after vs c701af5 = draw ms + look unchanged;
  Prepare/Preview shot unchanged), then item 3 (`superKey`/shadow key list churn) or close it as low value. Then the audit is complete.

## Findings 1+2 device check + audit closed (2026-11-08, phone VD 1080x2340, debug builds of c701af5 vs 4beb4d1)
- Runner `~/orca-android-tools/gl-sel-ab.sh <apk> <tag> [setup]` (RUNS=n): plate 1 fit, tap object 1 (selection outline + box
  corners; tool none, so no gizmo rings), 24 view_set yaw steps x3, MCP perf, shots `shots/<tag>-{sel,fit}.png`.
- Draw ms median: before 15.5 / 15.4 / 15.6, after 15.3 / 16.0 / 15.1 = noise (as predicted). GPU sum 3.9 both; selection pass 1.15 vs 1.1.
- Look: 0 px differ (>24) on both shots. Gizmo/ring call sites hand floats() straight to glBufferData like the selection box
  (only `far` :850 is held, by uploadPadded), so the gizmo path is covered by code, not run.
- Item 3 (superKey) closed as low value: GC churn only, Java heap stays 10-29 MB, frames are not limited by it.
- **New finding (untested cause):** with a selection, CPU draw is ~15.5 ms per frame vs GPU 3.9 ms, and vs the 5.6-5.8 ms r535
  baseline without a selection (PERFORMANCE.md; different method: swipe orbit). Selection frames look CPU-bound; ~10 ms of CPU
  somewhere in the selection path (outline/box build per frame?). Next: perf on the same build without a selection to confirm, then profile.

## Selection CPU cost: baseline confirmed (2026-11-09, phone VD, same debug build 4beb4d1)
- `NOSEL=1 gl-sel-ab.sh <apk> nosel` (new switch: skips the object tap). No selection: draw ms median 5.3 / 5.2 / 5.2, GPU sum 1.9.
  With selection (2026-11-08): 15.5 / 15.4 / 15.6, GPU sum 3.9. So one selection adds ~10 ms CPU per frame, ~2 ms GPU.
- Not CPU math: `selectionBox`/`meshBox` (PlateRenderer.kt:1359-1396) are a few float ops; `SelectionOverlay.draw`
  (SelectionOverlay.kt:39-74) issues mask draw(s) of the whole mesh into its own FBO, then a fullscreen quad on FB 0.
- Hypothesis (untested): driver stall from the mid-frame FBO switch (tiled GPU resolves the main pass at :42, reloads at :55)
  and/or the glClear on the mask texture waiting for last frame's sampling of it. Also check drawSelectionBox/drawGizmo uploads.
- Next: split the 10 ms. A/B with the selection style set to box only (no outline/x-ray -> no FBO) tells overlay vs box/gizmo.
  If overlay: try (a) mask pass before the main scene draw (no mid-frame switch), (b) ping-pong 2 mask targets,
  (c) glInvalidateFramebuffer on the mask depth after use. Measure each with gl-sel-ab.sh.
