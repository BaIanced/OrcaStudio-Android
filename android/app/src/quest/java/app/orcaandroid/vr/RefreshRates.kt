package app.orcaandroid.vr

import app.orcaandroid.core.FrameRates

/** Picks which of the runtime's refresh rates a requested number means. */
internal object RefreshRates {
    /** The offered rate closest to [wanted], null if none is close. 0 means no preference and is always allowed. */
    fun match(wanted: Float, offered: FloatArray): Float? =
        if (wanted == 0f) 0f else FrameRates.closest(wanted, offered.toList())
}
