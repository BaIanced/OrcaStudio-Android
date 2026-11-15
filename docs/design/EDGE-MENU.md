# Edge menu, hold menu, shortcuts, brush strip (visual round 4) - spec  [DRAFT, complete; waiting on the user]

Status 2026-11-15 (Opus, session 2): all sections written. Build tasks (section 10) go into MASTER-PLAN only after the
user says yes. Source mockup: `docs/audit/mockups/visual-round4.html` (artifact J1GKjZ8xupCgfXJcFiabaC). User verdicts:
ANSWERS 32 (round 3), 36 (pins broken, A strip liked + scrub / fade / swipe-up size), 37 (Edge panel warm, Hold menu warm).

## 1. What the user asked for
- Hot (round 3): G = hold / pen menu with nested submenus (not necessarily radial); same nesting in an E-style edge
  panel (Samsung Edge). L = pin what you use. The left column of round badges must go; the floating card covers the work.
- Round 4: Edge panel warm, Hold menu warm (no notes). Pins: "as far as I could tell pins don't work".
- Brush strip A (still, under the plate, does not turn with it): swipe left / right on the strip scrubs through the
  brushes; on landing the others fade out (or dim), leaving only the chosen one; swipe up on the strip = size up / down.

## 2. Facts (checked in source, 566dc0c + branch docs)
Paths relative to `android/app/src/main/java/app/orcaandroid/`.
- The badge column = `ToolButtons` `ui/prepare/PrepareOverlay.kt:599-718` (`ToolButton` 721-737, size
  `ViewDefaults.TOOL_BUTTON_DP`). Called at `PrepareOverlay.kt:196` (column, under `ViewButtons(rail = true)` :193, only
  when `workspace.focus == null`) and `:219` (movable row; + drags it, long press collapses it, :222-240). Entries, in
  order: Add (`AddMenu`), Arrange, Auto orient, Select multiple, Snap, Move lock; multi-selection: Duplicate, Assemble,
  Delete; one selection: Lay on face, Cut, Sculpt, Paint, Variable layer height, Duplicate, Delete, Split (objects /
  parts), More (Edit shape, Add volume group x5, Mesh tools group: Boolean, Simplify, Deform, Blend, Hollow, Repair,
  Check; then Sculpt + Mesh edit unless the room owns them; Measure); nothing selected: Measure.
- `AddMenu` `ui/prepare/PrepareDialogs.kt:73-91`: Import, Shape, Text, SVG, Make a part (`DesignEntries`
  `ui/prepare/DesignEntries.kt:35`, hidden inside a room), Samples, divider, Calibration (off while one runs).
- The hold menu = `ViewContextMenu` `PrepareOverlay.kt:345-424`, a Material `DropdownMenu` at the finger (called :287).
  Rows: Edit shape (`canEditShape`, T-051); with a selection: Duplicate, Delete, Center on plate; 2+ selected: an align
  icon row (6) + Assemble; one object: Edit in builder (unless the room owns it), Select multiple, Lay on face, Resize /
  Rotate toggles (single only), Add modifier, Boolean (2+ parts), Object settings; empty bed: Import, Shape, Text, SVG,
  Make a part, Arrange. So it already carries actions the badges don't (Center, Align, Resize, Rotate, Object settings).
- Opening: `PlateView.kt:314-317` long press without a drag, `:597` quick two-finger tap, `:715` right click / pen
  button on release -> `Viewport.kt:384-388` -> `SceneController.openContextMenu` (`ui/SceneController.kt:532-538`,
  picks the object under the point and selects it) -> `workspace.contextMenu = x to y`; closed `:566`.
- Pen: `PenConfig.button2` defaults to `PenAction.MENU` (`core/PenConfig.kt:21`), which sets `rightButton`
  (`PlateView.kt:528`), so the Note's S Pen button already opens this menu.
- Slide-through hook exists but is unused: `PlateView.Listener.onLongPressTouch(x, y, held)` (`PlateView.kt:82`) is
  called after the long press while the finger stays down (:318, :625) and on lift (:713, :767); no host overrides it.
- Focus room toolbar: `ui/prepare/RoomToolbar.kt:57-82` (Shapes, Push / pull, Reshape, Mesh edit, Sketch; 48 dp row),
  shown from `ui/AppScaffold.kt:323` while `workspace.focus != null`.
- Reshape = `Tool.Sculpt(brush, radius, strength, invert, mirror)` `ui/AppState.kt:68`; brushes (7, not the mockup's 8):
  draw, inflate, smooth, flatten, pinch, crease, grab (`ui/prepare/ToolPanel.kt:216-218`; MCP list `ai/McpTools.kt:967`;
  default `core/AppSettings.kt:428` = grab). Card body `SculptOptions` `ToolPanel.kt:313-344`: brush chips, mirror X/Y/Z
  bits, log size slider `SCULPT_MIN_MM..SCULPT_MAX_MM` (`ScrubSlider "sculpt_size"`), strength slider + invert (not for
  grab; invert not for smooth). The card is a `ToolCard` (the floating window the user says covers the work).
- Settings: Experimental section `ui/settings/MoreScreen.kt:221-224` (holds the focus room switch); booleans live in
  `core/AppSettings.kt` (`prefs.getBoolean("key", default)`) and mirror into `chrome` (`ui/Store.kt:76`, `AppState.kt:343`).
- Android gesture navigation takes Back from both side edges; a handle there needs `Modifier.systemGestureExclusion()`,
  which Android honours for at most 200 dp of height per edge.
- Name clash: Batch 6 (MASTER-PLAN T-060..T-065) is a sculpt "Pin field" (`docs/design/PINS.md`). Decision: the UI pins
  are called **shortcuts** in app text and code (`Shortcut`), "pins" only in this design talk. (My deviation; say so.)

## 3. Why the mockup's pins failed (source-checked in the mockup, not driven)
- Pinning was a hidden 650 ms hold on a menu leaf; nothing on screen said so.
- Pins were per context: a pin made on the plate vanished inside Reshape and the other way round.
- Most plate-menu leaves only toasted "(not in this sketch)", so a fresh pin did nothing on tap.
- Holding a pin removed it (same gesture as making one), so the size slider could be lost by accident.
- New pins landed at fixed spots (x 186 / 116 / 46, y 500) that could sit under the brush strip or the bottom panel.

## 4. The tree (one source for edge panel, hold menu and shortcuts)
`ui/prepare/MenuTree.kt` (new): `MenuNode(id, label, icon, enabled: (UiState) -> Boolean, visible: (UiState) -> Boolean,
action: MenuAction?, children: List<MenuNode>, scope)`. `MenuAction` is either a direct call on `vm` or a dialog key
(the existing `AddDialogs` keys: primitive, text, svg, samples, calib, boolean, simplify, deform, blend, hollow, check)
or a launcher key (import). Ids are stable strings (`plate.arrange`, `object.cut`, `reshape.brush.inflate`): shortcuts
store them, so never rename an id. Toggles (Snap, Select multiple, Resize, Rotate, view toggles) show a check, like the
View menu does today (`PrepareOverlay.kt:503`).

Plate context (`workspace.focus == null`, tool not Sculpt). Top level, in this order; a level shows only its visible rows:
| Top | Children | Shown when |
|---|---|---|
| Add | Import, Shape, Text, SVG, Make a part, Samples, Calibration | always |
| Object | Edit shape, Lay on face, Cut, Paint, Variable layer height, Resize, Rotate, Center on plate, Object settings | one selection |
| Selection | Duplicate, Delete, Center on plate, Align (6 rows), Assemble (2+) | multi-selection |
| Edit model | Reshape, Push / pull (both unless the room owns them), Split > (Objects, Parts), Add volume > (5), Mesh tools > (Boolean, Simplify, Deform, Blend, Hollow, Repair, Check), Edit in builder | one selection |
| Arrange | Arrange, Auto orient, Select multiple, Snap, Move lock (cycles, shows the mode) | scene not empty |
| Duplicate / Delete | as two top rows, not a submenu | one selection |
| Measure | (leaf) | scene not empty |
| View | the View menu rows (`PrepareOverlay.kt:494-538`) | always |
Undo / Redo stay in the top bar (they leave the column with the badges: `TopBar(viewButtons = true)`).

Room context (`workspace.focus != null`): the room toolbar stays as it is (it is a docked strip, not a floating card).
The tree there: the room tool's own options (Shapes: the Add palette entries; Push / pull: none yet; Mesh edit: its card
rows; Sketch: none) + View. Only Reshape gets its own tree in this batch; the others keep their cards (later batch).

Reshape context (`tool is Tool.Sculpt`, plate or room): Brush > (7, radio), Strength (a slider row), Invert (toggle;
hidden for grab and smooth), Mirror > (X, Y, Z toggles), View, Done (leaves the tool; in the room = Shapes tool).
Size is not in the tree: it is the strip's swipe and the size slider (section 8).

Check (JVM test): for each of the 4 selection states the tree reaches every action `ToolButtons` and `ViewContextMenu`
reach today (a list in the test), so turning the setting on loses nothing.

## 5. Edge panel
- Handle: a 12 x 64 dp rounded tab on the right edge (left with the setting "Edge menu on the left"), centred at 45 %
  of the plate area height, touch area 32 x 96 dp, `systemGestureExclusion` on the touch area (inside the 200 dp
  cap). Overlay alpha follows `LocalOverlayAlpha`. Hidden while a dialog, the Reshape size drag or the hold menu is up.
- Two ways in: tap the handle = panel opens and stays; drag out from the handle = slide-through (the finger's row
  highlights; lift on a leaf runs it and closes; lift on a branch opens it and the panel stays; lift outside = close).
- Panel: 232 dp wide (max 70 % of the plate width), full plate height minus the top bar, `surfaceContainerHigh`,
  48 dp rows (icon, label, pin button at the end of leaves, chevron on branches). Submenus drill down in place (the
  level slides in from the edge side, a back row "< Object" on top), not cascading: two 232 dp columns do not fit on a
  360 dp phone. Pen / mouse hover over a branch for 400 ms opens it (G: "submenus on hover").
- Close: tap outside, swipe the panel back toward its edge, system Back, or running a leaf (except toggles: a toggle
  flips and the panel stays so several can be set).
- Opening state is saved per session only (not persisted); the last level opened is not remembered (always top level).

## 6. Hold menu
- `ViewContextMenu` renders the same tree, starting at the level that fits the hit: on an object = Object's children
  first, then the other top rows below a divider; on the empty bed = Add's children, then Arrange, View; in Reshape =
  the Reshape tree. Same drill-down, back row and pin buttons as the edge panel.
- Replace the `DropdownMenu` with a `Popup` placed at the finger, clamped inside the plate area, opening toward the
  plate's centre. Rows 48 dp; width 232 dp.
- Slide-through at the finger: implement `onLongPressTouch` in `Viewport.kt` (hook at `PlateView.kt:82`, already fed
  :318 / :625 / :713 / :767): moves highlight the row under the finger (popup-local hit test), `held = false` over a
  leaf runs it, over a branch opens it, elsewhere leaves the menu open as today (so tap-to-pick keeps working).
- Pen (Note): the S Pen button already opens it (`PenConfig.kt:21`, `PlateView.kt:528`); hover opens branches (400 ms).
  Right click and two-finger tap: unchanged entry points.
- The align icon row stays an icon row (one row, 6 icons) inside Selection > Align.

## 7. Shortcuts (the fixed "pins")
- Make / remove: a pin icon button (24 dp icon, 48 dp target) at the end of every leaf row, filled when pinned. Tap it =
  toggle; a snackbar says "Added to shortcuts" / "Removed" with Undo. No hidden hold anywhere.
- State: `AppSettings.shortcuts` (key `menu_shortcuts`), an ordered list of node ids, one string joined with `,`. Scope
  comes from the id prefix: `plate.*` / `object.*` / `view.*` show outside Reshape, `reshape.*` only in Reshape, `view.*`
  in both. Unknown ids (renamed or removed) are skipped and dropped on the next save.
- Dock: one row of 48 dp round buttons, end side, 8 dp above the bottom panel / Slice (vertical column beside the
  handle in landscape). Max 6 per scope; at 6 the pin buttons of unpinned rows are disabled with the text "Shortcuts
  full". A shortcut whose node is disabled now (Cut with nothing selected) stays in place, dimmed; tapping it shows why
  ("Select an object first"). Toggles show their on state.
- Edit: long press the dock = edit mode (an x on each, drag to reorder, Done chip). This is the only way a shortcut is
  removed outside the menu, and it never happens on a single long press of one shortcut.
- First run with the setting on: the dock is empty and shows a one-line hint "Tap the pin in a menu to keep a tool here"
  until the first shortcut exists.

## 8. Brush strip and size (Reshape with the setting on)
- The `SculptOptions` card is not shown; in its place: the strip (bottom, full width minus the dock, 56 dp high, above
  the bottom panel) and a size slider (vertical, 160 dp, on the side opposite the handle, value in mm beside the thumb).
- Strip content: 7 brush icons with labels, chosen one centred and highlighted. Gestures (axis locks after touch slop
  on the dominant axis):
  - horizontal drag: one brush per 28 dp, live (the brush changes as the finger passes, light haptic tick each step);
  - vertical drag: size on the same log scale as today (`SCULPT_MIN_MM..SCULPT_MAX_MM` over 240 dp, up = bigger), a
    ring of the new radius drawn at the plate's last brush point (else the view centre) while the finger is down;
  - tap a brush = choose it.
- Fade: 600 ms after the finger lifts, the other brushes animate to 25 % alpha (200 ms); the chosen one stays full. A
  tap on a dimmed brush first undims all (no pick); a second tap picks. Any drag on the strip undims at once.
- TalkBack: the strip is one node, state "Brush inflate, size 12 mm", custom actions Next brush, Previous brush, Bigger,
  Smaller; the size slider is a normal slider with the same value text.
- Strength, invert, mirror move into the Reshape tree (section 4); they are reachable from the edge panel, the hold
  menu (long press in Reshape opens the menu, not a stroke: today a long press in sculpt mode is checked in the
  build task, since the stroke handler may already take it) and shortcuts.

## 9. Setting
- `edgeMenu`: AppSettings key `edge_menu`, default off, Experimental section under the focus room switch, title "Edge
  menu", text "Tools in a menu at the screen edge and under a long press, instead of the button column". Plus "Edge
  menu on the left" (same section, shown when on).
- Off: everything as today (badges, `DropdownMenu` hold menu, Reshape card); no dock, no handle.
- On: `ToolButtons` is not composed at :196 or :219 (the movable row and its stored position stay in settings,
  unused); `ViewButtons` move to the top bar; hold menu = tree; Reshape = strip + slider.

## 10. Build tasks (proposal: MASTER-PLAN Batch 8, T-080..T-086; ask the user before adding)
Each ends with a WSA VD device check (`TARGET=wsa orca-vd.sh start`, mcpao.sh state, `phone-ui.py layout`, cropped
shots only for looks) with the setting on and off, plus compile + JVM tests + smoke.
- T-080 Tree: `MenuTree.kt`, ids, scopes, the coverage JVM test (section 4). model=sonnet, effort=medium. Done when
  the test passes for 4 selection states and Reshape.
- T-081 Setting + edge panel + handle (sections 5, 9). model=opus, effort=high (gesture exclusion, slide-through).
  Done when on the VD: tap and slide-through both run Arrange and Object > Cut; Back closes; setting off = no change.
- T-082 Hold menu on the tree (section 6), incl. `onLongPressTouch` slide-through. model=opus, effort=high. Done when
  hold-drag-release on an object runs Duplicate, tap-pick still works, right click opens it, align row works.
- T-083 Shortcuts: state, dock, edit mode (section 7). model=sonnet, effort=high. Done when a pin made in the panel
  shows in the dock, survives an app restart, Reshape ones show only in Reshape, the 7th pin is refused.
- T-084 Brush strip + size slider + Reshape tree (section 8). model=opus, effort=high. Done when on the VD a horizontal
  swipe changes `Tool.Sculpt.brush` (mcpao state), vertical changes radius, others fade after 600 ms, TalkBack actions
  listed in the node info, strength / invert / mirror change from the panel.
- T-085 Phone trial build (CI dispatch, install on the phone per memory) + user feel check list in SIGNOFF-CHECKLIST.
  model=sonnet, effort=low.
- T-086 Batch 8 review gate (as T-055). model=fable, effort=high (a standalone user launch, memory no-fable-dispatch).

Deviations from the mockup, to tell the user: shortcuts not free-floating (one dock); pin = visible pin button, not a
hidden hold; submenus drill down in place, not cascade; size is a built-in slider + strip swipe, not a removable pin;
the tree follows the app's real tools (7 brushes, real menus), not the mockup's invented one; only Reshape loses its
card in this batch.
