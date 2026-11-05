package app.orcaandroid.vr

import app.orcaandroid.core.Plate
import app.orcaandroid.core.PrinterSetup
import app.orcaandroid.core.Scene
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.ui.Phase
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.vrScene
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VrLinkTest {
    private val eps = 1e-5f

    @Test fun vrOnAFreeEngineLoadsThenLaterOnesAreBusy() {
        val link = VrLink()
        assertEquals(VrLink.Claim.COLD, link.claimForVr())
        assertEquals(VrLink.Claim.BUSY, link.claimForVr())
        link.releaseVr()
        assertEquals(VrLink.Claim.COLD, link.claimForVr())
    }

    @Test fun vrOverA2dAppShares() = runBlocking {
        val link = VrLink()
        link.claimFor2d(this@VrLinkTest)
        assertEquals(VrLink.Claim.SHARED, link.claimForVr())
        // Leaving a shared view doesn't free the 2D app's engine.
        link.releaseVr()
        assertEquals(VrLink.Claim.SHARED, link.claimForVr())
    }

    @Test fun twoDWaitsForAColdVrAndAsksItToEnd() = runBlocking {
        val link = VrLink()
        assertEquals(VrLink.Claim.COLD, link.claimForVr())
        assertFalse(link.leaveRequested)
        val claim = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { link.claimFor2d(this@VrLinkTest) }
        assertTrue(link.leaveRequested)
        assertFalse(claim.isCompleted)
        link.releaseVr()
        withTimeout(5_000) { claim.await() }
        assertFalse(link.leaveRequested)
        assertEquals(VrLink.Claim.SHARED, link.claimForVr())
    }

    @Test fun aCancelled2dWaitStopsAskingVrToEnd() = runBlocking {
        val link = VrLink()
        assertEquals(VrLink.Claim.COLD, link.claimForVr())
        val claim = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { link.claimFor2d(this@VrLinkTest) }
        assertTrue(link.leaveRequested)
        claim.cancelAndJoin()
        assertFalse(link.leaveRequested)
    }

    @Test fun onlyTheCurrent2dOwnerReleases() = runBlocking {
        val link = VrLink()
        val old = Any()
        val new = Any()
        link.claimFor2d(old)
        link.claimFor2d(new)
        link.scene = scene()
        link.release2d(old)
        assertEquals(VrLink.Claim.SHARED, link.claimForVr())
        assertEquals(scene(), link.scene)
        link.release2d(new)
        assertNull(link.scene)
        assertEquals(VrLink.Claim.COLD, link.claimForVr())
    }

    @Test fun openCountsOverlappingViews() {
        val link = VrLink()
        assertFalse(link.open)
        link.viewOpened()
        link.viewOpened()
        link.viewClosed()
        assertTrue(link.open)
        link.viewClosed()
        assertFalse(link.open)
    }

    @Test fun plateCenterIsTheActivePlatesBedCentre() {
        val (x, y) = scene(active = 1).plateCenter()
        assertEquals(350f, x, eps)
        assertEquals(50f, y, eps)
    }

    @Test fun plateCenterWithoutThatPlateUsesTheOrigin() {
        val (x, y) = scene(active = 7).plateCenter()
        assertEquals(100f, x, eps)
        assertEquals(50f, y, eps)
    }

    @Test fun snapshotIsNullUntilReady() {
        assertNull(uiState(Phase.LOADING).vrScene())
        assertNull(UiState(phase = Phase.READY).vrScene())
    }

    @Test fun snapshotHoldsWhat2dShows() {
        val s = uiState(Phase.READY).vrScene()!!
        assertEquals(listOf(0f, 0f, 200f, 100f), s.bed)
        assertEquals(listOf(0f to 0f, 250f to 0f), s.origins)
        assertEquals(1, s.activePlate)
        assertEquals("mesh.bin", s.meshFile)
        assertEquals(4, s.meshVersion)
        assertEquals("paint.bin", s.paintFile)
        // As the 2D view colours a slot without a colour (Viewport.kt).
        assertEquals(listOf("#112233", "#FF7F27"), s.filamentColors)
    }

    @Test fun equalStatesGiveEqualSnapshots() {
        assertEquals(uiState(Phase.READY).vrScene(), uiState(Phase.READY).vrScene())
    }

    private fun scene(active: Int = 0) = VrScene(listOf(0f, 0f, 200f, 100f), listOf(0f to 0f, 250f to 0f), active, "mesh.bin", 4, "paint.bin", listOf("#112233"))

    private fun plate(index: Int, x: Float) = Plate(index, x, 0f, "", 0, emptyList(), null)

    private fun uiState(phase: Phase) = UiState(
        phase = phase,
        setup = PrinterSetup(emptyList(), emptyList(), floatArrayOf(0f, 0f, 200f, 100f), 250f, "", "", emptyList(), ""),
        scene = Scene(meshFile = "mesh.bin", meshVersion = 4, paintFile = "paint.bin", plates = listOf(plate(0, 0f), plate(1, 250f))),
        activePlate = 1,
        filaments = listOf(FilamentSlot("a", "#112233"), FilamentSlot("b")),
    )
}
