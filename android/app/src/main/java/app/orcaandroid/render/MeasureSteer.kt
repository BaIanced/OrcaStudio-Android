package app.orcaandroid.render

import android.view.View
import app.orcaandroid.core.Vec3
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Measure, steered-point camera (the user's vision, interaction plan step 6; a toggle beside the plain loupe): a held finger
 * steers the dot relatively (its moves, 1:1 on screen, so the closer the camera the finer the steps) and the camera follows,
 * easing the dot back to where it began. A slow or still finger frames what the snap caught ([focus]): the camera zooms
 * until the feature fills [FILL] of the view (no further: user 2026-10-27, "it just keeps zooming") and swings / tilts
 * towards a good look at it (a face or hole from the front, an edge from the side), at most [MAX_SWING_DEG] from the
 * starting view. A fast slide backs out quicker towards the starting view, so a wide slide gets out of a close-up. A second
 * finger freezes the camera; the dot still steers. Lifting puts the camera back where it was before the press.
 *
 * Runs on the UI thread; [onAim] gets the dot's screen spot each frame it or the camera changed (the host picks there).
 */
class MeasureSteer(private val view: View, private val camera: Camera, private val focus: () -> Focus?,
                   private val onAim: (Float, Float) -> Unit) {
    /** What to frame: the snapped [point], the feature's size (mm) and the best direction to see it from (towards the eye), if any. */
    class Focus(val point: Vec3, val size: Float, val look: Vec3?)

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

    /** Eases the view's angles a step [k] of the way to [yaw] / [pitch], about [p]. True when it moved. */
    private fun turn(p: Vec3, yaw: Float, pitch: Float, k: Float): Boolean {
        val (y0, p0) = camera.angles()
        val dy = wrap(yaw - y0) * k
        val dp = (pitch - p0) * k
        if (kotlin.math.abs(dy) < 0.01f && kotlin.math.abs(dp) < 0.01f) return false
        camera.orbitAbout(p, y0 + dy, p0 + dp)
        return true
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
                val f = focus()
                val distance = camera.state()[2]
                if (speed > FAST_DPS) {
                    // A fast slide backs out, angles too, towards the starting view.
                    if (distance < to[2]) { camera.zoomAt(exp(-ZOOM_OUT_PER_S * dt).coerceAtLeast(distance / to[2]), aimX, aimY); changed = true }
                    if (f != null) changed = turn(f.point, to[0], to[1], 1 - exp(-ZOOM_OUT_PER_S * dt)) || changed
                } else if (speed < SLOW_DPS && f != null) {
                    val k = 1 - exp(-dt / FRAME_S)
                    // Zoom until the feature fills FILL of the view's short side; never further out than the starting view.
                    val seen = camera.pxSize(f.point) * minOf(view.width, view.height)
                    val want = maxOf(f.size, MIN_FEATURE_MM) / FILL
                    val factor = exp(ln(seen / want) * k).coerceAtLeast(distance / to[2])
                    if (kotlin.math.abs(factor - 1f) > 1e-4f) { camera.zoomAbout(f.point, factor); changed = true }
                    f.look?.let { w ->
                        val (yaw, pitch) = aim(w, to[0], to[1])
                        changed = turn(f.point, yaw, pitch, k) || changed
                    }
                }
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
        /** Below this finger speed the camera frames the snap, above [FAST_DPS] it backs out (dp / s). */
        val SLOW_DPS get() = app.orcaandroid.core.Tuning["steer.slow"]
        val FAST_DPS get() = app.orcaandroid.core.Tuning["steer.fast"]
        /** Backing out (natural log of the zoom factor per second; angles ease back as fast). */
        val ZOOM_OUT_PER_S get() = app.orcaandroid.core.Tuning["steer.out_rate"]
        /** Framing: the feature fills this share of the view's short side; features count as at least [MIN_FEATURE_MM]. */
        val FILL get() = app.orcaandroid.core.Tuning["steer.fill"]
        val MIN_FEATURE_MM get() = app.orcaandroid.core.Tuning["steer.min_feature"]
        /** A corner snap has no segments, and a face / curve on a curved surface (a sphere's facets) is tiny: frame at least this size. */
        val CORNER_MM get() = app.orcaandroid.core.Tuning["steer.corner"]
        /** Framing: farthest the view swings / tilts from the starting view, and the pitch it keeps above the bed. */
        val MAX_SWING_DEG get() = app.orcaandroid.core.Tuning["steer.max_swing"]
        const val MIN_PITCH = 5f
        const val MAX_PITCH = 85f
        /** Framing's time constant (s): zoom and angles settle this smoothly. */
        val FRAME_S get() = app.orcaandroid.core.Tuning["steer.frame_s"]
        /** Framing a face: how far from the view now towards square-on (0 = no turn, 1 = square-on). */
        val FACE_SQUARE get() = app.orcaandroid.core.Tuning["steer.face_square"]
        /** Time constants (s): the speed's smoothing, the camera's follow and its way back after the lift. */
        val SPEED_S get() = app.orcaandroid.core.Tuning["steer.speed_s"]
        val FOLLOW_S get() = app.orcaandroid.core.Tuning["steer.follow_s"]
        val RETURN_S get() = app.orcaandroid.core.Tuning["steer.return_s"]
        const val EDGE_DP = 12f

        fun wrap(deg: Float) = ((deg % 360f) + 540f) % 360f - 180f

        private fun dirOf(yaw: Float, pitch: Float): Vec3 {
            val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
            return Vec3((kotlin.math.cos(p) * kotlin.math.cos(y)).toFloat(), (kotlin.math.cos(p) * kotlin.math.sin(y)).toFloat(), kotlin.math.sin(p).toFloat())
        }

        private fun unit(v: Vec3): Vec3? = v.length().takeIf { it > 1e-6f }?.let { v * (1f / it) }

        /**
         * Yaw / pitch looking from direction [w] (towards the eye), kept within [MAX_SWING_DEG] of the starting view
         * ([yaw0] / [pitch0]) and, from above, between [MIN_PITCH] and [MAX_PITCH].
         */
        fun aim(w: Vec3, yaw0: Float, pitch0: Float): Pair<Float, Float> {
            val start = dirOf(yaw0, pitch0)
            val angle = Math.toDegrees(kotlin.math.acos(w.dot(start).coerceIn(-1f, 1f).toDouble())).toFloat()
            // Past the cap: that share of the way (a linear blend, near enough to the great circle at these angles).
            val d = if (angle <= MAX_SWING_DEG) w else unit(start + (w - start) * (MAX_SWING_DEG / angle)) ?: start
            val yaw = Math.toDegrees(kotlin.math.atan2(d.y.toDouble(), d.x.toDouble())).toFloat()
            var pitch = Math.toDegrees(kotlin.math.asin(d.z.coerceIn(-1f, 1f).toDouble())).toFloat()
            if (pitch0 >= 0f) pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
            return yaw0 + wrap(yaw - yaw0) to pitch
        }

        /**
         * What to frame for a snap: [kind] and [lines] (x, y, z per end) of the caught feature, a hole's [diameter], a face's
         * [normal]; [toEye] = the view direction now (towards the eye). Null over the bed or nothing (no zoom in there).
         * A face is seen [FACE_SQUARE] of the way to square-on (some depth stays visible), a hole along its axis, an edge from the side.
         */
        fun focusOf(point: Vec3, kind: String?, lines: FloatArray, diameter: Float?, normal: Vec3?, toEye: Vec3): Focus? {
            if (kind == null || kind == "bed") return null
            val ends = (0 until lines.size / 3).map { Vec3(lines[3 * it], lines[3 * it + 1], lines[3 * it + 2]) }
            val span = if (ends.size >= 2) maxOf(ends.maxOf { it.x } - ends.minOf { it.x }, ends.maxOf { it.y } - ends.minOf { it.y },
                ends.maxOf { it.z } - ends.minOf { it.z }) else 0f
            val size = if (kind == "ring" && diameter != null) diameter else if (kind == "vertex" || kind == "face" || kind == "curve") maxOf(span, CORNER_MM) else span
            // A loop's normal (Newell's method over its segments); faces may come without one.
            fun loopNormal(): Vec3? {
                var n = Vec3.ZERO
                for (i in 0 until ends.size / 2) {
                    val a = ends[2 * i]; val b = ends[2 * i + 1]
                    n += Vec3((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y))
                }
                return unit(n)
            }
            val look = when (kind) {
                "face", "ring" -> (normal?.let { unit(it) } ?: loopNormal())?.let { n ->
                    val m = if (n.dot(toEye) < 0f) n * -1f else n
                    if (kind == "ring") m else unit(toEye + (m - toEye) * FACE_SQUARE)
                }
                "edge", "curve" -> {
                    // The edge's direction: from its first end to the end farthest from it; seen square to that.
                    val a = ends.firstOrNull()
                    val d = a?.let { s -> ends.maxByOrNull { (it - s).length() }?.let { unit(it - s) } }
                    d?.let { unit(toEye - it * toEye.dot(it)) }
                }
                else -> null
            }
            return Focus(point, size, look)
        }

        /** Camera.state() arrays [a] to [b] at [t]: angles and target linear, distance geometric (zoom feels even). */
        fun blend(a: FloatArray, b: FloatArray, t: Float) = FloatArray(a.size) { i ->
            if (i == 2) exp(ln(a[i]) + (ln(b[i]) - ln(a[i])) * t) else a[i] + (b[i] - a[i]) * t
        }
    }
}
