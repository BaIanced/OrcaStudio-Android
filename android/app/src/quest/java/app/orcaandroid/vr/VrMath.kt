package app.orcaandroid.vr

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Column-major 4x4 math for OpenXR views. Plain Kotlin, so JVM tests can run it, and no per-frame allocation. */
internal object VrMath {
    /** World to eye: the inverse of a rigid pose (position x y z, unit quaternion x y z w) at [offset] in [v]. */
    fun viewFromPose(out: FloatArray, v: FloatArray, offset: Int) {
        val px = v[offset]
        val py = v[offset + 1]
        val pz = v[offset + 2]
        val x = v[offset + 3]
        val y = v[offset + 4]
        val z = v[offset + 5]
        val w = v[offset + 6]
        // Rows of the pose's rotation, which are the columns of its inverse.
        val r00 = 1 - 2 * (y * y + z * z)
        val r01 = 2 * (x * y - w * z)
        val r02 = 2 * (x * z + w * y)
        val r10 = 2 * (x * y + w * z)
        val r11 = 1 - 2 * (x * x + z * z)
        val r12 = 2 * (y * z - w * x)
        val r20 = 2 * (x * z - w * y)
        val r21 = 2 * (y * z + w * x)
        val r22 = 1 - 2 * (x * x + y * y)
        out[0] = r00; out[4] = r10; out[8] = r20; out[12] = -(r00 * px + r10 * py + r20 * pz)
        out[1] = r01; out[5] = r11; out[9] = r21; out[13] = -(r01 * px + r11 * py + r21 * pz)
        out[2] = r02; out[6] = r12; out[10] = r22; out[14] = -(r02 * px + r12 * py + r22 * pz)
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
    }

    /** GL clip space (z -1..1) from OpenXR's asymmetric fov angles in radians. */
    fun projectionFromFov(out: FloatArray, left: Float, right: Float, up: Float, down: Float, near: Float, far: Float) {
        val tanL = tan(left)
        val tanR = tan(right)
        val tanU = tan(up)
        val tanD = tan(down)
        val w = tanR - tanL
        val h = tanU - tanD
        out.fill(0f)
        out[0] = 2 / w
        out[5] = 2 / h
        out[8] = (tanR + tanL) / w
        out[9] = (tanU + tanD) / h
        out[10] = -(far + near) / (far - near)
        out[11] = -1f
        out[14] = -2 * far * near / (far - near)
    }

    /** out = a * b. [out] must be neither input. */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[row + k * 4] * b[k + col * 4]
                out[row + col * 4] = sum
            }
        }
    }

    /**
     * Plate (mm, Z up) to the LOCAL space (metres, Y up) at 1:1: (x, y, z) to 0.001 * (x, z, -y), a pure rotation so
     * winding holds, with the plate point ([cx], [cy], 0) moved to [TABLE_Y] below and [TABLE_AHEAD] ahead of the origin.
     */
    fun plateToLocal(out: FloatArray, cx: Float, cy: Float) {
        out.fill(0f)
        out[0] = MM
        out[6] = -MM
        out[9] = MM
        out[12] = -MM * cx
        out[13] = TABLE_Y
        out[14] = MM * cy + TABLE_AHEAD
        out[15] = 1f
    }

    /** The inverse of [plateToLocal] on a point: LOCAL (metres) at [offset] in [v] to plate mm, written to [out] as x y z. */
    fun localToPlate(out: FloatArray, v: FloatArray, offset: Int, cx: Float, cy: Float) {
        out[0] = v[offset] / MM + cx
        out[1] = cy - (v[offset + 2] - TABLE_AHEAD) / MM
        out[2] = (v[offset + 1] - TABLE_Y) / MM
    }

    /**
     * An aim pose (position x y z, unit quaternion x y z w at [offset] in [v], in LOCAL) as a plate-space ray, written to
     * [out] as origin x y z then unit direction x y z, all mm. The aim direction is -Z of the pose.
     */
    fun aimToPlate(out: FloatArray, v: FloatArray, offset: Int, cx: Float, cy: Float) {
        localToPlate(out, v, offset, cx, cy)
        val x = v[offset + 3]
        val y = v[offset + 4]
        val z = v[offset + 5]
        val w = v[offset + 6]
        // Local direction is minus the pose's Z column; plate (x, y, z) is local (x, -z, y).
        val lx = -2 * (x * z + w * y)
        val ly = -2 * (y * z - w * x)
        val lz = -(1 - 2 * (x * x + y * y))
        val len = sqrt(lx * lx + ly * ly + lz * lz)
        out[3] = lx / len
        out[4] = -lz / len
        out[5] = ly / len
    }

    /** The hit of an [aimToPlate] ray [ray] with the plate plane z = 0: [out] gets x y (mm) and the distance. False if parallel or behind. */
    fun rayPlateZ0(out: FloatArray, ray: FloatArray): Boolean {
        val dz = ray[5]
        if (dz > -1e-6f && dz < 1e-6f) return false
        val t = -ray[2] / dz
        if (t <= 0f) return false
        out[0] = ray[0] + t * ray[3]
        out[1] = ray[1] + t * ray[4]
        out[2] = t
        return true
    }

    /**
     * The index of the plate whose bed rectangle holds ([x], [y]), or -1. [origins] holds [count] plate origins as x y pairs;
     * the bed spans [minX], [minY] to [maxX], [maxY] relative to each origin.
     */
    fun plateAtPoint(x: Float, y: Float, origins: FloatArray, count: Int, minX: Float, minY: Float, maxX: Float, maxY: Float): Int {
        for (i in 0 until count) {
            val ox = origins[2 * i]
            val oy = origins[2 * i + 1]
            if (x >= minX + ox && x <= maxX + ox && y >= minY + oy && y <= maxY + oy) return i
        }
        return -1
    }

    /**
     * The aim pose (as for [aimToPlate]) against the panel quad. [out] gets panel pixels x y (top-left origin, unclamped) and
     * the distance, and stays untouched when the ray is parallel, behind, or hits the back face. True if the hit is inside the panel.
     */
    fun rayQuad(out: FloatArray, v: FloatArray, offset: Int): Boolean {
        val px = v[offset]
        val py = v[offset + 1]
        val pz = v[offset + 2]
        val x = v[offset + 3]
        val y = v[offset + 4]
        val z = v[offset + 5]
        val w = v[offset + 6]
        val dx = -2 * (x * z + w * y)
        val dy = -2 * (y * z - w * x)
        val dz = -(1 - 2 * (x * x + y * y))
        val facing = dx * PANEL_SIN + dz * PANEL_COS // Against the normal (sin, 0, cos); negative faces the viewer.
        if (facing > -1e-6f) return false
        val t = ((PANEL_X - px) * PANEL_SIN + (PANEL_Z - pz) * PANEL_COS) / facing
        if (t <= 0f) return false
        val hx = px + t * dx - PANEL_X
        val hy = py + t * dy - PANEL_Y
        val hz = pz + t * dz - PANEL_Z
        val u = hx * PANEL_COS - hz * PANEL_SIN // Along the panel's right axis (cos, 0, -sin).
        out[0] = (u / PANEL_W + 0.5f) * PANEL_PX_W
        out[1] = (0.5f - hy / PANEL_H) * PANEL_PX_H
        out[2] = t
        return out[0] >= 0f && out[0] <= PANEL_PX_W && out[1] >= 0f && out[1] <= PANEL_PX_H
    }

    private const val MM = 0.001f
    /** Where the plate sits relative to the head at start, in metres. Comfort constants, to be set by the user check. */
    const val TABLE_Y = -0.4f
    const val TABLE_AHEAD = -0.6f

    /** The panel quad in LOCAL: centre, size in metres and pixels, yawed about +Y to face the origin. Comfort constants too. */
    const val PANEL_X = -0.55f
    const val PANEL_Y = -0.05f
    const val PANEL_Z = -0.85f
    const val PANEL_W = 0.64f
    const val PANEL_H = 0.40f
    const val PANEL_PX_W = 1280f
    const val PANEL_PX_H = 800f
    private const val PANEL_YAW_DEG = 33.0
    private val PANEL_SIN = sin(Math.toRadians(PANEL_YAW_DEG)).toFloat()
    private val PANEL_COS = cos(Math.toRadians(PANEL_YAW_DEG)).toFloat()
}
