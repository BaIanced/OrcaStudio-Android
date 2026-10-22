package app.orcaandroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hand-computed expectations for [ColourChangeEstimate].
 * Shared 2-filament matrix (row-major from -> to, mm3): [[0, 100], [200, 0]];
 * densities [1.0, 2.0] g/cm3, so a 0 -> 1 switch at multiplier 1 purges 100 mm3 = 0.20 g
 * (grams use the density of the filament flushed *to*: 100 / 1000 * 2.0),
 * and a 1 -> 0 switch purges 200 mm3 = 0.20 g (200 / 1000 * 1.0).
 */
class ColourChangeEstimateTest {
    private val matrix2 = arrayOf(doubleArrayOf(0.0, 100.0), doubleArrayOf(200.0, 0.0))
    private val densities2 = listOf(1.0, 2.0)

    private fun assertEstimate(
        e: ColourChangeEstimate.Estimate,
        changes: Int,
        mm3: Double,
        grams: Double,
        perFilament: DoubleArray,
    ) {
        assertEquals(changes, e.changes)
        assertEquals(mm3, e.flushMm3, 1e-9)
        assertEquals(grams, e.flushGrams, 1e-9)
        assertArrayEquals(perFilament, e.perFilamentGrams, 1e-9)
    }

    @Test fun singleColourNoChanges() {
        // One filament throughout: loaded once, never switched. 0 changes, 0 mm3, 0 g.
        val e = ColourChangeEstimate.estimate(listOf(listOf(0), listOf(0), listOf(0)), matrix2, 1.0, densities2, null)
        assertEstimate(e, 0, 0.0, 0.0, doubleArrayOf(0.0, 0.0))
    }

    @Test fun twoColoursAlternatingEveryLayer() {
        // Load 0 free; then 0->1 (100), 1->0 (200), 0->1 (100): 3 changes, 400 mm3.
        // Grams: to 1: 0.20 + 0.20 = 0.40; to 0: 0.20. Total 0.60 g.
        val e = ColourChangeEstimate.estimate(listOf(listOf(0), listOf(1), listOf(0), listOf(1)), matrix2, 1.0, densities2, null)
        assertEstimate(e, 3, 400.0, 0.60, doubleArrayOf(0.20, 0.40))
    }

    @Test fun oneLayerThreeColoursGreedyOrder() {
        // Matrix [[0,50,10],[40,0,30],[20,60,0]], all densities 1.0. Start filament 0 is loaded
        // and in the layer, so it prints first. Remaining {1,2}: 0->2 costs 10, 0->1 costs 50,
        // so greedy picks 2 (10 mm3 = 0.01 g to filament 2), then 2->1 costs 60 (0.06 g to 1).
        // The other order (0->1->2) would cost 50 + 30 = 80 mm3, so 70 mm3 proves the greedy pick.
        val matrix3 = arrayOf(
            doubleArrayOf(0.0, 50.0, 10.0),
            doubleArrayOf(40.0, 0.0, 30.0),
            doubleArrayOf(20.0, 60.0, 0.0),
        )
        val e = ColourChangeEstimate.estimate(listOf(listOf(2, 0, 1)), matrix3, 1.0, listOf(1.0, 1.0, 1.0), 0)
        assertEstimate(e, 2, 70.0, 0.07, doubleArrayOf(0.0, 0.06, 0.01))
    }

    @Test fun previousFilamentReusedAtLayerStart() {
        // Layer 0: load 0 free, switch 0->1 (100 mm3, 0.20 g to 1); filament 1 stays loaded.
        // Layer 1 uses {1, 0}: the loaded 1 prints first with no switch, then 1->0 (200 mm3, 0.20 g to 0).
        // 2 changes, 300 mm3, 0.40 g. Counting the layer-1 start as a switch would give 3 changes.
        val e = ColourChangeEstimate.estimate(listOf(listOf(0, 1), listOf(1, 0)), matrix2, 1.0, densities2, null)
        assertEstimate(e, 2, 300.0, 0.40, doubleArrayOf(0.20, 0.20))
    }

    @Test fun emptyLayersKeepLoadedFilament() {
        // Load 0; the empty layer changes nothing, so the switch on layer 2 is 0->1 (100 mm3, 0.20 g).
        val e = ColourChangeEstimate.estimate(listOf(listOf(0), emptyList(), listOf(1)), matrix2, 1.0, densities2, null)
        assertEstimate(e, 1, 100.0, 0.20, doubleArrayOf(0.0, 0.20))
    }

    @Test fun startFilamentNullFirstLoadIsFree() {
        // Nothing loaded at the start: loading filament 1 for the first layer is not a switch.
        val e = ColourChangeEstimate.estimate(listOf(listOf(1)), matrix2, 1.0, densities2, null)
        assertEstimate(e, 0, 0.0, 0.0, doubleArrayOf(0.0, 0.0))
    }

    @Test fun startFilamentSetCountsFirstSwitch() {
        // Filament 0 loaded before the print, first layer is filament 1: 0->1 counts (100 mm3, 0.20 g).
        val e = ColourChangeEstimate.estimate(listOf(listOf(1)), matrix2, 1.0, densities2, 0)
        assertEstimate(e, 1, 100.0, 0.20, doubleArrayOf(0.0, 0.20))
        // Same filament already loaded: no switch.
        val same = ColourChangeEstimate.estimate(listOf(listOf(0)), matrix2, 1.0, densities2, 0)
        assertEstimate(same, 0, 0.0, 0.0, doubleArrayOf(0.0, 0.0))
    }

    @Test fun multiplierZeroPurgesNothing() {
        // The switch still happens (1 change) but 100 * 0 = 0 mm3 and 0 g.
        val e = ColourChangeEstimate.estimate(listOf(listOf(0), listOf(1)), matrix2, 0.0, densities2, null)
        assertEstimate(e, 1, 0.0, 0.0, doubleArrayOf(0.0, 0.0))
    }

    @Test fun multiplierOnePointFiveScalesPurge() {
        // 0->1 at multiplier 1.5: 100 * 1.5 = 150 mm3; grams 150 / 1000 * 2.0 = 0.30 g to filament 1.
        val e = ColourChangeEstimate.estimate(listOf(listOf(0), listOf(1)), matrix2, 1.5, densities2, null)
        assertEstimate(e, 1, 150.0, 0.30, doubleArrayOf(0.0, 0.30))
    }

    @Test fun asymmetricMatrixDirectionsDiffer() {
        // 0->1 is 100 mm3 but 1->0 is 200 mm3 (the matrix is not symmetric):
        // total 300 mm3; grams to 1: 100/1000*2.0 = 0.20, to 0: 200/1000*1.0 = 0.20.
        val e = ColourChangeEstimate.estimate(listOf(listOf(0), listOf(1), listOf(0)), matrix2, 1.0, densities2, null)
        assertEstimate(e, 2, 300.0, 0.40, doubleArrayOf(0.20, 0.20))
    }

    @Test fun malformedMatrixReturnsNull() {
        // 2 filaments need 4 values; 3 is too short. n < 1 is malformed too, and an empty
        // value list cannot fill even a 1 x 1 matrix.
        assertNull(ColourChangeEstimate.parseFlushMatrix(listOf(0.0, 10.0, 20.0), 2))
        assertNull(ColourChangeEstimate.parseFlushMatrix(listOf(0.0, 10.0, 20.0, 0.0), 0))
        assertNull(ColourChangeEstimate.parseFlushMatrix(emptyList(), 1))
    }

    @Test fun parseUsesFirstBlockWhenLonger() {
        // Single-nozzle parse of [0, 100, 200, 0] is rows [[0, 100], [200, 0]].
        val m = ColourChangeEstimate.parseFlushMatrix(listOf(0.0, 100.0, 200.0, 0.0), 2)!!
        assertArrayEquals(doubleArrayOf(0.0, 100.0), m[0], 1e-9)
        assertArrayEquals(doubleArrayOf(200.0, 0.0), m[1], 1e-9)
        // Multi-nozzle values concatenate one block per nozzle: the second block
        // ([0, 999, 888, 0]) must be ignored, only the first n x n block is used.
        val multi = ColourChangeEstimate.parseFlushMatrix(
            listOf(0.0, 100.0, 200.0, 0.0, 0.0, 999.0, 888.0, 0.0), 2,
        )!!
        assertEquals(100.0, multi[0][1], 1e-9)
        assertEquals(200.0, multi[1][0], 1e-9)
    }

    @Test fun outOfRangeIdsIgnored() {
        // Layer 0 holds only filament 0 in range (7 and -1 are ignored) and loads it free;
        // layer 1 holds only out-of-range ids, so it behaves as empty and 0 stays loaded;
        // layer 2 then switches 0->1 (100 mm3, 0.20 g). No throw, 1 change.
        val e = ColourChangeEstimate.estimate(listOf(listOf(0, 7, -1), listOf(9), listOf(1)), matrix2, 1.0, densities2, null)
        assertEstimate(e, 1, 100.0, 0.20, doubleArrayOf(0.0, 0.20))
    }
}
