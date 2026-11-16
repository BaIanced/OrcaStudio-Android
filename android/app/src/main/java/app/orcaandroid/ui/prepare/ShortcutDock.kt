package app.orcaandroid.ui.prepare

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.orcaandroid.R
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import kotlin.math.roundToInt

/** Pinned shortcuts are parked (user 2026-11-16: more anxiety than use, for now): no pin buttons, no dock. The code and stored pins stay. */
internal const val PINS_ENABLED = false

/**
 * The shortcut dock (docs/design/EDGE-MENU.md section 7), with the setting `edgeMenu` on: a row (a column when [vertical]) of
 * 48 dp round buttons for the menu nodes pinned from the edge panel or the hold menu ([Shortcuts]). A tap runs the node through
 * the same runner as the menus; a node that cannot run now stays, dimmed, and a tap says why. A long press on the dock opens edit
 * mode (an x on each, drag to reorder, Done): the only way a shortcut leaves the dock outside the menus. Empty, it shows a one-line hint.
 * Shortcuts are per context: Reshape shows its own (`reshape.*`) and the view ones, the plate everything else. The shape room keeps its toolbar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShortcutDock(state: UiState, vm: AppViewModel, view: PlateView?, vertical: Boolean, modifier: Modifier = Modifier) {
    if (!PINS_ENABLED) return
    val reshaping = state.workspace.tool is Tool.Sculpt
    if (state.workspace.focus != null && !reshaping) return
    val all = state.chrome.shortcuts
    val shown = Shortcuts.forContext(all, reshaping)
    val curShown by rememberUpdatedState(shown)
    val curState by rememberUpdatedState(state)
    val curView by rememberUpdatedState(view)
    val alpha = LocalOverlayAlpha.current
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val run = rememberMenuRunner(vm)
    var editing by remember { mutableStateOf(false) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragBy by remember { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { 52.dp.toPx() }
    LaunchedEffect(shown.isEmpty()) { if (shown.isEmpty()) editing = false }
    BackHandler(enabled = editing) { editing = false }

    if (shown.isEmpty()) {
        if (Shortcuts.clean(all).isEmpty()) {
            Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = alpha)) {
                Text(stringResource(R.string.shortcut_hint), Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        return
    }

    fun endDrag() {
        val id = dragId ?: return
        val rows = curShown
        val from = rows.indexOf(id)
        val to = (from + (dragBy / step).roundToInt()).coerceIn(0, rows.lastIndex)
        dragId = null
        dragBy = 0f
        if (from >= 0 && to != from) vm.reorderShortcuts(rows.toMutableList().also { it.removeAt(from); it.add(to, id) })
    }

    val buttons: @Composable () -> Unit = {
        shown.forEach { id ->
            key(id) {
                val node = findMenuNode(id)
                if (node != null) {
                    val reason = Shortcuts.blockedReason(state, id)
                    val label = node.text ?: stringResource(node.label)
                    ShortcutButton(
                        node = node, label = label, blocked = reason != null, checked = node.checked?.invoke(state, view) == true,
                        editing = editing, vertical = vertical, dragging = dragId == id, dragBy = dragBy,
                        onClick = {
                            val s = curState
                            val why = Shortcuts.blockedReason(s, id)
                            if (why != null) vm.toast(context.getString(why)) else run(node, s, curView)
                        },
                        onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); editing = true },
                        onRemove = { vm.removeShortcut(id) },
                        onDragStart = { dragId = id; dragBy = 0f },
                        onDrag = { dragBy += it },
                        onDragEnd = ::endDrag,
                    )
                }
            }
        }
    }

    val dockLabel = stringResource(R.string.shortcut_dock)
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (editing) AssistChip(onClick = { editing = false }, label = { Text(stringResource(R.string.done)) })
        Surface(
            Modifier
                .semantics { contentDescription = dockLabel }
                .pointerInput(editing) {
                    if (!editing) detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); editing = true })
                },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
            shadowElevation = 3.dp,
        ) {
            if (vertical) Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { buttons() }
            else Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { buttons() }
        }
    }
}

/** What a node without an icon shows in its button: the first letters of its first two words ("Auto orient" = AO), or of one word. */
private fun initials(label: String): String {
    val words = label.split(' ', '-').filter { it.isNotEmpty() }
    return if (words.size > 1) words.take(2).joinToString("") { it.take(1).uppercase() } else label.take(2)
}

/** One 48 dp round shortcut; in edit mode it shows an x and follows a drag along the dock's axis. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutButton(node: MenuNode, label: String, blocked: Boolean, checked: Boolean, editing: Boolean, vertical: Boolean,
                           dragging: Boolean, dragBy: Float, onClick: () -> Unit, onLongPress: () -> Unit, onRemove: () -> Unit,
                           onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (blocked) 0.38f else 1f)
    Box(
        Modifier
            .size(48.dp)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                if (dragging) { if (vertical) translationY = dragBy else translationX = dragBy; scaleX = 1.1f; scaleY = 1.1f }
            },
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                .then(
                    if (editing) Modifier.pointerInput(node.id) {
                        detectDragGestures(onDragStart = { dragStart() }, onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() }) { change, d ->
                            change.consume()
                            drag(if (vertical) d.y else d.x)
                        }
                    } else Modifier.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongPress),
                )
                .semantics { contentDescription = label; if (node.checked != null) selected = checked },
            contentAlignment = Alignment.Center,
        ) {
            val icon = node.icon
            if (icon != null) Icon(icon(), null, tint = tint)
            else Text(initials(label), color = tint, fontSize = 13.sp, maxLines = 1)
        }
        if (editing) {
            val removeLabel = stringResource(R.string.shortcut_remove_one, label)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = removeLabel },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}
