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

    fun same(a: Float, b: Float): Boolean = abs(a - b) <= TOLERANCE_HZ

    fun label(rate: Float): String = rate.roundToInt().toString()

    fun encode(rates: List<Float>): String = rates.joinToString(",")

    fun decode(text: String): List<Float> = text.split(",").mapNotNull { it.trim().toFloatOrNull() }

    /**
     * What the system gave when a rate was asked for, kept only where it gave something else: listed rates the
     * system won't grant are labelled in the picker, not hidden (user, 2026-11-09). A later grant clears the entry.
     */
    fun recordGrant(refused: Map<Float, Float>, requested: Float, actual: Float): Map<Float, Float> {
        if (requested <= 0f || actual <= 0f) return refused
        val rest = refused.filterKeys { !same(it, requested) }
        return if (same(requested, actual)) rest else rest + (requested to actual)
    }

    /** The rate the system gave instead of [rate] last time, null if it was granted or never tried. */
    fun givenInstead(refused: Map<Float, Float>, rate: Float): Float? =
        refused.entries.firstOrNull { same(it.key, rate) }?.value

    fun encodeGrants(refused: Map<Float, Float>): String = refused.entries.joinToString(",") { "${it.key}:${it.value}" }

    fun decodeGrants(text: String): Map<Float, Float> = text.split(",").mapNotNull { pair ->
        val parts = pair.split(":")
        val k = parts.getOrNull(0)?.trim()?.toFloatOrNull()
        val v = parts.getOrNull(1)?.trim()?.toFloatOrNull()
        if (parts.size == 2 && k != null && v != null) k to v else null
    }.toMap()
}
