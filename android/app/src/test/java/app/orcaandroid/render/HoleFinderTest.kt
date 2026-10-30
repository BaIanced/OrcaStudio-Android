package app.orcaandroid.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HoleFinderTest {
    private val tris = ArrayList<FloatArray>()

    private fun tri(id: Int, vararg p: FloatArray) { tris += floatArrayOf(*p[0], *p[1], *p[2]); ids += id }
    private val ids = ArrayList<Int>()

    /** World point from (u, w, along axis) with u / w = the next two axes after [axis]. */
    private fun pt(axis: Int, u: Double, w: Double, a: Double) = FloatArray(3).also {
        it[axis] = a.toFloat(); it[(axis + 1) % 3] = u.toFloat(); it[(axis + 2) % 3] = w.toFloat()
    }

    private fun cylinder(id: Int, axis: Int, cu: Double, cw: Double, r: Double, a0: Double, a1: Double, segs: Int, hole: Boolean, arc: Double = 2 * Math.PI) {
        for (i in 0 until segs) {
            val t0 = arc * i / segs; val t1 = arc * (i + 1) / segs
            val p0 = pt(axis, cu + r * cos(t0), cw + r * sin(t0), a0); val p1 = pt(axis, cu + r * cos(t1), cw + r * sin(t1), a0)
            val q0 = pt(axis, cu + r * cos(t0), cw + r * sin(t0), a1); val q1 = pt(axis, cu + r * cos(t1), cw + r * sin(t1), a1)
            // Counter-clockwise from outside a peg; reversed = facing the axis (a hole).
            if (hole) { tri(id, p0, q1, p1); tri(id, p0, q0, q1) } else { tri(id, p0, p1, q1); tri(id, p0, q1, q0) }
        }
    }

    private fun box(id: Int, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) {
        val c = listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y0), doubleArrayOf(x1, y1), doubleArrayOf(x0, y1))
        for (i in 0..3) {
            val a = c[i]; val b = c[(i + 1) % 4]
            val p0 = floatArrayOf(a[0].toFloat(), a[1].toFloat(), z0.toFloat()); val p1 = floatArrayOf(b[0].toFloat(), b[1].toFloat(), z0.toFloat())
            val q0 = floatArrayOf(a[0].toFloat(), a[1].toFloat(), z1.toFloat()); val q1 = floatArrayOf(b[0].toFloat(), b[1].toFloat(), z1.toFloat())
            tri(id, p0, p1, q1); tri(id, p0, q1, q0)
        }
    }

    private fun mesh(): FloatData {
        val buf = FloatData.directFloats(tris.size * 24)
        for ((i, t) in tris.withIndex()) for (v in 0..2) {
            buf.put(t[v * 3]); buf.put(t[v * 3 + 1]); buf.put(t[v * 3 + 2])
            buf.put(0f); buf.put(0f); buf.put(1f); buf.put(ids[i].toFloat()); buf.put(0f)
        }
        buf.flip()
        return FloatData(buf, 8)
    }

    @Test
    fun findsVerticalAndSideHolesNotPegsOrBoxSides() {
        box(1, 0.0, 0.0, 0.0, 40.0, 30.0, 10.0)
        cylinder(1, 2, 10.0, 10.0, 2.5, 0.0, 10.0, 24, hole = true) // Z hole ⌀5
        cylinder(1, 2, 30.0, 20.0, 1.6, 0.0, 10.0, 12, hole = true) // Z hole ⌀3.2, coarse 12-gon
        cylinder(1, 0, 15.0, 5.0, 1.5, 0.0, 40.0, 32, hole = true) // X hole ⌀3 (u = Y, w = Z)
        cylinder(1, 2, 25.0, 8.0, 3.0, 10.0, 15.0, 24, hole = false) // peg on top: not a hole
        cylinder(1, 2, 5.0, 25.0, 2.0, 0.0, 10.0, 24, hole = true, arc = Math.PI / 2) // quarter round: too little wrap
        cylinder(2, 2, 60.0, 60.0, 4.0, 0.0, 5.0, 24, hole = true) // other object, not selected
        val r = HoleFinder.find(mesh(), setOf(1))!!
        val holes = r.holes.sortedBy { it.diameter }
        assertEquals(holes.toString(), 3, holes.size)
        assertEquals(3.0f, holes[0].diameter, 1e-3f); assertEquals(0, holes[0].axis)
        assertEquals(15.0f, holes[0].lo.y, 1e-3f); assertEquals(5.0f, holes[0].lo.z, 1e-3f)
        assertEquals(0f, holes[0].lo.x, 1e-3f); assertEquals(40f, holes[0].hi.x, 1e-3f)
        assertEquals(3.2f, holes[1].diameter, 1e-3f); assertEquals(2, holes[1].axis)
        assertEquals(5.0f, holes[2].diameter, 1e-3f)
        assertEquals(10f, holes[2].lo.x, 1e-3f); assertEquals(10f, holes[2].lo.y, 1e-3f)
        assertEquals(0f, holes[2].lo.z, 1e-3f); assertEquals(10f, holes[2].hi.z, 1e-3f)
        assertEquals(15f, r.boxHi.z, 1e-3f)
    }

    @Test
    fun mergesAHoleCutByACrossHole() {
        // Two coaxial pieces of one Z hole (the gap is where a cross hole cut it), each wrapping 300°.
        cylinder(1, 2, 0.0, 0.0, 2.0, 0.0, 4.0, 30, hole = true, arc = Math.PI * 5 / 3)
        cylinder(1, 2, 0.0, 0.0, 2.0, 6.0, 10.0, 30, hole = true, arc = Math.PI * 5 / 3)
        val holes = HoleFinder.find(mesh(), setOf(1))!!.holes
        assertEquals(holes.toString(), 1, holes.size)
        assertEquals(0f, holes[0].lo.z, 1e-3f); assertEquals(10f, holes[0].hi.z, 1e-3f)
    }

    @Test
    fun nothingSelectedFindsNothing() {
        cylinder(1, 2, 0.0, 0.0, 2.0, 0.0, 4.0, 24, hole = true)
        assertNull(HoleFinder.find(mesh(), setOf(7)))
        assertTrue(HoleFinder.find(mesh(), setOf(1))!!.holes.isNotEmpty())
    }
}
