package app.orcaandroid.render

import app.orcaandroid.core.Vec3

/** The cut tool's plane: through a point, turned by Euler degrees (Rz * Ry * Rx, as an instance's rotation) from the horizontal. */
object CutPlane {
    /** |normal z| under which the plane counts as upright (~3 deg). */
    private const val UPRIGHT_NZ = 0.05f

    fun normal(rot: Vec3) = RotateGizmo.apply(RotateGizmo.fromEuler(rot), Vec3(0f, 0f, 1f))

    /** Two unit vectors spanning the plane. */
    fun axes(rot: Vec3): Pair<Vec3, Vec3> {
        val m = RotateGizmo.fromEuler(rot)
        return RotateGizmo.apply(m, Vec3(1f, 0f, 0f)) to RotateGizmo.apply(m, Vec3(0f, 1f, 0f))
    }

    /** Angle between the plane and the horizontal, degrees. */
    fun tilt(rot: Vec3) = Math.toDegrees(Math.acos(normal(rot).z.coerceIn(-1f, 1f).toDouble())).toFloat()

    /** (centre xyz, u xyz, v xyz) for [PlateRenderer.cutDisc]: the disc of radius [r] about [c]. */
    fun disc(c: Vec3, r: Float, rot: Vec3): FloatArray {
        val (u, v) = axes(rot)
        return floatArrayOf(c.x, c.y, c.z, u.x * r, u.y * r, u.z * r, v.x * r, v.y * r, v.z * r)
    }

    /**
     * The cut height that puts the plane (turned by [rot], through ([cx], [cy], z) as the native cut takes it) through [p]; a plane
     * within ~3 deg of upright can't be moved by its height: null.
     */
    fun heightThrough(cx: Float, cy: Float, rot: Vec3, p: Vec3): Float? {
        val n = normal(rot)
        if (kotlin.math.abs(n.z) < UPRIGHT_NZ) return null
        return p.z + (n.x * (p.x - cx) + n.y * (p.y - cy)) / n.z
    }

    /** Whether the plane is too close to upright for its height to move it (it moves by a sideways shift instead). */
    fun upright(rot: Vec3) = kotlin.math.abs(normal(rot).z) < UPRIGHT_NZ

    /** The direction a drag moves the plane in: up for a lying or tilted plane, along the normal's level part for an upright one. */
    fun slideAxis(rot: Vec3): Vec3 {
        if (!upright(rot)) return Vec3(0f, 0f, 1f)
        val n = normal(rot)
        val l = kotlin.math.hypot(n.x, n.y)
        return Vec3(n.x / l, n.y / l, 0f)
    }

    /** The sideways shift from ([cx], [cy]) that puts an upright plane through [p] (along [slideAxis]). */
    fun shiftThrough(cx: Float, cy: Float, rot: Vec3, p: Vec3): Pair<Float, Float> {
        val a = slideAxis(rot)
        val t = a.x * (p.x - cx) + a.y * (p.y - cy)
        return a.x * t to a.y * t
    }

    /**
     * The Euler degrees (no turn about Z) that point the plane's normal along [n]; a downward [n] is flipped first, so "upper" stays
     * the side that faces up.
     */
    fun rotFacing(n: Vec3): Vec3 {
        val l = n.length().takeIf { it > 1e-6f } ?: return Vec3(0f, 0f, 0f)
        var x = n.x / l; var y = n.y / l; var z = n.z / l
        if (z < -1e-4f) { x = -x; y = -y; z = -z }
        // Ry(b) * Rx(a) * Z = (cos a sin b, -sin a, cos a cos b).
        val a = Math.toDegrees(Math.asin((-y).toDouble().coerceIn(-1.0, 1.0))).toFloat()
        val b = Math.toDegrees(Math.atan2(x.toDouble(), z.toDouble())).toFloat()
        return Vec3(a, b, 0f)
    }

    /** (nx, ny, nz, d) of the plane through [c]. */
    fun band(c: Vec3, rot: Vec3): FloatArray {
        val n = normal(rot)
        return floatArrayOf(n.x, n.y, n.z, n.dot(c))
    }
}
