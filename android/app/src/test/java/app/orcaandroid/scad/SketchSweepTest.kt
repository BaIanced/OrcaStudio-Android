package app.orcaandroid.scad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sketch's twist (extruded) and rise per turn (revolved, a coil) in the OpenSCAD code. */
class SketchSweepTest {
    private val ring = Sketch().plus(Loop.rect(P2(5.0, 0.0), P2(7.0, 2.0)), rect = true)

    private fun sketch(extra: Map<String, Double>) =
        ShapeDesign(listOf(Shape(1, Shape.Kind.SKETCH, "S", D3(14.0, 14.0, 2.0), extra = extra, sketch = ring)))

    @Test fun aRiseMakesARevolvedSketchACoil() {
        val code = sketch(mapOf(Shape.REVOLVE to 1.0, Shape.ANGLE to 1080.0, Shape.PITCH to 4.0)).toScad()
        assertTrue(code.contains("module coil("))
        assertTrue(code.contains("coil([[5, 0], [7, 0], [7, 2], [5, 2]], 14 / 14, 2 / 2, 2, 1080, 4, sides(max(14, 14)));"))
        assertFalse(code.contains("rotate_extrude"))
    }

    @Test fun aClockwiseOutlineIsTurnedRoundForTheCoil() {
        val cw = Sketch(listOf(Loop(listOf(P2(5.0, 0.0), P2(5.0, 2.0), P2(7.0, 2.0), P2(7.0, 0.0)))))
        val code = ShapeDesign(listOf(Shape(1, Shape.Kind.SKETCH, "S", D3(14.0, 14.0, 2.0),
            extra = mapOf(Shape.REVOLVE to 1.0, Shape.PITCH to 3.0), sketch = cw))).toScad()
        assertTrue(code.contains("coil([[7, 0], [7, 2], [5, 2], [5, 0]]"))
    }

    @Test fun noRiseKeepsThePlainRevolveAndNoCoilModule() {
        val code = sketch(mapOf(Shape.REVOLVE to 1.0, Shape.ANGLE to 180.0)).toScad()
        assertTrue(code.contains("rotate_extrude(angle = 180"))
        assertFalse(code.contains("module coil("))
    }

    @Test fun resamplingStartsOnThePlusXSideAndKeepsTheDirection() {
        val sq = listOf(P2(-1.0, -1.0), P2(1.0, -1.0), P2(1.0, 1.0), P2(-1.0, 1.0))
        val r = Sketch.resample(sq, 8)
        assertEquals(8, r.size)
        val want = listOf(P2(1.0, 0.0), P2(1.0, 1.0), P2(0.0, 1.0), P2(-1.0, 1.0), P2(-1.0, 0.0), P2(-1.0, -1.0), P2(0.0, -1.0), P2(1.0, -1.0))
        r.zip(want).forEach { (a, b) -> assertEquals(0.0, a.dist(b), 1e-9) }
    }

    @Test fun aTopOutlineLoftsTheExtrusion() {
        val circle = Sketch().plus(Loop.circle(P2(0.0, 0.0), 5.0))
        val sq = Sketch.START
        val code = ShapeDesign(listOf(Shape(1, Shape.Kind.SKETCH, "S", D3(20.0, 20.0, 10.0), sketch = sq, top = circle))).toScad()
        assertTrue(code.contains("module loft("))
        assertTrue(code.contains("loft([[10, 0], "))
        assertTrue(code.contains("20 / 20, 20 / 20, 10, 0, 1);"))
        assertFalse(code.contains("linear_extrude("))
        // Saved and read back with the design.
        assertEquals(circle.loops, Shape.of(Shape(1, Shape.Kind.SKETCH, "S", D3(), sketch = sq, top = circle).json()).top?.loops)
    }

    @Test fun pathFramesAreMitredAtCorners() {
        val f = Sweep.frames(listOf(P2(0.0, 0.0), P2(10.0, 0.0), P2(10.0, 10.0)), closed = false)
        listOf(P2(0.0, 1.0), P2(-1.0, 1.0), P2(-1.0, 0.0)).zip(f) { want, got -> assertEquals(0.0, want.dist(got.side), 1e-9) }
        val e = Sweep.frames(listOf(P2(0.0, 0.0), P2(10.0, 0.0)), closed = false, extend = 0.1)
        assertEquals(0.0, e[0].p.dist(P2(-0.1, 0.0)), 1e-9)
        assertEquals(0.0, e[1].p.dist(P2(10.1, 0.0)), 1e-9)
    }

    @Test fun anOpenPathSweepsTheProfileAndFitsTheBox() {
        val profile = Sketch().plus(Loop.rect(P2(-1.0, 3.0), P2(1.0, 5.0)), rect = true)
        val line = Sketch(listOf(Loop(listOf(P2(0.0, 0.0), P2(10.0, 0.0)), open = true)))
        val sw = Sweep.of(profile, line)!!
        assertEquals(D3(0.0, -1.0, 0.0), sw.bounds.first)
        assertEquals(D3(10.0, 1.0, 2.0), sw.bounds.second)
        val s = ShapeDesign.fitSketch(Shape(1, Shape.Kind.SKETCH, "S", D3(2.0, 2.0, 5.0), sketch = profile, path = line), profile)
        assertEquals(D3(10.0, 2.0, 2.0), s.size)
        val code = ShapeDesign(listOf(s)).toScad()
        assertTrue(code.contains("module sweep("))
        assertTrue(code.contains("scale([10 / 10, 2 / 2, 2 / 2]) translate([-5, 0, 0]) sweep([[-1, 0], [1, 0], [1, 2], [-1, 2]], [[0, 0, 0, 1], [10, 0, 0, 1]], false);"))
        assertFalse(code.contains("linear_extrude("))
        assertFalse(code.contains("module loft("))
        // The open path is saved and read back.
        assertEquals(line.loops, Shape.of(s.json()).path?.loops)
    }

    @Test fun aClosedPathPutsThePlusXSideOutsideAndHolesAreCut() {
        val profile = Sketch(listOf(Loop.rect(P2(0.0, 0.0), P2(2.0, 2.0)), Loop.rect(P2(0.5, 0.5), P2(1.5, 1.5)).copy(hole = true)))
        val square = Sketch(listOf(Loop.rect(P2(0.0, 0.0), P2(10.0, 10.0))))
        val sw = Sweep.of(profile, square)!!
        assertEquals(D3(-2.0, -2.0, 0.0), sw.bounds.first)
        assertEquals(D3(12.0, 12.0, 2.0), sw.bounds.second)
        val code = sw.toScad("14", "14", "2")
        assertTrue(code.contains("difference() { sweep("))
        assertTrue(code.contains(", true);"))
    }

    @Test fun repeatedPointsAHoleBelowAndARingDrawnOpenAreCleanedUp() {
        val profile = Sketch(listOf(Loop(listOf(P2(0.0, 0.0), P2(0.0, 0.0), P2(2.0, 0.0), P2(2.0, 2.0), P2(0.0, 0.0))),
            Loop.rect(P2(0.5, -5.0), P2(1.5, -4.0)).copy(hole = true)))
        val ring = Sketch(listOf(Loop(listOf(P2(0.0, 0.0), P2(10.0, 0.0), P2(10.0, 10.0), P2(0.0, 0.0)), open = true)))
        val sw = Sweep.of(profile, ring)!!
        assertEquals(listOf(3), sw.solids.map { it.size })
        assertEquals(0.0, sw.solids.flatten().minOf { it.y }, 1e-9)
        assertEquals(listOf(true), sw.paths.map { it.second })
        assertEquals(3, sw.paths[0].first.size)
        // A closed path with no area sweeps nothing.
        assertEquals(null, Sweep.of(profile, Sketch(listOf(Loop(listOf(P2(0.0, 0.0), P2(10.0, 0.0), P2(20.0, 0.0)))))))
    }

    @Test fun twistGoesToTheExtrusion() {
        assertTrue(sketch(mapOf(Shape.TWIST to 90.0)).toScad().contains("linear_extrude(2, twist = 90)"))
        assertFalse(sketch(emptyMap()).toScad().contains("twist"))
    }
}
