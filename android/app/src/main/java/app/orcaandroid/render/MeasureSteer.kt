package app.orcaandroid.render

import android.view.View
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Measure, steered-point camera (the user's vision, interaction plan step 6; a toggle beside the plain loupe): a held finger
 * steers the dot relatively (its moves, 1:1 on screen, so the closer the camera the finer the steps) and the camera follows,
 * easing the dot back to where it began. A slow or still finger zooms in gently towards the dot, a fast slide zooms out
 * quicker (to the starting view at most), so a wide slide gets out of a close-up. A second finger freezes the camera; the
 * dot still steers. Lifting puts the camera back where it was before the press.
 *
 * Runs on the UI thread; [onAim] gets the dot's screen spot each frame it or the camera changed (the host picks there).
 */
class MeasureSteer(private val view: View, private val camera: Camera, private val onAim: (Float, Float) -> Unit) {
    private val density = view.resources.displayMetrics.density
    /** The camera before the press, or null when not steering. */
    private var before: FloatArray? = null
    private var homeX = 0f
    private var homeY = 0f
    var aimX = 0f
        private set
    var aimY = 0f
        private set
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    /** Finger travel (px) since the last frame, and its smoothed speed (dp / s). */
    private var travel = 0f
    private var speed = 0f
    private var frozen = false
    private var lastFrame = 0L
    private var changed = false
    /** Putting the camera back after the lift: from, and how far along (0..1). */
    private var returnFrom: FloatArray? = null
    private var returnT = 0f
    private var ticking = false

    val active get() = before != null && returnFrom == null

    /** The press: the finger at (x, y), the dot at (ax, ay). A press during the camera's way back keeps the first view to return to. */
    fun begin(x: Float, y: Float, ax: Float, ay: Float) {
        before = if (returnFrom != null) before else camera.state()
        returnFrom = null
        homeX = ax; homeY = ay; aimX = ax; aimY = ay
        lastX = x; lastY = y
        travel = 0f; speed = 0f; frozen = false
        changed = true
        schedule()
    }

    /** The steering finger moved to (x, y): the dot moves as much, kept on screen. */
    fun move(x: Float, y: Float) {
        if (!active) return
        if (!lastX.isNaN()) {
            val dx = x - lastX
            val dy = y - lastY
            travel += hypot(dx, dy)
            val m = EDGE_DP * density
            aimX = (aimX + dx).coerceIn(m, view.width - m)
            aimY = (aimY + dy).coerceIn(m, view.height - m)
            changed = true
        }
        lastX = x; lastY = y
    }

    /** A second finger: the camera holds still (or moves again once it lifts). The next move re-bases, as the steering finger may change. */
    fun freeze(on: Boolean) {
        frozen = on
        lastX = Float.NaN
        lastY = Float.NaN
    }

    /** The lift or a cancel (call after the last pick): the camera eases back to the view before the press. */
    fun end() {
        if (before == null || returnFrom != null) return
        returnFrom = camera.state()
        returnT = 0f
        schedule()
    }

    private fun schedule() {
        if (ticking) return
        ticking = true
        lastFrame = android.os.SystemClock.uptimeMillis()
        view.postOnAnimation(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.1f)
            lastFrame = now
            val from = returnFrom
            val to = before
            if (to == null) { ticking = false; return }
            if (from != null) {
                returnT = (returnT + dt / RETURN_S).coerceAtMost(1f)
                camera.restore(blend(from, to, returnT.let { it * it * (3 - 2 * it) }))
                if (returnT >= 1f) { before = null; returnFrom = null; ticking = false; return }
                view.postOnAnimation(this)
                return
            }
            val inst = travel / density / dt
            travel = 0f
            speed += (inst - speed) * (1 - exp(-dt / SPEED_S))
            if (!frozen) {
                val distance = camera.state()[2]
                val limitIn = to[2] / MAX_ZOOM
                val factor = when {
                    speed < SLOW_DPS && distance > limitIn -> exp(ZOOM_IN_PER_S * dt).coerceAtMost(distance / limitIn)
                    speed > FAST_DPS && distance < to[2] -> exp(-ZOOM_OUT_PER_S * dt).coerceAtLeast(distance / to[2])
                    else -> 1f
                }
                if (factor != 1f) { camera.zoomAt(factor, aimX, aimY); changed = true }
                // The camera follows: what is under the dot slides back towards where the dot began.
                val k = 1 - exp(-dt / FOLLOW_S)
                val vx = (homeX - aimX) * k
                val vy = (homeY - aimY) * k
                if (hypot(vx, vy) > 0.05f) { camera.pan(vx, vy); aimX += vx; aimY += vy; changed = true }
            }
            if (changed) { changed = false; onAim(aimX, aimY) }
            view.postOnAnimation(this)
        }
    }

    companion object {
        /** Below this finger speed the camera zooms in, above [FAST_DPS] out (dp / s). */
        const val SLOW_DPS = 60f
        const val FAST_DPS = 500f
        /** Zoom rates (natural log of the factor per second): in gently (~1.5x a second), out about three times as fast. */
        const val ZOOM_IN_PER_S = 0.4f
        const val ZOOM_OUT_PER_S = 1.3f
        /** Closest zoom, as a factor of the starting view. */
        const val MAX_ZOOM = 12f
        /** Time constants (s): the speed's smoothing, the camera's follow and its way back after the lift. */
        const val SPEED_S = 0.12f
        const val FOLLOW_S = 0.35f
        const val RETURN_S = 0.3f
        const val EDGE_DP = 12f

        /** Camera.state() arrays [a] to [b] at [t]: angles and target linear, distance geometric (zoom feels even). */
        fun blend(a: FloatArray, b: FloatArray, t: Float) = FloatArray(a.size) { i ->
            if (i == 2) exp(ln(a[i]) + (ln(b[i]) - ln(a[i])) * t) else a[i] + (b[i] - a[i]) * t
        }
    }
}
