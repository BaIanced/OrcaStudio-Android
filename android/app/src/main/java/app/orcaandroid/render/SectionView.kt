package app.orcaandroid.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orcaandroid.core.Vec3

/**
 * The desktop's section view (GLCanvas3D SectionView): a plane facing the camera, swept through the
 * objects' bounding sphere from the camera side (ratio 0, off) to the far side (1). The plane keeps
 * the angle it had when first turned on until "Set viewing angle". Compose state, read by the panel.
 */
class SectionView(private val camera: Camera) {
    var ratio by mutableFloatStateOf(0f)
        private set
    /** Towards the camera; null until first aimed. */
    var normal by mutableStateOf<Vec3?>(null)
        private set
    var panelOpen by mutableStateOf(false)
    /** The plane last handed to the renderer, for picking; null when off. */
    var current: FloatArray? = null
    private var lastRatio = 0f

    val active get() = ratio > 0f

    fun set(value: Float) {
        val r = value.coerceIn(0f, 1f)
        if (r == ratio) return
        if (r == 0f) lastRatio = ratio
        ratio = r
        if (normal == null) alignToCamera()
    }

    fun toggle() = set(if (active) 0f else if (lastRatio > 0f) lastRatio else 0.5f)

    fun alignToCamera() { normal = camera.towardsEye() }

    /** Alt+wheel: one notch moves the cut by 1 %, as GLCanvas3D::on_mouse_wheel. */
    fun step(up: Boolean) = set(ratio + if (up) 0.01f else -0.01f)

    /**
     * The plane for [PlateRenderer.section] around the box [min]..[max] (GLCanvas3D::_get_section_view_plane),
     * or null when off.
     */
    fun plane(min: Vec3, max: Vec3): FloatArray? {
        val n = normal ?: return null
        if (!active) return null
        val center = (min + max) * 0.5f
        val radius = (max - min).length() * 0.5f
        return floatArrayOf(n.x, n.y, n.z, n.dot(center) + radius - 2f * radius * ratio)
    }

    companion object {
        /**
         * Where a pick ray [origin] + t [dir] enters the kept side of [plane] (as the desktop's raycaster
         * skips clipped hits), or null when the ray never does.
         */
        fun rayStart(plane: FloatArray, origin: Vec3, dir: Vec3): Vec3? {
            val n = Vec3(plane[0], plane[1], plane[2])
            val side = n.dot(origin) - plane[3]
            val along = n.dot(dir)
            return when {
                side <= 0f -> origin
                along >= 0f -> null
                else -> origin + dir * (side / -along)
            }
        }

        /** A hit at [point] is visible: not on the cut-away side (a little slack for the cut face itself). */
        fun kept(plane: FloatArray, point: Vec3) = Vec3(plane[0], plane[1], plane[2]).dot(point) <= plane[3] + 1e-3f
    }
}
