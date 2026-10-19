# Blind shader + lighting re-test (2026-10-16, r263, phone)

Run blind to the earlier results on request ("re-run the shader and lighting test blind ... compare notes"). Caveat: the
handoff's "Next task" paragraph (summary of the earlier run) was read before the blind request was understood; the
archive, earlier shots and other sessions were not looked at. Own test matrix, driven by MCP `view_set` + `view_screenshot`
and `labset.py`. Shots + contact sheets: `~/orca-android-tools/shots/blind-2026-10-16/` (sheetA..sheetV = matrix groups).

Scene: plate 1 = 20 mm cube (128.7, 131.3) + 20x60 mm pillar (43.6, 138.2); plate 2 = table (44 mm slab, 34 mm high).
Defaults unless noted: darkness 0.5, softness 6 mm, bias 2 mm, light az -57.5 / el 49 / strength 1, AO 0.

## Works (seen in shots)
- Shadows on/off switch: off removes the bed shadow and the shadow on parts; on restores (B1/B2, N1-N6).
- Direction: az 180 / 0 / 90 / -120 / -57.5 each throw the shadow to the opposite side, matching "azimuth from +X"
  (top view yaw 0 pitch 89: +X down, +Y right) (sheetL).
- Height: el 85 = shadow almost under the part, el 20 = long shadow (L5/L6). Length matches the angle: el 75 hard
  shadow, pillar ~16 mm / cube ~5 mm long, no sideways offset (P3 = alignment check, pass).
- Strength: 0.2 = parts dim, 2.0 = top faces clip to flat yellow (expected at 200 %) (L7/L8). Doesn't change shadow darkness.
- Darkness 0.1 / 1.0 and softness 0.5 / 12 behave as named; softness 0.5 = crisp edge, no visible acne or jaggies
  (C3, P3). Shadow stays attached at the cube's base at every bias (C1/C2 crops).
- Parts receive shadows: at darkness 1 the pillar's shadow (light az 180, el 20) visibly darkens the cube's lit face and
  top (N1 vs N2, N5 vs N6). The table slab darkens the top of its stem (T6 vs T7).
- AO: engravings (cube's Y) read clearly with AO 1/2, half-hidden with AO 0 (B1 vs B3/B4). AO 1 vs 2: hard to tell apart.
- Slice preview: identical with default light vs az 90 / el 20 / strength 2 / darkness 1, no shadows (by design,
  `PlateRenderer.kt:262`) (sheetV).

## Findings (untested fixes; none changed)
1. **Shadow spills off the bed** onto the empty background past the bed outline with long shadows (el 20, or az 0
   from the pillar near the edge): a dark smear in mid-air (L2/L6 top, M4, M5). The receiver quad isn't clipped to the bed.
2. **Section view ratio is measured over all plates' objects** (`Viewport.kt:296-300` -> `SectionView.plane`): on plate 1
   with the table on plate 2, cut 0.2/0.5 removes nothing and 0.8/0.95 removes everything (S1-S5). Desktop may do the same
   (scene-wide box); worth checking `GLCanvas3D::_get_section_view_plane` before changing.
3. **Shadow on parts is subtle at the default 50 %:** the shader drops only the key light term, scaled by darkness
   (`Shaders.kt:163-166`), so a face at a grazing key angle barely changes (my first side view looked like "no shadow on
   parts", which was wrong). Possibly fine; a design call.
4. Bias 0.2..8 mm shows almost no difference on this scene (no acne at 0.2, no gap at 8); the knob may be hard to judge.
5. Grid: in near-top views the 1 px vertical grid lines break / jump sideways at a couple of rows, and distant grid at
   grazing angles shimmers into dashes (A2, P3, T6). Not shader-related; low priority.
6. From below the bed (pitch -30) parts and their bed shadow show through the bed (A4). Probably expected.
7. Preview AO 0 vs 2: no visible difference at this zoom (V1). Not checked closer.

## Compared with the earlier Sonnet run (session e8b698f6, same day)
- Agree: shadows switch; pillar's long soft shadow; table shadow attached (top view); light direction/height/strength respond.
- Section view disagrees but both are right: Sonnet tested before the table existed on plate 2 (0.2 cut the parts, 0.5 removed
  the cube); after the table was added I got nothing at 0.2/0.5 and everything at 0.8. That confirms finding 2: the cut depends on
  objects on other plates.
- Bed edge: Sonnet's corner shot didn't include the edge; with the edge in frame the shadow spills past it (finding 1, new).
- "Nothing cast onto the box" (Sonnet) was true for that light (the shadow pointed away from the box); with the light behind the
  pillar, parts do receive shadows (N1-N6). Not tested by Sonnet.
- Only in this run: darkness/softness/bias extremes (bias ~no effect), AO, alignment check, table from the side, preview unaffected
  (Sonnet said so from the code only), strength 2.0 clipping.

## State left on the phone
Lab restored to what it was before the test (darkness 0.5, softness **12**, bias 2, light defaults). AO 0, shadows on,
Prepare tab. The test sliced plate 1 (preview cache only). The original AO level before the test wasn't recorded (now 0).
