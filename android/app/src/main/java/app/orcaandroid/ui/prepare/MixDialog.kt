package app.orcaandroid.ui.prepare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.ui.components.hexToRgb
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

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

/** Quick-pick combos of the mixable slots [ids] (list positions): each pair at 3:1, 1:1 and 1:3, each triple evenly, and all of them evenly. */
private fun quickPicks(ids: List<Int>): List<List<Pair<Int, Float>>> {
    val out = mutableListOf<List<Pair<Int, Float>>>()
    val ratios = if (ids.size > 5) listOf(1f to 1f) else listOf(3f to 1f, 1f to 1f, 1f to 3f)
    for (a in ids.indices) for (b in a + 1 until ids.size) ratios.forEach { (x, y) -> out += listOf(ids[a] to x, ids[b] to y) }
    if (ids.size in 3..5) for (a in ids.indices) for (b in a + 1 until ids.size) for (c in b + 1 until ids.size) out += listOf(ids[a], ids[b], ids[c]).map { it to 1f }
    if (ids.size >= 4) out += ids.map { it to 1f }
    return out.map { pick -> val sum = pick.sumOf { it.second.toDouble() }.toFloat(); pick.map { (i, w) -> i to w / sum } }
}

private fun swatch(hex: String?) = hexToRgb(hex ?: "#808080").let { Color(it[0], it[1], it[2]) }

private fun pct(share: Float) = (share * 100).roundToInt()

/** One quick pick: the parts as pie wedges around the blended colour. */
@Composable
private fun MixSwatch(colors: List<String?>, shares: List<Float>, selected: Boolean, label: String, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val center = swatch(Mix.blend(colors, shares))
    Canvas(Modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = label }.clickable(onClick = onClick)) {
        var a = -90f
        colors.forEachIndexed { i, c -> val sweep = 360f * shares[i]; drawArc(swatch(c), a, sweep, true); a += sweep }
        drawCircle(center, size.minDimension * 0.28f)
        drawCircle(outline, size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
        if (selected) drawCircle(ring, size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
    }
}

/**
 * Edits whether slot [index] is a mix of the other (non-mixed) slots, and in which parts; [index] == slots.size = a new mix slot.
 * Bambu Studio-like: a big live preview, quick-pick combos, a gradient balance bar for two parts and per-part sliders for more.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MixDialog(slots: List<FilamentSlot>, index: Int, sublayers: Boolean, onApply: (List<Pair<Int, Float>>?, Boolean) -> Unit, onDismiss: () -> Unit) {
    val candidates = slots.indices.filter { it != index && !Mix.isMixed(slots[it]) }
    val current = slots.getOrNull(index)
    // Weights per chosen slot (list position); only their proportions matter.
    val parts = remember { mutableStateMapOf<Int, Float>().apply { current?.let(Mix::parts)?.forEach { (c, r) -> put(c - 1, (r * 10).coerceIn(0.5f, 10f)) } } }
    var blend by remember { mutableStateOf(sublayers) }
    val chosen = candidates.filter { it in parts }
    val total = chosen.sumOf { parts.getValue(it).toDouble() }.toFloat()
    fun share(i: Int) = if (total > 0f) parts.getValue(i) / total else 0f
    val picks = remember(candidates) { quickPicks(candidates) }
    val outline = MaterialTheme.colorScheme.outline
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mix_title, index + 1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (candidates.size < 2) Text(stringResource(R.string.mix_need_two))
                if (chosen.size >= 2) {
                    val mixed = Mix.blend(chosen.map { slots[it].color }, chosen.map { parts.getValue(it) })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(10.dp)).background(swatch(mixed))
                            .border(1.dp, outline, RoundedCornerShape(10.dp)))
                        Column {
                            Text(mixed, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                            Text(chosen.joinToString("  ·  ") { "${it + 1}: ${pct(share(it))}%" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (picks.isNotEmpty()) {
                    Text(stringResource(R.string.mix_quick), style = MaterialTheme.typography.labelMedium)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        picks.forEach { pick ->
                            val on = pick.size == chosen.size && pick.all { (i, s) -> i in parts && abs(share(i) - s) < 0.02f }
                            MixSwatch(pick.map { slots[it.first].color }, pick.map { it.second }, on,
                                pick.joinToString(" + ") { (i, s) -> "${i + 1} ${pct(s)}%" }) {
                                parts.clear(); pick.forEach { (i, s) -> parts[i] = s * 10 }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.mix_pick), style = MaterialTheme.typography.labelMedium)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { i ->
                        val on = i in parts
                        Box(Modifier.size(44.dp).clip(CircleShape).clickable {
                            if (on) parts.remove(i) else parts[i] = if (chosen.isEmpty()) 5f else total / chosen.size
                        }.border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else outline, CircleShape).padding(5.dp)
                            .clip(CircleShape).background(swatch(slots[i].color)), contentAlignment = Alignment.Center) {
                            val rgb = hexToRgb(slots[i].color)
                            val ink = if (0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2] > 0.6f) Color.Black else Color.White
                            Text("${i + 1}", color = ink, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (chosen.size >= 3) {
                    // Shares always add up to 100 %: moving one part rescales the others in proportion, so no slider can "all go high".
                    fun setShare(i: Int, v: Float) {
                        val lo = 0.05f
                        val s = v.coerceIn(lo, 1f - lo * (chosen.size - 1))
                        val others = chosen.filter { it != i }
                        val ot = others.sumOf { share(it).toDouble() }.toFloat().coerceAtLeast(0.001f)
                        val rest = others.associateWith { (1f - s) * share(it) / ot }
                        parts[i] = s * 10
                        rest.forEach { (o, r) -> parts[o] = r * 10 }
                    }
                    Row(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).border(1.dp, outline, RoundedCornerShape(13.dp))) {
                        chosen.forEach { i -> Box(Modifier.weight(share(i).coerceAtLeast(0.01f)).fillMaxHeight().background(swatch(slots[i].color))) }
                    }
                    chosen.forEach { i ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorDot(slots[i].color)
                            Text("${i + 1}", Modifier.width(14.dp))
                            Slider(share(i), { setShare(i, it) }, Modifier.weight(1f), valueRange = 0.05f..0.9f)
                            Text("${pct(share(i))}%", Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TextButton(onClick = { chosen.forEach { parts[it] = 10f / chosen.size } }) { Text(stringResource(R.string.mix_even)) }
                }
                if (chosen.size == 2) {
                    // Balance bar: all of the first part at the left end, all of the second at the right; drawn in the blend's own colours.
                    val (a, b) = chosen
                    val ca = slots[a].color
                    val cb = slots[b].color
                    val strip = remember(ca, cb) { (0..23).map { swatch(Mix.blend(listOf(ca, cb), listOf(1f - it / 23f, it / 23f))) } }
                    fun bal(x: Float, w: Float) { val t = ((x / w) * 20).roundToInt().coerceIn(1, 19) / 20f; parts[a] = (1 - t) * 10; parts[b] = t * 10 }
                    val t = share(b)
                    val thumb = swatch(Mix.blend(listOf(ca, cb), listOf(1f - t, t)))
                    Canvas(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(16.dp))
                        .semantics { contentDescription = "${a + 1} ${pct(1f - t)}% + ${b + 1} ${pct(t)}%" }
                        .pointerInput(a, b) { detectTapGestures { bal(it.x, size.width.toFloat()) } }
                        .pointerInput(a, b) { detectDragGestures(onDragStart = { bal(it.x, size.width.toFloat()) }) { c, _ -> bal(c.position.x, size.width.toFloat()) } }) {
                        drawRect(Brush.horizontalGradient(strip))
                        val p = Offset(t * size.width, size.height / 2)
                        drawCircle(thumb, size.height / 2 - 3.dp.toPx(), p)
                        drawCircle(Color.Black, size.height / 2 - 2.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                        drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mix_sublayers), Modifier.weight(1f))
                    Switch(blend, { blend = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(chosen.map { (it + 1) to share(it) }, blend); onDismiss() }, enabled = chosen.size >= 2) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (current != null && Mix.isMixed(current)) TextButton(onClick = { onApply(null, blend); onDismiss() }) { Text(stringResource(R.string.mix_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

