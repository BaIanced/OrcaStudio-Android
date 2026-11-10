package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/**
 * JVM tests for [FbxReader]. Sample files are produced by a tiny binary FBX
 * writer built inside this test (per the task rules): correct headers, node
 * records with real absolute end offsets, 32-bit (v7300) and 64-bit (v7700)
 * layouts, and one zlib-compressed array.
 */
class FbxReaderTest {

    // ---------- tiny binary FBX writer ----------

    private data class N(val name: String, val props: List<ByteArray>, val children: List<N>)

    private class W {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u32(v: Long) {
            for (i in 0 until 4) out.write((v shr (8 * i)).toInt() and 0xFF)
        }
        fun u64(v: Long) {
            for (i in 0 until 8) out.write((v shr (8 * i)).toInt() and 0xFF)
        }
        fun bytes(b: ByteArray) = out.write(b)
        fun done(): ByteArray = out.toByteArray()
    }

    private fun pL(v: Long) = W().apply { u8('L'.code); u64(v) }.done()
    private fun pI(v: Int) = W().apply { u8('I'.code); u32(v.toLong()) }.done()
    private fun pD(v: Double) = W().apply {
        u8('D'.code)
        val bits = v.toRawBits()
        for (i in 0 until 8) out.write((bits shr (8 * i)).toInt() and 0xFF)
    }.done()
    private fun pS(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return W().apply { u8('S'.code); u32(b.size.toLong()); bytes(b) }.done()
    }
    private fun pArrD(values: DoubleArray, compress: Boolean = false, declared: Int = values.size): ByteArray {
        val raw = W().apply {
            for (v in values) {
                val bits = v.toRawBits()
                for (i in 0 until 8) out.write((bits shr (8 * i)).toInt() and 0xFF)
            }
        }.done()
        return arrayProp('d', declared, raw, compress)
    }
    private fun pArrI(values: IntArray): ByteArray {
        val raw = W().apply { for (v in values) u32(v.toLong()) }.done()
        return arrayProp('i', values.size, raw, false)
    }
    private fun arrayProp(type: Char, declared: Int, raw: ByteArray, compress: Boolean): ByteArray {
        val stored = if (compress) deflate(raw) else raw
        return W().apply {
            u8(type.code)
            u32(declared.toLong())
            u32(if (compress) 1 else 0)
            u32(stored.size.toLong())
            bytes(stored)
        }.done()
    }
    private fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(raw)
        d.finish()
        val buf = ByteArray(raw.size + 64)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    private fun headerSize(wide: Boolean) = if (wide) 25 else 13

    private fun buildNode(start: Int, spec: N, wide: Boolean): ByteArray {
        val nameBytes = spec.name.toByteArray(Charsets.UTF_8)
        val propBytes = ByteArrayOutputStream().apply {
            for (p in spec.props) write(p)
        }.toByteArray()
        // headerSize already includes the 1-byte name length.
        var childStart = start + headerSize(wide) + nameBytes.size + propBytes.size
        val childBlobs = spec.children.map { ch ->
            val b = buildNode(childStart, ch, wide)
            childStart += b.size
            b
        }
        val nullSize = if (spec.children.isNotEmpty()) headerSize(wide) else 0
        val total = headerSize(wide) + nameBytes.size + propBytes.size +
            childBlobs.sumOf { it.size } + nullSize
        val w = W()
        val endOffset = (start + total).toLong()
        if (wide) {
            w.u64(endOffset); w.u64(spec.props.size.toLong()); w.u64(propBytes.size.toLong())
        } else {
            w.u32(endOffset); w.u32(spec.props.size.toLong()); w.u32(propBytes.size.toLong())
        }
        w.u8(nameBytes.size)
        w.bytes(nameBytes)
        w.bytes(propBytes)
        for (b in childBlobs) w.bytes(b)
        if (nullSize > 0) w.bytes(ByteArray(nullSize))
        return w.done()
    }

    private fun fbxFile(version: Int, top: List<N>): ByteArray {
        val wide = version >= 7500
        val w = W()
        w.bytes("Kaydara FBX Binary  ".toByteArray(Charsets.US_ASCII))
        w.u8(0); w.u8(0x1A); w.u8(0)
        w.u32(version.toLong())
        var at = 27
        for (node in top) {
            val b = buildNode(at, node, wide)
            w.bytes(b)
            at += b.size
        }
        w.bytes(ByteArray(headerSize(wide))) // root NULL record
        return w.done()
    }

    // ---------- scene assembly helpers ----------

    private fun p70(vararg entries: Pair<String, List<ByteArray>>): N =
        N("Properties70", emptyList(), entries.map { (name, values) ->
            N("P", listOf(pS(name), pS(""), pS(""), pS("A")) + values, emptyList())
        })

    private fun obj(name: String, id: Long, objName: String, tag: String, children: List<N>): N =
        N(name, listOf(pL(id), pS("$objName\u0000\u0001$name"), pS(tag)), children)

    private fun geom(id: Long, verts: DoubleArray, pvi: IntArray, lem: N? = null,
                     compressVerts: Boolean = false, declaredVerts: Int = verts.size): N {
        val kids = ArrayList<N>()
        kids.add(N("Vertices", listOf(pArrD(verts, compressVerts, declaredVerts)), emptyList()))
        kids.add(N("PolygonVertexIndex", listOf(pArrI(pvi)), emptyList()))
        if (lem != null) kids.add(lem)
        return obj("Geometry", id, "Mesh$id", "Mesh", kids)
    }

    private fun lem(mapping: String, indices: IntArray, nestedInLayer: Boolean = false): N {
        val inner = N("LayerElementMaterial", emptyList(), listOf(
            N("MappingInformationType", listOf(pS(mapping)), emptyList()),
            N("ReferenceInformationType", listOf(pS("IndexToDirect")), emptyList()),
            N("Materials", listOf(pArrI(indices)), emptyList()),
        ))
        return if (nestedInLayer) N("Layer", listOf(pI(0)), listOf(inner)) else inner
    }

    private fun model(id: Long, vararg props: Pair<String, List<ByteArray>>): N =
        obj("Model", id, "Model$id", "", listOf(p70(*props)))

    private fun material(id: Long, r: Double, g: Double, b: Double, fallback: Boolean = false): N =
        obj("Material", id, "Mat$id", "", listOf(
            p70((if (fallback) "Diffuse" else "DiffuseColor") to listOf(pD(r), pD(g), pD(b)))))

    private fun conn(src: Long, dst: Long): N =
        N("C", listOf(pS("OO"), pL(src), pL(dst)), emptyList())

    private fun scene(version: Int, objects: List<N>, connections: List<N>,
                      settings: List<Pair<String, List<ByteArray>>> = emptyList()): ByteArray {
        val top = ArrayList<N>()
        if (settings.isNotEmpty()) top.add(N("GlobalSettings", emptyList(), listOf(p70(*settings.toTypedArray()))))
        top.add(N("Objects", emptyList(), objects))
        top.add(N("Connections", emptyList(), connections))
        return fbxFile(version, top)
    }

    private fun tri(id: Long = 100, modelId: Long = 200): Pair<List<N>, List<N>> {
        val g = geom(id, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 2.0, 3.0, 1.0, 6.0, 3.0),
            intArrayOf(0, 1, -3))
        val m = model(modelId)
        return listOf(g, m) to listOf(conn(id, modelId), conn(modelId, 0))
    }

    // ---------- assertions ----------

    private fun read(bytes: ByteArray): Pair<FloatArray, IntArray> {
        val f = File.createTempFile("fbx-test", ".fbx")
        f.writeBytes(bytes)
        f.deleteOnExit()
        return FbxReader.read(f)
    }

    private fun assertVerts(expected: FloatArray, actual: FloatArray, delta: Float = 0.001f) {
        assertEquals("vertex float count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("vertex float $i", expected[i], actual[i], delta)
        }
    }

    private fun expectThrow(bytes: ByteArray): IllegalArgumentException {
        try {
            read(bytes)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            return e
        }
        throw AssertionError("unreachable")
    }

    // ---------- tests ----------

    @Test
    fun singleTriangle64Bit() {
        val (objects, conns) = tri()
        val (verts, colours) = read(scene(7700, objects, conns))
        // cm, Y up: (x, y, z) -> (x, -z, y) * 10
        assertVerts(floatArrayOf(10f, -30f, 20f, 40f, -30f, 20f, 10f, -30f, 60f), verts)
        assertEquals(listOf(-1), colours.toList())
    }

    @Test
    fun singleTriangle32BitZlibVertices() {
        val g = geom(100, doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            intArrayOf(0, 1, -3), compressVerts = true)
        val m = model(200)
        val (verts, _) = read(scene(7300, listOf(g, m), listOf(conn(100, 200), conn(200, 0))))
        assertVerts(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 0f, 10f), verts)
    }

    @Test
    fun quadFansIntoTwoTriangles() {
        val g = geom(100, doubleArrayOf(
            0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 10.0, 10.0, 0.0, 0.0, 10.0, 0.0),
            intArrayOf(0, 1, 2, -4))
        val m = model(200)
        val (verts, colours) = read(scene(7700, listOf(g, m), listOf(conn(100, 200), conn(200, 0))))
        assertVerts(floatArrayOf(
            0f, 0f, 0f, 100f, 0f, 0f, 100f, 0f, 100f,
            0f, 0f, 0f, 100f, 0f, 100f, 0f, 0f, 100f), verts)
        assertEquals(2, colours.size)
    }

    @Test
    fun twoMaterialsByPolygon() {
        val g = geom(100, doubleArrayOf(
            0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0,
            2.0, 0.0, 0.0, 3.0, 0.0, 0.0, 2.0, 1.0, 0.0),
            intArrayOf(0, 1, -3, 3, 4, -6), lem = lem("ByPolygon", intArrayOf(0, 1)))
        val m = model(200)
        val matA = material(300, 1.0, 0.0, 0.0)
        val matB = material(301, 0.0, 1.0, 0.0)
        val (verts, colours) = read(scene(7700, listOf(g, m, matA, matB),
            listOf(conn(100, 200), conn(300, 200), conn(301, 200), conn(200, 0))))
        assertEquals(18, verts.size)
        assertEquals(listOf(0xFF0000, 0x00FF00), colours.toList())
    }

    @Test
    fun allSameMaterialNestedInLayer() {
        val g = geom(100, doubleArrayOf(
            0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0,
            2.0, 0.0, 0.0, 3.0, 0.0, 0.0, 2.0, 1.0, 0.0),
            intArrayOf(0, 1, -3, 3, 4, -6),
            lem = lem("AllSame", intArrayOf(1), nestedInLayer = true))
        val m = model(200)
        val matA = material(300, 1.0, 0.0, 0.0)
        val matB = material(301, 0.0, 0.0, 1.0)
        val (_, colours) = read(scene(7700, listOf(g, m, matA, matB),
            listOf(conn(100, 200), conn(300, 200), conn(301, 200), conn(200, 0))))
        assertEquals(listOf(0x0000FF, 0x0000FF), colours.toList())
    }

    @Test
    fun nestedModelTransformsCompose() {
        val g = geom(100, doubleArrayOf(1.0, 0.0, 0.0, 2.0, 0.0, 0.0, 1.0, 1.0, 0.0),
            intArrayOf(0, 1, -3))
        val child = model(201,
            "Lcl Translation" to listOf(pD(10.0), pD(0.0), pD(0.0)),
            "Lcl Rotation" to listOf(pD(0.0), pD(0.0), pD(90.0)))
        val parent = model(200, "Lcl Translation" to listOf(pD(0.0), pD(1.0), pD(0.0)))
        val (verts, _) = read(scene(7700, listOf(g, child, parent),
            listOf(conn(100, 201), conn(201, 200), conn(200, 0))))
        // (1,0,0)->Rz90->(0,1,0)->T(10,0,0)->(10,1,0)->T(0,1,0)->(10,2,0)->axes->(100,0,20)
        assertVerts(floatArrayOf(100f, 0f, 20f, 100f, 0f, 30f, 90f, 0f, 20f), verts)
    }

    @Test
    fun preRotationApplies() {
        val g = geom(100, doubleArrayOf(1.0, 0.0, 0.0, 2.0, 0.0, 0.0, 1.0, 1.0, 0.0),
            intArrayOf(0, 1, -3))
        val m = model(200, "PreRotation" to listOf(pD(0.0), pD(0.0), pD(90.0)))
        val (verts, _) = read(scene(7700, listOf(g, m), listOf(conn(100, 200), conn(200, 0))))
        assertVerts(floatArrayOf(0f, 0f, 10f, 0f, 0f, 20f, -10f, 0f, 10f), verts)
    }

    @Test
    fun zUpFileWithInchUnits() {
        val (objects, conns) = tri()
        val settings = listOf(
            "UpAxis" to listOf(pI(2)), "UpAxisSign" to listOf(pI(1)),
            "FrontAxis" to listOf(pI(1)), "FrontAxisSign" to listOf(pI(1)),
            "CoordAxis" to listOf(pI(0)), "CoordAxisSign" to listOf(pI(1)),
            "UnitScaleFactor" to listOf(pD(2.54)))
        val g = geom(100, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            intArrayOf(0, 1, -3))
        val (verts, _) = read(scene(7700, listOf(g, objects[1]), conns, settings))
        assertVerts(floatArrayOf(25.4f, 0f, 0f, 0f, 25.4f, 0f, 0f, 0f, 25.4f), verts, 0.01f)
    }

    @Test
    fun rotationOrderYzxHonoured() {
        val g = geom(100, doubleArrayOf(0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            intArrayOf(0, 1, -3))
        val m = model(200,
            "RotationOrder" to listOf(pI(2)),
            "Lcl Rotation" to listOf(pD(90.0), pD(90.0), pD(0.0)))
        val (verts, _) = read(scene(7700, listOf(g, m), listOf(conn(100, 200), conn(200, 0))))
        // YZX: M = Rx x Rz x Ry, angles X=90, Y=90, Z=0, so M = Rx(90) x Ry(90).
        // (0,0,1)->(10,0,0); (1,0,0)->(0,0,10); (0,1,0)->(0,-10,0).
        // (Under XYZ the first vertex would land at (0,0,-10) instead.)
        assertVerts(floatArrayOf(10f, 0f, 0f, 0f, 0f, 10f, 0f, -10f, 0f), verts)
    }

    @Test
    fun geometricScalingAppliesToGeometry() {
        val g = geom(100, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            intArrayOf(0, 1, -3))
        val m = model(200, "GeometricScaling" to listOf(pD(2.0), pD(2.0), pD(2.0)))
        val (verts, _) = read(scene(7700, listOf(g, m), listOf(conn(100, 200), conn(200, 0))))
        assertVerts(floatArrayOf(20f, 0f, 0f, 0f, 0f, 20f, 0f, -20f, 0f), verts)
    }

    @Test
    fun geometricTransformNotInheritedByChildModel() {
        val g = geom(100, doubleArrayOf(1.0, 0.0, 0.0, 2.0, 0.0, 0.0, 1.0, 1.0, 0.0),
            intArrayOf(0, 1, -3))
        val child = model(201)
        val parent = model(200, "GeometricTranslation" to listOf(pD(100.0), pD(0.0), pD(0.0)))
        val (verts, _) = read(scene(7700, listOf(g, child, parent),
            listOf(conn(100, 201), conn(201, 200), conn(200, 0))))
        // No +100 cm anywhere: the parent's geometric translation is not inherited.
        assertVerts(floatArrayOf(10f, 0f, 0f, 20f, 0f, 0f, 10f, 0f, 10f), verts)
    }

    @Test
    fun asciiFbxThrowsExactMessage() {
        val e = expectThrow("; FBX 7.4.0 project file\n; Copyright by Autodesk\n".toByteArray())
        assertEquals("ascii fbx", e.message)
        assertTrue(e.message == "ascii fbx")
    }

    @Test
    fun truncatedFileThrows() {
        val (objects, conns) = tri()
        val full = scene(7700, objects, conns)
        expectThrow(full.copyOf(full.size * 3 / 5))
    }

    @Test
    fun lyingArrayLengthThrows() {
        val g = geom(100, doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            intArrayOf(0, 1, -3), declaredVerts = 9999)
        val m = model(200)
        expectThrow(scene(7300, listOf(g, m), listOf(conn(100, 200), conn(200, 0))))
    }
}
