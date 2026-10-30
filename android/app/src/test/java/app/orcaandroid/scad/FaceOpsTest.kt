package app.orcaandroid.scad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Push / pull tier 2: flat regions of a mesh, face ops in OpenSCAD, and a MESH shape's box after a push. */
class FaceOpsTest {
    private fun p(x: Double, y: Double) = P2(x, y)

    /** Fan cap of convex-from-corner-0 [pts] (CCW from above) at [z], facing up or down. */
    private fun cap(pts: List<P2>, z: Double, up: Boolean): List<D3> = (1 until pts.size - 1).flatMap { i ->
        val a = D3(pts[0].x, pts[0].y, z)
        val b = D3(pts[i].x, pts[i].y, z)
        val c = D3(pts[i + 1].x, pts[i + 1].y, z)
        if (up) listOf(a, b, c) else listOf(a, c, b)
    }

    /** Side walls of [loop] from [z0] to [z1], facing right of its direction (outwards for a CCW outline). */
    private fun walls(loop: List<P2>, z0: Double, z1: Double): List<D3> = loop.indices.flatMap { i ->
        val p = loop[i]
        val q = loop[(i + 1) % loop.size]
        val a = D3(p.x, p.y, z0); val b = D3(q.x, q.y, z0); val c = D3(q.x, q.y, z1); val d = D3(p.x, p.y, z1)
        listOf(a, b, c, a, c, d)
    }

    private fun stl(corners: List<D3>): File = File.createTempFile("faceops", ".stl").apply {
        deleteOnExit()
        writeText(buildString {
            appendLine("solid t")
            corners.chunked(3).forEach { t ->
                appendLine("facet normal 0 0 0\nouter loop")
                t.forEach { appendLine("vertex ${it.x} ${it.y} ${it.z}") }
                appendLine("endloop\nendfacet")
            }
            appendLine("endsolid t")
        })
    }

    /** An L bracket 40 x 30 x 10: a 3 mm tab along x (its top split at x = 20), a 3 mm leg up y. */
    private val bracket by lazy {
        val l = listOf(p(0.0, 0.0), p(40.0, 0.0), p(40.0, 3.0), p(20.0, 3.0), p(3.0, 3.0), p(3.0, 30.0), p(0.0, 30.0))
        Stl.triangles(stl(cap(l, 10.0, true) + cap(l, 0.0, false) + walls(l, 0.0, 10.0)))!!
    }

    /** A 40 x 20 x 3 plate with a 10 x 10 square hole through it. */
    private val ring by lazy {
        val o = listOf(p(0.0, 0.0), p(40.0, 0.0), p(40.0, 20.0), p(0.0, 20.0))
        val h = listOf(p(15.0, 5.0), p(25.0, 5.0), p(25.0, 15.0), p(15.0, 15.0))
        val top = (0..3).flatMap { i ->
            val j = (i + 1) % 4
            listOf(D3(o[i].x, o[i].y, 3.0), D3(o[j].x, o[j].y, 3.0), D3(h[j].x, h[j].y, 3.0), D3(o[i].x, o[i].y, 3.0), D3(h[j].x, h[j].y, 3.0), D3(h[i].x, h[i].y, 3.0))
        }
        val bottom = top.chunked(3).flatMap { (a, b, c) -> listOf(D3(a.x, a.y, 0.0), D3(c.x, c.y, 0.0), D3(b.x, b.y, 0.0)) }
        Stl.triangles(stl(top + bottom + walls(o, 0.0, 3.0) + walls(h.reversed(), 0.0, 3.0)))!!
    }

    private fun near(a: D3, b: D3, tol: Double = 1e-6) =
        assertTrue("$a vs $b", abs(a.x - b.x) < tol && abs(a.y - b.y) < tol && abs(a.z - b.z) < tol)

    @Test
    fun flatRegions() {
        val end = FaceOps.region(bracket, D3(40.0, 1.5, 5.0))!!
        assertEquals(1, end.outline.size)
        assertEquals(4, end.outline[0].size)
        near(D3(1.0, 0.0, 0.0), end.normal)
        assertEquals(30.0, abs(FaceOps.area(end.outline[0])), 1e-4)
        // The tab's top is two quads (split at x = 20): one region, straight-run corners dropped.
        val top = FaceOps.region(bracket, D3(30.0, 3.0, 5.0))!!
        assertEquals(4, top.outline[0].size)
        near(D3(0.0, 1.0, 0.0), top.normal)
        assertEquals(37.0 * 10.0, abs(FaceOps.area(top.outline[0])), 1e-3)
        // A face with a hole: outer loop first, then the hole.
        val plate = FaceOps.region(ring, D3(5.0, 5.0, 3.0))!!
        assertEquals(2, plate.outline.size)
        assertEquals(800.0, abs(FaceOps.area(plate.outline[0])), 1e-3)
        assertEquals(100.0, abs(FaceOps.area(plate.outline[1])), 1e-3)
        near(D3(0.0, 0.0, 1.0), plate.normal)
        // Every outline corner lies on the face's plane in file coordinates.
        plate.outline.flatten().forEach { assertEquals(3.0, plate.at(it).z, 1e-6) }
        assertNull(FaceOps.region(ring, D3(20.0, 10.0, 3.0)))
        assertNull(FaceOps.region(bracket, D3(20.0, 20.0, 5.0)))
    }

    @Test
    fun emission() {
        val push = FaceOps.region(bracket, D3(40.0, 1.5, 5.0))!!.copy(d = 10.0)
        val pull = FaceOps.region(ring, D3(5.0, 5.0, 3.0))!!.copy(d = -1.0)
        val base = "import(\"m.stl\", convexity = 10);"
        val a = FaceOps.scad(base, listOf(push))
        assertTrue(a, a.startsWith("union() { import("))
        assertTrue(a, "multmatrix([[" in a && "translate([0, 0, -0.01]) linear_extrude(10.01) polygon(" in a)
        val b = FaceOps.scad(base, listOf(push, pull))
        assertTrue(b, b.startsWith("difference() { union() { import(") && "translate([0, 0, -1]) linear_extrude(1.01) offset(delta = 0.01) polygon(" in b)
        assertTrue(b, "[0, 1, 2, 3], [4, 5, 6, 7]" in b)
        assertEquals(base, FaceOps.scad(base, listOf(push.copy(d = 0.0))))
        val d = ShapeDesign(listOf(Shape(1, Shape.Kind.MESH, "M", D3(40.0, 30.0, 10.0), file = "/m.stl",
            extra = Shape.MESH_BOUNDS.zip(listOf(0.0, 0.0, 0.0, 40.0, 30.0, 10.0)).toMap())), nextId = 2).withFaces(1, listOf(push))
        assertTrue(d.toScad(), "union() { import(\"/m.stl\", convexity = 10); multmatrix(" in d.toScad())
        val back = ShapeDesign.decode(d.encode())!!
        assertEquals(d, back)
    }

    @Test
    fun capPicksTheOpToEdit() {
        val push = FaceOps.region(bracket, D3(40.0, 1.5, 5.0))!!.copy(d = 10.0)
        val pull = FaceOps.region(ring, D3(5.0, 5.0, 3.0))!!.copy(d = -1.0)
        val ops = listOf(push, pull)
        assertEquals(0, FaceOps.capAt(ops, D3(50.0, 1.5, 5.0)))
        assertEquals(-1, FaceOps.capAt(ops, D3(40.0, 1.5, 5.0)))
        assertEquals(1, FaceOps.capAt(ops, D3(5.0, 5.0, 2.0)))
        assertEquals(-1, FaceOps.capAt(ops, D3(20.0, 10.0, 2.0)))
    }

    /** A push grows the box, and every file point the push didn't touch keeps its design position (turned, mirrored, scaled). */
    @Test
    fun untouchedGeometryStaysPut() {
        val file = listOf(0.0, 0.0, 0.0, 40.0, 30.0, 10.0)
        val untouched = listOf(D3(0.0, 0.0, 0.0), D3(0.0, 30.0, 10.0), D3(3.0, 30.0, 0.0), D3(40.0, 3.0, 10.0))
        for ((rot, mirror) in listOf(D3(10.0, 20.0, 30.0) to D3(1.0, 0.0, 0.0), D3(0.0, 90.0, 45.0) to D3(0.0, 0.0, 1.0), D3() to D3())) {
            val s = Shape(1, Shape.Kind.MESH, "M", D3(20.0, 60.0, 10.0), D3(5.0, 6.0, 7.0), rot, mirror = mirror, file = "/m.stl",
                extra = Shape.MESH_BOUNDS.zip(file).toMap())
            val d0 = ShapeDesign(listOf(s), nextId = 2)
            val end = FaceOps.region(bracket, D3(40.0, 1.5, 5.0))!!.copy(d = 10.0)
            val cap = FaceOps.region(bracket, D3(20.0, 1.5, 10.0))!!.copy(d = 4.0)
            val floor = FaceOps.region(bracket, D3(20.0, 1.5, 0.0))!!.copy(d = -2.0)
            val d1 = d0.withFaces(1, listOf(end, cap, floor))
            val s1 = d1.find(1)!!
            assertEquals(listOf(0.0, 0.0, 0.0, 50.0, 30.0, 14.0), Shape.MESH_BOUNDS.map { s1.x(it, 0.0) })
            assertEquals(file, Shape.FILE_BOUNDS.map { s1.x(it, -1.0) })
            near(D3(25.0, 60.0, 14.0), s1.size)
            for (q in untouched) near(d0.meshToParent(s, q), d1.meshToParent(s1, q))
            // The pushed end lands 10 file mm further along the end face's normal.
            val e0 = d0.meshToParent(s, D3(40.0, 1.5, 5.0))
            val e1 = d1.meshToParent(s1, D3(50.0, 1.5, 5.0))
            assertEquals(5.0, kotlin.math.sqrt(FaceOp.dot(e1 - e0, e1 - e0)), 1e-6)
            for (q in untouched) near(q, d1.parentToMesh(s1, d1.meshToParent(s1, q)))
            // Taking the ops off restores the file's box and place.
            val s2 = d1.withFaces(1, emptyList()).find(1)!!
            assertEquals(file, Shape.MESH_BOUNDS.map { s2.x(it, 0.0) })
            assertFalse(Shape.FILE_BOUNDS.any { it in s2.extra })
            near(s.pos, s2.pos)
            near(s.size, s2.size)
        }
    }

    /** A tap's design point maps to the model file and back, inside a turned, flipped, repeated group, per copy. */
    @Test
    fun designAndFilePoints() {
        val file = listOf(0.0, 0.0, 0.0, 40.0, 30.0, 10.0)
        val m = Shape(2, Shape.Kind.MESH, "M", D3(20.0, 60.0, 10.0), D3(5.0, 6.0, 7.0), D3(0.0, 30.0, 10.0), mirror = D3(1.0, 0.0, 0.0),
            file = "/m.stl", extra = Shape.MESH_BOUNDS.zip(file).toMap(), repeat = Repeat(count = 2, step = D3(0.0, 0.0, 15.0)))
        val g = Shape(5, Shape.Kind.GROUP, "G", D3(), D3(3.0, 1.0, 0.0), D3(0.0, 0.0, 20.0), false, D3(0.0, 1.0, 0.0),
            children = listOf(m), repeat = Repeat(count = 3, step = D3(50.0, 0.0, 0.0)))
        val d = ShapeDesign(listOf(g), nextId = 6)
        for (copy in listOf(mapOf(5 to 0, 2 to 0), mapOf(5 to 2, 2 to 1))) for (q in listOf(D3(0.0, 0.0, 0.0), D3(40.0, 1.5, 5.0), D3(12.0, 30.0, 10.0))) {
            val p = d.meshToDesign(2, q, copy)!!
            near(q, d.designToMesh(2, p, copy)!!, 1e-9)
        }
        // A plain top-level MESH: file bounds' bottom centre at pos, the second copy one step on.
        val top = ShapeDesign(listOf(m.copy(rot = D3(), mirror = D3())), nextId = 3)
        near(D3(5.0, 6.0, 7.0), top.meshToDesign(2, D3(20.0, 15.0, 0.0))!!)
        near(D3(5.0, 6.0, 22.0), top.meshToDesign(2, D3(20.0, 15.0, 0.0), mapOf(2 to 1))!!)
    }

    @Test
    fun binaryStl() {
        val tris = bracket
        val f = File.createTempFile("faceops", ".stl").apply { deleteOnExit() }
        val buf = java.nio.ByteBuffer.allocate(84 + tris.size / 9 * 50).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.position(80); buf.putInt(tris.size / 9)
        for (t in 0 until tris.size / 9) { repeat(3) { buf.putFloat(0f) }; for (k in 0 until 9) buf.putFloat(tris[t * 9 + k]); buf.putShort(0) }
        f.writeBytes(buf.array())
        val read = Stl.triangles(f)
        assertNotNull(read)
        assertTrue(tris.contentEquals(read!!))
    }
}
