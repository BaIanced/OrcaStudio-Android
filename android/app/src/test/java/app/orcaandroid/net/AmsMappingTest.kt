package app.orcaandroid.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-computed expectations for the pure halves of [AmsMapping]: the CIE76 colour
 * distance and the automatic tray assignment. (The `v0`/`v1` JSON encodings are not
 * covered: they need a real `org.json`, which plain JVM unit tests do not have.)
 * Distance values were cross-checked against a line-by-line mirror of the Lab
 * conversion before pushing: black <-> white is 100.0 (L 0 -> 100, a/b both 0),
 * red <-> green is 170.58.
 */
class AmsMappingTest {

    private fun tray(type: String, color: String, ams: String = "0", slot: String = "0") =
        PrinterTray(amsId = ams, slotId = slot, name = "", filamentId = "", type = type, color = color,
            colors = emptyList(), colorType = "")

    @Test fun colorDistanceIdenticalIsZero() {
        assertEquals(0.0, AmsMapping.colorDistance("#123456", "#123456"), 1e-9)
        // The leading '#' is optional.
        assertEquals(0.0, AmsMapping.colorDistance("FF0000", "#FF0000"), 1e-9)
    }

    @Test fun colorDistanceBlackWhite() {
        // L goes 0 -> 100 with a = b = 0 at both ends, so the distance is the L gap.
        assertEquals(100.0, AmsMapping.colorDistance("#000000", "#FFFFFF"), 0.01)
        assertEquals(100.0, AmsMapping.colorDistance("#FFFFFF", "#000000"), 0.01)
    }

    @Test fun colorDistanceRedGreen() {
        assertEquals(170.58, AmsMapping.colorDistance("#FF0000", "#00FF00"), 0.01)
        // A near-identical red is much closer than a different hue: 6.38.
        assertEquals(6.38, AmsMapping.colorDistance("#FF0000", "#EE0000"), 0.01)
    }

    @Test fun colorDistanceUnparseableIsLarge() {
        // Too short to hold RRGGBB, or non-hex digits: the "large" sentinel, both ways.
        assertEquals(999999.0, AmsMapping.colorDistance("#FFF", "#FFFFFF"), 0.0)
        assertEquals(999999.0, AmsMapping.colorDistance("#FFFFFF", "red"), 0.0)
        assertEquals(999999.0, AmsMapping.colorDistance("#GGGGGG", "#000000"), 0.0)
    }

    @Test fun autoPicksClosestColourPerType() {
        // Trays are listed green-then-red; the used filaments are red-then-green.
        // Exact matches (distance 0) win over tray order.
        val red = tray("PLA", "#FF0000", slot = "1")
        val green = tray("PLA", "#00FF00", slot = "0")
        val used = listOf(UsedFilament(0, "PLA", "#FF0000"), UsedFilament(1, "PLA", "#00FF00"))
        val m = AmsMapping.auto(used, listOf(green, red))
        assertEquals(red, m[0])
        assertEquals(green, m[1])
    }

    @Test fun autoOnlySameTypeQualifies() {
        // The PETG tray is the exact colour but the wrong type, so the PLA filament
        // takes the blue PLA tray; type matching is case-insensitive ("pla" tray).
        val petgRed = tray("PETG", "#FF0000", slot = "0")
        val plaBlue = tray("pla", "#0000FF", slot = "1")
        val m = AmsMapping.auto(listOf(UsedFilament(0, "PLA", "#FF0000")), listOf(petgRed, plaBlue))
        assertEquals(plaBlue, m[0])
    }

    @Test fun autoLeavesUnmatchedFilamentOut() {
        // No ABS tray at all: the filament is left for the user to pick (absent).
        val m = AmsMapping.auto(listOf(UsedFilament(3, "ABS", "#FF0000")), listOf(tray("PLA", "#FF0000")))
        assertTrue(m.isEmpty())
    }

    @Test fun autoTrayServesTwoFilamentsOnlyForExactColour() {
        // One red tray, one blue tray; two red filaments and no third tray. The red
        // tray may serve both red filaments because the second match is exact
        // (distance 0); the blue tray is never taken for a red filament while the
        // exact tray can be re-used.
        val red = tray("PLA", "#FF0000", slot = "0")
        val blue = tray("PLA", "#0000FF", slot = "1")
        val used = listOf(UsedFilament(0, "PLA", "#FF0000"), UsedFilament(1, "PLA", "#FF0000"))
        val m = AmsMapping.auto(used, listOf(red, blue))
        assertEquals(red, m[0])
        assertEquals(red, m[1])
    }
}
