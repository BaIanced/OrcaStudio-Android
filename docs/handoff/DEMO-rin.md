# Full MCP demo: "Rin" chibi swordswoman (user-approved plan, 2026-10-17)

Goal: show off the MCP route by building a detailed anime figure on WSA, then measure cost and list gaps.
Budget (full version, user's choice after /usage showed headroom): up to about 25 calls, 6-8 screenshots at
600 px, up to two fix rounds, print settings tweaked. Still batch MCP steps where it doesn't hurt the result.

Figure (80 mm tall, chibi, head about 40%): stretched-sphere head; eyes = shallow ellipsoid holes with an
iris sphere and a highlight sphere; tiny mouth hole; dome hair cap, about 10 rotated cone bangs, repeat-circular
back spikes, mirrored twin tails (chained stretched spheres/cones), torus-arc ahoge; revolved-sketch torso; flared
skirt (revolve plus circular repeat of wedge pleats); mirrored legs and boots; torus scarf with two angled
rounded_box tails; katana (thin box blade, torus guard, cylinder hilt) across the body; stepped cylinder base with
raised "RIN" text. About 70-90 primitives.

Steps (batch several MCP steps per Bash call):
1. WSA `127.0.0.1:58526`: start the app (`monkey -p io.github.baianced.orcastudio_android -c
   android.intent.category.LAUNCHER 1`, about 12 s), `MCP_DEV=127.0.0.1:58526 ~/orca-android-tools/mcpao.sh
   'design_describe:{}'`. Overwrites the throwaway phone-stand draft.
2. Write the whole design as one JSON file (format: `design_set` tool description in `ai/McpTools.kt` FORMAT),
   send it with one `design_set` call (curl `-d @file`, or mcptest.sh `tool:{json}`).
3. `design_to_plate` (tap Allow if asked), `view_set` iso / front / side + `view_screenshot` size 600.
4. Up to two fix rounds (proportions, gaps, floating parts): batched `shape_update`s, re-shoot after each.
5. `setting_find` + `settings_edit`: 0.12 mm layers, tree supports; then `slice` + `slice_result`.
6. Send the user the final iso + front shots (SendUserFile). Close the app.
7. Cost: sum usage (input, cache read, cache write, output) from this session's transcript JSONL in
   `~/.claude/projects/C--Users-Vince-OrcaProjectsGithub/`. The user checks `/usage` before and after.
8. Gap report: what to add or fix so the MCP route is useful and impressive, ranked by impact.

Keep the design JSON in `~/orca-android-tools/rin.json` so Sonnet can replay it later.

## Result (run 2026-10-17, done)
- rin.json didn't exist before; written this run (31 top-level shapes, 80+ primitives with repeats). Final (post-fix)
  design saved there from design_get. Shots: `~/orca-android-tools/rin/` (iso3/front3 sent to the user).
- 80 mm, 0.12 mm, tree supports: 2 h 55 m, 27.3 g, 668 layers, no warnings. Left flaw: bangs stick out like a visor
  in side view (fix: tilt cones to rot X ~190, r 12.5); katana on the back is barely visible.
- Cost: 44 requests, 2.41 M cache read, 64 K cache write, 27 K output; /usage after: session 7%, week 31%.
- Gaps (#1 #2 #4 #5 fixed r291, 2026-10-17), by impact: 1. every design edit opens the editor, so view_* fails "No 3D view" until Close editor + Prepare
  (adb taps); tools should switch to Prepare themselves. 2. view "fit" frames the bed; iso/front/left + fit gave
  identical images; need frame-object. 3. no text check for floating/protruding parts. 4. shape_update rejects
  extra.revolve (design_set accepts it). 5. design_set ignores the name (stays "Shape design"). 6. Document: design_set
  {design:{...}} wrapper, mirror flips about the shape's own pos (mirror a group at the origin), repeat angle rules.
  7. slice already returns the result; say so so agents skip slice_result.

## Replay (2026-10-17, Sonnet 5.5, done)
- rin.json -> `design_set {design:<json>}` (rin_arg.json wrapper) -> design_to_plate -> view_set iso/front + 600 px shots ->
  settings_edit (process: layer_height 0.12, enable_support 1; args `type` + `values`) -> slice. Result matches the Opus run:
  2 h 54 m, 28.6 g, 668 layers, no warnings. About 10 tool calls, no fix rounds, no adb taps (gaps #1/#2 fixes held).
- Cost (whole session incl. reading handoff): 14 requests, 453 K cache read, 25 K cache write, 3 K output, vs 44 requests,
  2.41 M read, 64 K write, 27 K output for the Opus build: about 5x fewer requests, ~9x less output.
- Note: setting_find needs `type`; settings_edit takes `values` (not `changes`).
