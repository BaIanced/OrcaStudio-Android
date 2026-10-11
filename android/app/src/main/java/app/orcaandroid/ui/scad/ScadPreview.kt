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
import app.orcaandroid.render.MeshPreviewView
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

    Box(modifier) {
        AndroidView(
            factory = { ctx -> MeshPreviewView(ctx).also { view = it } },
            update = { v ->
                v.setColors(background, grid, floatArrayOf(0.96f, 0.58f, 0.2f))
                v.setMesh(s.preview)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (s.previewing) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))

        val mesh = s.preview
        if (mesh == null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.previewing) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(stringResource(if (s.previewing) R.string.scad_preview_building else R.string.scad_preview_none),
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
        }
        if (mesh != null) Text(
            stringResource(R.string.scad_preview_help),
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
