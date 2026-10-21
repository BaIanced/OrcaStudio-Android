package app.orcaandroid.ui.prepare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.hexToRgb
import java.util.Locale

/** The 3D view's bounds in the window (set by the workspace): floating cards are dragged within it. */
internal object CanvasBounds {
    var rect: androidx.compose.ui.geometry.Rect? = null
}

/** Options of the active 3D tool, shown as a small floating card. */
@Composable
internal fun ToolPanel(state: UiState, vm: AppViewModel) {
    val tool = state.tool
    if (tool == Tool.None) return
    // The header drags the card around the canvas and folds it to the title, so it can stay out of the way while
    // painting (user 2026-10-19, like the Design window). Kept on screen while dragging.
    var drag by remember { mutableStateOf(Offset.Zero) }
    var folded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // Undragged left and bottom edge in the window (the card sits at the bottom of its column), last laid out size, last placement.
    val anchor = remember { FloatArray(2) { Float.NaN } }
    val laidOut = remember { IntArray(2) }
    val placed = remember { arrayOf(Offset.Zero) }
    // Keeps a w x h card inside the 3D view: the window also holds the nav rail and panel, which drew over it (WSA r324).
    // Too tall for the view: the title bar stays visible.
    fun clamped(o: Offset, w: Int, h: Int): Offset {
        val area = CanvasBounds.rect
        if (area == null || anchor[0].isNaN()) return o
        val minX = area.left - anchor[0]
        val minY = area.top + h - anchor[1]
        return Offset(o.x.coerceIn(minX, (area.right - w - anchor[0]).coerceAtLeast(minX)),
            o.y.coerceIn(minY, (area.bottom - anchor[1]).coerceAtLeast(minY)))
    }
    Surface(Modifier
        .onGloballyPositioned { val p = it.positionInWindow(); anchor[0] = p.x; anchor[1] = p.y + it.size.height }
        .layout { measurable, c ->
            val p = measurable.measure(c)
            // A height change (fold, unfold, other options) keeps the title bar where it is: the card is anchored at its bottom,
            // so it moves by the change (user r335: it folded toward its bottom edge).
            val shift = if (laidOut[1] > 0) p.height - laidOut[1] else 0
            laidOut[0] = p.width; laidOut[1] = p.height
            // Clamped with the size just measured, not last frame's: an unfold in the top-left corner went past it, then snapped back (r335).
            val at = clamped(Offset(drag.x, drag.y + shift), p.width, p.height)
            placed[0] = at
            layout(p.width, p.height) { p.place(at.x.roundToInt(), at.y.roundToInt()) }
        }
        .onGloballyPositioned { if (placed[0] != drag) drag = placed[0] }
        .widthIn(max = 360.dp),
        shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.pointerInput(Unit) {
                detectDragGestures { change, d ->
                    change.consume()
                    drag = clamped(drag + d, laidOut[0], laidOut[1])
                }
            }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DragIndicator, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(toolTitle(tool), Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { folded = !folded }, modifier = Modifier.size(32.dp)) {
                    Icon(if (folded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(if (folded) R.string.design_unfold else R.string.design_minimize))
                }
                IconButton(onClick = { vm.scene.setTool(Tool.None) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            if (!folded) when (tool) {
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
                FilterChip(tool.state == i + 1, { vm.scene.setTool(tool.copy(state = i + 1)) }, label = { Text("${i + 1}") },
                    leadingIcon = { ColorDot(f.color) })
            }
        } else if (tool.kind != "fuzzy") {
            FilterChip(tool.state == 1, { vm.scene.setTool(tool.copy(state = 1)) }, label = { Text(stringResource(R.string.enforce)) })
            FilterChip(tool.state == 2, { vm.scene.setTool(tool.copy(state = 2)) }, label = { Text(stringResource(R.string.block)) })
        } else {
            FilterChip(tool.state == 1, { vm.scene.setTool(tool.copy(state = 1)) }, label = { Text(stringResource(R.string.paint)) })
        }
        FilterChip(tool.state == 0, { vm.scene.setTool(tool.copy(state = 0)) }, label = { Text(stringResource(R.string.erase)) })
    }
    // The desktop's painting tools per kind (GLGizmoMmuSegmentation / FdmSupports / Seam / FuzzySkin).
    val shapes = when (tool.kind) {
        "color" -> listOf("sphere", "circle", "triangle", "fill", "regions", "height", "pick")
        "seam" -> listOf("sphere", "circle")
        "support" -> listOf("sphere", "circle", "triangle", "fill", "regions", "pick")
        else -> listOf("sphere", "circle", "triangle", "fill")
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shapes.forEach { s ->
            FilterChip(tool.shape == s, { vm.scene.setTool(tool.copy(shape = s, back = if (s == "pick") tool.shape.takeIf { it != "pick" } ?: tool.back else tool.back,
                edgeAngle = if (s == "regions" && tool.edgeAngle <= 0f) 30f else tool.edgeAngle)) },
                label = { Text(stringResource(paintShapeLabel(s))) })
        }
    }
    val brush = tool.shape == "sphere" || tool.shape == "circle"
    if (brush) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.brush_size, tool.radius), style = MaterialTheme.typography.bodySmall)
        Slider(tool.radius, { vm.scene.setTool(tool.copy(radius = it)) }, valueRange = 0.5f..15f, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
    if (tool.shape == "height") Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.paint_height_range, tool.height), style = MaterialTheme.typography.bodySmall)
        Slider(tool.height, { vm.scene.setTool(tool.copy(height = it)) }, valueRange = 0.1f..8f, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
    if (tool.shape == "pick") Text(stringResource(R.string.paint_pick_hint), style = MaterialTheme.typography.bodySmall)
    // Regions view: the model split at its creases (outlined); a tap fills one region over any colour.
    if (tool.shape == "regions") Row(verticalAlignment = Alignment.CenterVertically) {
        val angle = tool.edgeAngle.takeIf { it > 0f } ?: 30f
        Text(stringResource(R.string.paint_crease_angle, angle.roundToInt()), style = MaterialTheme.typography.bodySmall)
        Slider(angle, { vm.scene.setTool(tool.copy(edgeAngle = it)) }, valueRange = 5f..89f, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
    // One-tap actions on the whole object. Clean specks = the desktop's gap fill: painted patches under the area take the
    // state that borders them most. Colour each part: every separate shell gets its own filament, largest first.
    var gapArea by rememberSaveable { mutableFloatStateOf(1f) }
    if (tool.kind == "color" || tool.kind == "support") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { vm.scene.paintGapFill(tool.kind, gapArea) }) { Text(stringResource(R.string.paint_clean_specks)) }
            Text(stringResource(R.string.paint_gap_area, gapArea), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            Slider(gapArea, { gapArea = it }, valueRange = 0.1f..5f, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        if (tool.kind == "color" && state.filaments.size > 1)
            FilledTonalButton(onClick = { vm.scene.paintByPart() }) { Text(stringResource(R.string.paint_by_part)) }
    }
    // Edge detection (brush) / smart fill angle (fill): the angle stays remembered while it is off.
    var edgeAngle by rememberSaveable { mutableFloatStateOf(30f) }
    if (brush || tool.shape == "fill") Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(tool.edgeAngle > 0f, { vm.scene.setTool(tool.copy(edgeAngle = if (tool.edgeAngle > 0f) 0f else edgeAngle)) },
            label = { Text(stringResource(R.string.paint_stop_at_edges)) })
        if (tool.edgeAngle > 0f) {
            Text(stringResource(R.string.paint_edge_angle, tool.edgeAngle.roundToInt()), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp))
            Slider(tool.edgeAngle, { edgeAngle = it; vm.scene.setTool(tool.copy(edgeAngle = it)) }, valueRange = 5f..89f,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
    }
    // Fill over colours: the fill ignores existing colours (the desktop's stops at them) and stops only at edges / the guard plane.
    if (tool.shape == "fill") FilterChip(tool.overColours, { vm.scene.setTool(tool.copy(overColours = !tool.overColours)) },
        label = { Text(stringResource(R.string.paint_over_colours)) })
    // Guard plane: painting stops at a horizontal plane (drawn like the cut plane); a stroke keeps to the side it starts on.
    val inst = state.selectedObject?.instances?.getOrNull(state.selection?.instance ?: 0)
    if (tool.shape != "pick" && inst != null) Row(verticalAlignment = Alignment.CenterVertically) {
        val range = inst.min.z..(inst.min.z + inst.size.z)
        FilterChip(tool.guardOn, {
            vm.scene.setTool(tool.copy(guardOn = !tool.guardOn, guardZ = if (tool.guardZ in range) tool.guardZ else inst.min.z + inst.size.z / 2))
        }, label = { Text(stringResource(R.string.paint_guard)) })
        if (tool.guardOn) {
            Text(stringResource(R.string.paint_guard_z, tool.guardZ), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            Slider(tool.guardZ.coerceIn(range), { vm.scene.setTool(tool.copy(guardZ = it)) }, valueRange = range,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
    }
    // Stroke lock (brush): the stroke keeps to a screen axis through its first point (straight lines).
    if (brush) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(tool.lock == "v", { vm.scene.setTool(tool.copy(lock = if (tool.lock == "v") "" else "v")) }, label = { Text(stringResource(R.string.paint_lock_vertical)) })
        FilterChip(tool.lock == "h", { vm.scene.setTool(tool.copy(lock = if (tool.lock == "h") "" else "h")) }, label = { Text(stringResource(R.string.paint_lock_horizontal)) })
    }
    // Support painting's overhang angle (the desktop's): "Highlight overhangs" tints the faces overhanging by more than it,
    // "On overhangs only" (brush) paints only those. One shared angle, remembered while both are off.
    var overhang by rememberSaveable { mutableFloatStateOf(40f) }
    if (tool.kind == "support") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tool.highlight > 0f, { vm.scene.setTool(tool.copy(highlight = if (tool.highlight > 0f) 0f else overhang)) },
                label = { Text(stringResource(R.string.paint_highlight_overhangs)) })
            if (brush) FilterChip(tool.overhang > 0f, { vm.scene.setTool(tool.copy(overhang = if (tool.overhang > 0f) 0f else overhang)) },
                label = { Text(stringResource(R.string.paint_overhangs_only)) })
        }
        if (tool.highlight > 0f || (brush && tool.overhang > 0f)) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.paint_overhang_angle, overhang.roundToInt()), style = MaterialTheme.typography.bodySmall)
            Slider(overhang, {
                overhang = it
                vm.scene.setTool(tool.copy(overhang = if (tool.overhang > 0f) it else 0f, highlight = if (tool.highlight > 0f) it else 0f))
            }, valueRange = 1f..90f, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
    }
    // Remap filaments (the desktop's tool): painted filament i becomes filament remap[i] on the whole object.
    var remapOpen by rememberSaveable { mutableStateOf(false) }
    if (tool.kind == "color" && state.filaments.size > 1) {
        val n = state.filaments.size
        var remap by remember(n) { mutableStateOf(List(n) { it }) }
        FilterChip(remapOpen, { remapOpen = !remapOpen }, label = { Text(stringResource(R.string.paint_remap)) })
        if (remapOpen) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                state.filaments.forEachIndexed { i, f ->
                    var menu by remember { mutableStateOf(false) }
                    ColorDot(f.color)
                    Text("${i + 1} →", style = MaterialTheme.typography.bodySmall)
                    Box {
                        FilterChip(remap[i] != i, { menu = true }, label = { Text("${remap[i] + 1}") },
                            leadingIcon = { ColorDot(state.filaments[remap[i]].color) })
                        DropdownMenu(menu, { menu = false }) {
                            state.filaments.forEachIndexed { j, g ->
                                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { ColorDot(g.color); Text(" ${j + 1}") } },
                                    onClick = { remap = remap.toMutableList().also { it[i] = j }; menu = false })
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.scene.paintRemap(remap); remap = List(n) { it } }, enabled = remap.withIndex().any { it.index != it.value }) {
                    Text(stringResource(R.string.paint_remap_apply))
                }
                TextButton(onClick = { remap = List(n) { it } }) { Text(stringResource(R.string.paint_remap_reset)) }
            }
        }
    }
    TextButton(onClick = { vm.scene.paintClear(tool.kind) }) { Text(stringResource(R.string.clear_painting)) }
}

private fun paintShapeLabel(shape: String) = when (shape) {
    "circle" -> R.string.paint_shape_circle; "triangle" -> R.string.paint_shape_triangle; "fill" -> R.string.paint_shape_fill
    "height" -> R.string.paint_shape_height; "pick" -> R.string.paint_shape_pick; "regions" -> R.string.paint_shape_regions; else -> R.string.paint_shape_sphere
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
    Slider(tool.z.coerceIn(lo, hi), { vm.scene.setTool(Tool.Cut(it)) }, valueRange = lo..hi)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(keepUpper, { keepUpper = it }); Text(stringResource(R.string.keep_upper))
        Checkbox(keepLower, { keepLower = it }); Text(stringResource(R.string.keep_lower))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(flip, { flip = it }, enabled = keepUpper); Text(stringResource(R.string.flip_upper))
    }
    FilledTonalButton(onClick = { vm.scene.cut(tool.z, keepUpper, keepLower, flip) }, enabled = keepUpper || keepLower, modifier = Modifier.fillMaxWidth()) {
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
    if (pts.isNotEmpty()) TextButton(onClick = { vm.scene.setTool(Tool.Measure) }) { Text(stringResource(R.string.reset)) }
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
                    vm.scene.layerAdjust(z, delta, profile.height / 10f)
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
        FilledTonalButton(onClick = { vm.scene.layerAdaptive(quality) }) { Text(stringResource(R.string.adaptive)) }
        OutlinedButton(onClick = { vm.scene.layerSmooth(5, true) }) { Text(stringResource(R.string.smooth)) }
        TextButton(onClick = vm.scene::layerReset) { Text(stringResource(R.string.reset)) }
    }
}
