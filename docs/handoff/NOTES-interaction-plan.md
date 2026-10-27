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
2. **Snapping rules** BUILT 2026-10-26 (archive): sticky catch (release 1.6x capture), off-silhouette seed rays, corners/edges only on
   lines longer than the snap distance (voxel noise fixed), Snap checkbox + Alt, hidden outline dashed. Open: curve kind not yet seen
   on a real part; Alt+click untested; user feel.
3. **Measure results** BUILT 2026-10-26 (archive, r432): points keep their kind (`ui/Measure.kt` MeasurePoint: ring = centre + axis, face flat only if
   its outline is planar, bed); panel headline = face gap / square to face or bed / centre to centre, else distance; angle between
   faces; dX/dY/dZ always. Open: ring-to-ring on two holes; a bed aim doesn't snap to a hole's bottom rim; user feel.
4. **Input types** BUILT 2026-10-26 (archive, r433): mouse / pen = exact (no lift), hover previews the snap (loupe + highlight), click places what the
   hover showed, press on a placed point (12 dp) drags it (no camera); finger keeps the 56 dp lift, a long press on a placed point (40 dp of the finger
   or its aim) keeps the point's spot and moves it 1:1. Open: sharp magnified loupe (E3, 2nd view) not built; real S Pen tap; Quest pointer; user feel.
5. **Axis inference** BUILT 2026-10-26 (archive, r434): within 8 deg of X / Y / Z the line locks (axis colour, headline = along it, B not moved);
   Shift or the 15° toggle = 15 deg steps (magenta); "Lock to X / Y / Z" toggle. Open: real Shift, Y / Z on a part, user feel.
6. **Steered-point camera (user vision, E3 + E4)**: finger steers the point, camera follows, drag speed = zoom (zoom-in slower than out), 2nd finger = freeze;
   prototype behind a toggle beside the plain loupe; compare on phone.
7. **Mask as explanation (E2)**: live red overhang-angle preview (per-face normal in shader), smart-fill crease angle from a seed (E1), brush-size circle.
8. **Gesture scrubber (E5)** + **per-region edits** (colour / material / smoothing via E1 + E5): tool-mode only, 2 fingers = camera.
9. **Cut** (low priority): drag in view moves the plane, tap feature to cut, long-press face aligns plane, x-ray while placing.

## Original brain dump (moved from HANDOFF-BACKLOG.md 2026-10-26; keep every idea open)
  only a remembered example of friendly UI; keep every idea open, prefer shared engines so no early choice blocks a later one. Not started.
  - Engines (features = combinations): (E1) surface picker / region grow over faces, pluggable stop rules (crease angle, coplanar,
    cylinder fit, geodesic radius); welded adjacency, off UI thread on huge meshes; desktop Orca smart fill may already cover the core
    (check src-orca). (E2) overlay renderer: per-face mask, feature highlight, x-ray/ghost, two-tone. (E3) precision pointer: sharp 2nd-view
    loupe (not framebuffer zoom), offset or steered cursor, per tool type. (E4) camera director: framing, easing, speed rules, freeze.
    (E5) gesture scrubber: vertical drag picks a setting, horizontal changes it; clashes with one-finger orbit, so tool-mode only, 2 fingers = camera.
  - Measure (user tried WSA, gear edge bottom-to-top was hard): biggest gap = no snap highlight (can't see what it caught). Highlight the
    whole feature (face, edge line, hole ring) + dot at the snap point, in view and loupe (E1+E2). Feature-to-feature results: parallel
    faces = separation (no slant error), point-face, hole diameter / centre distance; bed pickable as a face; always show dX/dY/dZ.
    Input type: mouse = exact cursor, hover preview, no offset (today the point jumps away from the mouse as if it were a finger);
    S Pen = hover shows highlight/loupe; finger = loupe/offset. Snapseed-style select point, then drag anywhere (relative, 1:1).
    Snapping: magnetic, priority vertex > edge > face, release distance > capture (no flicker), radii in screen px (zooming in = finer
    reach); Alt / magnet toggle = snap off. Axis inference: near vertical/bed axis the line locks + axis colour; Shift = 15 deg steps;
    touch = toggle (2nd finger reserved for camera). Lock = measure along the direction (B never stuck); "force B onto the line" maybe later.
  - Camera (user's own vision, keep): live camera during the drag, camera as the loupe. Viable form = steered point: finger steers the
    point on the surface, camera follows it, drag speed sets zoom (slow = in, fast = out); zoom-in slower than zoom-out so a wide slide
    aborts; 2nd finger = freeze/zoom out; framing anywhere, double-tap framing. Risk: feedback loop / seasick if the finger IS the point.
    Prototype behind a toggle next to the plain loupe, compare on phone; 2-3 non-converging tuning rounds = rethink.
  - Mask as explanation: live red preview of what an angle means: support overhang angle (global, per-face normal in shader, cheap) and
    smart-fill crease angle from a seed (E1); brush-size circle on the surface. User rarely paints, values it for understanding regions.
  - Cut: user likes Bambu's gizmo + two-colour halves; wants x-ray of the model while placing; current slider can't be dragged in the view.
    Ideas: drag in view moves the plane (E5), tap a feature to cut there (E3), long-press face = align plane. User rarely cuts (low priority).
  - Open: per-region edits (colour/material/smoothing) via E1+E5; which engine to start with (feature highlight for Measure looks first).
