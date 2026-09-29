package com.orcaslicer.android.core

import android.content.Context
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Protocols the app can upload G-code with; everything else is web-UI only. */
enum class HostType(val id: String, val label: String, val canUpload: Boolean) {
    MOONRAKER("moonraker", "Klipper (Moonraker)", true),
    OCTOPRINT("octoprint", "OctoPrint", true),
    PRUSALINK("prusalink", "PrusaLink", true),
    OTHER("other", "Nur Weboberfläche", false);

    companion object {
        fun fromId(id: String?): HostType = entries.firstOrNull { it.id == id } ?: when (id) {
            null, "" -> MOONRAKER
            else -> OTHER
        }
    }
}

/** Network access to a physical printer: where to upload G-code and which web UI to show. */
data class PrinterConnection(
    val type: HostType = HostType.MOONRAKER,
    /** Base URL, e.g. "http://192.168.1.50" or "http://printer.local:7125". */
    val url: String = "",
    val apiKey: String = "",
    /** Web UI to show in the device tab; empty = [url]. Mainsail/Fluidd usually live on port 80. */
    val webUrl: String = "",
) {
    val isConfigured get() = url.isNotBlank()

    fun webUiUrl(): String = normalizeUrl(webUrl.ifBlank { url })

    fun toJson(): String = JSONObject()
        .put("type", type.id).put("url", url).put("apiKey", apiKey).put("webUrl", webUrl).toString()

    companion object {
        fun fromJson(json: String): PrinterConnection = JSONObject(json).let {
            PrinterConnection(HostType.fromId(it.optString("type")), it.optString("url"), it.optString("apiKey"), it.optString("webUrl"))
        }

        /** Adds "http://" when the user typed a bare host name or IP. */
        fun normalizeUrl(url: String): String {
            val u = url.trim().trimEnd('/')
            return if (u.isEmpty() || u.contains("://")) u else "http://$u"
        }
    }
}

/** Persists app-wide settings and per-printer-preset connections. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString("theme", value.name).apply()

    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamic_color", false)
        set(value) = prefs.edit().putBoolean("dynamic_color", value).apply()

    fun connection(printer: String): PrinterConnection? =
        prefs.getString("conn:$printer", null)?.let { runCatching { PrinterConnection.fromJson(it) }.getOrNull() }

    fun setConnection(printer: String, connection: PrinterConnection?) {
        prefs.edit().apply {
            if (connection == null) remove("conn:$printer") else putString("conn:$printer", connection.toJson())
        }.apply()
    }
}
