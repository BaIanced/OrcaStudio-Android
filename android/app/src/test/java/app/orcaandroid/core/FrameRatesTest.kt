package app.orcaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRatesTest {
    @Test fun distinctSortsAndMergesNearbyRates() =
        assertEquals(listOf(60f, 89.9f, 144f), FrameRates.distinct(listOf(144f, 60f, 90f, 89.9f, 60f)))

    @Test fun distinctDropsNonRates() = assertEquals(listOf(72f), FrameRates.distinct(listOf(0f, -1f, 72f)))

    @Test fun closestFindsANearbyRate() = assertEquals(89.9f, FrameRates.closest(90f, listOf(72f, 89.9f)))

    @Test fun closestRefusesARateNotOffered() = assertNull(FrameRates.closest(110f, listOf(72f, 120f)))

    @Test fun labelIsWholeHertz() = assertEquals("90", FrameRates.label(89.9f))

    @Test fun storedListRoundTrips() {
        val rates = listOf(72f, 89.9f, 207f)
        assertEquals(rates, FrameRates.decode(FrameRates.encode(rates)))
    }

    @Test fun nothingStoredIsAnEmptyList() = assertEquals(emptyList<Float>(), FrameRates.decode(""))

    @Test fun aRefusedRateIsKeptWithWhatTheSystemGave() {
        val refused = FrameRates.recordGrant(emptyMap(), 144f, 120f)
        assertEquals(120f, FrameRates.givenInstead(refused, 144f))
        assertNull(FrameRates.givenInstead(refused, 90f))
    }

    @Test fun aLaterGrantClearsTheLabel() {
        val refused = FrameRates.recordGrant(mapOf(144f to 120f), 144.1f, 143.9f)
        assertNull(FrameRates.givenInstead(refused, 144f))
    }

    @Test fun noPreferenceIsNeverRecorded() = assertEquals(emptyMap<Float, Float>(), FrameRates.recordGrant(emptyMap(), 0f, 120f))

    @Test fun refusedRatesRoundTrip() {
        val refused = mapOf(144f to 120f, 165f to 120f)
        assertEquals(refused, FrameRates.decodeGrants(FrameRates.encodeGrants(refused)))
        assertEquals(emptyMap<Float, Float>(), FrameRates.decodeGrants(""))
    }
}
