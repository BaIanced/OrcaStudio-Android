package app.orcaandroid.vr

import kotlin.math.abs

/** Picks which of the runtime's refresh rates a requested number means. */
internal object RefreshRates {
    /** Listed rates need not be whole numbers, so "90" must still find 89.9. */
    private const val TOLERANCE_HZ = 0.5f

    /** The offered rate closest to [wanted], null if none is close. 0 means no preference and is always allowed. */
    fun match(wanted: Float, offered: FloatArray): Float? {
        if (wanted == 0f) return 0f
        return offered.filter { abs(it - wanted) <= TOLERANCE_HZ }.minByOrNull { abs(it - wanted) }
    }
}
