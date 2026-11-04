package app.orcaandroid.ui.settings

import app.orcaandroid.core.PresetType
import app.orcaandroid.ui.Screen

/** Where a notice's "Fix" link leads: an option in the settings editor, or another screen. */
sealed interface NoticeFix {
    data class Option(val type: PresetType, val key: String) : NoticeFix
    data class Go(val screen: Screen) : NoticeFix
    /** Turns or lays the object so it fits the printer ([app.orcaandroid.core.FitIssue]). */
    data class Fit(val issue: app.orcaandroid.core.FitIssue) : NoticeFix
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

/** A setting change that can damage the printer or a print: confirmed once, or allowed for good ("Always"). */
enum class DangerRule { NOZZLE_TEMP, BED_TEMP, PRINTER_GEOMETRY }

private val GEOMETRY_KEYS = setOf("nozzle_diameter", "z_offset", "extruder_offset")

/**
 * Printer settings fixed by the hardware of a vendor printer (user 2026-11-01): hidden when editing a vendor
 * preset or a user preset inheriting one, unless Expert settings is on. Scratch-built printers show them.
 */
private val VENDOR_FIXED_PRINTER_KEYS = setOf(
    "printable_area", "printable_height", "extruder_printable_area", "extruder_printable_height", "bed_exclude_area", "extruder_offset", "printer_structure", "printer_technology",
    "gcode_flavor", "auxiliary_fan", "support_chamber_temp_control", "support_air_filtration",
)

fun isVendorFixed(type: PresetType, key: String) = type == PresetType.PRINTER && key in VENDOR_FIXED_PRINTER_KEYS

/**
 * Whether an option with [mode] (0 simple, 1 advanced, 2 expert, 3 develop) shows outside Expert settings.
 * [vendorPrinter]: the edited printer preset is (or inherits) a vendor one.
 */
fun shownWithoutExpert(type: PresetType, key: String, mode: Int, vendorPrinter: Boolean) =
    mode <= 1 && !(vendorPrinter && isVendorFixed(type, key))

/** The rule [value] for [key] breaks, or null. Temperatures look at every number in a vector value. */
fun dangerRule(type: PresetType, key: String, value: String): DangerRule? {
    fun maxNumber() = value.split(',', ';').mapNotNull { it.trim().toDoubleOrNull() }.maxOrNull() ?: 0.0
    return when {
        type == PresetType.PRINTER && key in GEOMETRY_KEYS -> DangerRule.PRINTER_GEOMETRY
        key.startsWith("nozzle_temperature") && maxNumber() > 300 -> DangerRule.NOZZLE_TEMP
        key.contains("plate_temp") && maxNumber() > 110 -> DangerRule.BED_TEMP
        else -> null
    }
}
