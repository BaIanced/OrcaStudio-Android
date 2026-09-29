package com.orcaslicer.android.core

import android.content.Context
import java.util.Locale
import org.json.JSONObject

/**
 * Translates option labels, tooltips and settings page names with the desktop app's catalogs
 * (assets/i18n/<lang>.json, generated from localization/i18n/<lang>/OrcaSlicer_<lang>.po).
 */
class Translator private constructor(private val entries: Map<String, String>) {

    fun tr(text: String): String = if (text.isEmpty()) text else entries[text] ?: text

    companion object {
        val NONE = Translator(emptyMap())

        fun load(context: Context, locale: Locale = Locale.getDefault()): Translator {
            val available = context.assets.list("i18n")?.map { it.removeSuffix(".json") }?.toSet().orEmpty()
            // Catalog names are "de", "pt_BR", "zh_CN", ...
            val candidates = listOf("${locale.language}_${locale.country}", locale.language)
            val lang = candidates.firstOrNull { it in available }
                ?: available.firstOrNull { it.startsWith(locale.language + "_") }
                ?: return NONE
            if (lang == "en") return NONE
            val json = context.assets.open("i18n/$lang.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val map = HashMap<String, String>(obj.length() * 2)
            obj.keys().forEach { map[it] = obj.getString(it) }
            return Translator(map)
        }
    }
}
