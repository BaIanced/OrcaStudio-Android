package app.orcaandroid.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.OptionDef
import app.orcaandroid.core.PresetType
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState

/** How a doctor fix changes one option: set a fixed value, multiply the current number or add to it (result kept inside [min]..[max]). */
sealed interface DoctorOp {
    data class Set(val value: String) : DoctorOp
    data class Scale(val factor: Float, val min: Float = 0f, val max: Float = Float.MAX_VALUE) : DoctorOp
    data class Add(val delta: Float, val min: Float = 0f, val max: Float = Float.MAX_VALUE) : DoctorOp
}

data class DoctorFix(val type: PresetType, val key: String, val op: DoctorOp)

/** [calibration] = id of the built-in calibration test that measures the right value for this symptom, if there is one. */
/** A symptom with one number to dial in: [min]..[max] in [step]s, applied as that option's value. */
data class DoctorSlider(val type: PresetType, val key: String, val min: Float, val max: Float, val step: Float)

data class DoctorSymptom(val id: String, @StringRes val title: Int, @StringRes val text: Int, val fixes: List<DoctorFix>, val calibration: String? = null, val slider: DoctorSlider? = null)

/** What a fix would do to the current value: null when there is nothing to change (value already there, or a number could not be read). */
fun DoctorFix.newValue(current: String?): String? {
    val new = when (val o = op) {
        is DoctorOp.Set -> o.value
        is DoctorOp.Scale -> shift(current, o.factor >= 1f) { (it * o.factor).coerceIn(o.min, o.max) }
        is DoctorOp.Add -> shift(current, o.delta >= 0f) { (it + o.delta).coerceIn(o.min, o.max) }
    }
    return new?.takeIf { it != current?.trim() }
}

/** Applies [f] to the number in [current] (keeps a trailing %); null if [current] is not a plain number or the clamp would move it against the fix's direction ([up]). */
private fun shift(current: String?, up: Boolean, f: (Float) -> Float): String? {
    val raw = current?.trim() ?: return null
    val percent = raw.endsWith("%")
    val n = raw.removeSuffix("%").toFloatOrNull() ?: return null
    val r = f(n)
    if (if (up) r < n else r > n) return null
    return fmt(r) + if (percent) "%" else ""
}

private fun fmt(v: Float): String = "%.3f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')

private val TP = PresetType.PRINT
private val TF = PresetType.FILAMENT
private val TR = PresetType.PRINTER

/** Rule table: the symptom -> setting changes people usually try first, in order of how little they cost. Offline, no model. */
val DOCTOR_SYMPTOMS = listOf(
    DoctorSymptom("stringing", R.string.doc_stringing, R.string.doc_stringing_text, listOf(
        DoctorFix(TR, "retraction_length", DoctorOp.Add(0.4f, 0f, 3f)),
        DoctorFix(TF, "nozzle_temperature", DoctorOp.Add(-5f, 150f)),
        DoctorFix(TR, "wipe", DoctorOp.Set("1")),
        DoctorFix(TP, "travel_speed", DoctorOp.Scale(1.25f, 20f, 500f)),
    ), calibration = "retraction"),
    DoctorSymptom("warping", R.string.doc_warping, R.string.doc_warping_text, listOf(
        DoctorFix(TP, "brim_type", DoctorOp.Set("outer_only")),
        DoctorFix(TP, "brim_width", DoctorOp.Set("6")),
        DoctorFix(TF, "hot_plate_temp_initial_layer", DoctorOp.Add(5f, 0f, 120f)),
        DoctorFix(TF, "close_fan_the_first_x_layers", DoctorOp.Set("5")),
        DoctorFix(TP, "draft_shield", DoctorOp.Set("enabled")),
    )),
    DoctorSymptom("elephant_foot", R.string.doc_elephant_foot, R.string.doc_elephant_foot_text, listOf(
        DoctorFix(TP, "elefant_foot_compensation", DoctorOp.Add(0.1f, 0f, 1f)),
        DoctorFix(TF, "hot_plate_temp_initial_layer", DoctorOp.Add(-5f, 0f, 120f)),
    )),
    DoctorSymptom("small_holes", R.string.doc_small_holes, R.string.doc_small_holes_text, emptyList(), slider = DoctorSlider(TP, "xy_hole_compensation", 0f, 0.4f, 0.05f)),
    DoctorSymptom("layer_shift", R.string.doc_layer_shift, R.string.doc_layer_shift_text, listOf(
        DoctorFix(TP, "default_acceleration", DoctorOp.Scale(0.6f, 500f, 20000f)),
        DoctorFix(TP, "outer_wall_acceleration", DoctorOp.Scale(0.6f, 500f, 20000f)),
        DoctorFix(TP, "travel_speed", DoctorOp.Scale(0.8f, 20f, 500f)),
    )),
    DoctorSymptom("bridges", R.string.doc_bridges, R.string.doc_bridges_text, listOf(
        DoctorFix(TP, "bridge_flow", DoctorOp.Scale(0.9f, 0.5f, 1.2f)),
        DoctorFix(TP, "bridge_speed", DoctorOp.Scale(0.75f, 5f, 200f)),
        DoctorFix(TF, "nozzle_temperature", DoctorOp.Add(-5f, 150f)),
    )),
    DoctorSymptom("weak_layers", R.string.doc_weak_layers, R.string.doc_weak_layers_text, listOf(
        DoctorFix(TF, "nozzle_temperature", DoctorOp.Add(5f, 150f, 330f)),
        DoctorFix(TF, "fan_min_speed", DoctorOp.Add(-20f, 0f, 100f)),
        DoctorFix(TF, "fan_max_speed", DoctorOp.Add(-20f, 0f, 100f)),
    ), calibration = "temp"),
    DoctorSymptom("under_extrusion", R.string.doc_under_extrusion, R.string.doc_under_extrusion_text, listOf(
        DoctorFix(TF, "nozzle_temperature", DoctorOp.Add(5f, 150f, 330f)),
        DoctorFix(TF, "filament_flow_ratio", DoctorOp.Add(0.02f, 0.8f, 1.2f)),
        DoctorFix(TF, "filament_max_volumetric_speed", DoctorOp.Scale(0.8f, 1f, 50f)),
    ), calibration = "flow"),
    DoctorSymptom("over_extrusion", R.string.doc_over_extrusion, R.string.doc_over_extrusion_text, listOf(
        DoctorFix(TF, "filament_flow_ratio", DoctorOp.Add(-0.02f, 0.8f, 1.2f)),
        DoctorFix(TF, "nozzle_temperature", DoctorOp.Add(-5f, 150f)),
    ), calibration = "flow"),
    DoctorSymptom("ringing", R.string.doc_ringing, R.string.doc_ringing_text, listOf(
        DoctorFix(TP, "outer_wall_acceleration", DoctorOp.Scale(0.6f, 500f, 20000f)),
        DoctorFix(TP, "default_acceleration", DoctorOp.Scale(0.6f, 500f, 20000f)),
        DoctorFix(TP, "outer_wall_speed", DoctorOp.Scale(0.75f, 20f, 500f)),
    ), calibration = "input_shaping_freq"),
)

/** Print doctor: pick a symptom, see the setting changes on the current presets, apply the ticked ones as unsaved edits. */
@Composable
fun PrintDoctorDialog(state: UiState, vm: AppViewModel, onCalibration: (String) -> Unit, startId: String? = null, onDismiss: () -> Unit) {
    var symptom by remember { mutableStateOf(DOCTOR_SYMPTOMS.firstOrNull { it.id == startId }) }
    var defs by remember { mutableStateOf<Map<PresetType, Map<String, OptionDef>>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    val skipped = remember { mutableStateListOf<DoctorFix>() }
    val tr = vm.translator
    LaunchedEffect(Unit) {
        runCatching { vm.presets.refreshPresetValues(PresetType.entries) }
        defs = PresetType.entries.associateWith { t -> runCatching { vm.presets.optionDefs(t).associateBy { it.key } }.getOrDefault(emptyMap()) }
        loaded = true
    }
    val s = symptom
    if (s == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.print_doctor)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.print_doctor_text), style = MaterialTheme.typography.bodySmall)
                    DOCTOR_SYMPTOMS.forEach { d -> TextButton({ symptom = d }, Modifier.fillMaxWidth()) { Text(stringResource(d.title), Modifier.fillMaxWidth()) } }
                }
            },
            confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        )
        return
    }
    val sl = s.slider
    if (sl != null) {
        val cur = if (loaded) state.device.value(sl.type, sl.key)?.trim()?.toFloatOrNull() else null
        var pos by remember(loaded) { mutableStateOf((cur ?: 0f).coerceIn(sl.min, sl.max)) }
        val snapped = (Math.round(pos / sl.step) * sl.step).coerceIn(sl.min, sl.max)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(s.title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(s.text), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.doc_slider_value, fmt(snapped)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Slider(snapped, { pos = it }, Modifier.fillMaxWidth(), valueRange = sl.min..sl.max, steps = Math.round((sl.max - sl.min) / sl.step) - 1)
                }
            },
            confirmButton = {
                TextButton({ vm.presets.setOption(sl.type, sl.key, fmt(snapped)); onDismiss() }, enabled = loaded && fmt(snapped) != (cur?.let { fmt(it) })) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = { TextButton({ if (startId != null) onDismiss() else symptom = null }) { Text(stringResource(R.string.back)) } },
        )
        return
    }
    // Only fixes that would change something on the current presets.
    val changes = if (loaded) s.fixes.mapNotNull { f -> val cur = state.device.value(f.type, f.key); f.newValue(cur)?.let { Triple(f, cur, it) } } else emptyList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(s.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(s.text), style = MaterialTheme.typography.bodySmall)
                if (loaded && changes.isEmpty()) Text(stringResource(R.string.doctor_nothing_to_change))
                changes.forEach { (f, cur, new) ->
                    val def = defs[f.type]?.get(f.key)
                    val name = def?.let { tr.tr(it.label.ifEmpty { it.fullLabel.ifEmpty { f.key } }) } ?: f.key
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f !in skipped, { on -> if (on) skipped.remove(f) else skipped.add(f) })
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text("${cur ?: "–"}  →  $new", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                s.calibration?.let { c -> TextButton({ onCalibration(c) }) { Text(stringResource(R.string.doctor_open_calibration)) } }
            }
        },
        confirmButton = {
            val chosen = changes.filter { it.first !in skipped }
            TextButton({ chosen.forEach { (f, _, new) -> vm.presets.setOption(f.type, f.key, new) }; onDismiss() }, enabled = chosen.isNotEmpty()) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = { TextButton({ symptom = null; skipped.clear() }) { Text(stringResource(R.string.back)) } },
    )
}
