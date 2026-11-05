package app.orcaandroid.vr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VrMathTest {
    private val eps = 1e-5f

    private fun transform(m: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        FloatArray(4) { m[it] * x + m[it + 4] * y + m[it + 8] * z + m[it + 12] }

    private fun view(vararg pose: Float) = FloatArray(16).also { VrMath.viewFromPose(it, pose, 0) }

    @Test fun identityPoseGivesIdentityView() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertArrayEquals(identity, view(0f, 0f, 0f, 0f, 0f, 0f, 1f), eps)
    }

    @Test fun translatedPoseMovesTheWorldTheOtherWay() {
        val eyeSpace = transform(view(1f, 2f, 3f, 0f, 0f, 0f, 1f), 1f, 2f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, -3f, 1f), eyeSpace, eps)
    }

    @Test fun headTurnedLeftSeesWhatIsOnItsLeftAhead() {
        // A +90 degree turn about +Y faces -X, so a point at -X lands straight ahead (-Z in eye space).
        val half = (PI / 4).toFloat()
        val eyeSpace = transform(view(0f, 0f, 0f, 0f, sin(half), 0f, cos(half)), -2f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, -2f, 1f), eyeSpace, eps)
    }

    @Test fun viewInvertsThePoseIncludingTheOffset() {
        // The pose's own position must map to the eye-space origin under any rotation.
        val half = 0.4f
        val v = view(0.3f, 1.6f, -0.2f, sin(half), 0f, 0f, cos(half))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), transform(v, 0.3f, 1.6f, -0.2f), eps)
    }

    private fun ndc(clip: FloatArray) = floatArrayOf(clip[0] / clip[3], clip[1] / clip[3], clip[2] / clip[3])

    @Test fun asymmetricFovMapsItsEdgesToTheClipEdges() {
        val left = -0.9f
        val right = 0.7f
        val up = 0.8f
        val down = -1.0f
        val p = FloatArray(16)
        VrMath.projectionFromFov(p, left, right, up, down, 0.1f, 100f)
        val d = 5f
        assertEquals(-1f, ndc(transform(p, kotlin.math.tan(left) * d, 0f, -d))[0], eps)
        assertEquals(1f, ndc(transform(p, kotlin.math.tan(right) * d, 0f, -d))[0], eps)
        assertEquals(1f, ndc(transform(p, 0f, kotlin.math.tan(up) * d, -d))[1], eps)
        assertEquals(-1f, ndc(transform(p, 0f, kotlin.math.tan(down) * d, -d))[1], eps)
    }

    @Test fun projectionMapsNearAndFarToTheDepthRange() {
        val p = FloatArray(16)
        VrMath.projectionFromFov(p, -1f, 1f, 1f, -1f, 0.1f, 100f)
        assertEquals(-1f, ndc(transform(p, 0f, 0f, -0.1f))[2], 1e-3f)
        assertEquals(1f, ndc(transform(p, 0f, 0f, -100f))[2], 1e-3f)
    }

    @Test fun multiplyAppliesTheRightMatrixFirst() {
        val translate = view(-1f, 0f, 0f, 0f, 0f, 0f, 1f) // Moves points by +1 in x.
        val p = FloatArray(16)
        VrMath.projectionFromFov(p, -1f, 1f, 1f, -1f, 0.1f, 100f)
        val out = FloatArray(16)
        VrMath.multiply(out, p, translate)
        assertArrayEquals(transform(p, 2f, 0f, -3f), transform(out, 1f, 0f, -3f), eps)
    }

    private fun plate(cx: Float, cy: Float) = FloatArray(16).also { VrMath.plateToLocal(it, cx, cy) }

    @Test fun plateCentreSitsAtTheTable() {
        assertArrayEquals(floatArrayOf(0f, VrMath.TABLE_Y, VrMath.TABLE_AHEAD, 1f), transform(plate(128f, 90f), 128f, 90f, 0f), eps)
    }

    @Test fun plateAxesMapToLocalAtOneToOne() {
        val m = plate(0f, 0f)
        // 100 mm along +X goes right, along +Y away from the viewer (-Z), along +Z up, each 0.1 m.
        assertArrayEquals(floatArrayOf(0.1f, VrMath.TABLE_Y, VrMath.TABLE_AHEAD, 1f), transform(m, 100f, 0f, 0f), eps)
        assertArrayEquals(floatArrayOf(0f, VrMath.TABLE_Y, VrMath.TABLE_AHEAD - 0.1f, 1f), transform(m, 0f, 100f, 0f), eps)
        assertArrayEquals(floatArrayOf(0f, VrMath.TABLE_Y + 0.1f, VrMath.TABLE_AHEAD, 1f), transform(m, 0f, 0f, 100f), eps)
    }

    @Test fun plateTransformKeepsWinding() {
        val m = plate(10f, 20f)
        val det = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
        assertEquals(1e-9f, det, 1e-12f)
    }

    @Test fun localToPlateInvertsPlateToLocal() {
        val local = transform(plate(128f, 90f), 40f, -15f, 220f)
        val back = FloatArray(3)
        VrMath.localToPlate(back, local, 0, 128f, 90f)
        assertArrayEquals(floatArrayOf(40f, -15f, 220f), back, 1e-2f)
    }
}
