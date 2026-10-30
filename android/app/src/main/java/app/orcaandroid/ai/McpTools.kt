package app.orcaandroid.ai

import app.orcaandroid.R
import app.orcaandroid.core.Instance
import app.orcaandroid.core.PickHit
import app.orcaandroid.core.meshEditMatrix
import app.orcaandroid.core.Scene
import app.orcaandroid.core.Vec3
import app.orcaandroid.scad.Bounds
import app.orcaandroid.scad.D3
import app.orcaandroid.scad.Repeat
import app.orcaandroid.scad.Shape
import app.orcaandroid.scad.ShapeDesign
import app.orcaandroid.scad.Sketch
import app.orcaandroid.ui.PresetController
import app.orcaandroid.ui.Screen
import app.orcaandroid.ui.ScadController
import app.orcaandroid.ui.ShapeController
import app.orcaandroid.ui.SliceController
import app.orcaandroid.ui.preview.ROLE_NAMES
import app.orcaandroid.ui.Store
import kotlin.math.sqrt
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
class McpTools(private val store: Store, private val scad: ScadController, private val shapes: ShapeController, presets: PresetController,
               private val slicing: SliceController) {

    /** [run] returns the reply text, or a ready-made `content` [JSONArray] (e.g. an image) for [reply]. */
    internal class Tool(val name: String, val description: String, val schema: JSONObject, val run: suspend (JSONObject) -> Any)

    internal class ToolError(message: String) : Exception(message)

    /** The on-screen question a tool waits for (main thread only). */
    private var pending: CompletableDeferred<Boolean>? = null

    /** The user's answer to [UiState.aiConfirm]. */
    fun answer(allow: Boolean) {
        pending?.complete(allow)
    }

    private val presetTools = PresetTools(store, presets, ::confirm)

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
            obj(listOf("design"), "design" to p("object", "The whole design: {shapes: [...], dims: [...]}, i.e. design_get's design field (not its whole reply)."),
                "name" to p("string", "The design's name; default unchanged"))) { set(it) },
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
        Tool("shape_merge", "Edit: bakes two or more top-level shapes, or one group, into one mesh shape (OpenSCAD renders " +
            "it, holes cut). Its parts are no longer editable (undo brings them back). $EDIT", obj(listOf("ids"), "ids" to ids())) { args ->
            val ids = args.ids()
            withContext(Dispatchers.Main.immediate) {
                val d = design()
                d.need(ids, top = true)
                if (ids.size == 1 && d.find(ids[0])?.isGroup != true) throw ToolError("Merge needs two or more shapes, or one group")
                shapes.select(ids)
                shapes.merge()
            }?.join()
            withContext(Dispatchers.Main.immediate) {
                val st = editor()
                (if (st.statusError) "Merge failed: ${st.status}\n" else "") + describe(st.name, st.shapes ?: design(), st.selected)
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
                "revolve" to p("boolean", "true = revolve about Z, false = extrude; omitted = unchanged"),
                "top" to p("object", "Extrude only: a second drawing {loops} (same frame) the sides loft up to, e.g. square to circle; null removes it, omitted = unchanged"),
                "path" to p("object", "Extrude only: a path seen from above {loops} (a loop with open true is an open path) the drawing is swept along as a profile (profile x = sideways, left of the path / outwards on a closed one; y = up); the box fits the result; null removes it, omitted = unchanged"))) { sketch(it) },
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
        Tool("object_transform", "Plate edit: places one copy of an object. pos = centre of its footprint [x, y] in mm from " +
            "the front-left corner of a plate (its own plate, or plate); rot = degrees about X, Y, Z (absolute, as plate_describe " +
            "lists); scale = % per axis (100 = original size). Given fields change, others stay; the copy then rests on the bed. " +
            "With pos and rot / scale together it is two undo steps. $PLATE_EDIT",
            obj(listOf("object"), *COPY, "pos" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "number"))
                .put("minItems", 2).put("maxItems", 2).put("description", "Footprint centre [x, y] mm on the plate"),
                "plate" to p("integer", "Plate number for pos (moves the copy there); default its current plate"),
                "rot" to vec("Degrees about X, Y, Z"), "scale" to vec("Percent per axis, > 0"))) { transform(it) },
        Tool("object_lay_on_face", "Plate edit: turns one copy so its face that now points in direction normal lies flat on " +
            "the bed (plate frame: [1,0,0] right, [0,1,0] back, [0,0,1] up; e.g. [0,1,0] puts its back face down). $PLATE_EDIT",
            obj(listOf("object", "normal"), *COPY, "normal" to vec("Direction the face points in now"))) { layOnFace(it) },
        Tool("object_auto_orient", "Plate edit: turns every copy of an object (or of all objects, object omitted) the way " +
            "OrcaSlicer's auto orient finds best for printing (less support, flat on the bed). $PLATE_EDIT",
            obj(emptyList(), "object" to p("integer", "Object number from plate_describe; omitted = all"))) { autoOrient(it) },
        Tool("object_check_mesh", "Read-only mesh report of one copy, per part: faces, shells, volume, open edges, zero-area faces, sharp edges (folded back over 160 degrees) and faces " +
            "whose wall is thinner than thickness mm (default 0.4). Nothing changes.",
            obj(listOf("object"), *COPY, "thickness" to p("number", "Thin-wall limit in mm, 0..5; 0 skips the check; default 0.4"))) { checkMesh(it) },
        Tool("object_deform", "Plate edit: bends, twists or tapers an object along plate axis 0 = X, 1 = Y, 2 = Z, in this copy's frame " +
            "(the mesh is refined first; painting is removed). bend: amount = degrees, + toward +Z along X/Y, toward +X along Z; " +
            "twist: degrees about the axis; taper: the far end's scale (> 0). The low end stays put. $PLATE_EDIT",
            obj(listOf("object", "kind", "axis", "amount"), *COPY, "kind" to p("string", "bend, twist or taper"),
                "axis" to p("integer", "0..2"), "amount" to p("number", "Degrees, or taper scale"))) { deform(it) },
        Tool("object_smooth_blend", "Plate edit: fuses all model parts of one copy into a single mesh and rounds the joins and crevices " +
            "(OpenVDB closing by radius mm; convex edges stay; works on STLs; painting is removed). $PLATE_EDIT",
            obj(listOf("object", "radius"), *COPY, "radius" to p("number", "Blend radius in mm, 0.5..50"))) { smoothBlend(it) },
        Tool("object_hollow", "Plate edit: carves a closed cavity wall mm inside each model part of one copy (saves filament; sealed, no drain hole; " +
            "painting is removed). $PLATE_EDIT",
            obj(listOf("object", "wall"), *COPY, "wall" to p("number", "Wall thickness in mm, 0.8..10"))) { hollow(it) },
        Tool("object_sculpt", "Plate edit: sculpts the surface hit by a ray (brush dabs on the mesh; refined first when too coarse for the brush; " +
            "painting is removed on the first refine). Repeat dabs with `dabs`. $PLATE_EDIT",
            obj(listOf("origin", "dir", "brush"), "origin" to vec("Ray origin in plate coordinates"), "dir" to vec("Ray direction"),
                "brush" to p("string", "draw, inflate, smooth, flatten, pinch, crease or grab"), "radius" to p("number", "Brush radius in mm; default 5"),
                "strength" to p("number", "0..1; default 0.5"), "invert" to p("boolean", "Dent instead of bulge"), "mirror" to p("string", "Symmetry: also dab across the part's own middle planes, e.g. x or xz"), "dabs" to p("integer", "Repeat count 1..50; default 1 (one stroke: draw, inflate and crease do not build up within it)"))) { sculpt(it) },
        Tool("mesh_edit", "Mesh edit mode (vertex / edge / face): a ray selects the feature under it on that part (face = crease-bounded " +
            "region, edge = crease line, vertex = corner); the selection stays for later calls. move / scale / rotate then transform the " +
            "selected vertices about their centroid (neighbouring triangles stretch; painting is removed). Replies with the selection " +
            "(count, centroid, normal); a transform also with what changed. $PLATE_EDIT",
            obj(emptyList(), "origin" to vec("Ray origin in plate coordinates (select)"), "dir" to vec("Ray direction (select)"),
                "op" to p("string", "select (default with a ray), clear, all (every vertex of the part), grow (adds the neighbouring vertices), " +
                    "extrude (the selected faces pulled out by amount mm along the selection normal with walls; < 0 = pocket) or inset " +
                    "(the selected faces' border pulled amount mm in, new ring of faces) or cut (needs a ray: the plane through its hit across " +
                    "axis splits the band of faces there into a vertex ring, which becomes the selection; add scale for a waist / bulge) or " +
                    "fillet / chamfer (the selected crease edges rounded / cut off by amount mm; flat-sided parts only, a face selection " +
                    "takes its border edges)"),
                "axis" to vec("Cut plane normal (cut); default [0, 0, 1]"),
                "amount" to p("number", "mm for extrude / inset, radius / size for fillet / chamfer"),
                "kind" to p("string", "face (default), edge or vertex"), "angle" to p("number", "Crease angle in degrees; default 30, 0 = single triangles"),
                "toggle" to p("boolean", "Add to / remove from the selection instead of replacing it"),
                "move" to vec("Move by [dx, dy, dz] mm"), "scale" to vec("Scale by [x, y, z] percent"), "rotate" to vec("Turn by [x, y, z] degrees"))) { meshEdit(it) },
        Tool("object_duplicate", "Plate edit: adds copies of one copy of an object; they are arranged on its plate. $PLATE_EDIT",
            obj(listOf("object"), *COPY, "copies" to p("integer", "How many, 1..100; default 1"))) { duplicate(it) },
        Tool("plate_arrange", "Plate edit: arranges the objects on a plate (default the active one), or with all true " +
            "every object over all plates (plates are added when they don't fit). $PLATE_EDIT",
            obj(emptyList(), "plate" to p("integer", "Plate number"), "all" to p("boolean", "true = every plate"))) { arrange(it) },
        Tool("slice", "Run: slices a plate (default the active one) with the chosen printer, process and filaments and waits " +
            "for it (up to 10 min). The app then shows the Preview tab. Replies with the full result (same as slice_result, " +
            "which is only for re-reading it later), or the error.",
            obj(emptyList(), "plate" to p("integer", "Plate number; default the active plate"))) { slice(it) },
        Tool("slice_result", "Read: the last slice of a plate (default the active one): print time, filament, layers, time and " +
            "filament per extrusion role, and the slicer's warnings. Nothing is sliced here; use slice for that.",
            obj(emptyList(), "plate" to p("integer", "Plate number; default the active plate"))) { sliceResult(it) },
        Tool("view_set", "View: sets the 3D view of the plate (not the design editor): a preset view or camera angles, the " +
            "section view cut and the 3D look. Given fields change, others stay. Returns the camera and what is on. " +
            "Shows the Prepare tab's 3D view first (the design editor stays open behind it). To look at a part from a side, " +
            "give a preset view with object: fit always uses the iso angles.",
            obj(emptyList(),
                "view" to p("string", "iso, top, front, left, right, bottom, rear or fit (frames the plate from the iso angles)"),
                "plate" to p("integer", "Plate number to frame with view fit; default the active plate"),
                "object" to p("integer", "Object number (plate_describe) to fill the view with, seen from the given view or " +
                    "the current angles; 0 = every object on the active plate"),
                "yaw" to p("number", "Degrees around the plate"), "pitch" to p("number", "Degrees above the bed, -89..89"),
                "distance" to p("number", "Camera distance from the target, mm"),
                "target" to vec("Point the camera looks at, mm"),
                "section" to p("number", "Section view cut 0..1 (0 = off)"),
                "shadows" to p("boolean", "Shadows under objects"),
                "supersample" to p("string", "Preview supersampling: off, still (when the camera stops; default) or always"),
                "regime" to p("number", "Tuning: scale of the Preview layer-line regimes while a still view is refined, 0.3..1.5 (default 1)"),
                "projection" to p("string", "perspective or orthographic (the user's setting, also in the app's View menu)"))) { viewSet(it) },
        Tool("view_screenshot", "Read: a PNG of the plate's 3D view as drawn now (no buttons or panels), after a short wait " +
            "for the last view change. Costs far more than text: use view_state, plate_describe or view_set's reply when " +
            "they answer it. Shows the Prepare tab's 3D view first.",
            obj(emptyList(), "size" to p("integer", "Longer side in px, 256..1280; default 720"))) { viewShot(it) },
        Tool("tuning_get", "Read: the feel knobs (measure loupe, steered camera: zoom, speeds, smoothing), one line each: " +
            "key = value unit [min..max], with the default when changed. Same values as More > Tuning.", obj()) {
            app.orcaandroid.core.Tuning.describe()
        },
        Tool("tuning_set", "Edit: sets feel knobs live and saves them: {\"key\": value, ...} with keys from tuning_get " +
            "(clamped to the range), or {\"reset\": true} for all defaults. Returns the knobs after.", obj()) { args ->
            val bad = mutableListOf<String>()
            if (args.optBoolean("reset")) app.orcaandroid.core.Tuning.reset()
            for (k in args.keys()) if (k != "reset" && !app.orcaandroid.core.Tuning.set(k, args.optDouble(k).toFloat())) bad += k
            (if (bad.isEmpty()) "" else "Unknown: ${bad.joinToString()}\n") + app.orcaandroid.core.Tuning.describe()
        },
        Tool("view_state", "Read: what the plate's 3D view shows now, as text: camera, interaction mode and tool, the " +
            "selection, and where each plate, object copy and prime tower is on screen (screen px: left,top width x height; " +
            "tapping a box's centre hits it unless something covers it), marked when off or partly off the view. Far " +
            "cheaper than view_screenshot. Shows the Prepare tab's 3D view first.", obj()) { viewReport() },
        Tool("app_state", "Read (debug): the app's state as text: version, screen / tab, busy / error / message, open " +
            "dialogs and editors, chosen presets, plates and objects count, selection and tool, the 3D view's camera when " +
            "one is shown, memory, and whether a crash report is waiting. Nothing changes.", obj()) { appState() },
        Tool("design_mode", "Debug: turns the Theme | Layout designer panel on or off (like More > Design mode).",
            obj(listOf("on"), "on" to p("boolean", "true = open, false = close"))) { args ->
            val on = args.getBoolean("on")
            withContext(Dispatchers.Main.immediate) {
                store.update { it.copy(designMode = on, screen = if (on && it.screen == app.orcaandroid.ui.Screen.MORE) app.orcaandroid.ui.Screen.PREPARE else it.screen) }
            }
            "Design mode ${if (on) "on" else "off"}."
        },
        Tool("log_tail", "Read (debug): the newest lines of the app's own Android log (logcat of this process only), " +
            "oldest first. Values next to token / code / password / key / cookie / auth are masked.",
            obj(emptyList(), "lines" to p("integer", "How many lines, 1..500; default 100"),
                "filter" to p("string", "Only lines containing this text (case-insensitive), e.g. a tag like OrcaInput"),
                "level" to p("string", "Lowest level: V, D, I, W, E; default V"))) { logTail(it) },
        Tool("perf", "Read (debug): frame timing of the 3D view over its last 240 frames (fps, frame gaps, draw time on " +
            "the GL thread) and the app's memory. Move the camera first to measure drawing under load; an idle view " +
            "still draws every frame.", obj()) { perf() },
        Tool("models_human_check", "Web: if the Models tab's page shows Cloudflare's \"Verify you are human\" check, taps " +
            "its box and waits (up to 25 s) for the page to go on. Replies with the outcome and the page it ends on. Needs " +
            "the Models tab on screen.", obj()) {
            app.orcaandroid.ui.models.ModelBrowser.active?.get()?.humanCheck()
                ?: throw ToolError("The Models browser isn't open yet: open the Models tab in the app")
        },
    ) + presetTools.tools

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

    /** The design text in pieces, so an edit can reply with only the pieces it changed. */
    private class Rendered(val header: String, val dims: Map<String, String>, val blocks: Map<Int, String>, val fit: String)

    private fun describe(name: String, d: ShapeDesign, selected: List<Int>): String {
        val r = render(name, d, selected)
        return buildString {
            appendLine(r.header)
            r.dims.values.forEach { appendLine(it) }
            r.blocks.values.forEach { append(it) }
            if (d.shapes.isEmpty()) appendLine("(empty)")
            appendLine(r.fit)
        }
    }

    /** What changed from [before] to [after]: header, changed / new dims and top-level shape trees, removed ones, the fit check. */
    private fun diff(before: Rendered, after: Rendered, empty: Boolean) = buildString {
        appendLine(after.header)
        after.dims.forEach { (k, l) -> if (before.dims[k] != l) appendLine(l) }
        before.dims.keys.filter { it !in after.dims }.forEach { appendLine("dim $it: removed") }
        after.blocks.forEach { (id, t) -> if (before.blocks[id] != t) append(t) }
        before.blocks.keys.filter { it !in after.blocks }.forEach { appendLine("#$it: removed") }
        val kept = after.blocks.keys.filter { it in before.blocks }
        if (kept != before.blocks.keys.filter { it in after.blocks }) appendLine("order: ${after.blocks.keys.joinToString(", ") { "#$it" }}")
        if (empty) appendLine("(empty)")
        appendLine(after.fit)
    }

    private fun render(name: String, d: ShapeDesign, selected: List<Int>): Rendered {
        val count = d.shapes.sumOf { it.all().count() }
        val header = buildString {
            append("Design \"$name\": $count shape(s)")
            if (selected.isNotEmpty()) append(", selected ${ref(selected)}")
        }
        val dims = d.dims.associate { it.name to "dim ${it.name} = ${n(it.value)} (${n(it.min)}..${n(it.max)} step ${n(it.step)})" }
        fun block(top: Shape) = buildString {
            fun line(s: Shape, depth: Int) {
                append("  ".repeat(depth)).append("#${s.id} ${s.kind.key} \"${s.name}\"")
                if (s.hole) append(" HOLE")
                if (s.filament > 0 && !s.hole && depth == 0) append(" filament ${s.filament}")
                if (!s.isGroup) append(" size ${v(d.size(s))}")
                append(" pos ${v(d.pos(s))}")
                d.rot(s).takeIf { it != D3() }?.let { append(" rot ${v(it)}") }
                s.mirror.takeIf { it != D3() }?.let { append(" mirror ${v(it)}") }
                s.extra.forEach { (k, x) -> append(" $k=${n(x)}") }
                if (s.text.isNotEmpty()) append(" text \"${s.text}\"")
                s.repeat?.let { r -> append(if (r.circular) " repeat ${r.count}x around ${v(r.center)} over ${n(r.angle)}°" else " repeat ${r.count}x step ${v(r.step)}") }
                s.sketch?.let { sk -> append(" sketch ${sk.loops.size} loop(s)") }; s.top?.let { append(" lofted to a top outline") }; s.path?.let { append(" swept along a path") }
                s.bind.forEach { (f, dim) -> append(" $f<-$dim") }
                val b = d.bounds(s)
                append(" bounds ${v(b.min)}..${v(b.max)}")
                appendLine()
                s.children.forEach { line(it, depth + 1) }
            }
            line(top, 0)
        }
        return Rendered(header, dims, d.shapes.associate { it.id to block(it) }, fitNotes(d))
    }

    /**
     * Text check for parts that float or sit off the bed, from the top-level solids' bounding boxes (holes ignored).
     * Boxes of rotated shapes are larger than the shape, so "touching" can be a false positive; "apart" never is.
     */
    private fun fitNotes(d: ShapeDesign): String {
        val solids = d.shapes.filter { !it.hole }
        if (solids.isEmpty()) return ""
        val boxes = solids.map { d.bounds(it) }
        fun gap(a: Bounds, b: Bounds): Double {
            fun g(amin: Double, amax: Double, bmin: Double, bmax: Double) = maxOf(amin - bmax, bmin - amax, 0.0)
            val x = g(a.min.x, a.max.x, b.min.x, b.max.x)
            val y = g(a.min.y, a.max.y, b.min.y, b.max.y)
            val z = g(a.min.z, a.max.z, b.min.z, b.max.z)
            return sqrt(x * x + y * y + z * z)
        }
        val group = IntArray(solids.size) { it }
        fun root(i: Int): Int { var r = i; while (group[r] != r) r = group[r]; return r }
        for (i in solids.indices) for (j in i + 1 until solids.size) if (gap(boxes[i], boxes[j]) <= 0.05) group[root(j)] = root(i)
        val pieces = solids.indices.groupBy { root(it) }.values.toList()
        val notes = mutableListOf<String>()
        if (pieces.size > 1) {
            val names = pieces.joinToString("; ") { p -> p.joinToString(", ") { "#${solids[it].id}" } }
            val nearest = pieces.indices.flatMap { a -> (a + 1 until pieces.size).map { b ->
                pieces[a].minOf { i -> pieces[b].minOf { j -> gap(boxes[i], boxes[j]) } } } }.minOrNull() ?: 0.0
            notes += "${pieces.size} separate pieces, not touching (bounding boxes): $names; closest gap ${n(nearest)} mm"
        }
        val low = boxes.minOf { it.min.z }
        if (low > 0.05) notes += "lowest point is ${n(low)} mm above the bed (nothing to print on)"
        if (low < -0.05) notes += "lowest point is ${n(-low)} mm below the bed (that part is cut off)"
        return if (notes.isEmpty()) "Fit check: one connected piece on the bed (bounding boxes)." else notes.joinToString("\n") { "Fit check: $it" }
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

    /**
     * The plate's 3D view (main thread). Brings it on screen first: the Prepare tab unless Preview is up, and the
     * design editor tucked away (it stays open with its undo; the OpenSCAD button brings it back).
     */
    private suspend fun plateView(): app.orcaandroid.render.PlateView {
        fun shown() = app.orcaandroid.render.PlateView.active?.get()?.takeIf { it.isShown }
        val s = store.value
        if (s.screen != Screen.PREPARE && s.screen != Screen.PREVIEW) store.update { it.copy(screen = Screen.PREPARE) }
        if (s.scad != null && !s.scadOnPlate && !s.scadHidden) store.update { it.copy(scadHidden = true) }
        if (store.value != s) kotlinx.coroutines.delay(300)
        return shown() ?: withTimeoutOrNull(3_000) {
            var v = shown()
            while (v == null) { kotlinx.coroutines.delay(100); v = shown() }
            v
        } ?: throw ToolError("No 3D view is on screen: is the app in the foreground?")
    }

    private fun viewState(view: app.orcaandroid.render.PlateView): String {
        val c = view.camera.state()
        val s = store.value
        return "Camera yaw ${n(c[0].toDouble())}, pitch ${n(c[1].toDouble())}, distance ${n(c[2].toDouble())} mm, " +
            "target [${n(c[3].toDouble())}, ${n(c[4].toDouble())}, ${n(c[5].toDouble())}]; section ${n(view.section.ratio.toDouble())}, " +
            "shadows ${if (s.contactShadows) "on" else "off"}, supersample ${listOf("off", "still", "always").getOrElse(s.supersample) { "?" }}, ${if (view.camera.perspective) "perspective" else "orthographic"}; " +
            "plate ${s.activePlate + 1} active."
    }

    private suspend fun viewSet(args: JSONObject): String = withContext(Dispatchers.Main.immediate) {
        val view = plateView()
        val target = args.vec3("target")
        fun num(key: String) = if (args.has(key)) args.optDouble(key).takeIf { it.isFinite() }?.toFloat() ?: throw ToolError("$key must be a number") else null
        val yaw = num("yaw"); val pitch = num("pitch"); val distance = num("distance")
        val to = target?.let { Vec3(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
        var fitted = false
        if (args.has("object") && args.optString("view") == "fit") throw ToolError("Give view fit or object, not both")
        args.optString("view").takeIf { it.isNotEmpty() }?.let { name ->
            if (name == "fit") {
                val plate = if (args.has("plate")) args.optInt("plate") - 1 else store.value.activePlate
                if (plate !in 0 until store.value.scene.plates.size) throw ToolError("No plate ${plate + 1}")
                // The fit runs on the GL thread and sets the angles itself: the given ones go after it.
                view.onGl { frame(plate, false); view.camera.aim(yaw, pitch, distance, to) }
                fitted = true
            } else {
                val id = listOf("iso", "top", "front", "left", "right", "bottom", "rear").indexOf(name)
                if (id < 0) throw ToolError("view must be iso, top, front, left, right, bottom, rear or fit")
                view.camera.preset(id)
            }
        }
        if (args.has("object")) {
            val scene = store.value.scene
            val which = args.optInt("object", -1)
            val copies = when (which) {
                0 -> scene.objects.flatMap { it.instances }.filter { it.plate == store.value.activePlate }
                in 1..scene.objects.size -> scene.objects[which - 1].instances
                else -> throw ToolError("No object $which (there are ${scene.objects.size})")
            }
            if (copies.isEmpty()) throw ToolError("Nothing to frame: plate ${store.value.activePlate + 1} is empty")
            val lo = Vec3(copies.minOf { it.min.x }, copies.minOf { it.min.y }, copies.minOf { it.min.z })
            val hi = Vec3(copies.maxOf { it.min.x + it.size.x }, copies.maxOf { it.min.y + it.size.y }, copies.maxOf { it.min.z + it.size.z })
            view.camera.fit(lo, hi, keepAngles = true)
        }
        if (fitted) kotlinx.coroutines.delay(150) else view.camera.aim(yaw, pitch, distance, to)
        num("section")?.let { r -> view.section.set(r); view.section.panelOpen = view.section.active }
        if (args.has("shadows")) {
            val on = args.optBoolean("shadows")
            store.settings.contactShadows = on
            store.update { it.copy(contactShadows = on) }
        }
        args.optString("supersample").takeIf { it.isNotEmpty() }?.let { name ->
            val mode = listOf("off", "still", "always").indexOf(name)
            if (mode < 0) throw ToolError("supersample: off, still or always")
            store.settings.supersample = mode
            store.update { it.copy(supersample = mode) }
        }
        num("regime")?.let { r -> view.renderer.accumRegime = r.coerceIn(0.3f, 1.5f) }
        args.optString("projection").takeIf { it.isNotEmpty() }?.let { name ->
            val on = when (name) {
                "perspective" -> true
                "orthographic" -> false
                else -> throw ToolError("projection must be perspective or orthographic")
            }
            store.settings.perspective = on
            store.update { it.copy(perspective = on) }
            view.camera.perspective = on
        }
        viewState(view)
    }

    private fun memory(): String {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val native = android.os.Debug.getNativeHeapAllocatedSize() / mb
        return "memory: Java heap ${(rt.totalMemory() - rt.freeMemory()) / mb} of ${rt.maxMemory() / mb} MB, native heap $native MB"
    }

    private suspend fun appState(): String = withContext(Dispatchers.Main.immediate) {
        val s = store.value
        val ctx = store.app
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val cfg = ctx.resources.configuration
        buildString {
            appendLine("App ${info.versionName} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT}); " +
                "screen ${cfg.screenWidthDp}x${cfg.screenHeightDp} dp, " +
                (if (cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait") + ".")
            appendLine("Phase ${s.phase.name.lowercase()}; tab ${s.screen.name.lowercase()}" +
                (s.busy?.let { "; busy: $it" } ?: "") + (s.error?.let { "; error: $it" } ?: "") +
                (s.sliceError?.let { "; slice error (plate ${it.first + 1}): ${it.second}" } ?: "") + (s.message?.let { "; message: $it" } ?: "") + ".")
            val open = listOfNotNull(
                s.editor?.let { "preset editor" },
                s.addDialog?.let { "add dialog ($it)" },
                s.scad?.let { "design editor" },
                "printer setup".takeIf { s.showPrinterSetup },
                s.filamentChoice?.let { "filament choice" },
                s.projectOrImport?.let { "project-or-import question" },
                s.aiConfirm?.let { "MCP confirmation: $it" },
                s.contextMenu?.let { "3D context menu" },
            )
            appendLine("Open: " + open.joinToString().ifEmpty { "nothing over the main screen" } + ".")
            appendLine("Presets: printer ${s.printer ?: "none"}; process ${s.print ?: "none"}; filaments ${s.filaments.size}" +
                (if (s.overrides.values.any { it.isNotEmpty() }) " (unsaved changes)" else "") + ".")
            val scene = s.scene
            appendLine("Plates ${scene.plates.size}, active ${s.activePlate + 1}; objects ${scene.objects.size}, copies " +
                "${scene.objects.sumOf { it.instances.size }}; selected ${s.selectedItems.size}; tool ${s.tool.javaClass.simpleName}" +
                (if (s.selectMode) "; select mode" else "") + (if (s.resizing) "; resizing" else "") + ".")
            appendLine("Design mode ${if (s.designMode) "on" else "off"}; undo limit ${s.undoLimit}; auto-save " +
                (if (s.autoSaveSeconds > 0) "${s.autoSaveSeconds} s" else "off") + ".")
            appendLine("3D view: " + (app.orcaandroid.render.PlateView.active?.get()?.let { viewState(it) } ?: "none on screen."))
            val crash = java.io.File(ctx.filesDir, "crash_report.txt")
            append(memory() + "; " + if (crash.isFile) "crash report waiting: " + crash.readLines().firstOrNull().orEmpty().take(200)
                else "no crash report waiting")
        }
    }

    private suspend fun logTail(args: JSONObject): String = withContext(Dispatchers.IO) {
        val lines = LogTail.lineCount(args.optInt("lines", LogTail.DEFAULT_LINES))
        val level = args.optString("level", "V").uppercase().takeIf { it in listOf("V", "D", "I", "W", "E") }
            ?: throw ToolError("level must be V, D, I, W or E")
        val filter = args.optString("filter").takeIf { it.isNotEmpty() }
        // An app may read its own process's log without any permission.
        val proc = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}", "*:$level")
            .redirectErrorStream(true).start()
        val all = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        val picked = all.filter { filter == null || it.contains(filter, ignoreCase = true) }.takeLast(lines)
        if (picked.isEmpty()) "No log lines" + (filter?.let { " containing \"$it\"" } ?: "") + "."
        else picked.joinToString("\n") { LogMask.mask(it) }
    }

    private suspend fun perf(): String = withContext(Dispatchers.Main.immediate) {
        val view = app.orcaandroid.render.PlateView.active?.get()
        (view?.renderer?.frameStats?.report()?.let { "3D view: $it" } ?: "No 3D view on screen.") + "\n" + memory()
    }

    private suspend fun viewReport(): String = withContext(Dispatchers.Main.immediate) {
        val view = plateView()
        val s = store.value
        val scene = s.scene
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        val w = view.width.toFloat(); val h = view.height.toFloat()
        fun px(x: Float, y: Float) = "${Math.round(x + at[0])},${Math.round(y + at[1])}"
        /** The screen box of the world box [lo]..[hi], or where it is instead. */
        fun box(lo: Vec3, hi: Vec3): String {
            val corners = listOf(lo.x, hi.x).flatMap { x -> listOf(lo.y, hi.y).flatMap { y -> listOf(lo.z, hi.z).map { z -> Vec3(x, y, z) } } }
            val seen = corners.mapNotNull { view.camera.project(it) }
            if (seen.isEmpty()) return "behind the camera"
            val l = seen.minOf { it.first }; val r = seen.maxOf { it.first }
            val t = seen.minOf { it.second }; val b = seen.maxOf { it.second }
            if (r < 0f || b < 0f || l > w || t > h) return "off the view"
            val cl = l.coerceIn(0f, w); val cr = r.coerceIn(0f, w); val ct = t.coerceIn(0f, h); val cb = b.coerceIn(0f, h)
            val clipped = seen.size < corners.size || cl != l || cr != r || ct != t || cb != b
            return "${px(cl, ct)} ${Math.round(cr - cl)}x${Math.round(cb - ct)}" + if (clipped) " (partly off the view)" else ""
        }
        val selected = s.selectedItems
        buildString {
            appendLine("3D view ${view.width}x${view.height} px at screen ${at[0]},${at[1]}. ${viewState(view)}")
            append("Mode ${view.mode.name.lowercase()}; tool ")
            append(when (val tool = s.tool) {
                app.orcaandroid.ui.Tool.None -> "none"
                is app.orcaandroid.ui.Tool.Paint -> "paint ${tool.kind} ${tool.shape} (state ${tool.state}, radius ${n(tool.radius.toDouble())} mm)"
                is app.orcaandroid.ui.Tool.Sculpt -> "sculpt ${tool.brush} (radius ${n(tool.radius.toDouble())} mm, strength ${n(tool.strength.toDouble())}${if (tool.invert) ", inverted" else ""})"
                is app.orcaandroid.ui.Tool.MeshEdit -> "mesh edit ${tool.kind}${if (tool.triangles) " (triangles)" else ""}"
                app.orcaandroid.ui.Tool.LayOnFace -> "lay on face"
                app.orcaandroid.ui.Tool.Measure -> "measure, ${s.measure.size} point(s)" +
                    (s.loupe?.let { l -> " (loupe at ${l.x.toInt()},${l.y.toInt()}: ${l.feature?.kind ?: "nothing"})" } ?: "") +
                    (s.measureActive?.let { " (point ${it + 1} selected)" } ?: "") + s.measure.joinToString("") { m ->
                    " [${m.kind}${m.diameter?.let { d -> " D ${n(d.toDouble())}" } ?: ""} at ${n(m.point.x.toDouble())}, ${n(m.point.y.toDouble())}, ${n(m.point.z.toDouble())}]"
                } + (if (s.measure.size == 2) app.orcaandroid.ui.measureResult(s.measure[0], s.measure[1]).let { r ->
                    ": distance ${n(r.distance.toDouble())} mm" + (r.gap?.let { ", gap ${n(it.toDouble())} mm" } ?: "") +
                        (r.toPlane?.let { ", to plane ${n(it.toDouble())} mm" } ?: "") + (r.angle?.let { ", angle ${n(it.toDouble())} deg" } ?: "")
                } else "") + (s.measureLine?.third?.let { l ->
                    "; line locks to " + (if (l.axis >= 0) "XYZ"[l.axis].toString() else "${n(l.elevation.toDouble())} deg up, ${n(l.azimuth.toDouble())} deg round") +
                        ", along ${n(l.along.toDouble())} mm"
                } ?: "")
                is app.orcaandroid.ui.Tool.Cut -> "cut at Z ${n(tool.z.toDouble())} mm, tilt ${n(app.orcaandroid.render.CutPlane.tilt(tool.rot).toDouble())} deg"
                app.orcaandroid.ui.Tool.LayerHeight -> "variable layer height"
            })
            if (s.selectMode) append("; select mode on")
            s.contextMenu?.let { (x, y) -> append("; context menu open at ${px(x, y)}") }
            appendLine(".")
            appendLine("Selected: " + if (selected.isEmpty()) "nothing" else selected.joinToString { sel ->
                "object ${sel.obj + 1} copy ${sel.instance + 1}" + if (sel.volume >= 0) " part ${sel.volume + 1}" else ""
            })
            for (plate in scene.plates) {
                val lo = Vec3(plate.originX, plate.originY, 0f)
                append("Plate ${plate.index + 1}${if (plate.index == s.activePlate) " (active)" else ""}: ")
                appendLine(box(lo, Vec3(plate.originX + scene.plateWidth, plate.originY + scene.plateDepth, 0f)))
                plate.tower?.let { (x, y, tw, td, th) ->
                    appendLine("  prime tower: ${box(Vec3(lo.x + x, lo.y + y, 0f), Vec3(lo.x + x + tw, lo.y + y + td, th))}")
                }
            }
            for (o in scene.objects) for (i in o.instances) {
                val mark = if (selected.any { it.obj == o.index && it.instance == i.index }) " [selected]" else ""
                val where = if (i.plate < 0) " (off the plates)" else ""
                appendLine("object ${o.index + 1} \"${o.name}\" copy ${i.index + 1}$mark$where: ${box(i.min, i.min + i.size)}")
            }
        }.trimEnd()
    }

    private suspend fun viewShot(args: JSONObject): JSONArray {
        val size = if (args.has("size")) args.optInt("size").coerceIn(256, 1280) else 720
        val (view, caption) = withContext(Dispatchers.Main.immediate) { plateView().let { it to viewState(it) } }
        // The GL thread takes the last view change on its next frame; the shadow map follows one frame later.
        kotlinx.coroutines.delay(400)
        // A still Preview refines over ~32 frames (temporal supersampling): wait for it, a few seconds at most.
        for (i in 0 until 40) { if (view.renderer.settled) break; kotlinx.coroutines.delay(100) }
        val bitmap =android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val done = CompletableDeferred<Int>()
        withContext(Dispatchers.Main.immediate) {
            android.view.PixelCopy.request(view, bitmap, { done.complete(it) }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        val code = withTimeoutOrNull(5_000) { done.await() } ?: throw ToolError("The 3D view did not answer in time")
        if (code != android.view.PixelCopy.SUCCESS) throw ToolError("The screen copy failed (code $code); is the app in the foreground?")
        // The shadow pass lowers the surface's alpha: the screen shows that over the dark window behind it, so lay the
        // copy on black, not as an opaque picture (where a shadow turns into a light halo).
        val flat = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flat).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        val scale = size.toFloat() / maxOf(flat.width, flat.height)
        val shot = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(flat, (flat.width * scale).toInt().coerceAtLeast(1), (flat.height * scale).toInt().coerceAtLeast(1), true) else flat
        val png = java.io.ByteArrayOutputStream().also { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return JSONArray()
            .put(JSONObject().put("type", "image").put("data", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)).put("mimeType", "image/png"))
            .put(JSONObject().put("type", "text").put("text", caption))
    }

    private fun plateArg(args: JSONObject): Int {
        val scene = store.value.scene
        if (!args.has("plate")) return store.value.activePlate
        val plate = args.optInt("plate", 0) - 1
        if (plate !in scene.plates.indices) throw ToolError("No plate ${plate + 1}; there are ${scene.plates.size}")
        return plate
    }

    private suspend fun slice(args: JSONObject): String {
        val plate = withContext(Dispatchers.Main.immediate) {
            plateArg(args).also { p ->
                if (store.value.isSlicing) throw ToolError("A slice is already running")
                if (store.value.scene.objectsOn(p).isEmpty()) throw ToolError("Plate ${p + 1} is empty")
                store.update { it.copy(error = null) }
            }
        }
        val job = slicing.slice(plate)
        if (withTimeoutOrNull(SLICE_TIMEOUT) { job.join() } == null) throw ToolError("Slicing takes longer than ${SLICE_TIMEOUT / 1000} s; it goes on in the app (slice_result later)")
        (store.value.sliceError?.second ?: store.value.error)?.let { throw ToolError("Slicing failed: $it") }
        return sliceResult(JSONObject().put("plate", plate + 1))
    }

    private suspend fun sliceResult(args: JSONObject): String = withContext(Dispatchers.Main.immediate) {
        val plate = plateArg(args)
        val r = store.value.results[plate] ?: throw ToolError("Plate ${plate + 1} has no slice result: run slice first")
        buildString {
            appendLine("Plate ${plate + 1}: ${duration(r.printTimeSeconds)} print time, ${n(r.filamentGrams)} g (${n(r.filamentMm / 1000.0)} m) filament, " +
                "cost ${n(r.cost)}, ${r.layers.size} layers, top at z ${n(r.layers.lastOrNull()?.z?.toDouble() ?: 0.0)} mm, travel ${duration(r.travelTime.toDouble())}")
            r.roles.filter { it.time > 0f || it.filamentG > 0f }.forEach {
                appendLine("  ${ROLE_NAMES.getOrElse(it.role) { "?" }}: ${duration(it.time.toDouble())}, ${n(it.filamentG.toDouble())} g")
            }
            if (r.warnings.isEmpty()) append("No warnings.") else appendLine("Warnings:").also { r.warnings.take(15).forEach { w -> appendLine("  $w") } }
        }.trimEnd()
    }

    private suspend fun plateDescribe(): String = withContext(Dispatchers.Main.immediate) {
        val s = store.value
        buildString {
            appendLine("Printer: ${s.printer ?: "(none selected)"}")
            appendLine("Process: ${s.print ?: "(none)"}")
            if (s.filaments.isEmpty()) appendLine("Filaments: (none)")
            else s.filaments.forEachIndexed { i, f -> appendLine("Filament ${i + 1}: ${f.preset}${f.color?.let { c -> " ($c)" } ?: ""}") }
            val scene = s.scene
            appendLine("Plate size ${n(scene.plateWidth.toDouble())} x ${n(scene.plateDepth.toDouble())} mm")
            if (scene.objects.isEmpty()) { append("The plate is empty."); return@buildString }
            fun copies(plate: Int) = scene.objects.forEach { o ->
                val here = o.instances.filter { it.plate == plate }
                if (here.isEmpty()) return@forEach
                appendLine("  object ${o.index + 1} \"${o.name}\", ${o.triangles} triangles")
                here.forEach { i -> appendLine("    copy ${i.index + 1}: " + copyText(scene, i)) }
            }
            for (plate in scene.plates) {
                append("Plate ${plate.index + 1}")
                if (plate.index == s.activePlate) append(" (active)")
                if (plate.bedType.isNotEmpty()) append(", ${plate.bedType}")
                appendLine(": ${scene.objectsOn(plate.index).size} object(s)")
                copies(plate.index)
                s.results[plate.index]?.let { r ->
                    appendLine("  sliced: ${duration(r.printTimeSeconds)}, ${n(r.filamentGrams)} g, ${n(r.filamentMm / 1000.0)} m filament")
                }
            }
            if (scene.outsideCount > 0) { appendLine("Off the plates:"); copies(-1) }
        }
    }

    /** One copy's centre (on a plate), size, turn and scale: the part of a plate_describe line that an edit can change. */
    private fun copyText(scene: Scene, i: Instance) = buildString {
        if (i.plate >= 0) append("centre ${v2(footprint(scene, i))}, ")
        append("size ${n(i.size.x.toDouble())} x ${n(i.size.y.toDouble())} x ${n(i.size.z.toDouble())} mm")
        i.rotation.takeIf { r -> r.x != 0f || r.y != 0f || r.z != 0f }?.let { append(", rot ${v(it.d3())}") }
        i.scale.takeIf { c -> c.x != 1f || c.y != 1f || c.z != 1f }?.let { append(", scale ${v((it * 100f).d3())} %") }
    }

    /** The plates as lines keyed "Plate N" / "object N copy C", to tell what a plate edit changed. */
    private fun plateState(): Map<String, String> {
        val s = store.value
        val scene = s.scene
        val out = LinkedHashMap<String, String>()
        for (p in scene.plates) {
            out["Plate ${p.index + 1}"] = "Plate ${p.index + 1}${if (p.index == s.activePlate) " (active)" else ""}: ${scene.objectsOn(p.index).size} object(s)" +
                (s.results[p.index]?.let { r -> ", sliced: ${duration(r.printTimeSeconds)}, ${n(r.filamentGrams)} g" } ?: "")
        }
        for (o in scene.objects) for (i in o.instances) {
            val where = if (i.plate >= 0) "plate ${i.plate + 1}" else "off the plates"
            out["object ${o.index + 1} copy ${i.index + 1}"] = "object ${o.index + 1} \"${o.name}\" copy ${i.index + 1} ($where, ${o.triangles} triangles): " + copyText(scene, i)
        }
        return out
    }

    // --- Plate edits ----------------------------------------------------------------------------------

    /** Centre of [i]'s footprint in its plate's frame (mm from the plate's front-left corner). */
    private fun footprint(scene: Scene, i: Instance, plate: Int = i.plate): Pair<Double, Double> {
        val p = scene.plates.getOrNull(plate) ?: scene.plates.firstOrNull()
        return (i.min.x + i.size.x / 2 - (p?.originX ?: 0f)).toDouble() to (i.min.y + i.size.y / 2 - (p?.originY ?: 0f)).toDouble()
    }

    /** The 0-based object and copy that [args] name (1-based "object" / "copy"). */
    private fun copyOf(args: JSONObject): Pair<Int, Instance> {
        val o = args.optInt("object", 0) - 1
        val obj = store.value.scene.objects.getOrNull(o) ?: throw ToolError("No object ${o + 1} (see plate_describe)")
        val c = args.optInt("copy", 1) - 1
        return o to (obj.instances.getOrNull(c) ?: throw ToolError("Object ${o + 1} has no copy ${c + 1}"))
    }

    /** Runs engine operation [op] and adopts its scene (one undo step in the app). */
    private suspend fun plateOp(summary: String, busy: Int? = null, op: suspend () -> Scene) {
        if (busy != null) store.update { it.copy(busy = store.str(busy)) }
        try {
            val scene = op()
            withContext(Dispatchers.Main.immediate) { store.applyScene(scene) }
        } finally {
            if (busy != null) store.update { it.copy(busy = null) }
        }
        store.toast(store.str(R.string.ai_edit, summary))
    }

    /** The reply to a plate edit: what changed since [before] (a [plateState]), side effects included; plate_describe has the rest. */
    private fun plateDiff(summary: String, before: Map<String, String>): String {
        val after = plateState()
        val lines = after.filter { (k, l) -> before[k] != l }.values + before.keys.filter { it !in after }.map { "$it: removed" }
        return "Done: $summary.\n" + if (lines.isEmpty()) "Nothing changed on the plates." else lines.joinToString("\n")
    }

    /** Runs engine operation [op], adopts its scene (one undo step in the app) and returns what it changed. */
    private suspend fun plateEdit(summary: String, busy: Int? = null, op: suspend () -> Scene): String {
        val before = plateState()
        plateOp(summary, busy, op)
        return plateDiff(summary, before)
    }

    private suspend fun transform(args: JSONObject): String {
        val (o, inst) = copyOf(args)
        val rot = args.vec3("rot")
        val scale = args.vec3("scale")?.also { if (it.x <= 0 || it.y <= 0 || it.z <= 0) throw ToolError("scale must be > 0") }
        val pos = args.optJSONArray("pos")?.let { a ->
            if (a.length() != 2 || (0..1).any { !a.optDouble(it).isFinite() }) throw ToolError("pos must be 2 numbers")
            a.getDouble(0) to a.getDouble(1)
        }
        val plate = if (args.has("plate")) args.optInt("plate") - 1 else null
        if (plate != null && store.value.scene.plates.getOrNull(plate) == null) throw ToolError("No plate ${plate + 1}")
        if (plate != null && pos == null) throw ToolError("plate needs pos")
        if (pos == null && rot == null && scale == null) throw ToolError("Give pos, rot or scale")
        val ref = "object ${o + 1} copy ${inst.index + 1}"
        val before = plateState() // one snapshot for both steps, or the turn is missing from the reply
        if (rot != null || scale != null) plateOp("turned / scaled $ref") {
            store.engine.setTransform(o, inst.index, rotation = rot?.f(), scale = scale?.let { (it.f() * 0.01f) })
        }
        if (pos != null) plateOp("moved $ref") {
            // After a turn / scale the footprint has moved: measure it in the current scene.
            val scene = store.value.scene
            val now = scene.objects[o].instances[inst.index]
            val (x, y) = footprint(scene, now, plate ?: now.plate.coerceAtLeast(0))
            store.engine.setTransform(o, inst.index, offset = now.offset + Vec3((pos.first - x).toFloat(), (pos.second - y).toFloat(), 0f))
        }
        return plateDiff(if (pos != null && (rot != null || scale != null)) "turned / scaled and moved $ref" else if (pos != null) "moved $ref" else "turned / scaled $ref", before)
    }

    private suspend fun layOnFace(args: JSONObject): String {
        val (o, inst) = copyOf(args)
        val normal = args.vec3("normal") ?: throw ToolError("normal must be 3 numbers")
        if (normal.x == 0.0 && normal.y == 0.0 && normal.z == 0.0) throw ToolError("normal must not be [0, 0, 0]")
        return plateEdit("laid object ${o + 1} copy ${inst.index + 1} on a face") { store.engine.layOnFace(o, inst.index, normal.f()) }
    }

    private suspend fun autoOrient(args: JSONObject): String {
        val o = if (args.has("object")) copyOf(args).first else -1
        return plateEdit(if (o < 0) "oriented all objects" else "oriented object ${o + 1}", R.string.orienting) { store.engine.autoOrient(o) }
    }

    private suspend fun checkMesh(args: JSONObject): String {
        val (o, inst) = copyOf(args)
        val thickness = args.optDouble("thickness", 0.4)
        if (thickness !in 0.0..5.0) throw ToolError("thickness must be 0..5 mm")
        return "Object ${o + 1} copy ${inst.index + 1}\n" + app.orcaandroid.core.MeshReport.text(store.engine.checkMesh(o, inst.index, thickness.toFloat()))
    }

    private suspend fun deform(args: JSONObject): String {
        val (o, inst) = copyOf(args)
        val kind = args.optString("kind")
        if (kind !in listOf("bend", "twist", "taper")) throw ToolError("kind must be bend, twist or taper")
        val axis = args.optInt("axis", -1)
        if (axis !in 0..2) throw ToolError("axis must be 0..2")
        val amount = args.optDouble("amount", Double.NaN)
        if (amount.isNaN() || (kind == "taper" && amount <= 0)) throw ToolError("amount must be a number (taper: > 0)")
        return plateEdit("${mapOf("bend" to "bent", "twist" to "twisted", "taper" to "tapered").getValue(kind)} object ${o + 1}", R.string.deforming) {
            store.engine.deform(o, inst.index, kind, axis, amount.toFloat())
        }
    }

    private suspend fun sculpt(args: JSONObject): String {
        val origin = args.vec3("origin") ?: throw ToolError("origin must be [x, y, z]")
        val dir = args.vec3("dir") ?: throw ToolError("dir must be [x, y, z]")
        val brush = args.optString("brush")
        if (brush !in listOf("draw", "inflate", "smooth", "flatten", "pinch", "crease", "grab")) throw ToolError("unknown brush")
        val radius = args.optDouble("radius", 5.0)
        if (radius.isNaN() || radius <= 0) throw ToolError("radius must be > 0")
        val hit = store.engine.pick(origin.f(), dir.f()) ?: throw ToolError("the ray misses every object")
        val dabs = args.optInt("dabs", 1).coerceIn(1, 50)
        val mirror = args.optString("mirror").lowercase().fold(0) { m, c -> m or when (c) { 'x' -> 1; 'y' -> 2; 'z' -> 4; else -> throw ToolError("mirror takes x, y and z") } }
        return plateEdit("sculpted object ${hit.obj + 1}", R.string.deforming) {
            store.engine.sculpt(hit, List(dabs) { origin.f() to dir.f() }, brush, radius.toFloat(), args.optDouble("strength", 0.5).toFloat(),
                args.optBoolean("invert", false), newStroke = true, end = true, mirror = mirror).scene ?: throw ToolError("sculpt returned no scene")
        }
    }

    /** The part the last mesh_edit selected on, for ray-less calls. */
    private var meshEditTarget: PickHit? = null
    /** The scene's mesh version when [meshEditTarget] was last valid: undo / delete / another edit change it (stale indices). */
    private var meshEditVersion = -1

    private suspend fun meshEdit(args: JSONObject): String {
        val origin = args.vec3("origin")
        val dir = args.vec3("dir")
        if ((origin == null) != (dir == null)) throw ToolError("Give both origin and dir")
        val op = args.optString("op").ifEmpty { if (origin != null) "select" else "" }
        if (op !in listOf("", "select", "clear", "all", "grow", "extrude", "inset", "cut", "fillet", "chamfer")) throw ToolError("op must be select, clear, all, grow, extrude, inset, cut, fillet or chamfer")
        val kind = args.optString("kind", "face")
        if (kind !in listOf("face", "edge", "vertex")) throw ToolError("kind must be face, edge or vertex")
        val angle = args.optDouble("angle", 30.0).takeIf { it.isFinite() && it >= 0 } ?: throw ToolError("angle must be >= 0")
        val move = args.vec3("move")
        val scale = args.vec3("scale")?.also { if (it.x <= 0 || it.y <= 0 || it.z <= 0) throw ToolError("scale must be > 0") }
        val rotate = args.vec3("rotate")
        if (op.isEmpty() && move == null && scale == null && rotate == null) throw ToolError("Give a ray, an op or a transform")
        val ray = if (origin != null && dir != null) origin.f() to dir.f() else null
        if ((op == "select" || op == "cut") && ray == null) throw ToolError("$op needs origin and dir")
        if (ray != null && op in listOf("select", "extrude", "inset", "cut", "fillet", "chamfer")) {
            meshEditTarget = store.engine.pick(ray.first, ray.second) ?: throw ToolError("the ray misses every object")
            meshEditVersion = store.value.scene.meshVersion
        } else if (meshEditVersion != store.value.scene.meshVersion) meshEditTarget = null
        val target = meshEditTarget ?: throw ToolError("Nothing selected (or the plate changed since): select with a ray first")
        if (op == "extrude" || op == "inset") {
            val amount = args.optDouble("amount", Double.NaN).takeIf { it.isFinite() && (if (op == "inset") it > 0 else it != 0.0) }
                ?: throw ToolError(if (op == "inset") "inset needs amount > 0" else "extrude needs a non-zero amount")
            if (ray != null) store.engine.meshEdit(target, "select", ray, kind, angle.toFloat(), args.optBoolean("toggle"))
            var sel: JSONObject? = null
            val reply = plateEdit("${if (op == "inset") "inset" else "extruded"} the faces of object ${target.obj + 1}", R.string.deforming) {
                val step = if (op == "inset") store.engine.meshEdit(target, "inset", kind = kind, angle = angle.toFloat(), amount = amount.toFloat())
                else {
                    // Zero height first: the reply has the cap's normal, then the cap moves along it (moves are from the drag start).
                    val start = store.engine.meshEdit(target, "extrude", matrix = meshEditMatrix(null, null, null))
                    val n = start.selection.getJSONArray("normal")
                    store.engine.meshEdit(target, "move", matrix = meshEditMatrix(D3(n.getDouble(0) * amount, n.getDouble(1) * amount, n.getDouble(2) * amount), null, null))
                    store.engine.meshEdit(target, "end", kind = kind, angle = angle.toFloat())
                }
                sel = step.selection
                step.scene ?: throw ToolError("mesh_edit returned no scene")
            }
            meshEditVersion = store.value.scene.meshVersion
            return reply + "\n" + selectionText(sel!!)
        }
        if (op == "fillet" || op == "chamfer") {
            val amount = args.optDouble("amount", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: throw ToolError("$op needs amount > 0")
            if (ray != null) store.engine.meshEdit(target, "select", ray, kind, angle.toFloat(), args.optBoolean("toggle"))
            var sel: JSONObject? = null
            val reply = plateEdit("${if (op == "fillet") "rounded" else "chamfered"} the edges of object ${target.obj + 1}", R.string.deforming) {
                val step = store.engine.meshEdit(target, op, kind = kind, angle = angle.toFloat(), amount = amount.toFloat())
                sel = step.selection
                step.scene ?: throw ToolError("mesh_edit returned no scene")
            }
            meshEditVersion = store.value.scene.meshVersion
            return reply + "\n" + selectionText(sel!!)
        }
        if (op == "cut") {
            val axis = args.vec3("axis") ?: D3(0.0, 0.0, 1.0)
            if (axis.x == 0.0 && axis.y == 0.0 && axis.z == 0.0) throw ToolError("axis must not be zero")
            var sel: JSONObject? = null
            val reply = plateEdit("cut a ring into object ${target.obj + 1}", R.string.deforming) {
                val cut = store.engine.meshEdit(target, "cut", ray, kind, angle.toFloat(), axis = listOf(axis.x, axis.y, axis.z))
                var step = cut
                if (move != null || scale != null || rotate != null) {
                    store.engine.meshEdit(target, "move", matrix = meshEditMatrix(move, scale, rotate))
                    step = store.engine.meshEdit(target, "end")
                }
                sel = step.selection
                step.scene ?: throw ToolError("mesh_edit returned no scene")
            }
            meshEditVersion = store.value.scene.meshVersion
            return reply + "\n" + selectionText(sel!!)
        }
        var sel = if (op.isEmpty()) null else store.engine.meshEdit(target, op, ray, kind, angle.toFloat(), args.optBoolean("toggle")).selection
        if (move == null && scale == null && rotate == null) return selectionText(sel!!)
        val reply = plateEdit("edited the mesh of object ${target.obj + 1}", R.string.deforming) {
            store.engine.meshEdit(target, "move", matrix = meshEditMatrix(move, scale, rotate))
            val end = store.engine.meshEdit(target, "end")
            sel = end.selection
            end.scene ?: throw ToolError("mesh_edit returned no scene")
        }
        meshEditVersion = store.value.scene.meshVersion
        return reply + "\n" + selectionText(sel!!)
    }

    private fun selectionText(sel: JSONObject): String {
        val count = sel.optInt("count")
        if (count == 0) return "Selection: nothing."
        fun at(key: String) = sel.getJSONArray(key).let { a -> v(D3(a.getDouble(0), a.getDouble(1), a.getDouble(2))) }
        return "Selection: $count vertices, centroid ${at("centroid")}, normal ${at("normal")}, ${sel.getJSONArray("edges").length() / 6} crease edges."
    }

    private suspend fun hollow(args: JSONObject): String {
        val (o, inst) = copyOf(args)
        val wall = args.optDouble("wall", Double.NaN)
        if (wall.isNaN() || wall < 0.8 || wall > 10) throw ToolError("wall must be 0.8..10 mm")
        return plateEdit("hollowed object ${o + 1}", R.string.hollowing) { store.engine.hollowObject(o, inst.index, wall.toFloat()) }
    }

    private suspend fun smoothBlend(args: JSONObject): String {
        val (o, inst) = copyOf(args)
        val radius = args.optDouble("radius", Double.NaN)
        if (radius.isNaN() || radius < 0.5 || radius > 50) throw ToolError("radius must be 0.5..50 mm")
        return plateEdit("blended object ${o + 1}", R.string.blending) { store.engine.smoothBlend(o, inst.index, radius.toFloat()) }
    }

    private suspend fun duplicate(args: JSONObject): String {
        val (o, inst) = copyOf(args)
        val copies = if (args.has("copies")) args.optInt("copies", 0) else 1
        if (copies !in 1..100) throw ToolError("copies must be 1..100")
        return plateEdit("added $copies cop${if (copies == 1) "y" else "ies"} of object ${o + 1}") { store.engine.duplicate(listOf(o to inst.index), copies) }
    }

    private suspend fun arrange(args: JSONObject): String {
        val all = args.optBoolean("all")
        val plate = if (args.has("plate")) args.optInt("plate") - 1 else store.value.activePlate
        if (!all && store.value.scene.plates.getOrNull(plate) == null) throw ToolError("No plate ${plate + 1}")
        return plateEdit(if (all) "arranged all plates" else "arranged plate ${plate + 1}", R.string.arranging) {
            store.engine.arrange(if (all) -1 else plate)
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
        val now = render(st.name, after, st.selected)
        if (after == before) return@withContext "Nothing changed.\n" + now.header
        store.toast(store.str(R.string.ai_edit, summary))
        diff(render(st.name, before, st.selected), now, after.shapes.isEmpty())
    }

    private suspend fun set(args: JSONObject): String {
        val o = args.optJSONObject("design") ?: throw ToolError("design must be an object {shapes, dims}")
        val problems = validate(o, store.value.scad?.shapes)
        if (problems.isNotEmpty()) throw ToolError("Design not valid:\n" + problems.joinToString("\n"))
        val next = ShapeDesign.of(o)
        val name = args.optString("name").trim().takeIf { it.isNotEmpty() }
        return edit("changed the design (${next.shapes.sumOf { it.all().count() }} shapes)") {
            shapes.replace(next)
            name?.let(scad::rename)
        }
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
                if (k == Shape.REVOLVE) {
                    // Like sketch_set's revolve: the sketch's box follows the drawing turned or extruded.
                    val s = d.find(id)
                    val sk = s?.sketch?.takeIf { s.kind == Shape.Kind.SKETCH } ?: throw ToolError("extra.revolve is only for sketch shapes")
                    val on = !e.isNull(k) && (e.optBoolean(k) || e.optDouble(k, 0.0) != 0.0)
                    val solved = sk.solve({ d.dim(it)?.value })
                    d = d.update(id) { ShapeDesign.fitSketch(it.copy(extra = it.extra + (Shape.REVOLVE to if (on) 1.0 else 0.0)), solved) }
                    return@forEach
                }
                if (k !in EXTRA_KEYS) throw ToolError("extra.$k is not one of ${(EXTRA_KEYS + Shape.REVOLVE).joinToString()}")
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
        if (o.has("filament")) {
            val f = o.optInt("filament", -1)
            if (f !in 0..store.value.filaments.size) throw ToolError("filament must be 0 (the object's) .. ${store.value.filaments.size}")
            if (d.shapes.none { it.id == id }) throw ToolError("#$id is inside a group; only top-level shapes have a filament")
            d = d.update(id) { it.copy(filament = f) }
        }
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
            // top: the outline an extrusion lofts to; JSON null removes it, absent leaves it.
            val top = if (!args.has("top")) null else args.optJSONObject("top")?.let { t ->
                Sketch.of(t)?.takeIf { it.loops.isNotEmpty() } ?: throw ToolError("top needs loops like sketch") }
            val path = if (!args.has("path")) null else args.optJSONObject("path")?.let { t ->
                Sketch.of(t)?.takeIf { it.loops.isNotEmpty() } ?: throw ToolError("path needs loops like sketch") }
            shapes.replace(d.update(id) { s0 ->
                val s = s0.let { if (args.has("top")) it.copy(top = top) else it }.let { if (args.has("path")) it.copy(path = path) else it }
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
    private fun v2(p: Pair<Double, Double>) = "[${n(p.first)}, ${n(p.second)}]"
    private fun Vec3.d3() = D3(x.toDouble(), y.toDouble(), z.toDouble())
    private fun D3.f() = Vec3(x.toFloat(), y.toFloat(), z.toFloat())

    companion object {
        private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val EXTRA_KEYS = listOf(Shape.SIDES, Shape.TOP, Shape.WALL, Shape.TUBE, Shape.RADIUS, Shape.EDGE, Shape.CHAMFER,
            Shape.SHELL, Shape.OPEN, Shape.ANGLE, Shape.BLEND, Shape.HULL, Shape.PITCH, Shape.TWIST)
        private val BIND_FIELDS = listOf("size", "pos", "rot").flatMap { f -> "xyz".map { "$f.$it" } }
        private val NOTE_WORDS = listOf("ERROR", "WARNING")
        private const val RENDER_TIMEOUT = 180_000L
        private const val SLICE_TIMEOUT = 600_000L
        private const val CONFIRM_TIMEOUT = 60_000L

        private const val EDIT = "One undo step in the app (the user can press Undo)."
        private const val OPENS = " Opens the design editor on the saved draft when it is closed."
        private const val PLATE_EDIT = "Changes the user's plate (not the design); one undo step in the app. Returns what changed; plate_describe has the rest."

        /** Which copy a plate tool works on. */
        private val COPY = arrayOf("object" to p("integer", "Object number from plate_describe"),
            "copy" to p("integer", "Copy number from plate_describe; default 1"))

        internal fun p(type: String, description: String) = JSONObject().put("type", type).put("description", description)
        private fun vec(description: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "number"))
            .put("minItems", 3).put("maxItems", 3).put("description", description)
        private fun ids() = JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")).put("description", "Shape ids")

        internal fun obj(required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) = JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }

        /** Optional shape fields of shape_add / shape_update. */
        private val FIELDS = arrayOf(
            "name" to p("string", "Shown name"),
            "size" to vec("Bounding box [x, y, z] mm"),
            "pos" to vec("Centre of the box's bottom in x / y, its bottom in z, mm, in the parent's frame"),
            "rot" to vec("Degrees about the box centre, X then Y then Z"),
            "hole" to p("boolean", "true = cuts the solids of its own group"),
            "filament" to p("integer", "Top-level solids: the filament slot (1-based, see plate_describe) it prints with; 0 = the object's"),
            "mirror" to vec("1 = mirrored on that axis, 0 = not"),
            "extra" to p("object", "Kind specific numbers (null removes one): sides, top (cone top / bottom ratio), wall, tube, " +
                "radius, edge (rounded edges mm), chamfer (1 = chamfer the edge), shell (hollow wall mm), open (1 = open top), angle, revolve (sketch: 1 = turn about Z), pitch (revolved sketch: rise per turn mm, > 0 makes a coil; angle then up to 3600), twist (extruded sketch: degrees), blend (group: round where its shapes meet, mm; boxes / cylinders / cones / spheres)"),
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
            "hole (true = cuts the solids of its own group), mirror [x,y,z] (1 = mirrored on that axis about the shape's own pos: " +
            "to mirror across the origin, mirror a group placed at the origin), extra {sides, top " +
            "(cone top / bottom ratio), wall, tube, radius, edge (rounded edges mm), chamfer (1 = chamfer edge), shell " +
            "(hollow wall mm), open (1 = open top)}, text (text kind), repeat {circular, count, step [x,y,z], angle, " +
            "center [x,y,z]; circular: angle 360 spreads the copies evenly round the circle, any other angle puts the first copy " +
            "and the last angle apart}, sketch (sketch kind, see sketch_set), bind {\"size.x\": dimName, ...} (field driven by a " +
            "dim), children [shape] (group only)}. Keep ids of shapes you don't change; give new shapes new ids."

        const val INSTRUCTIONS = "OrcaStudio-Android (3D printing slicer). These tools edit the shape-builder design open in the " +
            "app's design editor (OpenSCAD is generated from it). Read with design_describe first (cheapest), design_get for " +
            "the JSON, design_snapshot for a picture when shape matters. Change it with the shape_* / dim_* / sketch_set tools " +
            "(or design_set for a whole new design); an edit replies with the dims and top-level shape trees it changed (side effects " +
            "included), removed ids and the fit check, so design_describe has the full tree. Check with design_preview, and ask design_to_plate to put it on the plate " +
            "(the user confirms on screen). plate_describe shows the print set-up and the plate itself (separate from the design); " +
            "the object_* / plate_arrange tools move, turn, scale, copy and arrange the objects on it. Print settings: preset_list / " +
            "preset_get / setting_find read the printer, process and filament presets, settings_edit changes settings as unsaved " +
            "edits, preset_select switches process / filament, preset_save and preset_delete wait for the user's Allow. " +
            "Every edit is one undo step the user can revert in the app."
    }
}
