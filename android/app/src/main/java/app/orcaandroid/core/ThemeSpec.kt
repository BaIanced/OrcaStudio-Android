package app.orcaandroid.core

import org.json.JSONObject

/**
 * The app's colours as the user chose them: a [preset] and, per light / dark mode, colour overrides
 * by UI region and colour slot (region key -> slot key -> ARGB). Keys are defined in `ui/theme/AppColors.kt`.
 */
data class ThemeSpec(
    val preset: String = DEFAULT_PRESET,
    val dark: Map<String, Map<String, Int>> = emptyMap(),
    val light: Map<String, Map<String, Int>> = emptyMap(),
) {
    fun overrides(dark: Boolean) = if (dark) this.dark else light

    /** Sets (or with null removes) one colour of the [dark] or light mode. */
    fun with(dark: Boolean, region: String, slot: String, argb: Int?): ThemeSpec {
        val modes = overrides(dark)
        val slots = modes[region].orEmpty().let { if (argb == null) it - slot else it + (slot to argb) }
        val updated = if (slots.isEmpty()) modes - region else modes + (region to slots)
        return if (dark) copy(dark = updated) else copy(light = updated)
    }

    /** Drops the [dark] or light mode's overrides of [region], or all of them when [region] is null. */
    fun reset(dark: Boolean, region: String? = null): ThemeSpec {
        val updated = if (region == null) emptyMap() else overrides(dark) - region
        return if (dark) copy(dark = updated) else copy(light = updated)
    }

    fun customCount(dark: Boolean) = overrides(dark).values.sumOf { it.size }

    fun toJson(): String = JSONObject().put("preset", preset).put("dark", modeJson(dark)).put("light", modeJson(light)).toString()

    companion object {
        const val DEFAULT_PRESET = "prusa"

        private fun modeJson(m: Map<String, Map<String, Int>>) =
            JSONObject(m.mapValues { (_, slots) -> JSONObject(slots.mapValues { "#%08X".format(it.value) }) })

        private fun parseMode(o: JSONObject?): Map<String, Map<String, Int>> = o?.keys()?.asSequence()?.mapNotNull { region ->
            // A region stored as anything but an object is skipped, not fatal:
            // getJSONObject would throw and fromJson would drop the whole spec.
            val slots = o.optJSONObject(region) ?: return@mapNotNull null
            val parsed = slots.keys().asSequence().mapNotNull { k ->
                slots.optString(k).removePrefix("#").toLongOrNull(16)?.let { k to it.toInt() }
            }.toMap()
            if (parsed.isEmpty()) null else region to parsed
        }?.toMap().orEmpty()

        fun fromJson(json: String?): ThemeSpec = json?.let {
            runCatching {
                val o = JSONObject(it)
                ThemeSpec(o.optString("preset").ifEmpty { DEFAULT_PRESET }, parseMode(o.optJSONObject("dark")), parseMode(o.optJSONObject("light")))
            }.getOrNull()
        } ?: ThemeSpec()
    }
}
