package app.orcaandroid.ui.preview

import app.orcaandroid.core.GcodeToolChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the Preview colour-changes line (Task 11): the mm3 -> g conversion,
 * the hide-when-0 rule and a streamed parse of a hand-written fixture. Prime-tower
 * volumes mirror [GcodeToolChanges]' fixtures: 10 mm of 1.75 mm filament = 24.0528 mm3.
 * The composable and the device behaviour are UNVERIFIED (no toolchain / device here).
 */
class ColourChangeLineTest {

    private fun result(changes: Int, mm3: Double) = GcodeToolChanges.Result(changes, emptyList(), mm3)

    @Test fun primeTowerGramsConversion() {
        // 1000 mm3 = 1 cm3, so at the fallback density the grams equal the density.
        assertEquals(1.24, ColourChangeLine.primeTowerGrams(1000.0), 1e-9)
        assertEquals(0.0, ColourChangeLine.primeTowerGrams(0.0), 1e-9)
        assertEquals(2.40528, ColourChangeLine.primeTowerGrams(2405.28, densityGPerCm3 = 1.0), 1e-6)
    }

    @Test fun hiddenUntilParsedAndWhenNoChanges() {
        assertFalse(ColourChangeLine.visible(null))
        assertFalse(ColourChangeLine.visible(result(changes = 0, mm3 = 24.0528)))
        assertTrue(ColourChangeLine.visible(result(changes = 1, mm3 = 0.0)))
    }

    @Test fun valueWording() {
        // 8500 mm3 at 1.24 g/cm3 = 10.54 g, shown to one decimal.
        assertEquals("3 · prime tower ~10.5 g", ColourChangeLine.value(result(changes = 3, mm3 = 8500.0)))
        assertEquals("1 · prime tower ~0.0 g", ColourChangeLine.value(result(changes = 1, mm3 = 0.0)))
    }

    @Test fun streamParseOfFixture() {
        // A lazy sequence, as the controller's streamed file lines arrive: T0 loads,
        // T1 is the one change; inside the tower E goes 5 -> 15 = 10 mm = 24.0528 mm3.
        val lines = sequence {
            yield("T0")
            yield("; CHANGE_LAYER")
            yield("T1")
            yield("G92 E0")
            yield("G1 X5 Y5 E5")
            yield("; FEATURE: Prime tower")
            yield("G1 X6 Y6 E10")
            yield("G1 X7 Y7 E15")
        }
        val r = ColourChangeLine.parse(lines)
        assertEquals(1, r.changes)
        assertEquals(24.0528, r.primeTowerMm3, 1e-3)
        assertTrue(ColourChangeLine.visible(r))
    }
}
