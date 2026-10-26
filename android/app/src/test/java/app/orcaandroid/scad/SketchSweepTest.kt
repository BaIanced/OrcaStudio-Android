package app.orcaandroid.scad

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

    @Test fun twistGoesToTheExtrusion() {
        assertTrue(sketch(mapOf(Shape.TWIST to 90.0)).toScad().contains("linear_extrude(2, twist = 90)"))
        assertFalse(sketch(emptyMap()).toScad().contains("twist"))
    }
}
