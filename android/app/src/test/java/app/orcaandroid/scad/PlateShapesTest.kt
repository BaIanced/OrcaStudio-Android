package app.orcaandroid.scad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** + Shape / + Text dialog fields -> the builder's one-shape design (T-043). */
class PlateShapesTest {
    private fun one(d: ShapeDesign): Shape = d.shapes.single()

    @Test fun cylinderKeepsTheEnteredSize() {
        val d = PlateShapes.primitive("cylinder", 30.0, 30.0, 12.0, 0.0, "Cylinder 1")
        val s = one(d)
        assertEquals(Shape.Kind.CYLINDER, s.kind)
        assertEquals(D3(30.0, 30.0, 12.0), s.size)
        assertEquals("Cylinder 1", s.name)
        assertFalse(s.hole)
        assertEquals(D3(), s.pos)
        assertEquals(2, d.nextId)
        assertTrue(s.extra.isEmpty())
    }

    @Test fun shapeIdsMapToBuilderKinds() {
        assertEquals(Shape.Kind.BOX, PlateShapes.kind("cube"))
        assertEquals(Shape.Kind.CYLINDER, PlateShapes.kind("cylinder"))
        assertEquals(Shape.Kind.SPHERE, PlateShapes.kind("sphere"))
        assertEquals(Shape.Kind.CONE, PlateShapes.kind("cone"))
        // The cone is pointed, like the dialog's cone.
        assertEquals(0.0, one(PlateShapes.primitive("cone", 20.0, 20.0, 20.0, 0.0, "Cone 1")).x(Shape.TOP, -1.0), 0.0)
    }

    @Test fun roundEdgesBecomeTheEdgeSizeWhereTheKindHasEdges() {
        assertEquals(2.0, one(PlateShapes.primitive("cube", 20.0, 20.0, 20.0, 2.0, "Cube 1")).x(Shape.EDGE, 0.0), 0.0)
        assertEquals(1.5, one(PlateShapes.primitive("cylinder", 20.0, 20.0, 20.0, 1.5, "Cylinder 1")).x(Shape.EDGE, 0.0), 0.0)
        assertEquals(1.5, one(PlateShapes.primitive("cone", 20.0, 20.0, 20.0, 1.5, "Cone 1")).x(Shape.EDGE, 0.0), 0.0)
        // A sphere has no edge; zero rounding adds none.
        assertTrue(one(PlateShapes.primitive("sphere", 20.0, 20.0, 20.0, 3.0, "Sphere 1")).extra.isEmpty())
        assertTrue(one(PlateShapes.primitive("cube", 20.0, 20.0, 20.0, 0.0, "Cube 1")).extra.isEmpty())
    }

    @Test fun roundingIsKeptUnderHalfTheSmallestSide() {
        assertEquals(2.0, one(PlateShapes.primitive("cube", 20.0, 20.0, 4.0, 9.0, "Cube 1")).x(Shape.EDGE, 0.0), 0.0)
    }

    @Test fun generatedCodeHoldsTheSizeAndEdge() {
        val code = PlateShapes.primitive("cube", 10.0, 20.0, 30.0, 1.0, "Cube 1").toScad()
        assertTrue(code, code.contains("cube([8, 18, 28])"))
        assertTrue(code, code.contains("minkowski"))
        val cyl = PlateShapes.primitive("cylinder", 30.0, 30.0, 12.0, 0.0, "Cylinder 1").pieces()
        assertEquals(1, cyl.size)
        assertTrue(cyl[0].scad, cyl[0].scad.contains("scale([30, 30, 12]) cylinder(h = 1, d = 1"))
    }

    @Test fun textSizeIsHeightAndDepthWithTheWidthFromTheLetters() {
        // "Orca" at size 10 measures 31 x 10.4 mm: 12 mm tall letters are 35.769... mm wide.
        val s = one(PlateShapes.text("Orca", 12.0, 3.0, D3(31.0, 10.4, 1.0), "Text 1"))
        assertEquals(Shape.Kind.TEXT, s.kind)
        assertEquals("Orca", s.text)
        assertEquals(12.0, s.size.y, 0.0)
        assertEquals(3.0, s.size.z, 0.0)
        assertEquals(31.0 * 12.0 / 10.4, s.size.x, 1e-9)
    }

    @Test fun textWidthFallsBackToAGuessAndStaysInRange() {
        assertEquals(0.6 * 10 * 4, PlateShapes.textWidth("Orca", 10.0, null), 1e-9)
        assertEquals(24.0, PlateShapes.textWidth(" Orca ", 10.0, D3(0.0, 0.0, 0.0)), 1e-9)
        assertEquals(PlateShapes.MAX_MM, PlateShapes.textWidth("x".repeat(400), 50.0, null), 0.0)
        assertEquals(0.1, PlateShapes.textWidth("", 10.0, null), 0.0)
    }

    @Test fun textCodeResizesTheProbeToTheBox() {
        val d = PlateShapes.text("Hi \"you\"", 10.0, 2.0, D3(20.0, 10.0, 1.0), "Text 1")
        assertTrue(d.toScad(), d.toScad().contains("resize([20, 10, 2]) " + PlateShapes.textProbe("Hi \"you\"")))
        assertEquals("linear_extrude(1) text(\"a\", size = 10, halign = \"center\", valign = \"center\");", PlateShapes.textProbe("a"))
    }

    @Test fun designNameIsAFileSafeStartOfTheText() {
        assertEquals("Orca", PlateShapes.textName("Orca"))
        assertEquals("a_b_c_d", PlateShapes.textName(" a/b.c\\d "))
        assertEquals("Text", PlateShapes.textName("  "))
        assertEquals("x".repeat(32), PlateShapes.textName("x".repeat(50)))
        assertEquals("Grüße", PlateShapes.textName("Grüße"))
    }
}
