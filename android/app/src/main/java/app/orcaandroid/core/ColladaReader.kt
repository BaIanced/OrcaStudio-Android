package app.orcaandroid.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal COLLADA (.dae) reader: converts the visual scene's static triangle
 * geometry to the app's neutral mesh form - millimetres, Z up, right-handed,
 * one diffuse colour per triangle (0xRRGGBB, -1 = none).
 *
 * Supports COLLADA 1.4 and 1.5 plain XML: <asset> unit scale and up_axis,
 * float_array sources behind <vertices>/POSITION, <triangles>, <polylist>
 * (via <vcount>) and <polygons> primitives (fanned), per-primitive material
 * symbols resolved through the instancing node's <bind_material> to the
 * material's effect diffuse colour, and node transforms (<matrix>,
 * <translate>, <rotate>, <scale>, nested nodes, in document order).
 * Skinned meshes (<instance_controller>) are skipped, not failed on.
 * Textures, normals, UVs, lights, cameras and animation are ignored.
 *
 * Parsing uses javax.xml.parsers DOM (present on both the JVM and the
 * Android runtime, unlike javax.xml.stream or XmlPullParser) with DTDs and
 * external entities disabled (XXE / entity-expansion safe).
 *
 * Throws IllegalArgumentException on anything malformed or unsupported.
 */
object ColladaReader {

    private class Prim(val indices: IntArray, val symbol: String?)

    private class Geometry(val positions: FloatArray, val prims: List<Prim>)

    /** Row-major 4x4, column-vector convention: p' = M * p. */
    private fun identity(): FloatArray =
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[r * 4 + k] * b[k * 4 + c]
                out[r * 4 + c] = s
            }
        }
        return out
    }

    private fun apply(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        return floatArrayOf(
            m[0] * x + m[1] * y + m[2] * z + m[3],
            m[4] * x + m[5] * y + m[6] * z + m[7],
            m[8] * x + m[9] * y + m[10] * z + m[11],
        )
    }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException("dae: $msg")

    // ---------- DOM helpers (namespace-unaware: plain tag names) ----------

    private fun Element.children(tag: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tag) out.add(n)
        }
        return out
    }

    private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

    private fun Element.descendant(tag: String): Element? =
        getElementsByTagName(tag).item(0) as? Element

    private fun Element.idAttr(): String = getAttribute("id")

    private fun refId(ref: String): String = ref.removePrefix("#")

    private fun floatsOf(text: String, what: String): FloatArray {
        val tokens = text.trim().split(WS).filter { it.isNotEmpty() }
        val out = FloatArray(tokens.size)
        for (i in tokens.indices) {
            out[i] = tokens[i].toFloatOrNull() ?: fail("bad number '${tokens[i]}' in $what")
        }
        return out
    }

    private fun intsOf(text: String, what: String): IntArray {
        val tokens = text.trim().split(WS).filter { it.isNotEmpty() }
        val out = IntArray(tokens.size)
        for (i in tokens.indices) {
            out[i] = tokens[i].toIntOrNull() ?: fail("bad index '${tokens[i]}' in $what")
        }
        return out
    }

    private val WS = Regex("\\s+")

    // ---------- document sections ----------

    /** Raw float_array contents of a <source>, honouring its declared count. */
    private fun sourceFloats(source: Element, fileBytes: Long): FloatArray {
        val fa = source.child("float_array") ?: fail("source without float_array")
        val declared = fa.getAttribute("count").toIntOrNull()
            ?: fail("float_array without count")
        // Every float needs at least one digit plus a separator in the text:
        // a declared count larger than the file itself is a lie.
        if (declared < 0 || declared > fileBytes) fail("float_array count $declared impossible")
        val all = floatsOf(fa.textContent, "float_array")
        if (all.size < declared) fail("float_array truncated: $declared declared, ${all.size} present")
        return all.copyOf(declared)
    }

    /** XYZ positions of a <source>, deinterleaved through its accessor. */
    private fun sourcePositions(source: Element, fileBytes: Long): FloatArray {
        val raw = sourceFloats(source, fileBytes)
        val accessor = source.child("technique_common")?.child("accessor")
            ?: return raw.copyOf(raw.size - raw.size % 3)
        val stride = accessor.getAttribute("stride").toIntOrNull() ?: 3
        val offset = accessor.getAttribute("offset").toIntOrNull() ?: 0
        val count = accessor.getAttribute("count").toIntOrNull()
            ?: fail("accessor without count")
        if (stride < 3) fail("accessor stride $stride < 3")
        if (count < 0 || offset < 0 || offset + count * stride > raw.size) {
            fail("accessor range outside float_array")
        }
        val out = FloatArray(count * 3)
        for (i in 0 until count) {
            val base = offset + i * stride
            out[i * 3] = raw[base]
            out[i * 3 + 1] = raw[base + 1]
            out[i * 3 + 2] = raw[base + 2]
        }
        return out
    }

    private fun parseGeometry(geomEl: Element, fileBytes: Long): Geometry {
        val sources = HashMap<String, Element>()
        val verticesById = HashMap<String, String>() // vertices id -> position source id
        val prims = ArrayList<Prim>()
        var positions: FloatArray? = null
        var positionSourceId: String? = null

        for (mesh in geomEl.children("mesh")) {
            for (el in mesh.children("source")) sources[el.idAttr()] = el
            for (v in mesh.children("vertices")) {
                val pos = v.children("input").firstOrNull { it.getAttribute("semantic") == "POSITION" }
                    ?: fail("vertices without POSITION input")
                verticesById[v.idAttr()] = refId(pos.getAttribute("source"))
            }
            val primEls = ArrayList<Element>()
            primEls.addAll(mesh.children("triangles"))
            primEls.addAll(mesh.children("polylist"))
            primEls.addAll(mesh.children("polygons"))
            for (prim in primEls) {
                val (parsed, srcId, srcPositions) =
                    parsePrimitive(prim, sources, verticesById, fileBytes)
                if (positionSourceId == null) {
                    positionSourceId = srcId
                    positions = srcPositions
                } else if (positionSourceId != srcId) {
                    fail("geometry with multiple position sources is unsupported")
                }
                prims.add(parsed)
            }
        }
        val pos = positions ?: fail("geometry has no vertices")
        return Geometry(pos, prims)
    }

    /** Returns the primitive, its position source id, and that source's positions. */
    private fun parsePrimitive(
        prim: Element,
        sources: Map<String, Element>,
        verticesById: Map<String, String>,
        fileBytes: Long,
    ): Triple<Prim, String, FloatArray> {
        val inputs = prim.children("input")
        if (inputs.isEmpty()) fail("${prim.tagName} without inputs")
        var stride = 0
        var vertexOffset = -1
        var vertexRef: String? = null
        for (input in inputs) {
            val off = input.getAttribute("offset").toIntOrNull()
                ?: fail("input without offset")
            if (off < 0) fail("negative input offset")
            if (off + 1 > stride) stride = off + 1
            val semantic = input.getAttribute("semantic")
            if (semantic == "VERTEX" || semantic == "POSITION") {
                vertexOffset = off
                vertexRef = refId(input.getAttribute("source"))
            }
        }
        val vRef = vertexRef ?: fail("${prim.tagName} without VERTEX input")
        val sourceId = verticesById[vRef] ?: vRef // vertices id, or a source id directly
        val source = sources[sourceId] ?: fail("unknown source #$sourceId")
        val positions = sourcePositions(source, fileBytes)
        val vcount = positions.size / 3

        val pEls = prim.children("p")
        if (pEls.isEmpty()) fail("${prim.tagName} without <p>")
        val symbol = prim.getAttribute("material").ifEmpty { null }
        val tris = ArrayList<Int>()

        fun cornerIndices(p: IntArray): IntArray {
            if (p.size % stride != 0) fail("<p> size ${p.size} not a multiple of stride $stride")
            val corners = p.size / stride
            val out = IntArray(corners)
            for (i in 0 until corners) {
                val vi = p[i * stride + vertexOffset]
                if (vi < 0 || vi >= vcount) fail("vertex index $vi out of range (0..<$vcount)")
                out[i] = vi
            }
            return out
        }

        fun fan(poly: IntArray) {
            if (poly.size < 3) fail("polygon with ${poly.size} corners")
            for (i in 1 until poly.size - 1) {
                tris.add(poly[0]); tris.add(poly[i]); tris.add(poly[i + 1])
            }
        }

        when (prim.tagName) {
            "triangles" -> {
                val corners = cornerIndices(intsOf(pEls[0].textContent, "<p>"))
                val countAttr = prim.getAttribute("count").toIntOrNull()
                if (countAttr != null && countAttr * 3 != corners.size) {
                    fail("triangles count $countAttr but ${corners.size} corners")
                }
                if (corners.size % 3 != 0) fail("triangles corners ${corners.size} not a multiple of 3")
                for (c in corners) tris.add(c)
            }
            "polylist" -> {
                val vc = prim.child("vcount") ?: fail("polylist without vcount")
                val counts = intsOf(vc.textContent, "vcount")
                val corners = cornerIndices(intsOf(pEls[0].textContent, "<p>"))
                if (counts.sum() != corners.size) {
                    fail("vcount sums to ${counts.sum()} but ${corners.size} corners present")
                }
                var at = 0
                for (n in counts) {
                    fan(corners.copyOfRange(at, at + n))
                    at += n
                }
            }
            "polygons" -> {
                for (p in pEls) fan(cornerIndices(intsOf(p.textContent, "<p>")))
            }
            else -> fail("unsupported primitive ${prim.tagName}")
        }
        return Triple(Prim(tris.toIntArray(), symbol), sourceId, positions)
    }

    /** Effect id -> diffuse 0xRRGGBB, or null (texture / no diffuse colour). */
    private fun effectDiffuse(effect: Element): Int? {
        val diffuse = effect.child("profile_COMMON")
            ?.descendant("diffuse") ?: return null
        val colorEl = diffuse.child("color") ?: return null // <texture> etc: no colour
        val rgba = floatsOf(colorEl.textContent, "diffuse colour")
        if (rgba.size < 3) fail("diffuse colour with ${rgba.size} components")
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        return (ch(rgba[0]) shl 16) or (ch(rgba[1]) shl 8) or ch(rgba[2])
    }

    private fun nodeLocalMatrix(node: Element): FloatArray {
        var local = identity()
        val nodes = node.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n !is Element) continue
            val m = when (n.tagName) {
                "matrix" -> {
                    val v = floatsOf(n.textContent, "matrix")
                    if (v.size != 16) fail("matrix with ${v.size} values")
                    v
                }
                "translate" -> {
                    val v = floatsOf(n.textContent, "translate")
                    if (v.size != 3) fail("translate with ${v.size} values")
                    identity().also { it[3] = v[0]; it[7] = v[1]; it[11] = v[2] }
                }
                "scale" -> {
                    val v = floatsOf(n.textContent, "scale")
                    if (v.size != 3) fail("scale with ${v.size} values")
                    identity().also { it[0] = v[0]; it[5] = v[1]; it[10] = v[2] }
                }
                "rotate" -> {
                    val v = floatsOf(n.textContent, "rotate")
                    if (v.size != 4) fail("rotate with ${v.size} values")
                    rotation(v[0], v[1], v[2], v[3])
                }
                else -> null // lookat/skew/unknown: not supported, contributes nothing
            }
            if (m != null) local = mul(local, m)
        }
        return local
    }

    private fun rotation(ax: Float, ay: Float, az: Float, degrees: Float): FloatArray {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-9f) fail("rotate with zero axis")
        val a = ax / len; val b = ay / len; val c = az / len
        val rad = Math.toRadians(degrees.toDouble())
        val cosT = cos(rad).toFloat(); val sinT = sin(rad).toFloat(); val t = 1f - cosT
        return floatArrayOf(
            a * a * t + cosT, a * b * t - c * sinT, a * c * t + b * sinT, 0f,
            a * b * t + c * sinT, b * b * t + cosT, b * c * t - a * sinT, 0f,
            a * c * t - b * sinT, b * c * t + a * sinT, c * c * t + cosT, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    // ---------- main entry ----------

    fun read(file: File): Pair<FloatArray, IntArray> {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            throw IllegalArgumentException("dae: cannot read file", e)
        }
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("dae: not well-formed xml", e)
        }
        val root = doc.documentElement ?: fail("empty document")
        if (root.tagName != "COLLADA") fail("root element is ${root.tagName}")

        // asset: unit scale + up axis
        var mmPerUnit = 1000f // COLLADA's own unit is metres when <unit> is absent
        var upAxis = "Y_UP"
        root.child("asset")?.let { asset ->
            asset.child("unit")?.let { unit ->
                val meterAttr = unit.getAttribute("meter")
                val meter = if (meterAttr.isEmpty()) 1f
                    else meterAttr.toFloatOrNull() ?: fail("unit with non-numeric meter")
                mmPerUnit = meter * 1000f
            }
            asset.child("up_axis")?.let { upAxis = it.textContent.trim().ifEmpty { "Y_UP" } }
        }

        // effects and materials
        val effectColors = HashMap<String, Int?>()
        root.child("library_effects")?.children("effect")?.forEach { effect ->
            effectColors[effect.idAttr()] = effectDiffuse(effect)
        }
        val materialEffect = HashMap<String, String>()
        root.child("library_materials")?.children("material")?.forEach { mat ->
            val ie = mat.child("instance_effect") ?: return@forEach
            materialEffect[mat.idAttr()] = refId(ie.getAttribute("url"))
        }

        // geometries
        val geometries = HashMap<String, Geometry>()
        root.child("library_geometries")?.children("geometry")?.forEach { geom ->
            geometries[geom.idAttr()] = parseGeometry(geom, bytes.size.toLong())
        }
        if (geometries.isEmpty()) fail("no geometry")

        val triangles = ArrayList<Float>()
        val colours = ArrayList<Int>()

        fun emit(geom: Geometry, world: FloatArray, bind: Map<String, String>) {
            for (prim in geom.prims) {
                val colour = prim.symbol
                    ?.let { bind[it] }
                    ?.let { materialEffect[it] }
                    ?.let { effectColors[it] }
                    ?: -1
                var i = 0
                while (i < prim.indices.size) {
                    repeat(3) {
                        val vi = prim.indices[i++]
                        val p = apply(
                            world,
                            geom.positions[vi * 3],
                            geom.positions[vi * 3 + 1],
                            geom.positions[vi * 3 + 2],
                        )
                        val (x, y, z) = when (upAxis) {
                            "Z_UP" -> Triple(p[0], p[1], p[2])
                            "X_UP" -> Triple(-p[2], p[1], p[0])
                            else -> Triple(p[0], -p[2], p[1]) // Y_UP (the default)
                        }
                        triangles.add(x * mmPerUnit)
                        triangles.add(y * mmPerUnit)
                        triangles.add(z * mmPerUnit)
                    }
                    colours.add(colour)
                }
            }
        }

        // visual scenes: walk nodes, instancing geometry with world matrices
        var sawScene = false
        fun walkNode(node: Element, parentWorld: FloatArray, nodesById: Map<String, Element>, depth: Int) {
            if (depth > 32) fail("node nesting too deep")
            val world = mul(parentWorld, nodeLocalMatrix(node))
            for (childEl in node.children("instance_geometry")) {
                val geom = geometries[refId(childEl.getAttribute("url"))]
                    ?: fail("instance of unknown geometry")
                val bind = HashMap<String, String>()
                childEl.child("bind_material")?.child("technique_common")
                    ?.children("instance_material")
                    ?.forEach { im ->
                        bind[im.getAttribute("symbol")] = refId(im.getAttribute("target"))
                    }
                emit(geom, world, bind)
            }
            // instance_controller (skinned): skipped on purpose.
            for (childEl in node.children("instance_node")) {
                val target = nodesById[refId(childEl.getAttribute("url"))]
                    ?: fail("instance_node of unknown node")
                walkNode(target, world, nodesById, depth + 1)
            }
            for (childEl in node.children("node")) {
                walkNode(childEl, world, nodesById, depth + 1)
            }
        }

        val sceneLib = root.child("library_visual_scenes")
        if (sceneLib != null) {
            sawScene = true
            val nodesById = HashMap<String, Element>()
            fun collect(n: Element) {
                if (n.tagName == "node" && n.idAttr().isNotEmpty()) nodesById[n.idAttr()] = n
                val kids = n.childNodes
                for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { collect(it) }
            }
            collect(sceneLib)
            for (scene in sceneLib.children("visual_scene")) {
                for (node in scene.children("node")) {
                    walkNode(node, identity(), nodesById, 0)
                }
            }
        }
        if (!sawScene) {
            // No scene at all: export the raw geometries, untransformed.
            for (geom in geometries.values) emit(geom, identity(), emptyMap())
        }
        if (colours.isEmpty()) fail("no mesh geometry in scene")

        return Pair(triangles.toFloatArray(), colours.toIntArray())
    }
}
