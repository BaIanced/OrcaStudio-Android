package app.orcaandroid.scad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The focus room asks before freezing a design to a mesh, except for the lossless wrap of a mesh (docs/design/FOCUS-ROOM.md section 5). */
class PlainWrapTest {
    private val bounds = listOf(2.0, 3.0, 0.0, 22.0, 33.0, 10.0)

    /** What ScadController.editMesh builds for a one-part mesh object. */
    private fun wrap(): ShapeDesign = ShapeDesign(listOf(
        Shape(1, Shape.Kind.MESH, "Part", D3(20.0, 30.0, 10.0), pos = D3(12.0, 18.0, 0.0), file = "/m.stl",
            extra = Shape.MESH_BOUNDS.zip(bounds).toMap())), nextId = 2)

    @Test fun freshWrapIsPlain() = assertTrue(wrap().isPlainWrap())

    @Test fun wrapWithAnAddedHoleIsNot() {
        val d = wrap()
        val hole = ShapeDesign.create(Shape.Kind.CYLINDER, d.nextId, "Hole").copy(hole = true)
        assertFalse(d.copy(shapes = d.shapes + hole, nextId = d.nextId + 1).isPlainWrap())
    }

    @Test fun movedShapeIsNot() {
        val d = wrap()
        assertFalse(d.update(1) { it.copy(pos = it.pos + D3(1.0, 0.0, 0.0)) }.isPlainWrap())
    }

    @Test fun resizedShapeIsNot() {
        val d = wrap()
        assertFalse(d.update(1) { it.copy(size = D3(25.0, 30.0, 10.0)) }.isPlainWrap())
    }

    @Test fun groupedShapeIsNot() {
        val d = wrap()
        val g = Shape(2, Shape.Kind.GROUP, "Group", D3(20.0, 30.0, 10.0), children = d.shapes)
        assertFalse(d.copy(shapes = listOf(g), nextId = 3).isPlainWrap())
    }
}
