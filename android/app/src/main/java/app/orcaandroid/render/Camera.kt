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

    /** Yaw, pitch, distance and target, for [restore] in a new view. */
    @Synchronized fun state() = floatArrayOf(yaw, pitch, distance, target[0], target[1], target[2])

    @Synchronized fun restore(s: FloatArray) {
        yaw = s[0]; pitch = s[1]; distance = s[2]
        for (i in 0..2) target[i] = s[3 + i]
    }

    @Synchronized fun viewportSize() = width to height

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

    /** Looks at the box [min]..[max] from the iso direction so that it fills the view. */
    @Synchronized fun fit(min: Vec3, max: Vec3) {
        target[0] = (min.x + max.x) / 2
        target[1] = (min.y + max.y) / 2
        target[2] = (min.z + max.z) / 2
        val radius = (max - min).length() / 2
        val aspect = width.toFloat() / height
        // Distance at which a sphere around the box fits the narrower field of view.
        val half = Math.toRadians(FOV / 2.0).let { kotlin.math.tan(it) }.toFloat() * minOf(1f, aspect)
        distance = (radius / half * 1.1f).coerceIn(5f, 10000f)
        yaw = -60f
        pitch = 30f
    }

    @Synchronized fun orbit(dxDeg: Float, dyDeg: Float) {
        yaw -= dxDeg
        pitch = (pitch + dyDeg).coerceIn(-89f, 89f)
    }

    @Synchronized fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(5f, 10000f)
    }

    /**
     * Zooms toward screen point (x, y), like the desktop's zoom to the mouse cursor: the point under it on
     * the plane through the target (facing the camera) stays put, so the target moves toward it and you can
     * get close to any part of the view, not only the orbit centre.
     */
    @Synchronized fun zoomAt(factor: Float, x: Float, y: Float) {
        val (o, d) = ray(x, y)
        val n = towardsEye()
        val t = target.let { Vec3(it[0], it[1], it[2]) }
        val old = distance
        zoom(factor)
        val f = old / distance
        val along = d.dot(n)
        if (f == 1f || kotlin.math.abs(along) < 1e-6f) return
        val hit = (t - o).dot(n) / along
        if (hit <= 0f) return
        val p = o + d * hit
        target[0] = p.x + (t.x - p.x) / f
        target[1] = p.y + (t.y - p.y) / f
        target[2] = p.z + (t.z - p.z) / f
    }

    /** Pans in the view plane; dx/dy in screen pixels. */
    @Synchronized fun pan(dx: Float, dy: Float) {
        val scale = distance / height * 0.8f
        val (r, u) = axes()
        for (i in 0..2) target[i] += (-dx * r[i] + dy * u[i]) * scale
    }

    /** View preset: 0 iso, 1 top, 2 front, 3 left, 4 right, 5 bottom, 6 rear. */
    @Synchronized fun preset(view: Int) {
        when (view) {
            1 -> { yaw = -90f; pitch = 89f }
            2 -> { yaw = -90f; pitch = 0f }
            3 -> { yaw = 180f; pitch = 0f }
            4 -> { yaw = 0f; pitch = 0f }
            5 -> { yaw = -90f; pitch = -89f }
            6 -> { yaw = 90f; pitch = 0f }
            else -> { yaw = -60f; pitch = 40f }
        }
    }

    /** Screen pixel of world point [p], or null when it lies behind the camera. */
    @Synchronized fun project(p: Vec3): Pair<Float, Float>? {
        val vp = FloatArray(16).also { viewProjection(it) }
        val r = FloatArray(4)
        Matrix.multiplyMV(r, 0, vp, 0, floatArrayOf(p.x, p.y, p.z, 1f), 0)
        if (r[3] <= 0f) return null
        return (r[0] / r[3] + 1f) * width / 2 to (1f - r[1] / r[3]) * height / 2
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

    /** Unit vector from the target towards the eye (the desktop's -get_dir_forward()). */
    @Synchronized fun towardsEye(): Vec3 {
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        return Vec3((cos(pr) * cos(yr)).toFloat(), (cos(pr) * sin(yr)).toFloat(), sin(pr).toFloat())
    }

    /** Near and far clip distances of the projection. */
    @Synchronized fun clip() = max(0.5f, distance / 200f) to distance * 20f

    /** Fills [out] with projection * view. */
    @Synchronized fun viewProjection(out: FloatArray) {
        val e = eye()
        val proj = FloatArray(16)
        val view = FloatArray(16)
        val (near, far) = clip()
        Matrix.perspectiveM(proj, 0, FOV, width.toFloat() / height, near, far)
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

    companion object {
        /** Vertical field of view, degrees. */
        const val FOV = 35f
    }
}
