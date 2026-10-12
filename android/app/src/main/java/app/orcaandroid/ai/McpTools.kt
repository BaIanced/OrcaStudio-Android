package app.orcaandroid.ai

import app.orcaandroid.R
import app.orcaandroid.scad.D3
import app.orcaandroid.scad.Shape
import app.orcaandroid.scad.ShapeDesign
import app.orcaandroid.ui.ScadController
import app.orcaandroid.ui.ShapeController
import app.orcaandroid.ui.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools AI clients get over MCP ([McpServer]). They go through the UI's controllers on the main
 * thread, so every edit shows up live in the editor and is one undo step there.
 */
class McpTools(private val store: Store, private val scad: ScadController, private val shapes: ShapeController) {

    private class Tool(val name: String, val description: String, val schema: JSONObject, val run: suspend (JSONObject) -> String)

    private class ToolError(message: String) : Exception(message)

    private val tools = listOf(
        Tool("design_get", "Read: the shape-builder design open in the app's design editor, as JSON " +
            "({open, name, selected, design}). design is the shape tree (see design_set for the format); when the " +
            "editor holds hand-written OpenSCAD code instead, source has the code and design is null.", noArgs()) { get() },
        Tool("design_describe", "Read: the open design as a short indented text tree (id, kind, name, size, position, " +
            "rotation, hole, repeat, dimension bindings, bounds). Cheapest way to see the design; prefer it over design_get.",
            noArgs()) { describe() },
        Tool("design_set", "Edit: replaces the whole shape-builder design (one undo step in the app; the user can press " +
            "Undo). Opens the design editor when it is closed. Fails when the editor holds hand-written code. " +
            "Returns the new design as a text tree. Units: mm and degrees. $FORMAT",
            JSONObject().put("type", "object").put("required", JSONArray().put("design"))
                .put("properties", JSONObject().put("design", JSONObject().put("type", "object")
                    .put("description", "The whole design: {shapes: [...], dims: [...]} as returned by design_get.")))) { set(it) },
    )

    fun list(): JSONArray = JSONArray().apply {
        tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.schema)) }
    }

    suspend fun call(name: String, args: JSONObject): JSONObject {
        val tool = tools.firstOrNull { it.name == name } ?: return reply("Unknown tool: $name", error = true)
        return try {
            reply(tool.run(args))
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

    private fun get(): String {
        val st = store.value.scad ?: return JSONObject().put("open", false)
            .put("note", "The design editor is closed; design_set opens it.").toString(2)
        return JSONObject().put("open", true).put("name", st.name)
            .put("selected", JSONArray(st.selected))
            .put("design", st.shapes?.json() ?: JSONObject.NULL)
            .apply { if (st.shapes == null) put("source", st.source) }
            .toString(2)
    }

    private fun describe(): String {
        val st = store.value.scad ?: return "The design editor is closed; design_set opens it."
        val d = st.shapes ?: return "The editor holds hand-written OpenSCAD code (${st.source.lines().size} lines), not a shape design."
        return describe(st.name, d, st.selected)
    }

    private fun describe(name: String, d: ShapeDesign, selected: List<Int>) = buildString {
        val count = d.shapes.sumOf { it.all().count() }
        append("Design \"$name\": $count shape(s)")
        if (selected.isNotEmpty()) append(", selected ${selected.joinToString { "#$it" }}")
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

    // --- Edit ---------------------------------------------------------------------------------------

    private suspend fun set(args: JSONObject): String {
        val o = args.optJSONObject("design") ?: throw ToolError("design must be an object {shapes, dims}")
        val current = store.value.scad?.shapes
        val problems = validate(o, current)
        if (problems.isNotEmpty()) throw ToolError("Design not valid:\n" + problems.joinToString("\n"))
        val next = ShapeDesign.of(o)
        return withContext(Dispatchers.Main.immediate) {
            if (store.value.scad == null) scad.open().join()
            val st = store.value.scad ?: throw ToolError("The design editor could not be opened")
            if (st.shapes == null) throw ToolError("The design editor holds hand-written OpenSCAD code. Ask the user to start a " +
                "shape design (Add > Shape builder) first; this tool won't replace their code.")
            shapes.replace(next)
            store.toast(store.str(R.string.ai_design_set, next.shapes.sumOf { it.all().count() }))
            val now = store.value.scad ?: st
            describe(now.name, now.shapes ?: next, now.selected)
        }
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
                    if (v.length() != 3 || (0..2).any { k -> v.optDouble(k).let { x -> x.isNaN() || x.isInfinite() } }) errs += "$p.$f must be 3 numbers"
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

    private fun n(x: Double) = ShapeDesign.n(x)
    private fun v(p: D3) = "[${n(p.x)}, ${n(p.y)}, ${n(p.z)}]"

    companion object {
        private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

        private fun noArgs() = JSONObject().put("type", "object").put("properties", JSONObject())

        private const val FORMAT = "Format: {shapes: [shape], dims: [{name, value, min, max, step}]}. " +
            "shape = {id (unique positive int), kind (box, rounded_box, cylinder, prism, cone, pyramid, sphere, dome, torus, " +
            "tube, wedge, text, sketch, mesh, group), name, size [x,y,z] (bounding box), pos [x,y,z] (centre of the box's " +
            "bottom in x/y, its bottom in z, in the parent's frame), rot [x,y,z] (about the box centre, X then Y then Z), " +
            "hole (true = cuts the solids of its own group), mirror [x,y,z] (1 = mirrored on that axis), extra {sides, top " +
            "(cone top / bottom ratio), wall, tube, radius, edge (rounded edges mm), chamfer (1 = chamfer edge), shell " +
            "(hollow wall mm), open (1 = open top)}, text (text kind), repeat {circular, count, step [x,y,z], angle, " +
            "center [x,y,z]}, bind {\"size.x\": dimName, ...} (field driven by a dim), children [shape] (group only)}. " +
            "Keep ids of shapes you don't change; give new shapes new ids."

        const val INSTRUCTIONS = "OrcaStudio-Android (3D printing slicer). These tools edit the shape-builder design open in the " +
            "app's design editor (OpenSCAD is generated from it). Read with design_describe first (cheapest), design_get for " +
            "the JSON; change with design_set. Every edit is one undo step the user can revert in the app."
    }
}
