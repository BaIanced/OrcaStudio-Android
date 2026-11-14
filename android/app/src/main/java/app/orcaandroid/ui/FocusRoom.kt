package app.orcaandroid.ui

import app.orcaandroid.core.SceneObject
import app.orcaandroid.scad.ScadDesign

/**
 * The focus room (docs/design/FOCUS-ROOM.md): one place to change an object's shape, behind the setting
 * [app.orcaandroid.core.AppSettings.focusRoom]. Session only; lives in [SceneState.focus] because it names a plate object.
 */
data class FocusRoom(
    /** Index in scene.objects; -1 while a "Make a part" visit has no shape on the plate yet. */
    val obj: Int,
    /** The object's name at entry: a scene whose objects[obj].name differs closes the room. */
    val name: String,
    val tool: RoomTool,
    /** Entered through "+ > Make a part". */
    val created: Boolean = false,
)

enum class RoomTool { SHAPES, PUSH_PULL, RESHAPE, MESH_EDIT, SKETCH }

enum class RoomKind { DESIGN, MESH }

/**
 * What an object is in the room: a [RoomKind.DESIGN] when it carries a shape-builder design, else a [RoomKind.MESH].
 * Derived on every read, never stored (Undo can change it). A hand-written code design counts as MESH here;
 * it has no room at all ([hasRoom]).
 */
fun roomKind(obj: SceneObject?): RoomKind = if (ScadDesign.of(obj)?.shapes != null) RoomKind.DESIGN else RoomKind.MESH

/** A design of hand-written OpenSCAD code: no room, "Edit shape" would open the code pane as before. */
fun isCodeDesign(obj: SceneObject?): Boolean = ScadDesign.of(obj).let { it != null && it.shapes == null }

/** Objects the room can open on: any object that is not a code design. */
fun hasRoom(obj: SceneObject?): Boolean = obj != null && !isCodeDesign(obj)

/** The tool a visit starts with: Shapes for a design, Push / pull for a mesh (user answer 13b). */
fun defaultRoomTool(kind: RoomKind): RoomTool = if (kind == RoomKind.DESIGN) RoomTool.SHAPES else RoomTool.PUSH_PULL

/** The room's name check: the scene no longer has the object the room was opened on. */
fun roomGone(focus: FocusRoom?, objects: List<SceneObject>): Boolean =
    focus != null && focus.obj >= 0 && objects.getOrNull(focus.obj)?.name != focus.name

/** The setting is on and one object that has a room is selected, outside a room: "Edit shape" is offered. */
val UiState.canEditShape: Boolean get() = chrome.focusRoom && workspace.focus == null && workspace.multiSelection.isEmpty() &&
    hasRoom(workspace.selection?.let { workspace.scene.objects.getOrNull(it.obj) })

/**
 * The object the 3D view isolates (others ghosted, camera framed, printer ghost): the builder's object, or in the room the
 * object a mesh tool works on. A design's room isolates through the builder, so only meshes are added here.
 */
val UiState.isolated: Int? get() = buildObject ?: workspace.focus?.takeIf { it.obj >= 0 }
    ?.takeIf { roomKind(workspace.scene.objects.getOrNull(it.obj)) == RoomKind.MESH }?.obj

/** The room replaces the old entries (Add shapes or holes, More > Reshape / Push / pull) for [obj]: the setting is on and the object has a room. */
fun UiState.roomOwnsShapes(obj: Int): Boolean = chrome.focusRoom && hasRoom(workspace.scene.objects.getOrNull(obj))
