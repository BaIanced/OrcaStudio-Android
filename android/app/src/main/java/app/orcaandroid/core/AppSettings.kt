package app.orcaandroid.core

import android.content.Context
import app.orcaandroid.net.PrinterConnection
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A recently opened or saved file. */
data class RecentFile(val uri: String, val name: String, val isProject: Boolean, val time: Long)

/** Persists app-wide settings, remembered selections and per-printer connections. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString("theme", value.name).apply()

    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamic_color", false)
        set(value) = prefs.edit().putBoolean("dynamic_color", value).apply()

    /** Colour preset and the user's own colours (More > Appearance, design mode). */
    var themeSpec: ThemeSpec
        get() = ThemeSpec.fromJson(prefs.getString("theme_spec", null))
        set(value) = prefs.edit().putString("theme_spec", value.toJson()).apply()

    /** Undo steps the engine keeps. */
    var undoLimit: Int
        get() = prefs.getInt("undo_limit", DEFAULT_UNDO_LIMIT)
        set(value) = prefs.edit().putInt("undo_limit", value).apply()

    /** Saves the session this often (seconds) while the plate has unsaved changes; 0 or less = only when the app goes to the background
     *  (switched off keeps the interval as -seconds, for switching it on again). */
    var autoSaveSeconds: Int
        get() = prefs.getInt("auto_save_seconds", DEFAULT_AUTO_SAVE_SECONDS)
        set(value) = prefs.edit().putInt("auto_save_seconds", value).apply()

    var layoutLab: LayoutLab
        get() = LayoutLab.fromJson(prefs.getString("layout_lab", null))
        set(value) = prefs.edit().putString("layout_lab", value.toJson()).apply()

    /** When profile updates were last looked for (ms); the automatic check runs at most once a day. */
    var lastProfileCheck: Long
        get() = prefs.getLong("last_profile_check", 0L)
        set(value) = prefs.edit().putLong("last_profile_check", value).apply()

    /** Back on the home screen quits after pressing it twice instead of asking. */
    var doubleBackToQuit: Boolean
        get() = prefs.getBoolean("double_back_quit", false)
        set(value) = prefs.edit().putBoolean("double_back_quit", value).apply()

    /** The local MCP server for AI clients (see `docs/mcp-tool-layer.md`); off by default. */
    var aiTools: Boolean
        get() = prefs.getBoolean("ai_tools", false)
        set(value) = prefs.edit().putBoolean("ai_tools", value).apply()

    /** The MCP server also listens on the local network (all interfaces), not only on 127.0.0.1. */
    var aiToolsLan: Boolean
        get() = prefs.getBoolean("ai_tools_lan", false)
        set(value) = prefs.edit().putBoolean("ai_tools_lan", value).apply()

    /** Bearer token AI clients must send; made on first use, replaced by [newAiToken]. */
    val aiToken: String
        get() = prefs.getString("ai_token", null) ?: newAiToken()

    fun newAiToken(): String {
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("ai_token", token).apply()
        return token
    }

    /** Printers chosen in the setup, as printerKey(model, nozzle). */
    var selectedPrinters: Set<String>
        get() = prefs.getStringSet("selected_printers", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("selected_printers", value).apply()

    /** Filament vendors/types hidden from the filament lists (like the desktop's filament selection). */
    var hiddenFilamentVendors: Set<String>
        get() = prefs.getStringSet("hidden_filament_vendors", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("hidden_filament_vendors", value).apply()

    /** Plate types hidden from the plate type pickers (like [hiddenFilamentVendors]). */
    var hiddenBedTypes: Set<String>
        get() = prefs.getStringSet("hidden_bed_types", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("hidden_bed_types", value).apply()

    /** Plate type of plates without their own; "" = the printer's default. */
    var preferredBedType: String
        get() = prefs.getString("preferred_bed_type", null).orEmpty()
        set(value) = prefs.edit().putString("preferred_bed_type", value).apply()

    /** AMS sync keeps the filament presets the user picked for its trays ([trayPicks]). */
    var preferMyFilaments: Boolean
        get() = prefs.getBoolean("prefer_my_filaments", true)
        set(value) = prefs.edit().putBoolean("prefer_my_filaments", value).apply()

    /** Presets the user picked for AMS trays: [TraySource.key] -> (what the tray reported then, the preset). */
    var trayPicks: Map<String, Pair<String, String>>
        get() = runCatching { JSONObject(prefs.getString("tray_picks", null) ?: "{}") }.getOrNull()
            ?.let { o -> o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { it.getString("loaded") to it.getString("preset") } } }.orEmpty()
        set(value) = prefs.edit().putString("tray_picks",
            JSONObject(value.mapValues { (_, p) -> JSONObject().put("loaded", p.first).put("preset", p.second) }).toString()).apply()

    /** Hide printer presets whose nozzle differs from the one the connected printer reports. */
    var matchNozzle: Boolean
        get() = prefs.getBoolean("match_nozzle", true)
        set(value) = prefs.edit().putBoolean("match_nozzle", value).apply()

    /** Last nozzle diameter (mm) each printer model reported, by printer_model. */
    var installedNozzles: Map<String, Float>
        get() = runCatching { JSONObject(prefs.getString("installed_nozzles", null) ?: "{}") }.getOrNull()
            ?.let { o -> o.keys().asSequence().associateWith { o.getDouble(it).toFloat() } }.orEmpty()
        set(value) = prefs.edit().putString("installed_nozzles", JSONObject(value.mapValues { it.value.toDouble() }).toString()).apply()

    var lastPrinter: String?
        get() = prefs.getString("printer", null)
        set(value) = prefs.edit().putString("printer", value).apply()

    fun lastPrint(printer: String): String? = prefs.getString("print:$printer", null)
    fun setLastPrint(printer: String, print: String) = prefs.edit().putString("print:$printer", print).apply()

    /**
     * The system preset a user printer preset inherits: both stand for the same physical printer, so
     * they share its connection and loaded filaments (kept under the base preset's name).
     */
    fun setPrinterBase(printer: String, base: String) = prefs.edit().apply {
        if (base.isEmpty() || base == printer) remove("base:$printer") else putString("base:$printer", base)
    }.apply()

    private fun family(printer: String) = prefs.getString("base:$printer", null) ?: printer

    fun lastFilaments(printer: String): List<FilamentSlot> =
        filaments(family(printer)).ifEmpty { filaments(printer) }

    private fun filaments(key: String): List<FilamentSlot> = prefs.getString("filaments:$key", null)?.let { json ->
        runCatching {
            JSONArray(json).map {
                val o = it as JSONObject
                FilamentSlot(o.getString("preset"), o.optString("color").ifEmpty { null },
                    source = o.optJSONObject("source")?.let { s -> TraySource(s.getString("key"), s.getString("loaded"), s.getString("synced")) })
            }
        }.getOrNull()
    }.orEmpty()

    fun setLastFilaments(printer: String, slots: List<FilamentSlot>) = prefs.edit().putString(
        "filaments:${family(printer)}",
        JSONArray(slots.map { f ->
            JSONObject().put("preset", f.preset).put("color", f.color ?: "").apply {
                f.source?.let { put("source", JSONObject().put("key", it.key).put("loaded", it.loaded).put("synced", it.synced)) }
            }
        }).toString(),
    ).apply()

    fun connection(printer: String): PrinterConnection? = (prefs.getString("conn:${family(printer)}", null) ?: prefs.getString("conn:$printer", null))
        ?.let { runCatching { PrinterConnection.fromJson(it) }.getOrNull() }

    fun setConnection(printer: String, connection: PrinterConnection?) {
        val key = family(printer)
        prefs.edit().apply {
            if (key != printer) remove("conn:$printer")
            if (connection == null) remove("conn:$key") else putString("conn:$key", connection.toJson())
        }.apply()
    }

    var recents: List<RecentFile>
        get() = runCatching {
            JSONArray(prefs.getString("recents", "[]")).map {
                val o = it as JSONObject
                RecentFile(o.getString("uri"), o.getString("name"), o.optBoolean("project"), o.optLong("time"))
            }
        }.getOrDefault(emptyList())
        set(value) = prefs.edit().putString("recents", JSONArray(value.map {
            JSONObject().put("uri", it.uri).put("name", it.name).put("project", it.isProject).put("time", it.time)
        }).toString()).apply()

    fun addRecent(file: RecentFile) {
        recents = (listOf(file) + recents.filter { it.uri != file.uri }).take(12)
    }
}

const val DEFAULT_UNDO_LIMIT = 200
const val MAX_UNDO_LIMIT = 5000
const val DEFAULT_AUTO_SAVE_SECONDS = 60
val AUTO_SAVE_RANGE = 10..3600
const val AI_TOOLS_PORT = 8765
