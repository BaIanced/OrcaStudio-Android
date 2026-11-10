package app.orcaandroid.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal binary FBX reader: converts a binary FBX scene's static mesh
 * geometry to the app's neutral mesh form - millimetres, Z up, right-handed,
 * one diffuse colour per triangle (0xRRGGBB, -1 = none).
 *
 * Binary FBX only: the "Kaydara FBX Binary" header is checked and ASCII
 * files throw IllegalArgumentException("ascii fbx"). Both node-record
 * layouts are read (32-bit offsets for version < 7500, 64-bit for >= 7500).
 * Node properties cover the scalar types (C Y I L F D), strings/raw (S R)
 * and the typed arrays (f d l i b, raw or zlib-compressed).
 *
 * Modelled on Assimp's FBX importer (FBXParser / FBXDocument /
 * FBXConverter, master, read 2026-11-09): object nodes carry
 * [id (L), name (S, "Name\u0000\u0001Subclass"), class tag (S)] as their
 * first three properties; Properties70 values start at property index 4;
 * connections are [type (S), source id (L), destination id (L)]; a Model's
 * local matrix is
 *   T x Roff x Rpiv x PreR x R x PostR x RpivInv x Soff x Spiv x S x SpivInv
 * with Euler angles in degrees under the model's RotationOrder (for order
 * ABC the matrix is Rc x Rb x Ra), and GeometricTranslation/Rotation/
 * Scaling from the same Model apply to its geometry alone, never to child
 * models. World = parent world x local.
 *
 * Materials: a Geometry's LayerElementMaterial (ByPolygon or AllSame,
 * IndexToDirect) indexes the Materials in the order they are connected to
 * the owning Model; the colour is the material's DiffuseColor property
 * (Diffuse as a fallback). Unit scale is GlobalSettings UnitScaleFactor
 * (cm per file unit) x 10 to mm. Axis conversion uses GlobalSettings
 * CoordAxis/Sign as +X and UpAxis/Sign as +Z, with +Y derived as Z x X so
 * the result is always right-handed (see UNVERIFIED notes in the task log).
 *
 * Throws IllegalArgumentException on anything malformed or unsupported.
 */
object FbxReader {

    // ---------- parsed node tree ----------

    private class Node(
        val name: String,
        val props: List<Any>,
        val children: List<Node>,
    ) {
        fun child(name: String): Node? = children.firstOrNull { it.name == name }
        fun childNodes(name: String): List<Node> = children.filter { it.name == name }
    }

    private class Cursor(val bytes: ByteArray) {
        var pos: Int = 0
        fun remaining(): Int = bytes.size - pos
        fun need(n: Int, what: String) {
            if (n < 0 || n > remaining()) fail("truncated $what")
        }
        fun u8(what: String): Int {
            need(1, what)
            return bytes[pos++].toInt() and 0xFF
        }
        fun take(n: Int, what: String): ByteArray {
            need(n, what)
            val out = bytes.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        fun u32(what: String): Long {
            need(4, what)
            var v = 0L
            for (i in 0 until 4) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }
        fun u64(what: String): Long {
            need(8, what)
            var v = 0L
            for (i in 0 until 8) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }
    }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException("fbx: $msg")

    // ---------- binary node parsing ----------

    private fun parseNodes(cur: Cursor, endLimit: Int, wide: Boolean, depth: Int): List<Node> {
        if (depth > 64) fail("node nesting too deep")
        val nodes = ArrayList<Node>()
        val headerSize = if (wide) 25 else 13
        val nullSize = headerSize
        val offSize = if (wide) 8 else 4
        while (cur.pos < endLimit) {
            val remaining = endLimit - cur.pos
            // A NULL record terminates this level's node list; like Assimp's
            // tokenizer, treat a zero end-offset word as the terminator
            // (this also stops the root level before the file footer).
            if (remaining >= offSize) {
                var off = 0L
                for (i in 0 until offSize) {
                    off = off or ((cur.bytes[cur.pos + i].toLong() and 0xFF) shl (8 * i))
                }
                if (off == 0L) {
                    cur.pos += minOf(nullSize, remaining)
                    break
                }
            }
            if (remaining < nullSize) fail("ragged node area ($remaining bytes)")
            nodes.add(parseNode(cur, wide, depth))
            if (cur.pos > endLimit) fail("node overruns its parent")
        }
        return nodes
    }

    private fun parseNode(cur: Cursor, wide: Boolean, depth: Int): Node {
        val start = cur.pos
        val endOffset: Long
        val numProps: Long
        val propLen: Long
        if (wide) {
            endOffset = cur.u64("node header")
            numProps = cur.u64("node header")
            propLen = cur.u64("node header")
        } else {
            endOffset = cur.u32("node header")
            numProps = cur.u32("node header")
            propLen = cur.u32("node header")
        }
        if (endOffset <= start || endOffset > cur.bytes.size) {
            fail("node end offset $endOffset outside file")
        }
        if (numProps < 0 || numProps > 1_000_000) fail("implausible property count $numProps")
        val nameLen = cur.u8("node name")
        val name = String(cur.take(nameLen, "node name"), Charsets.UTF_8)
        val propStart = cur.pos
        val props = ArrayList<Any>()
        for (i in 0 until numProps) props.add(parseProperty(cur))
        if (cur.pos - propStart.toLong() != propLen) {
            fail("property list length mismatch in node $name")
        }
        val children = parseNodes(cur, endOffset.toInt(), wide, depth + 1)
        if (cur.pos != endOffset.toInt()) fail("node $name does not end at its end offset")
        return Node(name, props, children)
    }

    private fun parseProperty(cur: Cursor): Any {
        return when (val type = cur.u8("property type").toChar()) {
            'Y' -> {
                cur.need(2, "int16 property")
                val b = ByteBuffer.wrap(cur.take(2, "int16 property")).order(ByteOrder.LITTLE_ENDIAN)
                b.short.toInt()
            }
            'C' -> cur.u8("bool property") != 0
            'I' -> {
                val b = ByteBuffer.wrap(cur.take(4, "int32 property")).order(ByteOrder.LITTLE_ENDIAN)
                b.int
            }
            'F' -> {
                val b = ByteBuffer.wrap(cur.take(4, "float property")).order(ByteOrder.LITTLE_ENDIAN)
                b.float
            }
            'D' -> {
                val b = ByteBuffer.wrap(cur.take(8, "double property")).order(ByteOrder.LITTLE_ENDIAN)
                b.double
            }
            'L' -> cur.u64("int64 property")
            'S' -> {
                val len = cur.u32("string property")
                if (len > cur.remaining()) fail("string property overruns file")
                String(cur.take(len.toInt(), "string property"), Charsets.UTF_8)
            }
            'R' -> {
                val len = cur.u32("raw property")
                if (len > cur.remaining()) fail("raw property overruns file")
                cur.take(len.toInt(), "raw property")
            }
            'f', 'd', 'i', 'l', 'b' -> parseArray(cur, type)
            else -> fail("unknown property type '$type'")
        }
    }

    private fun parseArray(cur: Cursor, type: Char): Any {
        val length = cur.u32("array header")
        val encoding = cur.u32("array header")
        val compressedLen = cur.u32("array header")
        val elemSize = when (type) {
            'f', 'i' -> 4
            'd', 'l' -> 8
            else -> 1 // 'b'
        }
        if (length > MAX_ARRAY_ELEMENTS) fail("array length $length too large")
        val rawLen = length * elemSize
        val data: ByteArray = when (encoding) {
            0L -> {
                if (compressedLen != rawLen) fail("raw array length mismatch")
                cur.take(rawLen.toInt(), "array data")
            }
            1L -> {
                if (compressedLen > cur.remaining()) fail("compressed array overruns file")
                if (rawLen > MAX_ARRAY_BYTES) fail("decompressed array too large")
                val comp = cur.take(compressedLen.toInt(), "array data")
                inflate(comp, rawLen.toInt())
            }
            else -> fail("unknown array encoding $encoding")
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            'f' -> FloatArray(length.toInt()) { buf.getFloat(it * 4) }
            'd' -> DoubleArray(length.toInt()) { buf.getDouble(it * 8) }
            'i' -> IntArray(length.toInt()) { buf.getInt(it * 4) }
            'l' -> LongArray(length.toInt()) { buf.getLong(it * 8) }
            else -> BooleanArray(length.toInt()) { data[it].toInt() != 0 }
        }
    }

    private fun inflate(comp: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(comp)
        val out = ByteArray(expected)
        var done = 0
        try {
            while (done < expected) {
                val n = inflater.inflate(out, done, expected - done)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.finished()) break
                    fail("zlib stream stalls")
                }
                done += n
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw IllegalArgumentException("fbx: corrupt zlib array", e)
        } finally {
            inflater.end()
        }
        if (done != expected) fail("zlib array inflated to $done of $expected bytes")
        return out
    }

    private const val MAX_ARRAY_ELEMENTS = 100_000_000L
    private const val MAX_ARRAY_BYTES = 256L * 1024 * 1024

    // ---------- Properties70 ----------

    /** A Properties70 node as name -> value properties (index 4 onwards). */
    private fun properties70(node: Node?): Map<String, List<Any>> {
        val p70 = node?.child("Properties70") ?: return emptyMap()
        val out = HashMap<String, List<Any>>()
        for (p in p70.childNodes("P")) {
            if (p.props.size < 5) continue
            val name = p.props[0] as? String ?: continue
            out[name] = p.props.subList(4, p.props.size)
        }
        return out
    }

    private fun propVec3(props: Map<String, List<Any>>, name: String, default: DoubleArray): DoubleArray {
        val values = props[name] ?: return default
        if (values.size < 3) return default
        val out = DoubleArray(3)
        for (i in 0 until 3) {
            out[i] = when (val v = values[i]) {
                is Double -> v
                is Float -> v.toDouble()
                is Int -> v.toDouble()
                is Long -> v.toDouble()
                else -> return default
            }
        }
        return out
    }

    private fun propDouble(props: Map<String, List<Any>>, name: String, default: Double): Double {
        val values = props[name] ?: return default
        return when (val v = values.firstOrNull()) {
            is Double -> v
            is Float -> v.toDouble()
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            else -> default
        }
    }

    private fun propInt(props: Map<String, List<Any>>, name: String, default: Int): Int {
        val values = props[name] ?: return default
        return when (val v = values.firstOrNull()) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            else -> default
        }
    }

    // ---------- 4x4 matrices (row-major, column-vector, Double) ----------

    private fun identity(): DoubleArray = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(16)
        for (r in 0 until 4) for (c in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[r * 4 + k] * b[k * 4 + c]
            out[r * 4 + c] = s
        }
        return out
    }

    private fun translation(v: DoubleArray): DoubleArray =
        identity().also { it[3] = v[0]; it[7] = v[1]; it[11] = v[2] }

    private fun scaling(v: DoubleArray): DoubleArray =
        identity().also { it[0] = v[0]; it[5] = v[1]; it[10] = v[2] }

    private fun rotX(deg: Double): DoubleArray {
        val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0)
    }

    private fun rotY(deg: Double): DoubleArray {
        val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
        return doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0)
    }

    private fun rotZ(deg: Double): DoubleArray {
        val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
        return doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    }

    /** Euler rotation like Assimp's GetRotationMatrix: for order ABC, M = Rc x Rb x Ra. */
    private fun euler(order: Int, e: DoubleArray): DoubleArray {
        val axes = when (order) {
            1 -> intArrayOf(0, 2, 1) // XZY
            2 -> intArrayOf(1, 2, 0) // YZX
            3 -> intArrayOf(1, 0, 2) // YXZ
            4 -> intArrayOf(2, 0, 1) // ZXY
            5 -> intArrayOf(2, 1, 0) // ZYX
            else -> intArrayOf(0, 1, 2) // XYZ (and SphericXYZ fallback)
        }
        fun axisMatrix(axis: Int): DoubleArray = when (axis) {
            0 -> rotX(e[0])
            1 -> rotY(e[1])
            else -> rotZ(e[2])
        }
        return mul(mul(axisMatrix(axes[2]), axisMatrix(axes[1])), axisMatrix(axes[0]))
    }

    /**
     * FBX local matrix per the Assimp chain:
     * T x Roff x Rpiv x PreR x R x PostR x RpivInv x Soff x Spiv x S x SpivInv.
     */
    private fun localMatrix(props: Map<String, List<Any>>): DoubleArray {
        val zero = doubleArrayOf(0.0, 0.0, 0.0)
        val ones = doubleArrayOf(1.0, 1.0, 1.0)
        val order = propInt(props, "RotationOrder", 0)
        val t = translation(propVec3(props, "Lcl Translation", zero))
        val rOff = translation(propVec3(props, "RotationOffset", zero))
        val rPiv = propVec3(props, "RotationPivot", zero)
        val preR = euler(0, propVec3(props, "PreRotation", zero))
        val r = euler(order, propVec3(props, "Lcl Rotation", zero))
        val postR = euler(0, propVec3(props, "PostRotation", zero))
        val sOff = translation(propVec3(props, "ScalingOffset", zero))
        val sPiv = propVec3(props, "ScalingPivot", zero)
        val s = scaling(propVec3(props, "Lcl Scaling", ones))
        var m = t
        m = mul(m, rOff)
        m = mul(m, translation(rPiv))
        m = mul(m, preR)
        m = mul(m, r)
        m = mul(m, postR)
        m = mul(m, translation(doubleArrayOf(-rPiv[0], -rPiv[1], -rPiv[2])))
        m = mul(m, sOff)
        m = mul(m, translation(sPiv))
        m = mul(m, s)
        m = mul(m, translation(doubleArrayOf(-sPiv[0], -sPiv[1], -sPiv[2])))
        return m
    }

    /** Geometric transform of a Model: applies to its geometry alone. */
    private fun geometricMatrix(props: Map<String, List<Any>>): DoubleArray {
        val zero = doubleArrayOf(0.0, 0.0, 0.0)
        val ones = doubleArrayOf(1.0, 1.0, 1.0)
        val gt = translation(propVec3(props, "GeometricTranslation", zero))
        val gr = euler(0, propVec3(props, "GeometricRotation", zero))
        val gs = scaling(propVec3(props, "GeometricScaling", ones))
        return mul(mul(gt, gr), gs)
    }

    // ---------- geometry / materials ----------

    private class MeshData(
        val positions: DoubleArray,
        val polygons: List<IntArray>,
        val materialForPolygon: IntArray?, // material index per polygon, or null
    )

    private fun parseGeometry(node: Node): MeshData {
        val verticesNode = node.child("Vertices") ?: fail("geometry without Vertices")
        val rawVerts = verticesNode.props.firstOrNull()
        val positions: DoubleArray = when (rawVerts) {
            is DoubleArray -> rawVerts
            is FloatArray -> DoubleArray(rawVerts.size) { rawVerts[it].toDouble() }
            else -> fail("Vertices is not a float array")
        }
        if (positions.size % 3 != 0) fail("Vertices length ${positions.size} not a multiple of 3")
        val vcount = positions.size / 3

        val pviNode = node.child("PolygonVertexIndex") ?: fail("geometry without PolygonVertexIndex")
        val pvi: IntArray = when (val raw = pviNode.props.firstOrNull()) {
            is IntArray -> raw
            is LongArray -> IntArray(raw.size) {
                val v = raw[it]
                if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) fail("polygon index overflow")
                v.toInt()
            }
            else -> fail("PolygonVertexIndex is not an int array")
        }
        // Split into polygons: a negative entry ends a polygon (index = ~entry).
        val polygons = ArrayList<IntArray>()
        var current = ArrayList<Int>()
        for (entry in pvi) {
            if (entry < 0) {
                val idx = entry.inv()
                if (idx >= vcount) fail("vertex index $idx out of range (0..<$vcount)")
                current.add(idx)
                if (current.size < 3) fail("polygon with ${current.size} corners")
                polygons.add(current.toIntArray())
                current = ArrayList()
            } else {
                if (entry >= vcount) fail("vertex index $entry out of range (0..<$vcount)")
                current.add(entry)
            }
        }
        if (current.isNotEmpty()) fail("polygon vertex index list ends mid-polygon")
        if (polygons.isEmpty()) fail("geometry has no polygons")

        // LayerElementMaterial (direct child, or nested in a Layer element).
        val lem = node.child("LayerElementMaterial")
            ?: node.childNodes("Layer").firstNotNullOfOrNull { it.child("LayerElementMaterial") }
        var materialForPolygon: IntArray? = null
        if (lem != null) {
            val mapping = (lem.child("MappingInformationType")?.props?.firstOrNull() as? String)
                ?: fail("material layer without MappingInformationType")
            val indices = when (val raw = lem.child("Materials")?.props?.firstOrNull()) {
                is IntArray -> raw
                is LongArray -> IntArray(raw.size) { raw[it].toInt() }
                else -> fail("material layer without Materials indices")
            }
            materialForPolygon = when (mapping) {
                "AllSame" -> {
                    if (indices.isEmpty()) fail("AllSame material layer without an index")
                    IntArray(polygons.size) { indices[0] }
                }
                "ByPolygon" -> {
                    if (indices.size != polygons.size) {
                        fail("ByPolygon material count ${indices.size} != polygon count ${polygons.size}")
                    }
                    indices
                }
                else -> fail("unsupported material mapping $mapping")
            }
        }
        return MeshData(positions, polygons, materialForPolygon)
    }

    private fun materialColour(materialNode: Node): Int {
        val props = properties70(materialNode)
        val rgb = if (props.containsKey("DiffuseColor")) {
            propVec3(props, "DiffuseColor", doubleArrayOf(0.0, 0.0, 0.0))
        } else if (props.containsKey("Diffuse")) {
            propVec3(props, "Diffuse", doubleArrayOf(0.0, 0.0, 0.0))
        } else {
            return -1
        }
        fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0).toInt()
        return (ch(rgb[0]) shl 16) or (ch(rgb[1]) shl 8) or ch(rgb[2])
    }

    // ---------- main entry ----------

    fun read(file: File): Pair<FloatArray, IntArray> {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            throw IllegalArgumentException("fbx: cannot read file", e)
        }
        // Header: "Kaydara FBX Binary  \0" (21 bytes) + 0x1A 0x00 + version u32.
        val magic = "Kaydara FBX Binary  "
        if (bytes.size < 27) {
            throw IllegalArgumentException(
                if (looksLikeText(bytes)) "ascii fbx" else "fbx: file too small")
        }
        val headOk = magic.indices.all { bytes[it] == magic[it].code.toByte() } &&
            bytes[20] == 0.toByte() && bytes[21] == 0x1A.toByte() && bytes[22] == 0.toByte()
        if (!headOk) {
            throw IllegalArgumentException(
                if (looksLikeText(bytes)) "ascii fbx" else "fbx: bad header")
        }
        val version = ByteBuffer.wrap(bytes, 23, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val wide = version >= 7500

        val cur = Cursor(bytes)
        cur.pos = 27
        val roots = parseNodes(cur, bytes.size, wide, 0)

        // GlobalSettings
        var unitScaleCm = 1.0
        var coordAxis = 0; var coordSign = 1
        var upAxis = 1; var upSign = 1
        for (root in roots) {
            if (root.name == "GlobalSettings") {
                val props = properties70(root)
                unitScaleCm = propDouble(props, "UnitScaleFactor", 1.0)
                coordAxis = propInt(props, "CoordAxis", 0)
                coordSign = propInt(props, "CoordAxisSign", 1)
                upAxis = propInt(props, "UpAxis", 1)
                upSign = propInt(props, "UpAxisSign", 1)
            }
        }
        val mmPerUnit = unitScaleCm * 10.0

        // Axis conversion basis, in FBX component space: X = signed coord
        // axis, Z = signed up axis, Y = Z x X (right-handed by construction).
        fun axisVec(axis: Int, sign: Int): DoubleArray {
            val v = DoubleArray(3)
            if (axis in 0..2) v[axis] = sign.toDouble()
            return v
        }
        val bx = axisVec(coordAxis, coordSign)
        val bz = axisVec(upAxis, upSign)
        val by = doubleArrayOf(
            bz[1] * bx[2] - bz[2] * bx[1],
            bz[2] * bx[0] - bz[0] * bx[2],
            bz[0] * bx[1] - bz[1] * bx[0],
        )
        if (by.all { it == 0.0 }) fail("degenerate axis settings")

        // Objects
        val objectsNode = roots.firstOrNull { it.name == "Objects" } ?: fail("no Objects node")
        val geometries = HashMap<Long, MeshData>()
        val models = HashMap<Long, Node>()
        val modelProps = HashMap<Long, Map<String, List<Any>>>()
        val materialColours = HashMap<Long, Int>()
        for (obj in objectsNode.children) {
            if (obj.props.size < 3) continue
            val id = obj.props[0] as? Long ?: continue
            when (obj.name) {
                "Geometry" -> geometries[id] = parseGeometry(obj)
                "Model" -> {
                    models[id] = obj
                    modelProps[id] = properties70(obj)
                }
                "Material" -> materialColours[id] = materialColour(obj)
            }
        }
        if (geometries.isEmpty()) fail("no mesh geometry")

        // Connections: geometry -> model, material -> model (in file order),
        // model -> parent model (or 0).
        val geomModel = HashMap<Long, Long>()
        val modelMaterials = HashMap<Long, MutableList<Long>>()
        val modelParent = HashMap<Long, Long>()
        val connections = roots.firstOrNull { it.name == "Connections" }
        if (connections != null) {
            for (c in connections.childNodes("C")) {
                if (c.props.size < 3) continue
                val type = c.props[0] as? String ?: continue
                if (type != "OO") continue
                val src = c.props[1] as? Long ?: continue
                val dst = c.props[2] as? Long ?: continue
                when {
                    geometries.containsKey(src) && models.containsKey(dst) -> geomModel[src] = dst
                    materialColours.containsKey(src) && models.containsKey(dst) ->
                        modelMaterials.getOrPut(dst) { ArrayList() }.add(src)
                    models.containsKey(src) && (dst == 0L || models.containsKey(dst)) ->
                        modelParent[src] = dst
                }
            }
        }

        // World matrices, parents first.
        val worldCache = HashMap<Long, DoubleArray>()
        fun worldOf(modelId: Long, seen: MutableSet<Long>): DoubleArray {
            worldCache[modelId]?.let { return it }
            if (!seen.add(modelId)) fail("model hierarchy cycle")
            val local = localMatrix(modelProps[modelId] ?: emptyMap())
            val parent = modelParent[modelId]
            val world = if (parent == null || parent == 0L) local else mul(worldOf(parent, seen), local)
            worldCache[modelId] = world
            return world
        }

        val triangles = ArrayList<Float>()
        val colours = ArrayList<Int>()
        var emitted = 0
        for ((geomId, mesh) in geometries) {
            val modelId = geomModel[geomId] ?: continue // not placed in the scene
            val world = worldOf(modelId, HashSet())
            val geo = geometricMatrix(modelProps[modelId] ?: emptyMap())
            val total = mul(world, geo)
            val materials = modelMaterials[modelId] ?: emptyList()
            for (p in mesh.polygons.indices) {
                val poly = mesh.polygons[p]
                val matIndex = mesh.materialForPolygon?.get(p) ?: -1
                val colour = if (matIndex in materials.indices) {
                    materialColours[materials[matIndex]] ?: -1
                } else {
                    -1
                }
                for (i in 1 until poly.size - 1) {
                    for (corner in intArrayOf(poly[0], poly[i], poly[i + 1])) {
                        val x = mesh.positions[corner * 3]
                        val y = mesh.positions[corner * 3 + 1]
                        val z = mesh.positions[corner * 3 + 2]
                        val wx = total[0] * x + total[1] * y + total[2] * z + total[3]
                        val wy = total[4] * x + total[5] * y + total[6] * z + total[7]
                        val wz = total[8] * x + total[9] * y + total[10] * z + total[11]
                        // Re-express in the (coord, front, up) basis, then scale.
                        val ox = wx * bx[0] + wy * bx[1] + wz * bx[2]
                        val oy = wx * by[0] + wy * by[1] + wz * by[2]
                        val oz = wx * bz[0] + wy * bz[1] + wz * bz[2]
                        triangles.add((ox * mmPerUnit).toFloat())
                        triangles.add((oy * mmPerUnit).toFloat())
                        triangles.add((oz * mmPerUnit).toFloat())
                    }
                    colours.add(colour)
                }
                emitted++
            }
        }
        if (emitted == 0) fail("no geometry connected to a model")

        return Pair(triangles.toFloatArray(), colours.toIntArray())
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var printable = 0
        val n = minOf(bytes.size, 64)
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x09..0x0D || b in 0x20..0x7E) printable++
        }
        return printable == n
    }
}
