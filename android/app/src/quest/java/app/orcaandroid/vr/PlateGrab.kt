package app.orcaandroid.vr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Feel of plate placement, reloaded live from `vr_tuning.json` (VrActivity) until a panel exists. All values are starting guesses. */
internal class VrTuning {
    /** Hand smoothing (One Euro filter): lower [minCutoffHz] means steadier when slow, higher [beta] means less lag when fast. */
    var minCutoffHz = 1.5f
    var beta = 4f
    var stickDeadZone = 0.15f
    var stickSmoothMs = 80f
    /** Response curve: 1 is linear, 2 gives finer control near the centre of the stick. */
    var stickCurve = 2f
    /** Per second at full deflection, multiplicative: 1 means about 2.7 times the distance in a second. */
    var distanceRate = 1f
    var zoomRate = 0.7f
    /** The stronger axis must beat the weaker by this factor to cancel it, so pushing away doesn't also zoom. */
    var axisLock = 1.5f
    /** False: hold the grip to carry the plate. True: click to pick up, click to drop. */
    var grabToggle = false
}

/**
 * One-controller plate placement. Aim a controller at the plate and grip to grab: the held point then rides the aim ray at
 * a fixed distance. Stick up is farther and down is closer; right is bigger and left is smaller. Either hand works, and only
 * one holds at a time. Only the user moves anything: no inertia after release. The plate stays level and keeps its yaw.
 * Plain Kotlin, no allocation per frame.
 */
internal class PlateGrab(var tuning: VrTuning = VrTuning()) {
    /** Where the plate centre sits in LOCAL, metres, and its zoom (1 is true size). */
    var x = 0f
    var y = VrMath.TABLE_Y
    var z = VrMath.TABLE_AHEAD
    var scale = 1f

    /** Bed bounds relative to the plate centre, in mm, set when the scene loads. */
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f

    /** Per hand, for the ray overlay. */
    val rayLength = FloatArray(XrShim.HANDS) { IDLE_RAY_M }
    val rayState = IntArray(XrShim.HANDS)

    /** The holding hand, or -1. */
    var holder = -1
        private set

    private val wasGrip = BooleanArray(XrShim.HANDS)
    private var wasClick = false
    private var heldDistance = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetZ = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var rawZ = 0f
    private var speed = 0f
    private var stickX = 0f
    private var stickY = 0f
    /** Zoom stopped at 1:1 on the way through and waits for the stick to come back to centre. */
    private var heldAtTrueSize = false

    fun setBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        this.minX = minX
        this.minY = minY
        this.maxX = maxX
        this.maxY = maxY
    }

    fun place(x: Float, y: Float, z: Float, scale: Float) {
        this.x = x.coerceIn(-MAX_DISTANCE_M, MAX_DISTANCE_M)
        this.y = y.coerceIn(-MAX_DISTANCE_M, MAX_DISTANCE_M)
        this.z = z.coerceIn(-MAX_DISTANCE_M, MAX_DISTANCE_M)
        this.scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** One frame. [hands] is laid out as in [XrShim]; [dt] is in seconds. Returns [NONE], [MOVED] or [RELEASED]. */
    fun update(hands: FloatArray, dt: Float): Int {
        for (hand in 0 until XrShim.HANDS) updateRay(hands, hand)
        var result = NONE
        val current = holder
        if (current >= 0) {
            val o = current * XrShim.FLOATS_PER_HAND
            val grip = hands[o + XrShim.HAND_GRIP] > 0.5f
            val clicked = tuning.grabToggle && grip && !wasGrip[current]
            if (hands[o + XrShim.HAND_ACTIVE] < 0.5f || (!tuning.grabToggle && !grip) || clicked) {
                holder = -1
                result = RELEASED
            } else {
                carry(hands, o, dt.coerceIn(MIN_DT, MAX_DT))
                rayLength[current] = heldDistance
                rayState[current] = RAY_GRABBED
                result = MOVED
            }
        } else {
            for (hand in 0 until XrShim.HANDS) {
                val o = hand * XrShim.FLOATS_PER_HAND
                if (hands[o + XrShim.HAND_GRIP] > 0.5f && !wasGrip[hand] && rayState[hand] == RAY_HOVER) {
                    startGrab(hands, o, hand)
                    break
                }
            }
        }
        for (hand in 0 until XrShim.HANDS) {
            val o = hand * XrShim.FLOATS_PER_HAND
            // A grip still held when tracking returns is not a new press.
            wasGrip[hand] = hands[o + XrShim.HAND_GRIP] > 0.5f || hands[o + XrShim.HAND_ACTIVE] < 0.5f
        }
        return result
    }

    private fun updateRay(h: FloatArray, hand: Int) {
        val o = hand * XrShim.FLOATS_PER_HAND
        rayState[hand] = RAY_NONE
        rayLength[hand] = IDLE_RAY_M
        if (h[o + XrShim.HAND_ACTIVE] < 0.5f) return
        rayState[hand] = RAY_IDLE
        val dy = aimY(h, o + XrShim.HAND_POSE)
        if (abs(dy) < 1e-5f) return
        val t = (y - h[o + XrShim.HAND_POSE + 1]) / dy
        if (t <= 0f) return
        val mm = 0.001f * scale
        val u = (h[o + XrShim.HAND_POSE] + t * aimX(h, o + XrShim.HAND_POSE) - x) / mm
        val v = -(h[o + XrShim.HAND_POSE + 2] + t * aimZ(h, o + XrShim.HAND_POSE) - z) / mm
        val margin = HOVER_MARGIN_M / mm
        if (u < minX - margin || u > maxX + margin || v < minY - margin || v > maxY + margin) return
        rayLength[hand] = t
        rayState[hand] = RAY_HOVER
    }

    private fun startGrab(h: FloatArray, o: Int, hand: Int) {
        holder = hand
        heldDistance = rayLength[hand].coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)
        val p = o + XrShim.HAND_POSE
        offsetX = x - (h[p] + heldDistance * aimX(h, p))
        offsetY = y - (h[p + 1] + heldDistance * aimY(h, p))
        offsetZ = z - (h[p + 2] + heldDistance * aimZ(h, p))
        rawX = x
        rawY = y
        rawZ = z
        speed = 0f
        stickX = 0f
        stickY = 0f
        heldAtTrueSize = false
        wasClick = true // A stick already pressed at grab time is not a reset.
        rayState[hand] = RAY_GRABBED
    }

    private fun carry(h: FloatArray, o: Int, dt: Float) {
        var sx = shape(h[o + XrShim.HAND_STICK_X])
        var sy = shape(h[o + XrShim.HAND_STICK_Y])
        if (abs(sx) * tuning.axisLock < abs(sy)) sx = 0f
        else if (abs(sy) * tuning.axisLock < abs(sx)) sy = 0f
        val blend = 1 - exp(-dt * 1000f / tuning.stickSmoothMs.coerceAtLeast(1f))
        stickX += (sx - stickX) * blend
        stickY += (sy - stickY) * blend
        if (abs(sy) + abs(stickY) < STICK_REST) stickY = 0f
        if (abs(sx) + abs(stickX) < STICK_REST) stickX = 0f

        heldDistance = (heldDistance * exp(tuning.distanceRate * stickY * dt)).coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)

        val click = h[o + XrShim.HAND_CLICK] > 0.5f
        var zoomed = scale
        if (click && !wasClick) {
            zoomed = 1f
            stickX = 0f
        } else if (sx == 0f) {
            heldAtTrueSize = false
        } else if (!heldAtTrueSize) {
            zoomed = (scale * exp(tuning.zoomRate * stickX * dt)).coerceIn(MIN_SCALE, MAX_SCALE)
            if ((scale - 1f) * (zoomed - 1f) < 0f) {
                zoomed = 1f
                heldAtTrueSize = true
            }
        }
        wasClick = click
        // Zoom about the held point, so the ray stays on it.
        val ratio = zoomed / scale
        offsetX *= ratio
        offsetY *= ratio
        offsetZ *= ratio
        scale = zoomed

        val p = o + XrShim.HAND_POSE
        filter(
            h[p] + heldDistance * aimX(h, p) + offsetX,
            h[p + 1] + heldDistance * aimY(h, p) + offsetY,
            h[p + 2] + heldDistance * aimZ(h, p) + offsetZ,
            dt,
        )
    }

    /** One Euro filter on the plate position: steady while the hand is nearly still, responsive when it moves. */
    private fun filter(tx: Float, ty: Float, tz: Float, dt: Float) {
        val dx = tx - rawX
        val dy = ty - rawY
        val dz = tz - rawZ
        rawX = tx
        rawY = ty
        rawZ = tz
        speed += (sqrt(dx * dx + dy * dy + dz * dz) / dt - speed) * alpha(SPEED_CUTOFF_HZ, dt)
        val a = alpha(tuning.minCutoffHz + tuning.beta * speed, dt)
        x += (tx - x) * a
        y += (ty - y) * a
        z += (tz - z) * a
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz.coerceAtLeast(0.01f))
        return 1f / (1f + tau / dt)
    }

    /** Dead zone, then the response curve, keeping the sign. */
    private fun shape(value: Float): Float {
        val zone = tuning.stickDeadZone.coerceIn(0f, 0.9f)
        val size = abs(value)
        if (size <= zone) return 0f
        val out = ((size - zone) / (1 - zone)).coerceAtMost(1f).pow(tuning.stickCurve.coerceAtLeast(0.1f))
        return if (value < 0) -out else out
    }

    // The aim direction is -Z of the pose at [p] (position x y z, quaternion x y z w).
    private fun aimX(h: FloatArray, p: Int) = -2 * (h[p + 3] * h[p + 5] + h[p + 6] * h[p + 4])
    private fun aimY(h: FloatArray, p: Int) = -2 * (h[p + 4] * h[p + 5] - h[p + 6] * h[p + 3])
    private fun aimZ(h: FloatArray, p: Int) = -(1 - 2 * (h[p + 3] * h[p + 3] + h[p + 4] * h[p + 4]))

    companion object {
        const val NONE = 0
        const val MOVED = 1
        const val RELEASED = 2

        const val RAY_NONE = 0
        const val RAY_IDLE = 1
        const val RAY_HOVER = 2
        const val RAY_GRABBED = 3

        const val IDLE_RAY_M = 1.5f
        const val MIN_DISTANCE_M = 0.2f
        const val MAX_DISTANCE_M = 6f
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 8f
        /** Aiming just off the bed edge still counts as on it. */
        private const val HOVER_MARGIN_M = 0.03f
        private const val SPEED_CUTOFF_HZ = 1f
        private const val STICK_REST = 0.001f
        private const val MIN_DT = 0.001f
        private const val MAX_DT = 0.1f
    }
}
