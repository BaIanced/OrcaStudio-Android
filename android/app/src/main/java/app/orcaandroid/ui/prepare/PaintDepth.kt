package app.orcaandroid.ui.prepare

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import java.util.Locale
import kotlin.math.roundToInt

/** Paint depth of the selected object (Object mode). Top / bottom: how many layers the colour goes in (`mmu_top_paint_layers`,
 *  0 = like the shell layers, -1 = all the way through). Sides: a wall width (`mmu_segmented_region_max_width`, 0 = all the way in). */
@Composable
internal fun PaintDepth(state: UiState, vm: AppViewModel) {
    if (state.selectedObject == null) return
    Text(stringResource(R.string.paint_depth), style = MaterialTheme.typography.labelMedium)
    DepthRow(state, vm, R.string.paint_depth_top, "mmu_top_paint_layers", false)
    DepthRow(state, vm, R.string.paint_depth_bottom, "mmu_bottom_paint_layers", false)
    DepthRow(state, vm, R.string.paint_depth_sides, "mmu_segmented_region_max_width", true)
}

@Composable
private fun DepthRow(state: UiState, vm: AppViewModel, label: Int, key: String, sides: Boolean) {
    val obj = state.selectedObject ?: return
    val stored = obj.settings[key]?.toFloatOrNull() ?: 0f
    fun set(v: Float) = vm.scene.setObjectSetting(obj.index, -1, key,
        if (v == 0f) null else if (sides) String.format(Locale.ROOT, "%.1f", v) else v.roundToInt().toString())
    var drag by remember(stored) { mutableFloatStateOf(stored) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 2.dp))
        FilterChip(stored == 0f, { set(0f) }, label = { Text(stringResource(if (sides) R.string.paint_depth_through else R.string.paint_depth_shells)) })
        val on = stored > 0f
        FilterChip(on, { if (!on) set(if (sides) 3f else 8f) }, label = {
            Text(when {
                !on -> stringResource(if (sides) R.string.paint_depth_limit else R.string.paint_depth_layers)
                sides -> stringResource(R.string.paint_depth_width, drag)
                else -> stringResource(R.string.paint_depth_layers_n, drag.roundToInt())
            })
        })
        if (!sides) FilterChip(stored < 0f, { set(-1f) }, label = { Text(stringResource(R.string.paint_depth_through)) })
    }
    if (stored > 0f) Slider(drag, { drag = it }, valueRange = if (sides) 0.4f..10f else 1f..40f, onValueChangeFinished = { set(drag) })
}
