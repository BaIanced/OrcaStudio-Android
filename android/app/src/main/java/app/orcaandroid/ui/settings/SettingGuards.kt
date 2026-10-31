package app.orcaandroid.ui.settings

import app.orcaandroid.core.PresetType
import app.orcaandroid.ui.Screen

/** Where a notice's "Fix" link leads: an option in the settings editor, or another screen. */
sealed interface NoticeFix {
    data class Option(val type: PresetType, val key: String) : NoticeFix
    data class Go(val screen: Screen) : NoticeFix
}

/** Curated fix for a slicer message (matched on the untranslated text), null when there is no obvious one. */
fun noticeFix(message: String): NoticeFix? {
    val m = message.lowercase()
    return when {
        "prime tower" in m && "no free spot" in m -> NoticeFix.Option(PresetType.PRINT, "prime_tower_width")
        "spiral" in m && ("vase" in m || "mode" in m) -> NoticeFix.Option(PresetType.PRINT, "spiral_mode")
        "organic support" in m || "tree support" in m -> NoticeFix.Option(PresetType.PRINT, "support_type")
        "nozzle temperatures are incompatible" in m -> NoticeFix.Go(Screen.MORE)
        else -> null
    }
}

/** A setting change that can damage the printer or a print: asked twice before it is applied. */
enum class DangerRule { NOZZLE_TEMP, BED_TEMP, PRINTER_GEOMETRY, GCODE }

private val GEOMETRY_KEYS = setOf("printable_area", "printable_height", "nozzle_diameter", "z_offset", "extruder_offset")
private val GCODE_KEYS = setOf("machine_start_gcode", "machine_end_gcode", "filament_start_gcode", "filament_end_gcode")

/** The rule [value] for [key] breaks, or null. Temperatures look at every number in a vector value. */
fun dangerRule(type: PresetType, key: String, value: String): DangerRule? {
    fun maxNumber() = value.split(',', ';').mapNotNull { it.trim().toDoubleOrNull() }.maxOrNull() ?: 0.0
    return when {
        key in GCODE_KEYS -> DangerRule.GCODE
        type == PresetType.PRINTER && key in GEOMETRY_KEYS -> DangerRule.PRINTER_GEOMETRY
        key.startsWith("nozzle_temperature") && maxNumber() > 300 -> DangerRule.NOZZLE_TEMP
        key.contains("plate_temp") && maxNumber() > 110 -> DangerRule.BED_TEMP
        else -> null
    }
}
