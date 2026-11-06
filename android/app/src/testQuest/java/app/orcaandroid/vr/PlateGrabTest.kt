package app.orcaandroid.vr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateGrabTest {
    private val dt = 1f / 72
    private val hands = FloatArray(XrShim.HANDS * XrShim.FLOATS_PER_HAND)

    private fun grab() = PlateGrab().also { it.setBounds(-128f, -128f, 128f, 128f) }

    /** A controller pitched down 45 degrees from 0.4 m above and 0.4 m before the plate centre: aims at the centre. */
    private fun aimAtCentre(hand: Int = 0, dx: Float = 0f) {
        val half = (-PI / 8).toFloat()
        val o = hand * XrShim.FLOATS_PER_HAND
        hands[o + XrShim.HAND_ACTIVE] = 1f
        hands[o + XrShim.HAND_POSE] = dx
        hands[o + XrShim.HAND_POSE + 1] = VrMath.TABLE_Y + 0.4f
        hands[o + XrShim.HAND_POSE + 2] = VrMath.TABLE_AHEAD + 0.4f
        hands[o + XrShim.HAND_POSE + 3] = sin(half)
        hands[o + XrShim.HAND_POSE + 4] = 0f
        hands[o + XrShim.HAND_POSE + 5] = 0f
        hands[o + XrShim.HAND_POSE + 6] = cos(half)
    }

    private fun set(field: Int, value: Float, hand: Int = 0) {
        hands[hand * XrShim.FLOATS_PER_HAND + field] = value
    }

    private fun run(g: PlateGrab, frames: Int = 72) {
        repeat(frames) { g.update(hands, dt) }
    }

    private fun held(g: PlateGrab): PlateGrab {
        aimAtCentre()
        g.update(hands, dt)
        set(XrShim.HAND_GRIP, 1f)
        g.update(hands, dt)
        assertEquals(0, g.holder)
        return g
    }

    @Test fun aimingAtThePlateHovers() {
        val g = grab()
        aimAtCentre()
        g.update(hands, dt)
        assertEquals(PlateGrab.RAY_HOVER, g.rayState[0])
        assertEquals(0.5657f, g.rayLength[0], 1e-3f)
    }

    @Test fun aimingOffThePlateDoesNotHoverOrGrab() {
        val g = grab()
        aimAtCentre(dx = 1f)
        g.update(hands, dt)
        assertEquals(PlateGrab.RAY_IDLE, g.rayState[0])
        set(XrShim.HAND_GRIP, 1f)
        g.update(hands, dt)
        assertEquals(-1, g.holder)
    }

    @Test fun anInactiveHandDrawsNoRay() {
        val g = grab()
        g.update(hands, dt)
        assertEquals(PlateGrab.RAY_NONE, g.rayState[0])
    }

    @Test fun grabbingDoesNotJump() {
        val g = held(grab())
        run(g)
        assertEquals(0f, g.x, 1e-4f)
        assertEquals(VrMath.TABLE_Y, g.y, 1e-4f)
        assertEquals(VrMath.TABLE_AHEAD, g.z, 1e-4f)
    }

    @Test fun aGripHeldBeforeAimingIsNotAGrab() {
        val g = grab()
        aimAtCentre(dx = 1f)
        set(XrShim.HAND_GRIP, 1f)
        g.update(hands, dt)
        aimAtCentre()
        g.update(hands, dt)
        assertEquals(-1, g.holder)
    }

    @Test fun thePlateFollowsTheHandAndStaysWhereDropped() {
        val g = held(grab())
        aimAtCentre(dx = 0.2f)
        run(g, 200)
        assertEquals(0.2f, g.x, 1e-3f)
        set(XrShim.HAND_GRIP, 0f)
        assertEquals(PlateGrab.RELEASED, g.update(hands, dt))
        val dropped = g.x
        aimAtCentre(dx = 0.5f)
        set(XrShim.HAND_STICK_Y, 1f)
        run(g)
        assertEquals("no inertia, and the stick does nothing when dropped", dropped, g.x, 0f)
    }

    @Test fun smoothingLagsAJumpButCatchesUp() {
        val g = held(grab())
        aimAtCentre(dx = 0.2f)
        g.update(hands, dt)
        assertTrue("one frame must not reach the target", g.x < 0.2f)
        assertTrue("but must move", g.x > 0f)
    }

    @Test fun stickUpIsFartherAndDownIsCloser() {
        val g = held(grab())
        val start = g.z
        set(XrShim.HAND_STICK_Y, 1f)
        run(g)
        val far = g.z
        assertTrue("up moves away, along -Z", far < start)
        set(XrShim.HAND_STICK_Y, -1f)
        run(g, 200)
        assertTrue("down comes back", g.z > far)
    }

    @Test fun distanceStaysInRange() {
        val g = held(grab())
        set(XrShim.HAND_STICK_Y, -1f)
        run(g, 72 * 10)
        assertEquals(PlateGrab.MIN_DISTANCE_M, g.rayLength[0], 1e-4f)
        set(XrShim.HAND_STICK_Y, 1f)
        run(g, 72 * 10)
        assertEquals(PlateGrab.MAX_DISTANCE_M, g.rayLength[0], 1e-4f)
    }

    @Test fun stickRightIsBiggerAndLeftIsSmaller() {
        val g = held(grab())
        set(XrShim.HAND_STICK_X, 1f)
        run(g)
        assertTrue(g.scale > 1f)
        set(XrShim.HAND_STICK_X, 0f)
        run(g, 10)
        val big = g.scale
        set(XrShim.HAND_STICK_X, -0.8f)
        run(g, 5)
        assertTrue(g.scale < big)
    }

    @Test fun zoomStopsAtTrueSizeUntilTheStickComesBack() {
        val g = held(grab())
        g.place(g.x, g.y, g.z, 1.02f)
        set(XrShim.HAND_STICK_X, -1f)
        run(g)
        assertEquals(1f, g.scale, 0f)
        set(XrShim.HAND_STICK_X, 0f)
        run(g, 2)
        set(XrShim.HAND_STICK_X, -1f)
        run(g, 72)
        assertTrue("a second push goes through", g.scale < 1f)
    }

    @Test fun stickClickResetsToTrueSize() {
        val g = held(grab())
        set(XrShim.HAND_STICK_X, 1f)
        run(g)
        set(XrShim.HAND_STICK_X, 0f)
        set(XrShim.HAND_CLICK, 1f)
        g.update(hands, dt)
        assertEquals(1f, g.scale, 0f)
    }

    @Test fun zoomKeepsTheHeldPointUnderTheRay() {
        val g = grab()
        // Aim 100 mm right of centre, so the held point is not the centre.
        aimAtCentre(dx = 0.1f)
        g.update(hands, dt)
        set(XrShim.HAND_GRIP, 1f)
        g.update(hands, dt)
        set(XrShim.HAND_STICK_X, 1f)
        run(g, 720)
        val scale = g.scale
        assertTrue(scale > 2f)
        // The centre moves away from the ray: the plate point under the ray stays 100 mm from centre, in plate mm.
        assertEquals(0.1f - 0.1f * scale, g.x, 2e-3f)
    }

    @Test fun aSmallDiagonalIsLockedToTheStrongerAxis() {
        val g = held(grab())
        set(XrShim.HAND_STICK_Y, 1f)
        set(XrShim.HAND_STICK_X, 0.4f)
        run(g)
        assertEquals(1f, g.scale, 0f)
    }

    @Test fun theDeadZoneIgnoresDrift() {
        val g = held(grab())
        val start = g.z
        set(XrShim.HAND_STICK_Y, 0.1f)
        set(XrShim.HAND_STICK_X, 0.1f)
        run(g)
        assertEquals(start, g.z, 1e-3f)
        assertEquals(1f, g.scale, 0f)
    }

    @Test fun theOtherHandCannotTakeOver() {
        val g = held(grab())
        aimAtCentre(hand = 1)
        g.update(hands, dt)
        set(XrShim.HAND_GRIP, 1f, hand = 1)
        g.update(hands, dt)
        assertEquals(0, g.holder)
        set(XrShim.HAND_GRIP, 0f, hand = 1)
        val z = g.z
        set(XrShim.HAND_STICK_Y, 1f, hand = 1)
        run(g)
        assertEquals("only the holder's stick counts", z, g.z, 1e-3f)
    }

    @Test fun eitherHandCanGrab() {
        val g = grab()
        aimAtCentre(hand = 1)
        g.update(hands, dt)
        set(XrShim.HAND_GRIP, 1f, hand = 1)
        g.update(hands, dt)
        assertEquals(1, g.holder)
    }

    @Test fun losingTrackingDropsThePlateAndAHeldGripDoesNotRegrab() {
        val g = held(grab())
        set(XrShim.HAND_ACTIVE, 0f)
        assertEquals(PlateGrab.RELEASED, g.update(hands, dt))
        assertEquals(-1, g.holder)
        aimAtCentre()
        g.update(hands, dt)
        assertEquals(-1, g.holder)
    }

    @Test fun toggleModeClicksToPickUpAndDrop() {
        val g = grab()
        g.tuning.grabToggle = true
        held(g)
        set(XrShim.HAND_GRIP, 0f)
        run(g, 5)
        assertEquals("releasing the button keeps holding", 0, g.holder)
        set(XrShim.HAND_GRIP, 1f)
        assertEquals(PlateGrab.RELEASED, g.update(hands, dt))
        run(g, 5)
        assertEquals("the same press doesn't pick it up again", -1, g.holder)
    }

    @Test fun placedScaleGoesThroughTheWorldMatrixAndBack() {
        val m = FloatArray(16)
        VrMath.plateToLocal(m, 100f, 50f, 0.3f, -0.2f, -1f, 2f)
        // The plate centre lands on the placed point, and 100 mm of plate is 0.2 m at zoom 2.
        assertEquals(0.3f, m[0] * 100f + m[12], 1e-5f)
        assertEquals(-0.2f, m[13], 1e-5f)
        assertEquals(-1f, m[6] * 50f + m[14], 1e-5f)
        assertEquals(0.002f, m[0], 1e-7f)
        val out = FloatArray(3)
        VrMath.localToPlate(out, floatArrayOf(0.5f, 0f, -1.2f), 0, 100f, 50f, 0.3f, -0.2f, -1f, 2f)
        assertEquals(200f, out[0], 1e-3f)
        assertEquals(150f, out[1], 1e-3f)
        assertEquals(100f, out[2], 1e-3f)
    }

    @Test fun aimMatrixPutsTheRayEndWhereTheAimPoints() {
        aimAtCentre()
        val m = FloatArray(16)
        VrMath.aimMatrix(m, hands, XrShim.HAND_POSE, 0.5657f)
        // The unit ray ends at z = -1 in its own space.
        assertEquals(VrMath.TABLE_Y, m[9] * -1f + m[13], 1e-3f)
        assertEquals(VrMath.TABLE_AHEAD, m[10] * -1f + m[14], 1e-3f)
    }
}
