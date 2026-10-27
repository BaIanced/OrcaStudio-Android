package app.orcaandroid.ui

import app.orcaandroid.core.PickHit
import app.orcaandroid.core.SnapFeature
import app.orcaandroid.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MeasureTest {
    private val up = Vec3(0f, 0f, 1f)

    private fun face(p: Vec3, n: Vec3) = MeasurePoint(p, "face", n)

    /** A closed polygon of [n] segments, radius [r], round [c] in the plane z = c.z, with alternating segment directions. */
    private fun ring(c: Vec3, r: Float, n: Int = 24): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until n) {
            val a0 = 2 * Math.PI * i / n
            val a1 = 2 * Math.PI * (i + 1) / n
            val p0 = listOf(c.x + r * cos(a0).toFloat(), c.y + r * sin(a0).toFloat(), c.z)
            val p1 = listOf(c.x + r * cos(a1).toFloat(), c.y + r * sin(a1).toFloat(), c.z)
            out += if (i % 2 == 0) p0 + p1 else p1 + p0
        }
        return out.toFloatArray()
    }

    private fun hit(p: Vec3, n: Vec3, f: SnapFeature?) = PickHit(0, 0, 0, 0, p, n, p, null, f)

    @Test fun parallelFacesGiveTheGapSquareToThem() {
        val r = measureResult(face(Vec3(0f, 0f, 0f), up), face(Vec3(3f, 4f, 10f), Vec3(0f, 0f, -1f)))
        assertEquals(10f, r.gap!!, 1e-4f)
        assertEquals(Vec3(3f, 4f, 10f).length(), r.distance, 1e-4f)
        assertNull(r.toPlane); assertNull(r.angle)
    }

    @Test fun tiltedFacesGiveTheAngle() {
        val r = measureResult(face(Vec3.ZERO, up), face(Vec3(5f, 0f, 0f), Vec3(1f, 0f, 0f)))
        assertNull(r.gap)
        assertEquals(90f, r.angle!!, 1e-3f)
    }

    @Test fun pointToFaceIsSquareToTheFace() {
        val r = measureResult(MeasurePoint(Vec3(7f, -2f, 4f), "vertex"), MeasurePoint.bed(Vec3(0f, 0f, 0f)))
        assertEquals(4f, r.toPlane!!, 1e-4f)
        assertEquals(Vec3(7f, 2f, 4f), r.delta)
    }

    @Test fun twoPointsGiveOnlyTheDistance() {
        val r = measureResult(MeasurePoint(Vec3.ZERO, "vertex"), MeasurePoint(Vec3(1f, 2f, 2f), "edge"))
        assertEquals(3f, r.distance, 1e-5f)
        assertNull(r.gap); assertNull(r.toPlane); assertNull(r.angle)
    }

    @Test fun ringPicksItsCentreAndAxis() {
        val c = Vec3(10f, 20f, 5f)
        val m = MeasurePoint.of(hit(Vec3(14.75f, 20f, 5f), up, SnapFeature("ring", ring(c, 4.75f), 9.5f)))
        assertEquals("ring", m.kind)
        assertEquals(0f, (m.point - c).length(), 1e-3f)
        assertEquals(1f, kotlin.math.abs(m.normal!!.z), 1e-4f)
        assertEquals(9.5f, m.diameter!!, 0f)
    }

    @Test fun faceIsFlatOnlyWhenItsOutlineIsPlanar() {
        val square = floatArrayOf(0f, 0f, 2f, 10f, 0f, 2f, 10f, 0f, 2f, 10f, 10f, 2f, 10f, 10f, 2f, 0f, 10f, 2f, 0f, 10f, 2f, 0f, 0f, 2f)
        val flat = MeasurePoint.of(hit(Vec3(5f, 5f, 2f), up, SnapFeature("face", square, null)))
        assertTrue(flat.isPlane)
        // A cylinder side: the outline is two circles 10 mm apart, not in the hit's tangent plane.
        val side = ring(Vec3.ZERO, 5f) + ring(Vec3(0f, 0f, 10f), 5f)
        val curved = MeasurePoint.of(hit(Vec3(5f, 0f, 5f), Vec3(1f, 0f, 0f), SnapFeature("face", side, null)))
        assertFalse(curved.isPlane)
        assertEquals("point", curved.kind)
        // No outline at all (a closed smooth surface) and a bare hit: points.
        assertEquals("point", MeasurePoint.of(hit(Vec3.ZERO, up, SnapFeature("face", FloatArray(0), null))).kind)
        assertEquals("point", MeasurePoint.of(hit(Vec3.ZERO, up, null)).kind)
    }

    @Test fun emptyRingFallsBack() {
        assertNull(MeasurePoint.ringOf(FloatArray(0)))
        assertNotNull(MeasurePoint.ringOf(ring(Vec3.ZERO, 1f, 8)))
    }

    @Test fun axisLockNearAnAxis() {
        val a = Vec3(1f, 2f, 3f)
        // 5 degrees off vertical: locks to Z, measured along it, B's foot straight above A.
        val t = Math.toRadians(5.0)
        val b = a + Vec3((10 * sin(t)).toFloat(), 0f, (10 * cos(t)).toFloat())
        val lock = measureLock(a, b, axes = true, steps = false)!!
        assertEquals(2, lock.axis)
        assertEquals((10 * cos(t)).toFloat(), lock.along, 1e-4f)
        assertEquals(a.x, lock.foot.x, 1e-5f); assertEquals(b.z, lock.foot.z, 1e-5f)
        // Downwards along -Y: still Y, a positive length.
        val y = measureLock(a, a + Vec3(0.5f, -20f, 0f), axes = true, steps = false)!!
        assertEquals(1, y.axis); assertEquals(20f, y.along, 1e-4f)
        // 12 degrees off: no lock; the toggle off: none either.
        val u = Math.toRadians(12.0)
        assertNull(measureLock(a, a + Vec3((10 * cos(u)).toFloat(), (10 * sin(u)).toFloat(), 0f), axes = true, steps = false))
        assertNull(measureLock(a, b, axes = false, steps = false))
        assertNull(measureLock(a, a, axes = true, steps = true))
    }

    @Test fun angleStepsLockToFifteenDegrees() {
        val a = Vec3.ZERO
        // Flat, 47 degrees round: 45 degree step (no axis), measured along it.
        val t = Math.toRadians(47.0)
        val lock = measureLock(a, Vec3((10 * cos(t)).toFloat(), (10 * sin(t)).toFloat(), 0f), axes = true, steps = true)!!
        assertEquals(-1, lock.axis)
        assertEquals(45f, lock.azimuth, 1e-3f); assertEquals(0f, lock.elevation, 1e-3f)
        assertEquals((10 * cos(Math.toRadians(2.0))).toFloat(), lock.along, 1e-4f)
        // 31 degrees up: the 30 degree elevation step.
        val e = Math.toRadians(31.0)
        assertEquals(30f, measureLock(a, Vec3((10 * cos(e)).toFloat(), 0f, (10 * sin(e)).toFloat()), axes = false, steps = true)!!.elevation, 1e-3f)
        // A step that lands on an axis takes its colour: -X (azimuth 180) and straight down.
        assertEquals(0, measureLock(a, Vec3(-10f, 0.5f, 0f), axes = false, steps = true)!!.axis)
        assertEquals(2, measureLock(a, Vec3(0.3f, 0f, -10f), axes = false, steps = true)!!.axis)
    }

    @Test fun planesDontLock() {
        val a = MeasurePoint(Vec3.ZERO, "vertex")
        val b = MeasurePoint(Vec3(0f, 0f, 10f), "vertex")
        assertNotNull(measureLock(a, b, axes = true, steps = false))
        assertNull(measureLock(a, face(Vec3(0f, 0f, 10f), up), axes = true, steps = false))
        assertNull(measureLock(MeasurePoint.bed(Vec3.ZERO), b, axes = true, steps = true))
        // Two bed spots (one plane): no gap of 0, point to point, and the line locks (here along X).
        val beds = MeasurePoint.bed(Vec3.ZERO) to MeasurePoint.bed(Vec3(30f, 1f, 0f))
        val r = measureResult(beds.first, beds.second)
        assertNull(r.gap); assertNull(r.toPlane)
        assertEquals(0, measureLock(beds.first, beds.second, axes = true, steps = false)!!.axis)
        // A corner on the face it bounds: no square distance of 0 either.
        assertNull(measureResult(face(Vec3.ZERO, up), MeasurePoint(Vec3(5f, 0f, 0f), "vertex")).toPlane)
    }
}
