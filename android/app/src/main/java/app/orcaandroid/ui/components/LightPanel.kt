package app.orcaandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.LayoutLab
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Light direction on a lit ball seen from above the bed (right = +X, up = +Y): the dot is where the key light stands,
 * the rim is the lowest elevation and the centre straight overhead. Drag anywhere on the ball; the scene follows live.
 */
@Composable
fun LightPanel(lab: LayoutLab, onLab: (LayoutLab) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val loEl = LayoutLab.LIGHT_ELEVATIONS.start
    val hiEl = LayoutLab.LIGHT_ELEVATIONS.endInclusive
    val curLabState = androidx.compose.runtime.rememberUpdatedState(lab)
    val onLabState = androidx.compose.runtime.rememberUpdatedState(onLab)
    Surface(modifier, shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.light_direction), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
                val d = LayoutLab()
                IconButton(onClick = { onLab(lab.copy(lightAzimuth = d.lightAzimuth, lightElevation = d.lightElevation)) }) { Icon(Icons.Default.Restore, stringResource(R.string.reset)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.done)) }
            }
            val dot = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(140.dp).pointerInput(Unit) {
                // Keyed on Unit: a lab change mid-drag must not restart the gesture; the latest lab is read per event.
                fun set(p: Offset) {
                    val r = size.width / 2f
                    val dx = p.x - r
                    val dy = r - p.y
                    val t = (hypot(dx, dy) / r).coerceAtMost(1f)
                    val az = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                    val curLab = curLabState.value
                    onLabState.value(curLab.copy(lightAzimuth = az, lightElevation = hiEl - t * (hiEl - loEl)))
                }
                detectDragGestures(onDragStart = { set(it) }) { change, _ -> change.consume(); set(change.position) }
            }) {
                val r = size.width / 2f
                drawCircle(Brush.radialGradient(listOf(Color(0xFFEEEEEE), Color(0xFF444444)), center = center, radius = r))
                // Ring marks the lowest allowed elevation, where the light skims the bed.
                drawCircle(Color.White.copy(alpha = 0.5f), radius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                val t = (hiEl - lab.lightElevation) / (hiEl - loEl)
                val a = Math.toRadians(lab.lightAzimuth.toDouble())
                val p = Offset(center.x + (cos(a) * t * r).toFloat(), center.y - (sin(a) * t * r).toFloat())
                drawCircle(Color.White, radius = 11f, center = p)
                drawCircle(dot, radius = 8f, center = p)
            }
        }
    }
}
