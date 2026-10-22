package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The orbit camera's pure state maths: presets, clamps, framing, and the eye geometry.
 * Nothing here touches the GL matrices (`viewProjection`, `ray`, `project`), which need
 * `android.opengl.Matrix` and so cannot run in a plain JVM unit test.
 * Default camera: yaw -60, pitch 40, distance 400, target (100, 100, 0).
 */
class CameraTest {

    private fun assertState(c: Camera, yaw: Float, pitch: Float, distance: Float, tx: Float, ty: Float, tz: Float) {
        val s = c.state()
        assertArrayEquals(floatArrayOf(yaw, pitch, distance, tx, ty, tz), s, 0.01f)
    }

    @Test fun defaults() {
        assertState(Camera(), -60f, 40f, 400f, 100f, 100f, 0f)
    }

    @Test fun presets() {
        // View preset: 0 iso, 1 top, 2 front, 3 left, 4 right, 5 bottom, 6 rear (distance/target untouched).
        val expected = mapOf(
            0 to (-60f to 40f), 1 to (-90f to 89f), 2 to (-90f to 0f), 3 to (180f to 0f),
            4 to (0f to 0f), 5 to (-90f to -89f), 6 to (90f to 0f),
        )
        for ((view, yp) in expected) {
            val c = Camera()
            c.preset(view)
            assertState(c, yp.first, yp.second, 400f, 100f, 100f, 0f)
        }
        // An unknown preset index falls back to iso, like 0.
        val c = Camera()
        c.preset(99)
        assertState(c, -60f, 40f, 400f, 100f, 100f, 0f)
    }

    @Test fun orbitClampsPitch() {
        val c = Camera()
        c.orbit(10f, 100f) // yaw -60 - 10 = -70; pitch 40 + 100 clamps to 89
        assertState(c, -70f, 89f, 400f, 100f, 100f, 0f)
        c.orbit(-20f, -300f) // yaw back to -50; pitch 89 - 300 clamps to -89
        assertState(c, -50f, -89f, 400f, 100f, 100f, 0f)
    }

    @Test fun zoomClampsDistance() {
        val c = Camera()
        c.zoom(2f) // 400 / 2 = 200
        assertEquals(200f, c.state()[2], 1e-3f)
        c.zoom(1000f) // 200 / 1000 = 0.2, clamped to the 5 mm minimum
        assertEquals(5f, c.state()[2], 1e-3f)
        val far = Camera()
        far.zoom(0.001f) // 400 / 0.001 = 400000, clamped to the 10000 mm maximum
        assertEquals(10000f, far.state()[2], 1e-3f)
    }

    @Test fun aimClampsAndLeavesNullsAlone() {
        val c = Camera()
        c.aim(pitch = 120f)
        assertEquals(89f, c.state()[1], 1e-3f)
        c.aim(pitch = -120f)
        assertEquals(-89f, c.state()[1], 1e-3f)
        c.aim(distance = 1f)
        assertEquals(5f, c.state()[2], 1e-3f)
        c.aim(distance = 1e9f)
        assertEquals(10000f, c.state()[2], 1e-3f)
        // Yaw is set unclamped, and arguments left null keep their values.
        c.aim(yaw = 33f)
        assertState(c, 33f, -89f, 10000f, 100f, 100f, 0f)
    }

    @Test fun frameCentresTargetAndFitsDiagonal() {
        // Landscape 200 x 100 viewport: diag = hypot(200, 100) = 223.607; aspect >= 1, so
        // distance = diag * 1.5 + 20 = 355.41. Target is the box centre, z reset to 0.
        val c = Camera()
        c.setViewport(200, 100)
        c.frame(0f, 0f, 200f, 100f)
        assertState(c, -60f, 40f, 355.41f, 100f, 50f, 0f)
        // top = true only changes the pitch to (near) straight down.
        c.frame(0f, 0f, 200f, 100f, top = true)
        assertState(c, -60f, 89f, 355.41f, 100f, 50f, 0f)
        // Portrait: aspect 0.5 < 1, so distance = diag * 1.8 / 0.5 + 20 = 824.98.
        val p = Camera()
        p.setViewport(100, 200)
        p.frame(0f, 0f, 200f, 100f)
        assertState(p, -60f, 40f, 824.98f, 100f, 50f, 0f)
    }

    @Test fun fitCentresBoxAndResetsAngles() {
        // Box 0..20 on every axis: centre (10, 10, 10), radius = |(20,20,20)| / 2 = 17.3205.
        // half = tan(35 / 2 deg) * min(1, aspect 2) = 0.31530; distance = 17.3205 / 0.31530 * 1.1 = 60.43.
        val c = Camera()
        c.setViewport(200, 100)
        c.fit(Vec3(0f, 0f, 0f), Vec3(20f, 20f, 20f))
        assertState(c, -60f, 30f, 60.43f, 10f, 10f, 10f)
        // keepAngles keeps the current yaw/pitch (here the right-side preset 0 / 0).
        val k = Camera()
        k.setViewport(200, 100)
        k.preset(4)
        k.fit(Vec3(0f, 0f, 0f), Vec3(20f, 20f, 20f), keepAngles = true)
        assertState(k, 0f, 0f, 60.43f, 10f, 10f, 10f)
    }

    @Test fun focusSnapsYawToNearestSide() {
        // Yaw snaps to the nearest multiple of 90 and pitch levels to 0; the box is then fit.
        val c = Camera()
        c.setViewport(200, 100)
        c.aim(yaw = 100f, pitch = 55f) // round(100 / 90) * 90 = 90
        c.focus(Vec3(0f, 0f, 0f), Vec3(20f, 20f, 20f))
        assertState(c, 90f, 0f, 60.43f, 10f, 10f, 10f)
        val d = Camera()
        d.setViewport(200, 100)
        d.aim(yaw = -100f) // round(-100 / 90) * 90 = -90
        d.focus(Vec3(0f, 0f, 0f), Vec3(20f, 20f, 20f))
        assertEquals(-90f, d.state()[0], 1e-3f)
        assertEquals(0f, d.state()[1], 1e-3f)
    }

    @Test fun eyeAndTowardsEye() {
        // Yaw 0, pitch 0 looks from +X: towardsEye = (cos0*cos0, cos0*sin0, sin0) = (1, 0, 0),
        // and the eye sits one distance along it from the target.
        val c = Camera()
        c.aim(yaw = 0f, pitch = 0f, distance = 100f, target = Vec3(0f, 0f, 0f))
        val t = c.towardsEye()
        assertEquals(1f, t.x, 1e-5f); assertEquals(0f, t.y, 1e-5f); assertEquals(0f, t.z, 1e-5f)
        val e = c.eye()
        assertEquals(100f, e.x, 1e-3f); assertEquals(0f, e.y, 1e-3f); assertEquals(0f, e.z, 1e-3f)
        // Default angles: (cos40*cos-60, cos40*sin-60, sin40) = (0.38302, -0.66341, 0.64279), a unit vector.
        val d = Camera().towardsEye()
        assertEquals(0.38302f, d.x, 1e-4f); assertEquals(-0.66341f, d.y, 1e-4f); assertEquals(0.64279f, d.z, 1e-4f)
        assertEquals(1f, d.length(), 1e-5f)
    }

    @Test fun clipDistances() {
        // Perspective: near = max(1, distance / 50), far = distance * 20. At 400: 8 to 8000.
        val c = Camera()
        assertEquals(8f to 8000f, c.clip())
        c.aim(distance = 10f) // near would be 0.2, floored to 1; far 200
        assertEquals(1f to 200f, c.clip())
        // Orthographic: near is always 1; far = (distance + ORTHO_BACK) + ORTHO_BACK = 400 + 4000.
        c.aim(distance = 400f)
        c.perspective = false
        assertEquals(1f to 4400f, c.clip())
    }

    @Test fun panMovesTargetInViewPlane() {
        // Viewport height 100, distance 400: scale = 2 * 400 * tan(17.5 deg) / 100 = 2.52239 mm per px.
        // At yaw -60 / pitch 40: right = (0.86603, 0.5, 0), up = (-0.32139, 0.55667, 0.76604).
        // pan(10, 0) moves the target by -10 * right * scale = (-21.845, -12.612, 0).
        val c = Camera()
        c.setViewport(100, 100)
        c.pan(10f, 0f)
        assertState(c, -60f, 40f, 400f, 78.155f, 87.388f, 0f)
        // pan(0, 10) moves it by +10 * up * scale = (-8.107, 14.041, 19.323) from the default target.
        val d = Camera()
        d.setViewport(100, 100)
        d.pan(0f, 10f)
        assertState(d, -60f, 40f, 400f, 91.893f, 114.041f, 19.323f)
    }

    @Test fun stateRestoreRoundTrip() {
        val c = Camera()
        c.aim(yaw = 12f, pitch = -34f, distance = 777f, target = Vec3(1f, 2f, 3f))
        val saved = c.state()
        c.preset(1)
        c.zoom(3f)
        c.restore(saved)
        assertArrayEquals(saved, c.state(), 0f)
    }
}
