package app.orcaandroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A top bar that may share the camera cutout's strip (fullscreen portrait, user 2026-10-13): [start] fills
 * the width up to the hole and [end] keeps to the right edge past it. When they can't both fit beside the
 * hole (or [start] would get less than [minStart]), the whole bar moves below it.
 * Without a hole in its way it lays out like a Row with a weighted [start].
 */
@Composable
fun CutoutRow(
    modifier: Modifier = Modifier,
    gap: Dp = 4.dp,
    minStart: Dp = 0.dp,
    start: @Composable () -> Unit,
    end: @Composable RowScope.() -> Unit,
) {
    val view = LocalView.current
    // Reading the inset recomposes on rotation; the hole rects come from the view.
    WindowInsets.displayCutout.getTop(LocalDensity.current)
    val holes = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        { Box { start() }; Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically, content = end) },
        modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() },
    ) { ms, c ->
        val g = gap.roundToPx()
        val w = c.maxWidth
        val ep = ms[1].measure(c.copy(minWidth = 0, minHeight = 0))
        val x0 = origin.x.roundToInt()
        val y0 = origin.y.roundToInt()
        // Holes the bar would touch, in this layout's coordinates.
        val hit = holes.filter { it.top < y0 + ep.height && it.bottom > y0 && it.left < x0 + w && it.right > x0 }
            .map { android.graphics.Rect(it.left - x0, it.top - y0, it.right - x0, it.bottom - y0) }
        val holeLeft = hit.minOfOrNull { it.left } ?: w
        val holeRight = hit.maxOfOrNull { it.right } ?: 0
        val beside = hit.isEmpty() || (holeLeft - g >= minStart.roundToPx() && holeRight + g <= w - ep.width)
        val shift = if (beside) 0 else hit.maxOf { it.bottom }
        val startWidth = (if (beside && hit.isNotEmpty()) holeLeft - g else w - ep.width - g).coerceAtLeast(0)
        val sp = ms[0].measure(c.copy(minWidth = startWidth, maxWidth = startWidth, minHeight = 0))
        val h = maxOf(sp.height, ep.height)
        layout(w, shift + h) {
            sp.place(0, shift + (h - sp.height) / 2)
            ep.place(w - ep.width, shift + (h - ep.height) / 2)
        }
    }
}

/**
 * A toolbar split around a camera cutout in its way (fullscreen portrait): [left] keeps to the start edge,
 * [right] fills the space past the hole. When [left] doesn't fit before the hole (or there is no hole in the
 * way) it lays out as one row, [left] then [right] filling the rest, and below the hole if that is in the way.
 */
@Composable
fun SplitAroundCutout(modifier: Modifier = Modifier, gap: Dp = 4.dp, left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit) {
    val view = LocalView.current
    WindowInsets.displayCutout.getTop(LocalDensity.current)
    val holes = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        { Row(verticalAlignment = Alignment.CenterVertically, content = left); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = right) },
        modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() },
    ) { ms, c ->
        val g = gap.roundToPx()
        val w = c.maxWidth
        val lp = ms[0].measure(c.copy(minWidth = 0, minHeight = 0))
        val x0 = origin.x.roundToInt()
        val y0 = origin.y.roundToInt()
        val hit = holes.filter { it.top < y0 + lp.height && it.bottom > y0 && it.left < x0 + w && it.right > x0 }
            .map { android.graphics.Rect(it.left - x0, it.top - y0, it.right - x0, it.bottom - y0) }
        val holeLeft = hit.minOfOrNull { it.left } ?: w
        val holeRight = hit.maxOfOrNull { it.right } ?: 0
        val beside = hit.isNotEmpty() && holeLeft - g >= lp.width
        val shift = if (hit.isEmpty() || beside) 0 else hit.maxOf { it.bottom }
        val rx = if (beside) holeRight + g else lp.width
        val rw = (w - rx).coerceAtLeast(0)
        val rp = ms[1].measure(c.copy(minWidth = rw, maxWidth = rw, minHeight = 0))
        val h = maxOf(lp.height, rp.height, if (beside) hit.maxOf { it.bottom } else 0)
        layout(w, shift + h) {
            lp.place(0, shift + (h - lp.height) / 2)
            rp.place(rx, shift + (h - rp.height) / 2)
        }
    }
}
