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

    /** A pose at [p] whose -Z points along [d] (any length), without roll: yaw about +Y then pitch about +X. */
    private fun aim(p: FloatArray, dx: Float, dy: Float, dz: Float): FloatArray {
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val pitch = kotlin.math.asin(dy / len)
        val yaw = kotlin.math.atan2(-dx, -dz)
        val sa = sin(yaw / 2)
        val ca = cos(yaw / 2)
        val sb = sin(pitch / 2)
        val cb = cos(pitch / 2)
        // qy * qx
        return floatArrayOf(p[0], p[1], p[2], ca * sb, cb * sa, -sa * sb, ca * cb)
    }

    private fun ray(pose: FloatArray, cx: Float = 0f, cy: Float = 0f) = FloatArray(6).also { VrMath.aimToPlate(it, pose, 0, cx, cy) }

    @Test fun identityPoseAimsAlongPlatePlusY() {
        val r = ray(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f))
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), r.copyOfRange(3, 6), eps)
    }

    @Test fun aimDirectionMatchesThePlateTransform() {
        // The design's derived direction, checked against the linear part of plateToLocal rather than trusted.
        val m = plate(30f, -50f)
        for ((dx, dy, dz) in listOf(Triple(0.3f, -0.5f, -0.8f), Triple(-1f, 0.2f, -0.1f), Triple(0.1f, -0.9f, 0.4f))) {
            val r = ray(aim(floatArrayOf(0.2f, 0.1f, -0.3f), dx, dy, dz), 30f, -50f)
            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            // Plate dir through the plate-to-local rotation must give back the local aim direction.
            val local = floatArrayOf(
                m[0] * r[3] + m[4] * r[4] + m[8] * r[5],
                m[1] * r[3] + m[5] * r[4] + m[9] * r[5],
                m[2] * r[3] + m[6] * r[4] + m[10] * r[5],
            ).map { it / MM_PER_UNIT }.toFloatArray()
            assertArrayEquals(floatArrayOf(dx / len, dy / len, dz / len), local, eps)
            assertEquals(1f, kotlin.math.sqrt(r[3] * r[3] + r[4] * r[4] + r[5] * r[5]), eps)
        }
    }

    @Test fun rayFromHeadToPlateCentreHitsIt() {
        val out = FloatArray(3)
        val pose = aim(floatArrayOf(0f, 0f, 0f), 0f, VrMath.TABLE_Y, VrMath.TABLE_AHEAD)
        assertEquals(true, VrMath.rayPlateZ0(out, ray(pose, 128f, 90f)))
        assertEquals(128f, out[0], 1e-2f)
        assertEquals(90f, out[1], 1e-2f)
    }

    @Test fun rayAtAnOffsetPlatePointHitsIt() {
        // Local point of plate (170, 40, 0) with the plate centred at (128, 90).
        val local = transform(plate(128f, 90f), 170f, 40f, 0f)
        val pose = aim(floatArrayOf(0.1f, 0.2f, 0f), local[0] - 0.1f, local[1] - 0.2f, local[2])
        val out = FloatArray(3)
        assertEquals(true, VrMath.rayPlateZ0(out, ray(pose, 128f, 90f)))
        assertEquals(170f, out[0], 1e-1f)
        assertEquals(40f, out[1], 1e-1f)
    }

    @Test fun rayPlateMissesWhenParallelOrAimedUp() {
        val out = FloatArray(3)
        assertEquals(false, VrMath.rayPlateZ0(out, ray(aim(floatArrayOf(0f, 0f, 0f), 0f, 0f, -1f))))
        assertEquals(false, VrMath.rayPlateZ0(out, ray(aim(floatArrayOf(0f, 0f, 0f), 0f, 1f, -1f))))
    }

    @Test fun plateAtPointFindsThePlateAndTheGap() {
        val origins = floatArrayOf(0f, 0f, 300f, 0f)
        assertEquals(0, VrMath.plateAtPoint(10f, 10f, origins, 2, 0f, 0f, 256f, 256f))
        assertEquals(1, VrMath.plateAtPoint(310f, 256f, origins, 2, 0f, 0f, 256f, 256f))
        assertEquals(-1, VrMath.plateAtPoint(280f, 100f, origins, 2, 0f, 0f, 256f, 256f))
        assertEquals(-1, VrMath.plateAtPoint(10f, -1f, origins, 2, 0f, 0f, 256f, 256f))
        assertEquals(-1, VrMath.plateAtPoint(310f, 10f, origins, 1, 0f, 0f, 256f, 256f))
    }

    /** A pose at [p] aimed at panel pixel ([px], [py]). */
    private fun aimAtPanel(p: FloatArray, px: Float, py: Float): FloatArray {
        val yaw = Math.toRadians(33.0).toFloat()
        val u = (px / VrMath.PANEL_PX_W - 0.5f) * VrMath.PANEL_W
        val v = (0.5f - py / VrMath.PANEL_PX_H) * VrMath.PANEL_H
        val tx = VrMath.PANEL_X + u * cos(yaw)
        val ty = VrMath.PANEL_Y + v
        val tz = VrMath.PANEL_Z - u * sin(yaw)
        return aim(p, tx - p[0], ty - p[1], tz - p[2])
    }

    @Test fun panelCentreAndCornersMapToPixels() {
        val out = FloatArray(3)
        val head = floatArrayOf(0f, 0f, 0f)
        assertEquals(true, VrMath.rayQuad(out, aimAtPanel(head, 640f, 400f), 0))
        assertEquals(640f, out[0], 0.1f)
        assertEquals(400f, out[1], 0.1f)
        assertEquals(true, VrMath.rayQuad(out, aimAtPanel(head, 10f, 10f), 0))
        assertEquals(10f, out[0], 0.1f)
        assertEquals(10f, out[1], 0.1f)
        assertEquals(true, VrMath.rayQuad(out, aimAtPanel(head, 1270f, 790f), 0))
        assertEquals(1270f, out[0], 0.1f)
        assertEquals(790f, out[1], 0.1f)
    }

    @Test fun panelOriginIsTopLeft() {
        val out = FloatArray(3)
        val head = floatArrayOf(0f, 0f, 0f)
        VrMath.rayQuad(out, aimAtPanel(head, 100f, 100f), 0)
        val left = out[0]
        val top = out[1]
        VrMath.rayQuad(out, aimAtPanel(head, 900f, 700f), 0)
        assertEquals(true, out[0] > left && out[1] > top)
    }

    @Test fun rayQuadOffRectIsNotAHitButStillReportsPixels() {
        val out = FloatArray(3)
        assertEquals(false, VrMath.rayQuad(out, aimAtPanel(floatArrayOf(0f, 0f, 0f), 1400f, 400f), 0))
        assertEquals(1400f, out[0], 0.1f)
    }

    @Test fun rayQuadMissesBehindAndBackFace() {
        val out = floatArrayOf(-1f, -1f, -1f)
        // Aimed away from the panel.
        assertEquals(false, VrMath.rayQuad(out, aim(floatArrayOf(0f, 0f, 0f), 0.55f, 0f, 0.85f), 0))
        // From behind the panel, through it.
        val behind = floatArrayOf(VrMath.PANEL_X - 0.5f, VrMath.PANEL_Y, VrMath.PANEL_Z - 0.8f)
        assertEquals(false, VrMath.rayQuad(out, aim(behind, 0.5f, 0f, 0.8f), 0))
        assertArrayEquals(floatArrayOf(-1f, -1f, -1f), out, 0f)
    }

    @Test fun panelFacesTheOrigin() {
        // Aiming from the origin at the panel centre is a front-face hit near 1 m.
        val out = FloatArray(3)
        assertEquals(true, VrMath.rayQuad(out, aim(floatArrayOf(0f, 0f, 0f), VrMath.PANEL_X, VrMath.PANEL_Y, VrMath.PANEL_Z), 0))
        assertEquals(1f, out[2], 0.05f)
    }

    private companion object {
        const val MM_PER_UNIT = 0.001f
    }
}
