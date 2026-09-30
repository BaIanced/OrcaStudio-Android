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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.VolumeType
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.SliceStatus
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.ConfirmDialog
import app.orcaandroid.ui.components.PickerDialog
import app.orcaandroid.ui.components.PickerItem
import app.orcaandroid.ui.components.framePlate

/** Controls over the 3D view on the Prepare screen. */
@Composable
fun PrepareOverlay(state: UiState, vm: AppViewModel, view: PlateView?, wide: Boolean) {
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TopBar(state, vm, view)
            state.calibration?.let { cal ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.calibration_active, cal.name), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.TextButton(onClick = vm::stopCalibration) { Text(stringResource(R.string.end_calibration)) }
                    }
                }
            }
        }
        if (wide) {
            Column(Modifier.align(Alignment.CenterStart).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButtons(state, vm)
            }
        } else {
            Row(Modifier.align(Alignment.BottomStart).padding(bottom = 64.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButtons(state, vm)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = if (wide) 64.dp else 0.dp, bottom = if (wide) 0.dp else 116.dp)) {
            ToolPanel(state, vm)
        }
        SliceButton(state, vm, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun TopBar(state: UiState, vm: AppViewModel, view: PlateView?) {
    var plateMenu by remember { mutableStateOf<Int?>(null) }
    var viewMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.scene.plates.forEach { plate ->
                Box {
                    FilterChip(
                        selected = plate.index == state.activePlate,
                        onClick = {
                            if (plate.index == state.activePlate) plateMenu = plate.index
                            else { vm.selectPlate(plate.index); view?.framePlate(plate.index) }
                        },
                        label = { Text(stringResource(R.string.plate_n, plate.index + 1)) },
                        trailingIcon = if (plate.index == state.activePlate) { { Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp)) } } else null,
                    )
                    if (plateMenu == plate.index) PlateMenu(state, vm, plate.index) { plateMenu = null }
                }
            }
            AssistChip(onClick = vm::addPlate, label = { Text("+") })
        }
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp) {
            Row {
                IconButton(onClick = vm::undo, enabled = state.scene.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                IconButton(onClick = vm::redo, enabled = state.scene.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                Box {
                    IconButton(onClick = { viewMenu = true }) { Icon(Icons.Default.Videocam, stringResource(R.string.view)) }
                    DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                        listOf(R.string.view_iso to 0, R.string.view_top to 1, R.string.view_front to 2, R.string.view_left to 3, R.string.view_right to 4).forEach { (label, id) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { viewMenu = false; view?.camera?.preset(id) })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.view_fit)) }, onClick = { viewMenu = false; view?.framePlate(state.activePlate) },
                            leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null) })
                    }
                }
                Box {
                    IconButton(onClick = { projectMenu = true }) { Icon(Icons.Default.Folder, stringResource(R.string.project)) }
                    ProjectMenu(state, vm, projectMenu) { projectMenu = false }
                }
            }
        }
    }
}

@Composable
private fun PlateMenu(state: UiState, vm: AppViewModel, plate: Int, onDismiss: () -> Unit) {
    var bedPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var gcodes by remember { mutableStateOf(false) }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.slice_plate)) }, onClick = { onDismiss(); vm.slice(plate) })
        DropdownMenuItem(text = { Text(stringResource(R.string.arrange_plate)) }, onClick = { onDismiss(); vm.arrange(false) })
        if (state.supportsBedTypes) {
            val current = state.scene.plates.getOrNull(plate)?.bedType?.ifEmpty { null } ?: state.setup?.defaultBedType.orEmpty()
            DropdownMenuItem(text = { Text(stringResource(R.string.plate_type) + ": " + vm.translator.tr(current)) }, onClick = { bedPicker = true })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.layer_gcodes)) }, onClick = { gcodes = true })
        if (state.scene.plates.size > 1)
            DropdownMenuItem(text = { Text(stringResource(R.string.delete_plate)) }, onClick = { confirmDelete = true })
    }
    if (bedPicker) {
        PickerDialog(stringResource(R.string.plate_type), state.scene.plates.getOrNull(plate)?.bedType,
            state.setup?.bedTypes.orEmpty().map { PickerItem(it, vm.translator.tr(it)) }, onDismiss = { bedPicker = false; onDismiss() }) {
            bedPicker = false; onDismiss(); vm.setPlateBedType(plate, it)
        }
    }
    if (confirmDelete) ConfirmDialog(stringResource(R.string.delete_plate), stringResource(R.string.delete_plate_text), stringResource(R.string.delete),
        { vm.deletePlate(plate) }) { confirmDelete = false; onDismiss() }
    if (gcodes) LayerGcodeDialog(state, vm, plate) { gcodes = false; onDismiss() }
}

@Composable
private fun ProjectMenu(state: UiState, vm: AppViewModel, open: Boolean, onDismiss: () -> Unit) {
    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openProject) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { it?.let(vm::saveProject) }
    val exportStl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { it?.let { u -> vm.exportStl(u, state.activePlate) } }
    var confirmNew by remember { mutableStateOf(false) }
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.new_project)) }, onClick = { onDismiss(); if (state.projectDirty) confirmNew = true else vm.newProject() })
        DropdownMenuItem(text = { Text(stringResource(R.string.open_project)) }, onClick = { onDismiss(); openProject.launch(arrayOf("*/*")) })
        DropdownMenuItem(text = { Text(stringResource(R.string.save_project)) }, onClick = { onDismiss(); saveProject.launch((state.projectName ?: "project") + ".3mf") },
            enabled = !state.scene.isEmpty)
        DropdownMenuItem(text = { Text(stringResource(R.string.export_stl)) }, onClick = { onDismiss(); exportStl.launch((state.projectName ?: "plate") + ".stl") },
            enabled = !state.scene.isEmpty)
        if (state.recents.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.recent), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            state.recents.take(6).forEach { r ->
                DropdownMenuItem(text = { Text(r.name, maxLines = 1) }, onClick = { onDismiss(); vm.openRecent(r) })
            }
        }
    }
    if (confirmNew) ConfirmDialog(stringResource(R.string.new_project), stringResource(R.string.discard_changes), stringResource(R.string.discard),
        vm::newProject) { confirmNew = false }
}

/** Context-dependent tool buttons: plate tools always, object tools once something is selected. */
@Composable
private fun ToolButtons(state: UiState, vm: AppViewModel) {
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var paintMenu by remember { mutableStateOf(false) }
    var splitMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val selected = state.selection != null

    Box {
        ToolButton(Icons.Default.Add, stringResource(R.string.add), active = false) { addMenu = true }
        AddMenu(state, vm, addMenu, onDismiss = { addMenu = false }, onDialog = { dialog = it })
    }
    ToolButton(Icons.Default.AutoAwesomeMosaic, stringResource(R.string.arrange), enabled = !state.scene.isEmpty) { vm.arrange(false) }
    ToolButton(Icons.Default._3dRotation, stringResource(R.string.auto_orient), enabled = !state.scene.isEmpty) { vm.autoOrient() }
    if (selected) {
        ToolButton(Icons.Default.VerticalAlignBottom, stringResource(R.string.lay_on_face), active = state.tool == Tool.LayOnFace) {
            vm.setTool(if (state.tool == Tool.LayOnFace) Tool.None else Tool.LayOnFace)
        }
        ToolButton(Icons.Default.ContentCut, stringResource(R.string.cut), active = state.tool is Tool.Cut) {
            val inst = state.selectedObject?.instances?.getOrNull(state.selection!!.instance)
            vm.setTool(if (state.tool is Tool.Cut) Tool.None else Tool.Cut((inst?.let { it.min.z + it.size.z / 2 }) ?: 5f))
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
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { paintMenu = false; vm.setTool(Tool.Paint(kind, state0, 3f)) })
                }
            }
        }
        ToolButton(Icons.Default.Layers, stringResource(R.string.variable_layer_height), active = state.tool == Tool.LayerHeight) {
            vm.setTool(if (state.tool == Tool.LayerHeight) Tool.None else Tool.LayerHeight)
        }
        ToolButton(Icons.Default.ContentCopy, stringResource(R.string.duplicate)) { vm.duplicate(1) }
        ToolButton(Icons.Default.Delete, stringResource(R.string.delete)) { vm.deleteSelected() }
        Box {
            ToolButton(Icons.Default.CallSplit, stringResource(R.string.split)) { splitMenu = true }
            DropdownMenu(expanded = splitMenu, onDismissRequest = { splitMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.split_objects)) }, onClick = { splitMenu = false; vm.split(false) })
                DropdownMenuItem(text = { Text(stringResource(R.string.split_parts)) }, onClick = { splitMenu = false; vm.split(true) })
            }
        }
        Box {
            ToolButton(Icons.Default.MoreVert, stringResource(R.string.more)) { moreMenu = true }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.add_modifier)) }, onClick = { moreMenu = false; vm.addVolume(VolumeType.MODIFIER, "box") })
                DropdownMenuItem(text = { Text(stringResource(R.string.add_support_blocker)) }, onClick = { moreMenu = false; vm.addVolume(VolumeType.SUPPORT_BLOCKER, "box") })
                DropdownMenuItem(text = { Text(stringResource(R.string.add_support_enforcer)) }, onClick = { moreMenu = false; vm.addVolume(VolumeType.SUPPORT_ENFORCER, "box") })
                DropdownMenuItem(text = { Text(stringResource(R.string.add_negative_volume)) }, onClick = { moreMenu = false; vm.addVolume(VolumeType.NEGATIVE, "box") })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.simplify)) }, onClick = { moreMenu = false; dialog = "simplify" })
                DropdownMenuItem(text = { Text(stringResource(R.string.repair)) }, onClick = { moreMenu = false; vm.repair() })
                DropdownMenuItem(text = { Text(stringResource(R.string.measure)) }, onClick = { moreMenu = false; vm.setTool(Tool.Measure) },
                    leadingIcon = { Icon(Icons.Default.Straighten, null) })
            }
        }
    } else if (!state.scene.isEmpty) {
        ToolButton(Icons.Default.Straighten, stringResource(R.string.measure), active = state.tool == Tool.Measure) {
            vm.setTool(if (state.tool == Tool.Measure) Tool.None else Tool.Measure)
        }
    }
    AddDialogs(state, vm, dialog) { dialog = null }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (active) androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) else
            androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(),
        modifier = Modifier.size(48.dp),
    ) { Icon(icon, label) }
}

@Composable
private fun SliceButton(state: UiState, vm: AppViewModel, modifier: Modifier) {
    val running = state.slice as? SliceStatus.Running
    if (running != null) {
        Surface(modifier.width(260.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${running.text} · ${running.percent} %", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                LinearProgressIndicator(progress = { running.percent / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm::cancelSlice, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        }
    } else {
        var menu by remember { mutableStateOf(false) }
        Box(modifier) {
            ExtendedFloatingActionButton(
                onClick = { if (!state.scene.isEmpty) vm.slice() },
                text = { Text(stringResource(if (state.scene.plates.size > 1) R.string.slice_plate_n else R.string.slice, state.activePlate + 1)) },
                icon = { Icon(Icons.Default.Layers, null) },
            )
            if (state.scene.plates.size > 1) {
                IconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) { Icon(Icons.Default.ExpandMore, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.slice_all)) }, onClick = { menu = false; vm.sliceAll() })
                }
            }
        }
    }
}
