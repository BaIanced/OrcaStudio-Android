package app.orcaandroid.ui.scad

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.scad.D3
import app.orcaandroid.scad.Dim
import app.orcaandroid.scad.Repeat
import app.orcaandroid.scad.Shape
import app.orcaandroid.scad.ShapeDesign
import app.orcaandroid.scad.Sketch
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.ScadEditorState
import app.orcaandroid.ui.ShapeController
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.ui.components.NumberField
import app.orcaandroid.ui.components.PickerField
import app.orcaandroid.ui.components.PickerItem
import app.orcaandroid.ui.prepare.ColorDot

/** Shapes the palette offers, in Tinkercad's order. */
private val PALETTE = listOf(
    Shape.Kind.BOX, Shape.Kind.CYLINDER, Shape.Kind.SPHERE, Shape.Kind.CONE, Shape.Kind.PYRAMID, Shape.Kind.WEDGE,
    Shape.Kind.ROUNDED_BOX, Shape.Kind.TUBE, Shape.Kind.TORUS, Shape.Kind.DOME, Shape.Kind.PRISM, Shape.Kind.TEXT,
    Shape.Kind.SKETCH,
)

/**
 * The shape builder (no code): palette, tools, the list of shapes and the selected shape's
 * settings. Keys while nothing is typed in a field: Delete, Ctrl+D / G / Shift+G / Z / Y / A,
 * arrows (1 mm, Shift 10 mm), Page Up / Down (height), H hole, S solid, Esc deselect.
 */
@Composable
fun ShapesTab(s: ScadEditorState, vm: AppViewModel) {
    val d = s.shapes ?: return
    val sh = vm.shapes
    val focus = remember { FocusRequester() }
    var sketching by remember { mutableStateOf<Int?>(null) }
    val stl = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(sh::importMesh) }
    sketching?.let { id ->
        val shape = d.find(id)
        if (shape == null) sketching = null
        else SketchEditor(shape.sketch ?: Sketch.START, shape.x(Shape.REVOLVE, 0.0) != 0.0, d.dims, shape.name,
            onDone = { s, dims -> sh.setSketch(id, s, dims); sketching = null }, onCancel = { sketching = null })
    }
    Column(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            val step = if (e.isShiftPressed) 10.0 else 1.0
            when {
                e.key == Key.Delete || e.key == Key.Backspace -> { sh.delete(); true }
                e.isCtrlPressed && e.key == Key.D -> { sh.duplicate(); true }
                e.isCtrlPressed && e.key == Key.G && e.isShiftPressed -> { sh.ungroup(); true }
                e.isCtrlPressed && e.key == Key.G -> { sh.group(); true }
                e.isCtrlPressed && (e.key == Key.Y || (e.key == Key.Z && e.isShiftPressed)) -> { sh.redo(); true }
                e.isCtrlPressed && e.key == Key.Z -> { sh.undo(); true }
                e.isCtrlPressed && e.key == Key.A -> { sh.selectAll(); true }
                e.key == Key.DirectionLeft -> { sh.move(D3(-step, 0.0, 0.0)); true }
                e.key == Key.DirectionRight -> { sh.move(D3(step, 0.0, 0.0)); true }
                e.key == Key.DirectionUp -> { sh.move(D3(0.0, step, 0.0)); true }
                e.key == Key.DirectionDown -> { sh.move(D3(0.0, -step, 0.0)); true }
                e.key == Key.PageUp -> { sh.move(D3(0.0, 0.0, step)); true }
                e.key == Key.PageDown -> { sh.move(D3(0.0, 0.0, -step)); true }
                e.key == Key.H && !e.isCtrlPressed -> { sh.setHole(true); true }
                e.key == Key.S && !e.isCtrlPressed -> { sh.setHole(false); true }
                e.key == Key.Escape && s.selected.isNotEmpty() -> { sh.select(emptyList()); true }
                else -> false
            }
        }.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
    ) {
        Text(stringResource(R.string.shape_add), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PALETTE.forEach { k ->
                AssistChip(onClick = { val id = sh.add(k); if (k == Shape.Kind.SKETCH) sketching = id; runCatching { focus.requestFocus() } }, label = { Text(stringResource(ShapeController.kindName(k))) },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
            }
            AssistChip(onClick = { stl.launch(arrayOf("model/stl", "application/sla", "application/vnd.ms-pki.stl", "application/octet-stream", "*/*")) },
                label = { Text(stringResource(R.string.shape_import)) }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
        }
        Toolbar(s, vm)
        HorizontalDivider()
        if (d.shapes.isEmpty()) {
            Text(stringResource(R.string.shape_empty), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        val filaments = vm.state.collectAsState().value.filaments
        d.shapes.forEach { ShapeRow(it, 0, s, vm, filaments) { runCatching { focus.requestFocus() } } }
        val one = s.selected.singleOrNull()?.let { d.find(it) }
        if (one != null) {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Properties(one, d, vm, filaments) { sketching = one.id }
        } else if (s.selected.size > 1) {
            Text(stringResource(R.string.shape_multi, s.selected.size), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Dims(d, vm)
    }
}

@Composable
private fun Toolbar(s: ScadEditorState, vm: AppViewModel) {
    val sh = vm.shapes
    val d = s.shapes ?: return
    val any = s.selected.isNotEmpty()
    val tops = s.selected.count { id -> d.shapes.any { it.id == id } }
    var mirror by remember { mutableStateOf(false) }
    var align by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = sh::undo, enabled = sh.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.shape_undo)) }
        IconButton(onClick = sh::redo, enabled = sh.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.shape_redo)) }
        IconButton(onClick = sh::duplicate, enabled = tops > 0) { Icon(Icons.Default.ContentCopy, stringResource(R.string.shape_duplicate)) }
        IconButton(onClick = sh::delete, enabled = any) { Icon(Icons.Default.Delete, stringResource(R.string.shape_delete)) }
        IconButton(onClick = sh::group, enabled = tops > 1) { Icon(Icons.Default.Workspaces, stringResource(R.string.shape_group_action)) }
        IconButton(onClick = sh::ungroup, enabled = s.selected.any { id -> d.shapes.any { it.id == id && it.isGroup } }) {
            Icon(Icons.Default.CallSplit, stringResource(R.string.shape_ungroup))
        }
        IconButton(onClick = { sh.merge() }, enabled = tops > 1 || (tops == 1 && s.selected.any { id -> d.shapes.any { it.id == id && it.isGroup } })) {
            Icon(Icons.Default.Merge, stringResource(R.string.shape_merge))
        }
        IconButton(onClick = { mirror = true }, enabled = any) {
            Icon(Icons.Default.Flip, stringResource(R.string.shape_mirror))
            DropdownMenu(mirror, { mirror = false }) {
                listOf("X", "Y", "Z").forEachIndexed { i, a ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.shape_mirror_axis, a)) }, onClick = { mirror = false; sh.mirror(i) })
                }
            }
        }
        IconButton(onClick = { align = true }, enabled = tops > 1) {
            Icon(Icons.Default.AlignHorizontalCenter, stringResource(R.string.shape_align))
            DropdownMenu(align, { align = false }) {
                val names = listOf(R.string.shape_align_min, R.string.shape_align_center, R.string.shape_align_max)
                listOf("X", "Y", "Z").forEachIndexed { axis, a ->
                    listOf(-1, 0, 1).forEach { mode ->
                        DropdownMenuItem(text = { Text("$a: " + stringResource(names[mode + 1])) }, onClick = { align = false; sh.align(axis, mode) })
                    }
                }
            }
        }
        IconButton(onClick = sh::drop, enabled = tops > 0) { Icon(Icons.Default.VerticalAlignBottom, stringResource(R.string.shape_drop)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShapeRow(shape: Shape, depth: Int, s: ScadEditorState, vm: AppViewModel, filaments: List<FilamentSlot>, onClicked: () -> Unit) {
    val selected = shape.id in s.selected
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) scheme.secondaryContainer else scheme.surface)
            .combinedClickable(onLongClick = { vm.shapes.tap(shape.id, add = true); onClicked() }) {
                vm.shapes.tap(shape.id, add = false); onClicked()
            }
            .padding(start = (16 + depth * 20).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A solid with its own filament shows that filament's colour.
        val own = filaments.getOrNull(shape.filament - 1)?.takeIf { !shape.hole && depth == 0 }
        if (own != null) ColorDot(own.color, 12)
        else Icon(if (shape.hole) Icons.Outlined.Circle else Icons.Default.Circle, null, Modifier.size(12.dp),
            tint = if (shape.hole) scheme.onSurfaceVariant else scheme.primary)
        Text(shape.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp))
        val tags = listOfNotNull(
            stringResource(R.string.shape_hole).takeIf { shape.hole },
            shape.repeat?.takeIf { it.count > 1 }?.let { "×${it.count}" },
        )
        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
    if (shape.isGroup) shape.children.forEach { ShapeRow(it, depth + 1, s, vm, filaments, onClicked) }
}

// --- Properties of the selected shape ------------------------------------------------------------

@Composable
private fun Properties(shape: Shape, d: ShapeDesign, vm: AppViewModel, filaments: List<FilamentSlot>, onEditSketch: () -> Unit) {
    val sh = vm.shapes
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var name by remember(shape.id) { mutableStateOf(shape.name) }
        OutlinedTextField(name, { name = it; if (it.isNotBlank()) sh.update(shape.id) { s -> s.copy(name = it) } },
            label = { Text(stringResource(R.string.shape_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!shape.hole, { sh.update(shape.id) { it.copy(hole = false) } }, SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.shape_solid))
            }
            SegmentedButton(shape.hole, { sh.update(shape.id) { it.copy(hole = true) } }, SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(R.string.shape_hole))
            }
        }
        // Each top-level solid is its own part, so it can print in its own filament (a group as one).
        if (!shape.hole && filaments.size > 1 && d.shapes.any { it.id == shape.id }) {
            PickerField(stringResource(R.string.filament), shape.filament.takeIf { it in 1..filaments.size }?.toString() ?: "0",
                listOf(PickerItem("0", stringResource(R.string.shape_filament_object))) +
                    filaments.mapIndexed { i, f -> PickerItem((i + 1).toString(), "${i + 1}: ${f.preset}", color = f.color.orEmpty()) },
                { v -> sh.update(shape.id) { it.copy(filament = v.toInt()) } })
        }
        if (shape.kind == Shape.Kind.TEXT) {
            var text by remember(shape.id) { mutableStateOf(shape.text) }
            OutlinedTextField(text, { text = it; sh.update(shape.id) { s -> s.copy(text = it) } },
                label = { Text(stringResource(R.string.shape_text_value)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (shape.kind == Shape.Kind.SKETCH) SketchSettings(shape, d, vm, onEditSketch)
        if (!shape.isGroup) {
            FieldRow(stringResource(R.string.shape_size), shape, d, vm, "size", listOf("W", "D", "H"), shape.size, listOf("width", "depth", "height"))
        } else {
            val b = d.bounds(shape.copy(pos = D3(), rot = D3(), repeat = null)).size
            Text(stringResource(R.string.scad_size, ShapeDesign.n(b.x), ShapeDesign.n(b.y), ShapeDesign.n(b.z)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FieldRow(stringResource(R.string.shape_position), shape, d, vm, "pos", listOf("X", "Y", "Z"), shape.pos, listOf("x", "y", "z"))
        FieldRow(stringResource(R.string.shape_rotation), shape, d, vm, "rot", listOf("X°", "Y°", "Z°"), shape.rot, listOf("turn_x", "turn_y", "turn_z"))
        when (shape.kind) {
            Shape.Kind.ROUNDED_BOX -> ExtraField(stringResource(R.string.shape_radius), shape, d, vm, Shape.RADIUS, 3.0)
            Shape.Kind.TUBE -> ExtraField(stringResource(R.string.shape_wall), shape, d, vm, Shape.WALL, 2.0)
            Shape.Kind.TORUS -> ExtraField(stringResource(R.string.shape_ring_thickness), shape, d, vm, Shape.TUBE, 8.0)
            Shape.Kind.CONE -> ExtraField(stringResource(R.string.shape_top), shape, d, vm, Shape.TOP, 0.0, slider = false)
            Shape.Kind.PRISM, Shape.Kind.CYLINDER -> ExtraField(stringResource(R.string.shape_sides), shape, d, vm, Shape.SIDES,
                if (shape.kind == Shape.Kind.PRISM) 6.0 else 0.0, slider = false, decimals = 0)
            else -> {}
        }
        if (shape.kind == Shape.Kind.MESH) Text(stringResource(R.string.shape_mesh_file, shape.file.substringAfterLast('/')),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val revolve = shape.x(Shape.REVOLVE, 0.0) != 0.0
        if (shape.kind in Shape.EDGED) EdgeEditor(shape, d, vm)
        if (shape.kind in Shape.SHELLED && !revolve) ShellEditor(shape, d, vm)
        RepeatEditor(shape, vm)
    }
}

/** Rounded or chamfered edges (a sketch: its outline's corners). */
@Composable
private fun EdgeEditor(shape: Shape, d: ShapeDesign, vm: AppViewModel) {
    val chamfer = shape.x(Shape.CHAMFER, 0.0) != 0.0
    Text(stringResource(if (shape.kind == Shape.Kind.SKETCH) R.string.shape_corners else R.string.shape_edges), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(!chamfer, { vm.shapes.update(shape.id) { it.copy(extra = it.extra + (Shape.CHAMFER to 0.0)) } }, SegmentedButtonDefaults.itemShape(0, 2)) {
            Text(stringResource(R.string.shape_edge_round))
        }
        SegmentedButton(chamfer, { vm.shapes.update(shape.id) { it.copy(extra = it.extra + (Shape.CHAMFER to 1.0)) } }, SegmentedButtonDefaults.itemShape(1, 2)) {
            Text(stringResource(R.string.shape_edge_chamfer))
        }
    }
    ExtraField(stringResource(R.string.shape_edge_size), shape, d, vm, Shape.EDGE, 0.0)
}

/** Hollow: a wall thickness, top open or closed. */
@Composable
private fun ShellEditor(shape: Shape, d: ShapeDesign, vm: AppViewModel) {
    Text(stringResource(R.string.shape_hollow), style = MaterialTheme.typography.labelLarge)
    ExtraField(stringResource(R.string.shape_shell_wall), shape, d, vm, Shape.SHELL, 0.0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.shape_open_top), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(shape.x(Shape.OPEN, 0.0) != 0.0, { on -> vm.shapes.update(shape.id) { it.copy(extra = it.extra + (Shape.OPEN to if (on) 1.0 else 0.0)) } })
    }
}

/** A sketch shape: open the drawing, extrude or revolve it. */
@Composable
private fun SketchSettings(shape: Shape, d: ShapeDesign, vm: AppViewModel, onEditSketch: () -> Unit) {
    val revolve = shape.x(Shape.REVOLVE, 0.0) != 0.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onEditSketch) {
            Icon(Icons.Default.Draw, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sketch_edit))
        }
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            SegmentedButton(!revolve, { vm.shapes.setRevolve(shape.id, false) }, SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.sketch_extrude))
            }
            SegmentedButton(revolve, { vm.shapes.setRevolve(shape.id, true) }, SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(R.string.sketch_revolve))
            }
        }
    }
    if (revolve) ExtraField(stringResource(R.string.sketch_angle), shape, d, vm, Shape.ANGLE, 360.0)
    else ExtraField(stringResource(R.string.sketch_top_scale), shape, d, vm, Shape.TOP, 1.0, slider = false)
}

/** Three numbers (size / position / rotation); the menu turns one into a slider or links it to one. */
@Composable
private fun FieldRow(title: String, shape: Shape, d: ShapeDesign, vm: AppViewModel, prefix: String, labels: List<String>, raw: D3,
                     names: List<String>) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { i, label ->
                val field = "$prefix.${"xyz"[i]}"
                val bound = shape.bind[field]?.takeIf { d.dim(it) != null }
                NumberField(d.value(shape, field, raw[i]).toFloat(), { vm.shapes.setField(shape.id, field, it.toDouble()) },
                    Modifier.weight(1f), label = if (bound != null) "$label = $bound" else label, decimals = 2)
            }
            Column {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.shape_slider_menu))
                }
                SliderMenu(menu, { menu = false }, shape, d, vm, labels.mapIndexed { i, l -> Triple("$prefix.${"xyz"[i]}", "$title $l", names[i]) })
            }
        }
    }
}

@Composable
private fun ExtraField(title: String, shape: Shape, d: ShapeDesign, vm: AppViewModel, key: String, def: Double,
                       slider: Boolean = true, decimals: Int = 2) {
    var menu by remember { mutableStateOf(false) }
    val bound = shape.bind[key]?.takeIf { d.dim(it) != null }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberField(d.value(shape, key, shape.x(key, def)).toFloat(), { vm.shapes.setField(shape.id, key, it.toDouble()) },
            Modifier.weight(1f), label = if (bound != null) "$title = $bound" else title, decimals = decimals)
        if (slider) Column {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.shape_slider_menu))
            }
            SliderMenu(menu, { menu = false }, shape, d, vm, listOf(Triple(key, title, key)))
        }
    }
}

/** Per field: make a new slider, link to an existing one, or unlink. */
@Composable
private fun SliderMenu(open: Boolean, onDismiss: () -> Unit, shape: Shape, d: ShapeDesign, vm: AppViewModel, fields: List<Triple<String, String, String>>) {
    DropdownMenu(open, onDismiss) {
        fields.forEach { (field, label, word) ->
            val bound = shape.bind[field]
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (bound != null) DropdownMenuItem(text = { Text(stringResource(R.string.shape_unlink, bound)) },
                onClick = { onDismiss(); vm.shapes.bind(shape.id, field, null) })
            else {
                DropdownMenuItem(text = { Text(stringResource(R.string.shape_make_slider)) }, leadingIcon = { Icon(Icons.Default.Add, null) },
                    onClick = { onDismiss(); vm.shapes.makeSlider(shape.id, field, "${shape.name} $word") })
                d.dims.forEach { dim ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.shape_link, dim.name)) }, leadingIcon = { Icon(Icons.Default.Link, null) },
                        onClick = { onDismiss(); vm.shapes.bind(shape.id, field, dim.name) })
                }
            }
        }
    }
}

@Composable
private fun RepeatEditor(shape: Shape, vm: AppViewModel) {
    val r = shape.repeat
    val set = { rep: Repeat? -> vm.shapes.setRepeat(shape.id, rep) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.shape_repeat), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(r != null, { on -> set(if (on) Repeat(step = D3(shape.size.x + 5, 0.0, 0.0)) else null) })
    }
    if (r == null) return
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(!r.circular, { set(r.copy(circular = false)) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.shape_repeat_row)) }
        SegmentedButton(r.circular, { set(r.copy(circular = true)) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.shape_repeat_circle)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumberField(r.count.toFloat(), { set(r.copy(count = it.toInt().coerceIn(1, 500))) }, Modifier.weight(1f),
            label = stringResource(R.string.shape_repeat_count), decimals = 0)
        if (r.circular) NumberField(r.angle.toFloat(), { set(r.copy(angle = it.toDouble())) }, Modifier.weight(1f),
            label = stringResource(R.string.shape_repeat_angle), decimals = 1)
    }
    if (r.circular) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumberField(r.center.x.toFloat(), { set(r.copy(center = r.center.copy(x = it.toDouble()))) }, Modifier.weight(1f),
            label = stringResource(R.string.shape_repeat_center) + " X", decimals = 2)
        NumberField(r.center.y.toFloat(), { set(r.copy(center = r.center.copy(y = it.toDouble()))) }, Modifier.weight(1f),
            label = stringResource(R.string.shape_repeat_center) + " Y", decimals = 2)
    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("X", "Y", "Z").forEachIndexed { i, a ->
            NumberField(r.step[i].toFloat(), { set(r.copy(step = r.step.with(i, it.toDouble()))) }, Modifier.weight(1f),
                label = stringResource(R.string.shape_repeat_step) + " " + a, decimals = 2)
        }
    }
}

// --- Dimensions --------------------------------------------------------------------------------

/** The sliders: named numbers the shapes' fields can follow; they show up in the Customizer too. */
@Composable
private fun Dims(d: ShapeDesign, vm: AppViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.shape_dims), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (d.dims.isEmpty()) Text(stringResource(R.string.shape_dims_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        d.dims.forEach { dim -> DimRow(dim, d, vm) }
    }
}

@Composable
private fun DimRow(dim: Dim, d: ShapeDesign, vm: AppViewModel) {
    val users = d.shapes.flatMap { it.all().toList() }.count { s -> s.bind.containsValue(dim.name) || s.sketch?.cons?.any { it.dim == dim.name } == true }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dim.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(pluralUses(users), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.shapes.removeDim(dim.name) }) { Text(stringResource(R.string.shape_dim_remove)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(dim.value.toFloat(), { vm.shapes.setDim(dim.copy(value = it.toDouble(), min = minOf(dim.min, it.toDouble()), max = maxOf(dim.max, it.toDouble()))) },
                    Modifier.weight(1f), label = stringResource(R.string.scad_setting_value))
                NumberField(dim.min.toFloat(), { vm.shapes.setDim(dim.copy(min = it.toDouble())) }, Modifier.weight(1f), label = stringResource(R.string.scad_setting_min))
                NumberField(dim.max.toFloat(), { vm.shapes.setDim(dim.copy(max = it.toDouble())) }, Modifier.weight(1f), label = stringResource(R.string.scad_setting_max))
                NumberField(dim.step.toFloat(), { if (it > 0) vm.shapes.setDim(dim.copy(step = it.toDouble())) }, Modifier.weight(1f), label = stringResource(R.string.scad_setting_step))
            }
        }
    }
}

@Composable
private fun pluralUses(n: Int) = stringResource(R.string.shape_dim_uses, n)
