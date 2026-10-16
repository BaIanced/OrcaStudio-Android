package app.orcaandroid.net

/**
 * Bambu model of a printer preset or serial number, to warn before sending a file sliced for one model to another.
 * Only models both sides identify are compared; anything unknown returns null (no warning).
 */
object PrinterModel {
    private val serialPrefixes = mapOf(
        "00M" to "X1C", "00W" to "X1", "03W" to "X1E", "01S" to "P1P", "01P" to "P1S",
        "030" to "A1 mini", "039" to "A1", "093" to "H2S", "094" to "H2D",
    )

    // Longest names first: "A1 mini" before "A1", "X1E"/"X1 Carbon" before "X1".
    private val presetNames = listOf(
        "a1 mini" to "A1 mini", "a1" to "A1", "p1s" to "P1S", "p1p" to "P1P", "x1e" to "X1E",
        "x1 carbon" to "X1C", "x1c" to "X1C", "x1" to "X1", "h2d" to "H2D", "h2s" to "H2S",
    )

    fun ofSerial(serial: String): String? = serialPrefixes[serial.trim().take(3).uppercase()]

    fun ofPreset(name: String): String? {
        val lower = name.lowercase()
        if (!lower.contains("bambu")) return null
        return presetNames.firstOrNull { (key, _) -> Regex("(^|[^a-z0-9])" + Regex.escape(key) + "([^a-z0-9]|$)").containsMatchIn(lower) }?.second
    }

    /** The two model names when [preset] and the printer with [serial] are known to differ, else null. */
    fun mismatch(preset: String?, serial: String): Pair<String, String>? {
        val sliced = ofPreset(preset ?: return null) ?: return null
        val connected = ofSerial(serial) ?: return null
        return if (sliced == connected) null else sliced to connected
    }
}
