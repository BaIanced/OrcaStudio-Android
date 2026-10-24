package app.orcaandroid.scad

import org.junit.Assert.assertEquals
import org.junit.Test

/** Build workspace inside groups: which shape a tap on a group's mesh hits, the frame a shape inside moves in, tap levels. */
class GroupPickTest {
    private fun box(id: Int, size: D3, pos: D3 = D3(), rot: D3 = D3(), hole: Boolean = false, kind: Shape.Kind = Shape.Kind.BOX) =
        Shape(id, kind, "S$id", size, pos, rot, hole)

    private fun apply(m: List<Double>, p: D3) = D3(
        m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3],
        m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7],
        m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11],
    )

    /** Turned, mirrored, repeated group 10: solid 11 inside hole 12, group 13 (turned about X) holding box 14. */
    private val design = ShapeDesign(listOf(
        box(1, D3(5.0, 5.0, 5.0), D3(-40.0, 0.0, 0.0)),
        Shape(10, Shape.Kind.GROUP, "G", D3(), D3(20.0, -5.0, 2.0), D3(0.0, 0.0, 90.0), mirror = D3(1.0, 0.0, 0.0),
            repeat = Repeat(count = 2, step = D3(0.0, 40.0, 0.0)), children = listOf(
                box(11, D3(10.0, 10.0, 10.0), D3(-8.0, 0.0, 0.0), D3(0.0, 0.0, 30.0)),
                box(12, D3(4.0, 4.0, 20.0), D3(-8.0, 0.0, -2.0), hole = true, kind = Shape.Kind.CYLINDER),
                Shape(13, Shape.Kind.GROUP, "H", D3(), D3(8.0, 0.0, 0.0), D3(90.0, 0.0, 0.0), children = listOf(
                    box(14, D3(6.0, 6.0, 6.0)),
                )),
            )),
    ), nextId = 15)

    private fun piece(d: ShapeDesign, id: Int, copy: Int) = d.pieces().first { it.shapeId == id && it.copy == copy }

    @Test fun aTapInsideAGroupFindsTheChildUnderIt() {
        for (copy in 0..1) {
            val p = piece(design, 10, copy)
            // The solid's middle lies in the hole's box too: the solid wins.
            assertEquals(listOf(10, 11), design.pathAt(p, apply(p.matrix, D3(-8.0, 0.0, 5.0))))
            // Box 14's middle (0, 0, 3) in group 13 sits at (8, 0, 3) in G: group 13 turns about its own middle.
            assertEquals(listOf(10, 13, 14), design.pathAt(p, apply(p.matrix, D3(8.0, 0.0, 3.0))))
            // Between the children: only the group.
            assertEquals(listOf(10), design.pathAt(p, apply(p.matrix, D3(0.0, 4.9, 5.0))))
        }
        val plain = piece(design, 1, 0)
        assertEquals(listOf(1), design.pathAt(plain, apply(plain.matrix, D3(0.0, 0.0, 2.5))))
    }

    @Test fun aShapeInsideATurnedFlippedGroupMovesAlongTheDesignAxes() {
        val v = D3(1.0, 2.0, 3.0)
        val before = apply(piece(design, 10, 0).matrix, D3(-8.0, 0.0, 5.0))
        val moved = design.update(11) { it.copy(pos = it.pos + design.toParent(11, v)) }
        val a = moved.find(11)!!
        val after = apply(piece(moved, 10, 0).matrix, a.pos + D3(0.0, 0.0, 5.0))
        for (i in 0..2) assertEquals(before[i] + v[i], after[i], 1e-9)
        // A top-level shape moves as given.
        assertEquals(v, design.toParent(1, v))
    }

    @Test fun aSecondTapGoesOneLevelDeeperAndATapBesideStaysOnItsLevel() {
        val hit = listOf(10, 13, 14)
        assertEquals(0, BuildTransform.pickLevel(emptyList(), hit))
        assertEquals(0, BuildTransform.pickLevel(listOf(1), hit))
        assertEquals(1, BuildTransform.pickLevel(listOf(10), hit))
        assertEquals(1, BuildTransform.pickLevel(listOf(10, 11), hit))
        assertEquals(2, BuildTransform.pickLevel(listOf(10, 13), hit))
        assertEquals(2, BuildTransform.pickLevel(listOf(10, 13, 14), hit))
        assertEquals(1, BuildTransform.pickLevel(listOf(10, 13, 14), listOf(10, 11)))
        assertEquals(0, BuildTransform.pickLevel(listOf(10, 11), listOf(10)))
    }
}
