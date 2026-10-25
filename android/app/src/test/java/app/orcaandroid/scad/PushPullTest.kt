package app.orcaandroid.scad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Build workspace push / pull: which face a hit grabs, and that the face moves along its normal while the opposite one stays. */
class PushPullTest {
    private fun apply(m: List<Double>, p: D3) = D3(
        m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3],
        m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7],
        m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11],
    )

    private fun piece(d: ShapeDesign, id: Int, copy: Int = 0) = d.pieces().first { it.shapeId == id && it.copy == copy }

    private fun near(a: D3, b: D3, eps: Double = 1e-6) = (0..2).forEach { assertEquals(b[it], a[it], eps) }

    private fun posed(d: ShapeDesign, id: Int, p: BuildTransform.Pose) = d.update(id) { it.copy(pos = p.pos, size = p.size) }

    private fun neg(v: D3) = D3(-v.x, -v.y, -v.z)

    @Test fun aPlainBoxGrowsFromTheFaceThatWasPushed() {
        val d = ShapeDesign(listOf(Shape(1, Shape.Kind.BOX, "B", D3(20.0, 20.0, 20.0))), nextId = 2)
        val p = piece(d, 1)
        val top = d.faceAt(p, D3(3.0, 2.0, 20.0), D3(0.0, 0.0, -1.0))!!
        assertEquals(2, top.axis); assertEquals(1, top.sign)
        near(top.normal, D3(0.0, 0.0, 1.0))
        val up = d.pushed(top, 5.0)!!
        near(up.size, D3(20.0, 20.0, 25.0)); near(up.pos, D3())
        val left = d.faceAt(p, D3(-10.0, 1.0, 5.0), D3(1.0, 0.0, 0.0))!!
        assertEquals(0, left.axis); assertEquals(-1, left.sign)
        val wider = d.pushed(left, 5.0)!!
        near(wider.size, D3(25.0, 20.0, 20.0)); near(wider.pos, D3(-2.5, 0.0, 0.0))
        // The bottom pushed down 4 mm: the top stays.
        val bottom = d.faceAt(p, D3(0.0, 0.0, 0.0), D3(0.0, 0.0, 1.0))!!
        near(d.pushed(bottom, 4.0)!!.pos, D3(0.0, 0.0, -4.0))
        // Pulled in past its size: 1 mm stays.
        near(d.pushed(top, -50.0)!!.size, D3(20.0, 20.0, 1.0))
        // A face seen from inside, and a point inside the box, grab nothing.
        assertNull(d.faceAt(p, D3(-10.0, 1.0, 5.0), D3(-1.0, 0.0, 0.0)))
        assertNull(d.faceAt(p, D3(0.0, 0.0, 10.0), D3(0.0, 0.0, -1.0)))
    }

    @Test fun everyFaceOfATurnedMirroredBoxMovesAlongItsNormal() {
        val size = D3(10.0, 14.0, 6.0)
        val d = ShapeDesign(listOf(Shape(1, Shape.Kind.BOX, "B", size, D3(5.0, 6.0, 7.0), D3(20.0, -35.0, 60.0), mirror = D3(0.0, 1.0, 0.0))), nextId = 2)
        val p = piece(d, 1)
        for (axis in 0..2) for (sign in listOf(-1, 1)) {
            fun mid(b: D3, s: Int) = D3(0.0, 0.0, b.z / 2).let { c -> c.with(axis, c[axis] + s * b[axis] / 2) }
            val at = apply(p.matrix, mid(size, sign))
            val opposite = apply(p.matrix, mid(size, -sign))
            val n = (at - opposite).let { v -> val k = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z); D3(v.x / k, v.y / k, v.z / k) }
            val f = d.faceAt(p, at, neg(n))
            assertNotNull("axis $axis sign $sign", f)
            assertEquals(axis, f!!.axis); assertEquals(sign, f.sign)
            near(f.normal, n)
            val next = posed(d, 1, d.pushed(f, 2.5)!!)
            val q = piece(next, 1)
            val grown = size.with(axis, size[axis] + 2.5)
            near(apply(q.matrix, mid(grown, -sign)), opposite, 1e-3)
            near(apply(q.matrix, mid(grown, sign)), at + D3(n.x * 2.5, n.y * 2.5, n.z * 2.5), 1e-3)
        }
    }

    @Test fun aCylinderSideWidensItAndItsCapsGrowItsHeight() {
        val d = ShapeDesign(listOf(Shape(1, Shape.Kind.CYLINDER, "C", D3(20.0, 20.0, 20.0))), nextId = 2)
        val p = piece(d, 1)
        // A corner of the polygon (angle 0) lies on the circle.
        val side = d.faceAt(p, D3(10.0, 0.0, 5.0), D3(-1.0, 0.0, 0.0))!!
        assertEquals(-1, side.axis)
        near(side.dir, D3(1.0, 0.0, 0.0))
        assertEquals(2, side.stretch.size)
        val wide = d.pushed(side, 4.0)!!
        near(wide.size, D3(24.0, 24.0, 20.0)); near(wide.pos, D3(2.0, 0.0, 0.0))
        val cap = d.faceAt(p, D3(1.0, 1.0, 20.0), D3(0.0, 0.0, -1.0))!!
        assertEquals(2, cap.axis)
        // A cone's slanted side is no face.
        val cone = ShapeDesign(listOf(Shape(1, Shape.Kind.CONE, "K", D3(20.0, 20.0, 20.0))), nextId = 2)
        assertNull(cone.faceAt(piece(cone, 1), D3(5.0, 0.0, 10.0), D3(-1.0, 0.0, 0.0)))
    }

    @Test fun aShapeInsideATurnedFlippedRepeatedGroupKeepsItsBottom() {
        val d = ShapeDesign(listOf(
            Shape(10, Shape.Kind.GROUP, "G", D3(), D3(20.0, -5.0, 2.0), D3(0.0, 0.0, 90.0), mirror = D3(1.0, 0.0, 0.0),
                repeat = Repeat(count = 2, step = D3(0.0, 40.0, 0.0)), children = listOf(
                    Shape(11, Shape.Kind.BOX, "B", D3(10.0, 10.0, 10.0), D3(-8.0, 0.0, 0.0), D3(0.0, 0.0, 30.0)),
                    Shape(12, Shape.Kind.CYLINDER, "H", D3(4.0, 4.0, 20.0), D3(-8.0, 0.0, -2.0), hole = true),
                )),
        ), nextId = 13)
        for (copy in 0..1) {
            val p = piece(d, 10, copy)
            val at = apply(p.matrix, D3(-8.0, 0.0, 10.0))
            val bottom = apply(p.matrix, D3(-8.0, 0.0, 0.0))
            val n = (at - bottom).let { v -> D3(v.x / 10, v.y / 10, v.z / 10) }
            val f = d.faceAt(p, at, neg(n))!!
            assertEquals(11, f.shapeId); assertEquals(2, f.axis); assertEquals(1, f.sign)
            // The hole (top at 18) keeps the group's height, so the group stays put.
            val next = posed(d, 11, d.pushed(f, 3.0)!!)
            val q = piece(next, 10, copy)
            near(apply(q.matrix, D3(-8.0, 0.0, 0.0)), bottom, 1e-3)
            near(apply(q.matrix, D3(-8.0, 0.0, 13.0)), at + D3(n.x * 3, n.y * 3, n.z * 3), 1e-3)
        }
    }
}
