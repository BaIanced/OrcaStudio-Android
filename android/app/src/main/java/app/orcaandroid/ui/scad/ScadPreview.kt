package app.orcaandroid.ui.scad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orcaandroid.R
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.MeshPreviewView
import app.orcaandroid.render.PreviewBox
import app.orcaandroid.render.PreviewPickListener
import app.orcaandroid.scad.D3
import app.orcaandroid.ui.ShapeController
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.ScadEditorState

/**
 * The editor's live 3D preview of the design: size readout, a warning when it doesn't fit the
 * printer, refresh (F5) and re-frame buttons. The plate is only changed by "Add to plate".
 */
@Composable
fun ScadPreviewPane(s: ScadEditorState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val ui by vm.state.collectAsState()
    var view by remember { mutableStateOf<MeshPreviewView?>(null) }
    val scheme = MaterialTheme.colorScheme
    val background = scheme.surfaceContainerHighest.rgb()
    val grid = scheme.outlineVariant.rgb()

    // Shape builder: outlines of the selection and the holes; taps pick, drags move.
    val shapes = s.shapes
    val boxes = remember(shapes, s.selected) {
        if (shapes == null) emptyList() else {
            val ids = shapes.shapes.map { it.id } + s.selected.filter { id -> shapes.shapes.none { it.id == id } }
            ids.mapNotNull { id ->
                val b = ShapeController.worldBounds(shapes, id) ?: return@mapNotNull null
                // Handles only for a lone selection.
                val (scalable, pivot) = if (s.selected == listOf(id)) ShapeController.handles(shapes, id) else false to null
                PreviewBox(id, Vec3(b.min.x.toFloat(), b.min.y.toFloat(), b.min.z.toFloat()),
                    Vec3(b.max.x.toFloat(), b.max.y.toFloat(), b.max.z.toFloat()), id in s.selected, shapes.find(id)?.hole == true,
                    scalable, pivot?.let { Vec3(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) })
            }
        }
    }
    val listener = remember(vm) {
        object : PreviewPickListener {
            override fun onPick(id: Int?, add: Boolean) = vm.shapes.tap(id, add)
            override fun onMove(dx: Float, dy: Float) = vm.shapes.move(D3(dx.toDouble(), dy.toDouble(), 0.0))
            override fun onResize(axis: Int, mm: Float) = vm.shapes.resize(axis, mm.toDouble())
            override fun onRotate(degrees: Float) = vm.shapes.turn(degrees.toDouble())
            override fun onLift(dz: Float) = vm.shapes.move(D3(0.0, 0.0, dz.toDouble()))
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx -> MeshPreviewView(ctx).also { view = it } },
            update = { v ->
                v.setColors(background, grid, floatArrayOf(0.96f, 0.58f, 0.2f))
                v.setMesh(s.preview)
                v.setBoxes(boxes)
                v.pickListener = if (shapes != null) listener else null
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (s.previewing) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))

        val mesh = s.preview
        if (mesh == null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.previewing) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(stringResource(when {
                    s.previewing -> R.string.scad_preview_building
                    shapes != null && shapes.shapes.isEmpty() -> R.string.shape_empty
                    else -> R.string.scad_preview_none
                }),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            // Size and fit, top left.
            val size = mesh.size
            val f = { v: Float -> if (v >= 100f) "%.0f".format(v) else "%.1f".format(v) }
            val plateW = ui.scene.plateWidth
            val plateD = ui.scene.plateDepth
            val maxH = ui.setup?.maxHeight ?: 0f
            val fitsFlat = (size.x <= plateW && size.y <= plateD) || (size.x <= plateD && size.y <= plateW)
            val warning = when {
                !fitsFlat -> stringResource(R.string.scad_too_big, f(plateW), f(plateD))
                maxH > 0f && size.z > maxH -> stringResource(R.string.scad_too_tall, f(maxH))
                else -> null
            }
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(stringResource(R.string.scad_size, f(size.x), f(size.y), f(size.z)))
                if (warning != null) Chip(warning, warn = true)
                if (s.previewStale && !s.previewing) Chip(stringResource(R.string.scad_preview_stale), warn = true)
                if (s.draftPreview) Chip(stringResource(R.string.scad_draft_preview))
            }
        }

        Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = scheme.surface.copy(alpha = 0.85f))
            FilledTonalIconButton(onClick = { vm.scad.preview() }, enabled = !s.previewing, colors = colors, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, stringResource(R.string.scad_preview_refresh), Modifier.size(20.dp))
            }
            if (mesh != null) FilledTonalIconButton(onClick = { view?.reframe() }, colors = colors, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.CenterFocusStrong, stringResource(R.string.scad_preview_frame), Modifier.size(20.dp))
            }
            if (mesh != null) FilledTonalIconButton(onClick = { vm.setScadOnPlate(true) }, colors = colors, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Visibility, stringResource(R.string.scad_on_plate), Modifier.size(20.dp))
            }
        }
        if (mesh != null) Text(
            stringResource(if (shapes != null) R.string.shape_preview_help else R.string.scad_preview_help),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Chip(text: String, warn: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (warn) scheme.errorContainer.copy(alpha = 0.92f) else scheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (warn) Icon(Icons.Default.WarningAmber, null, Modifier.size(14.dp).padding(end = 2.dp), tint = scheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.labelMedium, color = if (warn) scheme.onErrorContainer else scheme.onSurface)
        }
    }
}

private fun Color.rgb() = floatArrayOf(red, green, blue)
