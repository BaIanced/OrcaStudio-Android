package app.orcaandroid.ui.prepare

import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import kotlin.math.roundToInt

/** The hold menu's drill-down and slide tracking; rows in the popup window's pixels. */
internal class HoldNav : MenuNav() {
    /** The popup's own view (its window) while it is up. */
    var window: View? = null
    /** Where the long press went down (screen pixels): the landing guard's spot until the first level is on screen. */
    var press: Offset? = null
    /** Activates the row a slide lifted on (set while the menu is composed). */
    var onLift: ((String) -> Unit)? = null

    /** A screen point in the popup window's pixels, or null while it is not up. */
    fun toMenu(screen: Offset): Offset? = window?.takeIf { it.isAttachedToWindow }?.let { v ->
        val at = IntArray(2).also(v::getLocationOnScreen)
        Offset(screen.x - at[0], screen.y - at[1])
    }

    override fun landPoint(): Offset? = press?.let(::toMenu) ?: landAt
    override fun disarm(at: Offset?) { press = null; super.disarm(at) }
}

/**
 * The finger of the long press that opened the hold menu. The 3D view keeps it after the long press (the menu window never
 * gets these touches), so Viewport hands them over (`PlateListener.onLongPressTouch`).
 */
internal object HoldTouch {
    val nav = HoldNav()

    /** A long press or right click is about to open the menu: a new gesture, at the top level. */
    fun reset() {
        nav.sliding = false
        nav.highlight = null
        nav.finger = null
        nav.press = null
        nav.path = emptyList()
    }

    /** The long press's finger at pixel ([x], [y]) of [view]; [held] false = it lifted there (x < 0: the gesture was cancelled). */
    fun touch(view: View, x: Float, y: Float, held: Boolean) {
        val screen = if (x < 0f) null else IntArray(2).also(view::getLocationOnScreen).let { Offset(it[0] + x, it[1] + y) }
        val p = screen?.let(nav::toMenu)
        if (held) {
            // The first touch: the menu opens under the finger (its pick runs off the UI thread), so whatever lies under
            // the press point once the menu is up is not chosen.
            if (!nav.sliding) { nav.disarm(null); nav.press = screen; nav.sliding = true }
            nav.slideTo(p)
            return
        }
        if (!nav.sliding) return
        nav.slideTo(p)
        val key = nav.highlight
        val armed = nav.armed
        nav.sliding = false
        nav.highlight = null
        nav.finger = null
        // Lifted on a row it chose: activate it. Anywhere else the menu stays open, so a tap can pick.
        if (key != null && armed) nav.onLift?.invoke(key)
    }
}

/**
 * The hold menu with the setting `edgeMenu` on (docs/design/EDGE-MENU.md section 6): the menu tree in a popup at the finger,
 * opening toward the plate's centre, starting with the hit's own group ([holdMenuTop]). Slide-through from the long press,
 * drill-down with a back row, the align icons inside Selection > Align. Opened and closed as the old menu
 * (`workspace.contextMenu`, view pixels).
 */
@Composable
internal fun HoldMenu(state: UiState, vm: AppViewModel, view: PlateView?) {
    val nav = HoldTouch.nav
    val run = rememberMenuRunner(vm)
    val haptic = LocalHapticFeedback.current
    val cur by rememberUpdatedState(state)
    val curView by rememberUpdatedState(view)
    val at = state.workspace.contextMenu
    val top = holdMenuTop(state)
    val (valid, levelRows) = menuLevel(state, nav.path, top.rows)
    // A branch that went away (the selection changed) closes with its children.
    if (valid != nav.path) SideEffect { nav.path = valid }

    fun activate(key: String) = nav.activate(key, menuLevel(cur, nav.path, holdMenuTop(cur).rows).second, cur) { node ->
        if (!node.keepsOpen()) vm.scene.closeContextMenu()
        run(node, cur, curView)
    }
    DisposableEffect(nav) {
        nav.onLift = { activate(it) }
        onDispose { nav.onLift = null }
    }
    // Closed: the next one starts at the top level.
    LaunchedEffect(at == null) { if (at == null) { nav.path = emptyList(); nav.highlight = null } }
    LaunchedEffect(nav.highlight) { if (nav.sliding && nav.highlight != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    SlideDwell(nav, levelRows) { activate(it) }
    SlideAutoScroll(nav) { nav.slideTo(it) }
    if (at == null) return

    // The overlay is inset by 8 dp from the view whose pixels [at] are in.
    val inset = with(LocalDensity.current) { 8.dp.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = minOf(232.dp, maxWidth)
        val maxHeight = maxHeight
        val position = remember(at, inset) { AtFinger(at.first.roundToInt() - inset, at.second.roundToInt() - inset) }
        Popup(popupPositionProvider = position, onDismissRequest = vm.scene::closeContextMenu, properties = PopupProperties(focusable = true)) {
            val window = LocalView.current
            DisposableEffect(window) {
                nav.window = window
                onDispose { if (nav.window === window) nav.window = null }
            }
            Surface(
                Modifier.width(width).heightIn(max = maxHeight).onGloballyPositioned { nav.panel = nav.place(it) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = state.chrome.overlayAlpha),
                shadowElevation = 6.dp,
            ) {
                AnimatedContent(targetState = valid, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) }, label = "hold level") { path ->
                    MenuLevel(state, view, nav, path, menuLevel(state, path, top.rows).second, divider = if (path.isEmpty()) top.group else 0,
                        onActivate = ::activate, onSlider = { node, v -> node.slider?.set?.invoke(MenuEnv(vm, cur, curView), v) },
                        // The menu is a popup window over the snackbar: closing it leaves the Undo reachable.
                        pins = state.chrome.shortcuts, onPin = { vm.toggleShortcut(it.id); vm.scene.closeContextMenu() },
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * The popup's corner at the finger ([x], [y] in the anchor's pixels), opening toward the anchor's centre (left of the finger in
 * its right half, above it in its lower half), kept inside the anchor.
 */
private class AtFinger(val x: Int, val y: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val fx = anchorBounds.left + x
        val fy = anchorBounds.top + y
        val w = popupContentSize.width
        val h = popupContentSize.height
        val left = if (fx > anchorBounds.center.x) fx - w else fx
        val top = if (fy > anchorBounds.center.y) fy - h else fy
        return IntOffset(
            left.coerceIn(anchorBounds.left, maxOf(anchorBounds.left, anchorBounds.right - w)),
            top.coerceIn(anchorBounds.top, maxOf(anchorBounds.top, anchorBounds.bottom - h)),
        )
    }
}
