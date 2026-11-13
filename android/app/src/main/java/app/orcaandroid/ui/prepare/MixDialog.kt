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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.core.Mix
import app.orcaandroid.ui.components.hexToRgb
import kotlin.math.abs
import kotlin.math.roundToInt

/** Quick-pick combos of the mixable slots [ids] (list positions): each pair at 3:1, 1:1 and 1:3, each triple evenly, and all of them evenly. */
private fun quickPicks(ids: List<Int>): List<List<Pair<Int, Float>>> {
    val out = mutableListOf<List<Pair<Int, Float>>>()
    val ratios = if (ids.size > 5) listOf(1f to 1f) else listOf(3f to 1f, 1f to 1f, 1f to 3f)
    for (a in ids.indices) for (b in a + 1 until ids.size) ratios.forEach { (x, y) -> out += listOf(ids[a] to x, ids[b] to y) }
    if (ids.size in 3..5) for (a in ids.indices) for (b in a + 1 until ids.size) for (c in b + 1 until ids.size) out += listOf(ids[a], ids[b], ids[c]).map { it to 1f }
    if (ids.size >= 4) out += ids.map { it to 1f }
    return out.map(::normalized)
}

/** Picks over exactly the chosen parts [ids] (three or more): even, each part leading (2:1:1), and with three parts each one trailing (2:2:1). */
private fun setPicks(ids: List<Int>): List<List<Pair<Int, Float>>> {
    val out = mutableListOf(ids.map { it to 1f })
    ids.forEach { lead -> out += ids.map { it to if (it == lead) 2f else 1f } }
    if (ids.size == 3) ids.forEach { low -> out += ids.map { it to if (it == low) 1f else 2f } }
    return out.map(::normalized)
}

private fun normalized(pick: List<Pair<Int, Float>>): List<Pair<Int, Float>> {
    val sum = pick.sumOf { it.second.toDouble() }.toFloat()
    return pick.map { (i, w) -> i to w / sum }
}

private fun swatch(hex: String?) = hexToRgb(hex ?: "#808080").let { Color(it[0], it[1], it[2]) }

/** Black or white, whichever reads better on [c]. */
private fun inkOn(c: Color) = if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue > 0.6f) Color.Black else Color.White

/** Whole percentages of [shares] that always add up to 100 (largest remainder). */
private fun pcts(shares: List<Float>): List<Int> {
    val total = shares.sum().takeIf { it > 0f } ?: return shares.map { 0 }
    val raw = shares.map { it / total * 100 }
    val out = raw.map { it.toInt() }.toMutableList()
    var left = 100 - out.sum()
    raw.indices.sortedByDescending { raw[it] - out[it] }.forEach { if (left > 0) { out[it]++; left-- } }
    return out
}

/** Which part prints on each of [n] layers so every part's count tracks its share (the order a mix lays its colours down). */
private fun layerPattern(shares: List<Float>, n: Int): List<Int> {
    val count = IntArray(shares.size)
    return List(n) { k -> shares.indices.maxBy { shares[it] * (k + 1) - count[it] }.also { count[it]++ } }
}

/**
 * The result: the parts' colours as stacked print layers on the left, fading into the blended colour you see from a distance
 * on the right, with its hex code. Fewer than two parts: the hint (over the one chosen colour, if any).
 */
@Composable
private fun MixPreview(colors: List<String?>, shares: List<Float>, hint: String) {
    val shape = RoundedCornerShape(16.dp)
    val mixed = colors.size >= 2
    val hex = if (mixed) Mix.blend(colors, shares) else colors.firstOrNull()
    val fill = hex?.let(::swatch) ?: MaterialTheme.colorScheme.surfaceVariant
    val parts = colors.map(::swatch)
    val layers = remember(colors, shares) { if (mixed) layerPattern(shares, 24) else emptyList() }
    Box(Modifier.fillMaxWidth().height(96.dp).clip(shape).background(fill).border(1.dp, MaterialTheme.colorScheme.outline, shape)) {
        if (mixed) Canvas(Modifier.matchParentSize()) {
            val h = size.height / layers.size
            layers.forEachIndexed { k, p ->
                drawRect(parts[p], Offset(0f, k * h), Size(size.width, h + 1f))
                // A faint seam per layer, like the ridges of a printed wall.
                drawRect(Color.Black.copy(alpha = 0.14f), Offset(0f, (k + 1) * h - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            }
            drawRect(Brush.horizontalGradient(0f to fill.copy(alpha = 0f), 0.28f to fill.copy(alpha = 0f), 0.62f to fill, 1f to fill))
        }
        val ink = inkOn(fill)
        if (mixed) Text(hex!!, Modifier.align(Alignment.BottomEnd).padding(12.dp), color = ink,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
        else Text(hint, Modifier.align(Alignment.Center).padding(16.dp), color = if (hex == null) MaterialTheme.colorScheme.onSurfaceVariant else ink,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** One quick pick: the blended colour as a round swatch, the shares under it (e.g. 75/25). */
@Composable
private fun MixSwatch(colors: List<String?>, shares: List<Float>, selected: Boolean, label: String, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).semantics { contentDescription = label }.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).border(2.dp, ring, CircleShape).padding(4.dp).clip(CircleShape).background(swatch(Mix.blend(colors, shares)))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Text(pcts(shares).joinToString("/"), style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A section caption, with an optional action at the right end. */
@Composable
private fun Caption(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

/**
 * Edits whether slot [index] is a mix of the other (non-mixed) slots, and in which parts; [index] == slots.size = a new mix slot.
 * Bambu Studio-like: a big live preview (print layers fading into the blend), filament chips, quick picks, and one proportion
 * bar for any number of parts whose dividers drag in 5 % steps, so the shares always add up to 100 %.
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
    fun sharesNow(): List<Float> {
        val w = chosen.map { parts.getValue(it) }
        val t = w.sum()
        return if (t > 0f) w.map { it / t } else w.map { 0f }
    }
    val shares = sharesNow()
    val allPicks = remember(candidates) { quickPicks(candidates) }
    // One or two chosen: the combos that contain them. Three or more: ready-made shares of exactly those parts.
    val picks = when {
        chosen.isEmpty() -> emptyList()
        chosen.size >= 3 -> setPicks(chosen)
        else -> allPicks.filter { p -> chosen.all { c -> p.any { it.first == c } } }
    }
    val outline = MaterialTheme.colorScheme.outline
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mix_title, index + 1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MixPreview(chosen.map { slots[it].color }, shares, stringResource(when {
                    candidates.size < 2 -> R.string.mix_need_two
                    chosen.size == 1 -> R.string.mix_hint_one
                    else -> R.string.mix_hint
                }))
                Caption(stringResource(R.string.mix_pick))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { i ->
                        val on = i in parts
                        val fill = swatch(slots[i].color)
                        // The slot number stays on the chip (similar colours are told apart by it); a check badge marks the chosen ones.
                        Box(Modifier.size(48.dp)) {
                            Box(Modifier.matchParentSize().clip(CircleShape).clickable {
                                if (on) parts.remove(i) else parts[i] = if (chosen.isEmpty()) 5f else chosen.sumOf { parts.getValue(it).toDouble() }.toFloat() / chosen.size
                            }.border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else outline, CircleShape).padding(if (on) 6.dp else 4.dp)
                                .clip(CircleShape).background(fill).border(1.dp, outline.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = inkOn(fill), style = MaterialTheme.typography.labelLarge)
                            }
                            // Ringed in the dialog colour so the badge stands out on a chip of the same colour as the accent.
                            if (on) Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(AlertDialogDefaults.containerColor)
                                .padding(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                // Quick picks and proportions keep their place while empty (a note / a blank bar): the centred dialog would
                // otherwise grow and move the chips away under the finger between two taps.
                Caption(stringResource(R.string.mix_quick))
                if (picks.isEmpty()) Box(Modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.mix_quick_none),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        picks.forEach { pick ->
                            val on = pick.size == chosen.size && pick.all { (i, s) -> i in chosen && abs(shares[chosen.indexOf(i)] - s) < 0.02f }
                            MixSwatch(pick.map { slots[it.first].color }, pick.map { it.second }, on,
                                pick.joinToString(" + ") { (i, s) -> "${i + 1} ${(s * 100).roundToInt()}%" }) {
                                parts.clear(); pick.forEach { (i, s) -> parts[i] = s * 10 }
                            }
                        }
                    }
                }
                Caption(stringResource(R.string.mix_shares)) {
                    TextButton(onClick = { chosen.forEach { parts[it] = 10f / chosen.size } }, enabled = chosen.size >= 2) { Text(stringResource(R.string.mix_even)) }
                }
                if (chosen.size < 2) {
                    val shape = RoundedCornerShape(12.dp)
                    Box(Modifier.fillMaxWidth().height(44.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, outline.copy(alpha = 0.5f), shape))
                } else {
                    ShareBar(chosen.map { slots[it].color }, shares, chosen.mapIndexed { k, i -> "${i + 1} ${pcts(shares)[k]}%" }.joinToString(" + ")) { k, x ->
                        // Divider k sits between parts k and k + 1: only those two change, so the total stays 100 %.
                        val s = sharesNow()
                        val lo = s.take(k).sum() + MIN_SHARE
                        val hi = s.take(k + 2).sum() - MIN_SHARE
                        if (hi < lo) return@ShareBar
                        val at = ((x / STEP).roundToInt() * STEP).coerceIn(lo, hi)
                        val out = s.toMutableList()
                        out[k] = at - s.take(k).sum()
                        out[k + 1] = s.take(k + 2).sum() - at
                        chosen.forEachIndexed { j, i -> parts[i] = out[j] * 10 }
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mix_sublayers), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(blend, { blend = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(chosen.zip(shares) { i, s -> (i + 1) to s }, blend); onDismiss() }, enabled = chosen.size >= 2) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (current != null && Mix.isMixed(current)) TextButton(onClick = { onApply(null, blend); onDismiss() }) { Text(stringResource(R.string.mix_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private const val STEP = 0.05f
private const val MIN_SHARE = 0.05f

/**
 * The shares of [colors] as one bar of coloured segments (percent inside each wide enough segment) with a handle on every divider.
 * Tap or drag anywhere: the nearest divider follows the finger; [onMove] gets (divider, position 0..1).
 */
@Composable
private fun ShareBar(colors: List<String?>, shares: List<Float>, description: String, onMove: (Int, Float) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val labels = pcts(shares)
    var held by remember { mutableIntStateOf(-1) }
    // The gesture handlers outlive recompositions: read the latest shares and callback, not the ones they started with.
    val now by rememberUpdatedState(shares)
    val move by rememberUpdatedState(onMove)
    fun nearest(x: Float, w: Float): Int {
        var acc = 0f
        val cuts = now.dropLast(1).map { acc += it; acc }
        return cuts.indices.minBy { abs(cuts[it] - x / w) }
    }
    Box(Modifier.fillMaxWidth().height(44.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape)
        .semantics { contentDescription = description }
        .pointerInput(colors.size) { detectTapGestures { val w = size.width.toFloat(); move(nearest(it.x, w), it.x / w) } }
        .pointerInput(colors.size) {
            detectDragGestures(onDragStart = { held = nearest(it.x, size.width.toFloat()) }, onDragEnd = { held = -1 }, onDragCancel = { held = -1 }) { c, _ ->
                if (held >= 0) move(held, c.position.x / size.width)
            }
        }) {
        Row(Modifier.matchParentSize()) {
            colors.forEachIndexed { k, hex ->
                val fill = swatch(hex)
                Box(Modifier.weight(shares[k].coerceAtLeast(0.01f)).fillMaxHeight().background(fill), contentAlignment = Alignment.Center) {
                    if (shares[k] >= 0.12f) Text("${labels[k]}%", color = inkOn(fill), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            var acc = 0f
            shares.dropLast(1).forEachIndexed { k, s ->
                acc += s
                val x = acc * size.width
                val w = (if (k == held) 8.dp else 6.dp).toPx()
                val h = size.height - 12.dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(x - w / 2 - 1.dp.toPx(), 5.dp.toPx()), Size(w + 2.dp.toPx(), h + 2.dp.toPx()), CornerRadius(w))
                drawRoundRect(Color.White, Offset(x - w / 2, 6.dp.toPx()), Size(w, h), CornerRadius(w / 2))
            }
        }
    }
}
