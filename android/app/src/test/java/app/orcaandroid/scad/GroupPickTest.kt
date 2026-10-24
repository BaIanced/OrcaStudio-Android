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

    /** Design point of [local] (in shape [id]'s own frame, standing on z = 0) on the group's first copy. */
    private fun at(d: ShapeDesign, id: Int, local: D3): D3 {
        val s = d.find(id)!!
        val pivot = D3(0.0, 0.0, d.height(s) / 2)
        val q = ShapeDesign.rotate(ShapeDesign.flip(local - pivot, s.mirror), d.rot(s)) + pivot + d.pos(s)
        return apply(d.parentFrame(id).toList(), q)
    }

    private fun corners(s: D3) = (0 until 8).map { k ->
        D3(if (k and 1 == 0) -s.x / 2 else s.x / 2, if (k and 2 == 0) -s.y / 2 else s.y / 2, if (k and 4 == 0) 0.0 else s.z)
    }

    /** [next] with the groups holding [id] moved as [ShapeDesign.groupShifts] says (no bindings here). */
    private fun held(before: ShapeDesign, next: ShapeDesign, id: Int) =
        next.groupShifts(before, id).fold(next) { acc, (g, pos) -> acc.update(g) { it.copy(pos = pos) } }

    // The gizmos round pos and size to 0.001 mm.
    private fun close(a: D3, b: D3) { for (i in 0..2) assertEquals(b[i], a[i], 2e-3) }

    @Test fun theParentFrameIsTheGroupsFirstCopy() {
        assertEquals(piece(design, 10, 0).matrix, design.parentFrame(11).toList())
        assertEquals(ShapeDesign.translation(D3()).toList(), design.parentFrame(1).toList())
    }

    @Test fun aGizmoTurnOnANestedShapeTurnsItInTheDesignAndLeavesItsNeighboursPut() {
        // A design turn of 45 about Y (a level axis in group 13's frame, so its top changes), about a point off the shape.
        val r = Math.sqrt(0.5)
        val m = arrayOf(doubleArrayOf(r, 0.0, r), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-r, 0.0, r))
        val c = D3(5.0, 1.0, 2.0)
        val frame = design.parentFrame(14)
        val pose = BuildTransform.turned(design, design.find(14)!!, BuildTransform.turnInFrame(frame, m), BuildTransform.inFrame(frame, c))
        val turned = design.update(14) { it.copy(pos = pose.pos, rot = pose.rot) }
        // Group 13 (turned about X) moves; G turns about Z only, so its new height needs no shift.
        assertEquals(listOf(13), turned.groupShifts(design, 14).map { it.first })
        val next = held(design, turned, 14)
        for (k in corners(D3(6.0, 6.0, 6.0))) {
            val o = at(design, 14, k) - c
            close(at(next, 14, k), c + D3(r * (o.x + o.z), o.y, r * (o.z - o.x)))
        }
        // Box 11 and the hole stay where they were, though group 13 (and so G) got taller.
        for (k in corners(D3(10.0, 10.0, 10.0))) close(at(next, 11, k), at(design, 11, k))
        for (k in corners(D3(4.0, 4.0, 20.0))) close(at(next, 12, k), at(design, 12, k))
    }

    @Test fun aGizmoScaleInsideATurnedGroupScalesAlongTheDesignAxes() {
        // G is turned 90 about Z and mirrored: design X is the group's Y.
        val f = D3(2.0, 1.0, 1.0)
        val p = D3(3.0, -4.0, 0.0)
        val frame = design.parentFrame(12)
        val pose = BuildTransform.scaled(design, design.find(12)!!, BuildTransform.scaleInFrame(frame, f)!!, BuildTransform.inFrame(frame, p))!!
        val next = held(design, design.update(12) { it.copy(pos = pose.pos, size = pose.size) }, 12)
        for (k in corners(D3(4.0, 4.0, 20.0))) {
            val o = at(design, 12, k)
            val want = D3(p.x + (o.x - p.x) * f.x, p.y + (o.y - p.y) * f.y, p.z + (o.z - p.z) * f.z)
            // The corner order flips with the scale's sign-free axes: compare the corner sets.
            assert(corners(next.size(next.find(12)!!)).any { q -> (0..2).all { kotlin.math.abs(at(next, 12, q)[it] - want[it]) < 2e-3 } })
        }
        for (k in corners(D3(10.0, 10.0, 10.0))) close(at(next, 11, k), at(design, 11, k))
        // Uneven in a group turned off the axes: no scale.
        val tilted = design.update(10) { it.copy(rot = D3(0.0, 0.0, 30.0)) }
        assertEquals(null, BuildTransform.scaleInFrame(tilted.parentFrame(12), f))
    }
}
