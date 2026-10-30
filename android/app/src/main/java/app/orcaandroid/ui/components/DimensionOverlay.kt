package app.orcaandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.HoleFinder
import app.orcaandroid.render.PlateView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * View > Dimensions: CAD-style X / Y / Z size lines beside the selection's box, drawn in screen space over the 3D view
 * (the points go through the camera every frame, like the push / pull arrow). Red / green / blue = X / Y / Z.
 */
@Composable
fun DimensionOverlay(view: PlateView?) {
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { frame.longValue = it } }
    // Round holes in the selection, found off the main thread whenever the scene mesh or the selection changes.
    val holes = remember { mutableStateOf<HoleFinder.Result?>(null) }
    LaunchedEffect(view) {
        val r = view?.renderer ?: return@LaunchedEffect
        var seenMesh: Any? = null
        var seenIds: Set<Int>? = null
        while (true) {
            val mesh = r.sceneMesh
            val ids = r.selectedIds.map { Math.round(it) }.toSet()
            if (mesh !== seenMesh || ids != seenIds) {
                seenMesh = mesh
                seenIds = ids
                holes.value = if (mesh == null || ids.isEmpty()) null else withContext(Dispatchers.Default) { HoleFinder.find(mesh, ids) }
            }
            delay(300)
        }
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(color = Color.White)
    Canvas(Modifier.fillMaxSize()) {
        frame.longValue
        val vw = view ?: return@Canvas
        val (lo, hi) = vw.gizmoBox() ?: return@Canvas
        val cam = vw.camera
        fun scr(p: Vec3) = cam.project(p)?.let { Offset(it.first, it.second) }
        val size = hi - lo
        val center = (lo + hi) * 0.5f
        val gap = (maxOf(size.x, size.y, size.z) * 0.12f).coerceIn(3f, 25f)
        val tick = gap * 0.35f
        // The box's lowest (nearest the bottom of the screen) and leftmost corners keep the lines apart and in view.
        fun corner(x: Int, y: Int, z: Int) = Vec3(if (x == 0) lo.x else hi.x, if (y == 0) lo.y else hi.y, if (z == 0) lo.z else hi.z)
        val bottom = (0..3).map { corner(it and 1, it shr 1, 0) }.maxByOrNull { scr(it)?.y ?: -1e9f } ?: return@Canvas
        val left = (0..3).map { corner(it and 1, it shr 1, 0) }.minByOrNull { scr(it)?.x ?: 1e9f } ?: return@Canvas
        // Away from the box centre on the floor plane.
        fun out(p: Vec3, x: Boolean, y: Boolean) = Vec3(if (x) (if (p.x >= center.x) gap else -gap) else 0f, if (y) (if (p.y >= center.y) gap else -gap) else 0f, 0f)

        fun dim(a: Vec3, b: Vec3, off: Vec3, color: Color, mm: Float) {
            val a2 = a + off
            val b2 = b + off
            val sa = scr(a2) ?: return
            val sb = scr(b2) ?: return
            val ext = off * (1f / off.length())
            for ((w, c) in listOf(5f to Color.Black.copy(alpha = 0.5f), 2.5f to color)) {
                drawLine(c, sa, sb, w)
                // Extension lines from the box to the dimension line, and ticks across its ends.
                scr(a)?.let { drawLine(c, it, sa, w * 0.6f) }
                scr(b)?.let { drawLine(c, it, sb, w * 0.6f) }
                for (e in listOf(a2, b2)) {
                    val t0 = scr(e - ext * tick) ?: continue
                    val t1 = scr(e + ext * tick) ?: continue
                    drawLine(c, t0, t1, w)
                }
            }
            val mid = scr((a2 + b2) * 0.5f + ext * (tick * 1.5f)) ?: return
            val t = measurer.measure("%.1f".format(mm), labelStyle)
            val pad = 4.dp.toPx()
            val tl = mid - Offset(t.size.width / 2f, t.size.height / 2f)
            drawRoundRect(color.copy(alpha = 0.85f), tl - Offset(pad, pad / 2), Size(t.size.width + 2 * pad, t.size.height + pad), CornerRadius(pad))
            drawText(t, topLeft = tl)
        }

        val red = Color(0xFFD32F2F)
        val green = Color(0xFF2E7D32)
        val blue = Color(0xFF1565C0)
        // X: along the box's front-most floor edge; Y: along the side edge at the leftmost corner; Z: up the leftmost corner.
        val ox = out(bottom, false, true)
        dim(Vec3(lo.x, bottom.y, lo.z), Vec3(hi.x, bottom.y, lo.z), ox, red, size.x)
        val oy = out(left, true, false)
        dim(Vec3(left.x, lo.y, lo.z), Vec3(left.x, hi.y, lo.z), oy, green, size.y)
        val oz = out(left, true, true)
        dim(Vec3(left.x, left.y, lo.z), Vec3(left.x, left.y, hi.z), oz, blue, size.z)

        // Holes: a ring at the end nearer the eye and the diameter inside it. Found on the last uploaded mesh, so the
        // points follow the live box (a drag or resize in progress) by mapping the found box onto it.
        val found = holes.value ?: return@Canvas
        val fs = found.boxHi - found.boxLo
        fun live(p: Vec3): Vec3 {
            fun m(v: Float, a: Float, s: Float, l: Float, ls: Float) = if (s > 1e-4f) l + (v - a) * (ls / s) else l + (v - a)
            return Vec3(m(p.x, found.boxLo.x, fs.x, lo.x, size.x), m(p.y, found.boxLo.y, fs.y, lo.y, size.y), m(p.z, found.boxLo.z, fs.z, lo.z, size.z))
        }
        val amber = Color(0xFFFFA000)
        val eye = cam.eye()
        val taken = ArrayList<Rect>()
        for (h in found.holes.sortedByDescending { it.diameter }) {
            val a = live(h.lo)
            val b = live(h.hi)
            val end = if ((a - eye).length() <= (b - eye).length()) a else b
            val r = h.diameter / 2
            val ring = (0..32).mapNotNull { i ->
                val t = i * (2 * Math.PI / 32)
                val c = Math.cos(t).toFloat() * r
                val s = Math.sin(t).toFloat() * r
                scr(when (h.axis) { 0 -> end + Vec3(0f, c, s); 1 -> end + Vec3(c, 0f, s); else -> end + Vec3(c, s, 0f) })
            }
            if (ring.size < 33) continue
            for ((w, c) in listOf(4.5f to Color.Black.copy(alpha = 0.5f), 2f to amber)) for (i in 0 until 32) drawLine(c, ring[i], ring[i + 1], w)
            val mid = scr(end) ?: continue
            val t = measurer.measure(String.format(java.util.Locale.ROOT, "⌀%.2f", h.diameter).trimEnd('0').trimEnd('.'), labelStyle)
            val pad = 4.dp.toPx()
            val tl = mid - Offset(t.size.width / 2f, t.size.height / 2f)
            val box = Rect(tl - Offset(pad, pad / 2), Size(t.size.width + 2 * pad, t.size.height + pad))
            if (taken.any { it.overlaps(box) }) continue
            taken += box
            drawRoundRect(amber.copy(alpha = 0.9f), box.topLeft, box.size, CornerRadius(pad))
            drawText(t, topLeft = tl, color = Color.Black)
        }
    }
}
