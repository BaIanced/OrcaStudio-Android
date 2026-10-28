package app.orcaandroid.ui

import app.orcaandroid.ui.components.NumberScrub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberScrubTest {
    private val dp = 2f
    private val step = NumberScrub.STEP_DP * dp

    @Test fun sidewaysMovesWholeUnits() {
        val s = NumberScrub(20.0, dp)
        assertEquals(20.0, s.move(step * 0.4f, 0f), 1e-9)
        assertEquals(21.0, s.move(step * 0.2f, 0f), 1e-9)
        assertEquals(18.0, s.move(-step * 3f, 0f), 1e-9)
        assertFalse(s.fine)
    }

    @Test fun fingerAboveStartGivesTenths() {
        val s = NumberScrub(20.0, dp)
        val up = -(NumberScrub.FINE_DP + 1) * dp
        assertEquals(20.3, s.move(step * 3f, up), 1e-9)
        assertTrue(s.fine)
        // Back down: whole units again, from where the fine drag left it.
        assertEquals(21.0, s.move(step * 0.7f, 0f), 1e-9)
    }

    @Test fun neverBelowMin() {
        val s = NumberScrub(2.0, dp, min = 0.1)
        assertEquals(0.1, s.move(-step * 10f, 0f), 1e-9)
        assertEquals(1.0, s.move(step * 1f, 0f), 1e-9)
    }
}
