package app.orcaandroid.ui.prepare

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.LayerGcode
import app.orcaandroid.core.Vec3
import app.orcaandroid.core.VolumeType
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.NumberField
import app.orcaandroid.ui.components.PickerField
import app.orcaandroid.ui.components.PickerItem
import app.orcaandroid.ui.components.Vec3Fields
import java.util.Locale

/** The "+" menu: import, samples, primitives, text, SVG, calibration. */
@Composable
internal fun AddMenu(state: UiState, vm: AppViewModel, open: Boolean, onDismiss: () -> Unit, onDialog: (String) -> Unit) {
    val importModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.files.openModels(it) }
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.import_model)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) },
            onClick = { onDismiss(); importModels.launch(arrayOf("*/*")) })
        DropdownMenuItem(text = { Text(stringResource(R.string.add_shape)) }, leadingIcon = { Icon(Icons.Default.Interests, null) },
            onClick = { onDismiss(); onDialog("primitive") })
        DropdownMenuItem(text = { Text(stringResource(R.string.add_text)) }, leadingIcon = { Icon(Icons.Default.TextFields, null) },
            onClick = { onDismiss(); onDialog("text") })
        DropdownMenuItem(text = { Text(stringResource(R.string.add_svg)) }, leadingIcon = { Icon(Icons.Default.Category, null) },
            onClick = { onDismiss(); onDialog("svg") })
        DropdownMenuItem(text = { Text(stringResource(R.string.add_openscad)) }, leadingIcon = { Icon(Icons.Default.Code, null) },
            onClick = { onDismiss(); vm.scad.open() })
        DropdownMenuItem(text = { Text(stringResource(R.string.shape_builder)) }, leadingIcon = { Icon(Icons.Default.Architecture, null) },
            onClick = { onDismiss(); vm.scad.newShapes() })
        DropdownMenuItem(text = { Text(stringResource(R.string.sample_models)) }, leadingIcon = { Icon(Icons.Default.Toys, null) },
            onClick = { onDismiss(); onDialog("samples") })
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.calibration)) }, leadingIcon = { Icon(Icons.Default.Science, null) },
            onClick = { onDismiss(); onDialog("calib") }, enabled = state.calibration == null)
    }
}

/**
 * The flushing volumes matrix like the desktop's dialog: purge volume (mm³) for each change from
 * the row's filament to the column's, editable, with auto-calculation from the filament colours.
 */
@Composable
internal fun FlushDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    val n = state.filaments.size
    val stored = state.value(PresetType.PRINT, "flush_volumes_matrix")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
    var matrix by remember(stored) { mutableStateOf(if (stored.size == n * n) stored else List(n * n) { 0f }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flushing_volumes)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.flush_matrix_text), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(40.dp))
                    state.filaments.forEach { f -> Box(Modifier.width(84.dp), contentAlignment = Alignment.Center) { ColorDot(f.color, 18) } }
                }
                for (from in 0 until n) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { ColorDot(state.filaments[from].color, 18) }
                        for (to in 0 until n) {
                            if (from == to) Box(Modifier.width(84.dp), contentAlignment = Alignment.Center) { Text("—") }
                            else NumberField(matrix[from * n + to], { v ->
                                matrix = matrix.toMutableList().also { it[from * n + to] = v.coerceAtLeast(0f) }
                            }, Modifier.width(84.dp), decimals = 0, live = true)
                        }
                    }
                }
                TextButton(onClick = { onDismiss(); vm.scene.autoFlushMatrix() }) { Text(stringResource(R.string.auto_calculate)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                vm.presets.setOption(PresetType.PRINT, "flush_volumes_matrix", matrix.joinToString(",") { it.toInt().toString() })
            }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun AddDialogs(state: UiState, vm: AppViewModel, dialog: String?, onDismiss: () -> Unit) {
    when (dialog) {
        "primitive" -> PrimitiveDialog(vm, onDismiss)
        "text" -> TextDialog(vm, onDismiss)
        "svg" -> SvgDialog(vm, onDismiss)
        "samples" -> SamplesDialog(vm, onDismiss)
        "calib" -> CalibrationDialog(vm, onDismiss)
        "simplify" -> SimplifyDialog(state, vm, onDismiss)
        "deform" -> DeformDialog(vm, onDismiss)
        "blend" -> BlendDialog(vm, onDismiss)
        "check" -> CheckMeshDialog(vm, onDismiss)
        "boolean" -> MeshBooleanDialog(state, vm, onDismiss)
    }
}

/** GLGizmoMeshBoolean as a dialog: two parts of the selected object, one operation. */
@Composable
private fun MeshBooleanDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    val obj = state.selectedObject ?: return onDismiss()
    val parts = obj.volumes.filter { it.type == VolumeType.PART }
    val ops = listOf("union" to R.string.boolean_union, "difference" to R.string.boolean_difference, "intersection" to R.string.boolean_intersection)
    var op by remember { mutableStateOf("difference") }
    var src by remember { mutableIntStateOf(parts.getOrNull(0)?.index ?: -1) }
    var tool by remember { mutableIntStateOf(parts.getOrNull(1)?.index ?: -1) }
    var keepTool by remember { mutableStateOf(false) }
    val items = parts.map { PickerItem(it.index.toString(), it.name) }
    DialogFrame(stringResource(R.string.mesh_boolean), stringResource(ops.first { it.first == op }.second),
        enabled = src >= 0 && tool >= 0 && src != tool,
        onConfirm = { vm.scene.meshBoolean(obj.index, src, tool, op, keepTool && op != "union") }, onDismiss = onDismiss) {
        if (parts.size < 2) {
            Text(stringResource(R.string.boolean_needs_parts), style = MaterialTheme.typography.bodyMedium)
            return@DialogFrame
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ops.forEach { (id, label) -> FilterChip(selected = op == id, onClick = { op = id }, label = { Text(stringResource(label)) }) }
        }
        PickerField(stringResource(if (op == "difference") R.string.boolean_subtract_from else R.string.boolean_part1), src.toString(), items,
            { src = it.toInt() }, Modifier.fillMaxWidth())
        PickerField(stringResource(if (op == "difference") R.string.boolean_subtract_with else R.string.boolean_part2), tool.toString(), items,
            { tool = it.toInt() }, Modifier.fillMaxWidth())
        if (op != "union") Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.boolean_keep_tool), Modifier.weight(1f))
            Switch(keepTool, { keepTool = it })
        }
    }
}

@Composable
private fun DialogFrame(title: String, confirm: String, enabled: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }, enabled = enabled) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A size a mesh can be built from: above zero and not absurdly large (a 99999 mm cube hangs the slicer). */
private fun sizeOk(vararg mm: Float) = mm.all { it in 0.1f..1000f }

@Composable
private fun PrimitiveDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val shapes = listOf("cube" to R.string.shape_cube, "cylinder" to R.string.shape_cylinder, "sphere" to R.string.shape_sphere, "cone" to R.string.shape_cone)
    var shape by remember { mutableStateOf("cube") }
    var size by remember { mutableStateOf(Vec3(20f, 20f, 20f)) }
    var round by remember { mutableStateOf(0f) }
    DialogFrame(stringResource(R.string.add_shape), stringResource(R.string.add), enabled = sizeOk(size.x, size.y, size.z),
        onConfirm = { vm.scene.addPrimitive(shape, size, round) }, onDismiss = onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shapes.forEach { (id, label) -> FilterChip(selected = shape == id, onClick = { shape = id }, label = { Text(stringResource(label)) }) }
        }
        Vec3Fields(stringResource(R.string.size), size.x, size.y, size.z, "mm", { x, y, z -> size = Vec3(x, y, z) }, live = true)
        NumberField(round, { round = it }, Modifier.fillMaxWidth(), stringResource(R.string.round_edges), "mm", live = true)
    }
}

@Composable
private fun TextDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("Orca") }
    var height by remember { mutableFloatStateOf(10f) }
    var depth by remember { mutableFloatStateOf(3f) }
    DialogFrame(stringResource(R.string.add_text), stringResource(R.string.add), enabled = text.isNotBlank() && sizeOk(height, depth),
        onConfirm = { vm.scene.addText(text, height, depth) }, onDismiss = onDismiss) {
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.text)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(height, { height = it }, Modifier.weight(1f), stringResource(R.string.text_height), "mm", live = true)
            NumberField(depth, { depth = it }, Modifier.weight(1f), stringResource(R.string.depth), "mm", live = true)
        }
    }
}

@Composable
private fun SvgDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    var width by remember { mutableFloatStateOf(50f) }
    var depth by remember { mutableFloatStateOf(2f) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) uri = it else if (uri == null) onDismiss() }
    androidx.compose.runtime.LaunchedEffect(Unit) { pick.launch(arrayOf("image/svg+xml", "*/*")) }
    val u = uri ?: return
    DialogFrame(stringResource(R.string.add_svg), stringResource(R.string.add), enabled = sizeOk(width, depth), onConfirm = { vm.scene.addSvg(u, width, depth) }, onDismiss = onDismiss) {
        Text(u.lastPathSegment.orEmpty(), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(width, { width = it }, Modifier.weight(1f), stringResource(R.string.width), "mm", live = true)
            NumberField(depth, { depth = it }, Modifier.weight(1f), stringResource(R.string.depth), "mm", live = true)
        }
    }
}

@Composable
private fun SamplesDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val files = remember { vm.scene.sampleModels() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sample_models)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(files) { _, f ->
                    Text(f.nameWithoutExtension.replace('_', ' '), Modifier.fillMaxWidth().clickable { onDismiss(); vm.scene.addSample(f) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SimplifyDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    var ratio by remember { mutableFloatStateOf(0.5f) }
    val tris = state.selectedObject?.triangles ?: 0
    DialogFrame(stringResource(R.string.simplify), stringResource(R.string.apply), onConfirm = { vm.scene.simplify(ratio) }, onDismiss = onDismiss) {
        Text(stringResource(R.string.simplify_result, tris, (tris * ratio).toInt()))
        Slider(ratio, { ratio = it }, valueRange = 0.05f..0.95f)
    }
}

/** Bend, twist or taper the selected object along one plate axis (the mesh is refined first, so it also works on STLs). */
@Composable
private fun DeformDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val kinds = listOf("bend" to R.string.deform_bend, "twist" to R.string.deform_twist, "taper" to R.string.deform_taper)
    var kind by remember { mutableStateOf("bend") }
    var axis by remember { mutableIntStateOf(2) }
    // Per kind: range and start value; bend and twist in degrees, taper as the far end's scale.
    val range = when (kind) { "bend" -> -180f..180f; "twist" -> -360f..360f; else -> 0.1f..3f }
    var amount by remember(kind) { mutableFloatStateOf(when (kind) { "bend" -> 45f; "twist" -> 90f; else -> 0.5f }) }
    val axes = listOf("X", "Y", "Z")
    DialogFrame(stringResource(R.string.deform), stringResource(R.string.apply), onConfirm = { vm.scene.deform(kind, axis, amount) }, onDismiss = onDismiss) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            kinds.forEach { (id, label) -> FilterChip(selected = kind == id, onClick = { kind = id }, label = { Text(stringResource(label)) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.deform_axis))
            axes.forEachIndexed { i, a -> FilterChip(selected = axis == i, onClick = { axis = i }, label = { Text(a) }) }
        }
        Text(if (kind == "taper") stringResource(R.string.deform_taper_value, amount) else stringResource(R.string.deform_degrees, amount.roundToInt()))
        Slider(amount, { amount = if (kind == "taper") (it * 20).roundToInt() / 20f else it.roundToInt().toFloat() }, valueRange = range)
        Text(stringResource(when (kind) {
            // The bend side follows the native table: along X or Y it bends up (+Z), along Z toward +X.
            "bend" -> if (axis == 2) R.string.deform_bend_hint_x else R.string.deform_bend_hint_z
            "twist" -> R.string.deform_twist_hint
            else -> R.string.deform_taper_hint
        }), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.deform_note), style = MaterialTheme.typography.bodySmall)
    }
}

/** Calibration tests, ported from the desktop's Calibration menu. */
/** App names of the calibration tests, by the engine's test type. */
val CALIBRATION_LABELS = mapOf(
    "temp" to R.string.calib_temp,
    "flow" to R.string.calib_flow,
    "pa_line" to R.string.calib_pa_line,
    "pa_tower" to R.string.calib_pa_tower,
    "retraction" to R.string.calib_retraction,
    "max_volumetric" to R.string.calib_max_volumetric,
    "vfa" to R.string.calib_vfa,
    "input_shaping_freq" to R.string.calib_is_freq,
    "input_shaping_damp" to R.string.calib_is_damp,
    "cornering" to R.string.calib_cornering,
)

@Composable
fun CalibrationDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    data class Test(val id: String, val label: Int, val start: Float, val end: Float, val step: Float, val unit: String)
    val tests = listOf(
        Test("temp", CALIBRATION_LABELS.getValue("temp"), 230f, 190f, 5f, "°C"),
        Test("flow", CALIBRATION_LABELS.getValue("flow"), 0f, 0f, 0f, ""),
        Test("pa_line", CALIBRATION_LABELS.getValue("pa_line"), 0f, 0.1f, 0.002f, ""),
        Test("pa_tower", CALIBRATION_LABELS.getValue("pa_tower"), 0f, 0.1f, 0.002f, ""),
        Test("retraction", CALIBRATION_LABELS.getValue("retraction"), 0f, 2f, 0.1f, "mm"),
        Test("max_volumetric", CALIBRATION_LABELS.getValue("max_volumetric"), 5f, 20f, 0.5f, "mm³/s"),
        Test("vfa", CALIBRATION_LABELS.getValue("vfa"), 40f, 200f, 10f, "mm/s"),
        Test("input_shaping_freq", CALIBRATION_LABELS.getValue("input_shaping_freq"), 15f, 110f, 0f, "Hz"),
        Test("input_shaping_damp", CALIBRATION_LABELS.getValue("input_shaping_damp"), 0f, 0.4f, 0f, ""),
        Test("cornering", CALIBRATION_LABELS.getValue("cornering"), 1f, 15f, 0f, "mm/s"),
    )
    var test by remember { mutableStateOf(tests.first()) }
    var start by remember(test) { mutableFloatStateOf(test.start) }
    var end by remember(test) { mutableFloatStateOf(test.end) }
    var step by remember(test) { mutableFloatStateOf(test.step) }
    var pass by remember { mutableIntStateOf(1) }
    var linear by remember { mutableStateOf(false) }
    var model by remember { mutableIntStateOf(0) }
    var freqY by remember(test) { mutableStateOf(test.start to test.end) }
    // Input shaping, as in the desktop dialogs: the frequency test sweeps the frequency at a fixed
    // damping, the damping test sweeps the damping at a fixed frequency (per axis).
    var damping by remember { mutableFloatStateOf(0.15f) }
    var fixedFreq by remember { mutableStateOf(30f to 30f) }

    DialogFrame(stringResource(R.string.calibration), stringResource(R.string.start), onConfirm = {
        val params = mutableMapOf<String, Any?>("start" to start, "end" to end, "step" to step)
        when (test.id) {
            "flow" -> { params["pass"] = pass; params["linear"] = linear }
            "input_shaping_freq" -> {
                params["model"] = model
                params["start"] = damping; params["end"] = damping
                params["freq_start_x"] = start; params["freq_end_x"] = end
                params["freq_start_y"] = freqY.first; params["freq_end_y"] = freqY.second
            }
            "input_shaping_damp" -> {
                params["model"] = model
                params["freq_start_x"] = fixedFreq.first; params["freq_end_x"] = fixedFreq.first
                params["freq_start_y"] = fixedFreq.second; params["freq_end_y"] = fixedFreq.second
            }
            "cornering" -> params["model"] = model
        }
        vm.scene.startCalibration(test.id, params)
    }, onDismiss = onDismiss) {
        PickerField(stringResource(R.string.test), test.id, tests.map { PickerItem(it.id, stringResource(it.label)) },
            onSelect = { id -> test = tests.first { it.id == id } })
        when (test.id) {
            "flow" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(pass == 1, { pass = 1 }, label = { Text(stringResource(R.string.pass_n, 1)) })
                    FilterChip(pass == 2, { pass = 2 }, label = { Text(stringResource(R.string.pass_n, 2)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.flow_linear), Modifier.weight(1f))
                    Switch(linear, { linear = it })
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField(start, { start = it }, Modifier.weight(1f), stringResource(R.string.from), test.unit, 3, live = true)
                    NumberField(end, { end = it }, Modifier.weight(1f), stringResource(R.string.to), test.unit, 3, live = true)
                    if (test.step > 0f) NumberField(step, { step = it }, Modifier.weight(1f), stringResource(R.string.step), test.unit, 3, live = true)
                }
                if (test.id == "input_shaping_freq") {
                    Text(stringResource(R.string.y_axis), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField(freqY.first, { freqY = it to freqY.second }, Modifier.weight(1f), stringResource(R.string.from), "Hz", live = true)
                        NumberField(freqY.second, { freqY = freqY.first to it }, Modifier.weight(1f), stringResource(R.string.to), "Hz", live = true)
                    }
                    NumberField(damping, { damping = it.coerceIn(0f, 0.99f) }, Modifier.fillMaxWidth(), stringResource(R.string.damping), null, 3, live = true)
                }
                if (test.id == "input_shaping_damp") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField(fixedFreq.first, { fixedFreq = it to fixedFreq.second }, Modifier.weight(1f), stringResource(R.string.frequency_x), "Hz", live = true)
                        NumberField(fixedFreq.second, { fixedFreq = fixedFreq.first to it }, Modifier.weight(1f), stringResource(R.string.frequency_y), "Hz", live = true)
                    }
                }
                if (test.id.startsWith("input_shaping") || test.id == "cornering") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(model == 0, { model = 0 }, label = { Text(stringResource(R.string.model_ringing)) })
                        FilterChip(model == 1, { model = 1 }, label = { Text(stringResource(R.string.model_fast)) })
                    }
                }
            }
        }
        Text(stringResource(R.string.calibration_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** Pauses, filament changes and custom G-code at given heights of a plate. */
@Composable
internal fun LayerGcodeDialog(state: UiState, vm: AppViewModel, plate: Int, initialZ: Float? = null, onDismiss: () -> Unit) {
    val items = remember { mutableStateListOf<LayerGcode>().apply { addAll(state.scene.plates.getOrNull(plate)?.layerGcodes.orEmpty()) } }
    var adding by remember { mutableStateOf(initialZ != null) }
    var z by remember { mutableFloatStateOf(initialZ ?: 5f) }
    var type by remember { mutableStateOf("pause") }
    var extra by remember { mutableStateOf("") }
    var extruder by remember { mutableIntStateOf(2) }
    val types = listOf("pause" to R.string.gcode_pause, "color" to R.string.gcode_color, "custom" to R.string.gcode_custom)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layer_gcodes)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty() && !adding) Text(stringResource(R.string.no_layer_gcodes), style = MaterialTheme.typography.bodySmall)
                items.sortedBy { it.z }.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%.2f mm · %s".format(Locale.ROOT, g.z, stringResource(types.first { it.first == g.type }.second)) +
                            if (g.type == "color") " → ${g.extruder}" else if (g.extra.isNotBlank()) " · ${g.extra.take(20)}" else "",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { items.remove(g) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                }
                if (adding) {
                    HorizontalDivider()
                    NumberField(z, { z = it }, Modifier.fillMaxWidth(), stringResource(R.string.height), "mm", live = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        types.forEach { (id, label) -> FilterChip(type == id, { type = id }, label = { Text(stringResource(label)) }) }
                    }
                    when (type) {
                        // A colour change switches to another filament slot (runs the printer's filament change G-code).
                        "color" -> if (state.filaments.size < 2) {
                            Text(stringResource(R.string.color_change_needs_filament), style = MaterialTheme.typography.bodySmall)
                        } else {
                            PickerField(stringResource(R.string.filament), extruder.toString(),
                                state.filaments.mapIndexed { i, f -> PickerItem((i + 1).toString(), "${i + 1}: ${f.preset}", color = f.color.orEmpty()) }, { extruder = it.toInt() })
                        }
                        "custom" -> OutlinedTextField(extra, { extra = it }, label = { Text("G-code") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        else -> OutlinedTextField(extra, { extra = it }, label = { Text(stringResource(R.string.message)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = {
                        items.add(LayerGcode(z, type, extra, state.filaments.getOrNull(extruder - 1)?.color.orEmpty(), extruder))
                        adding = false; extra = ""
                    }, enabled = type != "color" || state.filaments.size >= 2) { Text(stringResource(R.string.add)) }
                } else {
                    TextButton(onClick = { adding = true }) { Text(stringResource(R.string.add_entry)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); vm.scene.setLayerGcodes(plate, items.toList()) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Mesh problems of the selected copy: open edges, zero-area faces, thin walls (the print3d_toolbox checks). */
@Composable
private fun CheckMeshDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var thickness by remember { mutableFloatStateOf(0.4f) }
    var report by remember { mutableStateOf<String?>(null) }
    var hasMarkers by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(thickness) {
        kotlinx.coroutines.delay(300)
        report = runCatching { vm.scene.meshReport(thickness) }.getOrElse { it.message ?: it.toString() } ?: ""
        hasMarkers = vm.scene.hasMeshMarkers
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_mesh)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.check_mesh_thin, "%.2f".format(thickness)))
                Slider(thickness, { thickness = Math.round(it * 20f) / 20f }, valueRange = 0.1f..2f)
                Text(report ?: stringResource(R.string.checking))
            }
        },
        confirmButton = {
            if (hasMarkers) TextButton(onClick = { vm.scene.showMeshMarkers(); onDismiss() }) { Text(stringResource(R.string.check_mesh_show)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = { if (hasMarkers) TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Fuses the selected object's parts into one mesh with smooth joins (OpenVDB closing, so it also works on STLs). */
@Composable
private fun BlendDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var radius by remember { mutableFloatStateOf(3f) }
    DialogFrame(stringResource(R.string.blend), stringResource(R.string.apply), onConfirm = { vm.scene.smoothBlend(radius) }, onDismiss = onDismiss) {
        Text(stringResource(R.string.blend_radius, radius.roundToInt()))
        Slider(radius, { radius = it.roundToInt().toFloat() }, valueRange = 1f..20f)
        Text(stringResource(R.string.blend_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.blend_note), style = MaterialTheme.typography.bodySmall)
    }
}
