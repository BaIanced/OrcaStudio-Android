package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignMathTest {
    private val a = Footprint(0f, 0f, 10f, 20f)
    private val b = Footprint(30f, 10f, 40f, 14f)

    @Test fun centerMovesTheGroupBoxToThePoint() {
        // Group box 0..40 x 0..20: centre (20, 10) -> (100, 100).
        val (dx, dy) = AlignMath.center(listOf(a, b), 100f, 100f)
        assertEquals(80f, dx, 1e-4f)
        assertEquals(90f, dy, 1e-4f)
    }

    @Test fun centerOfOneObjectIsItsOwnCentre() {
        val (dx, dy) = AlignMath.center(listOf(b), 0f, 0f)
        assertEquals(-35f, dx, 1e-4f)
        assertEquals(-12f, dy, 1e-4f)
    }

    @Test fun leftAndRightUseTheGroupEdges() {
        assertEquals(listOf(0f to 0f, -30f to 0f), AlignMath.align(listOf(a, b), AlignKind.LEFT))
        assertEquals(listOf(30f to 0f, 0f to 0f), AlignMath.align(listOf(a, b), AlignKind.RIGHT))
    }

    @Test fun centreXLinesUpTheMiddles() {
        // Group centre x = 20: a (centre 5) +15, b (centre 35) -15.
        assertEquals(listOf(15f to 0f, -15f to 0f), AlignMath.align(listOf(a, b), AlignKind.CENTER_X))
    }

    @Test fun frontMiddleBackUseY() {
        assertEquals(listOf(0f to 0f, 0f to -10f), AlignMath.align(listOf(a, b), AlignKind.FRONT))
        assertEquals(listOf(0f to 0f, 0f to 6f), AlignMath.align(listOf(a, b), AlignKind.BACK))
        // Group centre y = 10: a is already there, b (centre 12) -2.
        assertEquals(listOf(0f to 0f, 0f to -2f), AlignMath.align(listOf(a, b), AlignKind.CENTER_Y))
    }
}
