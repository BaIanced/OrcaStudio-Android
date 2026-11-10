package app.orcaandroid.core

import java.io.File

/**
 * Standalone reader for binary Autodesk 3DS files (Task 23). Returns the common
 * mesh contract: triangles as 9 floats each (x, y, z per corner) in millimetres,
 * Z up, right-handed, plus one `0xRRGGBB` per triangle (-1 = no colour).
 *
 * Chunk walk: `0x4D4D` main -> `0x3D3D` editor -> `0xAFFF` materials and
 * `0x4000` objects (`0x4100` mesh: `0x4110` vertices, `0x4120` faces with
 * `0x4130` per-material face groups, `0x4160` local matrix). Unknown chunks are
 * skipped by their length. Every length and count is validated against the
 * bytes actually remaining before anything is read or allocated; malformed or
 * truncated input throws [IllegalArgumentException].
 *
 * The `0x4160` matrix is parsed and validated but deliberately NOT applied:
 * modelled on Assimp's Discreet3DS importer (`3DSLoader.cpp`, CHUNK_TRMATRIX:
 * "Vertices are pretransformed by this matrix"), 3DS stores `0x4110` vertices
 * already in world space and the matrix only records the object's local frame,
 * so applying it again would double-transform. Its file layout (for the
 * record) is 12 floats in aiMatrix4x4 field order a1..c4, translation last.
 * 3DS units are unitless; values pass through unchanged as millimetres, and
 * the format's native axes are already Z up, right-handed, so no conversion.
 */
object Max3dsReader {

    fun read(file: File): Pair<FloatArray, IntArray> {
        val bytes = file.readBytes()
        val r = Reader(bytes)
        val materials = LinkedHashMap<String, Int?>()
        val meshes = ArrayList<Mesh>()
        var sawMain = false
        // Top level: find the main chunk (anything else is skipped by length).
        while (r.hasChunk()) {
            val (id, end) = r.chunkHeader()
            if (id == CHUNK_MAIN) {
                sawMain = true
                parseMain(r.child(end), materials, meshes)
            }
            r.skipTo(end)
        }
        if (!sawMain) throw IllegalArgumentException("3ds: no main chunk 0x4D4D")
        if (meshes.isEmpty()) throw IllegalArgumentException("3ds: no mesh objects")

        var triCount = 0
        for (m in meshes) triCount += m.faces.size / 3
        val verts = FloatArray(triCount * 9)
        val colours = IntArray(triCount)
        var t = 0
        for (m in meshes) {
            for (f in m.faces.indices step 3) {
                for (k in 0 until 3) {
                    val vi = m.faces[f + k] * 3
                    verts[t * 9 + k * 3] = m.vertices[vi]
                    verts[t * 9 + k * 3 + 1] = m.vertices[vi + 1]
                    verts[t * 9 + k * 3 + 2] = m.vertices[vi + 2]
                }
                val matName = m.faceMaterial[f / 3]
                colours[t] = if (matName == null) -1 else materials[matName] ?: -1
                t++
            }
        }
        return verts to colours
    }

    private class Mesh {
        var vertices = FloatArray(0)
        var faces = IntArray(0) // 3 vertex indices per face
        var faceMaterial = ArrayList<String?>() // one entry per face
        var matrix: FloatArray? = null // 0x4160, parsed for validation only
    }

    private fun parseMain(r: Reader, materials: MutableMap<String, Int?>, meshes: MutableList<Mesh>) {
        while (r.hasChunk()) {
            val (id, end) = r.chunkHeader()
            when (id) {
                CHUNK_EDITOR -> parseEditor(r.child(end), materials, meshes)
                CHUNK_MATERIAL -> materials.putAll(parseMaterial(r.child(end)))
                CHUNK_OBJECT -> parseObject(r.child(end))?.let { meshes.add(it) }
            }
            r.skipTo(end)
        }
    }

    private fun parseEditor(r: Reader, materials: MutableMap<String, Int?>, meshes: MutableList<Mesh>) {
        while (r.hasChunk()) {
            val (id, end) = r.chunkHeader()
            when (id) {
                CHUNK_MATERIAL -> materials.putAll(parseMaterial(r.child(end)))
                CHUNK_OBJECT -> parseObject(r.child(end))?.let { meshes.add(it) }
            }
            r.skipTo(end)
        }
    }

    /** Returns material name to diffuse `0xRRGGBB`, or name to null when it has no diffuse colour. */
    private fun parseMaterial(r: Reader): Map<String, Int?> {
        var name: String? = null
        var colour: Int? = null
        while (r.hasChunk()) {
            val (id, end) = r.chunkHeader()
            when (id) {
                CHUNK_MAT_NAME -> name = r.cstring(end)
                CHUNK_MAT_DIFFUSE -> colour = parseColour(r.child(end)) ?: colour
            }
            r.skipTo(end)
        }
        val n = name ?: throw IllegalArgumentException("3ds: material without a name")
        return mapOf(n to colour)
    }

    private fun parseColour(r: Reader): Int? {
        var rgb: Int? = null
        while (r.hasChunk()) {
            val (id, end) = r.chunkHeader()
            when (id) {
                CHUNK_RGB_FLOAT -> {
                    r.require(12, end, "3ds: short RGB float colour")
                    val red = (r.f32() * 255f).roundToByte()
                    val green = (r.f32() * 255f).roundToByte()
                    val blue = (r.f32() * 255f).roundToByte()
                    rgb = (red shl 16) or (green shl 8) or blue
                }
                CHUNK_RGB_BYTE -> {
                    r.require(3, end, "3ds: short RGB byte colour")
                    rgb = (r.u8() shl 16) or (r.u8() shl 8) or r.u8()
                }
            }
            r.skipTo(end)
        }
        return rgb
    }

    private fun parseObject(r: Reader): Mesh? {
        r.cstring(r.end) // object name; geometry does not need it
        var mesh: Mesh? = null
        while (r.hasChunk()) {
            val (id, end) = r.chunkHeader()
            if (id == CHUNK_MESH) mesh = parseMesh(r.child(end))
            r.skipTo(end)
        }
        return mesh
    }

    private fun parseMesh(r: Reader): Mesh {
        val mesh = Mesh()
        while (r.hasChunk()) {
            val (id, end) = r.chunkHeader()
            when (id) {
                CHUNK_VERTICES -> {
                    val count = r.u16()
                    r.require(count * 12, end, "3ds: vertex list overruns its chunk")
                    val v = FloatArray(count * 3)
                    for (i in v.indices) v[i] = r.f32()
                    mesh.vertices = v
                }
                CHUNK_FACES -> parseFaces(r.child(end), mesh)
                CHUNK_MATRIX -> {
                    r.require(48, end, "3ds: local matrix chunk too short")
                    val m = FloatArray(12)
                    for (i in m.indices) m[i] = r.f32()
                    mesh.matrix = m // validated only; vertices are pre-transformed (see class doc)
                }
            }
            r.skipTo(end)
        }
        return mesh
    }

    private fun parseFaces(r: Reader, mesh: Mesh) {
        val base = mesh.faceMaterial.size
        val count = r.u16()
        r.require(count * 8, r.end, "3ds: face list overruns its chunk")
        val faces = IntArray(count * 3)
        val vertCount = mesh.vertices.size / 3
        for (i in 0 until count) {
            for (k in 0 until 3) {
                val idx = r.u16()
                if (idx >= vertCount) throw IllegalArgumentException("3ds: face index $idx out of range ($vertCount vertices)")
                faces[i * 3 + k] = idx
            }
            r.u16() // flags: edge visibility, not geometry
            mesh.faceMaterial.add(null)
        }
        mesh.faces = mesh.faces + faces
        // Sub-chunks follow the face array inside the same chunk (0x4130 groups).
        while (r.hasChunk()) {
            val (id, end) = r.chunkHeader()
            if (id == CHUNK_FACE_MATERIAL) {
                val name = r.cstring(end)
                val n = r.u16()
                r.require(n * 2, end, "3ds: material face list overruns its chunk")
                for (i in 0 until n) {
                    val fi = r.u16()
                    if (fi >= count) throw IllegalArgumentException("3ds: material face index $fi out of range ($count faces)")
                    mesh.faceMaterial[base + fi] = name
                }
            }
            r.skipTo(end)
        }
    }

    private fun Float.roundToByte(): Int {
        val v = Math.round(this)
        return when {
            v < 0 -> 0
            v > 255 -> 255
            else -> v
        }
    }

    /** Bounds-checked little-endian cursor over one chunk's byte range. */
    private class Reader(private val bytes: ByteArray, private var pos: Int = 0, val end: Int = bytes.size) {
        fun child(end: Int) = Reader(bytes, pos, end)
        fun hasChunk(): Boolean = end - pos >= 6

        /** Reads a chunk header, returns id to chunk end. Validates the length against this range. */
        fun chunkHeader(): Pair<Int, Int> {
            if (end - pos < 6) throw IllegalArgumentException("3ds: truncated chunk header")
            val id = u16()
            val len = u32()
            if (len < 6) throw IllegalArgumentException("3ds: chunk length $len < 6")
            val start = pos - 6
            if (len > end - start) throw IllegalArgumentException("3ds: chunk 0x${id.toString(16)} overruns its parent")
            return id to start + len
        }

        fun skipTo(chunkEnd: Int) {
            pos = chunkEnd
        }

        fun require(n: Int, limit: Int, msg: String) {
            if (n < 0 || n > limit - pos) throw IllegalArgumentException(msg)
        }

        fun u8(): Int {
            if (pos >= end) throw IllegalArgumentException("3ds: unexpected end of data")
            return bytes[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            if (end - pos < 2) throw IllegalArgumentException("3ds: unexpected end of data")
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }

        fun u32(): Int {
            if (end - pos < 4) throw IllegalArgumentException("3ds: unexpected end of data")
            var v = 0
            for (i in 0 until 4) v = v or ((bytes[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }

        fun f32(): Float = Float.fromBits(u32())

        /** Reads a NUL-terminated string that must terminate before [limit]. */
        fun cstring(limit: Int): String {
            var i = pos
            while (i < limit && bytes[i] != 0.toByte()) i++
            if (i >= limit) throw IllegalArgumentException("3ds: unterminated string")
            val s = String(bytes, pos, i - pos, Charsets.ISO_8859_1)
            pos = i + 1
            return s
        }
    }

    private const val CHUNK_MAIN = 0x4D4D
    private const val CHUNK_EDITOR = 0x3D3D
    private const val CHUNK_MATERIAL = 0xAFFF
    private const val CHUNK_MAT_NAME = 0xA000
    private const val CHUNK_MAT_DIFFUSE = 0xA020
    private const val CHUNK_RGB_FLOAT = 0x0010
    private const val CHUNK_RGB_BYTE = 0x0011
    private const val CHUNK_OBJECT = 0x4000
    private const val CHUNK_MESH = 0x4100
    private const val CHUNK_VERTICES = 0x4110
    private const val CHUNK_FACES = 0x4120
    private const val CHUNK_FACE_MATERIAL = 0x4130
    private const val CHUNK_MATRIX = 0x4160
}
