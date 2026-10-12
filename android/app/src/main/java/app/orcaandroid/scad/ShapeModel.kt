package app.orcaandroid.scad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Three numbers (mm or degrees). */
data class D3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    operator fun get(i: Int) = when (i) { 0 -> x; 1 -> y; else -> z }
    fun with(i: Int, v: Double) = when (i) { 0 -> copy(x = v); 1 -> copy(y = v); else -> copy(z = v) }
    operator fun plus(o: D3) = D3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: D3) = D3(x - o.x, y - o.y, z - o.z)
    fun json() = JSONArray().put(x).put(y).put(z)

    companion object {
        fun of(a: JSONArray?, def: D3 = D3()) = if (a == null || a.length() < 3) def else D3(a.optDouble(0), a.optDouble(1), a.optDouble(2))
    }
}

/** Axis-aligned bounds of a shape in its parent's frame. */
data class Bounds(val min: D3, val max: D3) {
    val size get() = max - min
    val center get() = D3((min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2)
    fun union(o: Bounds) = Bounds(D3(min(min.x, o.min.x), min(min.y, o.min.y), min(min.z, o.min.z)),
        D3(max(max.x, o.max.x), max(max.y, o.max.y), max(max.z, o.max.z)))

    companion object {
        fun of(points: List<D3>) = Bounds(
            D3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }),
            D3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }),
        )
    }
}

/** Copies of a shape: in a row ([step] apart) or around a circle (about [center], over [angle] degrees). */
data class Repeat(
    val circular: Boolean = false,
    val count: Int = 3,
    val step: D3 = D3(25.0, 0.0, 0.0),
    val angle: Double = 360.0,
    val center: D3 = D3(),
) {
    fun json(): JSONObject = JSONObject().put("circular", circular).put("count", count).put("step", step.json())
        .put("angle", angle).put("center", center.json())

    /** Degrees between two circular copies: a full turn spreads them evenly, an arc ends on its last one. */
    val angleStep get() = if (angle % 360.0 == 0.0) angle / count else angle / max(1, count - 1)

    companion object {
        fun of(o: JSONObject?) = o?.let {
            Repeat(it.optBoolean("circular"), it.optInt("count", 3).coerceIn(1, 500), D3.of(it.optJSONArray("step")),
                it.optDouble("angle", 360.0), D3.of(it.optJSONArray("center")))
        }
    }
}

/**
 * One shape of a shape-builder design, Tinkercad style: a primitive sized by its bounding box, or a
 * group of shapes. [pos] is the centre of the bottom of the box (x, y) and its bottom (z), in the
 * parent's frame; rotation turns about the box centre (OpenSCAD order: X, then Y, then Z). A hole
 * cuts the solids of its own group (the design itself is the outermost group).
 */
data class Shape(
    val id: Int,
    val kind: Kind,
    val name: String,
    val size: D3,
    val pos: D3 = D3(),
    val rot: D3 = D3(),
    val hole: Boolean = false,
    val mirror: D3 = D3(),
    /** Kind specific: SIDES, TOP (cone top diameter / bottom), WALL / TUBE (mm), RADIUS (mm). */
    val extra: Map<String, Double> = emptyMap(),
    val text: String = "",
    /** A MESH shape's model file (absolute path; OpenSCAD import()). */
    val file: String = "",
    val repeat: Repeat? = null,
    /** The drawing a SKETCH shape extrudes (or revolves, extra REVOLVE = 1). */
    val sketch: Sketch? = null,
    /** Field ("size.x", "pos.z", "rot.y", "radius", ...) -> name of the [Dim] that drives it. */
    val bind: Map<String, String> = emptyMap(),
    val children: List<Shape> = emptyList(),
) {
    enum class Kind(val key: String) {
        BOX("box"), ROUNDED_BOX("rounded_box"), CYLINDER("cylinder"), PRISM("prism"), CONE("cone"), PYRAMID("pyramid"),
        SPHERE("sphere"), DOME("dome"), TORUS("torus"), TUBE("tube"), WEDGE("wedge"), TEXT("text"), SKETCH("sketch"), MESH("mesh"), GROUP("group");

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key } ?: BOX
        }
    }

    val isGroup get() = kind == Kind.GROUP

    fun x(key: String, def: Double) = extra[key] ?: def

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.key).put("name", name)
        .put("size", size.json()).put("pos", pos.json()).put("rot", rot.json())
        .apply {
            if (hole) put("hole", true)
            if (mirror != D3()) put("mirror", mirror.json())
            if (extra.isNotEmpty()) put("extra", JSONObject(extra))
            if (text.isNotEmpty()) put("text", text)
            if (file.isNotEmpty()) put("file", file)
            repeat?.let { put("repeat", it.json()) }
            sketch?.let { put("sketch", it.json()) }
            if (bind.isNotEmpty()) put("bind", JSONObject(bind))
            if (children.isNotEmpty()) put("children", JSONArray().apply { children.forEach { put(it.json()) } })
        }

    /** Every shape in this subtree, depth first. */
    fun all(): Sequence<Shape> = sequenceOf(this) + children.asSequence().flatMap { it.all() }

    companion object {
        const val SIDES = "sides"
        const val TOP = "top"
        const val WALL = "wall"
        const val TUBE = "tube"
        const val RADIUS = "radius"
        const val REVOLVE = "revolve"
        const val ANGLE = "angle"
        /** Rounded (or with CHAMFER = 1 chamfered) edges, mm; a sketch rounds its outline's corners. */
        const val EDGE = "edge"
        const val CHAMFER = "chamfer"
        /** Hollow with this wall (mm); OPEN = 1 leaves the top open. */
        const val SHELL = "shell"
        const val OPEN = "open"
        /** A MESH shape's own bounds (mm) as read from its file: min x / y / z, max x / y / z. */
        val MESH_BOUNDS = listOf("mx0", "my0", "mz0", "mx1", "my1", "mz1")

        /** Kinds whose edges can be rounded / chamfered, and kinds that can be hollowed. */
        val EDGED = setOf(Kind.BOX, Kind.CYLINDER, Kind.PRISM, Kind.CONE, Kind.PYRAMID, Kind.WEDGE, Kind.SKETCH)
        val SHELLED = setOf(Kind.BOX, Kind.ROUNDED_BOX, Kind.CYLINDER, Kind.PRISM, Kind.CONE, Kind.PYRAMID, Kind.SPHERE,
            Kind.DOME, Kind.WEDGE, Kind.SKETCH)

        fun of(o: JSONObject): Shape = Shape(
            id = o.optInt("id"),
            kind = Kind.of(o.optString("kind")),
            name = o.optString("name"),
            size = D3.of(o.optJSONArray("size"), D3(20.0, 20.0, 20.0)),
            pos = D3.of(o.optJSONArray("pos")),
            rot = D3.of(o.optJSONArray("rot")),
            hole = o.optBoolean("hole"),
            mirror = D3.of(o.optJSONArray("mirror")),
            extra = o.optJSONObject("extra")?.let { e -> e.keys().asSequence().associateWith { e.optDouble(it) } } ?: emptyMap(),
            text = o.optString("text"),
            file = o.optString("file"),
            repeat = Repeat.of(o.optJSONObject("repeat")),
            sketch = Sketch.of(o.optJSONObject("sketch")),
            bind = o.optJSONObject("bind")?.let { b -> b.keys().asSequence().associateWith { b.optString(it) } } ?: emptyMap(),
            children = o.optJSONArray("children")?.let { a -> (0 until a.length()).map { of(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

/** A named number that drives shape fields; it becomes a Customizer slider in the generated code. */
data class Dim(val name: String, val value: Double, val min: Double, val max: Double, val step: Double = 1.0) {
    fun json(): JSONObject = JSONObject().put("name", name).put("value", value).put("min", min).put("max", max).put("step", step)

    companion object {
        fun of(o: JSONObject) = Dim(o.optString("name"), o.optDouble("value"), o.optDouble("min", 0.0), o.optDouble("max", 100.0), o.optDouble("step", 1.0))

        /** An OpenSCAD name made from [label] that isn't [taken] yet ("Box 1 width" -> box_1_width). */
        fun ident(label: String, taken: (String) -> Boolean): String {
            val base = label.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                .let { if (it.isEmpty() || it[0].isDigit()) "d_$it" else it }
            var name = base
            var i = 2
            while (taken(name)) name = base + "_" + i++
            return name
        }

        /** A new slider for a length of [v] mm (or a position / angle when [signed]). */
        fun forLength(name: String, v: Double, signed: Boolean = false, angle: Boolean = false) =
            Dim(name, v, if (signed) minOf(v, -100.0) else 0.0, if (angle) 360.0 else maxOf(100.0, Math.ceil(v * 2 / 10) * 10), 1.0)
    }
}

/**
 * A design built from shapes, kept as JSON with the object (see [ScadDesign.shapes]) and turned into
 * OpenSCAD code ([toScad]) that the editor renders like any other design.
 */
data class ShapeDesign(val shapes: List<Shape> = emptyList(), val dims: List<Dim> = emptyList(), val nextId: Int = 1) {

    fun json(): JSONObject = JSONObject()
        .put("version", 1)
        .put("shapes", JSONArray().apply { shapes.forEach { put(it.json()) } })
        .put("dims", JSONArray().apply { dims.forEach { put(it.json()) } })
        .put("nextId", nextId)

    fun encode(): String = json().toString()

    fun find(id: Int): Shape? = shapes.asSequence().flatMap { it.all() }.firstOrNull { it.id == id }

    /** The top-level shape that holds [id] (itself when it is top level). */
    fun topOf(id: Int): Shape? = shapes.firstOrNull { top -> top.all().any { it.id == id } }

    /** Replaces the shape with [id] wherever it is. */
    fun update(id: Int, f: (Shape) -> Shape): ShapeDesign = copy(shapes = shapes.map { it.updated(id, f) })

    private fun Shape.updated(id: Int, f: (Shape) -> Shape): Shape =
        if (this.id == id) f(this) else if (children.isEmpty()) this else copy(children = children.map { it.updated(id, f) })

    fun dim(name: String) = dims.firstOrNull { it.name == name }

    /** [f] applied to every shape, groups' children included. */
    fun mapShapes(f: (Shape) -> Shape): ShapeDesign {
        fun m(s: Shape): Shape = f(if (s.children.isEmpty()) s else s.copy(children = s.children.map(::m)))
        return copy(shapes = shapes.map(::m))
    }

    /**
     * The design with the sliders [next]: sketches with a side length that follows a changed slider are
     * solved again, their boxes kept to the same scale.
     */
    fun withDims(next: List<Dim>): ShapeDesign {
        val changed = next.filter { n -> dim(n.name)?.value != n.value }.map { it.name }.toSet()
        val d = copy(dims = next)
        if (changed.isEmpty()) return d
        return d.mapShapes { s ->
            val sk = s.sketch
            if (sk == null || sk.cons.none { it.dim in changed }) s else fitSketch(s, sk.solve({ d.dim(it)?.value }), keepScale = true)
        }
    }

    /** The value a field has now: its dimension's value when bound. */
    fun value(s: Shape, field: String, raw: Double): Double = s.bind[field]?.let { dim(it)?.value } ?: raw

    /** Bounds of [s] in its parent's frame, copies included. */
    fun bounds(s: Shape): Bounds {
        val local: Bounds
        val height: Double
        if (s.isGroup) {
            val kids = s.children.map { bounds(it) }
            local = kids.reduceOrNull { a, b -> a.union(b) } ?: Bounds(D3(), D3())
            height = local.max.z
        } else {
            val sz = size(s)
            local = Bounds(D3(-sz.x / 2, -sz.y / 2, 0.0), D3(sz.x / 2, sz.y / 2, sz.z))
            height = sz.z
        }
        val r = rot(s)
        val p = pos(s)
        val pivot = D3(0.0, 0.0, height / 2)
        val corners = (0 until 8).map { i ->
            val c = D3(if (i and 1 == 0) local.min.x else local.max.x, if (i and 2 == 0) local.min.y else local.max.y,
                if (i and 4 == 0) local.min.z else local.max.z)
            rotate(c - pivot, r) + pivot + p
        }
        var b = Bounds.of(corners)
        s.repeat?.let { rep ->
            var all = b
            for (i in 1 until rep.count) {
                val copy = if (rep.circular) {
                    Bounds.of((0 until 8).map { k ->
                        val c = D3(if (k and 1 == 0) b.min.x else b.max.x, if (k and 2 == 0) b.min.y else b.max.y, if (k and 4 == 0) b.min.z else b.max.z)
                        rotate(c - rep.center, D3(0.0, 0.0, rep.angleStep * i)) + rep.center
                    })
                } else Bounds(b.min + D3(rep.step.x * i, rep.step.y * i, rep.step.z * i), b.max + D3(rep.step.x * i, rep.step.y * i, rep.step.z * i))
                all = all.union(copy)
            }
            b = all
        }
        return b
    }

    fun size(s: Shape) = D3(value(s, "size.x", s.size.x), value(s, "size.y", s.size.y), value(s, "size.z", s.size.z))
    fun pos(s: Shape) = D3(value(s, "pos.x", s.pos.x), value(s, "pos.y", s.pos.y), value(s, "pos.z", s.pos.z))
    fun rot(s: Shape) = D3(value(s, "rot.x", s.rot.x), value(s, "rot.y", s.rot.y), value(s, "rot.z", s.rot.z))

    // --- OpenSCAD -------------------------------------------------------------------------------

    /** The design as OpenSCAD code: dimensions as Customizer sliders, then one block per shape. */
    fun toScad(): String = buildString {
        appendLine("// Made with the shape builder. Edit the shapes, or use \"Edit as code\" to change this text.")
        if (dims.isNotEmpty()) {
            appendLine()
            appendLine("/* [Dimensions] */")
            dims.forEach { d -> appendLine("${d.name} = ${n(d.value)}; // [${n(d.min)}:${n(d.step)}:${n(d.max)}]") }
        }
        appendLine()
        appendLine("/* [Hidden] */")
        appendLine("\$fs = 0.4;")
        appendLine("\$fa = 4;")
        appendLine("// Sides for a round shape of diameter d: the shapes scale a 1 mm primitive, so \$fs alone would give too few.")
        appendLine("function sides(d) = max(12, min(180, ceil(PI * d / 0.8)));")
        if (shapes.any { s -> s.all().any { it.kind == Shape.Kind.ROUNDED_BOX } }) {
            appendLine()
            appendLine("// A box with all edges rounded by r (hull of eight balls).")
            appendLine("module rounded_box(s, r) {")
            appendLine("    q = max(0.01, min(r, min(s) / 2 - 0.01));")
            appendLine("    hull() for (x = [-1, 1], y = [-1, 1], z = [0, 1])")
            appendLine("        translate([x * (s[0] / 2 - q), y * (s[1] / 2 - q), q + z * (s[2] - 2 * q)]) sphere(r = q);")
            appendLine("}")
        }
        appendLine()
        group(this, shapes, "")
    }

    private fun group(out: StringBuilder, kids: List<Shape>, indent: String) {
        val solids = kids.filter { !it.hole }
        val holes = kids.filter { it.hole }
        if (holes.isEmpty()) { solids.forEach { shape(out, it, indent) }; return }
        out.appendLine("${indent}difference() {")
        val inner = "$indent    "
        if (solids.size == 1) shape(out, solids[0], inner)
        else {
            out.appendLine("${inner}union() {")
            solids.forEach { shape(out, it, "$inner    ") }
            out.appendLine("$inner}")
        }
        holes.forEach { shape(out, it, inner) }
        out.appendLine("$indent}")
    }

    private fun shape(out: StringBuilder, s: Shape, indent: String) {
        out.appendLine("$indent// ${s.name.replace('\n', ' ')}${if (s.hole) " (hole)" else ""}")
        var ind = indent
        s.repeat?.takeIf { it.count > 1 }?.let { r ->
            if (r.circular) {
                val c = r.center
                out.appendLine("${ind}for (i = [0 : ${r.count - 1}]) translate([${n(c.x)}, ${n(c.y)}, 0]) rotate([0, 0, i * ${n(r.angleStep)}]) translate([${n(-c.x)}, ${n(-c.y)}, 0])")
            } else {
                out.appendLine("${ind}for (i = [0 : ${r.count - 1}]) translate(i * [${n(r.step.x)}, ${n(r.step.y)}, ${n(r.step.z)}])")
            }
            ind += "    "
        }
        val h = if (s.isGroup) n(bounds(s.copy(pos = D3(), rot = D3(), repeat = null)).max.z) else f(s, "size.z", s.size.z)
        val rotated = s.rot != D3() || s.bind.keys.any { it.startsWith("rot.") }
        val pivot = rotated || s.mirror.z != 0.0
        out.append("${ind}translate([${f(s, "pos.x", s.pos.x)}, ${f(s, "pos.y", s.pos.y)}, ${f(s, "pos.z", s.pos.z)}${if (pivot) " + $h / 2" else ""}])")
        if (rotated) out.append(" rotate([${f(s, "rot.x", s.rot.x)}, ${f(s, "rot.y", s.rot.y)}, ${f(s, "rot.z", s.rot.z)}])")
        if (s.mirror.x != 0.0) out.append(" mirror([1, 0, 0])")
        if (s.mirror.y != 0.0) out.append(" mirror([0, 1, 0])")
        if (s.mirror.z != 0.0) out.append(" mirror([0, 0, 1])")
        if (pivot) out.append(" translate([0, 0, -$h / 2])")
        if (s.isGroup) {
            out.appendLine(" {")
            group(out, s.children, "$ind    ")
            out.appendLine("$ind}")
        } else {
            out.appendLine()
            out.appendLine("$ind    ${body(s)}")
        }
    }

    /** A solid's code: its primitive, edges rounded / chamfered and hollowed when asked. */
    private fun body(s: Shape): String {
        val w = f(s, "size.x", s.size.x)
        val d = f(s, "size.y", s.size.y)
        val h = f(s, "size.z", s.size.z)
        val sketch = s.kind == Shape.Kind.SKETCH
        val revolve = sketch && s.x(Shape.REVOLVE, 0.0) != 0.0
        val e = f(s, Shape.EDGE, s.x(Shape.EDGE, 0.0)).takeIf { s.kind in Shape.EDGED && value(s, Shape.EDGE, s.x(Shape.EDGE, 0.0)) > 0 }
        val chamfer = s.x(Shape.CHAMFER, 0.0) != 0.0
        val t = f(s, Shape.SHELL, s.x(Shape.SHELL, 0.0)).takeIf { s.kind in Shape.SHELLED && !revolve && value(s, Shape.SHELL, s.x(Shape.SHELL, 0.0)) > 0 }
        // A sketch rounds its outline in 2D; other shapes are shrunk by the edge and grown back with a ball (or a
        // double pyramid for a chamfer), exact for boxes and cylinders, close for the others.
        fun solid(w: String, d: String, h: String, inset: String?, e: String?): String =
            if (e == null || sketch) primitive(s, w, d, h, e, chamfer, inset)
            else {
                val tool = if (chamfer) "hull() { cylinder(h = $e, r1 = $e, r2 = 0, \$fn = 4); mirror([0, 0, 1]) cylinder(h = $e, r1 = $e, r2 = 0, \$fn = 4); }"
                else "sphere(r = $e, \$fn = sides(2 * $e));"
                "translate([0, 0, $e]) minkowski() { ${primitive(s, minus(w, e, 2.0), minus(d, e, 2.0), minus(h, e, 2.0))} $tool }"
            }
        if (t == null) return solid(w, d, h, null, e)
        // The cavity: the same shape smaller by the wall (a sketch's outline inset by it), open top reaching past the top.
        // Its edges are smaller by the wall (a chamfer by (2 - sqrt 2) x wall) so the wall stays even round them.
        val open = s.x(Shape.OPEN, 0.0) != 0.0
        if (sketch) return "difference() { ${solid(w, d, h, null, e)} translate([0, 0, $t]) " +
            "${solid(w, d, if (open) minus(h, t, 1.0, 0.01) else minus(h, t, 2.0), t, e)} }"
        val ie = e?.let { edge(it, t, if (chamfer) 2 - sqrt(2.0) else 1.0) }
        // An open top's cavity reaches past the top by its own edge, so its rounded rim doesn't leave a lip.
        val ih = if (open) minus(h, t, 1.0, 0.01).let { if (ie == null) it else plus(it, ie) } else minus(h, t, 2.0)
        return "difference() { ${solid(w, d, h, null, e)} translate([0, 0, $t]) ${solid(minus(w, t, 2.0), minus(d, t, 2.0), ih, null, ie)} }"
    }

    /** The edge [e] less [k] x [t]; null (sharp) when that leaves nothing, an expression when either is a slider. */
    private fun edge(e: String, t: String, k: Double): String? {
        val x = e.toDoubleOrNull()
        val y = t.toDoubleOrNull()
        return if (x != null && y != null) (x - k * y).takeIf { it > 0.01 }?.let(::n)
        else "max(0.01, $e - ${n(k)} * $t)"
    }

    private fun plus(a: String, b: String): String {
        val x = a.toDoubleOrNull()
        val y = b.toDoubleOrNull()
        return if (x != null && y != null) n(x + y) else "$a + $b"
    }

    /** [a] - [k] * [b] + [add], at least 0.01: a number when both are numbers, else an expression. */
    private fun minus(a: String, b: String, k: Double, add: Double = 0.0): String {
        val x = a.toDoubleOrNull()
        val y = b.toDoubleOrNull()
        return if (x != null && y != null) n(max(0.01, x - k * y + add))
        else "max(0.01, $a - ${n(k)} * $b${if (add != 0.0) " + ${n(add)}" else ""})"
    }

    /**
     * The primitive centred on x / y, standing on z = 0, filling a [w] x [d] x [h] box. A sketch also rounds
     * (or chamfers) its outline's corners by [edge] and insets the outline by [inset].
     */
    private fun primitive(s: Shape, w: String, d: String, h: String, edge: String? = null, chamfer: Boolean = false,
                          inset: String? = null): String {
        val sides = s.x(Shape.SIDES, 0.0).toInt()
        val fn = if (sides >= 3) ", \$fn = $sides" else ", \$fn = sides(max($w, $d))"
        val round = "\$fn = sides(max($w, $d, $h))"
        return when (s.kind) {
            Shape.Kind.BOX -> "translate([-$w / 2, -$d / 2, 0]) cube([$w, $d, $h]);"
            Shape.Kind.ROUNDED_BOX -> "rounded_box([$w, $d, $h], ${f(s, Shape.RADIUS, s.x(Shape.RADIUS, 2.0))});"
            Shape.Kind.CYLINDER, Shape.Kind.PRISM -> "scale([$w, $d, $h]) cylinder(h = 1, d = 1$fn);"
            Shape.Kind.CONE -> "scale([$w, $d, $h]) cylinder(h = 1, d1 = 1, d2 = ${n(s.x(Shape.TOP, 0.0).coerceIn(0.0, 1.0))}$fn);"
            Shape.Kind.PYRAMID -> "scale([$w, $d, $h]) rotate([0, 0, 45]) cylinder(h = 1, d1 = sqrt(2), d2 = 0, \$fn = 4);"
            Shape.Kind.SPHERE -> "translate([0, 0, $h / 2]) scale([$w, $d, $h]) sphere(d = 1, $round);"
            Shape.Kind.DOME -> "scale([$w / 2, $d / 2, $h]) intersection() { sphere(r = 1, $round); translate([-1, -1, 0]) cube([2, 2, 1]); }"
            Shape.Kind.TORUS -> {
                val t = f(s, Shape.TUBE, s.x(Shape.TUBE, 5.0))
                "scale([1, $d / $w, 1]) rotate_extrude(\$fn = sides($w)) translate([($w - min($t, $w / 2)) / 2, $h / 2]) scale([min($t, $w / 2) / 2, $h / 2]) circle(r = 1, \$fn = sides(max(min($t, $w / 2), $h)));"
            }
            Shape.Kind.TUBE -> {
                val t = f(s, Shape.WALL, s.x(Shape.WALL, 2.0))
                "difference() { scale([$w, $d, 1]) cylinder(h = $h, d = 1$fn); translate([0, 0, -0.01]) " +
                    "scale([max(0.01, $w - 2 * $t), max(0.01, $d - 2 * $t), 1]) cylinder(h = $h + 0.02, d = 1$fn); }"
            }
            Shape.Kind.WEDGE -> "scale([$w, $d, $h]) rotate([90, 0, 0]) linear_extrude(1, center = true) polygon([[-0.5, 0], [0.5, 0], [-0.5, 1]]);"
            Shape.Kind.TEXT -> if (s.text.isBlank()) "// (no text)" else
                "resize([$w, $d, $h]) linear_extrude(1) text(${quote(s.text)}, size = 10, halign = \"center\", valign = \"center\");"
            Shape.Kind.SKETCH -> sketch(s, w, d, h, edge, chamfer, inset)
            Shape.Kind.MESH -> {
                val b = Shape.MESH_BOUNDS.map { s.x(it, 0.0) }
                if (s.file.isBlank()) "// (no model file)"
                else "scale([$w / ${n(max(0.01, b[3] - b[0]))}, $d / ${n(max(0.01, b[4] - b[1]))}, $h / ${n(max(0.01, b[5] - b[2]))}]) " +
                    "translate([${n(-(b[0] + b[3]) / 2)}, ${n(-(b[1] + b[4]) / 2)}, ${n(-b[2])}]) import(${quote(s.file)}, convexity = 10);"
            }
            Shape.Kind.GROUP -> ""
        }
    }

    /**
     * A sketch extruded up [h] (its outline scaled to [w] x [d]), or revolved about Z (profile x = radius, y = height).
     * Corners are rounded / chamfered by [edge] and the outline inset by [inset] after scaling, so both are in mm.
     */
    private fun sketch(s: Shape, w: String, d: String, h: String, edge: String?, chamfer: Boolean, inset: String?): String {
        val sk = s.sketch ?: return "// (no sketch)"
        val (lo, hi) = sk.bounds() ?: return "// (empty sketch)"
        fun mods(code: String): String {
            var c = code
            // Convex corners rounded by offset(r) after a shrink, concave ones by a grow first; a chamfer cuts convex ones.
            if (edge != null) c = if (chamfer) "offset(delta = $edge, chamfer = true) offset(delta = -$edge) $c"
            else "offset(r = $edge) offset(delta = -$edge) offset(r = -$edge) offset(delta = $edge) $c"
            if (inset != null) c = "offset(delta = -$inset) $c"
            return c
        }
        if (s.x(Shape.REVOLVE, 0.0) != 0.0) {
            val r = max(0.01, hi.x)
            val nh = max(0.01, hi.y - lo.y)
            val angle = s.x(Shape.ANGLE, 360.0).coerceIn(1.0, 360.0)
            // The profile is scaled to the box before rounding, so the edge stays in mm; depth follows by a y scale.
            return "scale([1, $d / $w, 1]) rotate_extrude(${if (angle < 360.0) "angle = ${n(angle)}, " else ""}\$fn = sides(max($w, $d))) " +
                "intersection() { square([$w / 2 + 1, $h + 1]); ${mods("scale([$w / ${n(2 * r)}, $h / ${n(nh)}]) ${sk.toScad(P2(0.0, -lo.y))}")} }"
        }
        val top = s.x(Shape.TOP, 1.0).coerceAtLeast(0.0)
        val c = (lo + hi) * 0.5
        return "linear_extrude($h${if (top != 1.0) ", scale = ${n(top)}" else ""}) " +
            mods("scale([$w / ${n(max(0.01, hi.x - lo.x))}, $d / ${n(max(0.01, hi.y - lo.y))}]) ${sk.toScad(P2(-c.x, -c.y))}")
    }

    /** A field's code: the bound dimension's name, else the number. */
    private fun f(s: Shape, field: String, raw: Double) = s.bind[field]?.takeIf { dim(it) != null } ?: n(raw)

    private fun quote(t: String) = "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    companion object {
        fun decode(json: String?): ShapeDesign? = runCatching { of(JSONObject(json ?: return null)) }.getOrNull()

        fun of(o: JSONObject) = ShapeDesign(
            shapes = o.optJSONArray("shapes")?.let { a -> (0 until a.length()).map { Shape.of(a.getJSONObject(it)) } } ?: emptyList(),
            dims = o.optJSONArray("dims")?.let { a -> (0 until a.length()).map { Dim.of(a.getJSONObject(it)) } } ?: emptyList(),
            nextId = o.optInt("nextId", 1),
        ).let { d -> d.copy(nextId = max(d.nextId, (d.shapes.flatMap { it.all().toList() }.maxOfOrNull { it.id } ?: 0) + 1)) }

        /** Rounded to 0.001, no trailing zeros ("20", "2.5", "-0.125"). */
        fun n(v: Double): String {
            val r = Math.round(v * 1000.0) / 1000.0
            return if (r == Math.rint(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString()
            else java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString()
        }

        /** OpenSCAD's rotate([a, b, c]): about X, then Y, then Z. */
        fun rotate(p: D3, deg: D3): D3 {
            var (x, y, z) = Triple(p.x, p.y, p.z)
            if (deg.x != 0.0) { val a = Math.toRadians(deg.x); val c = cos(a); val s = sin(a); val ny = y * c - z * s; z = y * s + z * c; y = ny }
            if (deg.y != 0.0) { val a = Math.toRadians(deg.y); val c = cos(a); val s = sin(a); val nx = x * c + z * s; z = -x * s + z * c; x = nx }
            if (deg.z != 0.0) { val a = Math.toRadians(deg.z); val c = cos(a); val s = sin(a); val nx = x * c - y * s; y = x * s + y * c; x = nx }
            return D3(x, y, z)
        }

        /**
         * Sketch shape [s] with the drawing [sketch]: its box takes the drawing's size, 1:1 (or, with
         * [keepScale], at the box's current scale), and the unchanged part of the drawing stays in place.
         */
        fun fitSketch(s: Shape, sketch: Sketch, keepScale: Boolean = false): Shape {
            val (lo, hi) = sketch.bounds() ?: return s.copy(sketch = sketch)
            val old = s.sketch?.bounds()
            val revolve = s.x(Shape.REVOLVE, 0.0) != 0.0
            fun box(lo: P2, hi: P2) = if (revolve) D3(2 * max(0.5, hi.x), 2 * max(0.5, hi.x), max(0.5, hi.y - lo.y))
                else D3(max(0.5, hi.x - lo.x), max(0.5, hi.y - lo.y), s.size.z)
            val now = box(lo, hi)
            val k = if (keepScale && old != null) box(old.first, old.second).let { b -> D3(s.size.x / b.x, s.size.y / b.y, s.size.z / b.z) }
                else D3(1.0, 1.0, 1.0)
            if (revolve) return s.copy(sketch = sketch, size = D3(now.x * k.x, now.y * k.y, now.z * k.z))
            var pos = s.pos
            if (old != null) {
                // The box centre follows the drawing's centre.
                val shift = D3((lo.x + hi.x - old.first.x - old.second.x) / 2 * k.x, (lo.y + hi.y - old.first.y - old.second.y) / 2 * k.y, 0.0)
                pos += rotate(shift, D3(0.0, 0.0, s.rot.z))
            }
            return s.copy(sketch = sketch, pos = pos, size = D3(now.x * k.x, now.y * k.y, s.size.z))
        }

        /** A new shape of [kind] with a default size, standing on the workplane at [at]. */
        fun create(kind: Shape.Kind, id: Int, name: String, at: D3 = D3()): Shape = when (kind) {
            Shape.Kind.TEXT -> Shape(id, kind, name, D3(40.0, 12.0, 3.0), at, text = "Text")
            Shape.Kind.TORUS -> Shape(id, kind, name, D3(30.0, 30.0, 8.0), at, extra = mapOf(Shape.TUBE to 8.0))
            Shape.Kind.TUBE -> Shape(id, kind, name, D3(20.0, 20.0, 20.0), at, extra = mapOf(Shape.WALL to 2.0))
            Shape.Kind.ROUNDED_BOX -> Shape(id, kind, name, D3(20.0, 20.0, 20.0), at, extra = mapOf(Shape.RADIUS to 3.0))
            Shape.Kind.PRISM -> Shape(id, kind, name, D3(20.0, 20.0, 20.0), at, extra = mapOf(Shape.SIDES to 6.0))
            Shape.Kind.CONE -> Shape(id, kind, name, D3(20.0, 20.0, 20.0), at, extra = mapOf(Shape.TOP to 0.0))
            Shape.Kind.DOME -> Shape(id, kind, name, D3(20.0, 20.0, 10.0), at)
            Shape.Kind.SKETCH -> Shape(id, kind, name, D3(20.0, 20.0, 10.0), at, sketch = Sketch.START)
            else -> Shape(id, kind, name, D3(20.0, 20.0, 20.0), at)
        }
    }
}
