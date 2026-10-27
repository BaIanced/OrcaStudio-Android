package app.orcaandroid.core

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed Kotlin API over the native engine. The core is not re-entrant, so every call runs on one
 * dedicated thread; only [cancel] may be called concurrently.
 */
class Engine(private val cacheDir: File) {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "orca-engine") }.asCoroutineDispatcher()

    private suspend fun call(method: String, args: JSONObject = JSONObject()): JSONObject = withContext(thread) {
        // Android's JSONObject.toString() returns null when a nested array holds NaN or Infinity (put() rejects them
        // only at the top level); unchecked, that null surfaced as a bare NullPointerException dialog (r197).
        val json: String = args.toString() ?: throw OrcaException("$method: a number is not finite (NaN or Infinity)")
        JSONObject(OrcaNative.call(method, json)).throwIfError()
    }

    suspend fun init(resourcesDir: File, dataDir: File) = withContext(thread) {
        JSONObject(OrcaNative.init(resourcesDir.path, dataDir.path, File(cacheDir, "tmp").path)).throwIfError()
        Unit
    }

    // --- Presets ---------------------------------------------------------------------------------

    suspend fun loadPresets(): List<PrinterInfo> = parsePrinters(call("loadPresets"))
    suspend fun printerList(): List<PrinterInfo> = parsePrinters(call("printerList"))
    suspend fun selectPrinter(name: String): PrinterSetup = parseSetup(call("selectPrinter", args("printer" to name)))
    /** Plate type of plates without their own ("" = the printer's default); returns the selected printer's resulting default. */
    suspend fun setDefaultBedType(bedType: String): String =
        call("setDefaultBedType", args("bedType" to bedType)).optString("default_bed_type")

    /** Lets filaments whose nozzle temperatures are far apart share a print (desktop: "Remove mixed temperature restriction"). */
    suspend fun setAllowMixedTemperatures(allow: Boolean) { call("setAllowMixedTemperatures", args("allow" to allow)) }

    private val defsCache = HashMap<PresetType, List<OptionDef>>()

    suspend fun optionDefs(type: PresetType): List<OptionDef> = defsCache[type] ?: call("optionDefs", args("type" to type.id))
        .getJSONArray("defs").map { parseDef(it as JSONObject) }.also { defsCache[type] = it }

    suspend fun presetValues(type: PresetType, name: String): Map<String, String> =
        call("presetValues", args("type" to type.id, "name" to name)).strings()

    suspend fun savePreset(type: PresetType, base: String, name: String, overrides: Map<String, String>): PrinterSetup =
        parseSetup(call("savePreset", args("type" to type.id, "base" to base, "name" to name, "overrides" to overrides)))

    suspend fun deletePreset(type: PresetType, name: String): PrinterSetup =
        parseSetup(call("deletePreset", args("type" to type.id, "name" to name)))

    suspend fun setSelection(print: String, filaments: List<FilamentSlot>, overrides: Map<String, String>) {
        val fils = filaments.map { f -> mapOf("name" to f.preset, "color" to f.color, "overrides" to f.overrides) }
        call("setSelection", args("print" to print, "filaments" to fils, "overrides" to overrides))
    }

    /** [objectSettings]: an object / part / range editor's settings, applied over the process (null: the process). */
    suspend fun optionStates(objectSettings: Map<String, String>? = null): OptionStates =
        call("optionStates", if (objectSettings == null) JSONObject() else args("object" to objectSettings)).let { o ->
        OptionStates(o.getJSONArray("disabled").map { it as String }.toSet(), o.getJSONArray("hidden").map { it as String }.toSet())
    }

    suspend fun importPresets(paths: List<String>): Pair<List<PrinterInfo>, PrinterSetup> =
        call("importPresets", args("paths" to paths)).let { parsePrinters(it) to parseSetup(it) }

    suspend fun presetFile(type: PresetType, name: String): String = call("presetFile", args("type" to type.id, "name" to name)).getString("path")

    /** Loads cloud presets ({ name: { key: value } }) and saves them as user presets; returns how many and the printers. */
    suspend fun loadCloudPresets(presets: JSONObject): Pair<Int, List<PrinterInfo>> =
        call("loadCloudPresets", args("presets" to presets)).let { it.optInt("count") to parsePrinters(it) }

    /** User presets to upload to the cloud account [userId]: [{type, name, setting_id, sync_info, values}]. */
    suspend fun cloudUploads(userId: String): org.json.JSONArray = call("cloudUploads", args("userId" to userId)).getJSONArray("presets")

    /** Records an upload: the preset's cloud id, sync state ("" = in sync) and cloud updated_time. */
    suspend fun markUploaded(type: String, name: String, settingId: String, syncInfo: String, updatedTime: Long) {
        call("markUploaded", args("type" to type, "name" to name, "settingId" to settingId, "syncInfo" to syncInfo, "updatedTime" to updatedTime))
    }

    /** Installed version of a vendor's profiles, "" if not installed. */
    suspend fun vendorVersion(vendor: String): String = call("vendorVersion", args("vendor" to vendor)).optString("version")

    /**
     * The desktop's filament sync: one slot per loaded tray (keys: filament_id, filament_type,
     * color, colors, color_type, ams_id, slot_id, name). Returns the new slots and, per tray that
     * had no exact preset, the tray name and the reason.
     */
    suspend fun syncFilaments(trays: List<Map<String, Any?>>, filaments: List<FilamentSlot>): Pair<List<FilamentSlot>, List<Pair<String, String>>> {
        val fils = filaments.map { f -> mapOf("name" to f.preset, "color" to f.color) }
        val r = call("syncFilaments", args("trays" to trays, "filaments" to fils))
        val slots = r.getJSONArray("filaments").map { (it as JSONObject).let { f -> FilamentSlot(f.getString("name"), f.optString("color").ifEmpty { null }) } }
        val unknown = r.getJSONArray("unknown").map { (it as JSONObject).let { u -> u.optString("tray") to u.optString("message") } }
        return slots to unknown
    }

    // --- Scene -----------------------------------------------------------------------------------

    suspend fun scene() = parseScene(call("scene"))
    suspend fun loadModels(paths: List<String>, append: Boolean, plate: Int) =
        parseScene(call("loadModels", args("paths" to paths, "append" to append, "plate" to plate)))
    /** [volume] >= 0 transforms that part within its object instead of the copy. */
    suspend fun setTransform(obj: Int, instance: Int, offset: Vec3? = null, rotation: Vec3? = null, scale: Vec3? = null, mirror: Vec3? = null, volume: Int = -1) =
        parseScene(call("setTransform", args("object" to obj, "instance" to instance, "volume" to volume,
            "transform" to buildMap { offset?.let { put("offset", it) }; rotation?.let { put("rotation", it) }; scale?.let { put("scale", it) }; mirror?.let { put("mirror", it) } })))
    suspend fun deleteObject(obj: Int) = parseScene(call("deleteObject", args("object" to obj)))
    suspend fun deleteInstance(obj: Int, instance: Int) = parseScene(call("deleteInstance", args("object" to obj, "instance" to instance)))
    /** [items]: (object, instance) pairs; each batch call is one undo step. */
    suspend fun duplicate(items: List<Pair<Int, Int>>, copies: Int) =
        parseScene(call("duplicate", args("items" to items.map { listOf(it.first, it.second) }, "copies" to copies)))
    suspend fun deleteItems(items: List<Pair<Int, Int>>) = parseScene(call("deleteItems", args("items" to items.map { listOf(it.first, it.second) })))
    suspend fun moveItems(items: List<Pair<Int, Int>>, dx: Float, dy: Float) =
        parseScene(call("moveItems", args("items" to items.map { listOf(it.first, it.second) }, "dx" to dx, "dy" to dy)))
    suspend fun arrange(plate: Int) = parseScene(call("arrange", args("plate" to plate)))
    suspend fun layOnFace(obj: Int, instance: Int, normal: Vec3) = parseScene(call("layOnFace", args("object" to obj, "instance" to instance, "normal" to normal)))
    suspend fun autoOrient(obj: Int) = parseScene(call("autoOrient", args("object" to obj)))
    suspend fun split(obj: Int, toParts: Boolean) = parseScene(call("split", args("object" to obj, "toParts" to toParts)))
    suspend fun assemble(items: List<Pair<Int, Int>>) = parseScene(call("assemble", args("items" to items.map { listOf(it.first, it.second) })))
    suspend fun meshBoolean(obj: Int, src: Int, tool: Int, op: String, keepTool: Boolean) =
        parseScene(call("meshBoolean", args("object" to obj, "src" to src, "tool" to tool, "op" to op, "keepTool" to keepTool)))
    suspend fun cut(obj: Int, instance: Int, z: Float, keepUpper: Boolean, keepLower: Boolean, flipUpper: Boolean, floatUpper: Boolean = false, rot: Vec3 = Vec3(0f, 0f, 0f)) =
        parseScene(call("cut", args("object" to obj, "instance" to instance, "z" to z, "keepUpper" to keepUpper, "keepLower" to keepLower, "flipUpper" to flipUpper, "floatUpper" to floatUpper, "rotX" to rot.x, "rotY" to rot.y, "rotZ" to rot.z)))
    suspend fun simplify(obj: Int, ratio: Float) = parseScene(call("simplify", args("object" to obj, "ratio" to ratio)))
    suspend fun repair(obj: Int) = parseScene(call("repair", args("object" to obj)))
    /** Bends, twists or tapers ([kind] "bend" / "twist" / "taper") one object along plate [axis] 0..2, in this copy's frame: [amount] in degrees, or the far end's scale for a taper. */
    /**
     * Live sculpt of [target]'s part: one dab per ray (world origin, direction) that hits it, in stroke order; brushes draw,
     * inflate, smooth, flatten, pinch, crease, grab. Only the changed scene triangles come back ([SculptStep.patch]);
     * [sculptEnd] writes the part's mesh and the scene. [end] does that in the same call (MCP). [probe] only hit-tests
     * the rays, for the brush cursor ([SculptStep.cursor]).
     */
    suspend fun sculpt(target: PickHit, rays: List<Pair<Vec3, Vec3>>, brush: String, radius: Float, strength: Float, invert: Boolean,
                       newStroke: Boolean, end: Boolean = false, probe: Boolean = false): SculptStep {
        val o = call("sculpt", args("object" to target.obj, "instance" to target.instance, "volume" to target.volume,
            "rays" to rays.map { (a, d) -> listOf(a.x, a.y, a.z, d.x, d.y, d.z).map { it.toDouble() } },
            "brush" to brush, "radius" to radius, "strength" to strength, "invert" to invert, "newStroke" to newStroke, "end" to end, "probe" to probe))
        val scene = if (o.has("objects")) parseScene(o) else null
        val patch = o.optString("patch").takeIf { it.isNotEmpty() }?.let { MeshPatch.load(it, o.getInt("mesh_version")) }
        val cursor = if (o.has("point")) o.vec3("point") to o.vec3("normal") else null
        return SculptStep(scene, patch, o.optBoolean("hit"), cursor)
    }
    suspend fun sculptEnd() = parseScene(call("sculptEnd"))
    suspend fun smoothBlend(obj: Int, instance: Int, radius: Float) =
        parseScene(call("smoothBlend", args("object" to obj, "instance" to instance, "radius" to radius)))
    suspend fun deform(obj: Int, instance: Int, kind: String, axis: Int, amount: Float) =
        parseScene(call("deform", args("object" to obj, "instance" to instance, "kind" to kind, "axis" to axis, "amount" to amount)))
    /** Read-only mesh report of one copy; see [MeshReport.text]. Faces whose wall is thinner than [thickness] mm are counted. */
    suspend fun checkMesh(obj: Int, instance: Int = 0, thickness: Float = 0.4f): JSONObject =
        call("checkMesh", args("object" to obj, "instance" to instance, "thickness" to thickness))
    suspend fun replaceMesh(obj: Int, path: String, module: String = "") =
        parseScene(call("replaceMesh", args("object" to obj, "path" to path, "module" to module)))
    suspend fun setObjectModule(obj: Int, module: String) =
        parseScene(call("setObjectModule", args("object" to obj, "module" to module)))
    suspend fun addVolume(obj: Int, type: VolumeType, shape: String, size: Vec3) =
        parseScene(call("addVolume", args("object" to obj, "type" to type.code, "shape" to shape, "size" to size)))
    suspend fun deleteVolume(obj: Int, volume: Int) = parseScene(call("deleteVolume", args("object" to obj, "volume" to volume)))
    /**
     * Replaces all volumes of [obj] in one undo step (Build workspace, one volume per shape piece). Each entry takes its
     * mesh from `stl` (piece frame, missing/empty file = skipped) or keeps volume `keep` (mesh + paint), placed by
     * `matrix` (row-major 4x4 into the object frame). [keepCenter] shifts the copies so the parts stay centred in place,
     * or, when entries name an `anchor` (the old volume of the same unchanged piece), so the anchors stay in place.
     */
    suspend fun setVolumes(obj: Int, volumes: List<VolumeSpec>, keepCenter: Boolean = false) =
        parseScene(call("setVolumes", args("object" to obj, "keepCenter" to keepCenter, "volumes" to volumes.map {
            // No null "stl" / "matrix": the native side reads them as a string / an array.
            mapOf("keep" to it.keep, "type" to it.type.code, "name" to it.name, "anchor" to it.anchor, "extruder" to it.extruder) + (it.stl?.let { s -> mapOf("stl" to s) } ?: emptyMap()) +
                (it.matrix?.let { m -> mapOf("matrix" to m) } ?: emptyMap())
        })))
    /** The object the Build workspace edits (-1 = none): the scene mesh then gives its volumes selection id -1000 - volume. */
    suspend fun setBuildObject(obj: Int) = parseScene(call("setBuildObject", args("object" to obj)))
    suspend fun setObjectSetting(obj: Int, volume: Int, key: String, value: String?) =
        parseScene(call("setObjectSetting", args("object" to obj, "volume" to volume, "key" to key, "value" to value)))
    /** The same object setting on several objects, as one undo step. */
    suspend fun setObjectsSetting(objs: List<Int>, key: String, value: String?) =
        parseScene(call("setObjectSetting", args("objects" to objs, "key" to key, "value" to value)))
    suspend fun setLayerRanges(obj: Int, ranges: List<LayerRange>) = parseScene(call("setLayerRanges", args("object" to obj,
        "ranges" to ranges.map { mapOf("from" to it.from, "to" to it.to, "settings" to it.settings) })))
    suspend fun addPlate() = parseScene(call("addPlate"))
    suspend fun deletePlate(plate: Int) = parseScene(call("deletePlate", args("plate" to plate)))
    /** Several plates as one undo step. */
    suspend fun deletePlates(plates: List<Int>) = parseScene(call("deletePlate", args("plates" to plates)))
    suspend fun setPlateBedType(plate: Int, bedType: String) = parseScene(call("setPlateBedType", args("plate" to plate, "bedType" to bedType)))
    suspend fun undo() = parseScene(call("undo"))
    suspend fun redo() = parseScene(call("redo"))
    suspend fun setUndoLimit(limit: Int) = parseScene(call("setUndoLimit", args("limit" to limit)))
    suspend fun addPrimitive(shape: String, size: Vec3, plate: Int, round: Float = 0f) = parseScene(call("addPrimitive", args("shape" to shape, "size" to size, "plate" to plate, "round" to round)))
    /** Shape builder Blend joins: [spec] = {radius, parts} (ShapeDesign.blendCode) fused in OCCT into the STL [out]; its triangle count. */
    suspend fun blendSolids(spec: JSONObject, out: File): Int =
        call("blendSolids", args("parts" to spec.getJSONArray("parts"), "radius" to spec.getDouble("radius"), "out" to out.path)).optInt("triangles")
    suspend fun addText(text: String, font: String, height: Float, depth: Float, plate: Int) =
        parseScene(call("addText", args("text" to text, "font" to font, "height" to height, "depth" to depth, "plate" to plate)))
    suspend fun addSvg(path: String, width: Float, depth: Float, plate: Int) =
        parseScene(call("addSvg", args("path" to path, "width" to width, "depth" to depth, "plate" to plate)))

    // --- Tools -----------------------------------------------------------------------------------

    /** [only] >= 0: hits on that object alone (the Build workspace). */
    /** [snap] > 0 (world mm): the point moves to the nearest corner or sharp edge of its facet within that distance. */
    suspend fun pick(origin: Vec3, dir: Vec3, only: Int = -1, snap: Double = 0.0): PickHit? = call("pick", args("origin" to origin, "dir" to dir, "object" to only, "snap" to snap)).let { o ->
        if (!o.optBoolean("hit")) null
        else PickHit(o.getInt("object"), o.getInt("instance"), o.getInt("volume"), o.getInt("facet"), o.vec3("point"), o.vec3("normal"), o.vec3("local"),
            if (o.has("snap")) o.getString("snap") else null,
            o.optJSONObject("feature")?.let { f -> SnapFeature(f.getString("kind"), f.optJSONArray("lines").floats(),
                if (f.has("diameter")) f.getDouble("diameter").toFloat() else null) })
    }

    /** Paints [hits] (one stroke's brush positions, in order) in one engine pass. */
    /** [joins]: per hit, whether it continues the line from the stroke's previous hit (painted as a capsule, no gaps). */
    suspend fun paint(hits: List<PickHit>, camera: Vec3, radius: Float, kind: String, state: Int, newStroke: Boolean, section: FloatArray? = null,
                      joins: List<Boolean> = emptyList(), edgeAngle: Float = 0f, shape: String = "sphere", height: Float = 0.2f, overhang: Float = 0f,
                      guard: Float? = null, overColours: Boolean = false, locked: Int = 0) = parseScene(call("paint", args(
        "hits" to hits.mapIndexed { i, it -> mapOf("object" to it.obj, "instance" to it.instance, "volume" to it.volume, "facet" to it.facet, "local" to it.local,
            "join" to joins.getOrElse(i) { false }) },
        "camera" to camera, "radius" to radius, "kind" to kind, "state" to state, "newStroke" to newStroke, "section" to section, "edgeAngle" to edgeAngle,
        "shape" to shape, "height" to height, "overhang" to overhang, "guard" to guard, "overColours" to overColours, "locked" to locked)))
    suspend fun paintClear(obj: Int, kind: String) = parseScene(call("paintClear", args("object" to obj, "kind" to kind)))
    suspend fun paintFillCancel() = parseScene(call("paintFillCancel", args()))
    /** Eyedropper: the paint state under [hit] (0 = unpainted) and the part's own extruder (1-based, 0 = the object's). */
    suspend fun paintStateAt(hit: PickHit, kind: String) = call("paintStateAt", args("object" to hit.obj, "volume" to hit.volume,
        "facet" to hit.facet, "local" to hit.local, "kind" to kind)).let { it.getInt("state") to it.optInt("base", 0) }
    suspend fun paintByPart(obj: Int) = parseScene(call("paintByPart", args("object" to obj)))
    /** Regions view: world line segments (x, y, z per end) of a copy's crease edges sharper than [angle] degrees and open edges. */
    suspend fun creaseLines(obj: Int, instance: Int, angle: Float): FloatArray =
        call("creaseLines", args("object" to obj, "instance" to instance, "angle" to angle)).getJSONArray("lines").floats()
    /** [base]: also moves unpainted faces (parts whose own filament is remapped get the new one). */
    suspend fun paintRemap(obj: Int, dest: List<Int>, base: Boolean = false) =
        parseScene(call("paintRemap", args("object" to obj, "dest" to dest, "base" to base)))
    /** Paint shop layer bars: printed surface in mm2 per filament (index 0 = filament 1), unpainted faces as their part's. */
    suspend fun paintCoverage(obj: Int): FloatArray = call("paintCoverage", args("object" to obj)).getJSONArray("area").floats()
    /** [locked]: filaments whose specks stay (bit i = filament i + 1). */
    suspend fun paintGapFill(obj: Int, kind: String, area: Float, locked: Int = 0) =
        parseScene(call("paintGapFill", args("object" to obj, "kind" to kind, "area" to area, "locked" to locked)))

    private fun parseProfile(o: JSONObject) = parseScene(o) to LayerProfile(
        o.getJSONArray("profile").floats(), o.getDouble("min").toFloat(), o.getDouble("max").toFloat(), o.getDouble("height").toFloat())
    suspend fun layerProfile(obj: Int) = parseProfile(call("layerProfile", args("object" to obj)))
    suspend fun layerAdaptive(obj: Int, quality: Float) = parseProfile(call("layerAdaptive", args("object" to obj, "quality" to quality)))
    suspend fun layerSmooth(obj: Int, radius: Int, keepMin: Boolean) = parseProfile(call("layerSmooth", args("object" to obj, "radius" to radius, "keepMin" to keepMin)))
    suspend fun layerAdjust(obj: Int, z: Float, delta: Float, band: Float) = parseProfile(call("layerAdjust", args("object" to obj, "z" to z, "delta" to delta, "band" to band)))
    suspend fun layerReset(obj: Int) = parseProfile(call("layerReset", args("object" to obj)))

    suspend fun setLayerGcodes(plate: Int, items: List<LayerGcode>) = parseScene(call("setLayerGcodes", args("plate" to plate,
        "items" to items.map { mapOf("z" to it.z, "type" to it.type, "extra" to it.extra, "color" to it.color, "extruder" to it.extruder) })))
    suspend fun setWipeTower(plate: Int, pos: Pair<Float, Float>?) =
        parseScene(call("setWipeTower", args("plate" to plate, "x" to pos?.first, "y" to pos?.second)))
    suspend fun flushMatrix(): List<Float> = call("flushMatrix").getJSONArray("matrix").floats().toList()

    suspend fun calibStart(type: String, params: Map<String, Any?>): Pair<Scene, Calibration> =
        call("calibStart", args("type" to type, "params" to params)).let { o ->
            val c = o.getJSONObject("calibration")
            parseScene(o) to Calibration(c.getString("type"), c.getString("name"), c.getDouble("start").toFloat(), c.getDouble("end").toFloat(), c.getDouble("step").toFloat())
        }
    suspend fun calibStop() = parseScene(call("calibStop"))

    // --- Files -----------------------------------------------------------------------------------

    suspend fun saveProject(path: String) { call("saveProject", args("path" to path)) }
    suspend fun loadProject(path: String): Pair<Scene, ProjectInfo> = call("loadProject", args("path" to path)).let { o ->
        val p = o.getJSONObject("project")
        parseScene(o) to ProjectInfo(
            printer = p.optString("printer"),
            print = p.optString("print"),
            filaments = p.getJSONArray("filaments").map { it as String },
            printOverrides = p.optJSONObject("print_overrides").strings(),
            filamentOverrides = p.getJSONArray("filament_overrides").map { (it as JSONObject).strings() },
            filamentColors = p.getJSONArray("filament_colors").map { it as String },
        )
    }
    suspend fun exportStl(path: String, plate: Int) { call("exportStl", args("path" to path, "plate" to plate)) }
    /** Renders a mesh file (e.g. the OpenSCAD preview STL) to a square PNG; returns its base64 (as MCP image data wants it). */
    suspend fun snapshotPng(stlPath: String, size: Int): String =
        call("snapshot", args("stl" to stlPath, "size" to size)).getString("png")
    /** [sliced]: the slice result's G-code when [gcode] is a post-processed copy of it (for slice_info.config). */
    suspend fun exportGcode3mf(plate: Int, gcode: String, path: String, sliced: String = gcode) {
        call("exportGcode3mf", args("plate" to plate, "gcode" to gcode, "path" to path, "sliced" to sliced))
    }

    // --- Slicing ---------------------------------------------------------------------------------

    suspend fun slice(plate: Int, onProgress: (Int, String) -> Unit): SliceResult = withContext(thread) {
        val out = File(cacheDir, "out/plate_${plate + 1}").apply { mkdirs() }
        val gcode = File(cacheDir, "out/plate_${plate + 1}.gcode")
        parseSlice(JSONObject(OrcaNative.slice(plate, gcode.path, out.path) { p, t -> onProgress(p, t) }).throwIfError(), out.path, false)
    }

    suspend fun viewGcode(path: String, onProgress: (Int, String) -> Unit): SliceResult = withContext(thread) {
        val out = File(cacheDir, "out/external").apply { mkdirs() }
        parseSlice(JSONObject(OrcaNative.viewGcode(path, out.path) { p, t -> onProgress(p, t) }).throwIfError(), out.path, true)
    }

    /** Cancels a running slice or smooth blend; safe to call from any thread. */
    fun cancel() = OrcaNative.cancel()

    // --- Parsing ---------------------------------------------------------------------------------

    private fun parsePrinters(o: JSONObject) = o.getJSONArray("printers").map {
        val p = it as JSONObject
        PrinterInfo(p.getString("name"), p.getString("vendor"), p.optString("model"), p.optDouble("nozzle"), p.optBoolean("system"), p.optString("base"))
    }

    private fun parseRefs(a: JSONArray) = a.map {
        val o = it as JSONObject
        PresetRef(o.getString("name"), o.optString("vendor"), o.optBoolean("system"), o.optString("brand"), o.optString("type"), o.optString("base"), o.optString("color"))
    }

    private fun parseSetup(o: JSONObject): PrinterSetup {
        val bed = o.getJSONArray("bed")
        return PrinterSetup(
            prints = parseRefs(o.getJSONArray("prints")),
            filaments = parseRefs(o.getJSONArray("filaments")),
            bed = FloatArray(bed.length() * 2) { i -> bed.getJSONArray(i / 2).getDouble(i % 2).toFloat() },
            maxHeight = o.getDouble("max_height").toFloat(),
            defaultPrint = o.getString("default_print"),
            defaultFilament = o.getString("default_filament"),
            bedTypes = o.optJSONArray("bed_types")?.map { it as String }.orEmpty(),
            defaultBedType = o.optString("default_bed_type"),
        )
    }

    private fun parseDef(o: JSONObject) = OptionDef(
        key = o.getString("key"),
        label = o.optString("label"),
        fullLabel = o.optString("full_label"),
        category = o.optString("category"),
        tooltip = o.optString("tooltip"),
        type = o.getString("type"),
        sidetext = o.optString("sidetext"),
        mode = o.optInt("mode"),
        min = if (o.has("min")) o.getDouble("min") else null,
        max = if (o.has("max")) o.getDouble("max") else null,
        enumValues = o.optJSONArray("enum_values")?.map { it as String }.orEmpty(),
        enumLabels = o.optJSONArray("enum_labels")?.map { it as String }.orEmpty(),
        readonly = o.optBoolean("readonly"),
        multiline = o.optBoolean("multiline"),
        isCode = o.optBoolean("is_code"),
        pluginType = o.optString("plugin_type"),
    )

    private fun parseScene(o: JSONObject): Scene {
        val size = o.getJSONArray("plate_size").floats()
        return Scene(
            meshFile = o.getString("mesh"),
            meshVersion = o.getInt("mesh_version"),
            paintFile = o.optString("paint_mesh"),
            paintVersion = o.optInt("paint_version"),
            canUndo = o.optBoolean("can_undo"),
            canRedo = o.optBoolean("can_redo"),
            calibrationActive = o.optBoolean("calibration_active"),
            plateWidth = size[0],
            plateDepth = size[1],
            plates = o.getJSONArray("plates").let { a -> List(a.length()) { i ->
                val p = a.getJSONObject(i)
                val origin = p.getJSONArray("origin").floats()
                val wt = p.optJSONArray("wipe_tower")?.floats()
                Plate(
                    index = i,
                    originX = origin[0],
                    originY = origin[1],
                    bedType = p.optString("bed_type"),
                    instances = p.optInt("instances"),
                    layerGcodes = p.optJSONArray("layer_gcodes")?.map {
                        val g = it as JSONObject
                        LayerGcode(g.getDouble("z").toFloat(), g.getString("type"), g.optString("extra"), g.optString("color"), g.optInt("extruder", 1))
                    }.orEmpty(),
                    wipeTower = wt?.let { it[0] to it[1] },
                    tower = p.optJSONArray("tower")?.floats(),
                )
            } },
            objects = o.getJSONArray("objects").let { a -> List(a.length()) { i ->
                val obj = a.getJSONObject(i)
                SceneObject(
                    index = i,
                    name = obj.optString("name"),
                    triangles = obj.optInt("triangles"),
                    settings = obj.stringMap("settings"),
                    volumes = obj.getJSONArray("volumes").let { va -> List(va.length()) { vi ->
                        val v = va.getJSONObject(vi)
                        Volume(vi, v.optString("name"), VolumeType.of(v.getInt("type")), v.stringMap("settings"), v.vec3("offset"), v.vec3("size"),
                            if (v.has("rotation")) v.vec3("rotation") else Vec3(0f, 0f, 0f), if (v.has("scale")) v.vec3("scale") else Vec3(1f, 1f, 1f))
                    } },
                    instances = obj.getJSONArray("instances").let { ia -> List(ia.length()) { ii ->
                        val inst = ia.getJSONObject(ii)
                        val plate = inst.getInt("plate")
                        val design = inst.optJSONObject("design")?.let {
                            Instance(ii, plate, it.vec3("offset"), Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f), Vec3(1f, 1f, 1f), it.vec3("size"), it.vec3("min"))
                        }
                        Instance(ii, plate, inst.vec3("offset"), inst.vec3("rotation"), inst.vec3("scale"), inst.vec3("mirror"), inst.vec3("size"), inst.vec3("min"), design)
                    } },
                    layerRanges = obj.optJSONArray("layer_ranges")?.map {
                        val r = it as JSONObject
                        LayerRange(r.getDouble("from").toFloat(), r.getDouble("to").toFloat(), r.stringMap("settings"))
                    }.orEmpty(),
                    hasLayerProfile = obj.optBoolean("layer_profile"),
                    module = obj.optString("module"),
                )
            } },
        )
    }

    private fun parseSlice(o: JSONObject, previewDir: String, external: Boolean): SliceResult {
        val origin = o.optJSONArray("origin").floats()
        val ranges = o.optJSONObject("ranges")
        return SliceResult(
            gcodeFile = o.getString("gcode"),
            previewDir = previewDir,
            printTimeSeconds = o.optDouble("print_time_s"),
            filamentMm = o.optDouble("filament_mm"),
            filamentGrams = o.optDouble("filament_g"),
            cost = o.optDouble("cost"),
            warnings = o.optJSONArray("warnings")?.map { it as String }.orEmpty(),
            originX = origin.getOrElse(0) { 0f },
            originY = origin.getOrElse(1) { 0f },
            layers = o.getJSONArray("layers").map {
                val l = it as JSONObject
                PreviewLayer(l.getDouble("z").toFloat(), l.getInt("extrusion"), l.getInt("travel"), l.getInt("marker"), l.optDouble("time").toFloat())
            },
            roles = o.optJSONArray("roles")?.map {
                val r = it as JSONObject
                RoleStat(r.getInt("role"), r.optDouble("time").toFloat(), r.optDouble("filament_m").toFloat(), r.optDouble("filament_g").toFloat())
            }.orEmpty(),
            travelTime = o.optDouble("travel_time").toFloat(),
            ranges = ranges?.keys()?.asSequence()?.associateWith { k -> ranges.getJSONArray(k).floats().let { it[0] to it[1] } }.orEmpty(),
            filamentUse = o.optJSONArray("filaments")?.map { val f = it as JSONObject; f.optDouble("m").toFloat() to f.optDouble("g").toFloat() }.orEmpty(),
            extrusionCount = o.optJSONObject("counts")?.optInt("extrusions") ?: 0,
            external = external,
        )
    }
}
