# Edge menu, hold menu, shortcuts, brush strip (visual round 4) - spec  [DRAFT, IN PROGRESS]

Status 2026-11-15 (Opus, session 1): facts and decisions below; sections 3-8 and the build tasks are still to write.
Source mockup: `docs/audit/mockups/visual-round4.html` (artifact J1GKjZ8xupCgfXJcFiabaC). User verdicts: ANSWERS 32
(round 3), 36 (pins broken, A strip liked + scrub / fade / swipe-up size), 37 (Edge panel warm, Hold menu warm).

## 1. What the user asked for
- Hot (round 3): G = hold / pen menu with nested submenus (not necessarily radial); same nesting in an E-style edge
  panel (Samsung Edge). L = pin what you use. The left column of round badges must go; the floating card covers the work.
- Round 4: Edge panel warm, Hold menu warm (no notes). Pins: "as far as I could tell pins don't work".
- Brush strip A (still, under the plate, does not turn with it): swipe left / right on the strip scrubs through the
  brushes; on landing the others fade out (or dim), leaving only the chosen one; swipe up on the strip = size up / down.

## 2. Facts (checked in source, 566dc0c + branch docs)
Paths relative to `android/app/src/main/java/app/orcaandroid/`.
- The badge column = `ToolButtons` `ui/prepare/PrepareOverlay.kt:599-718` (`ToolButton` 721-737, size
  `ViewDefaults.TOOL_BUTTON_DP`). Called at `PrepareOverlay.kt:196` (only when `workspace.focus == null`) and `:219`
  (movable row with drag / long-press on +). Entries, in order: Add (AddMenu), Arrange, Auto orient, Select multiple,
  Snap, Move lock; multi-selection: Duplicate, Assemble, Delete; one selection: Lay on face, Cut, Sculpt, Paint, Variable
  layer height, Duplicate, Delete, Split (objects / parts), More (Edit shape, Add volume group x5, Mesh tools group:
  Boolean, Simplify, Deform, Blend, Hollow, Repair, Check; then Sculpt + Mesh edit unless the room owns them; Measure);
  nothing selected: Measure. That is the tree the edge menu must carry (not the mockup's invented one).
- A hold menu already exists: `ViewContextMenu` `PrepareOverlay.kt:345` (called :287), opened by
  `SceneController.kt:538` (`workspace.contextMenu = x to y`, `AppState.kt:170`), closed `SceneController.kt:566`.
  NOT YET READ: its rows (read 345-427 next session; T-051 put "Edit shape" first in it).
- Focus room toolbar: `ui/prepare/RoomToolbar.kt:41-58` (Shapes, Push / pull, Reshape, Mesh edit, Sketch), shown from
  `ui/AppScaffold.kt:323` while `workspace.focus != null`.
- Reshape = `Tool.Sculpt(brush, radius, strength, invert, mirror)` `ui/AppState.kt:68`; brushes (7, not the mockup's 8):
  draw, inflate, smooth, flatten, pinch, crease, grab (`ui/prepare/ToolPanel.kt:216-218`; MCP list `ai/McpTools.kt:967`;
  default `core/AppSettings.kt:428` = grab). Card body `SculptOptions` `ToolPanel.kt:313-344`: brush chips, mirror X/Y/Z
  bits, log size slider `SCULPT_MIN_MM..SCULPT_MAX_MM` (`ScrubSlider "sculpt_size"`), strength slider + invert (not for
  grab; invert not for smooth). The card is a `ToolCard` (the floating window the user says covers the work).
- Name clash: Batch 6 (MASTER-PLAN T-060..T-065) is a sculpt "Pin field" (`docs/design/PINS.md`). Decision: the UI pins
  are called **shortcuts** in app text and code (`Shortcut`), "pins" only in this design talk. (My deviation; say so.)

## 3. Why the mockup's pins failed (source-checked in the mockup, not driven)
- Pinning was a hidden 650 ms hold on a menu leaf; nothing on screen said so.
- Pins were per context: a pin made on the plate vanished inside Reshape and the other way round.
- Most plate-menu leaves only toasted "(not in this sketch)", so a fresh pin did nothing on tap.
- Holding a pin removed it (same gesture as making one), so the size slider could be lost by accident.
- New pins landed at fixed spots (x 186 / 116 / 46, y 500) that could sit under the brush strip or the bottom panel.

## Decisions so far (to expand)
- One tree object (`MenuNode`: id, label, icon, enabled(state), action, children) built from today's `ToolButtons`
  entries; both the edge panel and `ViewContextMenu` render it. Delete `ToolButtons` column when the setting is on.
- Shortcuts fixed design: each leaf row in a menu shows a pin icon at its end; tap the icon = add / remove shortcut
  (no hidden hold). Shortcuts sit in one dock (a short row above the bottom panel / beside the edge handle), not free
  floating; the dock holds context shortcuts (Reshape ones show only in Reshape) plus global ones, max ~5 visible, edit
  mode (remove / reorder) from the dock's own long press. Size slider = a built-in, always-present Reshape control (not
  a removable pin).
- Brush strip A: still strip under the plate in Reshape; horizontal swipe scrubs (one brush per ~22 dp), on lift the
  others fade to ~25 % alpha after 600 ms; tap the strip = show all again; vertical swipe on the strip = size (log
  scale, ring preview on the plate); strength / invert / mirror move to the edge menu's Reshape tree.

## Still to write (next session)
4. Tree contents table (plate context, one selection, multi, nothing selected, room, Reshape).
5. Edge panel: handle place (right; left-hand setting), slide-through + tap modes, widths in dp, 48 dp rows.
6. Hold menu: same tree at the finger; what changes in `ViewContextMenu`; Note pen button.
7. Shortcuts: state + persistence (AppSettings key), dock layout, limits.
8. Brush strip: gestures, thresholds, fade timing, accessibility (TalkBack actions), where strength / invert go.
9. Setting (`edgeMenu`, Experimental, default off) and what stays reachable while off.
10. Build tasks (MASTER-PLAN format, batch 8?) each with a VD device check; ask the user before they go in the plan.
