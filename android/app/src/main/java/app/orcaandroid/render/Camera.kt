package app.orcaandroid.render

import android.opengl.Matrix
import app.orcaandroid.core.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Orbit camera around a target point, Z up. Shared between the UI thread (gestures, picking rays)
 * and the GL thread (drawing), hence synchronized.
 */
class Camera {
    private var yaw = -60f
    private var pitch = 40f
    private var distance = 400f
    private val target = floatArrayOf(100f, 100f, 0f)
    private var width = 1
    private var height = 1

    @Synchronized fun setViewport(w: Int, h: Int) {
        width = max(w, 1)
        height = max(h, 1)
    }

    @Synchronized fun frame(minX: Float, minY: Float, maxX: Float, maxY: Float, top: Boolean = false) {
        target[0] = (minX + maxX) / 2
        target[1] = (minY + maxY) / 2
        target[2] = 0f
        // Fit the plate's diagonal, also in portrait views where the horizontal field of view is the narrower one.
        val diag = kotlin.math.hypot(maxX - minX, maxY - minY)
        val aspect = width.toFloat() / height
        distance = diag * (if (aspect < 1f) 1.8f / aspect else 1.5f) + 20f
        yaw = -60f
        pitch = if (top) 89f else 40f
    }

    @Synchronized fun orbit(dxDeg: Float, dyDeg: Float) {
        yaw -= dxDeg
        pitch = (pitch + dyDeg).coerceIn(-89f, 89f)
    }

    @Synchronized fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(5f, 10000f)
    }

    /** Pans in the view plane; dx/dy in screen pixels. */
    @Synchronized fun pan(dx: Float, dy: Float) {
        val scale = distance / height * 0.8f
        val (r, u) = axes()
        for (i in 0..2) target[i] += (-dx * r[i] + dy * u[i]) * scale
    }

    /** View preset: 0 iso, 1 top, 2 front, 3 left, 4 right. */
    @Synchronized fun preset(view: Int) {
        when (view) {
            1 -> { yaw = -90f; pitch = 89f }
            2 -> { yaw = -90f; pitch = 0f }
            3 -> { yaw = 180f; pitch = 0f }
            4 -> { yaw = 0f; pitch = 0f }
            else -> { yaw = -60f; pitch = 40f }
        }
    }

    @Synchronized fun eye(): Vec3 {
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        return Vec3(
            target[0] + (distance * cos(pr) * cos(yr)).toFloat(),
            target[1] + (distance * cos(pr) * sin(yr)).toFloat(),
            target[2] + (distance * sin(pr)).toFloat(),
        )
    }

    /** Fills [out] with projection * view. */
    @Synchronized fun viewProjection(out: FloatArray) {
        val e = eye()
        val proj = FloatArray(16)
        val view = FloatArray(16)
        Matrix.perspectiveM(proj, 0, FOV, width.toFloat() / height, max(0.5f, distance / 200f), distance * 20f)
        Matrix.setLookAtM(view, 0, e.x, e.y, e.z, target[0], target[1], target[2], 0f, 0f, 1f)
        Matrix.multiplyMM(out, 0, proj, 0, view, 0)
    }

    /** World-space ray through screen pixel (x, y): origin and normalized direction. */
    @Synchronized fun ray(x: Float, y: Float): Pair<Vec3, Vec3> {
        val vp = FloatArray(16).also { viewProjection(it) }
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, vp, 0)
        val nx = 2f * x / width - 1f
        val ny = 1f - 2f * y / height
        fun unproject(z: Float): Vec3 {
            val v = floatArrayOf(nx, ny, z, 1f)
            val r = FloatArray(4)
            Matrix.multiplyMV(r, 0, inv, 0, v, 0)
            return Vec3(r[0] / r[3], r[1] / r[3], r[2] / r[3])
        }
        val near = unproject(-1f)
        val far = unproject(1f)
        val d = far - near
        return near to d * (1f / d.length())
    }

    /** Intersection of the ray through (x, y) with the plane z = [z], or null if parallel/behind. */
    fun rayOnPlane(x: Float, y: Float, z: Float = 0f): Vec3? {
        val (o, d) = ray(x, y)
        if (kotlin.math.abs(d.z) < 1e-6f) return null
        val t = (z - o.z) / d.z
        return if (t < 0) null else o + d * t
    }

    private fun axes(): Pair<FloatArray, FloatArray> {
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val right = floatArrayOf(-sin(yr).toFloat(), cos(yr).toFloat(), 0f)
        val up = floatArrayOf((-sin(pr) * cos(yr)).toFloat(), (-sin(pr) * sin(yr)).toFloat(), cos(pr).toFloat())
        return right to up
    }

    private companion object {
        const val FOV = 35f
    }
}
