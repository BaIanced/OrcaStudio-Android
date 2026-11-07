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
