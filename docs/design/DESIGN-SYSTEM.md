# Design system (T-030; direction A, B builds on the same tokens)

Sources: `ui/theme/AppColors.kt`, `ui/prepare/PaintShop.kt:39-59`, `docs/audit/mockups/gate-directions.html`, `04-uiux.md`
(U-01, U-08, U-14). `S/` = `android/app/src/main/java/app/orcaandroid/`. Targets: phone, tablet, Chromebook.
Nothing is specified for the pie menu, corner-resize gate, light lab sliders or view cube: they are being cut.

## 1. Vocabulary
| Room | Meaning | Is today's |
|---|---|---|
| **Make** | build a new part | builder and OpenSCAD pane (also when opened on an existing object) |
| **Reshape** | change this part | Sculpt and Edit mesh. First tool: **Push / pull** (Edit mesh, Face) |
| **Paint** | colour, supports, seam, fuzzy skin | Paint shop |
| **Cut** | cut this part | Cut card |

Not allowed as a button, menu row or title: Design (as an entry), builder, Build workspace, Sculpt, Edit mesh, Mesh tools,
Paint shop, editor (for the Make room). "design" stays only as the noun for an OpenSCAD file and in "Design mode" (theme
colours). Spelling is always `Push / pull`.

## 2. Colour tokens
Room colours are four `Slot`s in `AppColors.kt`, in a new `Region.ROOMS("rooms", R.string.region_rooms, listOf(ROOM_MAKE,
ROOM_RESHAPE, ROOM_PAINT, ROOM_CUT))`, read with `LocalAppColors.current.color(Region.ROOMS, room.slot)`. Each slot picks
dark when `scheme.background.luminance() < 0.5f`, as `VIEW_SELECTION` does.

| Token (Kotlin) | Light | Dark | Note |
|---|---|---|---|
| `ROOM_MAKE` | `0xFF2E7D4F` | `0xFF7FD1A0` | green |
| `ROOM_RESHAPE` | `0xFF1F7A8C` | `0xFF4FB6C9` | teal, the mockup's `--shape` |
| `ROOM_PAINT` | `0xFF7F67BE` | `0xFFA98BF0` | light = today's `SHOP_FRAME`, which goes away |
| `ROOM_CUT` | `0xFFB0306F` | `0xFFF28DBB` | rose |
| `AXIS_X`, `AXIS_Y`, `AXIS_Z` | `0xFFEF5350`, `0xFF66BB6A`, `0xFF42A5F5` | same | top-level vals in `AppColors.kt`; `measureLockColor` (`Measure.kt:149`) reads them |
| `LIVE_LABEL`, `ON_LIVE_LABEL` | `0xFF2B2C32`, `0xFFEDEEF0` | same | dark pill in both themes (values of `OverlayParts.kt:278`) |

By calculation: text on a strip is `onColor(roomColour)` (existing helper) and reaches 4.5:1 for all eight values; as a
3 dp frame each reaches 3:1 on its theme's default `VIEW_BACKGROUND`. None is near the accent orange, selection blue or
error red. Colour is never the only signal: a strip always carries the room icon and the title.

Surfaces use existing Material slots: card and dock `surfaceContainerHigh` (no tonal elevation), text `onSurface`, hints
`onSurfaceVariant`, dividers `outlineVariant`, primary action `primary`. An axis colour is an arrow, bar or dot beside the
letter X / Y / Z, never the text colour (green is about 2:1 on a light card).

## 3. Type scale (4 sizes)
No custom `Typography`: the tokens are the Material slots. New components use only these.

| Size | Slots (`MaterialTheme.typography.`) | Use |
|---|---|---|
| 22 sp | `titleLarge` | dialog and screen titles |
| 16 sp | `titleMedium` | strip title, live-label number |
| 14 sp | `bodyMedium` (text, fields), `labelLarge` (buttons), `titleSmall` (card title) | default |
| 12 sp | `bodySmall` (hints), `labelMedium` (units, captions) | smallest allowed |

Not in new components: `labelSmall` (11 sp), `headlineSmall`. Numbers that change while shown use tabular figures
(`fontFeatureSettings = "tnum"`).

## 4. Spacing and targets
New file `S/ui/theme/Tokens.kt`:

| Token | Value | Use |
|---|---|---|
| `Space.XS`, `S`, `M`, `L`, `XL` | 4, 8, 12, 16, 24 dp | gaps and padding (existing 6 dp gaps become 8 when a file is touched) |
| `TouchTarget.MIN` | 48 dp | every clickable box at default scale |
| `TouchTarget.FLOOR` | 40 dp | absolute floor after any density scaling |
| `Modifier.touchTarget()` | `sizeIn(minWidth = TouchTarget.MIN, minHeight = TouchTarget.MIN)` | every icon button |

Size the `Icon` (24 dp; 20 dp in dense rows), never the button: `IconButton(Modifier.size(n))` with n < 48 dp is not
allowed (U-14: 17 sites). A scale applied through `LocalDensity` is clamped for controls to
`TouchTarget.FLOOR / TouchTarget.MIN` (0.833) or more. `ViewDefaults.TOOL_BUTTON_DP` stays 48.

## 5. Mode strip
`S/ui/components/ModeStrip.kt`; generalises `PaintShopBar` and `shopFrame`.

```kotlin
enum class Room(val slot: Slot, @StringRes val label: Int, val icon: ImageVector) {
    MAKE(ROOM_MAKE, R.string.room_make, Icons.Default.Architecture),
    RESHAPE(ROOM_RESHAPE, R.string.room_reshape, Icons.Default.Gesture),
    PAINT(ROOM_PAINT, R.string.paint, Icons.Default.Brush),
    CUT(ROOM_CUT, R.string.cut, Icons.Default.ContentCut) }
@Composable fun ModeStrip(room: Room, name: String, canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit,
    onRedo: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {})
@Composable fun roomFrame(room: Room?): Modifier   // border + padding of ModeStripDefaults.FRAME; null = no frame
```

Top edge of the workspace, full width, below the display-cutout inset, same in every orientation. Left to right:

| Slot | Token (`ModeStripDefaults.`) | Size |
|---|---|---|
| strip | `HEIGHT` | 48 dp high |
| padding | `PAD_START`, `PAD_END` | 16 dp, 8 dp |
| room icon, not clickable | `ICON` | 24 dp, then 8 dp |
| room word, `labelLarge` | `WORD_FROM` | only when the strip is 600 dp wide or more |
| title `stringResource(R.string.mode_editing, name)`, `titleMedium`, one line, ellipsis | `TITLE_MIN` | weight 1, min 96 dp |
| `actions` (Paint's view menu only) | | 48 dp each |
| Undo, Redo (`Icons.AutoMirrored.Filled.Undo`, `Redo`) | | 48 x 48 dp each, icon 24 dp |
| **Done** (`R.string.done`), `labelLarge` | `DONE_MIN_WIDTH`, `DONE_HEIGHT` | 4 dp gap; min 72 dp wide; pill 36 dp high in a 48 dp box |
| frame round the 3D view | `FRAME` | 3 dp, room colour |

- Container = room colour, content = `onColor(roomColour)`. Done is inverted (container = content colour, label = room
  colour). A disabled Undo / Redo is content at 38 % alpha.
- Done is the last child, at the top end corner, always. It does what Back does today in that room.
- Undo and Redo are always composed: an empty stack disables them, never hides them. They drive the stack active in the
  room (builder stack in Make, plate stack elsewhere).
- Short width drops, in order: room word, room icon; then the title shrinks to `TITLE_MIN`. Undo, Redo and Done never drop
  (at 360 dp: 228 dp fixed, 132 dp title).
- Room per tool: builder and code pane = `MAKE`; `Tool.Sculpt`, `Tool.MeshEdit` = `RESHAPE`; `Tool.Paint` = `PAINT`;
  `Tool.Cut` = `CUT`. What else a room hides is not this component's concern (direction A keeps today's).

## 6. Tool card
`S/ui/components/ToolCard.kt`: `ToolCard(title, side, footer, content)` and `CardActionRow(text, actions)`. For the Reshape,
Push / pull and Cut cards (Make keeps its shape panel). Docked, not floating or draggable, so it never covers the model.

| Zone | Token (`ToolCardDefaults.`) | Size and rule |
|---|---|---|
| header | `HEADER` | 48 dp. Title `titleSmall` at the start; Fold button 48 x 48 dp in the last slot. In a room there is no Close (Done is the exit); outside rooms (Measure, Lay on face) Close is last and Fold before it |
| body | `PAD`, `GAP` | scrolls; padding 12 dp, row gap 8 dp, control rows 48 dp high |
| action row | `ROW`, `ACTION_WIDTH` | 48 dp high. Text slot: weight 1, one line, ellipsis. Then at most 2 buttons, each exactly 80 dp wide, end-aligned, 4 dp apart, so they never move with the text (`Grow`, `Shrink`) |
| footer | `FOOTER`, `PRIMARY_MIN_WIDTH` | 56 dp, pinned below the scroll area, 1 dp `outlineVariant` line above. Primary button (`Apply`, `Perform cut`) at the bottom end: filled, 40 dp high in a 48 dp box, min 96 dp wide. Optional text button at the bottom start. Only the enabled state changes |
| corners | `RADIUS` | 16 dp on the corners facing the view; shadow 2 dp |

Docking (the rule the Paint dock uses, `AppScaffold.kt:426`):

| Window | Dock | Token | Size |
|---|---|---|---|
| under 600 dp wide, or not wider than tall | bottom, full width, above the navigation inset | `MAX_HEIGHT_FRACTION` | up to 0.45 of the window height; folded = header only |
| 600 dp or wider and wider than tall | end side, full height under the strip | `SIDE_WIDTH` | 320 dp |
| `WIDE_LAYOUT` (840 dp) or wider: Chromebook, landscape tablet | end side | `SIDE_WIDTH_WIDE` | 360 dp |

## 7. Live label
`S/ui/components/LiveLabel.kt`: `LiveLabel(value: Float, unit: String, axis: Int, angle: Float, onType: ((Float) -> Unit)?)`;
`axis` 0 / 1 / 2 = X / Y / Z, -1 = free.

| Part | Token (`LiveLabelDefaults.`) | Size and rule |
|---|---|---|
| pill | `HEIGHT`, `PAD_H` | 32 dp high, fully rounded, 12 dp side padding, 1 dp border `ON_LIVE_LABEL` at 30 % alpha; touch box 48 dp high, min 64 dp wide |
| axis arrow | `ARROW` | 16 dp `Icons.AutoMirrored.Filled.ArrowForward`, turned to the move's direction on screen; tinted `AXIS_*` and followed by the letter when locked to an axis, `ON_LIVE_LABEL` and no letter when free |
| number | | `titleMedium`, tabular, one decimal, `Locale.ROOT`; signed for Push / pull (`+5.0`) |
| unit | | `bodySmall`, 2 dp after the number (`mm`, `°`) |
| position | `OFFSET_X`, `OFFSET_Y`, `EDGE` | 24 dp to the end of and 48 dp above the finger or arrow tip; kept 8 dp inside the view; never under the finger |
| linger | `LINGER_MS` | 2000 ms after release |
| typing | `FIELD_WIDTH` | a tap (after release, or any time on a Push / pull arrow) turns the pill into a 120 x 48 dp decimal field; Enter applies the same operation as the drag, Back cancels |

The node's text is exactly number, space, unit (`3.0 mm`); the content description adds the axis. The value comes from the
operation's model-space numbers, never from screen pixels.

## 8. Notice or dialog
| Form | When | Rules |
|---|---|---|
| notice (snackbar) | the result of what the user just did; nothing to decide | one line, 60 characters at most, one action at most (`Undo`): "Already arranged", "Undone" |
| notice card (`Notices.kt`) | a lasting condition that blocks or degrades the print | title, lines, `Fix` link; closes to the "!" dot |
| dialog | the user must choose before work goes on (data loss, not undoable), must type more than one value, or a job they are waiting on failed | title `titleLarge`; one sentence, one hint; 2 buttons at most; the confirm button names the action ("Discard"), not "OK" |

Never a dialog during or right after a drag or stroke: checks after release are notices. Raw text (addresses, ports, error
codes) appears only inside a collapsed `Details` block.

## 9. Icons
One family: Material icons, filled, from `androidx.compose.material:material-icons-extended` (`Icons.Default.*`;
`Icons.AutoMirrored.Filled.*` for anything with a direction). A missing icon is drawn as an `ImageVector` in code (24 x 24
grid, 1.9 px round strokes, like `AutoOrientIcon`, `OverlayParts.kt:119`) and lives in `S/ui/components/AppIcons.kt`. No
bitmaps, no second icon pack, no Unicode glyphs as icons. Every icon-only button has a `Tip` and a content description.

## 10. String table (`res/values/strings.xml`)
| Key | Old | New | Seen in |
|---|---|---|---|
| `add_design` | Design | Make a part | + menu (`DesignEntries.kt:32`) |
| `shape_builder` | Shape builder | Make | `ScadEditor.kt:245`, `ScadHelp.kt:177` |
| `edit_in_builder` | Edit in builder | Add shapes or holes | object menu (`PrepareOverlay.kt:357`) |
| `shape_new_name` | Shape design | New part | default name (`ScadController.kt:274`) |
| `scad_close` | Close editor | removed: the strip's `done` | `ScadEditor.kt:252` |
| `paint_shop` | Paint shop | removed: strip shows `paint` and `mode_editing` | `PaintShop.kt:56` |
| `sculpt` | Sculpt | Reshape | overflow row, card title (`PrepareOverlay.kt:665`, `ToolPanel.kt:193`) |
| `sculpt_hint` | Drag over the model to sculpt; … | Drag over the model to reshape it; … | Sculpt card |
| `pen_text` | … sculpts stronger, … | … reshapes stronger, … | pen settings |
| `mesh_edit` | Edit mesh | Push / pull | overflow row; card title in Face mode (`PrepareOverlay.kt:666`, `ToolPanel.kt:194`) |
| `shape_push_pull` | Push/pull a face | Push / pull a face | `ShapeBuilder.kt:415` |
| `more_mesh_tools` | Mesh tools | More shape tools | overflow group (`PrepareOverlay.kt:654`) |
| `shape_blend_hint` | … hole rims (Build workspace). … | … hole rims (in Make). … | builder |
| `shape_edit_as_code_desc` | … (Undo in the shape builder cannot bring it back). | … (Undo in Make cannot bring it back). | builder |
| `scad_zip_variants` (both plurals) | The design opened in the editor. … | The design opened in Make. … | import notice |
| `add_openscad` | OpenSCAD model | removed: no code reference | - |

New keys: `mode_editing` = `Editing: %1$s`; `room_make` = `Make`; `room_reshape` = `Reshape`; `region_rooms` = `Mode strips`;
`coach_make` = `Make or change a model`; `mesh_edit_shrink` = `Shrink`; `mesh_edit_title_vertex` = `Move points`,
`mesh_edit_title_edge` = `Move edges` (card title in Vertex / Edge mode); `diameter` = `Diameter` (T-035).

Unchanged on purpose: `paint`, `cut`, `done`, `undo`, `redo`, the brush names (`sculpt_grab` = Grab, …), `shape_tab`, the
hard-coded `CAD` tag (`DesignEntries.kt:25`) and `OpenSCAD` label (`ScadEditor.kt:245`).

`values-de`: of the changed keys only `scad_close` and `add_openscad` exist there (remove both); `cut`, `paint`, `done`
are reused as they are; new keys fall back to English until a translation pass.

Check for T-032, expect 0 lines:
`grep -n -i -E ">[^<]*(shape builder|edit in builder|build workspace|sculpt|edit mesh|mesh tools|paint shop)" res/values*/strings.xml`
