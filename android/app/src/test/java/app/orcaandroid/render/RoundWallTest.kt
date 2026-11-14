package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import app.orcaandroid.core.roundWallMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The Edit mesh Diameter field: [HoleFinder.roundWall] on the selected vertices (x y z each). */
class RoundWallTest {
    /** [segs] vertices on two rings (heights [z0], [z1]) round the Z axis through ([cx], [cy]), radius from [r0] (bottom) to [r1] (top). */
    private fun ring(segs: Int, cx: Double, cy: Double, r0: Double, r1: Double, z0: Double, z1: Double): FloatArray {
        val out = ArrayList<Float>()
        for ((r, z) in listOf(r0 to z0, r1 to z1)) for (i in 0 until segs) {
            val t = 2 * Math.PI * i / segs
            out += listOf((cx + r * cos(t)).toFloat(), (cy + r * sin(t)).toFloat(), z.toFloat())
        }
        return out.toFloatArray()
    }

    @Test
    fun cylinderWallIsDetectedWithItsDiameter() {
        val w = HoleFinder.roundWall(ring(24, 10.0, 5.0, 1.5, 1.5, 0.0, 4.0))
        assertNotNull(w)
        assertEquals(2, w!!.axis)
        assertEquals(3.0, w.diameter.toDouble(), 0.01)
        assertEquals(10.0, w.centre.x.toDouble(), 0.01)
        assertEquals(5.0, w.centre.y.toDouble(), 0.01)
        // 3.0 -> 3.4: about the axis (here the centroid is on it) x and y by 3.4 / 3.0, z kept, no translation.
        val m = roundWallMatrix(w, Vec3(10f, 5f, 2f), 3.4f)
        assertEquals(3.4 / 3.0, m[0], 1e-4); assertEquals(3.4 / 3.0, m[5], 1e-4); assertEquals(1.0, m[10], 1e-9)
        assertEquals(0.0, m[3], 1e-4); assertEquals(0.0, m[7], 1e-4); assertEquals(0.0, m[11], 1e-9)
    }

    @Test
    fun boxFaceIsNotDetected() {
        // A 5 x 5 grid of vertices on a box's top face and on its side face: no extent along the axis, or collinear across it.
        val top = ArrayList<Float>(); val side = ArrayList<Float>()
        for (i in 0..4) for (j in 0..4) {
            top += listOf(i * 2.5f, j * 2.5f, 10f)
            side += listOf(10f, i * 2.5f, j * 2.5f)
        }
        assertNull(HoleFinder.roundWall(top.toFloatArray()))
        assertNull(HoleFinder.roundWall(side.toFloatArray()))
        // The four sides of a box (eight corners) lie on a circle across Z, but are not round.
        val corners = ArrayList<Float>()
        for (x in listOf(0f, 10f)) for (y in listOf(0f, 10f)) for (z in listOf(0f, 10f)) corners += listOf(x, y, z)
        assertNull(HoleFinder.roundWall(corners.toFloatArray()))
    }

    @Test
    fun coneWallIsNotDetected() {
        // Radius 1.5 at the bottom, 1.0 at the top over 4 mm.
        assertNull(HoleFinder.roundWall(ring(24, 0.0, 0.0, 1.5, 1.0, 0.0, 4.0)))
    }
}
