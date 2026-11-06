package app.orcaandroid.vr

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelTextTest {
    private fun hands() = FloatArray(XrShim.HANDS * XrShim.FLOATS_PER_HAND)

    private fun status(h: FloatArray, holder: Int = -1) = PanelText.status(h, holder, 0f, -0.3f, -0.4f, 1f).lines()

    @Test
    fun untrackedHandsSayWhy() {
        val lines = status(hands())
        assertEquals("Left: no position (asleep or out of the cameras' view)", lines[0])
        assertEquals("Right: no position (asleep or out of the cameras' view)", lines[1])
    }

    @Test
    fun trackedHandShowsGripAndStick() {
        val h = hands()
        val o = XrShim.FLOATS_PER_HAND
        h[o + XrShim.HAND_ACTIVE] = 1f
        h[o + XrShim.HAND_GRIP] = 1f
        h[o + XrShim.HAND_STICK_X] = 0.26f
        h[o + XrShim.HAND_STICK_Y] = -0.8f
        assertEquals("Right: tracked, grip down, stick 0.3 -0.8", status(h)[1])
    }

    @Test
    fun stickNoiseDoesNotChangeTheText() {
        val h = hands()
        h[XrShim.HAND_ACTIVE] = 1f
        h[XrShim.HAND_STICK_X] = -0.01f
        h[XrShim.HAND_STICK_Y] = 0.04f
        assertEquals("Left: tracked, grip up, stick 0.0 0.0", status(h)[0])
    }

    @Test
    fun plateLineShowsDistanceZoomAndHolder() {
        assertEquals("Plate: 0.50 m away, zoom 1.00", status(hands())[2])
        assertEquals("Plate: held by right, 0.50 m away, zoom 1.00", status(hands(), holder = 1)[2])
    }
}
