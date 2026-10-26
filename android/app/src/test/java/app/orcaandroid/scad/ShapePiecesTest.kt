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

    @Test fun piecesCarryTheirShapesFilamentWithoutChangingTheMesh() {
        val red = box(1, D3(10.0, 10.0, 10.0)).copy(filament = 2, repeat = Repeat(count = 2))
        val d = ShapeDesign(listOf(red, box(2, D3(3.0, 3.0, 12.0), hole = true).copy(filament = 3), box(3, D3(5.0, 5.0, 5.0))))
        assertEquals(listOf(2, 2, 0, 0), d.pieces().map { it.filament })
        assertEquals(d.pieces()[0].scad, ShapeDesign(listOf(red.copy(filament = 0))).pieces()[0].scad)
        assertEquals(2, Shape.of(red.json()).filament)
        assertEquals(0, Shape.of(box(3, D3(5.0, 5.0, 5.0)).json()).filament)
    }

    @Test fun nestedGroupsAreOnePiece() {
        val inner = Shape(20, Shape.Kind.GROUP, "Inner", D3(), D3(1.0, 0.0, 0.0), children = listOf(
            box(21, D3(6.0, 6.0, 6.0)),
            box(22, D3(2.0, 2.0, 9.0), D3(1.0, 0.0, 0.0), hole = true),
        ))
        val outer = Shape(10, Shape.Kind.GROUP, "Outer", D3(), D3(5.0, 5.0, 0.0), children = listOf(
            inner, box(11, D3(4.0, 4.0, 4.0), D3(-6.0, 0.0, 0.0))))
        val d = ShapeDesign(listOf(outer))
        val pieces = d.pieces()
        assertEquals(1, pieces.size)
        assertFalse(pieces[0].hole)
        // The grandchild hole cuts inside the piece's own code, not as a volume of its own.
        assertTrue(pieces[0].scad.contains("difference()"))
        assertMatches(d)
    }

    @Test fun aHoleInsideAGroupIsNotItsOwnPiece() {
        val group = Shape(10, Shape.Kind.GROUP, "G", D3(), children = listOf(
            box(11, D3(10.0, 10.0, 10.0)),
            box(12, D3(3.0, 3.0, 12.0), hole = true),
        ))
        val d = ShapeDesign(listOf(group, box(2, D3(3.0, 3.0, 12.0), hole = true)))
        val pieces = d.pieces()
        assertEquals(2, pieces.size)
        assertEquals(listOf(false, true), pieces.map { it.hole })
        assertTrue(pieces[0].scad.contains("difference()"))
        assertMatches(d)
    }

    @Test fun blankTextIsAPieceWithNoMesh() {
        val pieces = ShapeDesign(listOf(
            Shape(1, Shape.Kind.TEXT, "T", D3(40.0, 12.0, 3.0), text = ""),
            Shape(2, Shape.Kind.TEXT, "U", D3(40.0, 12.0, 3.0), text = "   "),
        )).pieces()
        assertEquals(2, pieces.size)
        // The code is only a comment: it renders an empty file, which the matching treats as not placed.
        assertTrue(pieces.all { it.scad.contains("// (no text)") })
        assertEquals(pieces[0].scad, pieces[1].scad)
        assertNotEquals(pieces[0].shapeId, pieces[1].shapeId)
    }

    @Test fun identicalShapesShareCodeButNotPieces() {
        val d = ShapeDesign(listOf(
            box(1, D3(10.0, 10.0, 10.0), D3(3.0, 0.0, 0.0)),
            box(2, D3(10.0, 10.0, 10.0), D3(3.0, 0.0, 0.0)),
        ))
        val pieces = d.pieces()
        assertEquals(2, pieces.size)
        assertEquals(pieces[0].scad, pieces[1].scad)
        assertEquals(pieces[0].matrix, pieces[1].matrix)
        assertEquals(listOf(1, 2), pieces.map { it.shapeId })
    }

    @Test fun repeatCountChangesKeepTheFirstCopies() {
        val s = box(1, D3(5.0, 5.0, 5.0)).copy(repeat = Repeat(count = 4, step = D3(10.0, 0.0, 0.0)))
        val four = ShapeDesign(listOf(s)).pieces()
        val three = ShapeDesign(listOf(s.copy(repeat = s.repeat!!.copy(count = 3)))).pieces()
        assertEquals(four.dropLast(1), three)
        // A count of 1 is no repeat at all: one piece, placed like the plain shape.
        val one = ShapeDesign(listOf(s.copy(repeat = s.repeat!!.copy(count = 1)))).pieces()
        assertEquals(ShapeDesign(listOf(box(1, D3(5.0, 5.0, 5.0)))).pieces(), one)
    }

    @Test fun reorderedShapesReorderTheirPieces() {
        val a = box(1, D3(10.0, 10.0, 10.0))
        val b = box(2, D3(4.0, 4.0, 4.0))
        assertEquals(listOf(1, 2), ShapeDesign(listOf(a, b)).pieces().map { it.shapeId })
        assertEquals(listOf(2, 1), ShapeDesign(listOf(b, a)).pieces().map { it.shapeId })
    }

    @Test fun mirrorChangesOnlyTheMatrix() {
        val plain = ShapeDesign(listOf(box(1, D3(10.0, 10.0, 10.0)))).pieces().single()
        val mirrored = ShapeDesign(listOf(box(1, D3(10.0, 10.0, 10.0), mirror = D3(1.0, 0.0, 0.0)))).pieces().single()
        assertEquals(plain.scad, mirrored.scad)
        assertNotEquals(plain.matrix, mirrored.matrix)
        assertEquals(-1.0, mirrored.matrix[0], 1e-9)
        assertEquals(1.0, mirrored.matrix[5], 1e-9)
    }

    @Test fun mirrorMagnitudeIsIgnoredToday() {
        // The mirror is read as a flag (any non-zero flips), so a 2 flips exactly like a 1. Pinned, not fixed.
        val one = ShapeDesign(listOf(box(1, D3(10.0, 10.0, 10.0), mirror = D3(1.0, 0.0, 0.0)))).pieces().single()
        val two = ShapeDesign(listOf(box(1, D3(10.0, 10.0, 10.0), mirror = D3(2.0, 0.0, 0.0)))).pieces().single()
        assertEquals(one.matrix, two.matrix)
        assertEquals(one.scad, two.scad)
    }

    @Test fun turnedCircularRepeatCopiesTurnAboutTheCentre() {
        val ring = box(1, D3(4.0, 2.0, 3.0), D3(30.0, 0.0, 0.0), D3(0.0, 0.0, 30.0))
            .copy(repeat = Repeat(circular = true, count = 4, center = D3(20.0, 5.0, 0.0)))
        val pieces = ShapeDesign(listOf(ring)).pieces()
        assertEquals(4, pieces.size)
        assertEquals((0 until 4).toList(), pieces.map { it.copy })
        assertEquals(1, pieces.map { it.scad }.distinct().size)
        // Copy i is copy 0 turned by i quarter turns about the centre (whose z plays no part).
        val c = D3(20.0, 5.0, 0.0)
        val p0 = apply(pieces[0].matrix, D3())
        for (i in 1 until 4) {
            val expected = ShapeDesign.rotate(p0 - c, D3(0.0, 0.0, 90.0 * i)) + c
            val actual = apply(pieces[i].matrix, D3())
            for (k in 0..2) assertEquals("copy $i axis $k", expected[k], actual[k], 1e-9)
        }
    }

    @Test fun tinyMoveChangesTheMatrixButNotTheCodeToday() {
        val d = ShapeDesign(listOf(box(1, D3(10.0, 10.0, 10.0))))
        val before = d.pieces().single()
        // Deterministic: the same design makes bit-equal matrices, so an unmoved piece can anchor.
        assertEquals(before.matrix, d.pieces().single().matrix)
        // But the anchor comparison is exact (== on the matrix): a move under the code's 0.001 rounding
        // changes the matrix, so such a piece no longer anchors. Pinned, not fixed.
        val moved = d.update(1) { it.copy(pos = it.pos + D3(1e-9, 0.0, 0.0)) }.pieces().single()
        assertEquals(before.scad, moved.scad)
        assertNotEquals(before.matrix, moved.matrix)
    }

    @Test fun aMergedGroupStaysWhereTheGroupWas() {
        val group = Shape(10, Shape.Kind.GROUP, "G", D3(), D3(5.0, -3.0, 2.0), D3(20.0, 90.0, 35.0), mirror = D3(1.0, 0.0, 0.0),
            children = listOf(
                box(11, D3(10.0, 10.0, 10.0), D3(-5.0, 0.0, 0.0)),
                box(12, D3(4.0, 6.0, 14.0), D3(6.0, 1.0, 1.0), D3(0.0, 0.0, 30.0)),
                box(13, D3(3.0, 3.0, 30.0), D3(0.0, 0.0, -5.0), hole = true),
            ), repeat = Repeat(count = 2, step = D3(30.0, 0.0, 0.0)), filament = 2)
        val other = box(1, D3(8.0, 8.0, 8.0), D3(40.0, 0.0, 0.0))
        val d = ShapeDesign(listOf(other, group))
        // The rendered mesh's bounds in the group's frame: the solids' box (the hole cuts inside it), not centred on the frame.
        val solids = group.children.filter { !it.hole }.map { d.bounds(it) }.reduce { a, b -> a.union(b) }
        val b = listOf(solids.min.x, solids.min.y, solids.min.z, solids.max.x, solids.max.y, solids.max.z)
        val m = d.merged(10, "/m/merged.stl", b)
        val mesh = m.shapes[1]
        assertEquals(other, m.shapes[0])
        assertEquals(Shape.Kind.MESH, mesh.kind)
        assertEquals(10, mesh.id)
        assertEquals(2, mesh.filament)
        assertEquals(group.repeat, mesh.repeat)
        assertEquals("/m/merged.stl", mesh.file)
        val before = d.pieces().filter { it.shapeId == 10 }
        val after = m.pieces().filter { it.shapeId == 10 }
        assertEquals(2, after.size)
        // Every corner of the mesh, as the group draws it and as the mesh shape draws it (its body centres it first).
        val shift = D3(solids.center.x, solids.center.y, solids.min.z)
        for (k in before.indices) for (i in 0 until 8) {
            val c = D3(if (i and 1 == 0) solids.min.x else solids.max.x, if (i and 2 == 0) solids.min.y else solids.max.y,
                if (i and 4 == 0) solids.min.z else solids.max.z)
            val want = apply(before[k].matrix, c)
            val got = apply(after[k].matrix, c - shift)
            for (a in 0..2) assertEquals(want[a], got[a], 1e-9)
        }
        assertEquals(d, d.merged(1, "/m/x.stl", b))
    }
    @Test
    fun blendedGroupSendsItsShapesToOccNotOpenScad() {
        val kids = listOf(box(2, D3(20.0, 20.0, 10.0)), box(3, D3(8.0, 8.0, 15.0), pos = D3(0.0, 0.0, 10.0), kind = Shape.Kind.CYLINDER),
            box(4, D3(4.0, 4.0, 30.0), hole = true, kind = Shape.Kind.CYLINDER))
        val g = Shape(1, Shape.Kind.GROUP, "G", D3(), extra = mapOf(Shape.BLEND to 2.0), children = kids)
        val code = ShapeDesign(listOf(g)).pieces().single().scad
        assertTrue(code.startsWith(ShapeDesign.BLEND_TAG))
        val spec = org.json.JSONObject(code.removePrefix(ShapeDesign.BLEND_TAG))
        assertEquals(2.0, spec.getDouble("radius"), 0.0)
        val parts = spec.getJSONArray("parts")
        assertEquals(listOf("box", "cylinder", "cylinder"), (0 until parts.length()).map { parts.getJSONObject(it).getString("kind") })
        assertTrue(parts.getJSONObject(2).getBoolean("hole"))
        assertEquals(10.0, parts.getJSONObject(1).getJSONArray("m").getDouble(11), 1e-9)
        // Without the blend the same group is OpenSCAD code again; a torus can't be blended yet.
        assertFalse(ShapeDesign(listOf(g.copy(extra = emptyMap()))).pieces().single().scad.startsWith(ShapeDesign.BLEND_TAG))
        val torus = g.copy(children = kids + box(5, D3(20.0, 20.0, 5.0), kind = Shape.Kind.TORUS))
        assertEquals(ShapeDesign.BLEND_TAG + "!S5", ShapeDesign(listOf(torus)).pieces().single().scad)
    }
}
