package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cut plane's placement maths: facing a normal, and the height that puts it through a point. */
class CutPlaneTest {
    @Test fun rotFacingPointsTheNormal() {
        for (n in listOf(Vec3(0f, 0f, 1f), Vec3(1f, 0f, 1f), Vec3(0f, 1f, 2f), Vec3(-0.3f, 0.5f, 0.8f), Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f))) {
            val l = n.length()
            val got = CutPlane.normal(CutPlane.rotFacing(n))
            assertEquals("x of $n", n.x / l, got.x, 1e-4f)
            assertEquals("y of $n", n.y / l, got.y, 1e-4f)
            assertEquals("z of $n", n.z / l, got.z, 1e-4f)
        }
    }

    @Test fun downwardNormalIsFlipped() {
        val got = CutPlane.normal(CutPlane.rotFacing(Vec3(0.2f, 0f, -1f)))
        val l = Vec3(0.2f, 0f, 1f).length()
        assertEquals(-0.2f / l, got.x, 1e-4f)
        assertEquals(1f / l, got.z, 1e-4f)
    }

    @Test fun heightPutsThePlaneThroughThePoint() {
        val rot = CutPlane.rotFacing(Vec3(0.4f, -0.3f, 1f))
        val p = Vec3(12f, -7f, 9f)
        val z = CutPlane.heightThrough(5f, 3f, rot, p)!!
        val n = CutPlane.normal(rot)
        assertEquals(0f, n.dot(p - Vec3(5f, 3f, z)), 1e-3f)
        assertEquals(9f, CutPlane.heightThrough(5f, 3f, Vec3(0f, 0f, 0f), p)!!, 1e-5f)
    }

    @Test fun uprightPlaneHasNoHeight() {
        assertNull(CutPlane.heightThrough(0f, 0f, CutPlane.rotFacing(Vec3(1f, 0f, 0f)), Vec3(3f, 0f, 2f)))
    }

    @Test fun uprightPlaneShiftsThroughThePoint() {
        val rot = CutPlane.rotFacing(Vec3(1f, 1f, 0f))
        val p = Vec3(14f, 2f, 30f)
        val (dx, dy) = CutPlane.shiftThrough(4f, 4f, rot, p)
        assertEquals(0f, CutPlane.normal(rot).dot(p - Vec3(4f + dx, 4f + dy, 0f)), 1e-3f)
        val a = CutPlane.slideAxis(rot)
        assertEquals(0f, a.z, 0f)
        assertEquals(1f, CutPlane.slideAxis(CutPlane.rotFacing(Vec3(0.3f, 0f, 1f))).z, 0f)
    }
}
