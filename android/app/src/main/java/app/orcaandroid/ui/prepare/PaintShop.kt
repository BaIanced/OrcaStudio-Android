package app.orcaandroid.ui.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Screen
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState

/** Colour painting opens the paint shop: a workspace of its own instead of a card over the plate (user plan 2026-10-21). */
internal val UiState.inPaintShop get() = screen == Screen.PREPARE && (tool as? Tool.Paint)?.kind == "color"

/** The frame around the 3D view that says "you are in the paint shop" (the mockup's purple). */
private val SHOP_FRAME = Color(0xFF7F67BE)

/**
 * The paint shop: a bar (title, undo, redo, Done), the framed 3D view and the colour paint options, docked at the bottom in
 * portrait and beside the view in landscape. The tab bar, plate tools and Slice button stay hidden; Done or Back leaves.
 */
@Composable
internal fun PaintShop(state: UiState, vm: AppViewModel, viewport: @Composable (Modifier) -> Unit) {
    val tool = state.tool as? Tool.Paint ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth > maxHeight && maxWidth >= 600.dp
        val dockMax = maxHeight * 0.45f
        val framed: @Composable (Modifier) -> Unit = { m -> viewport(m.background(SHOP_FRAME).padding(3.dp)) }
        val options: @Composable (Modifier) -> Unit = { m ->
            Surface(m, tonalElevation = 2.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorPaintOptions(state, vm, tool, layers = side)
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.paint_shop), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = SHOP_FRAME)
                    IconButton(onClick = vm.scene::undo, enabled = state.scene.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = vm.scene::redo, enabled = state.scene.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                    TextButton(onClick = { vm.scene.setTool(Tool.None) }) { Text(stringResource(R.string.done)) }
                }
            }
            if (side) Row(Modifier.weight(1f).fillMaxWidth()) {
                framed(Modifier.weight(1f).fillMaxHeight())
                options(Modifier.width(360.dp).fillMaxHeight())
            } else {
                framed(Modifier.weight(1f).fillMaxWidth())
                options(Modifier.fillMaxWidth().heightIn(max = dockMax))
            }
        }
    }
}
