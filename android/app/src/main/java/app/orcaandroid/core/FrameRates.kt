package app.orcaandroid.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** The frame rates offered in Settings. 0 Hz means no preference: the device decides. */
object FrameRates {
    /** Listed rates need not be whole numbers, so 90 must still find 89.9, and two modes that close are one choice. */
    private const val TOLERANCE_HZ = 0.5f

    /** Every rate once, slowest first. */
    fun distinct(rates: List<Float>): List<Float> {
        val out = ArrayList<Float>()
        for (rate in rates.filter { it > 0f }.sorted()) {
            if (out.none { abs(it - rate) <= TOLERANCE_HZ }) out.add(rate)
        }
        return out
    }

    /** The offered rate closest to [wanted], null if none is close. */
    fun closest(wanted: Float, offered: List<Float>): Float? =
        offered.filter { abs(it - wanted) <= TOLERANCE_HZ }.minByOrNull { abs(it - wanted) }

    fun label(rate: Float): String = rate.roundToInt().toString()

    fun encode(rates: List<Float>): String = rates.joinToString(",")

    fun decode(text: String): List<Float> = text.split(",").mapNotNull { it.trim().toFloatOrNull() }
}
