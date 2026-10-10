package app.orcaandroid.core

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed Kotlin API over the native engine. The core is not re-entrant, so every call runs on one
 * dedicated thread; only [cancelSlicing] may be called concurrently.
 */
class Engine(private val cacheDir: File) {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "orca-engine") }.asCoroutineDispatcher()

    private suspend fun call(method: String, args: JSONObject = JSONObject()): JSONObject = withContext(thread) {
        JSONObject(OrcaNative.call(method, args.toString())).throwIfError()
    }

    suspend fun init(resourcesDir: File, dataDir: File) = withContext(thread) {
        JSONObject(OrcaNative.init(resourcesDir.path, dataDir.path, File(cacheDir, "tmp").path)).throwIfError()
        Unit
    }

    // --- Presets ---------------------------------------------------------------------------------

    suspend fun loadPresets(): List<PrinterInfo> = parsePrinters(call("loadPresets"))
    suspend fun printerList(): List<PrinterInfo> = parsePrinters(call("printerList"))
    suspend fun selectPrinter(name: String): PrinterSetup = parseSetup(call("selectPrinter", args("printer" to name)))

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

    suspend fun optionStates(): OptionStates = call("optionStates").let { o ->
        OptionStates(o.getJSONArray("disabled").map { it as String }.toSet(), o.getJSONArray("hidden").map { it as String }.toSet())
    }

    suspend fun importPresets(paths: List<String>): Pair<List<PrinterInfo>, PrinterSetup> =
        call("importPresets", args("paths" to paths)).let { parsePrinters(it) to parseSetup(it) }

    suspend fun presetFile(type: PresetType, name: String): String = call("presetFile", args("type" to type.id, "name" to name)).getString("path")

    /** Loads cloud presets ({ name: { key: value } }) and saves them as user presets; returns how many and the printers. */
    suspend fun loadCloudPresets(presets: JSONObject): Pair<Int, List<PrinterInfo>> =
        call("loadCloudPresets", args("presets" to presets)).let { it.optInt("count") to parsePrinters(it) }

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
    suspend fun setTransform(obj: Int, instance: Int, offset: Vec3? = null, rotation: Vec3? = null, scale: Vec3? = null, mirror: Vec3? = null) =
        parseScene(call("setTransform", args("object" to obj, "instance" to instance,
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
    suspend fun cut(obj: Int, instance: Int, z: Float, keepUpper: Boolean, keepLower: Boolean, flipUpper: Boolean) =
        parseScene(call("cut", args("object" to obj, "instance" to instance, "z" to z, "keepUpper" to keepUpper, "keepLower" to keepLower, "flipUpper" to flipUpper)))
    suspend fun simplify(obj: Int, ratio: Float) = parseScene(call("simplify", args("object" to obj, "ratio" to ratio)))
    suspend fun repair(obj: Int) = parseScene(call("repair", args("object" to obj)))
    suspend fun addVolume(obj: Int, type: VolumeType, shape: String, size: Vec3) =
        parseScene(call("addVolume", args("object" to obj, "type" to type.code, "shape" to shape, "size" to size)))
    suspend fun deleteVolume(obj: Int, volume: Int) = parseScene(call("deleteVolume", args("object" to obj, "volume" to volume)))
    suspend fun setObjectSetting(obj: Int, volume: Int, key: String, value: String?) =
        parseScene(call("setObjectSetting", args("object" to obj, "volume" to volume, "key" to key, "value" to value)))
    /** The same object setting on several objects, as one undo step. */
    suspend fun setObjectsSetting(objs: List<Int>, key: String, value: String?) =
        parseScene(call("setObjectSetting", args("objects" to objs, "key" to key, "value" to value)))
    suspend fun setLayerRanges(obj: Int, ranges: List<LayerRange>) = parseScene(call("setLayerRanges", args("object" to obj,
        "ranges" to ranges.map { mapOf("from" to it.from, "to" to it.to, "settings" to it.settings) })))
    suspend fun addPlate() = parseScene(call("addPlate"))
    suspend fun deletePlate(plate: Int) = parseScene(call("deletePlate", args("plate" to plate)))
    suspend fun setPlateBedType(plate: Int, bedType: String) = parseScene(call("setPlateBedType", args("plate" to plate, "bedType" to bedType)))
    suspend fun undo() = parseScene(call("undo"))
    suspend fun redo() = parseScene(call("redo"))
    suspend fun addPrimitive(shape: String, size: Vec3, plate: Int) = parseScene(call("addPrimitive", args("shape" to shape, "size" to size, "plate" to plate)))
    suspend fun addText(text: String, font: String, height: Float, depth: Float, plate: Int) =
        parseScene(call("addText", args("text" to text, "font" to font, "height" to height, "depth" to depth, "plate" to plate)))
    suspend fun addSvg(path: String, width: Float, depth: Float, plate: Int) =
        parseScene(call("addSvg", args("path" to path, "width" to width, "depth" to depth, "plate" to plate)))

    // --- Tools -----------------------------------------------------------------------------------

    suspend fun pick(origin: Vec3, dir: Vec3): PickHit? = call("pick", args("origin" to origin, "dir" to dir)).let { o ->
        if (!o.optBoolean("hit")) null
        else PickHit(o.getInt("object"), o.getInt("instance"), o.getInt("volume"), o.getInt("facet"), o.vec3("point"), o.vec3("normal"), o.vec3("local"))
    }

    suspend fun paint(hit: PickHit, camera: Vec3, radius: Float, kind: String, state: Int, newStroke: Boolean) = parseScene(call("paint", args(
        "object" to hit.obj, "instance" to hit.instance, "volume" to hit.volume, "facet" to hit.facet, "local" to hit.local,
        "camera" to camera, "radius" to radius, "kind" to kind, "state" to state, "newStroke" to newStroke)))
    suspend fun paintClear(obj: Int, kind: String) = parseScene(call("paintClear", args("object" to obj, "kind" to kind)))

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
    suspend fun exportGcode3mf(plate: Int, gcode: String, path: String) { call("exportGcode3mf", args("plate" to plate, "gcode" to gcode, "path" to path)) }

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

    /** Safe to call from any thread while slicing runs. */
    fun cancelSlicing() = OrcaNative.cancel()

    // --- Parsing ---------------------------------------------------------------------------------

    private fun parsePrinters(o: JSONObject) = o.getJSONArray("printers").map {
        val p = it as JSONObject
        PrinterInfo(p.getString("name"), p.getString("vendor"), p.optString("model"), p.optDouble("nozzle"), p.optBoolean("system"), p.optString("base"))
    }

    private fun parseRefs(a: JSONArray) = a.map {
        val o = it as JSONObject
        PresetRef(o.getString("name"), o.optString("vendor"), o.optBoolean("system"), o.optString("brand"), o.optString("type"))
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
                        Volume(vi, v.optString("name"), VolumeType.of(v.getInt("type")), v.stringMap("settings"), v.vec3("offset"), v.vec3("size"))
                    } },
                    instances = obj.getJSONArray("instances").let { ia -> List(ia.length()) { ii ->
                        val inst = ia.getJSONObject(ii)
                        Instance(ii, inst.getInt("plate"), inst.vec3("offset"), inst.vec3("rotation"), inst.vec3("scale"), inst.vec3("mirror"), inst.vec3("size"), inst.vec3("min"))
                    } },
                    layerRanges = obj.optJSONArray("layer_ranges")?.map {
                        val r = it as JSONObject
                        LayerRange(r.getDouble("from").toFloat(), r.getDouble("to").toFloat(), r.stringMap("settings"))
                    }.orEmpty(),
                    hasLayerProfile = obj.optBoolean("layer_profile"),
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
