package app.orcaandroid.ui.prepare

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.AlignHorizontalLeft
import androidx.compose.material.icons.filled.AlignHorizontalRight
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.ui.graphics.vector.ImageVector
import app.orcaandroid.R
import app.orcaandroid.core.AlignKind
import app.orcaandroid.core.VolumeType
import app.orcaandroid.render.PlateView
import app.orcaandroid.render.SelectionStyle
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.EditorTarget
import app.orcaandroid.ui.RoomTool
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.framePlate
import app.orcaandroid.ui.canEditShape
import app.orcaandroid.ui.roomOwnsShapes

/**
 * The one menu tree behind the edge panel, the hold menu and the shortcuts (docs/design/EDGE-MENU.md section 4).
 * Nothing draws it yet. Ids are stable strings that shortcuts store: never rename one.
 */

/** Which context a node's id shows in: `reshape.*` only in Reshape, `view.*` / `view` in both, the rest outside Reshape. */
enum class MenuScope {
    PLATE, RESHAPE, BOTH;

    companion object {
        fun of(id: String): MenuScope = when {
            id == "view" || id.startsWith("view.") -> BOTH
            id.startsWith("reshape.") -> RESHAPE
            else -> PLATE
        }
    }
}

/** What a tap on a leaf does, with what the caller has at hand when it runs. */
class MenuEnv(val vm: AppViewModel, val state: UiState, val view: PlateView?)

sealed interface MenuAction {
    /** Names the call or dialog: several nodes may share one (Duplicate), the coverage test is by key. */
    val key: String

    /** A direct call on the view model. */
    class Call(override val key: String, val run: (MenuEnv) -> Unit) : MenuAction

    /** An `AddDialogs` key: primitive, text, svg, samples, calib, boolean, simplify, deform, blend, hollow, check. */
    class Dialog(val dialog: String) : MenuAction {
        override val key get() = "dialog.$dialog"
    }

    /** A file launcher the host owns: "import". */
    class Launcher(val launcher: String) : MenuAction {
        override val key get() = "launcher.$launcher"
    }
}

/** A slider row (Reshape strength). */
class MenuSlider(val range: ClosedFloatingPointRange<Float>, val value: (UiState) -> Float, val set: (MenuEnv, Float) -> Unit)

class MenuNode(
    val id: String,
    val label: Int,
    val icon: (() -> ImageVector)? = null,
    val enabled: (UiState) -> Boolean = { true },
    val visible: (UiState) -> Boolean = { true },
    val action: MenuAction? = null,
    val children: List<MenuNode> = emptyList(),
    /** A toggle or radio row shows a check while this is true. */
    val checked: ((UiState, PlateView?) -> Boolean)? = null,
    val slider: MenuSlider? = null,
    val scope: MenuScope = MenuScope.of(id),
    /** A literal label that replaces [label] (the mirror axes "X", "Y", "Z"). */
    val text: String? = null,
) {
    val isLeaf get() = children.isEmpty()

    /** Visible itself, and a branch only while one of its children is. */
    fun shown(state: UiState): Boolean = visible(state) && (isLeaf || children.any { it.shown(state) })

    fun shownChildren(state: UiState): List<MenuNode> = children.filter { it.shown(state) }
}

/** The tree as a level shows it: top rows visible in [state]'s context. */
fun menuRoots(state: UiState): List<MenuNode> = MENU_TREE.filter { it.shown(state) }

/** Every shown node under (and including) [nodes], depth first. */
fun flattenShown(state: UiState, nodes: List<MenuNode> = menuRoots(state)): List<MenuNode> =
    nodes.flatMap { listOf(it) + flattenShown(state, it.shownChildren(state)) }

/** Looks a stored shortcut id up; unknown ids give null (renamed or removed). */
fun findMenuNode(id: String): MenuNode? = ALL_NODES[id]

private val ALL_NODES: Map<String, MenuNode> by lazy {
    fun walk(n: MenuNode): List<MenuNode> = listOf(n) + n.children.flatMap(::walk)
    MENU_TREE.flatMap(::walk).associateBy { it.id }
}

// Context rules. The plate context is outside a room and outside Reshape.
private val UiState.reshaping get() = workspace.tool is Tool.Sculpt
private val UiState.plateCtx get() = workspace.focus == null && !reshaping
private val UiState.oneSelected get() = workspace.selection != null && workspace.multiSelection.isEmpty()
private val UiState.manySelected get() = workspace.multiSelection.isNotEmpty()
private val UiState.sceneFilled get() = !workspace.scene.isEmpty
private val UiState.sculptTool get() = workspace.tool as? Tool.Sculpt

private fun call(key: String, run: (MenuEnv) -> Unit) = MenuAction.Call(key, run)
private fun MenuEnv.sel() = state.workspace.selection
private fun MenuEnv.sculpt(change: (Tool.Sculpt) -> Tool.Sculpt) {
    (state.workspace.tool as? Tool.Sculpt)?.let { vm.scene.setTool(change(it)) }
}

private fun toggleTool(env: MenuEnv, tool: Tool) = env.vm.scene.setTool(if (env.state.workspace.tool == tool) Tool.None else tool)

private fun align(id: String, label: Int, icon: ImageVector, kind: AlignKind) =
    MenuNode(id, label, { icon }, action = call("scene.align.${kind.name.lowercase()}") { it.vm.scene.alignSelected(kind) })

private val ADD = MenuNode(
    "plate.add", R.string.add, { Icons.Default.Add },
    // In the room only the Shapes tool has the palette.
    visible = { it.reshaping.not() && (it.workspace.focus == null || it.workspace.focus.tool == RoomTool.SHAPES) },
    children = listOf(
        MenuNode("plate.add.import", R.string.import_model, { Icons.Default.FileOpen }, action = MenuAction.Launcher("import")),
        MenuNode("plate.add.shape", R.string.add_shape, { Icons.Default.Interests }, action = MenuAction.Dialog("primitive")),
        MenuNode("plate.add.text", R.string.add_text, { Icons.Default.TextFields }, action = MenuAction.Dialog("text")),
        MenuNode("plate.add.svg", R.string.add_svg, { Icons.Default.Category }, action = MenuAction.Dialog("svg")),
        MenuNode("plate.add.part", R.string.add_design, { Icons.Default.Architecture },
            visible = { !(it.chrome.focusRoom && it.workspace.focus != null) },
            action = call("add.design") { e -> if (e.state.chrome.focusRoom) e.vm.makePart() else e.vm.scad.newShapes() }),
        MenuNode("plate.add.samples", R.string.sample_models, { Icons.Default.Toys }, action = MenuAction.Dialog("samples")),
        MenuNode("plate.add.calibration", R.string.calibration, { Icons.Default.Science },
            enabled = { it.workspace.calibration == null }, action = MenuAction.Dialog("calib")),
    ),
)

private val OBJECT = MenuNode(
    "object", R.string.menu_object, { Icons.Default.Interests }, visible = { it.plateCtx && it.oneSelected },
    children = listOf(
        MenuNode("object.edit_shape", R.string.edit_shape, { Icons.Default.Architecture }, visible = { it.canEditShape },
            action = call("room.open") { e -> e.sel()?.let { e.vm.openRoom(it.obj) } }),
        MenuNode("object.lay_on_face", R.string.lay_on_face, { Icons.Default.VerticalAlignBottom },
            checked = { s, _ -> s.workspace.tool == Tool.LayOnFace }, action = call("tool.lay_on_face") { toggleTool(it, Tool.LayOnFace) }),
        MenuNode("object.cut", R.string.cut, { Icons.Default.ContentCut }, checked = { s, _ -> s.workspace.tool is Tool.Cut },
            action = call("tool.cut") { if (it.state.workspace.tool is Tool.Cut) it.vm.scene.setTool(Tool.None) else it.vm.scene.startCut() }),
        MenuNode("object.paint", R.string.paint, { Icons.Default.Brush }, checked = { s, _ -> s.workspace.tool is Tool.Paint },
            action = call("tool.paint") { it.vm.scene.openPaint("color", 2) }),
        MenuNode("object.layer_height", R.string.variable_layer_height, { Icons.Default.Layers },
            checked = { s, _ -> s.workspace.tool == Tool.LayerHeight }, action = call("tool.layer_height") { toggleTool(it, Tool.LayerHeight) }),
        MenuNode("object.resize", R.string.resize, { Icons.Default.OpenInFull }, checked = { s, _ -> s.workspace.resizing },
            action = call("scene.resize") { it.vm.scene.toggleResize() }),
        MenuNode("object.rotate", R.string.rotate, { Icons.Default.Rotate90DegreesCcw }, checked = { s, _ -> s.workspace.rotating },
            action = call("scene.rotate") { it.vm.scene.toggleRotate() }),
        MenuNode("object.center", R.string.center_on_plate, { Icons.Default.CenterFocusStrong },
            action = call("scene.center") { it.vm.scene.centerSelected() }),
        MenuNode("object.settings", R.string.object_settings_title,
            action = call("presets.object_settings") { e -> e.sel()?.let { e.vm.presets.openEditor(EditorTarget.Object(it.obj)) } }),
    ),
)

private val SELECTION = MenuNode(
    "selection", R.string.menu_selection, { Icons.Default.SelectAll }, visible = { it.plateCtx && it.manySelected },
    children = listOf(
        MenuNode("selection.duplicate", R.string.duplicate, { Icons.Default.ContentCopy }, action = call("scene.duplicate") { it.vm.scene.duplicate(1) }),
        MenuNode("selection.delete", R.string.delete, { Icons.Default.Delete }, action = call("scene.delete") { it.vm.scene.deleteSelected() }),
        MenuNode("selection.center", R.string.center_on_plate, { Icons.Default.CenterFocusStrong }, action = call("scene.center") { it.vm.scene.centerSelected() }),
        MenuNode(
            "selection.align", R.string.menu_align, { Icons.Default.AlignHorizontalLeft },
            children = listOf(
                align("selection.align.left", R.string.align_left, Icons.Default.AlignHorizontalLeft, AlignKind.LEFT),
                align("selection.align.center_x", R.string.align_center_x, Icons.Default.AlignHorizontalCenter, AlignKind.CENTER_X),
                align("selection.align.right", R.string.align_right, Icons.Default.AlignHorizontalRight, AlignKind.RIGHT),
                align("selection.align.back", R.string.align_back, Icons.Default.VerticalAlignTop, AlignKind.BACK),
                align("selection.align.center_y", R.string.align_center_y, Icons.Default.VerticalAlignCenter, AlignKind.CENTER_Y),
                align("selection.align.front", R.string.align_front, Icons.Default.VerticalAlignBottom, AlignKind.FRONT),
            ),
        ),
        MenuNode("selection.assemble", R.string.assemble, { Icons.Default.CallMerge },
            enabled = { it.workspace.multiSelection.size >= 2 }, action = call("scene.assemble") { it.vm.scene.assemble() }),
    ),
)

private val EDIT_MODEL = MenuNode(
    "plate.edit", R.string.menu_edit_model, { Icons.Default.Gesture }, visible = { it.plateCtx && it.oneSelected },
    children = listOf(
        // ToolButtons offers Reshape on every selected object; Push / pull only where the room does not own it.
        MenuNode("plate.edit.reshape", R.string.sculpt, { Icons.Default.Gesture }, checked = { s, _ -> s.workspace.tool is Tool.Sculpt },
            action = call("tool.reshape") { if (it.state.workspace.tool is Tool.Sculpt) it.vm.scene.setTool(Tool.None) else it.vm.scene.openReshape() }),
        MenuNode("plate.edit.push_pull", R.string.mesh_edit,
            visible = { s -> !s.roomOwnsShapes(s.workspace.selection?.obj ?: -1) },
            action = call("tool.mesh_edit") { it.vm.scene.setTool(Tool.MeshEdit()) }),
        MenuNode(
            "plate.edit.split", R.string.split, { Icons.Default.CallSplit },
            children = listOf(
                MenuNode("plate.edit.split.objects", R.string.split_objects, action = call("scene.split_objects") { it.vm.scene.split(false) }),
                MenuNode("plate.edit.split.parts", R.string.split_parts, action = call("scene.split_parts") { it.vm.scene.split(true) }),
            ),
        ),
        MenuNode(
            "plate.edit.volume", R.string.more_add_volume,
            children = listOf(
                R.string.add_part to VolumeType.PART, R.string.add_modifier to VolumeType.MODIFIER,
                R.string.add_support_blocker to VolumeType.SUPPORT_BLOCKER, R.string.add_support_enforcer to VolumeType.SUPPORT_ENFORCER,
                R.string.add_negative_volume to VolumeType.NEGATIVE,
            ).map { (label, type) ->
                MenuNode("plate.edit.volume.${type.name.lowercase()}", label, action = call("scene.volume.${type.name.lowercase()}") { it.vm.scene.addVolume(type, "box") })
            },
        ),
        MenuNode(
            "plate.edit.mesh", R.string.more_mesh_tools,
            children = listOf(
                MenuNode("plate.edit.mesh.boolean", R.string.mesh_boolean, action = MenuAction.Dialog("boolean")),
                MenuNode("plate.edit.mesh.simplify", R.string.simplify, action = MenuAction.Dialog("simplify")),
                MenuNode("plate.edit.mesh.deform", R.string.deform, action = MenuAction.Dialog("deform")),
                MenuNode("plate.edit.mesh.blend", R.string.blend, action = MenuAction.Dialog("blend")),
                MenuNode("plate.edit.mesh.hollow", R.string.hollow, action = MenuAction.Dialog("hollow")),
                MenuNode("plate.edit.mesh.repair", R.string.repair, action = call("scene.repair") { it.vm.scene.repair() }),
                MenuNode("plate.edit.mesh.check", R.string.check_mesh, action = MenuAction.Dialog("check")),
            ),
        ),
        MenuNode("plate.edit.builder", R.string.edit_in_builder, { Icons.Default.Architecture },
            visible = { s -> !s.roomOwnsShapes(s.workspace.selection?.obj ?: -1) },
            action = call("scad.edit_mesh") { e -> e.sel()?.let { e.vm.scad.editMesh(it.obj) } }),
    ),
)

private val ARRANGE = MenuNode(
    "plate.arrange", R.string.arrange, { Icons.Default.AutoAwesomeMosaic }, visible = { it.plateCtx && it.sceneFilled },
    children = listOf(
        MenuNode("plate.arrange.arrange", R.string.arrange, { Icons.Default.AutoAwesomeMosaic }, action = call("scene.arrange") { it.vm.scene.arrange(false) }),
        MenuNode("plate.arrange.auto_orient", R.string.auto_orient, action = call("scene.auto_orient") { it.vm.scene.autoOrient() }),
        MenuNode("plate.arrange.select_multiple", R.string.select_multiple, { Icons.Default.SelectAll },
            checked = { s, _ -> s.workspace.selectMode }, action = call("scene.select_mode") { it.vm.scene.setSelectMode(!it.state.workspace.selectMode) }),
        MenuNode("plate.arrange.snap", R.string.snap_toggle, checked = { s, _ -> s.workspace.snapPrepare },
            action = call("scene.snap") { it.vm.toggleSnap() }),
        // Cycles free / X / Y; checked while a lock is on.
        MenuNode("plate.arrange.move_lock", R.string.move_lock, { Icons.Default.OpenWith }, checked = { s, _ -> s.workspace.moveLock >= 0 },
            action = call("scene.move_lock") { it.vm.cycleMoveLock() }),
    ),
)

private val DUPLICATE = MenuNode("plate.duplicate", R.string.duplicate, { Icons.Default.ContentCopy }, visible = { it.plateCtx && it.oneSelected },
    action = call("scene.duplicate") { it.vm.scene.duplicate(1) })
private val DELETE = MenuNode("plate.delete", R.string.delete, { Icons.Default.Delete }, visible = { it.plateCtx && it.oneSelected },
    action = call("scene.delete") { it.vm.scene.deleteSelected() })
private val MEASURE = MenuNode("plate.measure", R.string.measure, { Icons.Default.Straighten }, visible = { it.plateCtx && it.sceneFilled },
    checked = { s, _ -> s.workspace.tool == Tool.Measure }, action = call("tool.measure") { toggleTool(it, Tool.Measure) })

/** The room's Push / pull ("Mesh edit") card rows. */
private val MESH_EDIT = MenuNode(
    "plate.meshedit", R.string.mesh_edit, { Icons.Default.Architecture }, visible = { it.workspace.focus != null && it.workspace.tool is Tool.MeshEdit },
    children = listOf(
        MenuNode("plate.meshedit.shrink", R.string.mesh_edit_shrink, action = call("meshedit.shrink") { it.vm.scene.meshEditOp("shrink") }),
        MenuNode("plate.meshedit.grow", R.string.mesh_edit_grow, action = call("meshedit.grow") { it.vm.scene.meshEditOp("grow") }),
        MenuNode("plate.meshedit.none", R.string.mesh_edit_none, action = call("meshedit.clear") { it.vm.scene.meshEditOp("clear") }),
        MenuNode("plate.meshedit.all", R.string.mesh_edit_all, action = call("meshedit.all") { it.vm.scene.meshEditOp("all") }),
    ),
)

private val BRUSHES = listOf(
    "draw" to R.string.sculpt_draw, "inflate" to R.string.sculpt_inflate, "smooth" to R.string.sculpt_smooth, "flatten" to R.string.sculpt_flatten,
    "pinch" to R.string.sculpt_pinch, "crease" to R.string.sculpt_crease, "grab" to R.string.sculpt_grab,
)

private val RESHAPE_BRUSH = MenuNode(
    "reshape.brush", R.string.menu_brush, { Icons.Default.Brush }, visible = { it.reshaping },
    children = BRUSHES.map { (brush, label) ->
        MenuNode("reshape.brush.$brush", label, checked = { s, _ -> s.sculptTool?.brush == brush },
            action = call("sculpt.brush.$brush") { e -> e.sculpt { it.copy(brush = brush) } })
    },
)
private val RESHAPE_STRENGTH = MenuNode(
    "reshape.strength", R.string.menu_strength, visible = { it.reshaping && it.sculptTool?.brush != "grab" },
    slider = MenuSlider(0.05f..1f, { it.sculptTool?.strength ?: 0.5f }, { e, v -> e.sculpt { it.copy(strength = v) } }),
)
private val RESHAPE_INVERT = MenuNode(
    "reshape.invert", R.string.sculpt_invert, visible = { it.reshaping && it.sculptTool?.brush.let { b -> b != "grab" && b != "smooth" } },
    checked = { s, _ -> s.sculptTool?.invert == true }, action = call("sculpt.invert") { e -> e.sculpt { it.copy(invert = !it.invert) } },
)
private val RESHAPE_MIRROR = MenuNode(
    "reshape.mirror", R.string.sculpt_mirror, visible = { it.reshaping },
    children = listOf("x", "y", "z").mapIndexed { i, axis ->
        val bit = 1 shl i
        MenuNode("reshape.mirror.$axis", R.string.sculpt_mirror, text = axis.uppercase(), checked = { s, _ -> (s.sculptTool?.mirror ?: 0) and bit != 0 },
            action = call("sculpt.mirror.$axis") { e -> e.sculpt { it.copy(mirror = it.mirror xor bit) } })
    },
)
// Leaves the tool: the Sculpt button toggling off on the plate, the Shapes tool in the room.
private val RESHAPE_DONE = MenuNode(
    "reshape.done", R.string.done, visible = { it.reshaping },
    action = call("sculpt.done") { e -> if (e.state.workspace.focus != null) e.vm.setRoomTool(RoomTool.SHAPES) else e.vm.scene.setTool(Tool.None) },
)

private fun viewItem(id: String, label: Int, icon: (() -> ImageVector)? = null, checked: ((UiState, PlateView?) -> Boolean)? = null, run: (MenuEnv) -> Unit) =
    MenuNode("view.$id", label, icon, checked = checked, action = call("view.$id", run))

private val VIEW = MenuNode(
    "view", R.string.view, { Icons.Default.Videocam },
    children = listOf(
        viewItem("iso", R.string.view_iso) { it.view?.camera?.preset(0) },
        viewItem("top", R.string.view_top) { it.view?.camera?.preset(1) },
        viewItem("front", R.string.view_front) { it.view?.camera?.preset(2) },
        viewItem("left", R.string.view_left) { it.view?.camera?.preset(3) },
        viewItem("right", R.string.view_right) { it.view?.camera?.preset(4) },
        viewItem("fit", R.string.view_fit, { Icons.Default.CenterFocusStrong }) { it.view?.framePlate(it.state.workspace.activePlate) },
        viewItem("section", R.string.section_view, { Icons.Default.ContentCut }, { _, v -> v?.section?.active == true }) { e ->
            e.view?.section?.let { it.toggle(); it.panelOpen = it.active }
        },
        viewItem("realistic", R.string.view_realistic, checked = { s, _ -> s.workspace.realistic }) { it.vm.setRealistic(!it.state.workspace.realistic) },
        viewItem("layer_lines", R.string.view_layer_lines, checked = { s, _ -> s.workspace.layerLines }) { it.vm.setLayerLines(!it.state.workspace.layerLines) },
        viewItem("edge_outline", R.string.view_edge_outline, checked = { s, _ -> s.workspace.edgeOutline }) { it.vm.setEdgeOutline(!it.state.workspace.edgeOutline) },
        viewItem("wireframe", R.string.view_wireframe, checked = { s, _ -> s.workspace.wireframe }) { it.vm.setWireframe(!it.state.workspace.wireframe) },
        viewItem("light", R.string.light_direction, checked = { s, _ -> s.workspace.lightPanel }) { it.vm.setLightPanel(!it.state.workspace.lightPanel) },
        viewItem("bounding_box", R.string.view_bounding_box, checked = { s, _ -> s.workspace.selectionStyle and SelectionStyle.BOX != 0 }) {
            it.vm.setSelectionStyle(it.state.workspace.selectionStyle xor SelectionStyle.BOX)
        },
        viewItem("dimensions", R.string.view_dimensions, checked = { s, _ -> s.workspace.dimensions }) { it.vm.setDimensions(!it.state.workspace.dimensions) },
    ),
)

/** Top level, in order. Each node's `visible` holds its context rule, so [menuRoots] gives the plate, room or Reshape tree. */
val MENU_TREE: List<MenuNode> = listOf(
    ADD, OBJECT, SELECTION, EDIT_MODEL, ARRANGE, DUPLICATE, DELETE, MEASURE, MESH_EDIT,
    RESHAPE_BRUSH, RESHAPE_STRENGTH, RESHAPE_INVERT, RESHAPE_MIRROR, VIEW, RESHAPE_DONE,
)
