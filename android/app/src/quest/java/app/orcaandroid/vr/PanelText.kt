package app.orcaandroid.vr

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** What [InfoPanel] says. Plain Kotlin, so JVM tests can run it. */
internal object PanelText {
    /** More than fits on the panel; only guards against pushing a huge file by mistake. */
    const val MAX_CHARS = 4000

    /** Shown until `vr_panel.txt` is pushed. A line starting with "# " is a heading. */
    val DEFAULT = """
        # Move the plate
        Aim the ray at the plate: it turns cyan.
        Hold the grip (middle finger) to grab: the ray turns orange.
        Move your hand to carry the plate.
        Stick up or down: farther or closer.
        Stick left or right: zoom.
        Click the stick: back to 1:1.
        Let go of the grip to drop it. The spot is saved.
    """.trimIndent()

    /** The live lines under the instructions, from [XrShim.nativeInput]'s array. Grip and stick read as off without a position. */
    fun status(hands: FloatArray, holder: Int, x: Float, y: Float, z: Float, scale: Float): String {
        val lines = ArrayList<String>(XrShim.HANDS + 1)
        for (hand in 0 until XrShim.HANDS) {
            val o = hand * XrShim.FLOATS_PER_HAND
            val name = if (hand == 0) "Left" else "Right"
            lines += if (hands[o + XrShim.HAND_ACTIVE] < 0.5f) {
                "$name: no position (asleep or out of the cameras' view)"
            } else {
                val grip = if (hands[o + XrShim.HAND_GRIP] > 0.5f) "grip down" else "grip up"
                "$name: tracked, $grip, stick ${oneDecimal(hands[o + XrShim.HAND_STICK_X])} ${oneDecimal(hands[o + XrShim.HAND_STICK_Y])}"
            }
        }
        val distance = sqrt(x * x + y * y + z * z)
        val held = when (holder) {
            0 -> "held by left, "
            1 -> "held by right, "
            else -> ""
        }
        lines += "Plate: $held${"%.2f".format(Locale.ROOT, distance)} m away, zoom ${"%.2f".format(Locale.ROOT, scale)}"
        return lines.joinToString("\n")
    }

    /** Stick noise around zero must not change the text, or the panel redraws for nothing, and "-0.0" reads as a fault. */
    private fun oneDecimal(value: Float) = "%.1f".format(Locale.ROOT, if (abs(value) < 0.05f) 0f else value)
}
