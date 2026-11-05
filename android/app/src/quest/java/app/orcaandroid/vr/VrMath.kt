package app.orcaandroid.vr

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

    /** Line endpoints (x y z each) of a square grid on the plane y = [y], from -[half] to +[half] in steps of [step]. */
    fun gridLines(half: Float, step: Float, y: Float): FloatArray {
        val perSide = Math.round(2 * half / step) + 1
        val out = FloatArray(perSide * 2 * 2 * 3)
        var i = 0
        for (n in 0 until perSide) {
            val t = -half + n * step
            for (p in floatArrayOf(t, y, -half, t, y, half, -half, y, t, half, y, t)) out[i++] = p
        }
        return out
    }
}
