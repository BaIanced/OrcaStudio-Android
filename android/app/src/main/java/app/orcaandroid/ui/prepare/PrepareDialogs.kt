package app.orcaandroid.ui.prepare

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Science
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.LayerGcode
import app.orcaandroid.core.Vec3
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
        DropdownMenuItem(text = { Text(stringResource(R.string.sample_models)) }, leadingIcon = { Icon(Icons.Default.Toys, null) },
            onClick = { onDismiss(); onDialog("samples") })
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.calibration)) }, leadingIcon = { Icon(Icons.Default.Science, null) },
            onClick = { onDismiss(); onDialog("calib") }, enabled = state.calibration == null)
    }
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

@Composable
private fun PrimitiveDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val shapes = listOf("cube" to R.string.shape_cube, "cylinder" to R.string.shape_cylinder, "sphere" to R.string.shape_sphere, "cone" to R.string.shape_cone)
    var shape by remember { mutableStateOf("cube") }
    var size by remember { mutableStateOf(Vec3(20f, 20f, 20f)) }
    DialogFrame(stringResource(R.string.add_shape), stringResource(R.string.add), onConfirm = { vm.scene.addPrimitive(shape, size) }, onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shapes.forEach { (id, label) -> FilterChip(selected = shape == id, onClick = { shape = id }, label = { Text(stringResource(label)) }) }
        }
        Vec3Fields(stringResource(R.string.size), size.x, size.y, size.z, "mm", { x, y, z -> size = Vec3(x, y, z) })
    }
}

@Composable
private fun TextDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("Orca") }
    var height by remember { mutableFloatStateOf(10f) }
    var depth by remember { mutableFloatStateOf(3f) }
    DialogFrame(stringResource(R.string.add_text), stringResource(R.string.add), enabled = text.isNotBlank(),
        onConfirm = { vm.scene.addText(text, height, depth) }, onDismiss = onDismiss) {
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.text)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(height, { height = it }, Modifier.weight(1f), stringResource(R.string.text_height), "mm")
            NumberField(depth, { depth = it }, Modifier.weight(1f), stringResource(R.string.depth), "mm")
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
    DialogFrame(stringResource(R.string.add_svg), stringResource(R.string.add), onConfirm = { vm.scene.addSvg(u, width, depth) }, onDismiss = onDismiss) {
        Text(u.lastPathSegment.orEmpty(), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(width, { width = it }, Modifier.weight(1f), stringResource(R.string.width), "mm")
            NumberField(depth, { depth = it }, Modifier.weight(1f), stringResource(R.string.depth), "mm")
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
                    NumberField(start, { start = it }, Modifier.weight(1f), stringResource(R.string.from), test.unit, 3)
                    NumberField(end, { end = it }, Modifier.weight(1f), stringResource(R.string.to), test.unit, 3)
                    if (test.step > 0f) NumberField(step, { step = it }, Modifier.weight(1f), stringResource(R.string.step), test.unit, 3)
                }
                if (test.id == "input_shaping_freq") {
                    Text(stringResource(R.string.y_axis), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField(freqY.first, { freqY = it to freqY.second }, Modifier.weight(1f), stringResource(R.string.from), "Hz")
                        NumberField(freqY.second, { freqY = freqY.first to it }, Modifier.weight(1f), stringResource(R.string.to), "Hz")
                    }
                    NumberField(damping, { damping = it.coerceIn(0f, 0.99f) }, Modifier.fillMaxWidth(), stringResource(R.string.damping), null, 3)
                }
                if (test.id == "input_shaping_damp") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField(fixedFreq.first, { fixedFreq = it to fixedFreq.second }, Modifier.weight(1f), stringResource(R.string.frequency_x), "Hz")
                        NumberField(fixedFreq.second, { fixedFreq = fixedFreq.first to it }, Modifier.weight(1f), stringResource(R.string.frequency_y), "Hz")
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
                    NumberField(z, { z = it }, Modifier.fillMaxWidth(), stringResource(R.string.height), "mm")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        types.forEach { (id, label) -> FilterChip(type == id, { type = id }, label = { Text(stringResource(label)) }) }
                    }
                    when (type) {
                        // A colour change switches to another filament slot (runs the printer's filament change G-code).
                        "color" -> if (state.filaments.size < 2) {
                            Text(stringResource(R.string.color_change_needs_filament), style = MaterialTheme.typography.bodySmall)
                        } else {
                            PickerField(stringResource(R.string.filament), extruder.toString(),
                                state.filaments.mapIndexed { i, f -> PickerItem((i + 1).toString(), "${i + 1}: ${f.preset}") }, { extruder = it.toInt() })
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
