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

    fun center(lo: Vec3, hi: Vec3) = Vec3((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2)

    fun radius(lo: Vec3, hi: Vec3, camera: Camera): Float {
        val c = center(lo, hi)
        return (hi - lo).length() / 2 + camera.pxSize(c) * RING_GAP_PX
    }

    fun axisVec(axis: Int) = when (axis) { 0 -> Vec3(1f, 0f, 0f); 1 -> Vec3(0f, 1f, 0f); else -> Vec3(0f, 0f, 1f) }

    /** Two unit vectors spanning the plane of the ring about [axis] (u x v = the axis). */
    private fun basis(axis: Int) = when (axis) {
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

    /** The ring (0 X, 1 Y, 2 Z) whose drawn line passes within [reach] pixels of (x, y), nearest first, or -1. */
    fun pick(lo: Vec3, hi: Vec3, camera: Camera, x: Float, y: Float, reach: Float): Int {
        val c = center(lo, hi)
        val r = radius(lo, hi, camera)
        var best = -1
        var bestD = reach
        for (axis in 0..2) {
            val pts = ring(axis, c, r).map { camera.project(it) }
            for (i in 0 until pts.size - 1) {
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
     * One drag of the ring about [axis] round [c] (radius [r]) from screen (x0, y0). [update] gives the angle turned so far
     * in degrees, counter-clockwise seen from the axis tip, unwrapped (it keeps counting past half a turn).
     */
    class Drag(val axis: Int, val c: Vec3, private val r: Float, private val camera: Camera, x0: Float, y0: Float) {
        private val n = axisVec(axis)
        private var last: Vec3? = inPlane(x0, y0)
        /** Where the drag started, from the centre in the ring plane (null when it started edge-on). */
        val start: Vec3? = last
        private var lastX = x0
        private var lastY = y0
        var degrees = 0f
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
            degrees += if (now != null && prev != null) {
                Math.toDegrees(atan2(cross(prev, now).dot(n), prev.dot(now)).toDouble()).toFloat()
            } else screenStep(lastX, lastY, x, y)
            last = now
            lastX = x; lastY = y
            return degrees
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
