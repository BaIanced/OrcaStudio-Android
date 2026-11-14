package app.orcaandroid.scad

import app.orcaandroid.scad.FitPresets.Fit
import app.orcaandroid.scad.FitPresets.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hole for..." presets: one test per column of the table (M3 row at least), the fit offset, the hexagon maths. */
class FitPresetsTest {
    private fun spec(kind: Kind, size: String, fit: Fit = Fit.TIGHT) = FitPresets.spec(kind, size, fit)!!

    @Test fun throughColumn() {
        val want = mapOf("M2" to 2.4, "M2.5" to 2.9, "M3" to 3.4, "M4" to 4.5, "M5" to 5.5, "M6" to 6.6, "M8" to 9.0)
        for ((size, d) in want) {
            val s = spec(Kind.THROUGH, size)
            assertEquals(size, d, s.diameter, 1e-9)
            assertEquals(0, s.sides)
            assertNull(s.depth)
            assertNull(s.cone)
        }
    }

    @Test fun tappingColumn() {
        val want = mapOf("M2" to 1.6, "M2.5" to 2.05, "M3" to 2.5, "M4" to 3.3, "M5" to 4.2, "M6" to 5.0, "M8" to 6.8)
        for ((size, d) in want) assertEquals(size, d, spec(Kind.TAPPING, size).diameter, 1e-9)
    }

    @Test fun nutPocketColumnIsAHexagonWithADepth() {
        val m3 = spec(Kind.NUT, "M3")
        assertEquals(6, m3.sides)
        assertEquals(2.4, m3.depth!!, 1e-9)
        // The size box is corner to corner: twice the circumradius of 5.5 across flats.
        assertEquals(2 * 3.175, m3.diameter, 0.002)
        val depths = mapOf("M2" to 1.6, "M2.5" to 2.0, "M4" to 3.2, "M5" to 4.7, "M6" to 5.2, "M8" to 6.8)
        for ((size, depth) in depths) assertEquals(size, depth, spec(Kind.NUT, size).depth!!, 1e-9)
        val flats = mapOf("M2" to 4.0, "M2.5" to 5.0, "M4" to 7.0, "M5" to 8.0, "M6" to 10.0, "M8" to 13.0)
        for ((size, af) in flats) assertEquals(size, FitPresets.hexCircumradius(af) * 2, spec(Kind.NUT, size).diameter, 1e-9)
    }

    @Test fun hexConversionFlatsToCircumradius() {
        assertEquals(3.175, FitPresets.hexCircumradius(5.5), 0.001)
        assertEquals(5.0, FitPresets.hexCircumradius(5.0 * Math.sqrt(3.0)), 1e-9)
    }

    @Test fun countersunkColumnIsA90DegreeConeOnTheThroughHole() {
        val heads = mapOf("M2" to 4.0, "M2.5" to 5.0, "M3" to 6.0, "M4" to 8.0, "M5" to 10.0, "M6" to 12.0, "M8" to 16.0)
        for ((size, head) in heads) {
            val s = spec(Kind.COUNTERSUNK, size)
            val c = s.cone!!
            assertEquals(size, head, c.head, 1e-9)
            assertEquals(spec(Kind.THROUGH, size).diameter, s.diameter, 1e-9)
            // 90 degrees: the cone is as tall as the radius grows.
            assertEquals(size, (c.head - s.diameter) / 2, c.height, 1e-9)
            assertEquals(size, s.diameter / c.head, c.topRatio, 1e-9)
        }
        val m3 = spec(Kind.COUNTERSUNK, "M3")
        assertEquals(1.3, m3.cone!!.height, 1e-9)
    }

    @Test fun magnetsHaveDiameterAndDepth() {
        val a = spec(Kind.MAGNET, "6 x 2")
        assertEquals(6.0, a.diameter, 1e-9)
        assertEquals(2.0, a.depth!!, 1e-9)
        val b = spec(Kind.MAGNET, "8 x 3")
        assertEquals(8.0, b.diameter, 1e-9)
        assertEquals(3.0, b.depth!!, 1e-9)
    }

    @Test fun heatSetDefaults() {
        assertEquals(4.0, spec(Kind.HEAT_SET, "M3").diameter, 1e-9)
        assertEquals(5.6, spec(Kind.HEAT_SET, "M4").diameter, 1e-9)
        assertEquals(6.4, spec(Kind.HEAT_SET, "M5").diameter, 1e-9)
        assertNull(FitPresets.spec(Kind.HEAT_SET, "M8", Fit.NORMAL))
        assertEquals(listOf("M3", "M4", "M5"), FitPresets.sizes(Kind.HEAT_SET))
    }

    @Test fun fitAddsToTheDiameterExceptTappingAndHeatSet() {
        assertEquals(listOf(0.0, 0.2, 0.4), Fit.entries.map { it.offset })
        for ((fit, add) in Fit.entries.map { it to it.offset }) {
            assertEquals(3.4 + add, spec(Kind.THROUGH, "M3", fit).diameter, 1e-9)
            assertEquals(6.0 + add, spec(Kind.MAGNET, "6 x 2", fit).diameter, 1e-9)
            assertEquals(2 * FitPresets.hexCircumradius(5.5 + add), spec(Kind.NUT, "M3", fit).diameter, 1e-9)
            assertEquals(2.5, spec(Kind.TAPPING, "M3", fit).diameter, 1e-9)
            assertEquals(4.0, spec(Kind.HEAT_SET, "M3", fit).diameter, 1e-9)
        }
        // M3 through, Normal: the 3.6 mm the builder test measures.
        assertEquals(3.6, spec(Kind.THROUGH, "M3", Fit.NORMAL).diameter, 1e-9)
        val cs = spec(Kind.COUNTERSUNK, "M3", Fit.NORMAL)
        assertEquals(3.6, cs.diameter, 1e-9)
        assertEquals(6.2, cs.cone!!.head, 1e-9)
    }

    @Test fun everyKindOffersItsSizesAndUnknownSizesAreNull() {
        for (k in Kind.entries) for (size in FitPresets.sizes(k)) assertNotNull("$k $size", FitPresets.spec(k, size, Fit.NORMAL))
        assertNull(FitPresets.spec(Kind.THROUGH, "M7", Fit.NORMAL))
        assertEquals(7, FitPresets.sizes(Kind.THROUGH).size)
    }

    @Test fun aHoleCylinderWithTheSpecCutsTheExpectedBore() {
        // The cylinder stays ordinary: the generated code is a cylinder of the preset's diameter, six-sided for a nut.
        val s = spec(Kind.NUT, "M3")
        val cyl = Shape(2, Shape.Kind.CYLINDER, "Hole", D3(s.diameter, s.diameter, s.depth!!), hole = true,
            extra = mapOf(Shape.SIDES to s.sides.toDouble()))
        val code = ShapeDesign(listOf(Shape(1, Shape.Kind.BOX, "Box", D3(20.0, 20.0, 5.0)), cyl), nextId = 3).toScad()
        assertTrue(code, code.contains("\$fn = 6"))
    }
}
