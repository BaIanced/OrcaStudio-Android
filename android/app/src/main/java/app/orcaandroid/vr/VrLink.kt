package app.orcaandroid.vr

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** What the 3D view of the 2D app shows, for the immersive view to draw without calling the engine. */
data class VrScene(
    /** Printable area outline in bed coordinates (mm), as x0,y0,x1,y1,... */
    val bed: List<Float>,
    val origins: List<Pair<Float, Float>>,
    val activePlate: Int,
    val meshFile: String,
    val meshVersion: Int,
    val paintFile: String,
    /** Hex colour per filament slot; null draws the default grey. */
    val filamentColors: List<String?>,
) {
    /** Centre of the active plate in plate mm. */
    fun plateCenter(): Pair<Float, Float> {
        val xs = bed.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bed.filterIndexed { i, _ -> i % 2 == 1 }
        val origin = origins.getOrElse(activePlate) { 0f to 0f }
        return origin.first + (xs.min() + xs.max()) / 2 to origin.second + (ys.min() + ys.max()) / 2
    }
}

/**
 * The 2D app and the immersive view (`src/quest`) share one engine, which holds one scene. Only the first to claim it may
 * load into it: a 2D view model, or VR on a cold start. VR opened over a 2D app reads [scene] and never calls the engine.
 */
class VrLink {
    enum class Claim { COLD, SHARED, BUSY }

    private object Vr

    /** Null, [Vr], or the 2D view model that holds the engine. */
    private val owner = MutableStateFlow<Any?>(null)
    private val views = MutableStateFlow(0)
    private val waiting2d = AtomicInteger()

    /** Published by the 2D app while it is ready; read once when VR opens, so the view never moves by itself. */
    @Volatile var scene: VrScene? = null

    /** A 2D app is waiting for the engine, so a cold-start VR should end. */
    val leaveRequested: Boolean get() = waiting2d.get() > 0

    /** An immersive view is on screen (started, not merely alive): nothing may write the session file. */
    val open: Boolean get() = views.value > 0

    fun viewOpened() { views.update { it + 1 } }

    fun viewClosed() { views.update { it - 1 } }

    /**
     * For a 2D app that just started the immersive view: returns when that has opened and closed again, so the 2D app can
     * hold still meanwhile and never edit a scene VR has. False if it didn't open within [startMs].
     */
    suspend fun awaitViewEnded(startMs: Long): Boolean {
        withTimeoutOrNull(startMs) { views.first { it > 0 } } ?: return false
        views.first { it == 0 }
        return true
    }

    /** [Claim.COLD]: load the project. [Claim.SHARED]: a 2D app holds the engine, read [scene]. [Claim.BUSY]: another VR holds it. */
    fun claimForVr(): Claim {
        if (owner.compareAndSet(null, Vr)) return Claim.COLD
        return if (owner.value === Vr) Claim.BUSY else Claim.SHARED
    }

    fun releaseVr() { owner.compareAndSet(Vr, null) }

    /** Takes the engine for the 2D app; while a cold-start VR holds it, asks that to end and waits, so nothing loads over it. */
    suspend fun claimFor2d(token: Any) {
        while (true) {
            val now = owner.value
            if (now === Vr) {
                waiting2d.incrementAndGet()
                // Also when cancelled: a view model cleared while it waits must not make later VR views end at once.
                try { owner.first { it !== Vr } } finally { waiting2d.decrementAndGet() }
            } else if (owner.compareAndSet(now, token)) return
        }
    }

    /** Only if [token] still holds it: a new view model can claim before the old one is cleared. */
    fun release2d(token: Any) {
        if (owner.compareAndSet(token, null)) scene = null
    }
}
