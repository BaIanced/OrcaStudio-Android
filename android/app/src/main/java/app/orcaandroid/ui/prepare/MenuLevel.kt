package app.orcaandroid.ui.prepare

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the edge panel (EdgePanel.kt) and the hold menu (HoldMenu.kt) share: a level of the menu tree with its back row, the
 * drill-down path, and slide-through (a held finger highlights rows; lifting on one activates it).
 */

/** The back row's key among a level's rows. */
internal const val MENU_BACK = "<back>"

/** Pen / mouse hover over a branch, and a still finger on one during slide-through, open it after this long. */
internal const val DWELL_MS = 400L

/** Toggles flip and leave the menu open so several can be set (spec section 5); every other leaf closes it. */
private val KEEP_OPEN = setOf("plate.arrange.snap", "plate.arrange.move_lock", "reshape.invert")
internal fun MenuNode.keepsOpen() = checked != null && (id.startsWith("view.") || id.startsWith("reshape.mirror.") || id in KEEP_OPEN)

/** Drill-down path, slide-through tracking and the rows on screen. Session only: back at the top level on every open. */
internal open class MenuNav {
    var path by mutableStateOf(emptyList<String>())
    var sliding by mutableStateOf(false)
    /** The row under the sliding finger. */
    var highlight by mutableStateOf<String?>(null)
    /** Rows on screen by "<level>|<row>" (a drill animates two levels at once), in the space [place] gives. */
    val rows = mutableMapOf<String, Rect>()
    /** The menu's own bounds, same space. */
    var panel = Rect.Zero
    /** The sliding finger, same space. */
    var finger: Offset? = null
    /** The live level's scroll: slide-through scrolls it near the menu's top and bottom edge. */
    var scroll: ScrollState? = null
    /**
     * Landing guard: after a drill during a slide (or when the hold menu opens under a held finger) the finger sits on a row
     * it never chose. Lifting there runs nothing; the guard lets go once the finger reaches another row.
     */
    var armed = true
    var landed = false
    var landing: String? = null
    /** Where the finger was when the guard was set, same space as [rows] (null: wherever the next slide event is). */
    var landAt: Offset? = null

    val level get() = path.joinToString("/")
    fun hit(p: Offset): String? {
        val prefix = "$level|"
        return rows.entries.firstOrNull { it.key.startsWith(prefix) && it.value.contains(p) }?.key?.removePrefix(prefix)
    }
    fun shown(levelKey: String) = rows.keys.any { it.startsWith("$levelKey|") }

    /** A row's bounds in the space the finger's positions arrive in (the edge panel's window). */
    open fun place(c: LayoutCoordinates): Rect = c.boundsInWindow()

    /** Sets the landing guard with the finger at [at]: nothing runs until it leaves the row there (once the level is on screen). */
    open fun disarm(at: Offset?) { armed = false; landed = false; landing = null; landAt = at }

    /** The guard's spot now, in the space of [rows]. */
    protected open fun landPoint(): Offset? = landAt

    /** The sliding finger is at [p] (null: unknown). True when it came onto a new row (for a haptic tick). */
    fun slideTo(p: Offset?): Boolean {
        finger = p
        val key = p?.let(::hit)
        if (!armed) {
            if (!landed && shown(level)) { landed = true; landing = landPoint().let { if (it != null) hit(it) else key } }
            if (landed && key != landing) armed = true
        }
        if (key == highlight) return false
        highlight = key
        return key != null
    }

    /** A tap, or a lift during slide-through, on [key] of the level [nodes]: back goes up, a branch opens, a leaf runs. */
    fun activate(key: String, nodes: List<MenuNode>, state: UiState, runLeaf: (MenuNode) -> Unit) {
        if (key == MENU_BACK) { path = path.dropLast(1); return }
        val node = nodes.firstOrNull { it.id == key } ?: return
        if (!node.isLeaf) { if (node.enabled(state)) path = path + node.id }
        else if (node.action != null && node.enabled(state)) runLeaf(node)
    }
}

/** Runs a leaf's action (the caller closes its menu first). Dialogs go through `openAddDialog`: ViewContextMenu hosts them. */
@Composable
internal fun rememberMenuRunner(vm: AppViewModel): (MenuNode, UiState, PlateView?) -> Unit {
    val importModels = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.files.openModels(it) }
    val launcher by rememberUpdatedState(importModels)
    return remember(vm) {
        { node, state, view ->
            when (val a = node.action) {
                is MenuAction.Call -> a.run(MenuEnv(vm, state, view))
                is MenuAction.Dialog -> vm.scene.openAddDialog(a.dialog)
                is MenuAction.Launcher -> if (a.launcher == "import") launcher.launch(arrayOf("*/*"))
                null -> Unit
            }
        }
    }
}

/** Slide-through: a finger held still on a branch (or the back row) for [DWELL_MS] opens it, so one gesture reaches a nested leaf. */
@Composable
internal fun SlideDwell(nav: MenuNav, nodes: List<MenuNode>, open: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val curNodes by rememberUpdatedState(nodes)
    val curOpen by rememberUpdatedState(open)
    LaunchedEffect(nav, nav.sliding, nav.highlight, nav.level) {
        val k = nav.highlight ?: return@LaunchedEffect
        if (!nav.sliding || !nav.armed) return@LaunchedEffect
        if (k != MENU_BACK && curNodes.none { it.id == k && !it.isLeaf }) return@LaunchedEffect
        delay(DWELL_MS)
        nav.disarm(nav.finger)
        nav.highlight = null
        curOpen(k)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/**
 * Slide-through on a level taller than its menu: a finger held in the menu's top or bottom 40 dp scrolls the level (once the
 * finger has been inside, away from both edges, so the opening spot doesn't scroll), and the highlight follows the rows.
 */
@Composable
internal fun SlideAutoScroll(nav: MenuNav, slideTo: (Offset) -> Unit) {
    val density = LocalDensity.current
    val zone = with(density) { 40.dp.toPx() }
    val step = with(density) { 6.dp.toPx() }
    val curSlide by rememberUpdatedState(slideTo)
    LaunchedEffect(nav, nav.sliding) {
        if (!nav.sliding) return@LaunchedEffect
        var inside = false
        var scrolled = false
        while (true) {
            withFrameNanos { }
            val p = nav.finger ?: continue
            // The rows moved under a still finger last frame (their bounds are new by now).
            if (scrolled) { curSlide(p); scrolled = false }
            val box = nav.panel
            val s = nav.scroll ?: continue
            if (p.x < box.left || p.x > box.right || p.y < box.top || p.y > box.bottom) continue
            val dy = when {
                p.y < box.top + zone -> -step
                p.y > box.bottom - zone -> step
                else -> { inside = true; 0f }
            }
            if (inside && nav.armed && (dy > 0f && s.canScrollForward || dy < 0f && s.canScrollBackward)) {
                s.scrollBy(dy)
                scrolled = true
            }
        }
    }
}

/**
 * One level: the back row (below the top level), then [nodes]; a divider after the first [divider] rows (0 = none). The
 * leaves of an icon-row branch (Align) show as icon buttons, three to a row, without a pin (a pin sits on a row; the six
 * align icons only mean something with two or more objects selected, which a dock button could not show).
 * [pins] are the shortcut ids; [onPin] is a tap on a leaf's pin button.
 */
@Composable
internal fun MenuLevel(state: UiState, view: PlateView?, nav: MenuNav, path: List<String>, nodes: List<MenuNode>, divider: Int,
                       onActivate: (String) -> Unit, onSlider: (MenuNode, Float) -> Unit, pins: List<String>, onPin: (MenuNode) -> Unit,
                       modifier: Modifier = Modifier) {
    val levelKey = path.joinToString("/")
    val live = levelKey == nav.level
    val scroll = rememberScrollState()
    if (live) SideEffect { nav.scroll = scroll }
    Column(modifier.verticalScroll(scroll).padding(vertical = 8.dp)) {
        val parent = path.lastOrNull()?.let(::findMenuNode)
        if (parent != null) {
            MenuRowFrame(nav, levelKey, MENU_BACK, live && nav.highlight == MENU_BACK, enabled = true, onClick = { onActivate(MENU_BACK) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.back))
                Spacer(Modifier.width(12.dp))
                Text(parent.text ?: stringResource(parent.label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
        if (parent?.iconRow == true) {
            nodes.chunked(3).forEach { line ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    line.forEach { node ->
                        MenuIconCell(nav, levelKey, node, live && nav.highlight == node.id, node.enabled(state)) { onActivate(node.id) }
                    }
                }
            }
            return@Column
        }
        nodes.forEachIndexed { i, node ->
            if (i == divider && divider > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val enabled = node.enabled(state)
            val slider = node.slider
            if (slider != null) {
                MenuRowFrame(nav, levelKey, node.id, false, enabled, onClick = null) {
                    Text(node.text ?: stringResource(node.label), Modifier.width(80.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Slider(slider.value(state), { onSlider(node, it) }, Modifier.weight(1f).padding(end = 8.dp), enabled = enabled,
                        valueRange = slider.range)
                }
                return@forEachIndexed
            }
            val checked = node.checked?.invoke(state, view)
            MenuRowFrame(nav, levelKey, node.id, live && nav.highlight == node.id, enabled, onClick = { onActivate(node.id) },
                hoverOpen = if (!node.isLeaf) { { onActivate(node.id) } } else null, checked = checked) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { node.icon?.let { Icon(it(), null) } }
                Spacer(Modifier.width(12.dp))
                Text(node.text ?: stringResource(node.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                if (checked == true) Icon(Icons.Default.Check, null, Modifier.padding(horizontal = 4.dp))
                if (!node.isLeaf) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(end = 8.dp))
                // Pin button (spec section 7): filled while a shortcut. Not disabled at the cap: a tap says "Shortcuts full".
                else if (node.action != null) {
                    val pinned = node.id in pins
                    val full = !pinned && !Shortcuts.canAdd(pins, node.id)
                    IconButton(onClick = { onPin(node) }) {
                        Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            stringResource(when { pinned -> R.string.shortcut_remove; full -> R.string.shortcut_full; else -> R.string.shortcut_add }),
                            Modifier.size(20.dp), tint = if (pinned) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = if (full) 0.38f else 1f))
                    }
                }
            }
        }
    }
}

/** A 40 dp row that registers its bounds for slide-through hit tests. */
@Composable
private fun MenuRowFrame(nav: MenuNav, levelKey: String, key: String, highlighted: Boolean, enabled: Boolean, onClick: (() -> Unit)?,
                         hoverOpen: (() -> Unit)? = null, checked: Boolean? = null, content: @Composable () -> Unit) {
    val id = "$levelKey|$key"
    DisposableEffect(id) { onDispose { nav.rows.remove(id) } }
    val onHover by rememberUpdatedState(hoverOpen)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .onGloballyPositioned { nav.rows[id] = nav.place(it) }
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .then(if (checked != null) Modifier.semantics { selected = checked } else Modifier)
            .then(if (hoverOpen != null) Modifier.pointerInput(id) { hoverDwell { onHover?.invoke() } } else Modifier)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ) { content() }
    }
}

/** One leaf of an icon-row level: a 48 dp icon button that registers its bounds like a row. */
@Composable
private fun MenuIconCell(nav: MenuNav, levelKey: String, node: MenuNode, highlighted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val id = "$levelKey|${node.id}"
    DisposableEffect(id) { onDispose { nav.rows.remove(id) } }
    val label = node.text ?: stringResource(node.label)
    Box(
        Modifier
            .size(48.dp)
            .onGloballyPositioned { nav.rows[id] = nav.place(it) }
            .clip(CircleShape)
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        node.icon?.let { Icon(it(), null, tint = tint) }
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
