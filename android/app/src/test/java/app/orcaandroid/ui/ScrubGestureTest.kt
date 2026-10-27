package app.orcaandroid.ui

import app.orcaandroid.ui.components.ScrubGesture
import app.orcaandroid.ui.components.scrubSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubGestureTest {
    private val dp = 2f

    @Test fun horizontalDragChangesValueOverRange() {
        val g = ScrubGesture(dp, 3, 1)
        var f = 0f
        repeat(60) { f += g.move(10f, if (it % 2 == 0) 1f else -1f) }
        // 600 px = 300 dp = the whole range; the jitter never switches to picking.
        assertEquals(1f, f, 1e-4f)
        assertFalse(g.picking)
        assertEquals(1, g.index)
    }

    @Test fun verticalDragPicksRows() {
        val g = ScrubGesture(dp, 4, 0)
        g.move(0f, 10f)
        assertTrue(g.picking)
        repeat(8) { g.move(0f, 10f) }      // 90 px = 45 dp: first step past 40 dp
        assertEquals(1, g.index)
        repeat(10) { g.move(0f, 10f) }     // +100 px = 50 dp more: the next row every 48 dp
        assertEquals(2, g.index)
        repeat(40) { g.move(0f, 10f) }     // clamped at the last row, no wind-up
        assertEquals(3, g.index)
        repeat(9) { g.move(0f, -10f) }     // back up past 40 dp from the clamp
        assertEquals(2, g.index)
    }

    @Test fun driftSwitchesAxis() {
        val g = ScrubGesture(dp, 3, 0)
        g.move(10f, 0f)
        assertFalse(g.picking)
        repeat(9) { g.move(0f, 10f) }      // 90 px straight down = 45 dp: picking, one row
        assertTrue(g.picking)
        assertEquals(1, g.index)
        var f = 0f
        repeat(12) { f += g.move(10f, 0f) } // sideways again (switch on the 9th move, which is 0): back to the value
        assertFalse(g.picking)
        assertTrue(f > 0f)
    }

    @Test fun snapKeepsStepAndRange() {
        assertEquals(4.2f, scrubSnap(4.23f, 0.5f..15f, 0.1f), 1e-4f)
        assertEquals(15f, scrubSnap(20f, 0.5f..15f, 0.1f), 1e-4f)
        assertEquals(30f, scrubSnap(30.4f, 5f..89f, 1f), 1e-4f)
    }
}
