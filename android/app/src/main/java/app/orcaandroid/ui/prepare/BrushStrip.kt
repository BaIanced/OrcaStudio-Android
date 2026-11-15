package app.orcaandroid.ui.prepare

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.SCULPT_MAX_MM
import app.orcaandroid.ui.SCULPT_MIN_MM
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Reshape with the setting `edgeMenu` on (docs/design/EDGE-MENU.md section 8): the brush strip under the plate and a vertical
 * size slider in place of the Sculpt card. Strength, invert and mirror are in the Reshape tree (MenuTree.kt).
 */

/** The seven brushes, in strip and menu order (the engine's names). */
internal val SCULPT_BRUSH_IDS = listOf("draw", "inflate", "smooth", "flatten", "pinch", "crease", "grab")

internal fun brushLabel(brush: String): Int = when (brush) {
    "inflate" -> R.string.sculpt_inflate
    "smooth" -> R.string.sculpt_smooth
    "flatten" -> R.string.sculpt_flatten
    "pinch" -> R.string.sculpt_pinch
    "crease" -> R.string.sculpt_crease
    "grab" -> R.string.sculpt_grab
    else -> R.string.sculpt_draw
}

internal fun brushIcon(brush: String): ImageVector = when (brush) {
    "inflate" -> Icons.Default.BubbleChart
    "smooth" -> Icons.Default.Waves
    "flatten" -> Icons.Default.HorizontalRule
    "pinch" -> Icons.Default.UnfoldLess
    "crease" -> Icons.Default.ShowChart
    "grab" -> Icons.Default.PanTool
    else -> Icons.Default.Brush
}

/** Which way a strip drag went once it passed the touch slop: along the strip = brushes, across = size. */
enum class StripAxis { BRUSH, SIZE }

/** The strip's and the slider's numbers as pure functions (JVM-tested in BrushStripTest). */
object BrushStripMath {
    /** One brush per this much horizontal drag. */
    const val BRUSH_STEP_DP = 28f
    /** A vertical drag this long covers the whole size range. */
    const val SIZE_SPAN_DP = 240f
    /** After the finger lifts the other brushes fade to [DIM_ALPHA] over [FADE_MS]. */
    const val FADE_DELAY_MS = 600L
    /** After a tap that only woke the dimmed strip: time to choose before it fades again. */
    const val WAKE_DELAY_MS = 2500L
    const val FADE_MS = 200
    const val DIM_ALPHA = 0.25f
    /** TalkBack Bigger / Smaller: a twentieth of the (log) size range, the slider's own step. */
    const val SIZE_STEP = 0.05f

    /** Null until the drag passed [slop]; then the dominant axis (a tie counts as along the strip). */
    fun lockAxis(dx: Float, dy: Float, slop: Float): StripAxis? = when {
        dx * dx + dy * dy <= slop * slop -> null
        abs(dx) >= abs(dy) -> StripAxis.BRUSH
        else -> StripAxis.SIZE
    }

    /** The brush after a drag of [dx] px from [start]: the row follows the finger (left = next), one per [stepPx], held at the ends. */
    fun brushIndex(start: Int, dx: Float, stepPx: Float, count: Int): Int =
        (start - (dx / stepPx).toInt()).coerceIn(0, count - 1)

    /** [brush] moved [by] places (Next / Previous), held at the ends. */
    fun stepBrush(brush: String, by: Int, brushes: List<String> = SCULPT_BRUSH_IDS): String =
        brushes[(brushes.indexOf(brush).coerceAtLeast(0) + by).coerceIn(0, brushes.lastIndex)]

    /** 0..1 position of [mm] on the log scale between [min] and [max] (the Sculpt card's slider scale). */
    fun sizeFraction(mm: Float, min: Float = SCULPT_MIN_MM, max: Float = SCULPT_MAX_MM): Float =
        ((ln(mm.coerceIn(min, max)) - ln(min)) / (ln(max) - ln(min))).coerceIn(0f, 1f)

    fun sizeAt(fraction: Float, min: Float = SCULPT_MIN_MM, max: Float = SCULPT_MAX_MM): Float =
        exp(ln(min) + fraction.coerceIn(0f, 1f) * (ln(max) - ln(min))).coerceIn(min, max)

    /** The size after a vertical drag of [dy] px from [startMm]: up (dy < 0) = bigger, the whole range over [spanPx]. */
    fun sizeForDrag(startMm: Float, dy: Float, spanPx: Float, min: Float = SCULPT_MIN_MM, max: Float = SCULPT_MAX_MM): Float =
        sizeAt(sizeFraction(startMm, min, max) - dy / spanPx, min, max)

    /** [mm] moved [steps] slider steps (TalkBack Bigger / Smaller). */
    fun stepSize(mm: Float, steps: Int, min: Float = SCULPT_MIN_MM, max: Float = SCULPT_MAX_MM): Float =
        sizeAt(sizeFraction(mm, min, max) + steps * SIZE_STEP, min, max)

    /** The row offset that puts item [index] in the middle of a strip [width] px wide. */
    fun centredOffset(index: Int, width: Float, itemPx: Float): Float = width / 2f - (index + 0.5f) * itemPx

    /** The item under [x] on a strip whose row sits at [rowOffset], or null between the ends. */
    fun itemAt(x: Float, rowOffset: Float, itemPx: Float, count: Int): Int? =
        floor((x - rowOffset) / itemPx).toInt().takeIf { it in 0 until count }
}

/** A size drag runs (strip or slider): the 3D view rings the brush at its new size and the edge handle steps aside. */
internal object BrushSizeDrag {
    var active by mutableStateOf(false)
}

/**
 * Reshape's bottom bar: the strip, with the shortcut dock beside it at the end when both fit side by side (the strip keeps at
 * least 240 dp), else the dock above it. Wide layouts: the strip alone (the dock is the column above it, PrepareOverlay).
 */
@Composable
internal fun ReshapeBar(state: UiState, vm: AppViewModel, view: PlateView?, tool: Tool.Sculpt, wide: Boolean, modifier: Modifier = Modifier) {
    if (wide) { BrushStrip(tool, vm, modifier.fillMaxWidth()); return }
    val docked = Shortcuts.forContext(state.chrome.shortcuts, reshaping = true).size
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 48 dp buttons 4 dp apart in a 4 dp padded row.
        val dock = (docked * 52 + 4).dp
        if (docked > 0 && maxWidth - dock - 8.dp >= 240.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                BrushStrip(tool, vm, Modifier.weight(1f))
                ShortcutDock(state, vm, view, vertical = false)
            }
        } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutDock(state, vm, view, vertical = false)
            BrushStrip(tool, vm, Modifier.fillMaxWidth())
        }
    }
}

/**
 * The brush strip (56 dp): the brushes with labels, the chosen one centred and highlighted. A drag along it scrubs the brushes
 * (one per [BrushStripMath.BRUSH_STEP_DP], a tick each), a drag across it sizes the brush (log scale, up = bigger), a tap picks.
 * [BrushStripMath.FADE_DELAY_MS] after a lift the other brushes dim; a tap on the dimmed strip only wakes it. For TalkBack one
 * node with the brush and size as its text and Next / Previous brush, Bigger / Smaller as custom actions.
 */
@Composable
internal fun BrushStrip(tool: Tool.Sculpt, vm: AppViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val alpha = LocalOverlayAlpha.current
    val curTool by rememberUpdatedState(tool)
    val count = SCULPT_BRUSH_IDS.size
    val index = SCULPT_BRUSH_IDS.indexOf(tool.brush).coerceAtLeast(0)
    var dimmed by remember { mutableStateOf(false) }
    // Each lift restarts the fade timer (the first one only after the first touch: on entry every brush shows).
    var lifts by remember { mutableIntStateOf(0) }
    var wait by remember { mutableLongStateOf(BrushStripMath.FADE_DELAY_MS) }
    LaunchedEffect(lifts) { if (lifts > 0) { delay(wait); dimmed = true } }
    val itemPx = with(density) { ITEM.toPx() }
    val stepPx = with(density) { BrushStripMath.BRUSH_STEP_DP.dp.toPx() }
    val spanPx = with(density) { BrushStripMath.SIZE_SPAN_DP.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    val offset by animateFloatAsState(BrushStripMath.centredOffset(index, width.toFloat(), itemPx), tween(160), label = "brush row")
    val curOffset by rememberUpdatedState(offset)

    val labels = SCULPT_BRUSH_IDS.map { stringResource(brushLabel(it)) }
    val stripLabel = stringResource(R.string.brush_strip)
    val stateText = stringResource(R.string.brush_strip_state, labels[index], tool.radius)
    val next = stringResource(R.string.brush_next)
    val previous = stringResource(R.string.brush_previous)
    val bigger = stringResource(R.string.brush_bigger)
    val smaller = stringResource(R.string.brush_smaller)
    fun set(t: Tool.Sculpt) { if (t != curTool) vm.scene.setTool(t) }

    // Drag state (one gesture at a time).
    val drag = remember { IntArray(2) }          // [0] start brush, [1] brush last set
    val dragSize = remember { FloatArray(1) }    // start size

    Surface(
        modifier
            .height(56.dp)
            .onSizeChanged { width = it.width }
            .clearAndSetSemantics {
                contentDescription = stripLabel
                stateDescription = stateText
                customActions = listOf(
                    CustomAccessibilityAction(next) { set(curTool.copy(brush = BrushStripMath.stepBrush(curTool.brush, 1))); true },
                    CustomAccessibilityAction(previous) { set(curTool.copy(brush = BrushStripMath.stepBrush(curTool.brush, -1))); true },
                    CustomAccessibilityAction(bigger) { set(curTool.copy(radius = BrushStripMath.stepSize(curTool.radius, 1))); true },
                    CustomAccessibilityAction(smaller) { set(curTool.copy(radius = BrushStripMath.stepSize(curTool.radius, -1))); true },
                )
            }
            .pointerInput(itemPx, stepPx, spanPx) {
                stripGestures(
                    onTap = { p ->
                        if (dimmed) { dimmed = false; wait = BrushStripMath.WAKE_DELAY_MS }
                        else {
                            BrushStripMath.itemAt(p.x, curOffset, itemPx, count)?.let { set(curTool.copy(brush = SCULPT_BRUSH_IDS[it])) }
                            wait = BrushStripMath.FADE_DELAY_MS
                        }
                        lifts++
                    },
                    onLock = { axis ->
                        dimmed = false
                        val i = SCULPT_BRUSH_IDS.indexOf(curTool.brush).coerceAtLeast(0)
                        drag[0] = i; drag[1] = i
                        dragSize[0] = curTool.radius
                        if (axis == StripAxis.SIZE) BrushSizeDrag.active = true
                    },
                    onDrag = { axis, d ->
                        if (axis == StripAxis.BRUSH) {
                            val i = BrushStripMath.brushIndex(drag[0], d.x, stepPx, count)
                            if (i != drag[1]) {
                                drag[1] = i
                                set(curTool.copy(brush = SCULPT_BRUSH_IDS[i]))
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        } else set(curTool.copy(radius = BrushStripMath.sizeForDrag(dragSize[0], d.y, spanPx)))
                    },
                    onEnd = { axis ->
                        if (axis == StripAxis.SIZE) BrushSizeDrag.active = false
                        if (axis != null) { wait = BrushStripMath.FADE_DELAY_MS; lifts++ }
                    },
                )
            },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
        shadowElevation = 3.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(offset.roundToInt(), 0) }) {
                SCULPT_BRUSH_IDS.forEachIndexed { i, brush ->
                    val chosen = i == index
                    val a by animateFloatAsState(if (dimmed && !chosen) BrushStripMath.DIM_ALPHA else 1f,
                        if (dimmed) tween(BrushStripMath.FADE_MS) else snap(), label = "brush fade")
                    Column(
                        Modifier
                            .width(ITEM)
                            .fillMaxHeight()
                            .padding(horizontal = 2.dp, vertical = 4.dp)
                            .graphicsLayer { this.alpha = a }
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val tint = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                        Icon(brushIcon(brush), null, Modifier.size(22.dp), tint = tint)
                        Text(labels[i], style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val ITEM = 64.dp

/** Tap (up before the slop) or an axis-locked drag ([d] = offset from the press); [onEnd] after any locked drag, also when cut off. */
private suspend fun PointerInputScope.stripGestures(onTap: (Offset) -> Unit, onLock: (StripAxis) -> Unit, onDrag: (StripAxis, Offset) -> Unit,
                                                   onEnd: (StripAxis?) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var axis: StripAxis? = null
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    if (axis == null) onTap(change.position)
                    break
                }
                val d = change.position - down.position
                if (axis == null) axis = BrushStripMath.lockAxis(d.x, d.y, viewConfiguration.touchSlop)?.also(onLock)
                if (axis != null) { change.consume(); onDrag(axis, d) }
            }
        } finally {
            onEnd(axis)
        }
    }
}

/**
 * The vertical size slider (160 dp, log scale, up = bigger) with the size in mm beside the thumb, on the inner side
 * ([labelRight]: the slider sits at the left edge). A press jumps to the spot, a drag follows; TalkBack sees a normal slider.
 */
@Composable
internal fun BrushSizeSlider(tool: Tool.Sculpt, vm: AppViewModel, labelRight: Boolean, modifier: Modifier = Modifier) {
    val curTool by rememberUpdatedState(tool)
    val alpha = LocalOverlayAlpha.current
    val fraction = BrushStripMath.sizeFraction(tool.radius)
    val desc = stringResource(R.string.brush_size_label)
    val valueText = stringResource(R.string.brush_size_mm, tool.radius)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha)
    val fill = MaterialTheme.colorScheme.primary
    val thumbR = with(LocalDensity.current) { 10.dp.toPx() }
    fun set(f: Float) {
        val r = BrushStripMath.sizeAt(f)
        if (r != curTool.radius) vm.scene.setTool(curTool.copy(radius = r))
    }
    Box(modifier.height(SLIDER).width(48.dp + 72.dp)) {
        Box(
            Modifier
                .align(if (labelRight) Alignment.CenterStart else Alignment.CenterEnd)
                .width(48.dp)
                .fillMaxHeight()
                .sliderSemantics(desc, valueText, fraction) { set(it) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        BrushSizeDrag.active = true
                        val h = size.height.toFloat()
                        fun at(y: Float) = 1f - ((y - thumbR) / (h - 2 * thumbR)).coerceIn(0f, 1f)
                        try {
                            set(at(down.position.y))
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                set(at(change.position.y))
                            }
                        } finally {
                            BrushSizeDrag.active = false
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val top = thumbR
                val bottom = size.height - thumbR
                val y = bottom - fraction * (bottom - top)
                drawLine(track, Offset(x, top), Offset(x, bottom), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                drawLine(fill, Offset(x, y), Offset(x, bottom), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(fill, thumbR, Offset(x, y))
            }
        }
        // The value beside the thumb (a plain box, not a Surface: touches beside the track stay the 3D view's).
        val labelY = with(LocalDensity.current) { (thumbR + (1f - fraction) * (SLIDER.toPx() - 2 * thumbR) - 12.dp.toPx()).roundToInt() }
        Box(
            Modifier
                .align(if (labelRight) Alignment.TopStart else Alignment.TopEnd)
                .offset { IntOffset(if (labelRight) 48.dp.roundToPx() else -48.dp.roundToPx(), labelY) }
                .clearAndSetSemantics { }
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)),
        ) {
            Text(valueText, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

private val SLIDER = 160.dp

/** A slider node for TalkBack: name, value text, position, and set-progress. */
private fun Modifier.sliderSemantics(name: String, value: String, fraction: Float, onSet: (Float) -> Unit) = clearAndSetSemantics {
    contentDescription = name
    stateDescription = value
    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    setProgress { onSet(it); true }
}
