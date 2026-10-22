package app.orcaandroid.render

import app.orcaandroid.core.Vec3

/** The cut tool's plane: through a point, turned by Euler degrees (Rz * Ry * Rx, as an instance's rotation) from the horizontal. */
object CutPlane {
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

    /** (nx, ny, nz, d) of the plane through [c]. */
    fun band(c: Vec3, rot: Vec3): FloatArray {
        val n = normal(rot)
        return floatArrayOf(n.x, n.y, n.z, n.dot(c))
    }
}
