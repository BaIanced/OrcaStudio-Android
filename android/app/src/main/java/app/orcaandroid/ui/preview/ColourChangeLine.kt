package app.orcaandroid.ui.preview

import app.orcaandroid.core.GcodeToolChanges
import java.util.Locale

/**
 * The pure half of the Preview "colour changes" line (Task 11): how the sliced plate's
 * tool-change count and prime-tower waste are computed and worded, kept out of the
 * composable so it can be unit-tested.
 *
 * The grams use a single density for the whole tower: the filament presets' densities
 * are not available in Kotlin state (a [app.orcaandroid.core.FilamentSlot] carries only
 * the user's overrides, not the preset's `filament_density`), so the fallback density
 * below is used and named here, per the task.
 */
object ColourChangeLine {

    /** Fallback filament density in g/cm3 (a PLA-like value), used because the preset density is not in Kotlin state. */
    const val DENSITY_G_PER_CM3 = 1.24

    /** Prime-tower volume ([mm3]) to grams at [densityGPerCm3] (1 cm3 = 1000 mm3). */
    fun primeTowerGrams(mm3: Double, densityGPerCm3: Double = DENSITY_G_PER_CM3): Double =
        mm3 / 1000.0 * densityGPerCm3

    /** The line is shown only once a parse finished and found at least one change. */
    fun visible(result: GcodeToolChanges.Result?): Boolean = result != null && result.changes > 0

    /** The value half of the line, e.g. `3 · prime tower ~10.5 g` (the label is the `colour_changes` string). */
    fun value(result: GcodeToolChanges.Result): String =
        "%d · prime tower ~%.1f g".format(Locale.ROOT, result.changes, primeTowerGrams(result.primeTowerMm3))

    /**
     * Parses G-code [lines] for tool changes. The sequence is consumed lazily, one line
     * at a time, so a caller streaming a 50+ MB file (e.g. `File.useLines`) never holds
     * it whole in memory.
     */
    fun parse(lines: Sequence<String>): GcodeToolChanges.Result = GcodeToolChanges.parse(lines)
}
