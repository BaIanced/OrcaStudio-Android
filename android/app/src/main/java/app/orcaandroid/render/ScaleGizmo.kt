package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The resize handles, as the desktop's scale gizmo (GLGizmoScale3D::update_grabbers_data): on the bottom of the selection
 * box grey corner handles (uniform) and red X / green Y handles at the edge midpoints, plus a blue Z handle over the top
 * centre. A handle's drag scales about the box's bottom centre, so the handle follows the finger while the copy stays
 * centred on its spot and on the bed (the desktop without Ctrl). Only handles the box itself doesn't hide are drawn or
 * picked, so nothing is grabbed through the model (user, Quest r323: corners behind it were picked); from (near) top
 * views, where the box hides most of its bottom, the corner and X / Y handles move to the top.
 */
object ScaleGizmo {
    /** [axis]: 0 X, 1 Y, 2 Z, -1 uniform (a corner). */
    class Handle(val index: Int, val pos: Vec3, val axis: Int)

    /** How far the Z handle sits above the top, in pixels, so it stays reachable from level side views. */
    const val Z_LIFT_PX = 22f

    /** The bottom centre of the box ([lo], [hi]): every handle scales about it. */
    fun pivot(lo: Vec3, hi: Vec3) = Vec3((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, lo.z)

    /**
     * The handles of the box ([lo], [hi]) that the box doesn't hide from [camera]; with [axes] false (a copy turned off
     * the 90 degree steps, where one world axis can't be scaled alone) only the corners.
     */
    fun visible(lo: Vec3, hi: Vec3, camera: Camera, axes: Boolean): List<Handle> {
        val cx = (lo.x + hi.x) / 2
        val cy = (lo.y + hi.y) / 2
        val eye = camera.eye()
        // A hair inside the box: handles on a face turned to the camera stay visible, those behind the box don't.
        val e = max(hi.x - lo.x, max(hi.y - lo.y, hi.z - lo.z)) * 1e-3f
        val inLo = Vec3(lo.x + e, lo.y + e, lo.z + e)
        val inHi = Vec3(hi.x - e, hi.y - e, hi.z - e)
        fun ring(z: Float): List<Handle> {
            val r = mutableListOf(
                Handle(0, Vec3(lo.x, lo.y, z), -1), Handle(1, Vec3(hi.x, lo.y, z), -1),
                Handle(2, Vec3(hi.x, hi.y, z), -1), Handle(3, Vec3(lo.x, hi.y, z), -1),
            )
            if (axes) {
                r += Handle(4, Vec3(lo.x, cy, z), 0); r += Handle(5, Vec3(hi.x, cy, z), 0)
                r += Handle(6, Vec3(cx, lo.y, z), 1); r += Handle(7, Vec3(cx, hi.y, z), 1)
            }
            return r.filter { !segmentHitsBox(eye, it.pos, inLo, inHi) }
        }
        // On the bottom as on the desktop; from (near) top views, where the box hides most of its bottom, on the top.
        val out = ring(lo.z).takeIf { r -> r.count { it.axis < 0 } >= 2 } ?: ring(hi.z)
        // Z over the top centre shows whenever the camera is above the box's bottom: from level views the box top hid it,
        // though it sits over the model's top edge there, not behind it.
        if (axes && eye.z > lo.z) {
            val top = Vec3(cx, cy, hi.z)
            return out + Handle(8, top + Vec3(0f, 0f, camera.pxSize(top) * Z_LIFT_PX), 2)
        }
        return out
    }

    /**
     * The scale ratio for a drag of [handle] (box [lo], [hi]) from screen (x0, y0) to (x1, y1), as the desktop's
     * GLGizmoScale3D::calc_ratio: the finger's ray meets the plane the handle moves in (level for corners and X / Y,
     * upright facing the camera for Z), and the move along the handle's direction from the pivot sets the ratio. Measured
     * from where the finger landed, so a touch beside the handle doesn't jump. Where that plane is nearly edge-on the move
     * is measured on screen instead (the desktop then holds the scale).
     */
    fun ratio(handle: Handle, lo: Vec3, hi: Vec3, camera: Camera, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val p = handle.pos
        // From the axis through the box centre (corners, X, Y; at the handle's height) or the bottom (Z).
        val from = if (handle.axis == 2) Vec3(p.x, p.y, lo.z) else Vec3((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, p.z)
        val reach = if (handle.axis == 2) hi.z - lo.z else (p - from).length()
        if (reach <= 1e-4f) return 1f
        val dir = if (handle.axis == 2) Vec3(0f, 0f, 1f) else (p - from) * (1f / reach)
        val up = Vec3(0f, 0f, 1f)
        fun along(x: Float, y: Float): Float? {
            val (o, d) = camera.ray(x, y)
            val n = if (handle.axis == 2) {
                val side = cross(d, up)
                if (side.length() < 1e-4f) return null
                cross(side, up).let { it * (1f / it.length()) }
            } else up
            val dn = d.dot(n)
            if (abs(dn) < EDGE_ON) return null
            return (o + d * ((p - o).dot(n) / dn) - p).dot(dir)
        }
        val a = along(x0, y0)
        val b = along(x1, y1)
        val moved = if (a != null && b != null) b - a else {
            // On screen: the finger's move along the handle's projected direction, scaled to millimetres.
            val s0 = camera.project(from) ?: return 1f
            val s1 = camera.project(from + dir * reach) ?: return 1f
            val sx = s1.first - s0.first
            val sy = s1.second - s0.second
            val len2 = sx * sx + sy * sy
            if (len2 < 16f) return 1f
            ((x1 - x0) * sx + (y1 - y0) * sy) / len2 * reach
        }
        return ((reach + moved) / reach).coerceIn(MIN_RATIO, MAX_RATIO)
    }

    private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    /** |cos| of the angle between the finger's ray and the drag plane's normal below which the plane counts as edge-on (~80 deg). */
    private const val EDGE_ON = 0.17f
    const val MIN_RATIO = 0.1f
    const val MAX_RATIO = 10f

    /** Whether the segment [a]-[b] passes through the box ([lo], [hi]) (slab test). */
    internal fun segmentHitsBox(a: Vec3, b: Vec3, lo: Vec3, hi: Vec3): Boolean {
        val o = floatArrayOf(a.x, a.y, a.z)
        val d = floatArrayOf(b.x - a.x, b.y - a.y, b.z - a.z)
        val l = floatArrayOf(lo.x, lo.y, lo.z)
        val h = floatArrayOf(hi.x, hi.y, hi.z)
        var t0 = 0f
        var t1 = 1f
        for (i in 0..2) {
            if (abs(d[i]) < 1e-9f) {
                if (o[i] <= l[i] || o[i] >= h[i]) return false
            } else {
                var u = (l[i] - o[i]) / d[i]
                var v = (h[i] - o[i]) / d[i]
                if (u > v) { val t = u; u = v; v = t }
                t0 = max(t0, u); t1 = min(t1, v)
                if (t0 >= t1) return false
            }
        }
        return true
    }

    /** The world scale (x, y, z) a drag of [handle] by [ratio] gives. */
    fun scale(handle: Handle, ratio: Float) = when (handle.axis) {
        0 -> Vec3(ratio, 1f, 1f)
        1 -> Vec3(1f, ratio, 1f)
        2 -> Vec3(1f, 1f, ratio)
        else -> Vec3(ratio, ratio, ratio)
    }

    /** Handle colours: X red, Y green, Z blue, corners light grey (the desktop's AXES_COLOR, GRABBER_UNIFORM_COL). */
    fun color(axis: Int) = when (axis) {
        0 -> floatArrayOf(0.85f, 0.2f, 0.2f)
        1 -> floatArrayOf(0.25f, 0.75f, 0.25f)
        2 -> floatArrayOf(0.25f, 0.45f, 0.95f)
        else -> floatArrayOf(0.85f, 0.85f, 0.85f)
    }
}
