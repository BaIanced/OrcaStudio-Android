# Focus room (direction B) - spec

Written 2026-11-14 against 9fcbffe (plan task T-050). Source root `S/` = `android/app/src/main/java/app/orcaandroid/`.
Build tasks: T-051..T-054. Everything here is behind the setting `focusRoom` (default off) unless a line says "both paths".

## 1. What it is

One place to change an object's shape. The user selects an object and taps **Edit shape** (or `+ > Make a part` for a
new one). The object is shown alone (others ghosted, camera framed), a strip says "Editing: <name>", a toolbar offers five
shape tools, and **Done** is the only exit. Slicing tools are not shown in the room; shape tools are not shown outside it.

It reuses what exists: the builder's isolate view, the mode strip from T-032, the builder, Sculpt and Edit mesh. It adds a
state object, a toolbar that routes to those tools, one undo rule and two guards.

## 2. Facts the design rests on (checked in source)

| Fact | Where |
|---|---|
| The room strip exists: `ModeStrip(room, name, canUndo, canRedo, onUndo, onRedo, onDone)`; `UiState.room` picks Make / Reshape / Paint / Cut | `S/ui/components/ModeStrip.kt:86`, `S/ui/prepare/PaintShop.kt:33-58` |
| The isolate view is driven by `state.buildObject` (builder) or `inPaintShop` only | `S/ui/components/Viewport.kt:687-701, 845-853`, `S/ui/ScadController.kt:78` |
| Sculpt and Edit mesh are `Tool.Sculpt` / `Tool.MeshEdit` in `SceneState.tool`; they open from the More menu | `S/ui/AppState.kt:68,76`, `S/ui/prepare/PrepareOverlay.kt:695-696` |
| "Edit in builder" calls `scad.editMesh(obj)`: a design opens as itself, a plain mesh is wrapped as one MESH shape per part and tagged at once, with no undo step | `S/ui/prepare/PrepareOverlay.kt:382`, `S/ui/ScadController.kt:238-272`, `android/core/jni/OrcaScene.cpp:1634` |
| The design tag is `ModelObject::module_name`; `setObjectModule(obj, "")` clears it. Nothing clears it today, so a mesh edit on a design object is overwritten by the next builder render (U-05) | same |
| Plate undo is a deque of whole-model snapshots, one per engine op; every builder commit replaces the object's volumes and so pushes a plate step (U-04) | `android/core/jni/OrcaScene.cpp:139-152, 1824-1856`, `S/ui/ShapeController.kt:52-64` |
| The builder has its own Kotlin undo stack (200 designs) | `S/ui/ShapeController.kt:30-83` |
| The sketch editor is a full screen inside the builder with its own Done / Cancel and undo | `S/ui/scad/ShapeBuilder.kt:135-148`, `S/ui/scad/SketchEditor.kt:136` |
| Sculpt drops all four paint layers whenever a stroke refines or splits the mesh (U-06) | `android/core/jni/OrcaSculpt.cpp:279-282, 409-412` |
| Upstream's "Keep painted feature after mesh change" is in libslic3r, which we build: `ModelVolume::save_painting()` / `restore_painting()` on top of `TriangleSelector::remap_painting`. Upstream labels it slow and experimental. `NOTES-upstream-2.5.md` does not mention it | `src-orca/src/libslic3r/Model.hpp:981-984`, `TriangleSelector.hpp:428-444`, `src-orca/src/slic3r/GUI/Gizmos/GLGizmoSimplify.cpp:561-570` |
| Back closes the builder, else drops tool and selection | `S/ui/AppScaffold.kt:173-177` |
| There is no "Experimental" section in Settings yet | `S/ui/settings/MoreScreen.kt:87-220` |

## 3. State

One object in `SceneState` (it names a plate object, so it lives with the selection):

```kotlin
/** The focus room, while open. Session only. */
data class FocusRoom(
    val obj: Int,            // index in scene.objects
    val name: String,        // the object's name at entry; a scene whose objects[obj].name differs closes the room
    val tool: RoomTool,
    val created: Boolean = false,  // entered through "+ > Make a part"; obj is -1 until the first shape is on the plate
)
enum class RoomTool { SHAPES, PUSH_PULL, RESHAPE, MESH_EDIT, SKETCH }
enum class RoomKind { DESIGN, MESH }
```

`SceneState.focus: FocusRoom? = null`. The **kind is derived, never stored**: `DESIGN` when `ScadDesign.of(object)?.shapes
!= null`, else `MESH`. Undo can change it, so every read derives it again.

An object whose design is hand-written OpenSCAD code (`shapes == null`) has no room: "Edit shape" opens the code pane as
today. Multi-selection has no "Edit shape".

`UiState.room` returns `Room.MAKE` for the whole visit while `focus != null`, so the strip colour does not change when
the tool does. With the setting off `UiState.room` is unchanged.

## 4. Entry

| Entry | Shown when | Does |
|---|---|---|
| **Edit shape** (context menu, first row; and the selection's More menu) | one object selected, setting on | opens the room on it with the default tool |
| **+ > Make a part** | setting on | `scad.newShapes()` as today, with `focus = FocusRoom(-1, name, SHAPES, created = true)` |

Default tool: `SHAPES` for a design, `PUSH_PULL` for a mesh (user answer 13b: Push / pull first).

With the setting on these entries are hidden: "Edit in builder" / "Add shapes or holes", More > Sculpt, More > Edit mesh,
`+ > Design`'s builder row. Paint, Cut, Measure, Lay on face and Mesh tools (bend, hollow, blend dialogs) stay where they
are: they are not in the room. With the setting off nothing changes.

Entering calls the engine's `roomBegin` (section 7) before anything else, then selects the object.

## 5. Toolbar and routing

Five buttons in a fixed order, always all visible: **Shapes** (Add / Hole), **Push / pull**, **Reshape**, **Mesh edit**,
**Sketch**. The toolbar takes the place of the plate tool column. The active tool's own panel or card (the builder panel,
the Sculpt card, the Edit mesh card) is the existing one, unchanged.

Rule: **builder tools run only on a design, mesh tools only on a mesh.** A tool of the other family converts the object
first. So there are two conversions, and each tool has one behaviour per kind:

| Tool | On a design | On a mesh |
|---|---|---|
| Shapes | the builder (`scad.editObject`) | wrap (below), then the builder |
| Sketch | the builder's "add sketch shape" action, which opens the sketch editor | wrap, then the same |
| Push / pull | the builder with `shapes.setPushPull(true)` | `Tool.MeshEdit(kind = "face")` (the Push / pull card of T-034) |
| Reshape | freeze (below), then `scene.openReshape()` | `scene.openReshape()` |
| Mesh edit | freeze, then `Tool.MeshEdit()` | `Tool.MeshEdit()` |

**Wrap (mesh -> design)** is today's `scad.editMesh`: lossless, each part becomes a MESH shape. No question. The builder
panel shows one line the first time: "This model is now made of shapes. You can add shapes and holes to it."

**Freeze (design -> mesh)** closes the builder (`scad.close()`) and clears the tag with `setObjectModule(obj, "", undo =
true)`. It asks first, once per visit (the U-05 guard):

> **Keep as a fixed shape?**
> Reshape and Mesh edit change the surface directly. The sizes and holes of "<name>" can't be edited as shapes afterwards.
> [Cancel] [Keep as a fixed shape]

Cancel leaves the tool unchanged. The question is skipped when the design is a **plain wrap**: every shape is of kind
MESH, none is a hole added in the builder, there is no group or repeat, and no shape was moved or resized (pure function
`ShapeDesign.isPlainWrap()`, JVM-tested). Freezing a plain wrap loses nothing.

Deviation from the T-050 brief: "convert inline" is a note, not a button, for mesh -> design, because that conversion
loses nothing; only design -> mesh asks.

**U-05 on both paths.** With the setting off, More > Sculpt and More > Edit mesh on a design object show the same
question and freeze the same way. The data loss does not depend on the setting.

## 6. Exit

**Done** is the only exit and always keeps the work. It: closes the builder if open, sets `tool = Tool.None`, calls
`roomEnd`, sets `focus = null`, leaves the object selected.

- Back = Done. Inside the sketch editor Back and Done / Cancel act on the sketch first, as today.
- Switching to another screen (Preview, Device, ...) runs Done first.
- Taps on other objects do nothing in the room. `scene.select(null)` and selection changes are ignored while `focus !=
  null`; nothing closes the room except Done and the name check in section 3.
- "Make a part" left with no shape placed: no object, no plate step, the added empty plate is removed as today
  (`ScadController.close`, line 305).
- There is no Cancel. "Throw the visit away" is Done, then one plate Undo (section 7).

Isolate view: the room uses the builder's mechanism for mesh tools too. `Viewport.kt`'s `focused` and its focus box take
`state.workspace.focus?.obj` as well as `state.buildObject`; the others-ghosted setting (`design.buildOthers`) and the
printer ghost apply. The camera frames once at entry and not again on a tool switch.

## 7. Undo

Two rules.

1. **Inside the room the strip's Undo / Redo drive the active stack.** Builder tools (Shapes, Sketch, Push / pull on a
   design): the builder stack (`vm.shapes`). Mesh tools: the plate stack, but never below the point where the mesh tool
   family was entered. A conversion (wrap or freeze) starts a fresh history: Undo does not cross it.
2. **Leaving the room leaves exactly one plate step**, whatever happened inside (zero if nothing changed). One plate Undo
   after Done restores the object as it was at entry, design tag included; one Redo brings the visit back.

Engine (`android/core/jni`, our code, not src-orca). New members `m_room_open`, `m_room_floor`, `m_room_tool_floor`:

| Call | Effect |
|---|---|
| `roomBegin` | if not open: `open = true; floor = tool_floor = m_undo.size()`. If already open: nothing. Returns the scene |
| `roomFloor` | `tool_floor = m_undo.size(); m_redo.clear()`. Called by Kotlin after each conversion and whenever the active stack changes to the plate stack |
| `roomEnd` | if open and `m_undo.size() > floor + 1`: erase `m_undo[floor + 1 .. end)`. Then `m_redo.clear()` if the stack was changed in the room, `open = false`, `m_calib_undo_depth = min(m_calib_undo_depth, m_undo.size())`. Returns the scene |
| `setObjectModule` | new optional `undo` (default false): `push_undo()` before the change |
| scene JSON | while open: `can_undo = m_undo.size() > tool_floor`; `undo()` throws "Nothing to undo" at the floor. Closed: as today |

`m_undo[floor]` is the snapshot the first op in the room pushed, i.e. the model at entry. Keeping it and dropping the
rest is the "one step". `trim_undo` while open: pop the front and decrement both floors while `floor > 0`; when `floor ==
0` erase `m_undo[floor + 1]` instead (the oldest step made in the room) and decrement `tool_floor` if it is above
`floor + 1`. The entry snapshot is never dropped while the room is open.

Sketch and code text keep their own undo inside their editors (unchanged). U-04's "plate Undo reverts one builder field"
goes away with rule 2, for visits made in the room.

## 8. Paint (U-06)

Sculpt keeps paint. At the two places that reset the four facet sets (`OrcaSculpt.cpp:279-282, 409-412`), take
`vol->save_painting()` before the mesh is replaced and `vol->restore_painting(saved)` after, in place of the resets.
A stroke that only moves vertices already keeps paint (paint is stored per triangle).

Budget: the remap runs only on a refine or a split, not per dab. On the 17,916-triangle insole with support paint on it,
the refine step may take at most 300 ms more than without paint. If it does not fit, the task stops and reports the
numbers; the fallback (decided by the planner, not the implementer) is to ask once before the first refining stroke.

Edit mesh ops that change topology are out of scope here; T-054 lists which of them drop paint.

## 9. The setting

`focusRoom`, stored as `focus_room`, default false, session-independent. Shown in Settings under a new collapsed section
**Experimental** as "One place to edit shapes", with the line "Edit shape opens every shape tool for the selected
object." Turning it off while a room is open is not possible (Settings is another screen; switching screens runs Done).

Reachable while off: everything as today (context menu "Add shapes or holes", More > Sculpt / Edit mesh, `+ > Design`),
plus the U-05 question (section 5) and kept paint (section 8), which are on both paths.

## 10. Not in this batch

Pins, lasso, stored re-editable deforms (batch 6, not planned: user answer 9). Defaulting the setting to on and removing
the old entries (T-055 decides with the user). A room for code designs. A discard button. German strings.
