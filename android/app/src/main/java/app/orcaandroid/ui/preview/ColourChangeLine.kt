package app.orcaandroid.ui.preview

import app.orcaandroid.core.GcodeToolChanges
import app.orcaandroid.core.SliceResult
import java.util.Locale
import kotlin.math.PI

/**
 * The pure half of the Preview "colour changes" line (Task 11): how the sliced plate's
 * tool-change count and prime-tower waste are computed and worded, kept out of the
 * composable so it can be unit-tested.
 *
 * The grams use one density for the whole tower: the plate's average, from the slice's
 * own filament length and weight ([densityOf]); the preset densities themselves are not
 * in Kotlin state. [DENSITY_G_PER_CM3] is only the fallback when the slice has no weight.
 */
object ColourChangeLine {

    /** Fallback filament density in g/cm3 (a PLA-like value), used because the preset density is not in Kotlin state. */
    const val DENSITY_G_PER_CM3 = 1.24

    /** The plate's average filament density in g/cm3: total grams over total volume (1.75 mm filament). */
    fun densityOf(r: SliceResult): Double {
        val cm3 = r.filamentMm * PI * 0.875 * 0.875 / 1000.0
        return if (cm3 > 0 && r.filamentGrams > 0) r.filamentGrams / cm3 else DENSITY_G_PER_CM3
    }

    /** Prime-tower volume ([mm3]) to grams at [densityGPerCm3] (1 cm3 = 1000 mm3). */
    fun primeTowerGrams(mm3: Double, densityGPerCm3: Double = DENSITY_G_PER_CM3): Double =
        mm3 / 1000.0 * densityGPerCm3

    /** The line is shown only once a parse finished and found at least one change. */
    fun visible(result: GcodeToolChanges.Result?): Boolean = result != null && result.changes > 0

    /** The value half of the line, e.g. `3 · prime tower ~10.5 g` (the label is the `colour_changes` string). */
    fun value(result: GcodeToolChanges.Result, densityGPerCm3: Double = DENSITY_G_PER_CM3): String =
        "%d · prime tower ~%.1f g".format(Locale.ROOT, result.changes, primeTowerGrams(result.primeTowerMm3, densityGPerCm3))

    /**
     * Parses G-code [lines] for tool changes. The sequence is consumed lazily, one line
     * at a time, so a caller streaming a 50+ MB file (e.g. `File.useLines`) never holds
     * it whole in memory.
     */
    fun parse(lines: Sequence<String>): GcodeToolChanges.Result = GcodeToolChanges.parse(lines)
}
