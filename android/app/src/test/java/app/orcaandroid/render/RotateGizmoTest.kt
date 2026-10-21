package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rotate gizmo's matrix maths: Euler angles in and out (Rz * Ry * Rx, as libslic3r's Geometry). */
class RotateGizmoTest {
    private fun assertSame(a: FloatArray, b: FloatArray) {
        for (i in 0..8) assertEquals("element $i", a[i], b[i], 1e-4f)
    }

    @Test fun eulerRoundTrip() {
        for (r in listOf(Vec3(0f, 0f, 0f), Vec3(30f, -20f, 75f), Vec3(-170f, 45f, 10f), Vec3(90f, 0f, 90f), Vec3(10f, 90f, 0f), Vec3(-35f, -90f, 0f))) {
            val m = RotateGizmo.fromEuler(r)
            assertSame(m, RotateGizmo.fromEuler(RotateGizmo.toEuler(m)))
        }
    }

    @Test fun worldTurnComposes() {
        // A world turn of 90 about Z on an unrotated copy is Euler z = 90.
        val e = RotateGizmo.toEuler(RotateGizmo.mul(RotateGizmo.matrix(2, 90f), RotateGizmo.fromEuler(Vec3(0f, 0f, 0f))))
        assertEquals(0f, e.x, 1e-3f); assertEquals(0f, e.y, 1e-3f); assertEquals(90f, e.z, 1e-3f)
        // A world turn about X after Z keeps the earlier turn: the matrix equals the product.
        val m = RotateGizmo.mul(RotateGizmo.matrix(0, 40f), RotateGizmo.fromEuler(Vec3(0f, 0f, 90f)))
        assertSame(m, RotateGizmo.fromEuler(RotateGizmo.toEuler(m)))
        // +90 about Z turns +X into +Y (counter-clockwise from the axis tip).
        val p = RotateGizmo.apply(RotateGizmo.matrix(2, 90f), Vec3(1f, 0f, 0f))
        assertEquals(0f, p.x, 1e-5f); assertEquals(1f, p.y, 1e-5f)
    }
}
