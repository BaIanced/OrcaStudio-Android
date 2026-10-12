package app.orcaandroid.ui

import app.orcaandroid.R
import app.orcaandroid.scad.Bounds
import app.orcaandroid.scad.D3
import app.orcaandroid.scad.Dim
import app.orcaandroid.scad.Repeat
import app.orcaandroid.scad.Shape
import app.orcaandroid.scad.ShapeDesign

/**
 * The shape builder's edits (no code): add, move, group, cut, align, repeat, sliders, with undo.
 * Every change goes through [commit], which regenerates the OpenSCAD code ([ScadController.setShapes]).
 */
class ShapeController(private val store: Store, private val scad: ScadController) {
    private val undo = ArrayDeque<Pair<ShapeDesign, List<Int>>>()
    private val redo = ArrayDeque<Pair<ShapeDesign, List<Int>>>()
    private var undoOwner: ShapeDesign? = null

    private val st get() = store.value.scad
    private val design get() = st?.shapes
    val selected get() = st?.selected.orEmpty()

    val canUndo get() = undo.isNotEmpty() && undoOwner === design
    val canRedo get() = redo.isNotEmpty() && undoOwner === design

    private fun commit(next: ShapeDesign, sel: List<Int> = selected) {
        val cur = design ?: return
        if (next == cur && sel == selected) return
        // A design loaded from elsewhere starts a fresh history.
        if (undoOwner !== cur) { undo.clear(); redo.clear() }
        if (next != cur) {
            undo.addLast(cur to selected)
            if (undo.size > 200) undo.removeFirst()
            redo.clear()
        }
        undoOwner = next
        scad.setShapes(next, sel)
    }

    fun undo() {
        val cur = design ?: return
        if (!canUndo) return
        val (d, sel) = undo.removeLast()
        redo.addLast(cur to selected)
        undoOwner = d
        scad.setShapes(d, sel)
    }

    fun redo() {
        val cur = design ?: return
        if (!canRedo) return
        val (d, sel) = redo.removeLast()
        undo.addLast(cur to selected)
        undoOwner = d
        scad.setShapes(d, sel)
    }

    fun select(ids: List<Int>) = scad.select(ids)

    /** Tap: only [id]; with Ctrl / Shift / long press ([add]) toggles it in the selection. */
    fun tap(id: Int?, add: Boolean) {
        if (id == null) { if (!add) select(emptyList()); return }
        select(if (!add) listOf(id) else if (id in selected) selected - id else selected + id)
    }

    fun selectAll() = design?.let { d -> select(d.shapes.map { it.id }) }

    // --- Adding ------------------------------------------------------------------------------------

    fun add(kind: Shape.Kind) {
        val d = design ?: return
        val name = store.str(kindName(kind)) + " " + (d.shapes.flatMap { it.all().toList() }.count { it.kind == kind } + 1)
        var shape = ShapeDesign.create(kind, d.nextId, name)
        // Beside what is there already, so it can be seen and picked.
        if (d.shapes.isNotEmpty()) {
            val all = d.shapes.map { d.bounds(it) }.reduce { a, b -> a.union(b) }
            shape = shape.copy(pos = D3(snap(all.max.x + 5 + shape.size.x / 2), snap(all.center.y), 0.0))
        }
        commit(d.copy(shapes = d.shapes + shape, nextId = d.nextId + 1), listOf(shape.id))
    }

    // --- Editing ------------------------------------------------------------------------------------

    fun update(id: Int, f: (Shape) -> Shape) {
        val d = design ?: return
        commit(d.update(id, f))
    }

    /** Sets a numeric field; a field bound to a dimension changes the dimension instead. */
    fun setField(id: Int, field: String, value: Double) {
        val d = design ?: return
        commit(withField(d, id, field, value) ?: return)
    }

    private fun withField(d: ShapeDesign, id: Int, field: String, value: Double): ShapeDesign? {
        val s = d.find(id) ?: return null
        val dim = s.bind[field]?.let { d.dim(it) }
        if (dim != null) return d.copy(dims = d.dims.map { if (it.name == dim.name) it.copy(value = value, min = minOf(it.min, value), max = maxOf(it.max, value)) else it })
        return d.update(id) { sh -> setRaw(sh, field, value) }
    }

    /** Size handle in the preview: the lone selected shape grows by [mm] along [axis], its min side kept (one undo step). */
    fun resize(axis: Int, mm: Double) {
        val d = design ?: return
        val id = selected.singleOrNull() ?: return
        val s = d.find(id) ?: return
        val c = "xyz"[axis]
        var next = withField(d, id, "size.$c", (d.size(s)[axis] + mm).coerceAtLeast(1.0)) ?: return
        // pos is the bottom centre: x / y follow half the growth, z (the bottom) stays.
        if (axis < 2) next = withField(next, id, "pos.$c", d.pos(s)[axis] + mm / 2) ?: return
        commit(next)
    }

    /** Turn handle in the preview: the lone selected shape turns by [degrees] about Z, kept in -180..180. */
    fun turn(degrees: Double) {
        val d = design ?: return
        val id = selected.singleOrNull() ?: return
        val s = d.find(id) ?: return
        var z = (d.rot(s).z + degrees) % 360.0
        if (z > 180) z -= 360.0
        if (z <= -180) z += 360.0
        commit(withField(d, id, "rot.z", z) ?: return)
    }

    private fun setRaw(s: Shape, field: String, v: Double): Shape {
        fun axis(c: Char) = when (c) { 'x' -> 0; 'y' -> 1; else -> 2 }
        return when {
            field.startsWith("size.") -> s.copy(size = s.size.with(axis(field.last()), v.coerceAtLeast(0.01)))
            field.startsWith("pos.") -> s.copy(pos = s.pos.with(axis(field.last()), v))
            field.startsWith("rot.") -> s.copy(rot = s.rot.with(axis(field.last()), v))
            else -> s.copy(extra = s.extra + (field to v))
        }
    }

    fun delete() {
        val d = design ?: return
        val ids = selected.toSet()
        if (ids.isEmpty()) return
        fun prune(list: List<Shape>): List<Shape> = list.filter { it.id !in ids }.map { if (it.isGroup) it.copy(children = prune(it.children)) else it }
        commit(d.copy(shapes = prune(d.shapes).filter { !it.isGroup || it.children.isNotEmpty() }), emptyList())
    }

    /** Copies the selected top-level shapes, 10 mm to the right and back. */
    fun duplicate() {
        val d = design ?: return
        var next = d.nextId
        fun renumber(s: Shape): Shape = s.copy(id = next++, children = s.children.map(::renumber))
        val tops = d.shapes.filter { it.id in selected }
        if (tops.isEmpty()) return
        val copies = tops.map { renumber(it).copy(pos = it.pos + D3(10.0, 10.0, 0.0)) }
        commit(d.copy(shapes = d.shapes + copies, nextId = next), copies.map { it.id })
    }

    fun setHole(hole: Boolean) {
        val d = design ?: return
        commit(selected.fold(d) { acc, id -> acc.update(id) { it.copy(hole = hole) } })
    }

    fun mirror(axis: Int) {
        val d = design ?: return
        commit(selected.fold(d) { acc, id -> acc.update(id) { s -> s.copy(mirror = s.mirror.with(axis, if (s.mirror[axis] != 0.0) 0.0 else 1.0)) } })
    }

    /** Moves the selection by [delta] mm (arrow keys, dragging in the preview). */
    fun move(delta: D3, ids: List<Int> = selected) {
        val d = design ?: return
        if (ids.isEmpty() || delta == D3()) return
        commit(ids.fold(d) { acc, id -> acc.update(id) { s -> s.copy(pos = s.pos + delta) } })
    }

    /** Puts the selected shapes' boxes on one line: [mode] -1 = min side, 0 = centre, 1 = max side. */
    fun align(axis: Int, mode: Int) {
        val d = design ?: return
        val shapes = selected.mapNotNull { id -> d.shapes.firstOrNull { it.id == id } }
        if (shapes.size < 2) return
        val boxes = shapes.map { d.bounds(it) }
        val all = boxes.reduce { a, b -> a.union(b) }
        val target = when (mode) { -1 -> all.min[axis]; 1 -> all.max[axis]; else -> all.center[axis] }
        var next = d
        shapes.zip(boxes).forEach { (s, b) ->
            val at = when (mode) { -1 -> b.min[axis]; 1 -> b.max[axis]; else -> b.center[axis] }
            next = next.update(s.id) { it.copy(pos = it.pos.with(axis, it.pos[axis] + target - at)) }
        }
        commit(next)
    }

    /** Puts the selection on the workplane (z = 0). */
    fun drop() {
        val d = design ?: return
        commit(selected.mapNotNull { id -> d.shapes.firstOrNull { it.id == id } }.fold(d) { acc, s ->
            val b = acc.bounds(s)
            acc.update(s.id) { it.copy(pos = it.pos.with(2, it.pos.z - b.min.z)) }
        })
    }

    fun setRepeat(id: Int, repeat: Repeat?) = update(id) { it.copy(repeat = repeat) }

    // --- Groups ------------------------------------------------------------------------------------

    /** Groups the selected top-level shapes: holes in the group cut its solids. */
    fun group() {
        val d = design ?: return
        val members = d.shapes.filter { it.id in selected }
        if (members.size < 2) return
        val b = members.map { d.bounds(it) }.reduce { a, c -> a.union(c) }
        val origin = D3(snap(b.center.x), snap(b.center.y), b.min.z)
        val kids = members.map { it.copy(pos = it.pos - origin) }
        val g = Shape(d.nextId, Shape.Kind.GROUP, store.str(R.string.shape_group) + " " + d.nextId, D3(), origin, children = kids)
        val at = d.shapes.indexOfFirst { it.id in selected }
        val rest = d.shapes.filter { it.id !in selected }.toMutableList()
        rest.add(at.coerceAtMost(rest.size), g)
        commit(d.copy(shapes = rest, nextId = d.nextId + 1), listOf(g.id))
    }

    /** Takes the selected groups apart, keeping where their shapes are. */
    fun ungroup() {
        val d = design ?: return
        val groups = d.shapes.filter { it.id in selected && it.isGroup }
        if (groups.isEmpty()) return
        val out = ArrayList<Shape>()
        val sel = ArrayList<Int>()
        for (s in d.shapes) {
            if (s !in groups) { out += s; continue }
            val h = d.bounds(s.copy(pos = D3(), rot = D3(), repeat = null)).max.z
            val gp = d.pos(s)
            val gr = d.rot(s)
            for (c in s.children) {
                val ch = d.size(c).z.takeUnless { c.isGroup } ?: d.bounds(c.copy(pos = D3(), rot = D3(), repeat = null)).max.z
                // The child's box centre through the group's turn; turns add up (exact for one axis).
                val centre = ShapeDesign.rotate(c.pos + D3(0.0, 0.0, ch / 2) - D3(0.0, 0.0, h / 2), gr) + D3(0.0, 0.0, h / 2) + gp
                out += c.copy(
                    pos = centre - D3(0.0, 0.0, ch / 2),
                    rot = if (gr == D3()) c.rot else c.rot + gr,
                    hole = c.hole || s.hole,
                    repeat = c.repeat ?: s.repeat,
                )
                sel += c.id
            }
        }
        commit(d.copy(shapes = out), sel)
    }

    // --- Dimensions (sliders) ---------------------------------------------------------------------

    /** A new slider named [name] that drives [field] of shape [id], starting at its current value. */
    fun makeSlider(id: Int, field: String, name: String) {
        val d = design ?: return
        val s = d.find(id) ?: return
        val ident = identifier(name, d)
        val v = current(d, s, field)
        val dim = Dim(ident, v, if (field.startsWith("pos.") || field.startsWith("rot.")) minOf(v, -100.0) else 0.0,
            if (field.startsWith("rot.")) 360.0 else maxOf(100.0, Math.ceil(v * 2 / 10) * 10), 1.0)
        commit(d.copy(dims = d.dims + dim).update(id) { it.copy(bind = it.bind + (field to ident)) })
    }

    /** Customizer "Add a slider" in the builder: a new dimension no shape follows yet; returns its name. */
    fun addDim(label: String, value: Double, min: Double, max: Double, step: Double): String? {
        val d = design ?: return null
        val ident = identifier(label, d)
        commit(d.copy(dims = d.dims + Dim(ident, value, minOf(min, value), maxOf(max, value), step)))
        return ident
    }

    fun bind(id: Int, field: String, dim: String?) {
        val d = design ?: return
        val s = d.find(id) ?: return
        // Unlinking keeps the value the field had.
        if (dim == null) commit(d.update(id) { setRaw(it, field, current(d, s, field)).copy(bind = it.bind - field) })
        else commit(d.update(id) { it.copy(bind = it.bind + (field to dim)) })
    }

    fun setDim(dim: Dim) {
        val d = design ?: return
        commit(d.copy(dims = d.dims.map { if (it.name == dim.name) dim else it }))
    }

    fun removeDim(name: String) {
        val d = design ?: return
        var next = d.copy(dims = d.dims.filter { it.name != name })
        d.shapes.flatMap { it.all().toList() }.filter { s -> s.bind.containsValue(name) }.forEach { s ->
            next = next.update(s.id) { sh ->
                sh.bind.filterValues { it == name }.keys.fold(sh) { acc, f -> setRaw(acc, f, current(d, s, f)) }.copy(bind = sh.bind.filterValues { it != name })
            }
        }
        commit(next)
    }

    private fun current(d: ShapeDesign, s: Shape, field: String): Double {
        val raw = when {
            field.startsWith("size.") -> s.size["xyz".indexOf(field.last())]
            field.startsWith("pos.") -> s.pos["xyz".indexOf(field.last())]
            field.startsWith("rot.") -> s.rot["xyz".indexOf(field.last())]
            else -> s.extra[field] ?: 0.0
        }
        return d.value(s, field, raw)
    }

    private fun identifier(label: String, d: ShapeDesign): String {
        val base = label.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
            .let { if (it.isEmpty() || it[0].isDigit()) "d_$it" else it }
        var name = base
        var i = 2
        while (d.dim(name) != null) name = base + "_" + i++
        return name
    }

    companion object {
        fun snap(v: Double, grid: Double = 1.0) = Math.round(v / grid) * grid

        fun kindName(k: Shape.Kind) = when (k) {
            Shape.Kind.BOX -> R.string.shape_box
            Shape.Kind.ROUNDED_BOX -> R.string.shape_rounded_box
            Shape.Kind.CYLINDER -> R.string.shape_cylinder
            Shape.Kind.PRISM -> R.string.shape_prism
            Shape.Kind.CONE -> R.string.shape_cone
            Shape.Kind.PYRAMID -> R.string.shape_pyramid
            Shape.Kind.SPHERE -> R.string.shape_sphere
            Shape.Kind.DOME -> R.string.shape_dome
            Shape.Kind.TORUS -> R.string.shape_torus
            Shape.Kind.TUBE -> R.string.shape_tube
            Shape.Kind.WEDGE -> R.string.shape_wedge
            Shape.Kind.TEXT -> R.string.shape_text
            Shape.Kind.GROUP -> R.string.shape_group
        }

        /** World-space bounds of shape [id] (its groups' moves and turns applied; copies ignored). */
        fun worldBounds(d: ShapeDesign, id: Int): Bounds? {
            fun path(list: List<Shape>): List<Shape>? {
                for (s in list) {
                    if (s.id == id) return listOf(s)
                    path(s.children)?.let { return listOf(s) + it }
                }
                return null
            }
            val p = path(d.shapes) ?: return null
            var b = d.bounds(p.last().let { if (p.size > 1) it.copy(repeat = null) else it })
            for (g in p.dropLast(1).asReversed()) {
                val h = d.bounds(g.copy(pos = D3(), rot = D3(), repeat = null)).max.z
                val r = d.rot(g)
                val gp = d.pos(g)
                val pivot = D3(0.0, 0.0, h / 2)
                b = Bounds.of((0 until 8).map { i ->
                    val c = D3(if (i and 1 == 0) b.min.x else b.max.x, if (i and 2 == 0) b.min.y else b.max.y, if (i and 4 == 0) b.min.z else b.max.z)
                    ShapeDesign.rotate(c - pivot, r) + pivot + gp
                })
            }
            return b
        }

        /**
         * Preview handles for shape [id]: whether its world box is its own size (a plain shape, no
         * turn, no copies, unturned groups) and its turn pivot in world space (null under a turned group).
         */
        fun handles(d: ShapeDesign, id: Int): Pair<Boolean, D3?> {
            fun path(list: List<Shape>): List<Shape>? {
                for (s in list) {
                    if (s.id == id) return listOf(s)
                    path(s.children)?.let { return listOf(s) + it }
                }
                return null
            }
            val p = path(d.shapes) ?: return false to null
            val groups = p.dropLast(1)
            if (groups.any { d.rot(it) != D3() }) return false to null
            val s = p.last()
            val h = if (s.isGroup) d.bounds(s.copy(pos = D3(), rot = D3(), repeat = null)).max.z else d.size(s).z
            val pivot = groups.fold(d.pos(s) + D3(0.0, 0.0, h / 2)) { acc, g -> acc + d.pos(g) }
            return (!s.isGroup && s.repeat == null && d.rot(s) == D3()) to pivot
        }
    }
}
