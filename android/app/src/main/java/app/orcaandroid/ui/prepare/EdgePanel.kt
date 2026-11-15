package app.orcaandroid.ui.prepare

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The back row's key among a level's rows. */
private const val BACK = "<back>"

/** Pen / mouse hover over a branch, and a still finger on one during slide-through, open it after this long. */
private const val DWELL_MS = 400L

/** Toggles flip and leave the panel open so several can be set (spec section 5); every other leaf closes it. */
private val KEEP_OPEN = setOf("plate.arrange.snap", "plate.arrange.move_lock", "reshape.invert")
private fun MenuNode.keepsOpen() = checked != null && (id.startsWith("view.") || id.startsWith("reshape.mirror.") || id in KEEP_OPEN)

/** Open state, drill-down path and slide-through tracking. Session only: closed and back at the top level on every start. */
private class EdgeMenu {
    var open by mutableStateOf(false)
    var path by mutableStateOf(emptyList<String>())
    var sliding by mutableStateOf(false)
    /** The row under the sliding finger. */
    var highlight by mutableStateOf<String?>(null)
    /** Rows on screen in window pixels, by "<level>|<row>" (a drill animates two levels at once). */
    val rows = mutableMapOf<String, Rect>()
    var panel = Rect.Zero
    var handle: LayoutCoordinates? = null
    /** After a drill during a slide the finger sits on a row of the new level it never chose: lifting there runs nothing. */
    var armed = true
    var landed = false
    var landing: String? = null

    val level get() = path.joinToString("/")
    fun hit(p: Offset): String? {
        val prefix = "$level|"
        return rows.entries.firstOrNull { it.key.startsWith(prefix) && it.value.contains(p) }?.key?.removePrefix(prefix)
    }
    fun shown(levelKey: String) = rows.keys.any { it.startsWith("$levelKey|") }
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
    val importModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.files.openModels(it) }
    val cur by rememberUpdatedState(state)
    val curView by rememberUpdatedState(view)
    val (valid, _) = menuLevel(state, m.path)
    // A branch that went away (the selection changed) closes with its children.
    if (valid != m.path) SideEffect { m.path = valid }

    fun runLeaf(node: MenuNode) {
        if (!node.enabled(cur)) return
        if (!node.keepsOpen()) m.close()
        when (val a = node.action) {
            is MenuAction.Call -> a.run(MenuEnv(vm, cur, curView))
            // ViewContextMenu hosts the add dialogs for the whole overlay.
            is MenuAction.Dialog -> vm.scene.openAddDialog(a.dialog)
            is MenuAction.Launcher -> if (a.launcher == "import") importModels.launch(arrayOf("*/*"))
            null -> Unit
        }
    }
    /** A tap, or a lift during slide-through: back goes up, a branch opens, a leaf runs. */
    fun activate(key: String) {
        if (key == BACK) { m.path = m.path.dropLast(1); return }
        val node = menuLevel(cur, m.path).second.firstOrNull { it.id == key } ?: return
        if (!node.isLeaf) { if (node.enabled(cur)) m.path = m.path + node.id }
        else if (node.action != null) runLeaf(node)
    }

    val onSlide by rememberUpdatedState<(Offset) -> Unit> { local ->
        val p = m.toWindow(local)
        val key = p?.let(m::hit)
        if (!m.armed) {
            if (!m.landed) { if (m.shown(m.level)) { m.landed = true; m.landing = key } }
            else if (key != m.landing) m.armed = true
        }
        if (key != m.highlight) {
            m.highlight = key
            if (key != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
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
    // Slide-through: a finger held still on a branch (or the back row) opens it, so one gesture reaches a nested leaf.
    LaunchedEffect(m.sliding, m.highlight, valid) {
        val k = m.highlight ?: return@LaunchedEffect
        if (!m.sliding || !m.armed) return@LaunchedEffect
        if (k != BACK && menuLevel(cur, valid).second.none { it.id == k && !it.isLeaf }) return@LaunchedEffect
        delay(DWELL_MS)
        m.armed = false
        m.landed = false
        m.highlight = null
        activate(k)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    BackHandler(enabled = m.open) { m.close() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val alpha = LocalOverlayAlpha.current
        // Out of the way of a dialog and of the hold menu (spec section 5); kept while a slide runs (it owns the finger).
        val hidden = state.workspace.addDialog != null || state.workspace.contextMenu != null
        if (!hidden || m.sliding) {
            val label = stringResource(R.string.edge_menu_title)
            Box(
                Modifier
                    // Centred at 45 % of the plate area's height.
                    .align(BiasAlignment(if (left) -1f else 1f, -0.1f))
                    .size(32.dp, 96.dp)
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
                val tab = if (left) RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp) else RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                Box(Modifier.size(12.dp, 64.dp).clip(tab).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha)),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(3.dp, 28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)))
                }
            }
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
                    MenuLevel(state, view, m, path, onActivate = ::activate) { node, v -> node.slider?.set?.invoke(MenuEnv(vm, cur, curView), v) }
                }
            }
        }
    }
}

/** One level: the back row (below the top level), then the level's rows. */
@Composable
private fun MenuLevel(state: UiState, view: PlateView?, m: EdgeMenu, path: List<String>, onActivate: (String) -> Unit,
                      onSlider: (MenuNode, Float) -> Unit) {
    val levelKey = path.joinToString("/")
    val nodes = menuLevel(state, path).second
    val live = levelKey == m.level
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        val parent = path.lastOrNull()?.let(::findMenuNode)
        if (parent != null) {
            MenuRowFrame(m, levelKey, BACK, live && m.highlight == BACK, enabled = true, onClick = { onActivate(BACK) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.back))
                Spacer(Modifier.width(12.dp))
                Text(parent.text ?: stringResource(parent.label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
        nodes.forEach { node ->
            val enabled = node.enabled(state)
            val slider = node.slider
            if (slider != null) {
                MenuRowFrame(m, levelKey, node.id, false, enabled, onClick = null) {
                    Text(node.text ?: stringResource(node.label), Modifier.width(80.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Slider(slider.value(state), { onSlider(node, it) }, Modifier.weight(1f).padding(end = 8.dp), enabled = enabled,
                        valueRange = slider.range)
                }
                return@forEach
            }
            val checked = node.checked?.invoke(state, view)
            MenuRowFrame(m, levelKey, node.id, live && m.highlight == node.id, enabled, onClick = { onActivate(node.id) },
                hoverOpen = if (!node.isLeaf) { { onActivate(node.id) } } else null, checked = checked) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { node.icon?.let { Icon(it(), null) } }
                Spacer(Modifier.width(12.dp))
                Text(node.text ?: stringResource(node.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                if (checked == true) Icon(Icons.Default.Check, null, Modifier.padding(horizontal = 4.dp))
                if (!node.isLeaf) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(end = 8.dp))
                // Shortcuts (T-083) wire this button; until then it only shows where it will be.
                else IconButton(onClick = {}, enabled = enabled) { Icon(Icons.Outlined.PushPin, stringResource(R.string.shortcut_add), Modifier.size(20.dp)) }
            }
        }
    }
}

/** A 48 dp row that registers its bounds for slide-through hit tests. */
@Composable
private fun MenuRowFrame(m: EdgeMenu, levelKey: String, key: String, highlighted: Boolean, enabled: Boolean, onClick: (() -> Unit)?,
                         hoverOpen: (() -> Unit)? = null, checked: Boolean? = null, content: @Composable () -> Unit) {
    val id = "$levelKey|$key"
    DisposableEffect(id) { onDispose { m.rows.remove(id) } }
    val onHover by rememberUpdatedState(hoverOpen)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onGloballyPositioned { m.rows[id] = it.boundsInWindow() }
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .then(if (checked != null) Modifier.semantics { selected = checked } else Modifier)
            .then(if (hoverOpen != null) Modifier.pointerInput(id) { hoverDwell { onHover?.invoke() } } else Modifier)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ) { content() }
    }
}

/** Pen or mouse hovering over the node for [DWELL_MS] runs [onDwell] ("submenus on hover"); touch never hovers. */
private suspend fun PointerInputScope.hoverDwell(onDwell: () -> Unit) = coroutineScope {
    var job: Job? = null
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            val type = e.changes.firstOrNull()?.type
            when (e.type) {
                PointerEventType.Enter -> if (type == PointerType.Stylus || type == PointerType.Mouse || type == PointerType.Eraser) {
                    job?.cancel()
                    job = launch { delay(DWELL_MS); onDwell() }
                }
                PointerEventType.Exit, PointerEventType.Press -> { job?.cancel(); job = null }
                else -> Unit
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
