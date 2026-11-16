package app.orcaandroid.ui.prepare

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.ViewDefaults
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

/** Open state on top of the shared drill-down and slide tracking (rows in window pixels). Closed and at the top level on every start. */
private class EdgeMenu : MenuNav() {
    var open by mutableStateOf(false)
    var handle: LayoutCoordinates? = null
    fun toWindow(p: Offset): Offset? = handle?.takeIf { it.isAttached }?.localToWindow(p)

    fun show() { open = true; path = emptyList(); highlight = null }
    fun close() { open = false; sliding = false; highlight = null; path = emptyList() }
}

/**
 * The edge menu (docs/design/EDGE-MENU.md section 5), with the setting `edgeMenu` on: a handle on the plate's right (or left)
 * edge; a tap opens the panel, a drag out of the handle slides through it (lift on a leaf runs it). Levels of the menu tree
 * (MenuTree.kt) drill down in place with a back row. Drawn over the whole plate area, outside the overlay's inset.
 */
@Composable
internal fun EdgePanel(state: UiState, vm: AppViewModel, view: PlateView?, top: Dp) {
    val left = state.chrome.edgeMenuLeft
    val m = remember { EdgeMenu() }
    val haptic = LocalHapticFeedback.current
    val run = rememberMenuRunner(vm)
    val cur by rememberUpdatedState(state)
    val curView by rememberUpdatedState(view)
    val (valid, levelRows) = menuLevel(state, m.path)
    // A branch that went away (the selection changed) closes with its children.
    if (valid != m.path) SideEffect { m.path = valid }

    /** A tap, or a lift during slide-through: back goes up, a branch opens, a leaf runs (and closes the panel unless a toggle). */
    fun activate(key: String) = m.activate(key, menuLevel(cur, m.path).second, cur) { node ->
        if (!node.keepsOpen()) m.close()
        run(node, cur, curView)
    }

    val slideTo by rememberUpdatedState<(Offset?) -> Unit> { p ->
        if (m.slideTo(p)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val onSlide by rememberUpdatedState<(Offset) -> Unit> { local -> slideTo(m.toWindow(local)) }
    val onSlideEnd by rememberUpdatedState<(Offset?) -> Unit> { local ->
        val p = local?.let(m::toWindow)
        val key = p?.let(m::hit)
        val armed = m.armed
        m.sliding = false
        m.highlight = null
        when {
            key != null -> { if (armed) activate(key) }
            // Lifted on the panel between rows (or never left the handle, which the panel covers): it stays.
            p == null || m.panel.contains(p) -> Unit
            else -> m.close()
        }
    }
    SlideDwell(m, levelRows) { activate(it) }
    SlideAutoScroll(m) { slideTo(it) }
    BackHandler(enabled = m.open) { m.close() }

    // The one-time hint goes once the panel has been opened.
    LaunchedEffect(m.open) { if (m.open) vm.dismissEdgeHint() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Out of the way of a dialog, the hold menu and the Reshape size drag (spec section 5); kept while a slide runs (it owns the finger).
        val hidden = state.workspace.addDialog != null || state.workspace.contextMenu != null || BrushSizeDrag.active
        // Centred at 45 % of the plate area's height.
        val at = BiasAlignment(if (left) -1f else 1f, -0.1f)
        val c = state.chrome
        val (touchW, touchH) = ViewDefaults.edgeHandleTouch(c.edgeHandleWidth, c.edgeHandleHeight)
        if (!hidden || m.sliding) {
            val label = stringResource(R.string.edge_menu_title)
            Box(
                Modifier
                    .align(at)
                    .size(touchW.dp, touchH.dp)
                    // Android's back gesture takes both side edges; this strip (under its 200 dp cap) stays ours.
                    .systemGestureExclusion()
                    .onGloballyPositioned { m.handle = it }
                    .semantics { contentDescription = label; role = Role.Button; onClick { m.show(); true } }
                    .pointerInput(m) {
                        edgeHandleGestures(
                            onTap = { m.show() },
                            onSlideStart = {
                                m.show()
                                m.sliding = true
                                m.armed = true
                            },
                            onSlide = { onSlide(it) },
                            onSlideEnd = { onSlideEnd(it) },
                        )
                    },
                contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                // The accent (not the overlay alpha): the old surface colour was the 3D view's own background (user 2026-11-15).
                val tab = if (left) RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp) else RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                Box(Modifier.size(c.edgeHandleWidth.dp, c.edgeHandleHeight.dp).alpha(c.edgeHandleAlpha).clip(tab).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) {
                    // Points into the plate, the way the panel comes out.
                    Icon(if (left) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft, null,
                        Modifier.size(minOf(c.edgeHandleWidth, 24f).dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        // First use: what the handle is for, beside it until the panel is opened or the hint is tapped.
        if (!c.edgeHintSeen && !hidden && !m.open) Surface(
            onClick = vm::dismissEdgeHint,
            modifier = Modifier.align(at).then(if (left) Modifier.padding(start = touchW.dp) else Modifier.padding(end = touchW.dp)).widthIn(max = 200.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 2.dp,
        ) {
            Text(stringResource(R.string.edge_hint), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary)
        }
        if (m.open) {
            // Tap outside closes (and goes nowhere else).
            Box(Modifier.fillMaxSize().pointerInput(m) { detectTapGestures { m.close() } })
            var dragX by remember { mutableFloatStateOf(0f) }
            val width = minOf(232.dp, maxWidth * 0.7f)
            Surface(
                Modifier
                    .align(if (left) Alignment.TopStart else Alignment.TopEnd)
                    .padding(top = top)
                    .width(width)
                    .fillMaxHeight()
                    .offset { IntOffset(dragX.roundToInt(), 0) }
                    .onGloballyPositioned { m.panel = it.boundsInWindow() }
                    // Swiped back toward its edge: closes.
                    .pointerInput(left) {
                        detectHorizontalDragGestures(
                            onDragEnd = { if (abs(dragX) > 64.dp.toPx()) m.close(); dragX = 0f },
                            onDragCancel = { dragX = 0f },
                        ) { change, dx ->
                            change.consume()
                            dragX = (dragX + dx).let { if (left) it.coerceAtMost(0f) else it.coerceAtLeast(0f) }
                        }
                    },
                shape = if (left) RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                AnimatedContent(
                    targetState = valid,
                    transitionSpec = {
                        // A deeper level slides in from the edge side, a shallower one from the other.
                        val inward = if (left) -1 else 1
                        val dir = if (targetState.size >= initialState.size) inward else -inward
                        slideInHorizontally(tween(160)) { dir * it } togetherWith slideOutHorizontally(tween(160)) { -dir * it }
                    },
                    label = "edge level",
                ) { path ->
                    MenuLevel(state, view, m, path, menuLevel(state, path).second, divider = 0, onActivate = ::activate,
                        onSlider = { node, v -> node.slider?.set?.invoke(MenuEnv(vm, cur, curView), v) },
                        pins = state.chrome.shortcuts, onPin = { vm.toggleShortcut(it.id) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Tap (up before the touch slop) or slide (past it; positions in the handle's own pixels, null when the gesture was cut off). */
private suspend fun PointerInputScope.edgeHandleGestures(onTap: () -> Unit, onSlideStart: () -> Unit, onSlide: (Offset) -> Unit,
                                                         onSlideEnd: (Offset?) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var total = Offset.Zero
        var slid = false
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change == null) { if (slid) onSlideEnd(null); break }
                if (!change.pressed) {
                    change.consume()
                    if (slid) onSlideEnd(change.position) else onTap()
                    break
                }
                total += change.positionChange()
                if (!slid && total.getDistance() > viewConfiguration.touchSlop) { slid = true; onSlideStart() }
                if (slid) { change.consume(); onSlide(change.position) }
            }
        } catch (e: CancellationException) {
            if (slid) onSlideEnd(null)
            throw e
        }
    }
}
