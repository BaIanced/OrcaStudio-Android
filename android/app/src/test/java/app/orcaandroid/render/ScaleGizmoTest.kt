package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scale gizmo's pure maths: pivot, per-axis scale vectors, colours, and which handles a
 * camera position leaves visible. The scenarios are worked from the code as written: handles
 * are hidden when the segment from the eye to the handle passes through the box shrunk by
 * 0.1% of its largest side, and the bottom ring is kept when at least 2 of its corners show.
 */
class ScaleGizmoTest {
    private val lo = Vec3(0f, 0f, 0f)
    private val hi = Vec3(10f, 10f, 10f)

    @Test fun pivotIsBottomCentre() {
        // Centre of the footprint, at the box's bottom height (not its middle height).
        assertEquals(Vec3(5f, 10f, 5f), ScaleGizmo.pivot(Vec3(0f, 0f, 5f), Vec3(10f, 20f, 15f)))
        assertEquals(Vec3(5f, 5f, 0f), ScaleGizmo.pivot(lo, hi))
    }

    @Test fun scalePerAxis() {
        // Axis handles scale only their own axis; a corner (axis -1, and any other value) is uniform.
        assertEquals(Vec3(2f, 1f, 1f), ScaleGizmo.scale(ScaleGizmo.Handle(4, Vec3.ZERO, 0), 2f))
        assertEquals(Vec3(1f, 2f, 1f), ScaleGizmo.scale(ScaleGizmo.Handle(6, Vec3.ZERO, 1), 2f))
        assertEquals(Vec3(1f, 1f, 2f), ScaleGizmo.scale(ScaleGizmo.Handle(8, Vec3.ZERO, 2), 2f))
        assertEquals(Vec3(2f, 2f, 2f), ScaleGizmo.scale(ScaleGizmo.Handle(0, Vec3.ZERO, -1), 2f))
    }

    @Test fun coloursPerAxis() {
        // X red, Y green, Z blue, corners light grey (the desktop's AXES_COLOR / GRABBER_UNIFORM_COL).
        assertArrayEquals(floatArrayOf(0.85f, 0.2f, 0.2f), ScaleGizmo.color(0), 0f)
        assertArrayEquals(floatArrayOf(0.25f, 0.75f, 0.25f), ScaleGizmo.color(1), 0f)
        assertArrayEquals(floatArrayOf(0.25f, 0.45f, 0.95f), ScaleGizmo.color(2), 0f)
        assertArrayEquals(floatArrayOf(0.85f, 0.85f, 0.85f), ScaleGizmo.color(-1), 0f)
    }

    /**
     * A camera at eye (100, 5, 5): yaw 0 / pitch 0 around target (0, 5, 5) at distance 100,
     * with a 100 x 1000 viewport (the height sets pxSize, hence the Z handle's lift).
     * From +X only the box's +X face shows: of the bottom ring the two +X corners (handles 1
     * and 2) and the +X edge midpoint (handle 5) survive the through-box test; the -X corners
     * and midpoint and both Y midpoints are behind the box. 2 visible corners keep the bottom
     * ring (no switch to the top ring).
     */
    private fun sideCamera(): Camera {
        val c = Camera()
        c.setViewport(100, 1000)
        c.aim(yaw = 0f, pitch = 0f, distance = 100f, target = Vec3(0f, 5f, 5f))
        return c
    }

    @Test fun visibleFromSide() {
        val handles = ScaleGizmo.visible(lo, hi, sideCamera(), axes = true)
        assertEquals(listOf(1, 2, 5, 8), handles.map { it.index })
        assertEquals(listOf(-1, -1, 0, 2), handles.map { it.axis })
        assertEquals(Vec3(10f, 0f, 0f), handles[0].pos)
        assertEquals(Vec3(10f, 10f, 0f), handles[1].pos)
        assertEquals(Vec3(10f, 5f, 0f), handles[2].pos)
        // The Z handle floats over the top centre by pxSize * Z_LIFT_PX: the depth of the top
        // centre from this eye is (100 - 5) = 95 mm along the view direction, so pxSize =
        // 2 * 95 * tan(17.5 deg) / 1000 = 0.05991 mm/px, and the lift is 0.05991 * 22 = 1.318 mm.
        val z = handles[3].pos
        assertEquals(5f, z.x, 1e-3f)
        assertEquals(5f, z.y, 1e-3f)
        assertEquals(11.318f, z.z, 0.01f)
    }

    @Test fun visibleWithoutAxesShowsCornersOnly() {
        // With axes off there are no edge-midpoint handles and no Z handle: just the corners
        // the box does not hide from this side (handles 1 and 2, as in visibleFromSide).
        val handles = ScaleGizmo.visible(lo, hi, sideCamera(), axes = false)
        assertEquals(listOf(1, 2), handles.map { it.index })
    }

    @Test fun ratioWithNoReachIsOne() {
        // A handle sitting on its own pivot has zero reach, so any drag reports ratio 1
        // (no scale) without ever consulting the camera rays.
        val c = sideCamera()
        val centred = ScaleGizmo.Handle(4, Vec3(5f, 5f, 0f), 0)
        assertEquals(1f, ScaleGizmo.ratio(centred, lo, hi, c, 0f, 0f, 50f, 50f), 0f)
        // Same for a Z handle on a flat box (hi.z == lo.z gives zero height to scale from).
        val flatLo = Vec3(0f, 0f, 3f)
        val flatHi = Vec3(10f, 10f, 3f)
        val z = ScaleGizmo.Handle(8, Vec3(5f, 5f, 3f), 2)
        assertEquals(1f, ScaleGizmo.ratio(z, flatLo, flatHi, c, 0f, 0f, 50f, 50f), 0f)
    }
}
