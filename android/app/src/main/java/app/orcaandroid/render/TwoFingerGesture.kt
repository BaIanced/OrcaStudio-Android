package app.orcaandroid.render

import kotlin.math.abs
import kotlin.math.hypot

/**
 * What a two-finger touch on the 3D view is: undecided until the fingers' gap or their midpoint moves past the touch
 * slop. A pinch only zooms (the midpoint wanders as fingers spread, which panned the view, user 2026-10-17); a
 * two-finger drag only pans (the gap drifts while dragging, which zoomed, user 2026-10-19) until the gap clearly changes
 * ([SWITCH_SLOPS]). Plain numbers in (no MotionEvent), so JVM tests can replay logged gestures.
 */
class TwoFingerGesture(private val slop: Float) {
    enum class Mode { UNDECIDED, PINCH, PAN }

    /** One move's outcome: where the pan grabs from (null = no pan), and a log line when the mode changed. */
    class Step(val panFromX: Float, val panFromY: Float, val pans: Boolean, val log: String?)

    var mode = Mode.UNDECIDED
        private set
    private var startSpan = 0f
    private var startX = 0f
    private var startY = 0f

    /**
     * Recent samples (ms, span, focus x, focus y), oldest at least [SWITCH_WINDOW_MS] back: a decided gesture switches
     * on recent motion only (measured from any of them). Measured from the landing, a long pan's drifting gap flipped it to PINCH whenever the
     * fingers came back near where they landed (the view stalled as the object reached the centre, user r308).
     */
    private val trail = ArrayDeque<FloatArray>()

    /** The second finger landed at time [t] (ms), fingers [span] apart, midpoint ([x], [y]). */
    fun down(t: Float, span: Float, x: Float, y: Float) {
        mode = Mode.UNDECIDED
        startSpan = span; startX = x; startY = y
        trail.clear()
        trail.addLast(floatArrayOf(t, span, x, y))
    }

    /** Gap change and midpoint travel since the landing (for logs). */
    fun gap(span: Float) = abs(span - startSpan)
    fun shift(x: Float, y: Float) = hypot(x - startX, y - startY)

    /** A move at [t]; ([lastX], [lastY]) = the midpoint the caller last panned to (it resets it when a third finger comes or goes). */
    fun move(t: Float, span: Float, x: Float, y: Float, lastX: Float, lastY: Float): Step {
        val before = mode
        trail.addLast(floatArrayOf(t, span, x, y))
        while (trail.size > 1 && t - trail[1][0] >= SWITCH_WINDOW_MS) trail.removeFirst()
        // Switches look for any recent sample the fingers clearly pinched (or panned) away from: measured only from the
        // oldest, a pinch right after a pan still saw the pan's travel and waited for it to leave the window.
        val toPinch = mode == Mode.PAN
        val ref = trail.firstOrNull { r ->
            val g = abs(span - r[1]); val s = hypot(x - r[2], y - r[3])
            if (toPinch) g > SWITCH_SLOPS * slop && g >= PINCH_LEAD * s else s > SWITCH_SLOPS * slop && s >= PINCH_LEAD * g
        } ?: trail.first()
        val recentGap = abs(span - ref[1])
        val recentShift = hypot(x - ref[2], y - ref[3])
        var log: String? = null
        when (mode) {
            Mode.UNDECIDED -> {
                // One finger still while the other spreads moves the midpoint half as far as the gap changes.
                val gap = gap(span)
                val shift = shift(x, y)
                // A drag's gap drifts too, so a pinch needs the gap to clearly lead (gap >= shift locked pans into zoom).
                if (gap > slop && gap >= PINCH_LEAD * shift) mode = Mode.PINCH
                else if (shift > slop) mode = Mode.PAN
                if (mode != Mode.UNDECIDED) log = "decided $mode gap=${gap.toInt()} shift=${shift.toInt()}"
            }
            Mode.PAN -> if (recentGap > SWITCH_SLOPS * slop && recentGap >= PINCH_LEAD * recentShift) {
                mode = Mode.PINCH
                log = "PAN -> PINCH gap=${recentGap.toInt()} shift=${recentShift.toInt()} (last ${(t - ref[0]).toInt()}ms)"
            }
            // Landing jitter can lock a pan into PINCH (gap 30 px vs shift 15 px at 54 ms, then a 172 px drag did
            // nothing, phone r307); a real pinch moves the midpoint at most half the gap change.
            Mode.PINCH -> if (recentShift > SWITCH_SLOPS * slop && recentShift >= PINCH_LEAD * recentGap) {
                mode = Mode.PAN
                log = "PINCH -> PAN gap=${recentGap.toInt()} shift=${recentShift.toInt()} (last ${(t - ref[0]).toInt()}ms)"
            }
        }
        // The move that turns the gesture into a pan catches up the travel spent deciding (25-45 px from the landing;
        // after a PINCH -> PAN switch, the trail's travel, ~90 px), so the pan doesn't start late.
        val step = when {
            mode != Mode.PAN -> Step(0f, 0f, false, log)
            before == Mode.PAN -> Step(lastX, lastY, true, log)
            before == Mode.UNDECIDED -> Step(startX, startY, true, log)
            else -> Step(ref[2], ref[3], true, log)
        }
        // A new mode measures its own way back from here (an older trail would still hold the motion that switched).
        if (mode != before && before != Mode.UNDECIDED) {
            trail.clear()
            trail.addLast(floatArrayOf(t, span, x, y))
        }
        return step
    }

    companion object {
        /** How many times the midpoint's travel the finger gap must change for a two-finger gesture to be a pinch. */
        const val PINCH_LEAD = 1.5f
        /** Touch slops of gap change (leading the midpoint by [PINCH_LEAD]) that turn a pan into a pinch, and of midpoint travel (leading the gap) back. */
        const val SWITCH_SLOPS = 3f
        /** How far back a decided gesture looks to switch between pan and pinch. */
        const val SWITCH_WINDOW_MS = 250f
    }
}
