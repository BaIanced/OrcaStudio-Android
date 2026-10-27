# Interaction plan (Snapseed-inspired; user GO to start with step 1, 2026-10-26)
Source: backlog brain dump (2026-10-26). Order = dependencies first; each step ships alone, behind a toggle where it changes feel.
Rules: deviations pre-approved (say what changed); user vision (steered-point camera) stays as a toggle option; 2-3 non-converging
tuning rounds = rethink; compile locally; Claude-verify on WSA, user feel open after.

1. **Measure snap highlight (E1 + E2)** [Opus]: show what the snap caught (face / edge line / hole ring) + dot at the snap point, in view and loupe.
   Today: SceneController.kt SNAP_PX 14, `pick(snap=)`, `onMeasureLoupe`, PlateView.kt longpress loupe (dot sits ~70 px above finger).
   Start: pick returns the feature kind + geometry; overlay draws it; verify on the gear (WSA, hold + keys.ps1 U).
   BUILT 2026-10-26 (archive): native `SnapFeatures` (OrcaTools.cpp) = sharp edges (>25 deg), smooth patches,
   edge lines followed to corners (vertex with other than 2 sharp edges, or turn >= 50 deg); pick returns `feature` {kind
   vertex/edge/ring/curve/face, world lines, ring diameter}. View: wide-line shader (WIDE_LINE_VS; GL line width is 1 px on WSA)
   in the kind colour over a dark border; loupe: kind-coloured ring, dot on the snapped point, label (Corner / Edge / Circle
   D mm / Edge line / Face). Also pulled in from step 2: snap distance measured from the pick ray (screen-like; nothing behind
   the hit), candidates from all facets within reach (BFS), ring vertices are not corners.
   Claude-verified WSA debug: gear face outline, hole ring 9.50 mm, cube vertical edge, cube top outline, tooth corner.
2. **Snapping rules**: priority vertex > edge > face, release distance > capture (no flicker), radii in screen px, magnet/Alt = snap off.
   Open from step 1 tests: (a) noisy voxel meshes (Smooth blend 'Assembly') snap to 'Corner' everywhere (sharp-edge graph is
   noise; try a normal-rank corner test, deg-1 ends); (b) aim just off the silhouette = no hit = no snap (should still catch the
   edge); (c) curve/edge-line kind on gear tooth flanks not yet seen; (d) dashed outline where depth hides parts of thin lines.
3. **Measure results**: always dX/dY/dZ; parallel faces = separation; point-face; hole diameter / centre distance; bed pickable as a face.
4. **Input types**: mouse = exact cursor + hover preview, no offset; S Pen hover = highlight/loupe; finger = loupe/offset, select then drag relative 1:1.
5. **Axis inference**: line locks near an axis with colour; Shift = 15 deg steps; touch = toggle.
6. **Steered-point camera (user vision, E3 + E4)**: finger steers the point, camera follows, drag speed = zoom (zoom-in slower than out), 2nd finger = freeze;
   prototype behind a toggle beside the plain loupe; compare on phone.
7. **Mask as explanation (E2)**: live red overhang-angle preview (per-face normal in shader), smart-fill crease angle from a seed (E1), brush-size circle.
8. **Gesture scrubber (E5)** + **per-region edits** (colour / material / smoothing via E1 + E5): tool-mode only, 2 fingers = camera.
9. **Cut** (low priority): drag in view moves the plane, tap feature to cut, long-press face aligns plane, x-ray while placing.
