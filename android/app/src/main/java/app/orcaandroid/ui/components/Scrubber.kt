package app.orcaandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gesture scrubber (interaction plan step 8, E5; Snapseed's): hold a setting's label in a tool panel and drag anywhere:
 * left / right changes it, up / down picks another setting of the same panel. [ScrubHud] lists them over the 3D view.
 * Starting on a label (user, 2026-10-27) leaves one-finger drags in the view as they are (orbit, paint, sculpt).
 */
object Scrub {
    class Row(val text: @Composable (Float) -> String, val value: Float, val range: ClosedFloatingPointRange<Float>,
              val step: Float, val set: (Float) -> Unit, val commit: () -> Unit)
    /** The visible scrubbable rows in panel order, refreshed at every recomposition by [ScrubLabel]. */
    internal val rows = LinkedHashMap<String, Row>()
    /** The scrub in progress: the rows it can pick (in order), the picked one and its live value; empty when idle. */
    var keys by mutableStateOf<List<String>>(emptyList())
    var index by mutableIntStateOf(0)
    var value by mutableFloatStateOf(0f)
}

/**
 * The scrub's axis logic in px (pure, for JVM tests). It starts on the axis of the first move; a sideways drift that
 * builds up past [SWITCH_DP] (it fades while the drag follows the current axis) switches. Picking steps one row
 * after [SWITCH_DP], then every [ROW_DP]; the value moves the whole range over [RANGE_DP].
 */
class ScrubGesture(private val dp: Float, private val count: Int, var index: Int) {
    var picking = false; private set
    private var first = true
    private var cross = 0f
    private var along = 0f

    /** One finger move; returns the value change as a fraction of the range (0 while picking). */
    fun move(dx: Float, dy: Float): Float {
        if (first) {
            first = false
            picking = abs(dy) > abs(dx)
            if (picking) { along = dy; return step() }
            return dx / (RANGE_DP * dp)
        }
        if (!picking) {
            cross = if (abs(dx) >= abs(dy)) cross * FADE + dy else cross + dy
            if (abs(cross) <= SWITCH_DP * dp) return dx / (RANGE_DP * dp)
            picking = true; along = cross; cross = 0f
            return step()
        }
        cross = if (abs(dy) >= abs(dx)) cross * FADE + dx else cross + dx
        if (abs(cross) > SWITCH_DP * dp) { picking = false; cross = 0f; along = 0f; return 0f }
        along += dy
        return step()
    }

    private fun step(): Float {
        while (along > SWITCH_DP * dp && index < count - 1) { index++; along -= ROW_DP * dp }
        while (along < -SWITCH_DP * dp && index > 0) { index--; along += ROW_DP * dp }
        // At the first or last row a further pull doesn't wind up: turning back steps after the usual 40 dp.
        if (index == count - 1) along = along.coerceAtMost(0f)
        if (index == 0) along = along.coerceAtLeast(0f)
        return 0f
    }

    companion object {
        const val SWITCH_DP = 40f
        const val ROW_DP = 48f
        const val RANGE_DP = 300f
        const val FADE = 0.8f
    }
}

/**
 * A number cell's drag (pure, for JVM tests; px): sideways = one whole unit per [STEP_DP]; with the finger [FINE_DP]
 * above where it started, 0.1 steps. The value never goes below [min].
 */
class NumberScrub(start: Double, private val density: Float, private val min: Double = -1e6) {
    private var raw = start
    var fine = false
        private set

    /** The finger moved [dx] sideways and is [dyFromStart] below its start (negative: above); returns the value. */
    fun move(dx: Float, dyFromStart: Float): Double {
        fine = dyFromStart < -FINE_DP * density
        val step = if (fine) 0.1 else 1.0
        raw = (raw + dx / (STEP_DP * density) * step).coerceAtLeast(min)
        return (Math.round(raw / step) * step).let { Math.round(it * 10) / 10.0 }.coerceAtLeast(min)
    }

    companion object {
        const val STEP_DP = 8f
        const val FINE_DP = 40f
    }
}

/**
 * Wraps a number field: a sideways drag scrubs it ([NumberScrub]; [onDrag] true at the start, false at the end), a tap
 * still edits it and an up / down drag still scrolls the panel. [content] gets whether a drag is in fine steps (null: none).
 */
@Composable
fun ScrubCell(value: Double, onValue: (Double) -> Unit, onDrag: (Boolean) -> Unit, modifier: Modifier = Modifier, min: Double = -1e6,
              content: @Composable (Boolean?) -> Unit) {
    val cur by androidx.compose.runtime.rememberUpdatedState(value)
    val set by androidx.compose.runtime.rememberUpdatedState(onValue)
    val drag by androidx.compose.runtime.rememberUpdatedState(onDrag)
    var fine by androidx.compose.runtime.remember { mutableStateOf<Boolean?>(null) }
    Box(modifier.pointerInput(min) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            var tx = 0f
            var ty = 0f
            var scrub: NumberScrub? = null
            var last = cur
            try { while (true) {
                val c = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) { if (scrub != null) c.consume(); break }
                val dx = c.position.x - c.previousPosition.x
                tx += dx
                ty += c.position.y - c.previousPosition.y
                if (scrub == null) {
                    if (abs(tx) > viewConfiguration.touchSlop && abs(tx) > abs(ty) * 1.5f) { scrub = NumberScrub(cur, density, min); drag(true) }
                    else if (abs(ty) > viewConfiguration.touchSlop) break
                    else continue
                }
                c.consume()
                val v = scrub.move(dx, c.position.y - down.position.y)
                fine = scrub.fine
                if (v != last) { last = v; set(v) }
            } } finally { if (scrub != null) { fine = null; drag(false) } }
        }
    }) { content(fine) }
}

/** Quantises a raw scrub value to its step inside the range. */
fun scrubSnap(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    val v = raw.coerceIn(range.start, range.endInclusive)
    if (step <= 0f) return v
    return (range.start + ((v - range.start) / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
}

/**
 * A setting's label that scrubs: registers its row while shown; a drag that starts on it runs [Scrub] until the finger
 * lifts. Shown as a tonal chip so it reads as something to hold.
 */
@Composable
fun ScrubLabel(key: String, text: @Composable (Float) -> String, value: Float, range: ClosedFloatingPointRange<Float>,
               step: Float, modifier: Modifier = Modifier, commit: () -> Unit = {}, set: (Float) -> Unit) {
    val row = Scrub.Row(text, value, range, step, set, commit)
    SideEffect { Scrub.rows[key] = row }
    DisposableEffect(key) { onDispose { Scrub.rows.remove(key) } }
    val haptic = LocalHapticFeedback.current
    val active = Scrub.keys.getOrNull(Scrub.index) == key
    Surface(modifier.pointerInput(key) { scrubGesture(key) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } },
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(text(value), Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** Label + slider, the usual setting row. */
@Composable
fun ScrubSlider(key: String, text: @Composable (Float) -> String, value: Float, range: ClosedFloatingPointRange<Float>,
                step: Float, modifier: Modifier = Modifier, commit: () -> Unit = {}, set: (Float) -> Unit) =
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ScrubLabel(key, text, value, range, step, commit = commit, set = set)
        Slider(value, set, valueRange = range, onValueChangeFinished = commit, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.scrubGesture(key: String, tick: () -> Unit) =
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var slop = androidx.compose.ui.geometry.Offset.Zero
        val moved = awaitTouchSlopOrCancellation(down.id) { change, over -> change.consume(); slop = over } ?: return@awaitEachGesture
        val keys = Scrub.rows.keys.toList()
        val startIndex = keys.indexOf(key).takeIf { it >= 0 } ?: return@awaitEachGesture
        val gesture = ScrubGesture(density, keys.size, startIndex)
        var raw = Scrub.rows[key]?.value ?: return@awaitEachGesture
        Scrub.keys = keys; Scrub.index = startIndex; Scrub.value = raw
        fun apply(dx: Float, dy: Float) {
            val before = gesture.index
            val frac = gesture.move(dx, dy)
            if (gesture.index != before) {
                Scrub.rows[keys[before]]?.commit?.invoke()
                raw = Scrub.rows[keys[gesture.index]]?.value ?: raw
                Scrub.index = gesture.index; Scrub.value = raw
                tick()
                return
            }
            val row = Scrub.rows[keys[gesture.index]] ?: return
            if (frac == 0f) return
            val span = row.range.endInclusive - row.range.start
            val wasEnd = raw <= row.range.start || raw >= row.range.endInclusive
            raw = (raw + frac * span).coerceIn(row.range.start, row.range.endInclusive)
            val snapped = scrubSnap(raw, row.range, row.step)
            if (snapped != Scrub.value) { Scrub.value = snapped; row.set(snapped) }
            if (!wasEnd && (raw <= row.range.start || raw >= row.range.endInclusive)) tick()
        }
        apply(slop.x, slop.y)
        var last = moved.position
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) { change.consume(); break }
                val d = change.position - last
                last = change.position
                change.consume()
                if (d.x != 0f || d.y != 0f) apply(d.x, d.y)
            }
        } finally {
            Scrub.rows[keys[gesture.index]]?.commit?.invoke()
            Scrub.keys = emptyList()
        }
    }

/** The scrub's list over the 3D view: every row of the panel, the picked one bright with where its value sits. */
@Composable
fun ScrubHud(modifier: Modifier = Modifier) {
    val keys = Scrub.keys
    if (keys.isEmpty()) return
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            keys.forEachIndexed { i, k ->
                val row = Scrub.rows[k] ?: return@forEachIndexed
                if (i == Scrub.index) {
                    Text(row.text(Scrub.value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val span = (row.range.endInclusive - row.range.start).takeIf { it > 0f } ?: 1f
                    val f = ((Scrub.value - row.range.start) / span).coerceIn(0f, 1f)
                    Box(Modifier.width(200.dp).height(4.dp).background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(f).height(4.dp).background(MaterialTheme.colorScheme.inversePrimary, RoundedCornerShape(2.dp)))
                    }
                } else Text(row.text(row.value), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f))
            }
        }
    }
}
