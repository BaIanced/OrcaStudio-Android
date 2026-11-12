package app.orcaandroid.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts mesh formats the slicer core doesn't read (glTF / GLB, PLY, OFF) to a 3MF it does. Colors (material
 * colors, textures sampled at each triangle's center, vertex colors) are snapped to the nearest filament slot and
 * written as one part per slot with an extruder, so a colored model opens as a multi-material object.
 */
object MeshConvert {
    val EXTENSIONS = setOf("glb", "gltf", "ply", "off")

    /** Triangles as 9 floats each (mm, Z up) and a per-triangle 0xRRGGBB color, or -1 when the file has none. */
    class Tris(val xyz: FloatArray, val rgb: IntArray)

    /** The converted 3MF next to [file], or null when it can't be read (compressed glTF, a broken file). */
    fun convert(file: File, slotColors: List<Int?>): File? = runCatching {
        val tris = when (file.extension.lowercase()) {
            "glb", "gltf" -> Gltf(file).read()
            "ply" -> readPly(file)
            "off" -> readOff(file)
            else -> null
        }?.takeIf { it.rgb.isNotEmpty() } ?: return null
        write3mf(File(file.parentFile, file.nameWithoutExtension + ".3mf"), file.nameWithoutExtension, tris, slotColors)
    }.getOrNull()

    /**
     * A glTF `uri` as a file in the model's folder. Refuses absolute paths, URI schemes (other than the `data:` the
     * callers handle first) and anything that resolves outside the model's parent folder.
     */
    internal fun resolveSibling(model: File, uri: String): File {
        val decoded = java.net.URLDecoder.decode(uri, "UTF-8")
        require(!decoded.startsWith("/") && !decoded.startsWith("\\") && !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(decoded)) { "Bad glTF uri" }
        val root = (model.absoluteFile.parentFile ?: error("no parent")).canonicalFile
        val out = File(root, decoded).canonicalFile
        require(out.path.startsWith(root.path + File.separator)) { "glTF uri outside the model folder" }
        return out
    }

    fun parseColor(c: String?): Int? = c?.trim()?.removePrefix("#")?.takeIf { it.length >= 6 }?.take(6)?.toIntOrNull(16)

    // ---- 3MF writer

    private fun slotOf(rgb: Int, slots: List<Int?>): Int {
        if (rgb < 0) return 1
        var best = 1
        var bestD = Long.MAX_VALUE
        slots.forEachIndexed { i, c ->
            if (c == null) return@forEachIndexed
            val dr = ((rgb shr 16) and 255) - ((c shr 16) and 255)
            val dg = ((rgb shr 8) and 255) - ((c shr 8) and 255)
            val db = (rgb and 255) - (c and 255)
            val d = (dr * dr * 2 + dg * dg * 4 + db * db * 3).toLong() // weighted: the eye is most sensitive to green
            if (d < bestD) { bestD = d; best = i + 1 }
        }
        return best
    }

    private fun write3mf(out: File, name: String, t: Tris, slots: List<Int?>): File {
        val n = t.rgb.size
        val colored = slots.count { it != null } > 1 && t.rgb.any { it >= 0 }
        val slotFor = IntArray(n) { if (colored) slotOf(t.rgb[it], slots) else 1 }
        val order = (0 until n).sortedBy { slotFor[it] }
        val vertIndex = HashMap<Pair<Int, Triple<Int, Int, Int>>, Int>() // (slot, position) -> index within that slot's mesh
        val verts = LinkedHashMap<Int, StringBuilder>()
        val faces = LinkedHashMap<Int, StringBuilder>()
        val vcount = HashMap<Int, Int>()
        for (tri in order) {
            val slot = slotFor[tri]
            val vb = verts.getOrPut(slot) { StringBuilder() }
            val ids = IntArray(3)
            for (k in 0 until 3) {
                val x = t.xyz[tri * 9 + k * 3]; val y = t.xyz[tri * 9 + k * 3 + 1]; val z = t.xyz[tri * 9 + k * 3 + 2]
                val key = Pair(slot, Triple(x.toBits(), y.toBits(), z.toBits()))
                ids[k] = vertIndex.getOrPut(key) { vb.append("<vertex x=\"$x\" y=\"$y\" z=\"$z\"/>"); vcount.merge(slot, 1, Int::plus)!! - 1 }
            }
            if (ids[0] == ids[1] || ids[1] == ids[2] || ids[0] == ids[2]) continue
            faces.getOrPut(slot) { StringBuilder() }.append("<triangle v1=\"${ids[0]}\" v2=\"${ids[1]}\" v3=\"${ids[2]}\"/>")
        }
        val safe = name.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        // One mesh object per filament slot (ids 1..n) and an assembly object whose components are the parts. Native
        // (bbs_3mf.cpp) only builds volumes and extruders this way, and only for 3MFs tagged as made by Orca (m_is_bbl_3mf).
        val used = faces.keys.toList()
        val asm = used.size + 1
        val res = StringBuilder()
        used.forEachIndexed { i, slot -> res.append("<object id=\"${i + 1}\" type=\"model\"><mesh><vertices>${verts[slot]}</vertices><triangles>${faces[slot]}</triangles></mesh></object>") }
        res.append("<object id=\"$asm\" type=\"model\"><components>")
        used.indices.forEach { res.append("<component objectid=\"${it + 1}\"/>") }
        res.append("</components></object>")
        val model = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">" +
            "<metadata name=\"Application\">OrcaSlicer-2.3.2</metadata>" +
            "<resources>$res</resources><build><item objectid=\"$asm\"/></build></model>"
        val cfg = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><config><object id=\"$asm\"><metadata key=\"name\" value=\"$safe\"/>")
        used.forEachIndexed { i, slot ->
            cfg.append("<part id=\"${i + 1}\" subtype=\"normal_part\"><metadata key=\"name\" value=\"$safe $slot\"/>")
            cfg.append("<metadata key=\"extruder\" value=\"$slot\"/></part>")
        }
        cfg.append("</object></config>")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(path: String, text: String) { z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>")
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>")
            put("3D/3dmodel.model", model)
            put("Metadata/model_settings.config", cfg.toString())
        }
        return out
    }

    // ---- OFF / PLY

    private fun readOff(file: File): Tris {
        val tokens = file.readText().lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.joinToString(" ").split(Regex("\\s+"))
        var p = 0
        if (tokens[p].endsWith("OFF")) p++
        val nv = tokens[p++].toInt(); val nf = tokens[p++].toInt(); p++
        val v = FloatArray(nv * 3) { tokens[p++].toFloat() }
        val xyz = ArrayList<Float>(); val rgb = ArrayList<Int>()
        repeat(nf) {
            val k = tokens[p++].toInt()
            val idx = IntArray(k) { tokens[p++].toInt() }
            var color = -1
            // Optional face color: 3 ints (0-255) or 4 with alpha; the rest of the face line is not marked, so only
            // take it when the face has exactly k + 3 / k + 4 tokens on its line (unknown here): skipped.
            fan(v, idx, xyz, rgb, color)
        }
        return Tris(xyz.toFloatArray(), rgb.toIntArray())
    }

    private fun fan(v: FloatArray, idx: IntArray, xyz: MutableList<Float>, rgb: MutableList<Int>, color: Int, vcol: IntArray? = null) {
        for (i in 1 until idx.size - 1) {
            for (j in intArrayOf(idx[0], idx[i], idx[i + 1])) for (c in 0 until 3) xyz.add(v[j * 3 + c])
            rgb.add(if (vcol != null) avg(vcol[idx[0]], vcol[idx[i]], vcol[idx[i + 1]]) else color)
        }
    }

    private fun avg(a: Int, b: Int, c: Int): Int {
        if (a < 0 || b < 0 || c < 0) return -1
        fun ch(s: Int) = (((a shr s) and 255) + ((b shr s) and 255) + ((c shr s) and 255)) / 3
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun readPly(file: File): Tris {
        val bytes = file.readBytes()
        val end = String(bytes, Charsets.ISO_8859_1).indexOf("end_header")
        require(end > 0)
        var body = end + "end_header".length
        if (bytes[body] == '\r'.code.toByte()) body++
        body++
        val header = String(bytes, 0, end, Charsets.ISO_8859_1).lines().map { it.trim().split(Regex("\\s+")) }
        val format = header.first { it[0] == "format" }[1]
        class Elem(val name: String, val count: Int, val props: MutableList<List<String>> = mutableListOf())
        val elems = mutableListOf<Elem>()
        for (h in header) when (h[0]) { "element" -> elems += Elem(h[1], h[2].toInt()); "property" -> elems.last().props += h }
        val ascii = format == "ascii"
        require(ascii || format == "binary_little_endian") { "PLY format" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).also { it.position(body) }
        val words = if (ascii) String(bytes, body, bytes.size - body, Charsets.ISO_8859_1).split(Regex("\\s+")).filter { it.isNotEmpty() } else emptyList()
        var wi = 0
        fun size(t: String) = when (t) { "char", "uchar", "int8", "uint8" -> 1; "short", "ushort", "int16", "uint16" -> 2; "double", "float64" -> 8; else -> 4 }
        fun read(t: String): Double {
            if (ascii) return words[wi++].toDouble()
            return when (t) {
                "char", "int8" -> buf.get().toDouble(); "uchar", "uint8" -> (buf.get().toInt() and 255).toDouble()
                "short", "int16" -> buf.short.toDouble(); "ushort", "uint16" -> (buf.short.toInt() and 0xffff).toDouble()
                "int", "int32" -> buf.int.toDouble(); "uint", "uint32" -> (buf.int.toLong() and 0xffffffffL).toDouble()
                "double", "float64" -> buf.double; else -> buf.float.toDouble()
            }
        }
        var v = FloatArray(0); var vcol: IntArray? = null
        val xyz = ArrayList<Float>(); val rgb = ArrayList<Int>()
        for (e in elems) {
            if (e.name == "vertex") {
                v = FloatArray(e.count * 3)
                val hasColor = e.props.any { it.last() == "red" }
                if (hasColor) vcol = IntArray(e.count) { -1 }
                for (i in 0 until e.count) {
                    var r = 0; var g = 0; var b = 0
                    for (p in e.props) {
                        val x = read(p[1])
                        when (p.last()) {
                            "x" -> v[i * 3] = x.toFloat(); "y" -> v[i * 3 + 1] = x.toFloat(); "z" -> v[i * 3 + 2] = x.toFloat()
                            "red" -> r = x.toInt(); "green" -> g = x.toInt(); "blue" -> b = x.toInt()
                        }
                    }
                    vcol?.set(i, (r shl 16) or (g shl 8) or b)
                }
            } else if (e.name == "face") {
                for (i in 0 until e.count) {
                    var idx = IntArray(0)
                    for (p in e.props) {
                        if (p[1] == "list") {
                            val k = read(p[2]).toInt()
                            val list = IntArray(k) { read(p[3]).toInt() }
                            if (p.last() == "vertex_indices" || p.last() == "vertex_index") idx = list
                        } else read(p[1])
                    }
                    if (idx.size >= 3) fan(v, idx, xyz, rgb, -1, vcol)
                }
            } else {
                for (i in 0 until e.count) for (p in e.props) if (p[1] == "list") { val k = read(p[2]).toInt(); repeat(k) { read(p[3]) } } else read(p[1])
            }
        }
        return Tris(xyz.toFloatArray(), rgb.toIntArray())
    }

    // ---- glTF / GLB

    private class Gltf(val file: File) {
        lateinit var json: JSONObject
        var glbBin: ByteArray? = null
        val buffers = HashMap<Int, ByteArray>()
        val images = HashMap<Int, Bitmap?>()

        fun read(): Tris {
            val raw = file.readBytes()
            if (raw.size > 12 && raw[0] == 'g'.code.toByte() && raw[1] == 'l'.code.toByte()) {
                val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                var p = 12
                var text: String? = null
                while (p + 8 <= raw.size) {
                    val len = b.getInt(p); val type = b.getInt(p + 4)
                    if (type == 0x4E4F534A) text = String(raw, p + 8, len) else if (type == 0x004E4942 && glbBin == null) glbBin = raw.copyOfRange(p + 8, p + 8 + len)
                    p += 8 + len
                }
                json = JSONObject(text ?: error("no JSON chunk"))
            } else json = JSONObject(String(raw))
            val used = (json.optJSONArray("extensionsRequired") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } }
            require(used.none { it.contains("draco") || it.contains("meshopt") }) { "compressed glTF" }
            val xyz = ArrayList<Float>(); val rgb = ArrayList<Int>()
            val nodes = json.optJSONArray("nodes") ?: JSONArray()
            val scenes = json.optJSONArray("scenes")
            val roots = scenes?.optJSONObject(json.optInt("scene", 0))?.optJSONArray("nodes")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
                ?: (0 until nodes.length()).toList()
            fun walk(i: Int, parent: FloatArray) {
                val node = nodes.getJSONObject(i)
                val m = mul(parent, local(node))
                if (node.has("mesh")) mesh(json.getJSONArray("meshes").getJSONObject(node.getInt("mesh")), m, xyz, rgb)
                node.optJSONArray("children")?.let { c -> for (k in 0 until c.length()) walk(c.getInt(k), m) }
            }
            roots.forEach { walk(it, IDENTITY) }
            if (nodes.length() == 0) json.optJSONArray("meshes")?.let { ms -> for (i in 0 until ms.length()) mesh(ms.getJSONObject(i), IDENTITY, xyz, rgb) }
            // glTF is Y up in meters: Y up -> Z up, and a model under 1 unit tall is taken as meters (-> mm).
            val a = xyz.toFloatArray()
            var i = 0
            while (i < a.size) { val y = a[i + 1]; val z = a[i + 2]; a[i + 1] = -z; a[i + 2] = y; i += 3 }
            var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
            for (k in a.indices) { min = minOf(min, a[k]); max = maxOf(max, a[k]) }
            val scale = if (a.isNotEmpty() && max - min < 1f) 1000f else 1f
            if (scale != 1f) for (k in a.indices) a[k] *= scale
            return Tris(a, rgb.toIntArray())
        }

        fun local(node: JSONObject): FloatArray {
            node.optJSONArray("matrix")?.let { a -> return FloatArray(16) { a.getDouble(it).toFloat() } }
            val t = node.optJSONArray("translation")?.let { a -> floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat()) } ?: floatArrayOf(0f, 0f, 0f)
            val q = node.optJSONArray("rotation")?.let { a -> FloatArray(4) { a.getDouble(it).toFloat() } } ?: floatArrayOf(0f, 0f, 0f, 1f)
            val s = node.optJSONArray("scale")?.let { a -> floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat()) } ?: floatArrayOf(1f, 1f, 1f)
            val (x, y, z, w) = q.toList()
            // Column-major, M = T * R * S.
            return floatArrayOf(
                (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0f,
                (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0f,
                (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0f,
                t[0], t[1], t[2], 1f)
        }

        fun mul(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(16)
            for (c in 0 until 4) for (row in 0 until 4) { var s = 0f; for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]; r[c * 4 + row] = s }
            return r
        }

        fun buffer(i: Int): ByteArray = buffers.getOrPut(i) {
            val b = json.getJSONArray("buffers").getJSONObject(i)
            val uri = b.optString("uri")
            when {
                uri.isEmpty() -> glbBin ?: error("no buffer")
                uri.startsWith("data:") -> Base64.decode(uri.substringAfter(','), Base64.DEFAULT)
                else -> resolveSibling(file, uri).readBytes()
            }
        }

        fun viewBytes(view: Int): Triple<ByteArray, Int, Int> {
            val v = json.getJSONArray("bufferViews").getJSONObject(view)
            return Triple(buffer(v.getInt("buffer")), v.optInt("byteOffset", 0), v.optInt("byteStride", 0))
        }

        /** An accessor as floats (count x components); integer types are read as their value. */
        fun accessor(i: Int): Pair<FloatArray, Int> {
            val a = json.getJSONArray("accessors").getJSONObject(i)
            require(!a.has("sparse")) { "sparse accessor" }
            val comps = when (a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> error("accessor type") }
            val count = a.getInt("count"); val ct = a.getInt("componentType"); val norm = a.optBoolean("normalized")
            val out = FloatArray(count * comps)
            if (!a.has("bufferView")) return out to comps
            val (data, off, stride0) = viewBytes(a.getInt("bufferView"))
            val csize = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val stride = if (stride0 > 0) stride0 else csize * comps
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val base = off + a.optInt("byteOffset", 0)
            for (e in 0 until count) for (c in 0 until comps) {
                val p = base + e * stride + c * csize
                out[e * comps + c] = when (ct) {
                    5126 -> bb.getFloat(p)
                    5121 -> (bb.get(p).toInt() and 255).let { if (norm) it / 255f else it.toFloat() }
                    5123 -> (bb.getShort(p).toInt() and 0xffff).let { if (norm) it / 65535f else it.toFloat() }
                    5125 -> (bb.getInt(p).toLong() and 0xffffffffL).toFloat()
                    5120 -> bb.get(p).toFloat(); 5122 -> bb.getShort(p).toFloat()
                    else -> error("component type")
                }
            }
            return out to comps
        }

        fun image(i: Int): Bitmap? = images.getOrPut(i) {
            val im = json.getJSONArray("images").getJSONObject(i)
            val bytes = when {
                im.has("bufferView") -> viewBytes(im.getInt("bufferView")).let { (d, o, _) ->
                    val v = json.getJSONArray("bufferViews").getJSONObject(im.getInt("bufferView")); d.copyOfRange(o, o + v.getInt("byteLength")) }
                im.optString("uri").startsWith("data:") -> Base64.decode(im.getString("uri").substringAfter(','), Base64.DEFAULT)
                im.has("uri") -> resolveSibling(file, im.getString("uri")).readBytes()
                else -> return@getOrPut null
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        fun mesh(mesh: JSONObject, m: FloatArray, xyz: MutableList<Float>, rgb: MutableList<Int>) {
            val prims = mesh.getJSONArray("primitives")
            for (pi in 0 until prims.length()) {
                val p = prims.getJSONObject(pi)
                val mode = p.optInt("mode", 4)
                if (mode !in 4..6) continue
                val attrs = p.getJSONObject("attributes")
                val (pos, _) = accessor(attrs.getInt("POSITION"))
                val nv = pos.size / 3
                val idx: IntArray = if (p.has("indices")) accessor(p.getInt("indices")).first.let { f -> IntArray(f.size) { f[it].toInt() } } else IntArray(nv) { it }
                val tri = ArrayList<Int>()
                when (mode) {
                    4 -> for (i in 0 until idx.size / 3 * 3) tri.add(idx[i])
                    5 -> for (i in 0 until idx.size - 2) { if (i % 2 == 0) { tri.add(idx[i]); tri.add(idx[i + 1]); tri.add(idx[i + 2]) } else { tri.add(idx[i + 1]); tri.add(idx[i]); tri.add(idx[i + 2]) } }
                    6 -> for (i in 1 until idx.size - 1) { tri.add(idx[0]); tri.add(idx[i]); tri.add(idx[i + 1]) }
                }
                val mat = if (p.has("material")) json.getJSONArray("materials").getJSONObject(p.getInt("material")) else null
                val pbr = mat?.optJSONObject("pbrMetallicRoughness")
                val factor = pbr?.optJSONArray("baseColorFactor")?.let { a -> DoubleArray(3) { a.getDouble(it) } } ?: doubleArrayOf(1.0, 1.0, 1.0)
                val texInfo = pbr?.optJSONObject("baseColorTexture")
                val bmp = texInfo?.let { t ->
                    val src = json.optJSONArray("textures")?.optJSONObject(t.getInt("index"))?.optInt("source", -1) ?: -1
                    if (src >= 0) image(src) else null
                }
                val uv = if (bmp != null) attrs.optInt("TEXCOORD_${texInfo.optInt("texCoord", 0)}", -1).takeIf { it >= 0 }?.let { accessor(it).first } else null
                val vcolor = if (attrs.has("COLOR_0")) accessor(attrs.getInt("COLOR_0")) else null
                for (t in 0 until tri.size / 3) {
                    val vs = intArrayOf(tri[t * 3], tri[t * 3 + 1], tri[t * 3 + 2])
                    if (vs.any { it >= nv }) continue
                    for (v in vs) {
                        val x = pos[v * 3]; val y = pos[v * 3 + 1]; val z = pos[v * 3 + 2]
                        xyz.add(m[0] * x + m[4] * y + m[8] * z + m[12]); xyz.add(m[1] * x + m[5] * y + m[9] * z + m[13]); xyz.add(m[2] * x + m[6] * y + m[10] * z + m[14])
                    }
                    var r = factor[0]; var g = factor[1]; var b = factor[2]
                    if (vcolor != null) {
                        val (c, n) = vcolor
                        r *= vs.sumOf { c[it * n].toDouble() } / 3; g *= vs.sumOf { c[it * n + 1].toDouble() } / 3; b *= vs.sumOf { c[it * n + 2].toDouble() } / 3
                    }
                    if (bmp != null && uv != null) {
                        val u = vs.sumOf { uv[it * 2].toDouble() } / 3; val w = vs.sumOf { uv[it * 2 + 1].toDouble() } / 3
                        val px = bmp.getPixel(((u - Math.floor(u)) * bmp.width).toInt().coerceIn(0, bmp.width - 1), ((w - Math.floor(w)) * bmp.height).toInt().coerceIn(0, bmp.height - 1))
                        r *= srgbToLinear(android.graphics.Color.red(px)); g *= srgbToLinear(android.graphics.Color.green(px)); b *= srgbToLinear(android.graphics.Color.blue(px))
                    }
                    // glTF factors are linear: back to sRGB, which is what the filament colors are.
                    fun enc(x: Double) = (linearToSrgb(x.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt()
                    rgb.add((enc(r) shl 16) or (enc(g) shl 8) or enc(b))
                }
            }
        }

        fun srgbToLinear(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        fun linearToSrgb(x: Double): Double = if (x <= 0.0031308) x * 12.92 else 1.055 * Math.pow(x, 1 / 2.4) - 0.055

        companion object { val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f) }
    }
}
