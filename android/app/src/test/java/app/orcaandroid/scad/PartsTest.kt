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

    @Test fun placedPartHasASizeSliderThatScalesIt() {
        val (g, dim) = Part.WHEEL.place(1, "Wheel 1") { it == "wheel_1_size" }
        assertEquals("wheel_1_size_2", dim.name)
        assertEquals(dim.name, g.bind[Shape.SCALE])
        fun width(v: Double): Double {
            val d = ShapeDesign(listOf(g), listOf(dim.copy(value = v)), 20)
            return d.bounds(d.shapes[0]).let { it.max.x - it.min.x }
        }
        assertEquals(40.0, width(100.0), 1e-6)
        assertEquals(80.0, width(200.0), 1e-6)
        assertEquals(10.0, width(25.0), 1e-6)
    }
}

class SavedPartsTest {
    private fun bound(): Pair<ShapeDesign, Shape> {
        val kid = Shape(2, Shape.Kind.BOX, "B", D3(10.0, 10.0, 10.0), bind = mapOf("size.x" to "w"))
        val g = Shape(1, Shape.Kind.GROUP, "G", D3(), D3(30.0, 5.0, 4.0), children = listOf(kid))
        return ShapeDesign(listOf(g), listOf(Dim("w", 10.0, 0.0, 100.0), Dim("other", 3.0, 0.0, 10.0)), 3) to g
    }

    @Test fun savesOnlyUsedSlidersAtTheOrigin() {
        val (d, g) = bound()
        val saved = ShapeDesign.decode(SavedParts.encode(d, g))!!
        assertEquals(listOf("w"), saved.dims.map { it.name })
        assertEquals(D3(), saved.shapes.single().pos)
    }

    @Test fun placeRenumbersAndRenamesTakenSliders() {
        val (d, g) = bound()
        val (p, dims) = SavedParts.place(SavedParts.encode(d, g), 20, "G 2") { it == "w" }!!
        assertEquals(listOf(20, 21), p.all().map { it.id }.toList())
        assertEquals("G 2", p.name)
        assertEquals("w_2", dims.single().name)
        assertEquals("w_2", p.children.single().bind["size.x"])
        val free = SavedParts.place(SavedParts.encode(d, g), 20, "G") { false }!!
        assertEquals("w", free.second.single().name)
    }
}
