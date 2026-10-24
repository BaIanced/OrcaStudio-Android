package app.orcaandroid.scad

import app.orcaandroid.core.Instance
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.RotateGizmo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The Build workspace's gizmos: a plate turn / resize mapped into the shapes moves their pieces exactly as the plate gizmo showed. */
class BuildTransformTest {
    private fun box(id: Int, size: D3, pos: D3 = D3(), rot: D3 = D3(), mirror: D3 = D3(), repeat: Repeat? = null) =
        Shape(id, Shape.Kind.BOX, "S$id", size, pos, rot, false, mirror, repeat = repeat)

    private fun copy(rot: Vec3, mirror: Vec3 = Vec3(1f, 1f, 1f), scale: Float = 1f) =
        Instance(0, 0, Vec3(100f, 50f, 3f), rot, Vec3(scale, scale, scale), mirror, Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))

    /** Design point [p] on the plate: offset + R * scale * mirror * p. */
    private fun plate(inst: Instance, p: D3): D3 {
        val r = RotateGizmo.fromEuler(inst.rotation)
        val q = doubleArrayOf(p.x * inst.scale.x * inst.mirror.x, p.y * inst.scale.y * inst.mirror.y, p.z * inst.scale.z * inst.mirror.z)
        fun row(i: Int) = (0..2).sumOf { r[i * 3 + it] * q[it] }
        return D3(inst.offset.x + row(0), inst.offset.y + row(1), inst.offset.z + row(2))
    }

    private fun apply(m: List<Double>, p: D3) = D3(
        m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3],
        m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7],
        m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11],
    )

    private fun corners(d: ShapeDesign, s: Shape): List<D3> {
        val local = if (s.isGroup) s.children.map { d.bounds(it) }.reduce { a, b -> a.union(b) }
        else d.size(s).let { z -> Bounds(D3(-z.x / 2, -z.y / 2, 0.0), D3(z.x / 2, z.y / 2, z.z)) }
        return (0 until 8).map { i ->
            D3(if (i and 1 == 0) local.min.x else local.max.x, if (i and 2 == 0) local.min.y else local.max.y, if (i and 4 == 0) local.min.z else local.max.z)
        }
    }

    private fun posed(s: Shape, p: BuildTransform.Pose) = s.copy(pos = p.pos, rot = p.rot, size = p.size, repeat = p.repeat)

    private fun assertNear(e: D3, a: D3, tol: Double = 2e-3) {
        for (i in 0..2) assertEquals("axis $i of $a vs $e", e[i], a[i], tol)
    }

    /** Each piece corner of [after] on the plate = [expected] of that corner of [before] on the plate. */
    private fun assertPlate(inst: Instance, before: ShapeDesign, after: ShapeDesign, expected: (D3) -> D3) {
        val old = before.pieces()
        val new = after.pieces()
        assertEquals(old.size, new.size)
        for (k in old.indices) {
            val s0 = before.shapes.first { it.id == old[k].shapeId }
            val s1 = after.shapes.first { it.id == new[k].shapeId }
            corners(before, s0).zip(corners(after, s1)).forEach { (c0, c1) ->
                assertNear(expected(plate(inst, apply(old[k].matrix, c0))), plate(inst, apply(new[k].matrix, c1)))
            }
        }
    }

    private fun turnCase(inst: Instance, s: Shape, axis: Int, deg: Float) {
        val d = ShapeDesign(listOf(s))
        val c = D3(104.0, 47.0, 9.0)
        val m = RotateGizmo.matrix(axis, deg)
        val pose = BuildTransform.turned(d, s, BuildTransform.designTurn(inst, m), BuildTransform.toDesign(inst, Vec3(c.x.toFloat(), c.y.toFloat(), c.z.toFloat())))
        assertPlate(inst, d, ShapeDesign(listOf(posed(s, pose)))) { w ->
            val q = w - c
            D3((0..2).sumOf { m[it] * q[it] }, (0..2).sumOf { m[3 + it] * q[it] }, (0..2).sumOf { m[6 + it] * q[it] }) + c
        }
    }

    @Test fun toDesignUndoesThePlacement() {
        val inst = copy(Vec3(10f, -20f, 135f), Vec3(-1f, 1f, 1f), 1.5f)
        val p = D3(3.0, -7.0, 12.0)
        val w = plate(inst, p)
        assertNear(p, BuildTransform.toDesign(inst, Vec3(w.x.toFloat(), w.y.toFloat(), w.z.toFloat())))
    }

    @Test fun turnOnTheAxesOfATurnedCopy() {
        val s = box(1, D3(20.0, 10.0, 6.0), D3(5.0, 2.0, 0.0), D3(0.0, 0.0, 30.0))
        turnCase(copy(Vec3(0f, 0f, 90f)), s, 0, 40f)
        turnCase(copy(Vec3(0f, 0f, 90f)), s, 2, -75f)
        turnCase(copy(Vec3(15f, 0f, 200f)), s, 1, 90f)
    }

    @Test fun turnAMirroredShapeOfAMirroredCopy() {
        turnCase(copy(Vec3(0f, 0f, 45f), Vec3(-1f, 1f, 1f)), box(1, D3(20.0, 10.0, 6.0), D3(5.0, 2.0, 1.0), D3(10.0, 0.0, 0.0), D3(0.0, 1.0, 0.0)), 1, 30f)
    }

    @Test fun turnARepeatAndAGroup() {
        turnCase(copy(Vec3(0f, 0f, 0f)), box(1, D3(4.0, 4.0, 8.0), D3(0.0, 0.0, 0.0), repeat = Repeat(count = 3, step = D3(10.0, 0.0, 0.0))), 2, 90f)
        turnCase(copy(Vec3(0f, 0f, 0f)), box(1, D3(4.0, 4.0, 8.0), D3(10.0, 0.0, 0.0), repeat = Repeat(circular = true, count = 4, center = D3(2.0, 3.0, 0.0))), 2, 33f)
        val group = Shape(5, Shape.Kind.GROUP, "G", D3(), D3(3.0, 1.0, 0.0), D3(0.0, 0.0, 20.0),
            children = listOf(box(2, D3(10.0, 10.0, 4.0)), box(3, D3(4.0, 4.0, 10.0), D3(6.0, 0.0, 4.0))))
        turnCase(copy(Vec3(0f, 0f, 90f)), group, 0, 90f)
    }

    private fun scaleCase(inst: Instance, s: Shape, f: Vec3) {
        val d = ShapeDesign(listOf(s))
        val p = D3(98.0, 55.0, 3.0)
        val fd = BuildTransform.designScale(inst, f) ?: D3(f.x.toDouble(), f.x.toDouble(), f.x.toDouble())
        val pose = BuildTransform.scaled(d, s, fd, BuildTransform.toDesign(inst, Vec3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())))
        assertNotNull(pose)
        assertPlate(inst, d, ShapeDesign(listOf(posed(s, pose!!)))) { w -> D3(p.x + (w.x - p.x) * f.x, p.y + (w.y - p.y) * f.y, p.z + (w.z - p.z) * f.z) }
    }

    @Test fun scaleAlongThePlateAxes() {
        // A copy turned 90 degrees and a shape turned 90 degrees: plate X is design Y is the shape's X.
        scaleCase(copy(Vec3(0f, 0f, 90f)), box(1, D3(20.0, 10.0, 6.0), D3(5.0, 2.0, 0.0), D3(0.0, 0.0, 90.0)), Vec3(2f, 1f, 1.5f))
        scaleCase(copy(Vec3(0f, 0f, -90f), Vec3(1f, -1f, 1f), 2f), box(1, D3(20.0, 10.0, 6.0), D3(5.0, 2.0, 1.0), D3(0.0, 0.0, 180.0)), Vec3(1f, 0.5f, 1f))
        scaleCase(copy(Vec3(0f, 0f, 0f)), box(1, D3(4.0, 4.0, 8.0), D3(1.0, 1.0, 0.0), repeat = Repeat(count = 3, step = D3(10.0, 5.0, 0.0))), Vec3(1.5f, 2f, 1f))
    }

    @Test fun evenScaleOfTurnedShapes() {
        scaleCase(copy(Vec3(0f, 0f, 45f)), box(1, D3(20.0, 10.0, 6.0), D3(5.0, 2.0, 0.0), D3(30.0, 0.0, 30.0)), Vec3(1.7f, 1.7f, 1.7f))
        assertNull(BuildTransform.designScale(copy(Vec3(0f, 0f, 45f)), Vec3(2f, 1f, 1f)))
    }

    @Test fun toDesignUndoesAnUnevenlyScaledMirroredCopy() {
        val inst = Instance(0, 0, Vec3(100f, 50f, 3f), Vec3(0f, 0f, 90f), Vec3(2f, 0.5f, 1.5f), Vec3(-1f, 1f, 1f), Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
        val p = D3(3.0, -7.0, 12.0)
        val w = plate(inst, p)
        assertNear(p, BuildTransform.toDesign(inst, Vec3(w.x.toFloat(), w.y.toFloat(), w.z.toFloat())))
    }

    @Test fun axesFreeAtExactRightAngles() {
        val d = ShapeDesign()
        assertEquals(true, BuildTransform.axesFree(d, copy(Vec3(0f, 0f, 90f)), emptyList()))
        assertEquals(true, BuildTransform.axesFree(d, copy(Vec3(0f, 0f, 180f)), emptyList()))
        assertEquals(true, BuildTransform.axesFree(d, copy(Vec3(180f, 0f, 0f)),
            listOf(box(1, D3(1.0, 1.0, 1.0), rot = D3(90.0, 0.0, 0.0)))))
        assertEquals(false, BuildTransform.axesFree(d, copy(Vec3(0f, 0f, 45f)), emptyList()))
    }

    @Test fun designScaleAtExactRightAngles() {
        // A quarter-turned copy: plate X scales design Y, plate Y scales design X.
        val f = BuildTransform.designScale(copy(Vec3(0f, 0f, 90f)), Vec3(2f, 3f, 4f))
        assertNotNull(f)
        assertNear(D3(3.0, 2.0, 4.0), f!!, 1e-9)
        // A half-turned copy: the axes stay their own (factors are positive; flips are the mirror's job).
        val g = BuildTransform.designScale(copy(Vec3(0f, 0f, 180f)), Vec3(2f, 3f, 4f))
        assertNotNull(g)
        assertNear(D3(2.0, 3.0, 4.0), g!!, 1e-9)
    }

    @Test fun scaleACircularRepeatAboutItsCentre() {
        // An even scale: the ring's centre moves with the scale and every copy follows.
        scaleCase(copy(Vec3(0f, 0f, 0f)), box(1, D3(4.0, 2.0, 3.0), D3(30.0, 0.0, 0.0),
            repeat = Repeat(circular = true, count = 4, center = D3(20.0, 5.0, 0.0))), Vec3(1.5f, 1.5f, 1.5f))
    }

    @Test fun unevenScaleOfACircularRepeatIsRefused() {
        // The copies are the shape turned about the centre, and a turn does not commute with an uneven
        // scale (copy 1's corner landed 3 mm off in the plate check this test replaces).
        val d = ShapeDesign()
        val ring = box(1, D3(4.0, 2.0, 3.0), D3(30.0, 0.0, 0.0),
            repeat = Repeat(circular = true, count = 4, center = D3(20.0, 5.0, 0.0)))
        assertNull(BuildTransform.scaled(d, ring, D3(1.5, 2.0, 1.0), D3()))
        // A single copy is never turned, so it scales like the plain shape.
        assertNotNull(BuildTransform.scaled(d, ring.copy(repeat = ring.repeat!!.copy(count = 1)), D3(1.5, 2.0, 1.0), D3()))
    }

    @Test fun noScaleForGroupsOrUnevenTurnedShapes() {
        val d = ShapeDesign()
        assertNull(BuildTransform.scaled(d, box(1, D3(1.0, 1.0, 1.0), rot = D3(0.0, 0.0, 30.0)), D3(2.0, 1.0, 1.0), D3()))
        assertNull(BuildTransform.scaled(d, Shape(5, Shape.Kind.GROUP, "G", D3(), children = listOf(box(2, D3(1.0, 1.0, 1.0)))), D3(2.0, 2.0, 2.0), D3()))
        val inst = copy(Vec3(0f, 0f, 0f))
        assertEquals(true, BuildTransform.axesFree(d, inst, listOf(box(1, D3(1.0, 1.0, 1.0), rot = D3(0.0, 90.0, 270.0)))))
        assertEquals(false, BuildTransform.axesFree(d, inst, listOf(box(1, D3(1.0, 1.0, 1.0), rot = D3(0.0, 0.0, 30.0)))))
    }
}
