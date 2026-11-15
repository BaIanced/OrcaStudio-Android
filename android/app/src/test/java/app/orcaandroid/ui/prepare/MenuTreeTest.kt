package app.orcaandroid.ui.prepare

import app.orcaandroid.core.Scene
import app.orcaandroid.core.SceneObject
import app.orcaandroid.scad.D3
import app.orcaandroid.scad.ScadDesign
import app.orcaandroid.scad.Shape
import app.orcaandroid.scad.ShapeDesign
import app.orcaandroid.ui.ChromeState
import app.orcaandroid.ui.SceneState
import app.orcaandroid.ui.Selection
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The menu tree (docs/design/EDGE-MENU.md section 4) must reach every action the tool buttons ([ToolButtons]) and the
 * hold menu ([ViewContextMenu]) reach today, in each selection state. The key lists below are those two composables, by hand.
 */
class MenuTreeTest {
    private fun obj(index: Int, module: String = "") = SceneObject(index, "part$index", 12, emptyMap(), emptyList(), emptyList(), emptyList(), false, module)
    private val shapes = ShapeDesign(listOf(Shape(1, Shape.Kind.BOX, "Box", D3(10.0, 10.0, 10.0))), nextId = 2)
    private val designObj = obj(0, ScadDesign("d.scad", "cube(1);", emptyMap(), shapes = shapes).encode())
    private val codeObj = obj(0, ScadDesign("d.scad", "cube(1);", emptyMap(), shapes = null).encode())

    private fun state(objects: List<SceneObject>, sel: Selection? = null, multi: List<Selection> = emptyList(), tool: Tool = Tool.None,
                      room: Boolean = true) =
        UiState(workspace = SceneState(scene = Scene(objects = objects), selection = sel, multiSelection = multi, tool = tool),
            chrome = ChromeState(focusRoom = room))

    /** What a tap can run: shown, and enabled. */
    private fun reachable(s: UiState): Set<String> = flattenShown(s).filter { it.enabled(s) }.mapNotNull { it.action?.key }.toSet()

    private val view = setOf(
        "view.iso", "view.top", "view.front", "view.left", "view.right", "view.fit", "view.section", "view.realistic", "view.layer_lines",
        "view.edge_outline", "view.wireframe", "view.light", "view.bounding_box", "view.dimensions",
    )

    // ToolButtons: the Add menu (AddMenu in PrepareDialogs.kt) plus the View menu (ViewButtons).
    private val add = setOf("launcher.import", "dialog.primitive", "dialog.text", "dialog.svg", "add.design", "dialog.samples", "dialog.calib")

    // ToolButtons with a non-empty scene: Arrange, Auto orient, Select multiple, Snap, Move lock.
    private val arrange = setOf("scene.arrange", "scene.auto_orient", "scene.select_mode", "scene.snap", "scene.move_lock")

    private val volumes = setOf("scene.volume.part", "scene.volume.modifier", "scene.volume.support_blocker", "scene.volume.support_enforcer",
        "scene.volume.negative")
    private val meshTools = setOf("dialog.boolean", "dialog.simplify", "dialog.deform", "dialog.blend", "dialog.hollow", "scene.repair", "dialog.check")

    // ToolButtons with one selection (buttons + Split + the More menu) and ViewContextMenu on one object.
    private val oneObject = arrange + setOf(
        "scene.duplicate", "scene.delete", "tool.lay_on_face", "tool.cut", "tool.reshape", "tool.paint", "tool.layer_height",
        "scene.split_objects", "scene.split_parts", "tool.measure",
        "scene.center", "scene.resize", "scene.rotate", "presets.object_settings",
    ) + volumes + meshTools

    private fun assertCovers(s: UiState, today: Set<String>) {
        val missing = today - reachable(s)
        assertTrue("not reachable in the tree: $missing", missing.isEmpty())
    }

    @Test fun nothingSelectedOnAnEmptyPlate() {
        assertCovers(state(emptyList()), add + view)
    }

    @Test fun nothingSelected() {
        // Measure is the one extra button; the context menu on the empty bed offers Import, Shape, Text, SVG, Make a part, Arrange.
        assertCovers(state(listOf(obj(0))), add + view + arrange + "tool.measure")
    }

    @Test fun oneObjectInTheRoom() {
        val s = state(listOf(obj(0)), sel = Selection(0))
        // The room owns Push / pull and Edit in builder; Edit shape replaces them.
        assertCovers(s, add + view + oneObject + "room.open")
        assertTrue("tool.mesh_edit" !in reachable(s) && "scad.edit_mesh" !in reachable(s))
    }

    @Test fun oneObjectWithoutTheRoom() {
        val s = state(listOf(obj(0)), sel = Selection(0), room = false)
        assertCovers(s, add + view + oneObject + setOf("tool.mesh_edit", "scad.edit_mesh"))
        assertTrue("room.open" !in reachable(s))
    }

    @Test fun oneDesignObject() {
        assertCovers(state(listOf(designObj), sel = Selection(0)), add + view + oneObject + "room.open")
    }

    @Test fun oneCodeDesignObjectHasNoRoom() {
        val s = state(listOf(codeObj), sel = Selection(0))
        assertCovers(s, add + view + oneObject + setOf("tool.mesh_edit", "scad.edit_mesh"))
        assertTrue("room.open" !in reachable(s))
    }

    @Test fun twoOrMoreObjects() {
        val s = state(listOf(obj(0), obj(1)), multi = listOf(Selection(0), Selection(1)))
        // ToolButtons: Duplicate, Assemble, Delete; ViewContextMenu: Duplicate, Delete, Center, six aligns, Assemble.
        assertCovers(s, add + view + arrange + setOf("scene.duplicate", "scene.assemble", "scene.delete", "scene.center") +
            listOf("left", "center_x", "right", "back", "center_y", "front").map { "scene.align.$it" })
    }

    @Test fun reshape() {
        val s = state(listOf(obj(0)), sel = Selection(0), tool = Tool.Sculpt(brush = "draw"))
        // The Sculpt button toggling off, and the card: brush chips, mirror chips, invert; strength is a slider row.
        assertCovers(s, view + "sculpt.done" + listOf("draw", "inflate", "smooth", "flatten", "pinch", "crease", "grab").map { "sculpt.brush.$it" } +
            listOf("x", "y", "z").map { "sculpt.mirror.$it" } + "sculpt.invert")
        assertNotNull(flattenShown(s).firstOrNull { it.id == "reshape.strength" }?.slider)
        // Plate rows are gone in Reshape.
        assertTrue("scene.arrange" !in reachable(s))
    }

    @Test fun reshapeHidesStrengthAndInvertWhereTheCardDoes() {
        fun ids(brush: String) = flattenShown(state(listOf(obj(0)), sel = Selection(0), tool = Tool.Sculpt(brush = brush))).map { it.id }.toSet()
        assertTrue("reshape.strength" !in ids("grab") && "reshape.invert" !in ids("grab"))
        assertTrue("reshape.strength" in ids("smooth") && "reshape.invert" !in ids("smooth"))
        assertTrue("reshape.strength" in ids("draw") && "reshape.invert" in ids("draw"))
    }

    @Test fun drillDownLevels() {
        val one = state(listOf(obj(0)), sel = Selection(0))
        val (open, rows) = menuLevel(one, listOf("object"))
        assertEquals(listOf("object"), open)
        assertTrue(rows.any { it.id == "object.cut" })
        assertEquals(listOf("plate.arrange.arrange"), menuLevel(one, listOf("plate.arrange")).second.map { it.id }.take(1))
        // The selection went away: Object is no longer shown, so the panel falls back to the top level.
        val none = state(listOf(obj(0)))
        val (back, top) = menuLevel(none, listOf("object"))
        assertTrue(back.isEmpty())
        assertEquals(menuRoots(none).map { it.id }, top.map { it.id })
        // A leaf in the path is not a level.
        assertEquals(listOf("plate.edit", "plate.edit.split"), menuLevel(one, listOf("plate.edit", "plate.edit.split", "plate.edit.split.parts")).first)
    }

    @Test fun idsAreUniqueAndScoped() {
        val all = mutableListOf<MenuNode>()
        fun walk(n: MenuNode) { all += n; n.children.forEach(::walk) }
        MENU_TREE.forEach(::walk)
        val ids = all.map { it.id }
        assertEquals("duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size, ids.toSet().size)
        assertTrue(all.all { it.action != null || it.slider != null || it.children.isNotEmpty() })
        assertEquals(MenuScope.RESHAPE, findMenuNode("reshape.brush.draw")?.scope)
        assertEquals(MenuScope.BOTH, findMenuNode("view.iso")?.scope)
        assertEquals(MenuScope.PLATE, findMenuNode("object.cut")?.scope)
        assertNull(findMenuNode("no.such.id"))
    }
}
