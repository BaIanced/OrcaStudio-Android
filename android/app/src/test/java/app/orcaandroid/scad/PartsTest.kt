package app.orcaandroid.scad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ready-made parts: unique ids from the first free one, a sane size, and they survive the design's JSON round trip. */
class PartsTest {
    @Test fun partsAreNumberedGroupsThatRoundTrip() {
        for (p in Part.entries) {
            val g = p.build(7, "P")
            val ids = g.all().map { it.id }.toList()
            assertEquals(p.name, (7 until 7 + ids.size).toList(), ids)
            assertTrue(g.isGroup)
            val d = ShapeDesign(listOf(g), nextId = 7 + ids.size)
            val b = d.bounds(g)
            assertEquals(p.name, 0.0, b.min.z, 1e-9)
            assertTrue(p.name, b.max.x - b.min.x in 20.0..60.0 && b.max.z in 8.0..45.0)
            assertEquals(d.toScad(), ShapeDesign.decode(d.encode())!!.toScad())
        }
    }

    @Test fun wheelIsFortyMillimetresWithAnAxleHole() {
        val d = ShapeDesign(listOf(Part.WHEEL.build(1, "W")))
        assertTrue(d.toScad().contains("difference"))
        assertEquals(40.0, d.bounds(d.shapes[0]).let { it.max.x - it.min.x }, 1e-6)
    }
}
