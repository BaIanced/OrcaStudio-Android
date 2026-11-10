package app.orcaandroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * Pins [Max3dsReader] against 3DS files built byte-by-byte in the test (no
 * fixtures): chunk tree 0x4D4D -> 0x3D3D -> materials (0xAFFF) and objects
 * (0x4000/0x4100). Covers the contract cases: single triangle, quad, two
 * materials, a transformed instance, units/axis pass-through, truncation and
 * lying counts.
 */
class Max3dsReaderTest {

    // ---- tiny 3DS writer -------------------------------------------------

    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun f32(v: Float) = u32(v.toRawBits())
    private fun cstr(s: String) = s.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)

    private fun chunk(id: Int, vararg parts: ByteArray): ByteArray {
        var payload = ByteArray(0)
        for (p in parts) payload += p
        return u16(id) + u32(payload.size + 6) + payload
    }

    private fun verts(vararg v: Float): ByteArray {
        var body = u16(v.size / 3)
        for (f in v) body += f32(f)
        return chunk(0x4110, body)
    }

    private fun faces(vararg tris: IntArray, groups: List<Pair<String, IntArray>> = emptyList()): ByteArray {
        var body = u16(tris.size)
        for (t in tris) body += u16(t[0]) + u16(t[1]) + u16(t[2]) + u16(0)
        for ((name, idx) in groups) {
            var g = cstr(name) + u16(idx.size)
            for (i in idx) g += u16(i)
            body += chunk(0x4130, g)
        }
        return chunk(0x4120, body)
    }

    private fun matrix(vararg m: Float): ByteArray {
        var body = ByteArray(0)
        for (f in m) body += f32(f)
        return chunk(0x4160, body)
    }

    private fun mesh(vararg chunks: ByteArray) = chunk(0x4100, *chunks)
    private fun obj(name: String, vararg chunks: ByteArray) = chunk(0x4000, cstr(name), *chunks)

    private fun material(name: String, diffuse: ByteArray) = chunk(0xAFFF, chunk(0xA000, cstr(name)), chunk(0xA020, diffuse))
    private fun diffuseBytes(r: Int, g: Int, b: Int) = chunk(0x0011, byteArrayOf(r.toByte(), g.toByte(), b.toByte()))
    private fun diffuseFloats(r: Float, g: Float, b: Float) = chunk(0x0010, f32(r) + f32(g) + f32(b))

    private fun fileOf(vararg editorChildren: ByteArray) = chunk(0x4D4D, chunk(0x3D3D, *editorChildren))

    private fun read(bytes: ByteArray): Pair<FloatArray, IntArray> {
        val f = File.createTempFile("max3ds-test", ".3ds")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return Max3dsReader.read(f)
    }

    private fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) =
        floatArrayOf(ax, ay, az, bx, by, bz, cx, cy, cz)

    // ---- happy paths -----------------------------------------------------

    @Test fun singleTriangleWithByteMaterial() {
        val bytes = fileOf(
            material("red", diffuseBytes(255, 0, 0)),
            obj("tri", mesh(
                verts(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f),
                faces(intArrayOf(0, 1, 2), groups = listOf("red" to intArrayOf(0)))
            ))
        )
        val (v, c) = read(bytes)
        assertArrayEquals(tri(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), v, 0f)
        assertArrayEquals(intArrayOf(0xFF0000), c)
    }

    @Test fun quadWithoutMaterialIsMinusOne() {
        val bytes = fileOf(
            obj("quad", mesh(
                verts(0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f),
                faces(intArrayOf(0, 1, 2), intArrayOf(0, 2, 3))
            ))
        )
        val (v, c) = read(bytes)
        assertArrayEquals(
            tri(0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f, 0f) + tri(0f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f), v, 0f
        )
        assertArrayEquals(intArrayOf(-1, -1), c)
    }

    @Test fun twoMaterialsByteAndFloatDiffuse() {
        val bytes = fileOf(
            material("bytes", diffuseBytes(0, 128, 255)),
            material("floats", diffuseFloats(1f, 0.25f, 0f)),
            obj("two", mesh(
                verts(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 5f, 5f, 5f, 6f, 5f, 5f, 5f, 6f, 5f),
                faces(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5),
                    groups = listOf("bytes" to intArrayOf(0), "floats" to intArrayOf(1)))
            ))
        )
        val (_, c) = read(bytes)
        // 0.25f * 255 = 63.75 rounds to 64 = 0x40.
        assertArrayEquals(intArrayOf(0x0080FF, 0xFF4000), c)
    }

    @Test fun faceWithUnknownMaterialNameIsMinusOne() {
        val bytes = fileOf(
            obj("tri", mesh(
                verts(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
                faces(intArrayOf(0, 1, 2), groups = listOf("ghost" to intArrayOf(0)))
            ))
        )
        val (_, c) = read(bytes)
        assertArrayEquals(intArrayOf(-1), c)
    }

    @Test fun localMatrixIsNotReapplied() {
        // Assimp's Discreet3DS importer: 0x4110 vertices are stored already
        // transformed ("pretransformed by this matrix"); 0x4160 records the
        // local frame only. A pure-translation matrix must therefore NOT move
        // the output a second time. Floats in Assimp's read order: three
        // basis rows, then the translation in slots 9..11.
        val m = matrix(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            100f, 200f, 300f
        )
        val bytes = fileOf(
            obj("moved", mesh(
                verts(101f, 202f, 303f, 104f, 202f, 303f, 101f, 206f, 303f),
                faces(intArrayOf(0, 1, 2)),
                m
            ))
        )
        val (v, _) = read(bytes)
        assertArrayEquals(tri(101f, 202f, 303f, 104f, 202f, 303f, 101f, 206f, 303f), v, 0f)
    }

    @Test fun coordinatesPassThroughUnchanged() {
        // 3DS is unitless (treated as mm) and natively Z up: no scaling, no axis swap.
        val bytes = fileOf(
            obj("axes", mesh(
                verts(-5.5f, 2.25f, -7.75f, 1f, 2f, 3f, -1f, -2f, -3f),
                faces(intArrayOf(0, 1, 2))
            ))
        )
        val (v, _) = read(bytes)
        assertArrayEquals(tri(-5.5f, 2.25f, -7.75f, 1f, 2f, 3f, -1f, -2f, -3f), v, 0f)
    }

    @Test fun unknownChunksSkippedAndObjectsConcatenated() {
        val junk = chunk(0x1234, byteArrayOf(9, 9, 9, 9, 9))
        val bytes = fileOf(
            junk,
            obj("a", mesh(verts(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), faces(intArrayOf(0, 1, 2)))),
            obj("b", mesh(verts(5f, 5f, 5f, 6f, 5f, 5f, 5f, 6f, 5f), faces(intArrayOf(0, 1, 2))))
        )
        val (v, c) = read(bytes)
        assertEquals(18, v.size)
        assertArrayEquals(intArrayOf(-1, -1), c)
        assertArrayEquals(tri(5f, 5f, 5f, 6f, 5f, 5f, 5f, 6f, 5f), v.copyOfRange(9, 18), 0f)
    }

    // ---- hostile input ---------------------------------------------------

    @Test fun truncatedFileThrows() {
        val bytes = fileOf(
            obj("tri", mesh(verts(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), faces(intArrayOf(0, 1, 2))))
        )
        assertThrows(IllegalArgumentException::class.java) { read(bytes.copyOf(bytes.size * 3 / 5)) }
    }

    @Test fun lyingVertexCountThrows() {
        // The chunk length is honest; the vertex count inside lies (10 claimed, 3 present).
        var body = u16(10)
        body += f32(0f) + f32(0f) + f32(0f) + f32(1f) + f32(0f) + f32(0f) + f32(0f) + f32(1f) + f32(0f)
        val bytes = fileOf(obj("lie", mesh(chunk(0x4110, body), faces(intArrayOf(0, 1, 2)))))
        assertThrows(IllegalArgumentException::class.java) { read(bytes) }
    }

    @Test fun lyingChunkLengthThrows() {
        val bytes = fileOf(
            obj("tri", mesh(verts(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), faces(intArrayOf(0, 1, 2))))
        )
        // Inflate the main chunk's declared length beyond the file size.
        val bad = bytes.copyOf()
        val inflated = bytes.size + 100
        bad[2] = inflated.toByte(); bad[3] = (inflated shr 8).toByte()
        bad[4] = (inflated shr 16).toByte(); bad[5] = (inflated shr 24).toByte()
        assertThrows(IllegalArgumentException::class.java) { read(bad) }
    }

    @Test fun faceIndexOutOfRangeThrows() {
        val bytes = fileOf(
            obj("bad", mesh(verts(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), faces(intArrayOf(0, 1, 3))))
        )
        assertThrows(IllegalArgumentException::class.java) { read(bytes) }
    }

    @Test fun garbageFileThrows() {
        assertThrows(IllegalArgumentException::class.java) { read("this is not a 3ds file at all".toByteArray()) }
    }
}
