package com.orcaslicer.android.core

import android.content.Context
import android.util.Log
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the on-device layout the native core expects and serializes every call into it.
 *
 *   filesDir/resources      extracted resources.zip (resources_dir)
 *   filesDir/data           data_dir; data/system/<Vendor>.json + <Vendor>/ are installed vendors
 *   cacheDir/models         imported model files (original names, the core picks the loader by extension)
 *   cacheDir/out            preview mesh, toolpath preview and G-code of the last slice
 */
class OrcaRepository(private val context: Context) {

    private companion object {
        const val TAG = "Orca"
    }

    /** The core is not re-entrant; one thread keeps all native calls in order. */
    private val native = Executors.newSingleThreadExecutor { r -> Thread(r, "orca-core").apply { priority = Thread.NORM_PRIORITY } }
        .asCoroutineDispatcher()

    private val resourcesDir = File(context.filesDir, "resources")
    private val dataDir = File(context.filesDir, "data")
    private val systemDir = File(dataDir, "system")
    private val modelsDir = File(context.cacheDir, "models")
    private val outDir = File(context.cacheDir, "out")

    /** Extracts bundled resources on first start or after an app update, then initializes the core. */
    suspend fun initialize() = withContext(native) {
        val prefs = context.getSharedPreferences("orca", Context.MODE_PRIVATE)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        if (prefs.getLong("resources_version", -1) != version || !resourcesDir.isDirectory) {
            resourcesDir.deleteRecursively()
            context.assets.open("resources.zip").use { unzip(it, resourcesDir) }
            // Installed vendors come from the same APK, so refresh them too.
            installedVendorIds().forEach { extractVendor(it) }
            prefs.edit().putLong("resources_version", version).apply()
        }
        listOf(dataDir, modelsDir, outDir).forEach { it.mkdirs() }
        JSONObject(OrcaNative.init(resourcesDir.path, dataDir.path, File(context.cacheDir, "tmp").path)).throwIfError()
        Unit
    }

    fun availableVendors(): List<Vendor> {
        val json = context.assets.open("vendors/index.json").bufferedReader().use { it.readText() }
        return JSONArray(json).map {
            val o = it as JSONObject
            val models = o.getJSONArray("models").map { m ->
                val model = m as JSONObject
                PrinterModel(model.getString("name"), model.getJSONArray("nozzles").map { n -> n as String })
            }
            Vendor(o.getString("id"), o.getString("name"), models, o.optBoolean("required"))
        }
    }

    fun installedVendorIds(): Set<String> =
        systemDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    /** Installs exactly [vendorIds] (plus the always-required filament library). */
    suspend fun setInstalledVendors(vendorIds: Set<String>) = withContext(native) {
        val required = availableVendors().filter { it.required }.map { it.id }
        val wanted = vendorIds + required
        systemDir.mkdirs()
        for (id in installedVendorIds() - wanted) {
            File(systemDir, "$id.json").delete()
            File(systemDir, id).deleteRecursively()
        }
        for (id in wanted - installedVendorIds()) extractVendor(id)
    }

    private fun extractVendor(id: String) {
        File(systemDir, id).deleteRecursively()
        context.assets.open("vendors/$id.zip").use { unzip(it, systemDir) }
    }

    suspend fun loadPresets(): List<PrinterInfo> = withContext(native) {
        val result = JSONObject(OrcaNative.loadPresets()).throwIfError()
        result.optString("errors").takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "Preset loading: $it") }
        parsePrinters(result)
    }

    suspend fun loadPrinterList(): List<PrinterInfo> = withContext(native) {
        parsePrinters(JSONObject(OrcaNative.printerList()).throwIfError())
    }

    private fun parsePrinters(result: JSONObject) = result.getJSONArray("printers").map {
        val o = it as JSONObject
        PrinterInfo(o.getString("name"), o.getString("vendor"), o.optString("model"), o.optDouble("nozzle"), o.optBoolean("system"))
    }

    suspend fun selectPrinter(name: String): PrinterSetup = withContext(native) {
        val o = JSONObject(OrcaNative.selectPrinter(name)).throwIfError()
        Log.i(TAG, "Printer $name: ${o.getJSONArray("prints").length()} processes, ${o.getJSONArray("filaments").length()} filaments")
        parseSetup(o)
    }

    private fun parseSetup(o: JSONObject): PrinterSetup {
        val bed = o.getJSONArray("bed")
        return PrinterSetup(
            prints = o.getJSONArray("prints").toPresetRefs(),
            filaments = o.getJSONArray("filaments").toPresetRefs(),
            bed = FloatArray(bed.length() * 2) { i -> bed.getJSONArray(i / 2).getDouble(i % 2).toFloat() },
            maxHeight = o.getDouble("max_height").toFloat(),
            defaultPrint = o.getString("default_print"),
            defaultFilament = o.getString("default_filament"),
        )
    }

    /** Copies the picked documents into the cache (keeping their names) and loads them as the plate. */
    suspend fun loadModel(uris: List<Uri>, copies: Int): ModelInfo = withContext(native) {
        modelsDir.listFiles()?.forEach { it.delete() }
        val paths = uris.mapIndexed { i, uri ->
            val file = File(modelsDir, "${i}_${displayName(uri)}")
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            file.path
        }
        val mesh = File(outDir, "mesh.bin")
        val o = JSONObject(OrcaNative.loadModel(paths.toTypedArray(), copies, mesh.path)).throwIfError()
        ModelInfo(
            objects = o.getInt("objects"),
            triangles = o.getInt("triangles"),
            fits = o.optBoolean("fits", true),
            min = o.getJSONArray("min").toFloatArray(),
            size = o.getJSONArray("size").toFloatArray(),
            meshFile = mesh.path,
        )
    }

    suspend fun slice(
        print: String,
        filament: String,
        overrides: Overrides,
        onProgress: (Int, String) -> Unit,
    ): SliceResult = withContext(native) {
        val gcode = File(outDir, "plate.gcode")
        val preview = File(outDir, "toolpaths.bin")
        val o = JSONObject(
            OrcaNative.slice(print, filament, overrides.merged().toString(), gcode.path, preview.path) { p, t -> onProgress(p, t) },
        ).throwIfError()
        SliceResult(
            gcodeFile = o.getString("gcode"),
            previewFile = preview.path,
            printTimeSeconds = o.getDouble("print_time_s"),
            filamentMm = o.getDouble("filament_mm"),
            filamentGrams = o.getDouble("filament_g"),
            cost = o.getDouble("cost"),
            layers = o.getJSONArray("layers").map { val l = it as JSONArray; LayerMark(l.getDouble(0).toFloat(), l.getInt(1)) },
            warnings = o.getJSONArray("warnings").map { it as String },
        )
    }

    private val defsCache = HashMap<PresetType, List<OptionDef>>()

    suspend fun optionDefs(type: PresetType): List<OptionDef> = withContext(native) {
        defsCache.getOrPut(type) {
            JSONArray(OrcaNative.optionDefs(type.id)).map {
                val o = it as JSONObject
                OptionDef(
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
                    enumValues = o.optJSONArray("enum_values")?.map { v -> v as String }.orEmpty(),
                    enumLabels = o.optJSONArray("enum_labels")?.map { v -> v as String }.orEmpty(),
                    readonly = o.optBoolean("readonly"),
                    multiline = o.optBoolean("multiline"),
                    isCode = o.optBoolean("is_code"),
                )
            }
        }
    }

    suspend fun presetValues(type: PresetType, name: String): Map<String, String> = withContext(native) {
        val o = JSONObject(OrcaNative.presetValues(type.id, name)).throwIfError()
        o.keys().asSequence().associateWith { o.getString(it) }
    }

    suspend fun savePreset(type: PresetType, base: String, newName: String, overrides: Map<String, String>): PrinterSetup =
        withContext(native) {
            parseSetup(JSONObject(OrcaNative.savePreset(type.id, base, newName, JSONObject(overrides).toString())).throwIfError())
        }

    suspend fun deletePreset(type: PresetType, name: String): PrinterSetup = withContext(native) {
        parseSetup(JSONObject(OrcaNative.deletePreset(type.id, name)).throwIfError())
    }

    /** Desktop settings layout per type (assets/settings_layout.json). */
    fun settingsLayout(type: PresetType): List<SettingsPage> {
        val json = context.assets.open("settings_layout.json").bufferedReader().use { it.readText() }
        return JSONObject(json).getJSONArray(type.id).map { p ->
            val page = p as JSONObject
            SettingsPage(page.getString("page"), page.getJSONArray("groups").map { g ->
                val group = g as JSONObject
                SettingsGroup(group.getString("group"), group.getJSONArray("options").map { it as String })
            })
        }
    }

    /** Safe to call from any thread while [slice] runs. */
    fun cancelSlicing() = OrcaNative.cancel()

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it.replace('/', '_') }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.stl"
    }

    private fun unzip(input: InputStream, target: File) {
        val root = target.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                require(out.path.startsWith(root.path + File.separator)) { "Bad zip entry ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }
}
