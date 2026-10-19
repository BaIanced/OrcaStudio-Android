package app.orcaandroid.render

import app.orcaandroid.render.TwoFingerGesture.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Replays two-finger gestures as (ms, span, midpoint) samples. Cases mirror the phone scripts in orca-android-tools
 * (pan2j.sh, pan2b.sh, pinch.sh) and the logged user gestures; slop 24 px is the phone's.
 */
class TwoFingerGestureTest {
    private class Run(val modes: List<Mode>, val panned: Float)

    /** Samples every [dt] ms; [spans] and [xs]/[ys] give each sample (the first is the landing). */
    private fun replay(spans: List<Float>, xs: List<Float>, ys: List<Float>, dt: Float = 16f): Run {
        val g = TwoFingerGesture(24f)
        g.down(0f, spans[0], xs[0], ys[0])
        var lastX = xs[0]; var lastY = ys[0]
        var panned = 0f
        val modes = mutableListOf<Mode>()
        for (i in 1 until spans.size) {
            val s = g.move(i * dt, spans[i], xs[i], ys[i], lastX, lastY)
            // Total pan distance applied, as the camera would see it.
            if (s.pans) panned += hypot(xs[i] - s.panFromX, ys[i] - s.panFromY)
            lastX = xs[i]; lastY = ys[i]
            modes += g.mode
        }
        return Run(modes, panned)
    }

    private fun lerp(a: Float, b: Float, n: Int) = List(n + 1) { a + (b - a) * it / n }

    @Test fun straightPanIsPanAndPansTheWholeWay() {
        val r = replay(List(13) { 150f }, lerp(600f, 850f, 12), List(13) { 1000f })
        assertTrue(r.modes.last() == Mode.PAN && Mode.PINCH !in r.modes)
        assertEquals(250f, r.panned, 1f)
    }

    @Test fun landingJitterLockRecoversToPanWithCatchUp() {
        // Gap wobbles 30 px while still (decides PINCH, like r307's #9), then a 250 px drag.
        val spans = listOf(150f, 180f, 180f) + List(12) { 150f }
        val xs = listOf(600f, 600f, 600f) + lerp(600f, 850f, 12).drop(1) + 850f
        val r = replay(spans, xs, List(xs.size) { 1000f })
        assertEquals(Mode.PINCH, r.modes.first())
        assertEquals(Mode.PAN, r.modes.last())
        // Travel during the PINCH lock is caught up (within the switch window).
        assertTrue("panned ${r.panned}", r.panned > 200f)
    }

    @Test fun outAndBackPanWithDriftingGapStaysPan() {
        // User r308 stall: pan 400 px down and back to the landing point while the gap grows 8 px a step.
        val n = 15
        val ys = lerp(800f, 1200f, n) + lerp(1200f, 800f, n).drop(1)
        val spans = List(ys.size) { 200f + 4f * it }
        val r = replay(spans, List(ys.size) { 650f }, ys, dt = 30f)
        assertTrue("modes ${r.modes}", Mode.PINCH !in r.modes)
    }

    @Test fun pinchOutAndInStayPinch() {
        for ((a, b) in listOf(200f to 600f, 600f to 200f)) {
            val r = replay(lerp(a, b, 12), List(13) { 650f }, List(13) { 1000f })
            assertTrue("modes ${r.modes}", r.modes.all { it == Mode.PINCH } && r.panned == 0f)
        }
    }

    @Test fun pinchWithOneFingerStillIsPinch() {
        // One finger still, the other spreads: the midpoint moves half the gap change.
        val spans = lerp(200f, 500f, 12)
        val xs = spans.map { 500f + it / 2 }
        val r = replay(spans, xs, List(13) { 1000f })
        assertTrue("modes ${r.modes}", Mode.PAN !in r.modes)
    }

    @Test fun deliberatePinchAfterPanSwitchesToPinch() {
        // Pan 200 px, then hold the midpoint and spread 200 px fast.
        val spans = List(11) { 200f } + lerp(200f, 400f, 8).drop(1)
        val xs = lerp(500f, 700f, 10) + List(8) { 700f }
        val r = replay(spans, xs, List(xs.size) { 1000f })
        assertEquals(Mode.PAN, r.modes[9])
        assertEquals(Mode.PINCH, r.modes.last())
    }
}
