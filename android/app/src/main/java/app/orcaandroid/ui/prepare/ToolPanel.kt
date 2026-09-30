package app.orcaandroid.ui.prepare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.hexToRgb
import java.util.Locale

/** Options of the active 3D tool, shown as a small floating card. */
@Composable
internal fun ToolPanel(state: UiState, vm: AppViewModel) {
    val tool = state.tool
    if (tool == Tool.None) return
    Surface(Modifier.widthIn(max = 360.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(toolTitle(tool), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { vm.setTool(Tool.None) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            when (tool) {
                is Tool.Paint -> PaintOptions(state, vm, tool)
                is Tool.Cut -> CutOptions(state, vm, tool)
                Tool.Measure -> MeasureOptions(state, vm)
                Tool.LayOnFace -> Text(stringResource(R.string.lay_on_face_hint), style = MaterialTheme.typography.bodySmall)
                Tool.LayerHeight -> LayerHeightOptions(state, vm)
                Tool.None -> {}
            }
        }
    }
}

@Composable
private fun toolTitle(tool: Tool) = stringResource(
    when (tool) {
        is Tool.Paint -> when (tool.kind) {
            "support" -> R.string.paint_supports; "seam" -> R.string.paint_seam; "fuzzy" -> R.string.paint_fuzzy; else -> R.string.paint_color
        }
        is Tool.Cut -> R.string.cut
        Tool.Measure -> R.string.measure
        Tool.LayOnFace -> R.string.lay_on_face
        Tool.LayerHeight -> R.string.variable_layer_height
        Tool.None -> R.string.close
    }
)

@Composable
private fun PaintOptions(state: UiState, vm: AppViewModel, tool: Tool.Paint) {
    Text(stringResource(R.string.paint_hint), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tool.kind == "color") {
            state.filaments.forEachIndexed { i, f ->
                FilterChip(tool.state == i + 1, { vm.setTool(tool.copy(state = i + 1)) }, label = { Text("${i + 1}") },
                    leadingIcon = { ColorDot(f.color) })
            }
        } else if (tool.kind != "fuzzy") {
            FilterChip(tool.state == 1, { vm.setTool(tool.copy(state = 1)) }, label = { Text(stringResource(R.string.enforce)) })
            FilterChip(tool.state == 2, { vm.setTool(tool.copy(state = 2)) }, label = { Text(stringResource(R.string.block)) })
        } else {
            FilterChip(tool.state == 1, { vm.setTool(tool.copy(state = 1)) }, label = { Text(stringResource(R.string.paint)) })
        }
        FilterChip(tool.state == 0, { vm.setTool(tool.copy(state = 0)) }, label = { Text(stringResource(R.string.erase)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.brush_size, tool.radius), style = MaterialTheme.typography.bodySmall)
        Slider(tool.radius, { vm.setTool(tool.copy(radius = it)) }, valueRange = 0.5f..15f, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
    TextButton(onClick = { vm.paintClear(tool.kind) }) { Text(stringResource(R.string.clear_painting)) }
}

@Composable
fun ColorDot(hex: String?, size: Int = 14) {
    val c = hexToRgb(hex ?: "#FF7F27")
    Box(Modifier.size(size.dp).background(Color(c[0], c[1], c[2]), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun CutOptions(state: UiState, vm: AppViewModel, tool: Tool.Cut) {
    val inst = state.selectedObject?.instances?.getOrNull(state.selection?.instance ?: 0) ?: return
    var keepUpper by remember { mutableStateOf(true) }
    var keepLower by remember { mutableStateOf(true) }
    var flip by remember { mutableStateOf(false) }
    val lo = inst.min.z
    val hi = inst.min.z + inst.size.z
    Text("Z = %.2f mm".format(Locale.ROOT, tool.z), style = MaterialTheme.typography.bodyMedium)
    Slider(tool.z.coerceIn(lo, hi), { vm.setTool(Tool.Cut(it)) }, valueRange = lo..hi)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(keepUpper, { keepUpper = it }); Text(stringResource(R.string.keep_upper))
        Checkbox(keepLower, { keepLower = it }); Text(stringResource(R.string.keep_lower))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(flip, { flip = it }, enabled = keepUpper); Text(stringResource(R.string.flip_upper))
    }
    FilledTonalButton(onClick = { vm.cut(tool.z, keepUpper, keepLower, flip) }, enabled = keepUpper || keepLower, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.perform_cut))
    }
}

@Composable
private fun MeasureOptions(state: UiState, vm: AppViewModel) {
    val pts = state.measure
    when (pts.size) {
        0 -> Text(stringResource(R.string.measure_hint_first), style = MaterialTheme.typography.bodySmall)
        1 -> Text(stringResource(R.string.measure_hint_second), style = MaterialTheme.typography.bodySmall)
        else -> {
            val d = pts[1] - pts[0]
            Text("%.2f mm".format(Locale.ROOT, d.length()), style = MaterialTheme.typography.headlineSmall)
            Text("ΔX %.2f  ΔY %.2f  ΔZ %.2f".format(Locale.ROOT, kotlin.math.abs(d.x), kotlin.math.abs(d.y), kotlin.math.abs(d.z)),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (pts.isNotEmpty()) TextButton(onClick = { vm.setTool(Tool.Measure) }) { Text(stringResource(R.string.reset)) }
}

/**
 * Variable layer height: the object's layer-height curve (height up, layer height right). Tapping
 * the graph raises (right half) or lowers (left half) the layer height around that height.
 */
@Composable
private fun LayerHeightOptions(state: UiState, vm: AppViewModel) {
    val profile = state.layerProfile
    var quality by remember { mutableFloatStateOf(0.5f) }
    if (profile == null) {
        Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val maxH = profile.max.coerceAtLeast(0.05f)
    Text(stringResource(R.string.layer_height_hint), style = MaterialTheme.typography.bodySmall)
    Canvas(
        Modifier.fillMaxWidth().height(180.dp)
            .border(1.dp, outline, RoundedCornerShape(4.dp))
            .pointerInput(profile) {
                detectTapGestures { p ->
                    val z = (1f - p.y / size.height) * profile.height
                    val delta = if (p.x > size.width / 2) 0.02f else -0.02f
                    vm.layerAdjust(z, delta, profile.height / 10f)
                }
            }
    ) {
        val pts = profile.profile
        // Min / max guide lines
        listOf(profile.min, profile.max).forEach { h ->
            val x = h / maxH * size.width * 0.9f
            drawLine(outline, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        if (pts.size >= 4) {
            val path = Path()
            var i = 0
            while (i + 1 < pts.size) {
                val x = pts[i + 1] / maxH * size.width * 0.9f
                val y = size.height - pts[i] / profile.height * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
            drawPath(path, primary, style = Stroke(3f))
        }
    }
    Text("%.2f – %.2f mm".format(Locale.ROOT, profile.min, profile.max), style = MaterialTheme.typography.labelSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.quality), style = MaterialTheme.typography.bodySmall)
        Slider(quality, { quality = it }, Modifier.weight(1f).padding(horizontal = 8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(onClick = { vm.layerAdaptive(quality) }) { Text(stringResource(R.string.adaptive)) }
        OutlinedButton(onClick = { vm.layerSmooth(5, true) }) { Text(stringResource(R.string.smooth)) }
        TextButton(onClick = vm::layerReset) { Text(stringResource(R.string.reset)) }
    }
}
