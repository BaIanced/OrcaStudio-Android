package app.orcaandroid.ui.prepare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the finger is that opened the pie menu: the 3D view feeds it while the finger is down, the menu reads it. */
class PieTouch {
    var at by mutableStateOf<Offset?>(null)
        private set
    var held by mutableStateOf(false)
        private set
    /** The finger lifted (the menu then picks the wedge under it, if any). */
    var lifted by mutableStateOf(false)
        private set

    fun reset() { at = null; held = false; lifted = false }

    /** A negative [x] means the gesture was cancelled: no pick. */
    fun update(x: Float, y: Float, held: Boolean) {
        if (x < 0f) { at = null; this.held = false; lifted = true; return }
        at = Offset(x, y)
        this.held = held
        lifted = !held
    }
}

class PieItem(val icon: ImageVector, val label: String, val enabled: Boolean = true, val action: () -> Unit)

private const val DEAD_DP = 24f
private val RADIUS = 92.dp
private val BUTTON = 52.dp

/**
 * Radial menu opened by a long press at [at] (view pixels; the overlay is inset by [inset] px from the view). While the
 * finger is down, sliding toward a wedge highlights it and lifting picks it; lifting near the middle leaves the menu open
 * so a wedge can be tapped. A tap outside closes it.
 */
@Composable
fun PieMenu(at: Pair<Float, Float>, inset: Int, items: List<PieItem>, touch: PieTouch, onClose: () -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val radius = with(density) { RADIUS.toPx() }
    val margin = radius + with(density) { BUTTON.toPx() }
    fun clampAxis(v: Float, extent: Int) = if (extent <= 0) v else v.coerceIn(min(margin, extent / 2f), maxOf(extent - margin, extent / 2f))
    val center = Offset(clampAxis(at.first - inset, size.width), clampAxis(at.second - inset, size.height))
    val n = items.size
    fun angleOf(i: Int) = -PI / 2 + 2 * PI * i / n
    fun wedgeAt(p: Offset?): Int? {
        if (p == null) return null
        val d = Offset(p.x - inset - center.x, p.y - inset - center.y)
        if (hypot(d.x, d.y) < with(density) { DEAD_DP.dp.toPx() }) return null
        var a = atan2(d.y.toDouble(), d.x.toDouble()) + PI / 2
        if (a < 0) a += 2 * PI
        val i = (a / (2 * PI / n)).roundToInt() % n
        return i.takeIf { items[it].enabled }
    }
    val hover = if (touch.held) wedgeAt(touch.at) else null
    LaunchedEffect(hover) { if (hover != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    LaunchedEffect(touch.lifted) {
        if (touch.lifted) wedgeAt(touch.at)?.let { onClose(); items[it].action() }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(Unit) { detectTapGestures { onClose() } }) {
        if (size != IntSize.Zero) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.Black.copy(alpha = 0.28f), radius + BUTTON.toPx() * 0.9f, center)
                drawCircle(Color.White.copy(alpha = 0.5f), 5.dp.toPx(), center)
            }
            val half = with(density) { (BUTTON / 2).toPx() }
            items.forEachIndexed { i, item ->
                val a = angleOf(i)
                val x = center.x + (radius * cos(a)).toFloat() - half
                val y = center.y + (radius * sin(a)).toFloat() - half
                val on = hover == i
                Column(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 4.dp,
                        modifier = Modifier.size(BUTTON).scale(if (on) 1.2f else 1f)
                            .clickable(enabled = item.enabled) { onClose(); item.action() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(item.icon, item.label, Modifier.size(26.dp), tint = if (item.enabled) Color.Unspecified else Color.Gray)
                        }
                    }
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = Color.White, textAlign = TextAlign.Center,
                        maxLines = 1, modifier = Modifier.wrapContentWidth(unbounded = true).padding(top = 2.dp))
                }
            }
        }
    }
}
