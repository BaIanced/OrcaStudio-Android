package app.orcaandroid.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRatesTest {
    private val quest = floatArrayOf(72f, 80f, 90f, 96f, 100f, 120f, 144f, 180f, 200f, 207f)

    @Test fun exactRateIsFound() = assertEquals(144f, RefreshRates.match(144f, quest))

    @Test fun nearbyRateIsFound() = assertEquals(89.9f, RefreshRates.match(90f, floatArrayOf(72f, 89.9f)))

    @Test fun closestWins() = assertEquals(90.1f, RefreshRates.match(90f, floatArrayOf(89.7f, 90.1f)))

    @Test fun rateNotOfferedIsRefused() = assertNull(RefreshRates.match(110f, quest))

    @Test fun zeroMeansNoPreference() = assertEquals(0f, RefreshRates.match(0f, floatArrayOf()))

    @Test fun nothingOfferedRefusesARate() = assertNull(RefreshRates.match(90f, floatArrayOf()))

    @Test fun notANumberIsRefused() = assertNull(RefreshRates.match(Float.NaN, quest))
}
