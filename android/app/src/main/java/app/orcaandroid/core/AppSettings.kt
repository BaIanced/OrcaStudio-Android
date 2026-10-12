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

    /** Undo steps the engine keeps. */
    var undoLimit: Int
        get() = prefs.getInt("undo_limit", DEFAULT_UNDO_LIMIT)
        set(value) = prefs.edit().putInt("undo_limit", value).apply()

    /** Saves the session this often (seconds) while the plate has unsaved changes; 0 = only when the app goes to the background. */
    var autoSaveSeconds: Int
        get() = prefs.getInt("auto_save_seconds", DEFAULT_AUTO_SAVE_SECONDS)
        set(value) = prefs.edit().putInt("auto_save_seconds", value).apply()

    /** Back on the home screen quits after pressing it twice instead of asking. */
    var doubleBackToQuit: Boolean
        get() = prefs.getBoolean("double_back_quit", false)
        set(value) = prefs.edit().putBoolean("double_back_quit", value).apply()

    /** The local MCP server for AI clients (see `docs/mcp-tool-layer.md`); off by default. */
    var aiTools: Boolean
        get() = prefs.getBoolean("ai_tools", false)
        set(value) = prefs.edit().putBoolean("ai_tools", value).apply()

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
                FilamentSlot(o.getString("preset"), o.optString("color").ifEmpty { null })
            }
        }.getOrNull()
    }.orEmpty()

    fun setLastFilaments(printer: String, slots: List<FilamentSlot>) = prefs.edit().putString(
        "filaments:${family(printer)}",
        JSONArray(slots.map { JSONObject().put("preset", it.preset).put("color", it.color ?: "") }).toString(),
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
