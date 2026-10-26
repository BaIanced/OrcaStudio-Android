package app.orcaandroid.scad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
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
    /** Filament (1-based) a top-level solid prints with in the Build workspace; 0 = the object's. */
    val filament: Int = 0,
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
            if (filament > 0) put("filament", filament)
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
        /** A group's Blend joins radius (mm): where its shapes meet (and hole rims) is rounded, in the Build workspace. */
        const val BLEND = "blend"
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
            filament = o.optInt("filament").coerceAtLeast(0),
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

        /** A new slider for a length of [v] mm (or a position / angle when [signed]; an edge or wall, in 0.1 mm steps, when [small]). */
        fun forLength(name: String, v: Double, signed: Boolean = false, angle: Boolean = false, small: Boolean = false) =
            if (small) Dim(name, v, 0.0, maxOf(10.0, Math.ceil(v * 2)), 0.1)
            else Dim(name, v, if (signed) minOf(v, -100.0) else 0.0, if (angle) maxOf(360.0, v) else maxOf(100.0, Math.ceil(v * 2 / 10) * 10), 1.0)
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

    /** The groups that hold [id], outermost first, then the shape itself; empty when there is none. */
    fun pathTo(id: Int): List<Shape> {
        fun path(list: List<Shape>): List<Shape>? {
            for (s in list) {
                if (s.id == id) return listOf(s)
                path(s.children)?.let { return listOf(s) + it }
            }
            return null
        }
        return path(shapes).orEmpty()
    }

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

    /** Height of [s] whose middle it turns and flips about: its size, or a group's children's top. */
    fun height(s: Shape): Double = if (s.isGroup) s.children.maxOfOrNull { bounds(it).max.z } ?: 0.0 else size(s).z

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
            rotate(flip(c - pivot, s.mirror), r) + pivot + p
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
        prelude(this, shapes)
        appendLine()
        group(this, shapes, "")
    }

    /** The hidden settings and helpers the code starts with (the rounded box module only when [shapes] use it). */
    private fun prelude(out: StringBuilder, shapes: List<Shape>) = with(out) {
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
    }

    // --- Build workspace: one Orca volume per shape ------------------------------------------------

    /**
     * One Orca volume of the design: a top-level shape (a group as one mesh) or one copy of a repeated one.
     * [scad] draws it in its own frame (centred on x / y, standing on z = 0; a group's children where they sit in
     * the group), [matrix] (row-major 4 x 4) puts that frame where the design has it. Moving, turning or mirroring a
     * shape only changes [matrix], so equal [scad] means the same mesh. A hole is a negative volume: Orca cuts it
     * from every part of the object when slicing, as the design's outermost group does in [toScad]. [filament] is the
     * top-level shape's (0 = the object's; a hole has none); it leaves [scad] alone, so a colour change keeps the mesh.
     */
    data class Piece(val shapeId: Int, val copy: Int, val hole: Boolean, val scad: String, val matrix: List<Double>, val filament: Int = 0)

    /** The design as Orca volumes, top-level shapes in order, a repeated shape's copies one after another. */
    fun pieces(): List<Piece> = shapes.flatMap { top ->
        val s = resolved(top)
        val scad = if (s.isGroup && s.x(Shape.BLEND, 0.0) > 0) blendCode(s) else buildString {
            prelude(this, listOf(s))
            appendLine()
            if (s.isGroup) group(this, s.children, "") else appendLine(body(s))
        }
        val own = placement(s)
        copies(s).mapIndexed { i, c -> Piece(top.id, i, s.hole, scad, mul(c, own).toList(), if (s.hole) 0 else s.filament) }
    }

    /**
     * A blended group's piece "code": [BLEND_TAG], then {radius, parts} for the engine's blendSolids (OCCT), or "!" and
     * the name of the first shape it can't take. Still a string, so piece caching and reuse work as for OpenSCAD code.
     */
    private fun blendCode(g: Shape): String {
        val parts = JSONArray()
        val bad = blendParts(g.children, translation(D3()), false, parts)
        return BLEND_TAG + (if (bad != null) "!$bad" else JSONObject().put("radius", g.x(Shape.BLEND, 0.0)).put("parts", parts).toString())
    }

    /**
     * Adds every copy of [kids]' shapes (resolved), placed by [frame] in the blended group's frame, to [out]; returns the
     * name of the first one OCCT can't take: other kinds, many-sided cylinders / cones, hollow shapes, or a group with
     * holes of its own (they cut only that group, which a flat list can't say).
     */
    private fun blendParts(kids: List<Shape>, frame: DoubleArray, hole: Boolean, out: JSONArray): String? {
        for (c in kids) {
            val own = placement(c)
            for (copy in copies(c)) {
                val m = mul(frame, mul(copy, own))
                if (c.isGroup) {
                    if (c.children.any { it.hole }) return c.name
                    blendParts(c.children, m, hole || c.hole, out)?.let { return it }
                    continue
                }
                val kind = when (c.kind) {
                    Shape.Kind.BOX, Shape.Kind.ROUNDED_BOX -> "box"
                    Shape.Kind.CYLINDER, Shape.Kind.CONE -> c.kind.key.takeIf { c.x(Shape.SIDES, 0.0) < 3 }
                    Shape.Kind.SPHERE -> "sphere"
                    else -> null
                }
                if (kind == null || (c.kind in Shape.SHELLED && c.x(Shape.SHELL, 0.0) > 0)) return c.name
                val edge = when (c.kind) {
                    Shape.Kind.ROUNDED_BOX -> min(c.x(Shape.RADIUS, 2.0), min(c.size.x, min(c.size.y, c.size.z)) / 2 - 0.01)
                    in Shape.EDGED -> c.x(Shape.EDGE, 0.0)
                    else -> 0.0
                }
                out.put(JSONObject().put("kind", kind).put("size", c.size.json()).put("hole", hole || c.hole)
                    .put("m", JSONArray(m.toList())).apply {
                        if (c.kind == Shape.Kind.CONE) put("top", c.x(Shape.TOP, 0.0))
                        if (edge > 0) put("round", edge).put("chamfer", c.kind != Shape.Kind.ROUNDED_BOX && c.x(Shape.CHAMFER, 0.0) != 0.0)
                    })
            }
        }
        return null
    }

    /**
     * Merge bake: top-level group [id] replaced by a MESH shape of [file], the group's piece code rendered alone (holes
     * in it cut); [b] = the file's bounds in the group's frame ([Shape.MESH_BOUNDS] order). The mesh lands where the
     * group was and keeps its turn, mirror, repeat, hole and filament; bindings go (a mesh has no sliders).
     */
    fun merged(id: Int, file: String, b: List<Double>): ShapeDesign {
        val g = shapes.firstOrNull { it.id == id && it.isGroup } ?: return this
        val size = D3(max(0.01, b[3] - b[0]), max(0.01, b[4] - b[1]), max(0.01, b[5] - b[2]))
        val h = height(g)
        val r = rot(g)
        // Group: x -> p + h/2 + R F (x - h/2). Mesh (its body moves x by -t, t = box centre x / y and bottom):
        // x -> p' + h'/2 + R F (x - t - h'/2). Equal for every x when p' = p + h/2 - h'/2 + R F (t + h'/2 - h/2).
        val t = D3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, b[2] + size.z / 2 - h / 2)
        val pos = pos(g) + D3(0.0, 0.0, h / 2 - size.z / 2) + rotate(flip(t, g.mirror), r)
        val mesh = Shape(id, Shape.Kind.MESH, g.name, size, pos, r, g.hole, g.mirror, extra = Shape.MESH_BOUNDS.zip(b).toMap(),
            file = file, repeat = g.repeat, filament = g.filament)
        return copy(shapes = shapes.map { if (it.id == id) mesh else it })
    }

    /**
     * Build workspace pick inside a group: the shapes at design point [p] on [piece], outermost first (the piece's
     * shape, then the child under [p], its child, ...). A point on a surface lies in several boxes: solids win over
     * holes, then the smallest box. Empty when [piece]'s shape is gone.
     */
    fun pathAt(piece: Piece, p: D3): List<Int> {
        val top = shapes.firstOrNull { it.id == piece.shapeId }?.let(::resolved) ?: return emptyList()
        val out = arrayListOf(top.id)
        var q = inverse(piece.matrix.toDoubleArray(), p)
        var kids = if (top.isGroup) top.children else emptyList()
        while (kids.isNotEmpty()) {
            var best: Triple<Shape, D3, Double>? = null
            for (c in kids) {
                val box = if (c.isGroup) c.children.map { bounds(it) }.reduceOrNull { a, b -> a.union(b) } ?: continue
                    else Bounds(D3(-c.size.x / 2, -c.size.y / 2, 0.0), D3(c.size.x / 2, c.size.y / 2, c.size.z))
                val own = placement(c)
                for (m in copies(c)) {
                    val l = inverse(mul(m, own), q)
                    val e = 0.01
                    if ((0..2).any { l[it] < box.min[it] - e || l[it] > box.max[it] + e }) continue
                    val vol = (0..2).fold(1.0) { acc, i -> acc * (box.max[i] - box.min[i]) }
                    val b = best
                    if (b == null || (b.first.hole && !c.hole) || (b.first.hole == c.hole && vol < b.third)) best = Triple(c, l, vol)
                }
            }
            val (c, l) = best ?: break
            out += c.id
            q = l
            kids = if (c.isGroup) c.children else emptyList()
        }
        return out
    }

    /**
     * Design-frame direction [v] in the frame shape [id] moves in (its parent group's; groups turn and flip their
     * children). A repeated group counts as its first copy.
     */
    fun toParent(id: Int, v: D3): D3 =
        pathTo(id).dropLast(1).fold(v) { acc, g ->
            val m = placement(resolved(g))
            // The turn and flip are orthonormal: the inverse is the transpose.
            D3((0..2).sumOf { m[it * 4] * acc[it] }, (0..2).sumOf { m[it * 4 + 1] * acc[it] }, (0..2).sumOf { m[it * 4 + 2] * acc[it] })
        }

    /**
     * Where the frame shape [id] lives in (its parent group's) lies in the design: row-major 4 x 4, rigid (turn, flip,
     * move). The identity for a top-level shape; a repeated group counts as its first copy, as in [toParent].
     */
    fun parentFrame(id: Int): DoubleArray = pathTo(id).dropLast(1).fold(translation(D3())) { acc, g -> mul(acc, placement(resolved(g))) }

    /**
     * After shape [id] changed (from [before]): each group holding it, innermost first, whose height changed moved so
     * its children stay where they were. A group turns and flips about its middle (half its height): a taller group
     * would otherwise swing its turned children. Returns the new pos of each group that has to move.
     */
    fun groupShifts(before: ShapeDesign, id: Int): List<Pair<Int, D3>> {
        var d = this
        val out = ArrayList<Pair<Int, D3>>()
        for (g in pathTo(id).dropLast(1).reversed()) {
            val old = before.find(g.id) ?: continue
            val now = d.find(g.id) ?: continue
            val k = (d.height(now) - before.height(old)) / 2
            if (abs(k) < 1e-9) continue
            // x -> p + h/2 + R F (x - h/2) stays put when p' = p + (R F - I) (0, 0, (h' - h) / 2).
            val m = matrix(rot(now))
            val fz = if (now.mirror.z != 0.0) -1.0 else 1.0
            val pos = pos(now) + D3(m[0][2] * fz * k, m[1][2] * fz * k, m[2][2] * fz * k - k)
            if ((0..2).all { abs(pos[it] - pos(now)[it]) < 1e-9 }) continue
            out += g.id to pos
            d = d.update(g.id) { it.copy(pos = pos) }
        }
        return out
    }

    // --- Push / pull ------------------------------------------------------------------------------------

    /**
     * A flat face push / pull grabs ([pushed]): a side of a shape's box ([axis] 0 x, 1 y, 2 z; [sign] +-1 the end) or,
     * with [axis] -1, the round side of a cylinder, tube or prism ([dir]: outward in the shape's x / y, on its unit
     * circle). [point], [normal] (out of the shape, unit) and [stretch] (the unit directions its size changes along) are
     * in the design frame, on the copy that was hit; [length] is the size the push changes (mm, along [normal]).
     */
    data class Face(val shapeId: Int, val axis: Int, val sign: Int, val dir: D3, val point: D3, val normal: D3,
                    val stretch: List<D3>, val length: Double, val hole: Boolean)

    /**
     * The face at design point [p] of [piece], hit by a ray along design direction [ray]: of every solid and hole in the
     * piece (through its groups and copies), the box side (or round side) [p] lies on; a solid's must face the ray (a
     * hole's wall may face either way: seen from outside, or cut into a group's solid). Solids win, then the closest.
     * Null on curved or slanted surfaces, meshes, spheres and tori.
     */
    fun faceAt(piece: Piece, p: D3, ray: D3): Face? {
        val top = shapes.firstOrNull { it.id == piece.shapeId }?.let(::resolved) ?: return null
        var best: Pair<Face, Double>? = null
        // m: s's own frame -> design.
        fun visit(s: Shape, m: DoubleArray, hole: Boolean) {
            if (s.isGroup) {
                for (c in s.children) {
                    val own = placement(c)
                    for (k in copies(c)) visit(c, mul(m, mul(k, own)), hole || c.hole)
                }
                return
            }
            val f = face(s, m, p, ray, hole) ?: return
            val b = best
            if (b == null || (b.first.hole && !f.first.hole) || (b.first.hole == f.first.hole && f.second < b.second)) best = f
        }
        visit(top, piece.matrix.toDoubleArray(), top.hole)
        return best?.first
    }

    /** [faceAt] for one solid [s] (resolved; [m] its frame -> design): the face and how far [p] lies off it (mm). */
    private fun face(s: Shape, m: DoubleArray, p: D3, ray: D3, hole: Boolean): Pair<Face, Double>? {
        if (s.kind in NO_FACES) return null
        val l = inverse(m, p)
        val half = D3(s.size.x / 2, s.size.y / 2, s.size.z / 2)
        // From the box centre.
        val c = D3(l.x, l.y, l.z - half.z)
        val e = 0.05
        if ((0..2).any { abs(c[it]) > half[it] + e }) return null
        val round = s.kind in ROUND_SIDES
        var axis = -2
        var gap = Double.MAX_VALUE
        for (i in 0..2) {
            if (round && i < 2) continue
            val g = abs(half[i] - abs(c[i]))
            if (g <= e && g < gap) { axis = i; gap = g }
        }
        var dir = D3()
        if (axis == -2 && round) {
            // On the n-gon of the unit circle (a scaled cylinder): its sides lie between cos(pi / n) and 1.
            val u = D3(c.x / half.x, c.y / half.y, 0.0)
            val r = sqrt(u.x * u.x + u.y * u.y)
            val n = s.x(Shape.SIDES, 0.0).toInt().takeIf { it >= 3 }
                ?: max(12, min(180, kotlin.math.ceil(Math.PI * max(s.size.x, s.size.y) / 0.8).toInt()))
            if (r < 1e-9 || r < cos(Math.PI / n) - e / min(half.x, half.y) || r > 1 + e / min(half.x, half.y)) return null
            axis = -1
            dir = D3(u.x / r, u.y / r, 0.0)
            gap = (1 - r) * min(half.x, half.y)
        }
        if (axis == -2) return null
        val sign = if (axis >= 0 && c[axis] < 0) -1 else 1
        fun turn(v: D3) = D3((0..2).sumOf { m[it] * v[it] }, (0..2).sumOf { m[4 + it] * v[it] }, (0..2).sumOf { m[8 + it] * v[it] })
        fun unit(v: D3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z).let { k -> D3(v.x / k, v.y / k, v.z / k) }
        val normal = unit(turn(if (axis < 0) dir else D3().with(axis, sign.toDouble())))
        if (!hole && normal.x * ray.x + normal.y * ray.y + normal.z * ray.z >= 0) return null
        val stretch = if (axis < 0) listOf(unit(turn(D3(1.0, 0.0, 0.0))), unit(turn(D3(0.0, 1.0, 0.0)))) else listOf(normal)
        val length = if (axis >= 0) s.size[axis] else if (abs(dir.x) >= abs(dir.y)) s.size.x else s.size.y
        return Face(s.id, axis, sign, dir, p, normal, stretch, length, hole) to gap
    }

    /**
     * Shape [f]'s pose (in its parent's frame) after its face [f] moved [mm] out (in when negative), the opposite side
     * staying put; a round side widens the shape by [mm] in x and y. Sizes stay at least 1 mm. Null when the shape is gone.
     */
    fun pushed(f: Face, mm: Double): BuildTransform.Pose? {
        val s = find(f.shapeId)?.let(::resolved) ?: return null
        val sz = s.size
        val d = max(mm, 1.0 - if (f.axis < 0) min(sz.x, sz.y) else sz[f.axis])
        val size = if (f.axis < 0) D3(sz.x + d, sz.y + d, sz.z) else sz.with(f.axis, sz[f.axis] + d)
        // The opposite side's middle, in the old and the new own frame.
        fun far(b: D3) = when (f.axis) {
            -1 -> D3(-f.dir.x * b.x / 2, -f.dir.y * b.y / 2, 0.0)
            2 -> if (f.sign > 0) D3() else D3(0.0, 0.0, b.z)
            else -> D3().with(f.axis, -f.sign * b[f.axis] / 2)
        }
        fun at(m: DoubleArray, q: D3) = D3((0..2).sumOf { m[it] * q[it] } + m[3], (0..2).sumOf { m[4 + it] * q[it] } + m[7],
            (0..2).sumOf { m[8 + it] * q[it] } + m[11])
        val pos = s.pos + at(placement(s), far(sz)) - at(placement(s.copy(size = size)), far(size))
        fun round(v: D3) = D3(Math.round(v.x * 1000.0) / 1000.0 + 0.0, Math.round(v.y * 1000.0) / 1000.0 + 0.0, Math.round(v.z * 1000.0) / 1000.0 + 0.0)
        return BuildTransform.Pose(round(pos), s.rot, round(size), s.repeat)
    }

    /** Point [p] through the inverse of rigid row-major 4 x 4 [m] (turn, flip and move only). */
    private fun inverse(m: DoubleArray, p: D3): D3 {
        val d = D3(p.x - m[3], p.y - m[7], p.z - m[11])
        return D3((0..2).sumOf { m[it * 4] * d[it] }, (0..2).sumOf { m[it * 4 + 1] * d[it] }, (0..2).sumOf { m[it * 4 + 2] * d[it] })
    }

    /** [s] with the fields bound to a dimension set to its value and no bindings left (children too). */
    private fun resolved(s: Shape): Shape = s.copy(
        size = size(s), pos = pos(s), rot = rot(s),
        extra = s.extra + s.bind.filter { (field, d) -> '.' !in field && dim(d) != null }.mapValues { dim(it.value)!!.value },
        bind = emptyMap(), children = s.children.map(::resolved),
    )

    /** Where [shape] (resolved) puts its own frame in its parent's, as [shape] writes it: turned and flipped about its middle. */
    private fun placement(s: Shape): DoubleArray {
        val h = height(s)
        val p = s.pos
        val m = matrix(s.rot)
        val f = D3(if (s.mirror.x != 0.0) -1.0 else 1.0, if (s.mirror.y != 0.0) -1.0 else 1.0, if (s.mirror.z != 0.0) -1.0 else 1.0)
        val turn = DoubleArray(16).also { r ->
            for (i in 0..2) for (j in 0..2) r[i * 4 + j] = m[i][j] * f[j]
            r[15] = 1.0
        }
        return mul(mul(translation(D3(p.x, p.y, p.z + h / 2)), turn), translation(D3(0.0, 0.0, -h / 2)))
    }

    /** The transforms of [s]'s copies (just the identity without a repeat), as [shape] writes them. */
    private fun copies(s: Shape): List<DoubleArray> {
        val r = s.repeat?.takeIf { it.count > 1 } ?: return listOf(translation(D3()))
        return (0 until r.count).map { i ->
            if (!r.circular) translation(D3(r.step.x * i, r.step.y * i, r.step.z * i))
            else {
                val m = matrix(D3(0.0, 0.0, r.angleStep * i))
                val turn = DoubleArray(16).also { t -> for (a in 0..2) for (b in 0..2) t[a * 4 + b] = m[a][b]; t[15] = 1.0 }
                mul(mul(translation(D3(r.center.x, r.center.y, 0.0)), turn), translation(D3(-r.center.x, -r.center.y, 0.0)))
            }
        }
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
        val h = if (s.isGroup) n(height(s)) else f(s, "size.z", s.size.z)
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
        /** Starts a Blend joins piece's code ([blendCode]) instead of OpenSCAD. */
        const val BLEND_TAG = "// blend joins\n"

        /** Kinds without flat faces to push ([faceAt]), and those whose side is round (pushing it widens x and y). */
        private val NO_FACES = setOf(Shape.Kind.GROUP, Shape.Kind.MESH, Shape.Kind.SPHERE, Shape.Kind.TORUS)
        private val ROUND_SIDES = setOf(Shape.Kind.CYLINDER, Shape.Kind.TUBE, Shape.Kind.PRISM)

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

        /** [p] through OpenSCAD's mirror() of the axes set in [m]. */
        /** A row-major 4 x 4 translation by [d]. */
        fun translation(d: D3) = DoubleArray(16).also { t -> t[0] = 1.0; t[5] = 1.0; t[10] = 1.0; t[15] = 1.0; t[3] = d.x; t[7] = d.y; t[11] = d.z }

        /** [a] x [b], both row-major 4 x 4. */
        fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { k ->
            val i = k / 4
            val j = k % 4
            (0..3).sumOf { a[i * 4 + it] * b[it * 4 + j] }
        }

        fun flip(p: D3, m: D3) = D3(if (m.x != 0.0) -p.x else p.x, if (m.y != 0.0) -p.y else p.y, if (m.z != 0.0) -p.z else p.z)

        /** rotate([a, b, c]) as a row-major matrix (Rz * Ry * Rx). */
        fun matrix(deg: D3): Array<DoubleArray> {
            val ex = rotate(D3(1.0, 0.0, 0.0), deg)
            val ey = rotate(D3(0.0, 1.0, 0.0), deg)
            val ez = rotate(D3(0.0, 0.0, 1.0), deg)
            return Array(3) { i -> doubleArrayOf(ex[i], ey[i], ez[i]) }
        }

        /** The rotate([a, b, c]) angles of rotation matrix [m], each in -180..180. */
        fun angles(m: Array<DoubleArray>): D3 {
            val sy = -m[2][0].coerceIn(-1.0, 1.0)
            val b = Math.asin(sy)
            // Gimbal lock (b = ±90°): only a - c (or a + c) counts; put it all in c.
            val (a, c) = if (kotlin.math.abs(sy) > 1 - 1e-9) 0.0 to kotlin.math.atan2(-m[0][1], m[1][1])
                else kotlin.math.atan2(m[2][1], m[2][2]) to kotlin.math.atan2(m[1][0], m[0][0])
            fun deg(r: Double) = Math.round(Math.toDegrees(r) * 1e6) / 1e6 + 0.0
            return D3(deg(a), deg(b), deg(c))
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
