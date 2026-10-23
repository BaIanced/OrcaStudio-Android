package app.orcaandroid.scad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Build workspace's volumes: each piece's mesh frame placed by its matrix lands where the OpenSCAD code puts the shape. */
class ShapePiecesTest {
    private fun box(id: Int, size: D3, pos: D3 = D3(), rot: D3 = D3(), mirror: D3 = D3(), hole: Boolean = false, kind: Shape.Kind = Shape.Kind.BOX) =
        Shape(id, kind, "S$id", size, pos, rot, hole, mirror)

    private fun apply(m: List<Double>, p: D3) = D3(
        m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3],
        m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7],
        m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11],
    )

    /** The piece's own-frame box (as [ShapeDesign.bounds] sees it) taken through its matrix. */
    private fun placed(d: ShapeDesign, top: Shape, piece: ShapeDesign.Piece): Bounds {
        val local = if (top.isGroup) top.children.map { d.bounds(it) }.reduce { a, b -> a.union(b) }
        else d.size(top).let { s -> Bounds(D3(-s.x / 2, -s.y / 2, 0.0), D3(s.x / 2, s.y / 2, s.z)) }
        return Bounds.of((0 until 8).map { i ->
            apply(piece.matrix, D3(if (i and 1 == 0) local.min.x else local.max.x, if (i and 2 == 0) local.min.y else local.max.y,
                if (i and 4 == 0) local.min.z else local.max.z))
        })
    }

    private fun assertBounds(expected: Bounds, actual: Bounds) {
        for (i in 0..2) {
            assertEquals(expected.min[i], actual.min[i], 1e-9)
            assertEquals(expected.max[i], actual.max[i], 1e-9)
        }
    }

    private fun assertMatches(d: ShapeDesign) {
        val pieces = d.pieces()
        for (top in d.shapes) {
            val own = pieces.filter { it.shapeId == top.id }
            assertTrue(own.isNotEmpty())
            assertBounds(d.bounds(top), own.map { placed(d, top, it) }.reduce { a, b -> a.union(b) })
        }
    }

    @Test fun turnedMirroredAndMovedShapesLandWhereTheCodePutsThem() {
        assertMatches(ShapeDesign(listOf(
            box(1, D3(20.0, 10.0, 5.0), D3(3.0, -4.0, 2.0), D3(30.0, 0.0, 0.0)),
            box(2, D3(8.0, 12.0, 30.0), D3(-15.0, 6.0, 0.0), D3(10.0, 45.0, 70.0), D3(1.0, 0.0, 1.0)),
            box(3, D3(6.0, 6.0, 40.0), D3(0.0, 0.0, -1.0), hole = true, kind = Shape.Kind.CYLINDER),
        )))
    }

    @Test fun aGroupIsOnePieceInItsOwnFrame() {
        val group = Shape(10, Shape.Kind.GROUP, "G", D3(), D3(5.0, 5.0, 3.0), D3(0.0, 90.0, 0.0), children = listOf(
            box(11, D3(10.0, 10.0, 10.0), D3(-5.0, 0.0, 0.0)),
            box(12, D3(4.0, 4.0, 20.0), D3(5.0, 0.0, 0.0), hole = true),
        ))
        val d = ShapeDesign(listOf(group))
        val pieces = d.pieces()
        assertEquals(1, pieces.size)
        assertFalse(pieces[0].hole)
        // The children sit in the group's frame: their own translate stays in the code, the group's doesn't.
        assertTrue(pieces[0].scad.contains("difference()"))
        assertTrue(pieces[0].scad.contains("translate([-5, 0, 0])"))
        assertFalse(pieces[0].scad.contains("translate([5, 5, 3"))
        assertMatches(d)
    }

    @Test fun repeatedShapesGiveOnePiecePerCopy() {
        val row = box(1, D3(5.0, 5.0, 5.0), D3(1.0, 2.0, 0.0), D3(0.0, 0.0, 20.0)).copy(repeat = Repeat(count = 4, step = D3(10.0, 0.0, 2.0)))
        val ring = box(2, D3(4.0, 2.0, 3.0), D3(30.0, 0.0, 0.0)).copy(repeat = Repeat(circular = true, count = 6, center = D3(20.0, 5.0, 0.0)))
        val arc = box(3, D3(4.0, 2.0, 3.0), D3(0.0, -30.0, 0.0)).copy(repeat = Repeat(circular = true, count = 3, angle = 90.0))
        val d = ShapeDesign(listOf(row, ring, arc))
        val pieces = d.pieces()
        assertEquals(listOf(4, 6, 3), listOf(1, 2, 3).map { id -> pieces.count { it.shapeId == id } })
        assertEquals((0 until 4).toList(), pieces.filter { it.shapeId == 1 }.map { it.copy })
        // Copies share the mesh.
        assertEquals(1, pieces.filter { it.shapeId == 2 }.map { it.scad }.distinct().size)
        assertMatches(d)
    }

    @Test fun movingOrTurningKeepsTheMeshResizingChangesIt() {
        val d = ShapeDesign(listOf(box(1, D3(20.0, 10.0, 5.0)).copy(extra = mapOf(Shape.EDGE to 1.0))))
        val before = d.pieces().single()
        val moved = d.update(1) { it.copy(pos = D3(7.0, 8.0, 9.0), rot = D3(0.0, 0.0, 33.0), mirror = D3(1.0, 0.0, 0.0)) }.pieces().single()
        assertEquals(before.scad, moved.scad)
        assertNotEquals(before.matrix, moved.matrix)
        assertNotEquals(before.scad, d.update(1) { it.copy(size = D3(21.0, 10.0, 5.0)) }.pieces().single().scad)
    }

    @Test fun boundDimensionsAreWrittenAsNumbers() {
        val s = box(1, D3(20.0, 10.0, 5.0), D3(0.0, 0.0, 0.0)).copy(
            extra = mapOf(Shape.EDGE to 1.0), bind = mapOf("size.x" to "width", "pos.z" to "lift", Shape.EDGE to "edge"))
        val d = ShapeDesign(listOf(s), listOf(Dim("width", 33.0, 1.0, 100.0), Dim("lift", 4.0, 0.0, 50.0), Dim("edge", 2.0, 0.0, 5.0)))
        val piece = d.pieces().single()
        assertFalse(piece.scad.contains("width"))
        assertFalse(piece.scad.contains("edge"))
        // Rounded by 2: the 33 x 10 x 5 box shrinks by twice the edge before the ball grows it back.
        assertTrue(piece.scad.contains("cube([29, 6, 1])"))
        assertTrue(piece.scad.contains("sphere(r = 2"))
        assertEquals(4.0, piece.matrix[11], 1e-9)
        assertMatches(d)
        // A slider change makes a new mesh.
        assertNotEquals(piece.scad, d.withDims(listOf(Dim("width", 40.0, 1.0, 100.0), Dim("lift", 4.0, 0.0, 50.0), Dim("edge", 2.0, 0.0, 5.0))).pieces().single().scad)
    }

    @Test fun holesAreFlagged() {
        val pieces = ShapeDesign(listOf(box(1, D3(10.0, 10.0, 10.0)), box(2, D3(3.0, 3.0, 12.0), hole = true))).pieces()
        assertEquals(listOf(false, true), pieces.map { it.hole })
    }
}
