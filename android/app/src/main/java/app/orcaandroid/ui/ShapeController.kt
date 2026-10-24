package app.orcaandroid.ui

import app.orcaandroid.R
import app.orcaandroid.core.Instance
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.RotateGizmo
import app.orcaandroid.scad.Bounds
import app.orcaandroid.scad.BuildTransform
import app.orcaandroid.scad.D3
import app.orcaandroid.scad.Dim
import app.orcaandroid.scad.Repeat
import app.orcaandroid.scad.Shape
import app.orcaandroid.scad.ShapeDesign
import app.orcaandroid.scad.Sketch
import app.orcaandroid.scad.Stl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    /** Replaces the whole design (AI tools) as one undo step; by default selected shapes that still exist stay selected. */
    fun replace(next: ShapeDesign, sel: List<Int> = selected.filter { next.find(it) != null }) = commit(next, sel)

    /** Tap: only [id]; with Ctrl / Shift / long press ([add]) toggles it in the selection. */
    fun tap(id: Int?, add: Boolean) {
        if (id == null) { if (!add) select(emptyList()); return }
        select(if (!add) listOf(id) else if (id in selected) selected - id else selected + id)
    }

    fun selectAll() = design?.let { d -> select(d.shapes.map { it.id }) }

    /** Build workspace: a tap in the main view picks the shape under it (as [tap]). */
    fun tapAt(origin: Vec3, dir: Vec3, add: Boolean) {
        val obj = store.value.buildObject ?: return
        store.launch { tap(scad.shapeAt(obj, origin, dir)?.second, add) }
    }

    /** The copy of the build object a main-view drag grabbed: its turn, scale and mirror map the drag into the design. */
    private var grabbed = 0

    /** Build workspace: [result] gets whether a drag starting on the ray grabs a selected shape. */
    fun grabAt(origin: Vec3, dir: Vec3, result: (Boolean) -> Unit) {
        val obj = store.value.buildObject ?: return result(false)
        store.launch {
            val hit = scad.shapeAt(obj, origin, dir)
            val d = design
            if (hit != null) grabbed = hit.first
            result(hit != null && d != null && selected.any { d.topOf(it)?.id == hit.second })
        }
    }

    /** Build workspace: a main-view drag moved the selection [dx], [dy] mm on the plate. */
    fun moveOnPlate(dx: Float, dy: Float) {
        val inst = store.value.buildObject?.let { store.value.scene.objects.getOrNull(it) }?.instances?.let { it.getOrNull(grabbed) ?: it.firstOrNull() }?.shown ?: return
        // Plate -> design frame: the copy's matrix is translation * rotation * scale * mirror (mirror = +-1 per axis).
        val r = RotateGizmo.fromEuler(inst.rotation)
        fun local(i: Int, scale: Float, mirror: Float) = (r[i] * dx + r[3 + i] * dy) / scale.toDouble() * (if (mirror < 0f) -1.0 else 1.0)
        move(D3(local(0, inst.scale.x, inst.mirror.x), local(1, inst.scale.y, inst.mirror.y), local(2, inst.scale.z, inst.mirror.z)))
    }

    /** Build workspace: the copy the gizmos sit on (they show only while the object has one) and the selected top-level shapes. */
    fun buildGizmoTarget(): Pair<Instance, List<Shape>>? {
        val inst = store.value.buildObject?.let { store.value.scene.objects.getOrNull(it) }?.instances?.singleOrNull()?.shown ?: return null
        val d = design ?: return null
        return (inst to selected.mapNotNull { d.topOf(it) }.distinctBy { it.id }).takeIf { it.second.isNotEmpty() }
    }

    /** Build workspace: the main view's scale gizmo scaled the selection by [scale] (plate axes) about plate point [pivot]. */
    fun resizeOnPlate(scale: Vec3, pivot: Vec3) {
        val (inst, tops) = buildGizmoTarget() ?: return
        val d = design ?: return
        // Off the axes the gizmo has corners only: an even scale.
        val f = BuildTransform.designScale(inst, scale) ?: D3(scale.x.toDouble(), scale.x.toDouble(), scale.x.toDouble())
        val p = BuildTransform.toDesign(inst, pivot)
        commit(tops.fold(d) { acc, s -> acc.find(s.id)?.let { BuildTransform.scaled(acc, it, f, p) }?.let { posed(acc, s.id, it) } ?: acc })
    }

    /** Build workspace: the main view's rotate gizmo turned the selection [degrees] about plate axis [axis] through plate point [pivot]. */
    fun turnOnPlate(axis: Int, degrees: Float, pivot: Vec3) {
        val (inst, tops) = buildGizmoTarget() ?: return
        val d = design ?: return
        val m = BuildTransform.designTurn(inst, RotateGizmo.matrix(axis, degrees))
        val c = BuildTransform.toDesign(inst, pivot)
        commit(tops.fold(d) { acc, s -> acc.find(s.id)?.let { posed(acc, s.id, BuildTransform.turned(acc, it, m, c)) } ?: acc })
    }

    /** [d] with shape [id] at [pose]; only changed fields are set, so a field bound to a dimension moves it only when it changes. */
    private fun posed(d: ShapeDesign, id: Int, pose: BuildTransform.Pose): ShapeDesign {
        val s = d.find(id) ?: return d
        var next = d
        for ((name, now, to) in listOf(Triple("pos", d.pos(s), pose.pos), Triple("rot", d.rot(s), pose.rot), Triple("size", d.size(s), pose.size)))
            for (i in 0..2) if (kotlin.math.abs(now[i] - to[i]) > 1e-6) next = withField(next, id, "$name.${"xyz"[i]}", to[i]) ?: next
        return if (pose.repeat != s.repeat) next.update(id) { it.copy(repeat = pose.repeat) } else next
    }

    // --- Adding ------------------------------------------------------------------------------------

    /** Adds a new [kind] shape beside the others and selects it; returns its id. */
    fun add(kind: Shape.Kind): Int? {
        val d = design ?: return null
        val name = store.str(kindName(kind)) + " " + (d.shapes.flatMap { it.all().toList() }.count { it.kind == kind } + 1)
        val shape = beside(d, ShapeDesign.create(kind, d.nextId, name))
        commit(d.copy(shapes = d.shapes + shape, nextId = d.nextId + 1), listOf(shape.id))
        return shape.id
    }

    /** Adds a picked STL as a mesh shape, at its own size; the file is kept in files/openscad/meshes. */
    fun importMesh(uri: android.net.Uri) = store.launch {
        val (file, b) = withContext(Dispatchers.IO) {
            val src = store.resources.importFile(uri, "openscad")
            val dir = File(store.app.filesDir, "openscad/meshes").apply { mkdirs() }
            var dst = File(dir, src.name)
            var i = 2
            while (dst.exists() && dst.length() != src.length()) dst = File(dir, src.nameWithoutExtension + "_" + i++ + "." + src.extension)
            src.copyTo(dst, overwrite = true)
            src.delete()
            dst to Stl.bounds(dst)
        }
        if (b == null) { file.delete(); store.toast(store.str(R.string.shape_import_failed)); return@launch }
        val d = design ?: return@launch
        val size = D3(maxOf(0.01, b[3] - b[0]), maxOf(0.01, b[4] - b[1]), maxOf(0.01, b[5] - b[2]))
        val shape = beside(d, Shape(d.nextId, Shape.Kind.MESH, file.nameWithoutExtension, size, file = file.absolutePath,
            extra = Shape.MESH_BOUNDS.zip(b).toMap()))
        commit(d.copy(shapes = d.shapes + shape, nextId = d.nextId + 1), listOf(shape.id))
    }

    /** [shape] placed beside what is there already (right of it, on the workplane), so it can be seen and picked. */
    fun beside(d: ShapeDesign, shape: Shape): Shape {
        if (d.shapes.isEmpty()) return shape
        val all = d.shapes.map { d.bounds(it) }.reduce { a, b -> a.union(b) }
        return shape.copy(pos = D3(snap(all.max.x + 5 + shape.size.x / 2), snap(all.center.y), 0.0))
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

    /** [d] with [field] of shape [id] set to [value]; a field bound to a dimension changes the dimension instead. */
    fun withField(d: ShapeDesign, id: Int, field: String, value: Double): ShapeDesign? {
        val s = d.find(id) ?: return null
        val dim = s.bind[field]?.let { d.dim(it) }
        if (dim != null) return d.withDims(d.dims.map { if (it.name == dim.name) it.copy(value = value, min = minOf(it.min, value), max = maxOf(it.max, value)) else it })
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

    /**
     * New drawing for sketch shape [id] (one undo step): its box takes the drawing's size, 1:1. [dims]: the
     * sliders as the sketch editor left them (other sketches following a changed one are solved again).
     */
    fun setSketch(id: Int, sketch: Sketch, dims: List<Dim>? = null) {
        val d = design ?: return
        commit((if (dims != null) d.withDims(dims) else d).update(id) { ShapeDesign.fitSketch(it, sketch) })
    }

    /** Sketch shape [id]: extrude ([revolve] false) or revolve about Z. */
    fun setRevolve(id: Int, revolve: Boolean) {
        val d = design ?: return
        commit(d.update(id) { s -> ShapeDesign.fitSketch(s.copy(extra = s.extra + (Shape.REVOLVE to if (revolve) 1.0 else 0.0)), s.sketch ?: Sketch.START) })
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
        commit(grouped(d, R.string.shape_group) ?: return, listOf(d.nextId))
    }

    /** [d] with the selected top-level shapes (two or more) in a new group, id [ShapeDesign.nextId], named [name] + id. */
    private fun grouped(d: ShapeDesign, name: Int): ShapeDesign? {
        val members = d.shapes.filter { it.id in selected }
        if (members.size < 2) return null
        val b = members.map { d.bounds(it) }.reduce { a, c -> a.union(c) }
        val origin = D3(snap(b.center.x), snap(b.center.y), b.min.z)
        val kids = members.map { placed(d, it, d.pos(it) - origin, d.rot(it)) }
        val g = Shape(d.nextId, Shape.Kind.GROUP, store.str(name) + " " + d.nextId, D3(), origin, children = kids)
        val at = d.shapes.indexOfFirst { it.id in selected }
        val rest = d.shapes.filter { it.id !in selected }.toMutableList()
        rest.add(at.coerceAtMost(rest.size), g)
        return d.copy(shapes = rest, nextId = d.nextId + 1)
    }

    /**
     * Merge: the selected top-level shapes (two or more, or one group) baked into one mesh shape, holes cut
     * ([ShapeDesign.merged]). It moves, colours and renders as one solid; its parts are no longer editable (undo
     * brings them back). The render runs first; an edit meanwhile drops the result.
     */
    fun merge(): kotlinx.coroutines.Job? {
        val d = design ?: return null
        val tops = d.shapes.filter { it.id in selected }
        val (g, id) = when {
            tops.size > 1 -> (grouped(d, R.string.shape_merged) ?: return null) to d.nextId
            tops.singleOrNull()?.isGroup == true -> d to tops[0].id
            else -> return null
        }
        return store.launch {
            val file = scad.renderMesh(g.pieces().first { it.shapeId == id }.scad) ?: return@launch
            val b = withContext(Dispatchers.IO) { Stl.bounds(file) }
            if (b == null) { store.toast(store.str(R.string.shape_import_failed)); return@launch }
            if (design != d) { store.toast(store.str(R.string.shape_merge_changed)); return@launch }
            commit(g.merged(id, file.absolutePath, b), listOf(id))
        }
    }

    /** Takes the selected groups apart, keeping where their shapes are (the group's turn and mirror go to each shape). */
    fun ungroup() {
        val d = design ?: return
        val groups = d.shapes.filter { it.id in selected && it.isGroup }
        if (groups.isEmpty()) return
        val out = ArrayList<Shape>()
        val sel = ArrayList<Int>()
        for (s in d.shapes) {
            if (s !in groups) { out += s; continue }
            val h = d.height(s)
            val gp = d.pos(s)
            val gr = d.rot(s)
            val gm = s.mirror
            val g = ShapeDesign.matrix(gr)
            fun sign(i: Int) = if (gm[i] != 0.0) -1.0 else 1.0
            for (c in s.children) {
                val ch = d.height(c)
                // In OpenSCAD: T(gp + h/2) Rg Mg T(-h/2) T(cp + ch/2) Rc Mc T(-ch/2)
                //            = T(gp + h/2 + Rg Mg (cp + ch/2 - h/2)) (Rg Mg Rc Mg) (Mg Mc) T(-ch/2).
                val centre = ShapeDesign.rotate(ShapeDesign.flip(d.pos(c) + D3(0.0, 0.0, ch / 2 - h / 2), gm), gr) + D3(0.0, 0.0, h / 2) + gp
                val rot = if (gr == D3() && gm == D3()) d.rot(c) else {
                    val r = ShapeDesign.matrix(d.rot(c))
                    ShapeDesign.angles(Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> g[i][k] * r[k][j] * sign(k) * sign(j) } } })
                }
                fun xor(i: Int) = if ((gm[i] != 0.0) != (c.mirror[i] != 0.0)) 1.0 else 0.0
                out += placed(d, c, centre - D3(0.0, 0.0, ch / 2), rot).copy(
                    mirror = D3(xor(0), xor(1), xor(2)),
                    hole = c.hole || s.hole,
                    repeat = c.repeat ?: s.repeat,
                )
                sel += c.id
            }
        }
        commit(d.copy(shapes = out), sel)
    }

    /** [s] at [pos] turned by [rot]; a field bound to a dimension stays bound only while its value is unchanged. */
    private fun placed(d: ShapeDesign, s: Shape, pos: D3, rot: D3): Shape {
        var bind = s.bind
        for ((k, old, new) in listOf(Triple("pos", d.pos(s), pos), Triple("rot", d.rot(s), rot))) for (i in 0..2) {
            if (kotlin.math.abs(old[i] - new[i]) > 1e-9) bind = bind - "$k.${"xyz"[i]}"
        }
        return s.copy(pos = pos, rot = rot, bind = bind)
    }

    // --- Dimensions (sliders) ---------------------------------------------------------------------

    /** A new slider named [name] that drives [field] of shape [id], starting at its current value. */
    fun makeSlider(id: Int, field: String, name: String) {
        val d = design ?: return
        val s = d.find(id) ?: return
        val ident = identifier(name, d)
        val dim = Dim.forLength(ident, current(d, s, field), signed = field.startsWith("pos.") || field.startsWith("rot."),
            angle = field.startsWith("rot.") || field == Shape.ANGLE, small = field in setOf(Shape.EDGE, Shape.SHELL, Shape.WALL))
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
        commit(d.withDims(d.dims.map { if (it.name == dim.name) dim else it }))
    }

    fun removeDim(name: String) {
        val d = design ?: return
        val value = d.dim(name)?.value ?: 0.0
        // Sketch lengths that followed it keep its value.
        var next = d.copy(dims = d.dims.filter { it.name != name }).mapShapes { s ->
            val sk = s.sketch
            if (sk == null || sk.cons.none { it.dim == name }) s
            else s.copy(sketch = sk.copy(cons = sk.cons.map { if (it.dim == name) it.copy(value = value, dim = null) else it }))
        }
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

    private fun identifier(label: String, d: ShapeDesign) = Dim.ident(label) { d.dim(it) != null }

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
            Shape.Kind.SKETCH -> R.string.shape_sketch
            Shape.Kind.MESH -> R.string.shape_mesh
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
                val r = d.rot(g)
                val gp = d.pos(g)
                val pivot = D3(0.0, 0.0, d.height(g) / 2)
                b = Bounds.of((0 until 8).map { i ->
                    val c = D3(if (i and 1 == 0) b.min.x else b.max.x, if (i and 2 == 0) b.min.y else b.max.y, if (i and 4 == 0) b.min.z else b.max.z)
                    ShapeDesign.rotate(ShapeDesign.flip(c - pivot, g.mirror), r) + pivot + gp
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
            if (groups.any { d.rot(it) != D3() || it.mirror != D3() }) return false to null
            val s = p.last()
            val pivot = groups.fold(d.pos(s) + D3(0.0, 0.0, d.height(s) / 2)) { acc, g -> acc + d.pos(g) }
            return (!s.isGroup && s.repeat == null && d.rot(s) == D3()) to pivot
        }
    }
}
