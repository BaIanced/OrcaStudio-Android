# Colour painting survey (Claude, 2026-10-21, web)

User 2026-10-21: "frankly I find orca painting atrocious and generally only use fill / paint by layer". Asked for ideas from
non-Orca/Bambu tools.

## Who has what
- Cura 5.11 (2025-10): brush only (seam + extruder painting). Nothing new.
- Creality Print / Flashforge / Qidi: the same upstream Orca/Bambu set. Creality 7.3: OBJ colours auto-matched to filaments, gradients.
- Bambu Studio 2.7: texture-to-colour (textured model -> 4/8/16 filament regions), per-part gradient; 2.8 beta "Decompose colour".
- Prusa ColorMix / Full Spectrum Orca fork: virtual mixed colours from layer interleaving (an engine feature, not painting UX).
- LayerPaint (layerpaint.app, browser): Magic Fill (flood to creases, scroll sizes it, 2nd click applies), crease auto-partition
  into regions, region fill, Borders (draw split lines, encircling strokes auto-close), guard planes that tools stop at,
  isolate/cut (hide the side in the way), symmetry, shape tool (line/rect/circle previewed and movable before applying),
  decals (logo/image/text stamped in full colour, follows curves), grow / clean specks / flag too-thin patches, colour swap,
  AI paint from photo/prompt, layer-by-layer print check with a swap counter.
- PrintPal 3D Model Painter (browser): brush (front faces only, "paint through thin walls" option), smart fill w/ hover preview,
  bucket fill w/ "stop at sharp edges", height range, eyedropper, "Move view" mode for touch, paint detail levels,
  "Colour each separate part" (largest shell first), "Merge specks" (< N mm2), "stop at existing colour edges".
- Primed3D (desktop): Band = movable slab with thickness, position, rotation and pitch (tilted bands), Gradient between two colours
  with an on-screen direction arrow + Steps (quantised bands), Photo projection from the camera (drag/scale/rotate, PNG alpha),
  fill tolerance slider (exact -> colour family), geometry fill (angle limit, ignores colour).
- Orca issue #12166 (open, no maintainer reply): paint depth / paint through walls+infill.

## Ranked for this app (fill + height user, touch screen)
1. Live band tool: drag top/bottom handles on the model with a live preview, tilt it (Primed3D Band); replaces click-height-range.
2. Gradient / stripes: pick 2+ filaments, drag an arrow, N steps -> quantised bands (Primed3D, Creality, Bambu).
3. Hold-to-preview fill: press shows the region that would fill, release applies, slide finger off cancels (LayerPaint/PrintPal hover).
4. Regions view: crease auto-partition drawn as outlines, tap a region to colour it (LayerPaint; shares code with the edge outline item).
5. One-tap quick actions: colour each separate part, merge specks, eyedropper, swap colour (remap exists).
6. Fill stops: "stop at existing colour" and guard plane (a draggable plane fills/bands stop at).
7. Decals: text/logo/image projected from the camera, quantised to the loaded filaments (big; Bambu/LayerPaint/Primed3D).
8. Symmetry painting (LayerPaint).
