package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class MeasureSteerTest {
    private val iso = Vec3(0.5f, -0.5f, 0.7071f).let { it * (1f / it.length()) }

    @Test fun bedAndNothingAreNotFramed() {
        assertNull(MeasureSteer.focusOf(Vec3.ZERO, null, FloatArray(0), null, null, iso))
        assertNull(MeasureSteer.focusOf(Vec3.ZERO, "bed", FloatArray(0), null, null, iso))
    }

    @Test fun holeSizeIsItsDiameterSeenAlongItsAxis() {
        val f = MeasureSteer.focusOf(Vec3.ZERO, "ring", floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), 9.5f, Vec3(0f, 0f, -1f), iso)!!
        assertEquals(9.5f, f.size, 1e-4f)
        // The axis flipped towards the eye.
        assertEquals(1f, f.look!!.z, 1e-4f)
    }

    @Test fun edgeIsSeenSquareToIt() {
        // A vertical edge 20 mm long.
        val f = MeasureSteer.focusOf(Vec3.ZERO, "edge", floatArrayOf(0f, 0f, 0f, 0f, 0f, 20f), null, null, iso)!!
        assertEquals(20f, f.size, 1e-4f)
        assertEquals(0f, f.look!!.z, 1e-4f)
        assertEquals(1f, f.look!!.length(), 1e-4f)
    }

    @Test fun faceTurnsPartWayToSquareOn() {
        val f = MeasureSteer.focusOf(Vec3.ZERO, "face", floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f), null, Vec3(0f, 0f, 1f), iso)!!
        val look = f.look!!
        // Between the view now and the face's normal.
        assert(look.z > iso.z && look.z < 1f)
    }

    @Test fun swingIsCappedAndStaysAboveTheBed() {
        // Asking to look from straight below, from an iso view: capped at the max swing, pitch kept above MIN_PITCH.
        val (yaw, pitch) = MeasureSteer.aim(Vec3(0f, 0f, -1f), -60f, 40f)
        assert(pitch >= MeasureSteer.MIN_PITCH - 1e-3f)
        assert(abs(MeasureSteer.wrap(yaw + 60f)) < 1e-2f)
        // Within the cap: exact.
        val (y2, p2) = MeasureSteer.aim(run { val c = kotlin.math.cos(Math.toRadians(60.0)).toFloat(); Vec3(c * 0.5f, -c * 0.866f, kotlin.math.sin(Math.toRadians(60.0)).toFloat()) }, -60f, 40f)
        assertEquals(60f, p2, 0.1f)
        assertEquals(-60f, y2, 0.1f)
    }
}
