package app.orcaandroid.ai

import app.orcaandroid.R
import app.orcaandroid.scad.D3
import app.orcaandroid.scad.Repeat
import app.orcaandroid.scad.Shape
import app.orcaandroid.scad.ShapeDesign
import app.orcaandroid.scad.Sketch
import app.orcaandroid.ui.ScadController
import app.orcaandroid.ui.ShapeController
import app.orcaandroid.ui.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools AI clients get over MCP ([McpServer]). They go through the UI's controllers on the main
 * thread, so every edit shows up live in the editor and is one undo step there. Putting a design on
 * the plate waits for the user's Allow on screen.
 */
class McpTools(private val store: Store, private val scad: ScadController, private val shapes: ShapeController) {

    /** [run] returns the reply text, or a ready-made `content` [JSONArray] (e.g. an image) for [reply]. */
    private class Tool(val name: String, val description: String, val schema: JSONObject, val run: suspend (JSONObject) -> Any)

    private class ToolError(message: String) : Exception(message)

    /** The on-screen question a tool waits for (main thread only). */
    private var pending: CompletableDeferred<Boolean>? = null

    /** The user's answer to [UiState.aiConfirm]. */
    fun answer(allow: Boolean) {
        pending?.complete(allow)
    }

    private val tools = listOf(
        Tool("design_get", "Read: the shape-builder design in the app's design editor, as JSON " +
            "({name, selected, design}).$OPENS design is the shape tree (see design_set for the format); when the " +
            "editor holds hand-written OpenSCAD code instead, source has the code and design is null.", obj()) { get() },
        Tool("design_describe", "Read: the open design as a short indented text tree (id, kind, name, size, position, " +
            "rotation, hole, repeat, dimension bindings, bounds). Cheapest way to see the design; prefer it over design_get.$OPENS",
            obj()) { describe() },
        Tool("design_snapshot", "Read: a small PNG picture of the open design, rendered from the current preview (shaded, " +
            "seen from the front-left above). Use it only when the shape matters; design_describe is far cheaper.$OPENS",
            obj(emptyList(), "size" to p("integer", "Image size in px (square), 128..1024; default 512"))) { snapshot(it) },
        Tool("design_set", "Edit: replaces the whole shape-builder design.$OPENS " +
            "Prefer the shape_* tools for small changes. $EDIT Units: mm and degrees. $FORMAT",
            obj(listOf("design"), "design" to p("object", "The whole design: {shapes: [...], dims: [...]} as returned by design_get."))) { set(it) },
        Tool("shape_add", "Edit: adds one shape (any kind but group / mesh) and selects it. Without pos it is placed right " +
            "of the others on the workplane. Returns its id in the tree. $EDIT",
            obj(listOf("kind"), "kind" to p("string", "box, rounded_box, cylinder, prism, cone, pyramid, sphere, dome, torus, tube, wedge, text, sketch"),
                *FIELDS)) { add(it) },
        Tool("shape_update", "Edit: changes the given fields of shape id; others stay. A field bound to a dim changes " +
            "the dim (and every shape following it). $EDIT", obj(listOf("id"), "id" to p("integer", "Shape id"), *FIELDS)) { update(it) },
        Tool("shape_delete", "Edit: deletes shapes (a group left empty goes too). $EDIT", obj(listOf("ids"), "ids" to ids())) { args ->
            val ids = args.ids()
            edit("deleted ${ref(ids)}") { d -> d.need(ids); shapes.select(ids); shapes.delete() }
        },
        Tool("shape_move", "Edit: moves shapes by [dx, dy, dz] mm (in their parent's frame). $EDIT",
            obj(listOf("ids", "by"), "ids" to ids(), "by" to vec("Offset [dx, dy, dz] in mm"))) { args ->
            val ids = args.ids()
            val by = args.vec3("by") ?: throw ToolError("by must be 3 numbers")
            edit("moved ${ref(ids)}") { d -> d.need(ids); shapes.move(by, ids) }
        },
        Tool("shape_align", "Edit: lines up two or more top-level shapes' bounding boxes on one axis. $EDIT",
            obj(listOf("ids", "axis", "to"), "ids" to ids(), "axis" to p("string", "x, y or z"), "to" to p("string", "min, center or max"))) { args ->
            val ids = args.ids()
            val axis = "xyz".indexOf(args.optString("axis")).takeIf { args.optString("axis").length == 1 && it >= 0 }
                ?: throw ToolError("axis must be x, y or z")
            val mode = listOf("min", "center", "max").indexOf(args.optString("to")).takeIf { it >= 0 }?.minus(1)
                ?: throw ToolError("to must be min, center or max")
            edit("aligned ${ref(ids)}") { d -> d.need(ids, top = true, min = 2); shapes.select(ids); shapes.align(axis, mode) }
        },
        Tool("shape_group", "Edit: groups two or more top-level shapes; holes in a group cut only its own solids. Returns " +
            "the group's id in the tree. $EDIT", obj(listOf("ids"), "ids" to ids())) { args ->
            val ids = args.ids()
            edit("grouped ${ref(ids)}") { d -> d.need(ids, top = true, min = 2); shapes.select(ids); shapes.group() }
        },
        Tool("shape_ungroup", "Edit: takes top-level groups apart, keeping where their shapes are. $EDIT",
            obj(listOf("ids"), "ids" to ids())) { args ->
            val ids = args.ids()
            edit("ungrouped ${ref(ids)}") { d ->
                d.need(ids, top = true)
                ids.firstOrNull { d.find(it)?.isGroup != true }?.let { throw ToolError("#$it is not a group") }
                shapes.select(ids); shapes.ungroup()
            }
        },
        Tool("shape_duplicate", "Edit: copies top-level shapes 10 mm right and back; the copies get new ids. $EDIT",
            obj(listOf("ids"), "ids" to ids())) { args ->
            val ids = args.ids()
            edit("duplicated ${ref(ids)}") { d -> d.need(ids, top = true); shapes.select(ids); shapes.duplicate() }
        },
        Tool("sketch_set", "Edit: the drawing a sketch shape extrudes (or, revolve true, turns about Z); its box takes " +
            "the drawing's size. $SKETCH $EDIT",
            obj(listOf("id", "sketch"), "id" to p("integer", "Id of a sketch shape"), "sketch" to p("object", "{loops, cons}"),
                "revolve" to p("boolean", "true = revolve about Z, false = extrude; omitted = unchanged"))) { sketch(it) },
        Tool("dim_set", "Edit: creates or changes a dim (a named number shown as a slider; shapes follow it through " +
            "bind). $EDIT", obj(listOf("name"), "name" to p("string", "Identifier (letters, digits, _)"),
                "value" to p("number", "Required for a new dim"), "min" to p("number", "Slider minimum"),
                "max" to p("number", "Slider maximum"), "step" to p("number", "Slider step"))) { dimSet(it) },
        Tool("dim_bind", "Edit: makes a shape field follow a dim, or (dim null) unlinks it keeping its value. $EDIT",
            obj(listOf("id", "field"), "id" to p("integer", "Shape id"),
                "field" to p("string", "size.x|y|z, pos.x|y|z, rot.x|y|z or an extra key (radius, wall, ...)"),
                "dim" to JSONObject().put("type", JSONArray().put("string").put("null")).put("description", "Dim name, or null to unlink"))) { bind(it) },
        Tool("dim_remove", "Edit: removes a dim; fields that followed it keep its value. $EDIT",
            obj(listOf("name"), "name" to p("string", "Dim name"))) { args ->
            val name = args.optString("name")
            edit("removed dim $name") { d -> d.dim(name) ?: throw ToolError("No dim '$name'"); shapes.removeDim(name) }
        },
        Tool("design_preview", "Read: renders the open design in the app's preview (OpenSCAD) and waits for it. Returns " +
            "the status, the result's size and OpenSCAD errors / warnings. Use it to check a design after editing.", obj()) { preview() },
        Tool("plate_describe", "Read: the print set-up and plate(s): the chosen printer, process and filaments, then for " +
            "each plate its objects with their on-plate size in mm (and the slice estimate when it has been sliced). This is " +
            "the plate the user prints; the design_* tools show the shape-builder design, which is separate until design_to_plate.",
            obj()) { plateDescribe() },
        Tool("design_to_plate", "Confirm: puts the open design on the active plate (replacing the object it made before). " +
            "Waits up to 60 s for the user to tap Allow in the app; fails with \"declined by the user\" otherwise.", obj()) { toPlate() },
    )

    fun list(): JSONArray = JSONArray().apply {
        tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.schema)) }
    }

    suspend fun call(name: String, args: JSONObject): JSONObject {
        val tool = tools.firstOrNull { it.name == name } ?: return reply("Unknown tool: $name", error = true)
        return try {
            when (val out = tool.run(args)) {
                is JSONArray -> JSONObject().put("content", out)
                else -> reply(out.toString())
            }
        } catch (e: ToolError) {
            reply(e.message.orEmpty(), error = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("OrcaMcp", "tool $name failed", e)
            reply("Failed: ${e.message ?: e}", error = true)
        }
    }

    private fun reply(text: String, error: Boolean = false) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .apply { if (error) put("isError", true) }

    // --- Read ---------------------------------------------------------------------------------------

    private suspend fun get(): String = withContext(Dispatchers.Main.immediate) {
        val st = editor()
        JSONObject().put("name", st.name)
            .put("selected", JSONArray(st.selected))
            .put("design", st.shapes?.json() ?: JSONObject.NULL)
            .apply { if (st.shapes == null) put("source", st.source) }
            .toString(2)
    }

    private suspend fun describe(): String = withContext(Dispatchers.Main.immediate) {
        val st = editor()
        val d = st.shapes ?: return@withContext "The editor holds hand-written OpenSCAD code (${st.source.lines().size} lines), not a shape design."
        describe(st.name, d, st.selected)
    }

    private fun describe(name: String, d: ShapeDesign, selected: List<Int>) = buildString {
        val count = d.shapes.sumOf { it.all().count() }
        append("Design \"$name\": $count shape(s)")
        if (selected.isNotEmpty()) append(", selected ${ref(selected)}")
        appendLine()
        d.dims.forEach { appendLine("dim ${it.name} = ${n(it.value)} (${n(it.min)}..${n(it.max)} step ${n(it.step)})") }
        fun line(s: Shape, depth: Int) {
            append("  ".repeat(depth)).append("#${s.id} ${s.kind.key} \"${s.name}\"")
            if (s.hole) append(" HOLE")
            if (!s.isGroup) append(" size ${v(d.size(s))}")
            append(" pos ${v(d.pos(s))}")
            d.rot(s).takeIf { it != D3() }?.let { append(" rot ${v(it)}") }
            s.mirror.takeIf { it != D3() }?.let { append(" mirror ${v(it)}") }
            s.extra.forEach { (k, x) -> append(" $k=${n(x)}") }
            if (s.text.isNotEmpty()) append(" text \"${s.text}\"")
            s.repeat?.let { r -> append(if (r.circular) " repeat ${r.count}x around ${v(r.center)} over ${n(r.angle)}°" else " repeat ${r.count}x step ${v(r.step)}") }
            s.sketch?.let { sk -> append(" sketch ${sk.loops.size} loop(s)") }
            s.bind.forEach { (f, dim) -> append(" $f<-$dim") }
            val b = d.bounds(s)
            append(" bounds ${v(b.min)}..${v(b.max)}")
            appendLine()
            s.children.forEach { line(it, depth + 1) }
        }
        d.shapes.forEach { line(it, 0) }
        if (d.shapes.isEmpty()) appendLine("(empty)")
    }

    /** Renders the editor's design as its preview and reports how it went. */
    private suspend fun preview(): String = withContext(Dispatchers.Main.immediate) {
        editor()
        scad.preview()
        withTimeoutOrNull(RENDER_TIMEOUT) { scad.awaitIdle() } ?: throw ToolError("The preview takes longer than ${RENDER_TIMEOUT / 1000} s; try again later")
        val st = store.value.scad ?: throw ToolError("The design editor was closed")
        buildString {
            appendLine((if (st.statusError) "FAILED: " else "OK: ") + st.status.orEmpty())
            st.preview?.takeIf { !st.previewStale }?.let { m ->
                appendLine("size ${n(m.size.x.toDouble())} x ${n(m.size.y.toDouble())} x ${n(m.size.z.toDouble())} mm, ${m.triangles} triangles")
            }
            val notes = st.log.lines().filter { l -> NOTE_WORDS.any { l.contains(it, ignoreCase = true) } }
            if (notes.isNotEmpty()) appendLine(notes.take(15).joinToString("\n"))
        }
    }

    /** Renders the design's current preview to a PNG and returns it as MCP image content (plus a short caption). */
    private suspend fun snapshot(args: JSONObject): JSONArray {
        val size = if (args.has("size")) args.optInt("size").coerceIn(128, 1024) else 512
        val (stl, caption) = withContext(Dispatchers.Main.immediate) {
            editor()
            scad.preview()
            withTimeoutOrNull(RENDER_TIMEOUT) { scad.awaitIdle() } ?: throw ToolError("The preview takes longer than ${RENDER_TIMEOUT / 1000} s; try again later")
            val st = store.value.scad ?: throw ToolError("The design editor was closed")
            val path = scad.previewStlPath ?: throw ToolError(
                if (st.statusError) "Nothing to show: ${st.status.orEmpty()}" else "Nothing to show: the design is empty.")
            path to buildString {
                append("Design \"${st.name}\"")
                st.preview?.let { m -> append(", ${n(m.size.x.toDouble())} x ${n(m.size.y.toDouble())} x ${n(m.size.z.toDouble())} mm") }
                if (st.previewStale) append(" (the preview is out of date; run design_preview to refresh it)")
            }
        }
        val png = store.engine.snapshotPng(stl, size)
        return JSONArray()
            .put(JSONObject().put("type", "image").put("data", png).put("mimeType", "image/png"))
            .put(JSONObject().put("type", "text").put("text", caption))
    }

    private suspend fun plateDescribe(): String = withContext(Dispatchers.Main.immediate) {
        val s = store.value
        buildString {
            appendLine("Printer: ${s.printer ?: "(none selected)"}")
            appendLine("Process: ${s.print ?: "(none)"}")
            if (s.filaments.isEmpty()) appendLine("Filaments: (none)")
            else s.filaments.forEachIndexed { i, f -> appendLine("Filament ${i + 1}: ${f.preset}${f.color?.let { c -> " ($c)" } ?: ""}") }
            val scene = s.scene
            if (scene.objects.isEmpty()) { append("The plate is empty."); return@buildString }
            for (plate in scene.plates) {
                val objs = scene.objectsOn(plate.index)
                append("Plate ${plate.index + 1}")
                if (plate.index == s.activePlate) append(" (active)")
                if (plate.bedType.isNotEmpty()) append(", ${plate.bedType}")
                appendLine(": ${objs.size} object(s)")
                objs.forEach { o ->
                    val here = o.instances.filter { it.plate == plate.index }
                    append("  ${o.name}")
                    if (here.size > 1) append(" x${here.size}")
                    here.firstOrNull()?.size?.let { z -> append(" — ${n(z.x.toDouble())} x ${n(z.y.toDouble())} x ${n(z.z.toDouble())} mm") }
                    appendLine(", ${o.triangles} triangles")
                }
                s.results[plate.index]?.let { r ->
                    appendLine("  sliced: ${duration(r.printTimeSeconds)}, ${n(r.filamentGrams)} g, ${n(r.filamentMm / 1000.0)} m filament")
                }
            }
            scene.outsideCount.takeIf { it > 0 }?.let { append("$it object(s) are off the plate.") }
        }
    }

    // --- Edit ---------------------------------------------------------------------------------------

    /** The open editor, opened on the saved draft when closed (main thread). */
    private suspend fun editor() = store.value.scad ?: run {
        scad.open().join()
        store.value.scad ?: throw ToolError("The design editor could not be opened")
    }

    /** The open shape design (main thread); fails when the editor holds hand-written code. */
    private suspend fun design(): ShapeDesign = editor().shapes ?: throw ToolError("The design editor holds hand-written " +
        "OpenSCAD code. Ask the user to start a shape design (Add > Shape builder) first; these tools won't replace their code.")

    /**
     * Runs [change] on the open shape design on the main thread (it commits one undo step through
     * [shapes]), shows "AI: [summary]" and returns the new tree.
     */
    private suspend fun edit(summary: String, change: (ShapeDesign) -> Unit): String = withContext(Dispatchers.Main.immediate) {
        val before = design()
        change(before)
        val st = store.value.scad ?: throw ToolError("The design editor was closed")
        val after = st.shapes ?: before
        if (after == before) return@withContext "Nothing changed.\n" + describe(st.name, after, st.selected)
        store.toast(store.str(R.string.ai_edit, summary))
        describe(st.name, after, st.selected)
    }

    private suspend fun set(args: JSONObject): String {
        val o = args.optJSONObject("design") ?: throw ToolError("design must be an object {shapes, dims}")
        val problems = validate(o, store.value.scad?.shapes)
        if (problems.isNotEmpty()) throw ToolError("Design not valid:\n" + problems.joinToString("\n"))
        val next = ShapeDesign.of(o)
        return edit("changed the design (${next.shapes.sumOf { it.all().count() }} shapes)") { shapes.replace(next) }
    }

    private suspend fun add(args: JSONObject): String {
        val kind = Shape.Kind.entries.firstOrNull { it.key == args.optString("kind") && it != Shape.Kind.GROUP && it != Shape.Kind.MESH }
            ?: throw ToolError("kind must be one of " + Shape.Kind.entries.filter { it != Shape.Kind.GROUP && it != Shape.Kind.MESH }.joinToString { it.key })
        return edit("added a ${kind.key}") { d ->
            val count = d.shapes.sumOf { s -> s.all().count { it.kind == kind } } + 1
            val shape = ShapeDesign.create(kind, d.nextId, store.str(ShapeController.kindName(kind)) + " " + count)
            var next = withFields(d.copy(shapes = d.shapes + shape, nextId = d.nextId + 1), shape.id, args)
            if (!args.has("pos")) next = next.update(shape.id) { shapes.beside(d, it) }
            shapes.replace(next, listOf(shape.id))
        }
    }

    private suspend fun update(args: JSONObject): String {
        val id = args.optInt("id", -1)
        return edit("changed #$id") { d ->
            d.need(listOf(id))
            if (d.find(id)?.isGroup == true && args.has("size")) throw ToolError("A group's size follows its shapes; change them instead")
            shapes.replace(withFields(d, id, args))
        }
    }

    /** [d0] with the fields of [o] set on shape [id]: numbers through [ShapeController.withField], so bound fields move their dim. */
    private fun withFields(d0: ShapeDesign, id: Int, o: JSONObject): ShapeDesign {
        var d = d0
        fun num(field: String, v: Double) { d = shapes.withField(d, id, field, v) ?: throw ToolError("No shape #$id") }
        for (key in listOf("size", "pos", "rot")) o.vec3(key)?.let { v ->
            if (key == "size" && (0..2).any { v[it] <= 0 }) throw ToolError("size must be > 0")
            "xyz".forEachIndexed { i, c -> num("$key.$c", v[i]) }
        }
        o.optJSONObject("extra")?.let { e ->
            e.keys().forEach { k ->
                if (k !in EXTRA_KEYS) throw ToolError("extra.$k is not one of ${EXTRA_KEYS.joinToString()}")
                if (e.isNull(k)) d = d.update(id) { it.copy(extra = it.extra - k, bind = it.bind - k) }
                else num(k, e.optDouble(k).takeIf { it.isFinite() } ?: throw ToolError("extra.$k must be a number or null"))
            }
        }
        if (o.has("name")) {
            val name = o.optString("name")
            if (name.isBlank() || name.any { it.isISOControl() }) throw ToolError("name must be text on one line")
            d = d.update(id) { it.copy(name = name) }
        }
        if (o.has("text")) d = d.update(id) { it.copy(text = o.optString("text").replace('\n', ' ')) }
        if (o.has("hole")) d = d.update(id) { it.copy(hole = o.optBoolean("hole")) }
        o.vec3("mirror")?.let { m -> d = d.update(id) { it.copy(mirror = D3(flag(m.x), flag(m.y), flag(m.z))) } }
        if (o.has("repeat")) {
            val r = if (o.isNull("repeat")) null else Repeat.of(o.optJSONObject("repeat") ?: throw ToolError("repeat must be an object or null"))
            d = d.update(id) { it.copy(repeat = r) }
        }
        return d
    }

    private suspend fun sketch(args: JSONObject): String {
        val id = args.optInt("id", -1)
        val o = args.optJSONObject("sketch") ?: throw ToolError("sketch must be an object {loops, cons}")
        val loops = o.optJSONArray("loops") ?: throw ToolError("sketch.loops must be an array")
        val sk = Sketch.of(o)?.takeIf { it.loops.size == loops.length() && it.loops.isNotEmpty() }
            ?: throw ToolError("Each loop needs pts with at least 2 [x, y] points")
        if (sk.loops.any { l -> l.pts.any { !it.x.isFinite() || !it.y.isFinite() } }) throw ToolError("Points must be numbers")
        if (sk.cons.size != (o.optJSONArray("cons")?.length() ?: 0)) throw ToolError("A constraint is not valid (kind, a [loop, corner], b for equal / parallel)")
        return edit("changed the drawing of #$id") { d ->
            if (d.find(id)?.kind != Shape.Kind.SKETCH) throw ToolError("#$id is not a sketch shape")
            sk.cons.mapNotNull { it.dim }.firstOrNull { d.dim(it) == null }?.let { throw ToolError("Unknown dim '$it'") }
            val solved = sk.solve({ d.dim(it)?.value })
            val revolve = if (args.has("revolve")) args.optBoolean("revolve") else null
            shapes.replace(d.update(id) { s ->
                ShapeDesign.fitSketch(if (revolve == null) s else s.copy(extra = s.extra + (Shape.REVOLVE to if (revolve) 1.0 else 0.0)), solved)
            })
        }
    }

    private suspend fun dimSet(args: JSONObject): String {
        val name = args.optString("name")
        fun num(k: String) = if (!args.has(k)) null else args.optDouble(k).takeIf { it.isFinite() } ?: throw ToolError("$k must be a number")
        val value = num("value"); val min = num("min"); val max = num("max"); val step = num("step")
        if (step != null && step <= 0) throw ToolError("step must be > 0")
        return edit("set dim $name") { d ->
            val old = d.dim(name)
            if (old == null) {
                if (!IDENT.matches(name)) throw ToolError("name must be an identifier (letters, digits, _)")
                val v = value ?: throw ToolError("A new dim needs a value")
                shapes.replace(d.copy(dims = d.dims + app.orcaandroid.scad.Dim(name, v, minOf(min ?: minOf(0.0, v), v), maxOf(max ?: maxOf(100.0, v * 2), v), step ?: 1.0)))
            } else {
                val v = value ?: old.value
                shapes.setDim(old.copy(value = v, min = minOf(min ?: old.min, v), max = maxOf(max ?: old.max, v), step = step ?: old.step))
            }
        }
    }

    private suspend fun bind(args: JSONObject): String {
        val id = args.optInt("id", -1)
        val field = args.optString("field")
        val dim = if (args.isNull("dim")) null else args.optString("dim")
        if (field !in BIND_FIELDS && field !in EXTRA_KEYS) throw ToolError("field must be size.x|y|z, pos.x|y|z, rot.x|y|z or an extra key")
        return edit(if (dim == null) "unlinked #$id $field" else "linked #$id $field to $dim") { d ->
            d.need(listOf(id))
            if (dim != null && d.dim(dim) == null) throw ToolError("No dim '$dim' (create it with dim_set)")
            shapes.bind(id, field, dim)
        }
    }

    /** Asks the user on screen; true when they allowed it within 60 s. */
    private suspend fun confirm(text: String): Boolean {
        if (pending != null) throw ToolError("Another request is already waiting for the user")
        val answer = CompletableDeferred<Boolean>()
        pending = answer
        store.update { it.copy(aiConfirm = text) }
        return try {
            withTimeoutOrNull(CONFIRM_TIMEOUT) { answer.await() } ?: false
        } finally {
            pending = null
            store.update { it.copy(aiConfirm = null) }
        }
    }

    private suspend fun toPlate(): String = withContext(Dispatchers.Main.immediate) {
        val st = editor()
        val plate = store.value.activePlate + 1
        val replaces = st.onPlate?.takeIf { name -> store.value.scene.objects.any { it.name == name } }
        val text = store.str(R.string.ai_confirm_plate, st.name, plate) + (replaces?.let { "\n" + store.str(R.string.ai_confirm_replaces, it) } ?: "")
        if (!confirm(text)) throw ToolError("Declined by the user (or no answer within ${CONFIRM_TIMEOUT / 1000} s)")
        scad.render()
        withTimeoutOrNull(RENDER_TIMEOUT) { scad.awaitIdle() } ?: throw ToolError("Rendering takes longer than ${RENDER_TIMEOUT / 1000} s; it goes on in the app")
        val now = store.value.scad ?: throw ToolError("The design editor was closed")
        if (now.statusError) throw ToolError("Not placed: ${now.status.orEmpty()}")
        "Placed on plate $plate as \"${now.onPlate}\". ${now.status.orEmpty()}"
    }

    /** What is wrong with design JSON [o]; mesh shapes may only keep files the [current] design already uses. */
    private fun validate(o: JSONObject, current: ShapeDesign?): List<String> {
        val errs = mutableListOf<String>()
        val list = o.optJSONArray("shapes") ?: return listOf("design.shapes must be an array")
        val dims = o.optJSONArray("dims") ?: JSONArray()
        val dimNames = HashSet<String>()
        for (i in 0 until dims.length()) {
            val dim = dims.optJSONObject(i) ?: run { errs += "dims[$i] is not an object"; null } ?: continue
            val name = dim.optString("name")
            if (!IDENT.matches(name)) errs += "dims[$i].name '$name' must be a plain identifier (letters, digits, _)"
            else if (!dimNames.add(name)) errs += "dims[$i].name '$name' is used twice"
            if (dim.optDouble("value").isNaN()) errs += "dims[$i].value must be a number"
        }
        val files = current?.shapes?.flatMap { s -> s.all().map { it.file }.toList() }.orEmpty().filter { it.isNotEmpty() }.toSet()
        val ids = HashSet<Int>()
        fun walk(a: JSONArray, path: String) {
            for (i in 0 until a.length()) {
                val p = "$path[$i]"
                val s = a.optJSONObject(i) ?: run { errs += "$p is not an object"; null } ?: continue
                val id = s.optInt("id", 0)
                if (id <= 0) errs += "$p.id must be a positive integer" else if (!ids.add(id)) errs += "$p.id $id is used twice"
                val kind = s.optString("kind")
                if (Shape.Kind.entries.none { it.key == kind }) errs += "$p.kind '$kind' is unknown"
                if (s.optString("name").any { it.isISOControl() }) errs += "$p.name has control characters"
                for (f in listOf("size", "pos", "rot", "mirror")) s.optJSONArray(f)?.let { v ->
                    if (v.length() != 3 || (0..2).any { k -> !v.optDouble(k).isFinite() }) errs += "$p.$f must be 3 numbers"
                }
                if (kind != "group") s.optJSONArray("size")?.let { v -> if ((0..2).any { k -> !(v.optDouble(k) > 0) }) errs += "$p.size must be > 0" }
                s.optJSONObject("bind")?.let { b ->
                    b.keys().forEach { f -> b.optString(f).let { dim -> if (dim !in dimNames) errs += "$p.bind.$f names unknown dim '$dim'" } }
                }
                val file = s.optString("file")
                if (file.isNotEmpty() && file !in files) errs += "$p.file: mesh files can't be added over MCP (keep the existing ones)"
                if (kind == "mesh" && file.isEmpty()) errs += "$p is a mesh without a file"
                val kids = s.optJSONArray("children")
                if (kind == "group" && (kids?.length() ?: 0) == 0) errs += "$p is a group without children"
                kids?.let { walk(it, "$p.children") }
            }
        }
        walk(list, "shapes")
        return errs.take(30)
    }

    // --- Helpers ------------------------------------------------------------------------------------

    /** Fails unless every id is a shape (top level when [top]) and there are at least [min]. */
    private fun ShapeDesign.need(ids: List<Int>, top: Boolean = false, min: Int = 1) {
        if (ids.size < min) throw ToolError("Needs at least $min shape ids")
        ids.forEach { id ->
            if (find(id) == null) throw ToolError("No shape #$id")
            if (top && shapes.none { it.id == id }) throw ToolError("#$id is inside a group; this works on top-level shapes")
        }
    }

    private fun JSONObject.ids(): List<Int> = optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }
        ?.takeIf { it.isNotEmpty() } ?: throw ToolError("ids must be a non-empty array of shape ids")

    private fun JSONObject.vec3(key: String): D3? = optJSONArray(key)?.let { a ->
        if (a.length() != 3 || (0..2).any { !a.optDouble(it).isFinite() }) throw ToolError("$key must be 3 numbers")
        D3(a.getDouble(0), a.getDouble(1), a.getDouble(2))
    }

    private fun duration(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun flag(x: Double) = if (x != 0.0) 1.0 else 0.0
    private fun ref(ids: List<Int>) = ids.joinToString { "#$it" }
    private fun n(x: Double) = ShapeDesign.n(x)
    private fun v(p: D3) = "[${n(p.x)}, ${n(p.y)}, ${n(p.z)}]"

    companion object {
        private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val EXTRA_KEYS = listOf(Shape.SIDES, Shape.TOP, Shape.WALL, Shape.TUBE, Shape.RADIUS, Shape.EDGE, Shape.CHAMFER,
            Shape.SHELL, Shape.OPEN, Shape.ANGLE)
        private val BIND_FIELDS = listOf("size", "pos", "rot").flatMap { f -> "xyz".map { "$f.$it" } }
        private val NOTE_WORDS = listOf("ERROR", "WARNING")
        private const val RENDER_TIMEOUT = 180_000L
        private const val CONFIRM_TIMEOUT = 60_000L

        private const val EDIT = "One undo step in the app (the user can press Undo)."
        private const val OPENS = " Opens the design editor on the saved draft when it is closed."

        private fun p(type: String, description: String) = JSONObject().put("type", type).put("description", description)
        private fun vec(description: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "number"))
            .put("minItems", 3).put("maxItems", 3).put("description", description)
        private fun ids() = JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")).put("description", "Shape ids")

        private fun obj(required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) = JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }

        /** Optional shape fields of shape_add / shape_update. */
        private val FIELDS = arrayOf(
            "name" to p("string", "Shown name"),
            "size" to vec("Bounding box [x, y, z] mm"),
            "pos" to vec("Centre of the box's bottom in x / y, its bottom in z, mm, in the parent's frame"),
            "rot" to vec("Degrees about the box centre, X then Y then Z"),
            "hole" to p("boolean", "true = cuts the solids of its own group"),
            "mirror" to vec("1 = mirrored on that axis, 0 = not"),
            "extra" to p("object", "Kind specific numbers (null removes one): sides, top (cone top / bottom ratio), wall, tube, " +
                "radius, edge (rounded edges mm), chamfer (1 = chamfer the edge), shell (hollow wall mm), open (1 = open top), angle"),
            "text" to p("string", "The text of a text shape"),
            "repeat" to JSONObject().put("type", JSONArray().put("object").put("null"))
                .put("description", "Copies: {circular, count, step [x,y,z], angle, center [x,y,z]}, or null for none"),
        )

        private const val SKETCH = "sketch = {loops: [{pts: [[x, y], ...] (corners, mm), bulge: [k per side from corner i " +
            "to i+1, 0 = straight, tan(sweep / 4) for an arc; a circle is 2 points with bulge [1, 1]], hole: true for a " +
            "cut-out}], cons: [{kind: length|equal|parallel|level|plumb, a: [loop, corner], b: [loop, corner] (equal / " +
            "parallel), value: mm (length), dim: name (length following a dim)}]}."

        private const val FORMAT = "Format: {shapes: [shape], dims: [{name, value, min, max, step}]}. " +
            "shape = {id (unique positive int), kind (box, rounded_box, cylinder, prism, cone, pyramid, sphere, dome, torus, " +
            "tube, wedge, text, sketch, mesh, group), name, size [x,y,z] (bounding box), pos [x,y,z] (centre of the box's " +
            "bottom in x/y, its bottom in z, in the parent's frame), rot [x,y,z] (about the box centre, X then Y then Z), " +
            "hole (true = cuts the solids of its own group), mirror [x,y,z] (1 = mirrored on that axis), extra {sides, top " +
            "(cone top / bottom ratio), wall, tube, radius, edge (rounded edges mm), chamfer (1 = chamfer edge), shell " +
            "(hollow wall mm), open (1 = open top)}, text (text kind), repeat {circular, count, step [x,y,z], angle, " +
            "center [x,y,z]}, sketch (sketch kind, see sketch_set), bind {\"size.x\": dimName, ...} (field driven by a " +
            "dim), children [shape] (group only)}. Keep ids of shapes you don't change; give new shapes new ids."

        const val INSTRUCTIONS = "OrcaStudio-Android (3D printing slicer). These tools edit the shape-builder design open in the " +
            "app's design editor (OpenSCAD is generated from it). Read with design_describe first (cheapest), design_get for " +
            "the JSON, design_snapshot for a picture when shape matters. Change it with the shape_* / dim_* / sketch_set tools " +
            "(or design_set for a whole new design), check with design_preview, and ask design_to_plate to put it on the plate " +
            "(the user confirms on screen). plate_describe shows the print set-up and the plate itself (separate from the design). " +
            "Every edit is one undo step the user can revert in the app."
    }
}
