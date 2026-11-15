package app.orcaandroid.ui.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Screen
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.ModeStrip
import app.orcaandroid.ui.components.Room

/** Painting of every kind (colour, supports, seam, fuzzy skin) opens the paint shop: a workspace of its own instead of a card over the plate (user plan 2026-10-21, all kinds 2026-10-29). */
internal val UiState.inPaintShop get() = chrome.screen == Screen.PREPARE && workspace.tool is Tool.Paint

/**
 * The room the user is in, or null on the plain plate: Make while the builder or code pane is open, Reshape for Sculpt and
 * Edit mesh, Paint for the paint shop, Cut for the cut card. Each shows the mode strip and a frame round the 3D view.
 */
internal val UiState.room: Room? get() = when {
    chrome.screen != Screen.PREPARE -> null
    // The focus room is Make for the whole visit, so the strip colour does not change with the tool.
    workspace.focus != null -> Room.MAKE
    inPaintShop -> Room.PAINT
    design.scad != null -> Room.MAKE
    else -> when (workspace.tool) {
        is Tool.Sculpt, is Tool.MeshEdit -> Room.RESHAPE
        is Tool.Cut -> Room.CUT
        else -> null
    }
}

/**
 * The strip across the top of a room: "Editing: <name>", the room's Undo / Redo (the builder's stack in Make, the plate's
 * elsewhere) and Done, which does what Back does today (Make closes the editor, the others drop the tool). Paint keeps the
 * plate's view menu on it. The tab bar, plate tools and Slice button stay hidden in the paint shop.
 */
@Composable
internal fun RoomBar(room: Room, state: UiState, vm: AppViewModel, view: PlateView?) {
    val focus = state.workspace.focus
    // The builder's stack drives Undo / Redo while the builder is open; in the focus room a mesh tool uses the plate's.
    val make = room == Room.MAKE && (focus == null || state.design.scad != null)
    val name = (if (focus != null) focus.name else if (make) state.design.scad?.name else state.workspace.selectedItems.firstOrNull()
        ?.let { state.workspace.scene.objects.getOrNull(it.obj)?.name })?.takeIf { it.isNotBlank() }
        ?: state.workspace.projectName?.takeIf { it.isNotBlank() } ?: stringResource(room.label)
    ModeStrip(room, name,
        canUndo = if (make) vm.shapes.canUndo else state.workspace.scene.canUndo,
        canRedo = if (make) vm.shapes.canRedo else state.workspace.scene.canRedo,
        onUndo = { if (make) vm.shapes.undo() else vm.scene.undo() },
        onRedo = { if (make) vm.shapes.redo() else vm.scene.redo() },
        onDone = { if (focus != null) vm.closeRoom() else if (make) vm.scad.close() else vm.scene.setTool(Tool.None) },
        actions = { if (room == Room.PAINT) ViewButtons(state, vm, view, rail = false, undoRedo = false) })
}

/** The colour paint options, docked at the bottom in portrait and beside the view ([side]) in landscape. */
@Composable
internal fun PaintShopDock(state: UiState, vm: AppViewModel, side: Boolean, modifier: Modifier) {
    val tool = state.workspace.tool as? Tool.Paint ?: return
    Surface(modifier, tonalElevation = 2.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("color" to (R.string.paint_color to 2), "support" to (R.string.paint_supports to 1),
                    "seam" to (R.string.paint_seam to 1), "fuzzy" to (R.string.paint_fuzzy to 1)).forEach { (kind, p) ->
                    FilterChip(tool.kind == kind, { if (tool.kind != kind) vm.scene.openPaint(kind, p.second) }, label = { Text(stringResource(p.first)) })
                }
            }
            if (tool.kind == "color") ColorPaintOptions(state, vm, tool, layers = side) else PaintOptions(state, vm, tool)
        }
    }
}
