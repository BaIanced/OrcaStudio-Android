package app.orcaandroid.ui.prepare

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PriorityHigh
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import app.orcaandroid.R
import app.orcaandroid.core.PlateSwitcher
import app.orcaandroid.scad.ScadDesign
import app.orcaandroid.core.VolumeType
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.SliceStatus
import app.orcaandroid.ui.EditorTarget
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.ConfirmDialog
import app.orcaandroid.ui.components.CutoutRow
import app.orcaandroid.ui.components.PickerDialog
import app.orcaandroid.ui.components.PickerItem
import app.orcaandroid.ui.components.framePlate
import app.orcaandroid.ui.components.showPlate

/** Controls over the 3D view on the Prepare screen. */
@Composable
fun PrepareOverlay(state: UiState, vm: AppViewModel, view: PlateView?, wide: Boolean) {
    val lab = state.lab
    val sliceOnGrip = sliceOnGrip(state, wide)
    // Tools in a column on the left: wide layouts always, portrait if chosen in the Layout lab.
    val column = wide || lab.toolsLeft
    // Portrait with the switcher on the panel grip (AppScaffold): nothing of it over the 3D view.
    val onHandle = lab.platesOnHandle && !wide
    val filmstrip = lab.plates == PlateSwitcher.FILMSTRIP && state.tool == Tool.None && !onHandle
    CompositionLocalProvider(LocalOverlayAlpha provides lab.overlayAlpha) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize().padding(8.dp).onSizeChanged { containerSize = it }) {
        // Measured: the top bar moves below a camera hole it can't sit beside (CutoutRow).
        var bars by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().onSizeChanged { bars = with(density) { it.height.toDp() } },
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TopBar(state, vm, view, viewButtons = !column, onHandle = onHandle)
            state.calibration?.let { cal ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.calibration_active, CALIBRATION_LABELS[cal.type]?.let { stringResource(it) } ?: cal.name), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.TextButton(onClick = vm.scene::stopCalibration) { Text(stringResource(R.string.end_calibration)) }
                    }
                }
            }
            if (state.showSelectionBanner) SelectionBanner(state, vm)
        }
        if (column) {
            // Below the top bar (and the banners): the column scrolls instead of covering the plate selector.
            val top = maxOf(bars + 12.dp, 60.dp + (if (state.calibration != null) 50.dp else 0.dp) + (if (state.showSelectionBanner) 50.dp else 0.dp))
            val scroll = rememberScrollState()
            Box(Modifier.align(Alignment.TopStart).padding(top = top, bottom = if (filmstrip) 64.dp else 0.dp)) {
                Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ViewButtons(state, vm, view, rail = true)
                    HorizontalDivider(Modifier.width(48.dp).padding(horizontal = 8.dp))
                    ToolButtons(state, vm)
                }
                ScrollCue(scroll, up = true, Modifier.align(Alignment.TopCenter))
                ScrollCue(scroll, up = false, Modifier.align(Alignment.BottomCenter))
            }
        } else {
            val defaultY = maxOf(0f, (containerSize.height - rowSize.height - with(density) { 64.dp.roundToPx() }).toFloat())
            val rowScroll = rememberScrollState()
            Row(
                Modifier
                    .offset {
                        val curX = (if (lab.toolsRowX >= 0f) with(density) { lab.toolsRowX.dp.toPx() } else 0f) + dragOffsetX
                        val curY = (if (lab.toolsRowY >= 0f) with(density) { lab.toolsRowY.dp.toPx() } else defaultY) + dragOffsetY
                        val maxX = maxOf(0f, (containerSize.width - rowSize.width).toFloat())
                        val maxY = maxOf(0f, (containerSize.height - rowSize.height).toFloat())
                        IntOffset(curX.coerceIn(0f, maxX).roundToInt(), curY.coerceIn(0f, maxY).roundToInt())
                    }
                    .then(if (containerSize.width > 0) Modifier.widthIn(max = with(density) { containerSize.width.toDp() }) else Modifier)
                    .onSizeChanged { rowSize = it }
                    .pointerInput(Unit) { detectTapGestures { } }
                    .horizontalScroll(rowScroll),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ToolButtons(
                    state, vm,
                    collapsed = lab.toolsCollapsed,
                    onAddDrag = { delta ->
                        dragOffsetX += delta.x
                        dragOffsetY += delta.y
                    },
                    onAddDragEnd = {
                        val curX = (if (lab.toolsRowX >= 0f) with(density) { lab.toolsRowX.dp.toPx() } else 0f) + dragOffsetX
                        val curY = (if (lab.toolsRowY >= 0f) with(density) { lab.toolsRowY.dp.toPx() } else defaultY) + dragOffsetY
                        val maxX = maxOf(0f, (containerSize.width - rowSize.width).toFloat())
                        val maxY = maxOf(0f, (containerSize.height - rowSize.height).toFloat())
                        val finalX = curX.coerceIn(0f, maxX)
                        val finalY = curY.coerceIn(0f, maxY)
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        vm.setLab(lab.copy(toolsRowX = with(density) { finalX.toDp().value }, toolsRowY = with(density) { finalY.toDp().value }))
                    },
                    onAddLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.setLab(lab.copy(toolsCollapsed = !lab.toolsCollapsed))
                    }
                )
            }
        }
        // With the tool column the filmstrip shares the bottom row with the Slice button and takes the space it leaves.
        val stripWithSlice = filmstrip && column
        // On a narrow canvas (less than about two thumbnails left beside it) the strip gets its own row above.
        if (stripWithSlice) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 60.dp)) {
            if (maxWidth >= 360.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                PlateScale(lab.plateScale) { Filmstrip(state, vm, view, Modifier.weight(1f)) }
                if (!sliceOnGrip) SliceButton(state, vm, Modifier)
            } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlateScale(lab.plateScale) { Filmstrip(state, vm, view, Modifier.fillMaxWidth()) }
                if (!sliceOnGrip) SliceButton(state, vm, Modifier)
            }
        } else if (filmstrip) PlateScale(lab.plateScale) { Filmstrip(state, vm, view, Modifier.align(Alignment.BottomStart).padding(bottom = 120.dp)) }
        // The layer height graph is tall: on wide layouts it sits at the right edge (like the desktop's)
        // above the Slice button, not over the object in the middle.
        val rightPanel = wide && state.tool == Tool.LayerHeight
        Column(if (rightPanel) Modifier.align(Alignment.BottomEnd).padding(bottom = 72.dp)
               else Modifier.align(Alignment.BottomStart).padding(start = if (column) 64.dp else 0.dp,
                   bottom = if (wide) 0.dp else if (column) 72.dp else 116.dp)) {
            ToolPanel(state, vm)
        }
        if (!stripWithSlice && !sliceOnGrip) SliceButton(state, vm, Modifier.align(Alignment.BottomEnd))
        // Setup problems (first case: a filament slot of the wrong type): tap opens the panel at the offending setting.
        val problems = state.setupProblems()
        if (problems.isNotEmpty() && !rightPanel) {
            val label = stringResource(R.string.filament_type_mismatch, problems[0].have, problems[0].tray)
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = { ProblemFocus.show(problems[0].slot) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 72.dp),
                containerColor = androidx.compose.ui.graphics.Color.Red, contentColor = androidx.compose.ui.graphics.Color.White,
            ) { Icon(Icons.Default.PriorityHigh, label) }
        }
        ViewContextMenu(state, vm)
    }
    }
}

private val UiState.showSelectionBanner get() = selectMode || multiSelection.isNotEmpty()

/** While several objects are selected or select mode is on: the count, select all, and leaving select mode. */
@Composable
private fun SelectionBanner(state: UiState, vm: AppViewModel) {
    // Full width with the text taking the rest, so the buttons stay put when the count changes.
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = state.selectedItems.size
            Text(if (count == 0) stringResource(R.string.select_mode_hint) else stringResource(R.string.n_selected, count),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            androidx.compose.material3.TextButton(onClick = vm.scene::selectAll) { Text(stringResource(R.string.select_all)) }
            if (state.selectMode) androidx.compose.material3.TextButton(onClick = { vm.scene.setSelectMode(false) }) { Text(stringResource(R.string.done)) }
        }
    }
}

/**
 * The 3D view's context menu (right click, long press) at the pointer, as on the desktop: object
 * actions on an object (or on all selected objects), adding things on the empty bed.
 */
@Composable
private fun ViewContextMenu(state: UiState, vm: AppViewModel) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val importModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.files.openModels(it) }
    val at = state.contextMenu
    // The overlay is inset by 8 dp from the view whose pixels [at] are in.
    val inset = with(LocalDensity.current) { 8.dp.roundToPx() }
    if (at != null) {
        val close = vm.scene::closeContextMenu
        Box(Modifier.offset { IntOffset(at.first.toInt() - inset, at.second.toInt() - inset) }) {
            DropdownMenu(expanded = true, onDismissRequest = close) {
                val sel = state.selection
                if (state.selectedItems.isNotEmpty()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { close(); vm.scene.duplicate(1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { close(); vm.scene.deleteSelected() })
                }
                if (state.multiSelection.size >= 2)
                    DropdownMenuItem(text = { Text(stringResource(R.string.assemble)) }, leadingIcon = { Icon(Icons.Default.CallMerge, null) },
                        onClick = { close(); vm.scene.assemble() })
                if (sel != null) {
                    if (ScadDesign.of(state.scene.objects.getOrNull(sel.obj)) != null)
                        DropdownMenuItem(text = { Text(stringResource(R.string.scad_edit_design)) }, leadingIcon = { Icon(Icons.Default.Code, null) },
                            onClick = { close(); vm.scad.editObject(sel.obj) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.select_multiple)) }, leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                        onClick = { close(); vm.scene.setSelectMode(true) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.lay_on_face)) }, leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, null) },
                        onClick = { close(); vm.scene.setTool(Tool.LayOnFace) })
                    if (state.multiSelection.isEmpty())
                        DropdownMenuItem(text = { Text(stringResource(if (state.resizing) R.string.resize_done else R.string.resize)) },
                            leadingIcon = { Icon(Icons.Default.OpenInFull, null) }, onClick = { close(); vm.scene.toggleResize() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_modifier)) },
                        onClick = { close(); vm.scene.addVolume(VolumeType.MODIFIER, "box") })
                    if ((state.scene.objects.getOrNull(sel.obj)?.volumes?.count { it.type == VolumeType.PART } ?: 0) >= 2)
                        DropdownMenuItem(text = { Text(stringResource(R.string.mesh_boolean)) },
                            onClick = { close(); dialog = "boolean" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.object_settings_title)) },
                        onClick = { close(); vm.presets.openEditor(EditorTarget.Object(sel.obj)) })
                } else if (state.multiSelection.isEmpty()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.import_model)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                        onClick = { close(); importModels.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_shape)) }, leadingIcon = { Icon(Icons.Default.Interests, null) },
                        onClick = { close(); dialog = "primitive" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_text)) }, leadingIcon = { Icon(Icons.Default.TextFields, null) },
                        onClick = { close(); dialog = "text" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_svg)) }, leadingIcon = { Icon(Icons.Default.Category, null) },
                        onClick = { close(); dialog = "svg" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_openscad)) }, leadingIcon = { Icon(Icons.Default.Code, null) },
                        onClick = { close(); vm.scad.open() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.shape_builder)) }, leadingIcon = { Icon(Icons.Default.Architecture, null) },
                        onClick = { close(); vm.scad.newShapes() })
                    if (!state.scene.isEmpty)
                        DropdownMenuItem(text = { Text(stringResource(R.string.arrange)) }, leadingIcon = { Icon(Icons.Default.AutoAwesomeMosaic, null) },
                            onClick = { close(); vm.scene.arrange(false) })
                }
            }
        }
    }
    AddDialogs(state, vm, dialog ?: state.addDialog) { dialog = null; vm.scene.openAddDialog(null) }
}

/** Plate switcher (as chosen in the Layout lab), profile-update chip, undo/redo/view (when not in the tool column) and File. */
@Composable
private fun TopBar(state: UiState, vm: AppViewModel, view: PlateView?, viewButtons: Boolean, onHandle: Boolean) {
    val alpha = LocalOverlayAlpha.current
    val bar = MaterialTheme.colorScheme.surface.copy(alpha = alpha)
    val views: @Composable () -> Unit = { Surface(shape = RoundedCornerShape(20.dp), color = bar) { Row { ViewButtons(state, vm, view, rail = false) } } }
    // In fullscreen portrait the bar can sit beside the camera hole (CutoutRow). With the switcher on the grip the
    // view buttons take the free start, left of the hole, and File stays right of it (together they didn't fit past it).
    CutoutRow(minStart = 96.dp, start = {
        if (onHandle) { if (viewButtons) views() }
        else PlateScale(state.lab.plateScale) {
            when (state.lab.plates) {
                PlateSwitcher.TABS -> Surface(shape = RoundedCornerShape(20.dp), color = bar) { PlateTabs(state, vm, view, Modifier.padding(start = 4.dp)) }
                PlateSwitcher.FILMSTRIP -> if (state.lab.plateLabel) Surface(shape = RoundedCornerShape(20.dp), color = bar) { PlateLabel(state, vm) }
                PlateSwitcher.CHIPS -> PlateChips(state, vm, view)
            }
        }
    }) {
        UpdatesChip(state, vm)
        if (viewButtons && !onHandle) views()
        FileButton(state, vm)
    }
}

/** The original plate chips: tap the active one for its menu. */
@Composable
private fun PlateChips(state: UiState, vm: AppViewModel, view: PlateView?) {
    var plateMenu by remember { mutableStateOf<Int?>(null) }
    // Unselected chips are transparent by default and vanish against the canvas; use the bar colour.
    val bar = MaterialTheme.colorScheme.surface.copy(alpha = LocalOverlayAlpha.current)
    PlateRow(state, vm, Modifier, 4.dp, Alignment.CenterVertically, add = {
        AssistChip(onClick = vm.scene::addPlate, label = { Text("+") }, colors = AssistChipDefaults.assistChipColors(containerColor = bar))
    }) { plate, _ ->
        val plateName = stringResource(R.string.plate_n, plate.index + 1)
        Box {
            FilterChip(
                selected = plate.index == state.activePlate,
                onClick = {
                    if (plate.index == state.activePlate) plateMenu = plate.index
                    else { vm.scene.selectPlate(plate.index); view?.showPlate(plate.index) }
                },
                // Just the number ("Plate" took too much room, user 2026-10-14); "Plate n" for TalkBack.
                label = { Text("${plate.index + 1}") },
                modifier = Modifier.semantics { contentDescription = plateName },
                trailingIcon = if (plate.index == state.activePlate) { { Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp)) } } else null,
                colors = FilterChipDefaults.filterChipColors(containerColor = bar),
            )
            if (plateMenu == plate.index) PlateMenu(state, vm, plate.index) { plateMenu = null }
        }
    }
}

/** Undo, redo and the view menu: tool-column buttons ([rail]) or plain icons in the top bar. */
@Composable
private fun ViewButtons(state: UiState, vm: AppViewModel, view: PlateView?, rail: Boolean) {
    var viewMenu by remember { mutableStateOf(false) }
    val button: @Composable (ImageVector, String, Boolean, () -> Unit) -> Unit = { icon, label, enabled, onClick ->
        if (rail) ToolButton(icon, label, enabled = enabled, onClick = onClick)
        else Tip(label) { IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) } }
    }
    button(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo), state.scene.canUndo, vm.scene::undo)
    button(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo), state.scene.canRedo, vm.scene::redo)
    Box {
        button(Icons.Default.Videocam, stringResource(R.string.view), true) { viewMenu = true }
        DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
            listOf(R.string.view_iso to 0, R.string.view_top to 1, R.string.view_front to 2, R.string.view_left to 3, R.string.view_right to 4).forEach { (label, id) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { viewMenu = false; view?.camera?.preset(id) })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.view_fit)) }, onClick = { viewMenu = false; view?.framePlate(state.activePlate) },
                leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null) })
            HorizontalDivider()
            // The desktop's View menu: Use Perspective View / Use Orthogonal View (K toggles).
            listOf(R.string.view_perspective to true, R.string.view_orthographic to false).forEach { (label, on) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { viewMenu = false; vm.setPerspective(on) },
                    trailingIcon = { if (state.perspective == on) Icon(Icons.Default.Check, null) })
            }
            HorizontalDivider()
            // The desktop's canvas toolbar button: toggles the cut and opens its panel.
            DropdownMenuItem(text = { Text(stringResource(R.string.section_view)) },
                onClick = { viewMenu = false; view?.section?.let { it.toggle(); it.panelOpen = it.active } },
                leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                trailingIcon = { if (view?.section?.active == true) Icon(Icons.Default.Check, null) })
        }
    }
}

@Composable
internal fun PlateMenu(state: UiState, vm: AppViewModel, plate: Int, onDismiss: () -> Unit) {
    var bedPicker by remember { mutableStateOf(false) }
    var gcodes by remember { mutableStateOf(false) }
    // The menu hides while one of its dialogs is open.
    DropdownMenu(expanded = !bedPicker && !gcodes, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.slice_plate)) }, onClick = { onDismiss(); vm.slicing.slice(plate) })
        DropdownMenuItem(text = { Text(stringResource(R.string.arrange_plate)) }, onClick = { onDismiss(); vm.scene.arrange(false) })
        if (state.supportsBedTypes) {
            DropdownMenuItem(text = { Text(stringResource(R.string.plate_type) + ": " + vm.translator.plate(state.bedTypeOf(plate).orEmpty())) },
                onClick = { bedPicker = true })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.layer_gcodes)) }, onClick = { gcodes = true })
        if (state.scene.plates.size > 1)
            // No confirm: the plate's objects stay as floaters, like on the desktop (user, 2026-10-13).
            DropdownMenuItem(text = { Text(stringResource(R.string.delete_plate)) }, onClick = { onDismiss(); vm.scene.deletePlate(plate) })
    }
    if (bedPicker) {
        val current = state.bedTypeOf(plate)
        PickerDialog(stringResource(R.string.plate_type), current,
            state.bedTypeChoices(current).map { PickerItem(it, vm.translator.plate(it)) }, onDismiss = { bedPicker = false; onDismiss() }) {
            bedPicker = false; onDismiss(); vm.scene.setPlateBedType(plate, it)
        }
    }
    if (gcodes) LayerGcodeDialog(state, vm, plate) { gcodes = false; onDismiss() }
}

@Composable
internal fun ProjectMenu(state: UiState, vm: AppViewModel, open: Boolean, onDismiss: () -> Unit) {
    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm.files::openProject) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { it?.let(vm.files::saveProject) }
    val exportStl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { it?.let { u -> vm.files.exportStl(u, state.activePlate) } }
    var confirmNew by remember { mutableStateOf(false) }
    // Like the desktop: an unsaved project is named after its first object.
    val base = state.projectName ?: state.scene.objects.firstOrNull()?.name?.substringBeforeLast('.') ?: "project"
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.new_project)) }, onClick = { onDismiss(); if (state.projectDirty) confirmNew = true else vm.files.newProject() })
        DropdownMenuItem(text = { Text(stringResource(R.string.open_project)) }, onClick = { onDismiss(); openProject.launch(arrayOf("*/*")) })
        DropdownMenuItem(text = { Text(stringResource(R.string.save_project)) }, onClick = { onDismiss(); saveProject.launch("$base.3mf") },
            enabled = !state.scene.isEmpty)
        DropdownMenuItem(text = { Text(stringResource(R.string.export_stl)) }, onClick = { onDismiss(); exportStl.launch("$base.stl") },
            enabled = !state.scene.isEmpty)
        if (state.recents.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.recent), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            state.recents.take(6).forEach { r ->
                DropdownMenuItem(text = { Text(r.name, maxLines = 1) }, onClick = { onDismiss(); vm.files.openRecent(r) })
            }
        }
    }
    if (confirmNew) ConfirmDialog(stringResource(R.string.new_project), stringResource(R.string.discard_changes), stringResource(R.string.discard),
        vm.files::newProject) { confirmNew = false }
}

/** Context-dependent tool buttons: plate tools always, object tools once something is selected. */
@Composable
private fun ToolButtons(
    state: UiState,
    vm: AppViewModel,
    collapsed: Boolean = false,
    onAddDrag: ((Offset) -> Unit)? = null,
    onAddDragEnd: (() -> Unit)? = null,
    onAddLongPress: (() -> Unit)? = null,
) {
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var paintMenu by remember { mutableStateOf(false) }
    var splitMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val selected = state.selection != null

    val currentOnTap by rememberUpdatedState { addMenu = true }
    val currentOnLongPress by rememberUpdatedState(onAddLongPress)
    val currentOnDrag by rememberUpdatedState(onAddDrag)
    val currentOnDragEnd by rememberUpdatedState(onAddDragEnd)

    val addModifier = if (onAddDrag != null) {
        Modifier.pointerInput(Unit) {
            detectTapDragOrLongPress(
                onTap = { currentOnTap() },
                onLongPress = { currentOnLongPress?.invoke() },
                onDrag = { currentOnDrag?.invoke(it) },
                onDragEnd = { currentOnDragEnd?.invoke() }
            )
        }
    } else Modifier

    Box(addModifier) {
        ToolButton(Icons.Default.Add, stringResource(R.string.add), active = false, tip = onAddDrag == null) {
            if (onAddDrag == null) addMenu = true
        }
        AddMenu(state, vm, addMenu, onDismiss = { addMenu = false }, onDialog = { dialog = it })
    }
    if (!collapsed) {
        ToolButton(Icons.Default.AutoAwesomeMosaic, stringResource(R.string.arrange), enabled = !state.scene.isEmpty) { vm.scene.arrange(false) }
        ToolButton(AutoOrientIcon, stringResource(R.string.auto_orient), enabled = !state.scene.isEmpty) { vm.scene.autoOrient() }
        ToolButton(Icons.Default.SelectAll, stringResource(R.string.select_multiple), active = state.selectMode, enabled = !state.scene.isEmpty) {
            vm.scene.setSelectMode(!state.selectMode)
        }
        if (state.multiSelection.isNotEmpty()) {
            ToolButton(Icons.Default.ContentCopy, stringResource(R.string.duplicate)) { vm.scene.duplicate(1) }
            ToolButton(Icons.Default.CallMerge, stringResource(R.string.assemble), enabled = state.multiSelection.size >= 2) { vm.scene.assemble() }
            ToolButton(Icons.Default.Delete, stringResource(R.string.delete)) { vm.scene.deleteSelected() }
        } else if (selected) {
            ToolButton(Icons.Default.VerticalAlignBottom, stringResource(R.string.lay_on_face), active = state.tool == Tool.LayOnFace) {
                vm.scene.setTool(if (state.tool == Tool.LayOnFace) Tool.None else Tool.LayOnFace)
            }
            ToolButton(Icons.Default.ContentCut, stringResource(R.string.cut), active = state.tool is Tool.Cut) {
                if (state.tool is Tool.Cut) vm.scene.setTool(Tool.None) else vm.scene.startCut()
            }
            Box {
                ToolButton(Icons.Default.Brush, stringResource(R.string.paint), active = state.tool is Tool.Paint) { paintMenu = true }
                DropdownMenu(expanded = paintMenu, onDismissRequest = { paintMenu = false }) {
                    listOf(
                        Triple(R.string.paint_supports, "support", 1),
                        Triple(R.string.paint_seam, "seam", 1),
                        Triple(R.string.paint_fuzzy, "fuzzy", 1),
                        Triple(R.string.paint_color, "color", 2),
                    ).forEach { (label, kind, state0) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { paintMenu = false; vm.scene.setTool(Tool.Paint(kind, state0, 3f)) })
                    }
                }
            }
            ToolButton(Icons.Default.Layers, stringResource(R.string.variable_layer_height), active = state.tool == Tool.LayerHeight) {
                vm.scene.setTool(if (state.tool == Tool.LayerHeight) Tool.None else Tool.LayerHeight)
            }
            ToolButton(Icons.Default.ContentCopy, stringResource(R.string.duplicate)) { vm.scene.duplicate(1) }
            ToolButton(Icons.Default.Delete, stringResource(R.string.delete)) { vm.scene.deleteSelected() }
            Box {
                ToolButton(Icons.Default.CallSplit, stringResource(R.string.split)) { splitMenu = true }
                DropdownMenu(expanded = splitMenu, onDismissRequest = { splitMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.split_objects)) }, onClick = { splitMenu = false; vm.scene.split(false) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.split_parts)) }, onClick = { splitMenu = false; vm.scene.split(true) })
                }
            }
            Box {
                ToolButton(Icons.Default.MoreVert, stringResource(R.string.more)) { moreMenu = true }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_part)) }, onClick = { moreMenu = false; vm.scene.addVolume(VolumeType.PART, "box") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mesh_boolean)) }, onClick = { moreMenu = false; dialog = "boolean" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_modifier)) }, onClick = { moreMenu = false; vm.scene.addVolume(VolumeType.MODIFIER, "box") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_support_blocker)) }, onClick = { moreMenu = false; vm.scene.addVolume(VolumeType.SUPPORT_BLOCKER, "box") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_support_enforcer)) }, onClick = { moreMenu = false; vm.scene.addVolume(VolumeType.SUPPORT_ENFORCER, "box") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_negative_volume)) }, onClick = { moreMenu = false; vm.scene.addVolume(VolumeType.NEGATIVE, "box") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.simplify)) }, onClick = { moreMenu = false; dialog = "simplify" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.repair)) }, onClick = { moreMenu = false; vm.scene.repair() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.measure)) }, onClick = { moreMenu = false; vm.scene.setTool(Tool.Measure) },
                        leadingIcon = { Icon(Icons.Default.Straighten, null) })
                }
            }
        } else if (!state.scene.isEmpty) {
            ToolButton(Icons.Default.Straighten, stringResource(R.string.measure), active = state.tool == Tool.Measure) {
                vm.scene.setTool(if (state.tool == Tool.Measure) Tool.None else Tool.Measure)
            }
        }
    }
    AddDialogs(state, vm, dialog) { dialog = null }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, tip: Boolean = true, onClick: () -> Unit) {
    val alpha = LocalOverlayAlpha.current
    // No tooltip where long press means something else (the movable row's + collapses the row).
    val wrap: @Composable (@Composable () -> Unit) -> Unit = if (tip) { c -> Tip(label, c) } else { c -> c() }
    wrap {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            colors = if (active) androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) else
                androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = alpha)),
            modifier = Modifier.size(48.dp),
        ) { Icon(icon, label) }
    }
}

/** Portrait Prepare without the OpenSCAD editor: the Slice button sits at the right end of the panel's grip (user, 2026-10-17). */
fun sliceOnGrip(state: UiState, wide: Boolean) = !wide && state.scad == null

/** The Slice button on the panel grip, sized by the Layout lab's own slider (on top of the main UI size only). */
@Composable
fun GripSliceButton(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
        androidx.compose.ui.unit.Density(base.density * state.lab.sliceScale, base.fontScale)) {
        SliceButton(state, vm, modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun SliceButton(state: UiState, vm: AppViewModel, modifier: Modifier) {
    val running = state.slice as? SliceStatus.Running
    if (running != null) {
        Surface(modifier.width(260.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${running.text} · ${running.percent} %", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                LinearProgressIndicator(progress = { running.percent / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm.slicing::cancelSlice, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        }
    } else {
        var menu by remember { mutableStateOf(false) }
        val label = stringResource(if (state.scene.plates.size > 1) R.string.slice_plate_n else R.string.slice, state.activePlate + 1)
        Box(modifier) {
            // The FAB's text isn't in the accessibility tree (no label for TalkBack or ui dumps): name it here.
            ExtendedFloatingActionButton(
                onClick = { vm.slicing.slice() },
                modifier = Modifier.semantics { contentDescription = label },
                text = { Text(label) },
                icon = { Icon(Icons.Default.Layers, null) },
            )
            if (state.scene.plates.size > 1) {
                IconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) { Icon(Icons.Default.ExpandMore, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.slice_all)) }, onClick = { menu = false; vm.slicing.sliceAll() })
                }
            }
        }
    }
}

/**
 * Small arrow at the end of the tool column while more buttons are hidden that way (user, 2026-10-13);
 * tapping it scrolls by most of a screen.
 */
@Composable
private fun ScrollCue(scroll: androidx.compose.foundation.ScrollState, up: Boolean, modifier: Modifier) {
    val show = if (up) scroll.canScrollBackward else scroll.canScrollForward
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.animation.AnimatedVisibility(show, modifier, enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut()) {
        Surface(
            onClick = { scope.launch { scroll.animateScrollBy(if (up) -scroll.viewportSize * 0.8f else scroll.viewportSize * 0.8f) } },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = LocalOverlayAlpha.current),
            modifier = Modifier.size(width = 28.dp, height = 18.dp),
        ) {
            Icon(if (up) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                stringResource(if (up) R.string.scroll_up else R.string.scroll_down), Modifier.padding(1.dp))
        }
    }
}

/** The plate switcher chosen in the Layout lab, as a bar of its own (nested on the portrait panel's grip). */
@Composable
fun PlateSwitcherBar(state: UiState, vm: AppViewModel, view: PlateView?, modifier: Modifier = Modifier) {
    PlateScale(state.lab.plateScale) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            when (state.lab.plates) {
                PlateSwitcher.TABS -> PlateTabs(state, vm, view, Modifier.weight(1f))
                PlateSwitcher.FILMSTRIP -> Filmstrip(state, vm, view, Modifier.weight(1f))
                PlateSwitcher.CHIPS -> Box(Modifier.weight(1f)) { PlateChips(state, vm, view) }
            }
        }
    }
}

/**
 * Detects tap, long-press, or drag on the + button without gesture conflicts.
 * Pointer movements past touch slop are consumed so the underlying 3D view does not orbit.
 */
private suspend fun PointerInputScope.detectTapDragOrLongPress(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var totalDrag = Offset.Zero
        var isDragging = false
        var tapped = false

        val passedSlop = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!change.pressed) {
                    change.consume()
                    tapped = true
                    return@withTimeoutOrNull false
                }
                totalDrag += change.positionChange()
                if (totalDrag.x * totalDrag.x + totalDrag.y * totalDrag.y > slop * slop) {
                    change.consume()
                    return@withTimeoutOrNull true
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }

        if (tapped) {
            onTap()
            return@awaitEachGesture
        }
        if (passedSlop == null) {
            onLongPress()
        } else if (passedSlop) {
            isDragging = true
            onDrag(totalDrag)
        }

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (isDragging) onDragEnd()
                break
            }
            // positionChange(): both positions in the button's current coordinates, so the row moving under the finger doesn't skew it.
            val diff = change.positionChange()
            totalDrag += diff
            if (!isDragging && (totalDrag.x * totalDrag.x + totalDrag.y * totalDrag.y > slop * slop)) {
                isDragging = true
            }
            if (isDragging) {
                change.consume()
                onDrag(diff)
            }
        }
    }
}
