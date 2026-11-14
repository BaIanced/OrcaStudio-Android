package app.orcaandroid.ui

import app.orcaandroid.core.Scene
import app.orcaandroid.core.SceneObject
import app.orcaandroid.scad.D3
import app.orcaandroid.scad.ScadDesign
import app.orcaandroid.scad.Shape
import app.orcaandroid.scad.ShapeDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The focus room's pure parts (docs/design/FOCUS-ROOM.md sections 3, 4 and 6). */
class FocusRoomTest {
    private fun obj(index: Int = 0, name: String = "part", module: String = "") =
        SceneObject(index, name, 12, emptyMap(), emptyList(), emptyList(), emptyList(), false, module)

    private fun design(shapes: ShapeDesign?) = obj(module = ScadDesign("d.scad", "cube(1);", emptyMap(), shapes = shapes).encode())

    private val shapes = ShapeDesign(listOf(Shape(1, Shape.Kind.BOX, "Box", D3(10.0, 10.0, 10.0))), nextId = 2)

    @Test fun designWithShapesIsADesign() {
        val o = design(shapes)
        assertEquals(RoomKind.DESIGN, roomKind(o))
        assertTrue(hasRoom(o))
        assertFalse(isCodeDesign(o))
    }

    @Test fun plainMeshIsAMesh() {
        val o = obj()
        assertEquals(RoomKind.MESH, roomKind(o))
        assertTrue(hasRoom(o))
        assertFalse(isCodeDesign(o))
        assertEquals(RoomKind.MESH, roomKind(null))
        assertFalse(hasRoom(null))
    }

    @Test fun codeDesignHasNoRoom() {
        val o = design(null)
        assertTrue(isCodeDesign(o))
        assertFalse(hasRoom(o))
        // Derived like any non-design: it never counts as a design, so no builder tool runs on it.
        assertEquals(RoomKind.MESH, roomKind(o))
    }

    @Test fun otherModuleTagIsAMesh() {
        assertEquals(RoomKind.MESH, roomKind(obj(module = "something else")))
    }

    @Test fun defaultToolIsShapesForADesignAndPushPullForAMesh() {
        assertEquals(RoomTool.SHAPES, defaultRoomTool(RoomKind.DESIGN))
        assertEquals(RoomTool.PUSH_PULL, defaultRoomTool(RoomKind.MESH))
        assertEquals(RoomTool.SHAPES, defaultRoomTool(roomKind(design(shapes))))
        assertEquals(RoomTool.PUSH_PULL, defaultRoomTool(roomKind(obj())))
    }

    @Test fun kindFollowsTheObjectWhenUndoChangesIt() {
        val before = design(shapes)
        val after = obj(name = before.name)
        assertEquals(RoomKind.DESIGN, roomKind(before))
        assertEquals(RoomKind.MESH, roomKind(after))
    }

    @Test fun roomClosesWhenItsObjectNameChangesOrIsGone() {
        val focus = FocusRoom(1, "b", RoomTool.PUSH_PULL)
        val objects = listOf(obj(0, "a"), obj(1, "b"))
        assertFalse(roomGone(focus, objects))
        assertTrue(roomGone(focus, listOf(obj(0, "a"), obj(1, "c"))))
        assertTrue(roomGone(focus, listOf(obj(0, "a"))))
        assertTrue(roomGone(focus, emptyList()))
        assertFalse(roomGone(null, objects))
        // A made part has no object until its first shape is on the plate.
        assertFalse(roomGone(FocusRoom(-1, "New part", RoomTool.SHAPES, created = true), emptyList()))
    }

    @Test fun selectionIsKeptWhileTheRoomIsOpen() {
        val sel = Selection(0)
        val room = SceneState(selection = sel, focus = FocusRoom(0, "a", RoomTool.PUSH_PULL))
        assertEquals(sel, room.withSelected(emptyList()).selection)
        assertEquals(sel, room.withSelected(listOf(Selection(1))).selection)
        assertEquals(sel, room.toggled(listOf(Selection(1))).selection)
        // Without a room nothing changes from today.
        assertNull(SceneState(selection = sel).withSelected(emptyList()).selection)
        assertEquals(Selection(1), SceneState(selection = sel).withSelected(listOf(Selection(1))).selection)
    }

    @Test fun editShapeNeedsTheSettingOneObjectAndNoRoom() {
        val objects = listOf(obj(0, "a"), design(shapes).copy(index = 1, name = "d"), design(null).copy(index = 2, name = "code"))
        fun state(setting: Boolean, selection: Selection?, focus: FocusRoom? = null, multi: List<Selection> = emptyList()) = UiState(
            workspace = SceneState(scene = Scene(objects = objects), selection = selection, multiSelection = multi, focus = focus),
            chrome = ChromeState(focusRoom = setting))
        assertFalse(state(false, Selection(0)).canEditShape)
        assertTrue(state(true, Selection(0)).canEditShape)
        assertTrue(state(true, Selection(1)).canEditShape)
        assertFalse(state(true, Selection(2)).canEditShape)
        assertFalse(state(true, null).canEditShape)
        assertFalse(state(true, null, multi = listOf(Selection(0), Selection(1))).canEditShape)
        assertFalse(state(true, Selection(0), focus = FocusRoom(0, "a", RoomTool.PUSH_PULL)).canEditShape)
        // The room replaces the old entries only where it applies.
        assertFalse(state(false, Selection(0)).roomOwnsShapes(0))
        assertTrue(state(true, Selection(0)).roomOwnsShapes(0))
        assertFalse(state(true, Selection(2)).roomOwnsShapes(2))
    }

    @Test fun isolatesMeshObjectsOfTheRoomOnly() {
        val objects = listOf(obj(0, "a"), design(shapes).copy(index = 1, name = "d"))
        fun state(focus: FocusRoom?) = UiState(workspace = SceneState(scene = Scene(objects = objects), focus = focus))
        assertNull(state(null).isolated)
        assertEquals(0, state(FocusRoom(0, "a", RoomTool.PUSH_PULL)).isolated)
        // A design isolates through the builder (buildObject), not through the room itself.
        assertNull(state(FocusRoom(1, "d", RoomTool.SHAPES)).isolated)
        assertNull(state(FocusRoom(-1, "New part", RoomTool.SHAPES, created = true)).isolated)
    }
}
