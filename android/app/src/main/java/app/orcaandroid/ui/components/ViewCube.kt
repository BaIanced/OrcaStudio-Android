package app.orcaandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orcaandroid.R
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.Camera
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Orientation cube in a corner of the 3D view, like Fusion's / Onshape's view cube. It turns with [camera]; a tap on a
 * face, edge or corner (each face is split 3 x 3: centre = face, sides = edges, corners = corners) swings the camera to
 * look from there, and a drag on the cube orbits the view. Drawn in Compose with its own projection, not in GL.
 */
@Composable
fun ViewCube(camera: Camera, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    // Yaw and pitch as last drawn; polled each frame (the 3D view renders continuously and has no change callback).
    var angles by remember { mutableStateOf(0f to 0f) }
    LaunchedEffect(camera) {
        while (true) withFrameNanos {
            val s = camera.state()
            if (s[0] != angles.first || s[1] != angles.second) angles = s[0] to s[1]
        }
    }
    var pressed by remember { mutableStateOf<Cell?>(null) }
    val scope = rememberCoroutineScope()
    var swing by remember { mutableStateOf<Job?>(null) }
    val scheme = MaterialTheme.colorScheme
    val face = scheme.surfaceVariant
    val hot = scheme.primary
    val edge = scheme.outline
    val text = scheme.onSurfaceVariant
    val names = FACES.map { androidx.compose.ui.res.stringResource(it.label).uppercase() }

    Canvas(modifier.size(size).pointerInput(camera) {
        awaitEachGesture {
            val down = awaitFirstDown()
            swing?.cancel()
            val (yaw, pitch) = camera.state().let { it[0] to it[1] }
            val start = hit(cells(yaw, pitch, this.size.width / 2f), down.position)
            pressed = start
            var dragging = false
            var total = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val d = change.positionChange()
                total += d
                if (!dragging && total.getDistance() > viewConfiguration.touchSlop) { dragging = true; pressed = null }
                if (dragging) camera.orbit(d.x * ORBIT_DEG_PER_PX, d.y * ORBIT_DEG_PER_PX)
                change.consume()
            }
            pressed = null
            if (!dragging && start != null) swing = scope.launch { swingTo(camera, start.look) }
        }
    }) {
        val half = this.size.width / 2f
        val (yaw, pitch) = angles
        val eye = basis(yaw, pitch).third
        val outline = Stroke(1.dp.toPx())
        fun quadPath(q: List<Offset>) = Path().apply { moveTo(q[0].x, q[0].y); for (i in 1..3) lineTo(q[i].x, q[i].y); close() }
        // Faces lit by how squarely they face the eye, so the cube reads as solid.
        for (f in FACES) {
            val corners = faceQuad(f, yaw, pitch, half) ?: continue
            drawPath(quadPath(corners), androidx.compose.ui.graphics.lerp(face, Color.White, 0.04f + 0.12f * f.n.dot(eye)))
        }
        // The pressed face, edge or corner: its cells on every visible face.
        pressed?.let { p -> for (c in cells(yaw, pitch, half)) if (c.look == p.look) drawPath(quadPath(c.quad), hot) }
        // Face outlines and labels: the label is mapped onto the face's projected square, so it tilts and turns with it.
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = text.toArgb(); textAlign = android.graphics.Paint.Align.CENTER; textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        for ((f, name) in FACES.zip(names)) {
            val corners = faceQuad(f, yaw, pitch, half) ?: continue
            drawPath(quadPath(corners), edge, style = outline)
            val m = android.graphics.Matrix()
            // Unit square 100 x 100 (label baseline near the middle) onto the face corners, in the same order.
            val src = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
            val dst = FloatArray(8).also { for (i in 0..3) { it[2 * i] = corners[i].x; it[2 * i + 1] = corners[i].y } }
            if (!m.setPolyToPoly(src, 0, dst, 0, 4)) continue
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save(); nc.concat(m)
                // Shrinks long names to fit the face (the square is 100 wide).
                paint.textSize = 22f
                paint.textSize = 22f * minOf(1f, 88f / paint.measureText(name))
                nc.drawText(name, 50f, 58f, paint)
                nc.restore()
            }
        }
    }
}

/** Degrees the view turns per pixel dragged on the cube (the 3D view uses 0.3; the cube is small). */
private const val ORBIT_DEG_PER_PX = 0.5f

/** Half the width of a face's centre cell, as a fraction of half the face: the rest is the edge and corner band. */
private const val CENTRE = 0.5f

/** Cube half size as a fraction of half the canvas, leaving room for a corner seen diagonally (sqrt 3). */
private const val CUBE = 0.56f

/** A face: outward normal, the face's right and down directions as seen from outside with Z up (for the label). */
private class Face(val label: Int, val n: Vec3, val right: Vec3, val down: Vec3)

private val FACES = listOf(
    Face(R.string.view_top, Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f)),
    Face(R.string.view_bottom, Vec3(0f, 0f, -1f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
    Face(R.string.view_front, Vec3(0f, -1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)),
    Face(R.string.view_rear, Vec3(0f, 1f, 0f), Vec3(-1f, 0f, 0f), Vec3(0f, 0f, -1f)),
    Face(R.string.view_left, Vec3(-1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f)),
    Face(R.string.view_right, Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, -1f)),
)

/** One of a face's 9 cells: its screen quad and the direction to look from (face normal plus edge/corner neighbours). */
private class Cell(val quad: List<Offset>, val look: Vec3)

/** The view's right, up and towards-eye axes for [yaw] / [pitch], as in [Camera]. */
private fun basis(yaw: Float, pitch: Float): Triple<Vec3, Vec3, Vec3> {
    val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
    val right = Vec3(-sin(y).toFloat(), cos(y).toFloat(), 0f)
    val up = Vec3((-sin(p) * cos(y)).toFloat(), (-sin(p) * sin(y)).toFloat(), cos(p).toFloat())
    val eye = Vec3((cos(p) * cos(y)).toFloat(), (cos(p) * sin(y)).toFloat(), sin(p).toFloat())
    return Triple(right, up, eye)
}

/** Orthographic screen point of cube point [q] (cube from -1 to 1) on a canvas of half width [half]. */
private fun screen(q: Vec3, right: Vec3, up: Vec3, half: Float) =
    Offset(half + q.dot(right) * half * CUBE, half - q.dot(up) * half * CUBE)

/** Corners of face [f] in label order (top left, top right, bottom right, bottom left), or null when it faces away. */
private fun faceQuad(f: Face, yaw: Float, pitch: Float, half: Float): List<Offset>? {
    val (right, up, eye) = basis(yaw, pitch)
    if (f.n.dot(eye) <= 0.02f) return null
    return listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (a, b) -> screen(f.n + f.right * a + f.down * b, right, up, half) }
}

/** The 9 cells of each face turned towards the eye. */
private fun cells(yaw: Float, pitch: Float, half: Float): List<Cell> {
    val (right, up, eye) = basis(yaw, pitch)
    val bounds = floatArrayOf(-1f, -CENTRE, CENTRE, 1f)
    val out = ArrayList<Cell>(27)
    for (f in FACES) {
        if (f.n.dot(eye) <= 0.02f) continue
        for (i in 0..2) for (j in 0..2) {
            val (a0, a1) = bounds[i] to bounds[i + 1]
            val (b0, b1) = bounds[j] to bounds[j + 1]
            val quad = listOf(a0 to b0, a1 to b0, a1 to b1, a0 to b1).map { (a, b) -> screen(f.n + f.right * a + f.down * b, right, up, half) }
            out += Cell(quad, f.n + f.right * (i - 1).toFloat() + f.down * (j - 1).toFloat())
        }
    }
    return out
}

/** The cell under [p]; the visible cells of a convex cube don't overlap on screen. */
private fun hit(cells: List<Cell>, p: Offset): Cell? = cells.firstOrNull { c ->
    var sign = 0f
    (0..3).all { k ->
        val a = c.quad[k]; val b = c.quad[(k + 1) % 4]
        val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        if (abs(cross) < 1e-3f) true else if (sign == 0f) { sign = cross; true } else sign * cross > 0
    }
}

/** Swings the camera to look from direction [look] over ~300 ms, the short way round. Straight down/up keeps the front at the bottom. */
private suspend fun swingTo(camera: Camera, look: Vec3) {
    val flat = hypot(look.x, look.y)
    val toYaw = if (flat < 1e-4f) -90f else Math.toDegrees(atan2(look.y, look.x).toDouble()).toFloat()
    val toPitch = Math.toDegrees(atan2(look.z, flat).toDouble()).toFloat().coerceIn(-89f, 89f)
    val s = camera.state()
    val fromYaw = s[0]; val fromPitch = s[1]
    val dYaw = ((toYaw - fromYaw) % 360f + 540f) % 360f - 180f
    val start = withFrameNanos { it }
    while (true) {
        val t = withFrameNanos { ((it - start) / 300e6f).coerceAtMost(1f) }
        val e = t * t * (3 - 2 * t)
        camera.aim(yaw = fromYaw + dYaw * e, pitch = fromPitch + (toPitch - fromPitch) * e)
        if (t >= 1f) break
    }
}
