package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The rotate handles, as the desktop's rotate gizmo (GLGizmoRotate3D): three rings round the selection box's centre, red
 * about X, green about Y, blue about Z, a little wider than the box. Dragging a ring turns the copy about that world axis
 * through the box centre; the angle is where the finger's ray meets the ring's plane (GLGizmoRotate::on_dragging), or,
 * where that plane is nearly edge-on, the finger's move along the ring on screen.
 */
object RotateGizmo {
    /** How far the rings sit outside the box's half diagonal, in pixels (the desktop's Offset, in screen terms). */
    const val RING_GAP_PX = 18f
    const val SEGMENTS = 96
    /** Fine snap, with the finger outside the ring (the desktop's tick scale, ScaleStepsCount 72). */
    const val FINE_STEP = 5f
    /** Coarse snap, with the finger well inside the ring (the desktop's SnapRegionsCount 8). */
    const val COARSE_STEP = 45f
    /** Half the width of the free band along the ring, in dp: a finger on the ring drifts, and the desktop's bands are mouse-thin. */
    const val FREE_BAND_DP = 14f

    fun center(lo: Vec3, hi: Vec3) = Vec3((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2)

    fun radius(lo: Vec3, hi: Vec3, camera: Camera): Float {
        val c = center(lo, hi)
        return (hi - lo).length() / 2 + camera.pxSize(c) * RING_GAP_PX
    }

    fun axisVec(axis: Int) = when (axis) { 0 -> Vec3(1f, 0f, 0f); 1 -> Vec3(0f, 1f, 0f); else -> Vec3(0f, 0f, 1f) }

    /** Two unit vectors spanning the plane of the ring about [axis] (u x v = the axis). */
    fun basis(axis: Int) = when (axis) {
        0 -> Vec3(0f, 1f, 0f) to Vec3(0f, 0f, 1f)
        1 -> Vec3(0f, 0f, 1f) to Vec3(1f, 0f, 0f)
        else -> Vec3(1f, 0f, 0f) to Vec3(0f, 1f, 0f)
    }

    /** The ring about [axis] as [SEGMENTS] + 1 points (closed). */
    fun ring(axis: Int, c: Vec3, r: Float): List<Vec3> {
        val (u, v) = basis(axis)
        return (0..SEGMENTS).map { i ->
            val a = i * 2.0 * Math.PI / SEGMENTS
            c + u * (r * cos(a).toFloat()) + v * (r * sin(a).toFloat())
        }
    }

    /**
     * Per segment of [ring] (point i to i + 1): whether the box ([lo], [hi]) hides it from [camera] (its midpoint lies
     * behind the box), as [ScaleGizmo.visible] hides the handles behind it (user 2026-10-28: back parts grabbed touches).
     */
    fun hidden(ring: List<Vec3>, lo: Vec3, hi: Vec3, camera: Camera): BooleanArray {
        val eye = camera.eye()
        val e = maxOf(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z) * 1e-3f
        val inLo = Vec3(lo.x + e, lo.y + e, lo.z + e)
        val inHi = Vec3(hi.x - e, hi.y - e, hi.z - e)
        return BooleanArray(ring.size - 1) { i -> ScaleGizmo.segmentHitsBox(eye, (ring[i] + ring[i + 1]) * 0.5f, inLo, inHi) }
    }

    /**
     * The ring (0 X, 1 Y, 2 Z) whose drawn line passes within [reach] pixels of (x, y), nearest first, or -1; with
     * [occluded], the parts the box hides ([hidden]) don't count.
     */
    fun pick(lo: Vec3, hi: Vec3, camera: Camera, x: Float, y: Float, reach: Float, axisCount: Int = 3, occluded: Boolean = false): Int {
        val c = center(lo, hi)
        val r = radius(lo, hi, camera)
        var best = -1
        var bestD = reach
        for (axis in 0 until axisCount) {
            val world = ring(axis, c, r)
            val behind = if (occluded) hidden(world, lo, hi, camera) else null
            val pts = world.map { camera.project(it) }
            for (i in 0 until pts.size - 1) {
                if (behind?.get(i) == true) continue
                val a = pts[i] ?: continue
                val b = pts[i + 1] ?: continue
                val d = segmentDistance(x, y, a.first, a.second, b.first, b.second)
                if (d < bestD) { bestD = d; best = axis }
            }
        }
        return best
    }

    private fun segmentDistance(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-6f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(x - (ax + dx * t), y - (ay + dy * t))
    }

    /**
     * The snap step for a finger [len] from the centre in the ring plane (null: edge-on), as GLGizmoRotate::on_dragging:
     * [FINE_STEP] outside the ring, [COARSE_STEP] well inside it, free along it within [freeHalf] (world units).
     */
    fun snapStep(len: Float?, r: Float, freeHalf: Float) = when {
        len == null -> 0f
        len > r + freeHalf -> FINE_STEP
        len < minOf(r * 2f / 3f, r - freeHalf) -> COARSE_STEP
        else -> 0f
    }

    /**
     * One drag of the ring about [axis] round [c] (radius [r]) from screen (x0, y0). [update] gives the angle turned so far
     * in degrees, counter-clockwise seen from the axis tip, unwrapped (it keeps counting past half a turn), snapped to
     * [step] where the finger is in a snap zone ([snapStep]; [freeBandPx] is the free band's half width on screen). The
     * steps count from where the drag started, as the desktop's count from its grabber.
     */
    class Drag(val axis: Int, val c: Vec3, private val r: Float, private val camera: Camera, x0: Float, y0: Float, private val freeBandPx: Float) {
        private val n = axisVec(axis)
        private val freeHalf = minOf(camera.pxSize(c) * freeBandPx, r * 0.25f)
        private var last: Vec3? = inPlane(x0, y0)
        /** Where the drag started, from the centre in the ring plane (null when it started edge-on). */
        val start: Vec3? = last
        private var lastX = x0
        private var lastY = y0
        private var raw = 0f
        /** The turn so far, snapped to [step]. */
        var degrees = 0f
            private set
        /** The snap step in force (0 = free). */
        var step = 0f
            private set

        /** The finger's ray on the ring plane, as a direction from the centre; null where the plane is (nearly) edge-on. */
        private fun inPlane(x: Float, y: Float): Vec3? {
            val (o, d) = camera.ray(x, y)
            val dn = d.dot(n)
            if (abs(dn) < EDGE_ON) return null
            val p = o + d * ((c - o).dot(n) / dn) - c
            val q = p - n * p.dot(n)
            return if (q.length() < r * 0.05f) null else q
        }

        fun update(x: Float, y: Float): Float {
            val now = inPlane(x, y)
            val prev = last
            raw += if (now != null && prev != null) {
                Math.toDegrees(atan2(cross(prev, now).dot(n), prev.dot(now)).toDouble()).toFloat()
            } else screenStep(lastX, lastY, x, y)
            last = now
            lastX = x; lastY = y
            step = if (now != null) snapStep(now.length(), r, freeHalf) else screenSnap(x, y)
            degrees = if (step > 0f) Math.round(raw / step) * step else raw
            return degrees
        }

        /**
         * [snapStep] on screen, where the finger's ray misses the ring plane or grazes it (far outside a tilted ring, WSA):
         * fine outside the ring's outline, coarse inside its 2/3 outline, free within [freeBandPx] of the ring.
         */
        private fun screenSnap(x: Float, y: Float): Float {
            val outer = ring(axis, c, r).mapNotNull { camera.project(it) }
            if (outer.size < 3) return 0f
            var near = Float.MAX_VALUE
            for (i in 0 until outer.size - 1) {
                near = minOf(near, segmentDistance(x, y, outer[i].first, outer[i].second, outer[i + 1].first, outer[i + 1].second))
            }
            if (near < freeBandPx) return 0f
            if (!inside(outer, x, y)) return FINE_STEP
            val inner = ring(axis, c, r * 2f / 3f).mapNotNull { camera.project(it) }
            return if (inner.size >= 3 && inside(inner, x, y)) COARSE_STEP else 0f
        }

        /**
         * Edge-on: the move along the ring's tangent on screen at the finger, over the ring's radius on screen. The
         * tangent is that of the ring point nearest the finger on the half facing the camera: edge-on, the back half
         * lies on the same screen line with the tangent reversed, and mixing the two cancelled the turn (WSA).
         */
        private fun screenStep(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val pts = ring(axis, c, r)
            val toEye = camera.eye() - c
            var bestI = -1
            var bestD = Float.MAX_VALUE
            val proj = pts.map { camera.project(it) }
            for (i in 0 until SEGMENTS) {
                if ((pts[i] - c).dot(toEye) < 0f) continue
                val p = proj[i] ?: continue
                val d = hypot(p.first - x0, p.second - y0)
                if (d < bestD) { bestD = d; bestI = i }
            }
            if (bestI < 0) return 0f
            val a = proj[bestI]!!
            val b = proj[bestI + 1] ?: return 0f
            val tx = b.first - a.first
            val ty = b.second - a.second
            val tl = hypot(tx, ty)
            val cp = camera.project(c) ?: return 0f
            val rPx = proj.filterNotNull().maxOf { hypot(it.first - cp.first, it.second - cp.second) }
            if (tl < 1e-3f || rPx < 8f) return 0f
            val along = ((x1 - x0) * tx + (y1 - y0) * ty) / tl
            return Math.toDegrees((along / rPx).toDouble()).toFloat()
        }
    }

    /** Even-odd test of (x, y) against the closed outline [pts]. */
    fun inside(pts: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var odd = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val (xi, yi) = pts[i]
            val (xj, yj) = pts[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) odd = !odd
            j = i
        }
        return odd
    }

    /** |cos| between the finger's ray and the ring plane's normal below which the plane counts as edge-on (~80 deg). */
    private const val EDGE_ON = 0.17f

    fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    /** The 3x3 rotation by [degrees] about world [axis], row major. */
    fun matrix(axis: Int, degrees: Float): FloatArray {
        val a = Math.toRadians(degrees.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return when (axis) {
            0 -> floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
            1 -> floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
            else -> floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        }
    }

    fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { k -> val i = k / 3; val j = k % 3; (0..2).sumOf { (a[i * 3 + it] * b[it * 3 + j]).toDouble() }.toFloat() }

    fun apply(m: FloatArray, p: Vec3) = Vec3(m[0] * p.x + m[1] * p.y + m[2] * p.z, m[3] * p.x + m[4] * p.y + m[5] * p.z, m[6] * p.x + m[7] * p.y + m[8] * p.z)

    /** The instance rotation (Euler degrees, x, y, z) as a matrix: Rz * Ry * Rx (Geometry::rotation_transform). */
    fun fromEuler(r: Vec3) = mul(matrix(2, r.z), mul(matrix(1, r.y), matrix(0, r.x)))

    /** Euler degrees (x, y, z) of [m] = Rz * Ry * Rx, as Geometry::extract_euler_angles. */
    fun toEuler(m: FloatArray): Vec3 {
        val r20 = m[6].coerceIn(-1f, 1f)
        val deg = { v: Double -> Math.toDegrees(v).toFloat() }
        if (abs(abs(r20) - 1f) < 1e-5f) {
            // Gimbal lock (y = +-90): only x -+ z is known; z = 0.
            return if (r20 < 0f) Vec3(deg(atan2(m[1].toDouble(), m[2].toDouble())), 90f, 0f)
            else Vec3(deg(atan2(-m[1].toDouble(), -m[2].toDouble())), -90f, 0f)
        }
        val y = -asin(r20.toDouble())
        val ic = 1.0 / cos(y)
        val x = atan2(m[7] * ic, m[8] * ic)
        val z = atan2(m[3] * ic, m[0] * ic)
        return Vec3(deg(x), deg(y), deg(z))
    }

    /** Ring colours: X red, Y green, Z blue (the desktop's AXES_COLOR). */
    fun color(axis: Int) = ScaleGizmo.color(axis)
}
