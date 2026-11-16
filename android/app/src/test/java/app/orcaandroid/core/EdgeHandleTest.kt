package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the edge handle's size settings (T-087): defaults inside their ranges, and a touch box that is at least 48 dp wide,
 * 16 dp taller than the tab and never over Android's 200 dp gesture exclusion cap, across the whole height range.
 */
class EdgeHandleTest {

    @Test fun defaultsInsideRanges() {
        assertTrue(ViewDefaults.EDGE_HANDLE_WIDTH in ViewDefaults.EDGE_HANDLE_WIDTHS)
        assertTrue(ViewDefaults.EDGE_HANDLE_HEIGHT in ViewDefaults.EDGE_HANDLE_HEIGHTS)
        assertTrue(ViewDefaults.EDGE_HANDLE_ALPHA in ViewDefaults.EDGE_HANDLE_ALPHAS)
    }

    @Test fun touchBox() {
        assertEquals(48f to 112f, ViewDefaults.edgeHandleTouch(ViewDefaults.EDGE_HANDLE_WIDTH, ViewDefaults.EDGE_HANDLE_HEIGHT))
        assertEquals(48f to 64f, ViewDefaults.edgeHandleTouch(12f, 48f))
        assertEquals(56f to 200f, ViewDefaults.edgeHandleTouch(40f, 184f))
        // Out of range (an old or hand-edited value): the height is still capped.
        assertEquals(200f, ViewDefaults.edgeHandleTouch(20f, 400f).second)
    }
}
