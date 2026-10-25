package app.orcaandroid.ui.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.FilamentSlot
import kotlin.math.pow

/** The per-slot options that make a filament slot a mix of other slots (upstream's mixed filament). */
object Mix {
    const val IS_MIXED = "filament_is_mixed"
    const val COMPONENTS = "filament_mixed_components"
    const val RATIOS = "filament_mixed_sublayer_ratios"
    val KEYS = setOf(IS_MIXED, COMPONENTS, RATIOS)

    fun isMixed(slot: FilamentSlot) = slot.overrides[IS_MIXED] == "1"

    /** [f]'s overrides, plus the mix recipe [same] (the saved slot holding the same preset) has when [f] has none (a 3MF carries none). */
    fun carry(f: FilamentSlot, same: FilamentSlot?): Map<String, String> =
        if (isMixed(f) || same == null) f.overrides else f.overrides + same.overrides.filterKeys { it in KEYS }

    /** (1-based component slot, share 0..1) pairs of a mixed slot. */
    fun parts(slot: FilamentSlot): List<Pair<Int, Float>> {
        if (!isMixed(slot)) return emptyList()
        val comps = slot.overrides[COMPONENTS].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        val ratios = slot.overrides[RATIOS].orEmpty().split(',').mapNotNull { it.trim().toFloatOrNull() }
        return comps.mapIndexed { i, c -> c to (ratios.getOrNull(i) ?: (1f / comps.size)) }
    }

    /** Blends [colors] by [weights] in linear light, like paint-free light mixing of the swatches. */
    fun blend(colors: List<String?>, weights: List<Float>): String {
        val total = weights.sum().takeIf { it > 0f } ?: return "#808080"
        val sum = FloatArray(3)
        colors.forEachIndexed { i, hex ->
            val v = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: 0x808080
            for (c in 0..2) {
                val s = ((v shr (16 - 8 * c)) and 0xFF) / 255f
                sum[c] += s.toDouble().pow(2.2).toFloat() * weights[i] / total
            }
        }
        val out = sum.map { (it.toDouble().pow(1 / 2.2) * 255).toInt().coerceIn(0, 255) }
        return "#%02X%02X%02X".format(out[0], out[1], out[2])
    }
}

/** Edits whether slot [index] is a mix of the other (non-mixed) slots, and in which parts. */
@Composable
internal fun MixDialog(slots: List<FilamentSlot>, index: Int, sublayers: Boolean, onApply: (List<Pair<Int, Float>>?, Boolean) -> Unit, onDismiss: () -> Unit) {
    val candidates = slots.indices.filter { it != index && !Mix.isMixed(slots[it]) }
    val parts = remember { mutableStateMapOf<Int, Int>().apply { Mix.parts(slots[index]).forEach { (c, r) -> put(c - 1, (r * 10).toInt().coerceIn(1, 10)) } } }
    var blend by remember { mutableStateOf(sublayers) }
    val chosen = candidates.filter { it in parts }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mix_title, index + 1)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (candidates.size < 2) Text(stringResource(R.string.mix_need_two))
                candidates.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Checkbox(i in parts, { on -> if (on) parts[i] = 5 else parts.remove(i) })
                        ColorDot(slots[i].color)
                        Text("${i + 1}", Modifier.padding(end = 4.dp))
                        Slider((parts[i] ?: 5).toFloat(), { parts[i] = it.toInt() }, Modifier.weight(1f), enabled = i in parts, valueRange = 1f..10f, steps = 8)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mix_sublayers), Modifier.weight(1f))
                    androidx.compose.material3.Switch(blend, { blend = it })
                }
                if (chosen.size >= 2) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorDot(Mix.blend(chosen.map { slots[it].color }, chosen.map { parts.getValue(it).toFloat() }), 26)
                    Text(chosen.joinToString(" : ") { parts.getValue(it).toString() })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val sum = chosen.sumOf { parts.getValue(it) }.toFloat()
                onApply(chosen.map { (it + 1) to parts.getValue(it) / sum }, blend); onDismiss()
            }, enabled = chosen.size >= 2) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (Mix.isMixed(slots[index])) TextButton(onClick = { onApply(null, blend); onDismiss() }) { Text(stringResource(R.string.mix_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
