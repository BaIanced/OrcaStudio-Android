package app.orcaandroid.ui.prepare

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.render.PlateView
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Screen
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState

/** Colour painting opens the paint shop: a workspace of its own instead of a card over the plate (user plan 2026-10-21). */
internal val UiState.inPaintShop get() = screen == Screen.PREPARE && (tool as? Tool.Paint)?.kind == "color"

/** The frame around the 3D view that says "you are in the paint shop" (the mockup's purple). */
private val SHOP_FRAME = Color(0xFF7F67BE)

/**
 * The paint shop's frame on the 3D view: a border, not a fill, so nothing purple shows while the view draws its
 * first frame. The plate view keeps its place in the layout ([Workspace]); only this frame, the bar and the dock come and go.
 */
internal fun shopFrame(shop: Boolean): Modifier = if (shop) Modifier.border(3.dp, SHOP_FRAME).padding(3.dp) else Modifier

/**
 * The paint shop's bar: title, undo, redo, the plate's view menu (camera presets, Realistic, layer lines, edges) and
 * Done. The tab bar, plate tools and Slice button stay hidden; Done or Back leaves.
 */
@Composable
internal fun PaintShopBar(state: UiState, vm: AppViewModel, view: PlateView?) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.paint_shop), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = SHOP_FRAME)
            ViewButtons(state, vm, view, rail = false)
            TextButton(onClick = { vm.scene.setTool(Tool.None) }) { Text(stringResource(R.string.done)) }
        }
    }
}

/** The colour paint options, docked at the bottom in portrait and beside the view ([side]) in landscape. */
@Composable
internal fun PaintShopDock(state: UiState, vm: AppViewModel, side: Boolean, modifier: Modifier) {
    val tool = state.tool as? Tool.Paint ?: return
    Surface(modifier, tonalElevation = 2.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorPaintOptions(state, vm, tool, layers = side)
        }
    }
}
