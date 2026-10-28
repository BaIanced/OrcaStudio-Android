package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Z rail's lift: snaps to the floor and to faces, else whole (or tenth) mm steps. */
class ZRailTest {
    @Test fun roundsWithoutSnap() {
        // A 10 mm box at 0..10 with nothing near: 12.4 mm rounds to 12, or to 12.4 in 0.1 steps.
        assertEquals(12f to null, ZRail.lift(12.4f, 0f, 10f, listOf(0f), 1f, 1f))
        assertEquals(12.4f, ZRail.lift(12.4f, 0f, 10f, listOf(0f), 1f, 0.1f).first, 1e-4f)
    }

    @Test fun snapsBottomOrTopOntoFaces() {
        // The box's bottom lands on a 20 mm top face: lift 20 within reach.
        assertEquals(20f to 20f, ZRail.lift(19.3f, 0f, 10f, listOf(0f, 20f), 1f, 1f))
        // Its top meets a face at 5 (sinking a hole flush): lift -5.
        assertEquals(-5f to 5f, ZRail.lift(-4.6f, 0f, 10f, listOf(0f, 5f), 1f, 1f))
        // Back on the floor from 7..17.
        assertEquals(-7f to 0f, ZRail.lift(-6.5f, 7f, 17f, listOf(0f), 1f, 1f))
        // The nearest of two snaps wins.
        assertEquals(3f to 3f, ZRail.lift(3.4f, 0f, 10f, listOf(3f, 4f), 1f, 1f))
        assertNull(ZRail.lift(1.5f, 0f, 10f, listOf(0f, 3f), 1f, 1f).second)
    }

    @Test fun middleSnapsOnlyWhenAsked() {
        // A 0..10 box dragged 94.6 mm: its middle (5) meets the plate middle at 100 -> lift 95.
        assertEquals(95f to 100f, ZRail.lift(94.6f, 0f, 10f, listOf(100f), 1f, 1f, mid = true))
        assertEquals(95f to null, ZRail.lift(94.6f, 0f, 10f, listOf(100f), 1f, 1f))
    }

    @Test fun gapGrowsWithTheBox() {
        assertEquals(4f, ZRail.gap(Vec3(0f, 0f, 0f), Vec3(5f, 5f, 5f)), 0f)
        assertEquals(25f, ZRail.gap(Vec3(0f, 0f, 0f), Vec3(100f, 20f, 10f)), 0f)
    }
}
