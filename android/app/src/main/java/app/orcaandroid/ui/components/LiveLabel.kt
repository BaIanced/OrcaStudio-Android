package app.orcaandroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.ui.theme.AXIS_X
import app.orcaandroid.ui.theme.AXIS_Y
import app.orcaandroid.ui.theme.AXIS_Z
import app.orcaandroid.ui.theme.LIVE_LABEL
import app.orcaandroid.ui.theme.ON_LIVE_LABEL
import app.orcaandroid.ui.theme.TouchTarget
import java.util.Locale

/** Sizes of the live label (design spec section 7). */
object LiveLabelDefaults {
    val HEIGHT = 32.dp
    val PAD_H = 12.dp
    val ARROW = 16.dp
    val OFFSET_X = 24.dp
    val OFFSET_Y = 48.dp
    val EDGE = 8.dp
    const val LINGER_MS = 2000L
    val FIELD_WIDTH = 120.dp
}

/** The number as the label shows it: one decimal, `Locale.ROOT`, a sign when [signed] (Push / pull: `+5.0`). */
fun liveLabelNumber(value: Float, signed: Boolean = false) = String.format(Locale.ROOT, if (signed) "%+.1f" else "%.1f", value)

/**
 * A number with its unit and an axis arrow, shown beside a finger or an arrow tip while a distance or angle is dragged.
 * [axis] 0 / 1 / 2 = X / Y / Z, -1 = free; [angle] turns the arrow (degrees on screen, 0 = right). The node's text is
 * exactly "number unit" (`3.0 mm`); [value] comes from the operation's model-space numbers, never from screen pixels.
 * [onType]: tap to type a value (null: not editable).
 */
@Composable
fun LiveLabel(value: Float, unit: String, axis: Int, angle: Float, onType: ((Float) -> Unit)?, modifier: Modifier = Modifier, signed: Boolean = false) {
    val axisColor = when (axis) { 0 -> AXIS_X; 1 -> AXIS_Y; 2 -> AXIS_Z; else -> null }
    val ink = ON_LIVE_LABEL
    val number = MaterialTheme.typography.titleMedium
    val small = MaterialTheme.typography.bodySmall
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = number.fontSize, fontWeight = number.fontWeight, fontFeatureSettings = "tnum")) { append(liveLabelNumber(value, signed)) }
        withStyle(SpanStyle(fontSize = small.fontSize)) { append(" "); append(unit) }
    }
    val description = if (axisColor == null) stringResource(R.string.live_label_free) else stringResource(R.string.live_label_axis, "XYZ"[axis].toString())
    val box = modifier.defaultMinSize(minWidth = 64.dp, minHeight = TouchTarget.MIN).semantics { contentDescription = description }
    Box(if (onType != null) box.clickable(role = Role.Button) { onType(value) } else box, contentAlignment = Alignment.Center) {
        Surface(Modifier.height(LiveLabelDefaults.HEIGHT), shape = RoundedCornerShape(50), color = LIVE_LABEL, contentColor = ink,
            border = BorderStroke(1.dp, ink.copy(alpha = 0.3f))) {
            Row(Modifier.padding(horizontal = LiveLabelDefaults.PAD_H), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(LiveLabelDefaults.ARROW).rotate(angle), tint = axisColor ?: ink)
                if (axisColor != null) Text("XYZ"[axis].toString(), Modifier.padding(start = 2.dp), style = small, color = axisColor)
                Text(text, Modifier.padding(start = 6.dp), maxLines = 1)
            }
        }
    }
}

/**
 * Places a [LiveLabel] beside the finger at ([x], [y]) in its parent's pixels: [LiveLabelDefaults.OFFSET_X] to the end of it
 * and [LiveLabelDefaults.OFFSET_Y] above it, flipped to the other side at the edge and kept [LiveLabelDefaults.EDGE] inside the view.
 */
fun Modifier.liveLabelAt(x: Float, y: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val edge = LiveLabelDefaults.EDGE.roundToPx()
    val dx = LiveLabelDefaults.OFFSET_X.roundToPx()
    val dy = LiveLabelDefaults.OFFSET_Y.roundToPx()
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width + 2 * edge
    val h = if (constraints.hasBoundedHeight) constraints.maxHeight else p.height + 2 * edge
    var left = x.toInt() + dx
    if (left + p.width > w - edge) left = x.toInt() - dx - p.width
    val top = (y.toInt() - dy - p.height / 2)
    layout(w, h) { p.place(IntOffset(left.coerceIn(edge, maxOf(edge, w - edge - p.width)), top.coerceIn(edge, maxOf(edge, h - edge - p.height)))) }
}
