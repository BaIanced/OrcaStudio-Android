package app.orcaandroid.ui

import app.orcaandroid.R
import app.orcaandroid.SliceProgress
import app.orcaandroid.core.SliceResult
import app.orcaandroid.service.SliceService
import app.orcaandroid.ui.preview.ColourChangeLine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Slicing with progress, the preview state and viewing external G-code files. */
class SliceController(private val store: Store, private val presets: PresetController) {
    private val engine = store.engine
    private val progress = store.container.sliceProgress

    /** In-flight G-code tool-change parses per plate (Task 11); a plate's parse is cancelled when it is re-sliced. */
    private val parseJobs = mutableMapOf<Int, Job>()

    fun slice(plate: Int = store.value.activePlate) = store.launch {
        val running = store.value.slice as? SliceStatus.Running
        if (running != null && !running.background) return@launch
        if (store.value.scene.objectsOn(plate).isEmpty()) {
            store.toast(store.str(R.string.nothing_to_slice))
            return@launch
        }
        // A background slice of this plate is already under way: wait for it instead of starting over.
        if (running != null && running.plate == plate && promote()) {
            autoJob?.join()
            return@launch
        }
        stopAutoSlice()
        autoJob?.join()
        runSlice(plate, background = false)
    }

    /** Shows the active plate's current slice in the preview (the Slice button once a background slice is ready). */
    fun showPreview(plate: Int = store.value.activePlate) = store.update { s ->
        val result = s.results[plate] ?: return@update s
        s.copy(previewPlate = plate, external = null, screen = Screen.PREVIEW, preview = s.preview.showingAll(result.layers.lastIndex))
    }

    /** Turns the running background slice into one the user waits for: progress panel, notification, Preview at the end. */
    private fun promote(): Boolean {
        engine.keepSlice()
        var promoted = false
        store.update { s ->
            val r = s.slice as? SliceStatus.Running
            if (r == null || !r.background) s else { promoted = true; s.copy(slice = r.copy(background = false), sliceError = null) }
        }
        if (promoted) {
            userCancelled = false
            runCatching { SliceService.start(store.app) }
        }
        return promoted
    }

    private suspend fun runSlice(plate: Int, background: Boolean) {
        parseJobs.remove(plate)?.cancel()
        if (!background) userCancelled = false
        val preparing = store.str(R.string.preparing)
        store.update { it.copy(slice = SliceStatus.Running(plate, 0, preparing, background), sliceError = if (background) it.sliceError else null,
            fitIssue = if (background) it.fitIssue else null) }
        if (!background) {
            progress.value = SliceProgress(0, preparing)
            runCatching { SliceService.start(store.app) }
        }
        val key = editKey(store.value, plate)
        try {
            presets.syncSelection()
            val result = engine.slice(plate, background) { p, t ->
                // Keeps the flag a promote() may have cleared meanwhile.
                store.update { s -> s.copy(slice = SliceStatus.Running(plate, p, t, (s.slice as? SliceStatus.Running)?.background ?: background)) }
                if (store.value.isSlicing) progress.value = SliceProgress(p, t)
            }
            val waited = store.value.isSlicing
            val before = lastSlices[plate]?.takeIf { it.project == key.project }
            lastSlices[plate] = SliceSummary(key.project, result.printTimeSeconds, result.filamentGrams)
            val delta = before?.let { SliceDelta(plate, result.printTimeSeconds - it.seconds, result.filamentGrams - it.grams) }
            store.update {
                val shown = if (waited) it.copy(previewPlate = plate, external = null, screen = Screen.PREVIEW,
                    preview = it.preview.showingAll(result.layers.lastIndex), upload = null)
                // In the background the preview only follows when nobody is looking at another plate's.
                else if (it.screen != Screen.PREVIEW || it.previewPlate == plate) it.copy(previewPlate = plate,
                    preview = it.preview.showingAll(result.layers.lastIndex))
                else it
                shown.copy(results = it.results + (plate to result), sliceDelta = delta ?: it.sliceDelta?.takeIf { d -> d.plate != plate })
            }
            parseToolChanges(plate, result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!store.value.isSlicing) {
                // Pre-empted by an edit, or a background failure: silent. A failure is not retried until something changes;
                // tapping Slice shows it.
                if (e !is app.orcaandroid.core.Engine.SlicePreempted && e.message != CANCELLED) failedKey = key
                android.util.Log.i("OrcaSlice", "background slice ended: ${e.message}")
                return
            }
            // On the 3D view (Notices), not in a dialog: a dialog window could open out of sight (Quest panel, user 2026-10-19).
            // The failure a user cancel makes is not an error (user 2026-10-28): no badge.
            android.util.Log.w("OrcaSlice", "slice failed", e)
            if (!userCancelled) store.update { it.copy(sliceError = plate to (e.message ?: e.toString())) }
            // Any failure: say which object is too big and offer a turn / lay that fits. Not only on "inside the
            // print volume": an oversized object often fails first on the prime tower spot (WSA r524, 2026-11-04).
            if (!userCancelled) store.checkFit()
        } finally {
            progress.value = null
            store.update { it.copy(slice = SliceStatus.Idle) }
        }
    }

    // --- Background auto-slice (G4) ---------------------------------------------------------------

    /** What a slice depends on; [project] tells deltas of unrelated projects apart. */
    private data class EditKey(val plate: Int, val scene: app.orcaandroid.core.Scene, val printer: String?, val print: String?,
        val filaments: List<app.orcaandroid.core.FilamentSlot>, val overrides: Map<app.orcaandroid.core.PresetType, Map<String, String>>,
        val project: String?)

    private fun editKey(s: UiState, plate: Int) = EditKey(plate, s.scene, s.printer, s.print, s.filaments, s.overrides, s.projectName)

    private data class SliceSummary(val project: String?, val seconds: Double, val grams: Double)

    /** Each plate's last successful slice, for the delta badge. */
    private val lastSlices = mutableMapOf<Int, SliceSummary>()

    /** The edit state a background slice failed on; not retried until it changes. */
    @Volatile private var failedKey: EditKey? = null

    private var autoJob: Job? = null

    /**
     * Slices the active plate in the background [IDLE_MS] after the last change (at once on the Preview screen) while
     * its result is missing. Any engine call cancels the background slice first (Engine.preempt), so editing never
     * waits for it; the next pause starts it again. Off in Battery Saver. Runs for the view model's lifetime.
     */
    suspend fun autoSlice() {
        val power = store.app.getSystemService(android.os.PowerManager::class.java)
        store.state.map { s ->
            val plate = s.activePlate
            val wanted = s.autoSlice && s.phase == Phase.READY && s.results[plate] == null && s.scene.objectsOn(plate).isNotEmpty()
            Triple(if (wanted) editKey(s, plate) else null, s.slice == SliceStatus.Idle && s.busy == null, s.screen == Screen.PREVIEW)
        }.distinctUntilChanged().collectLatest { (key, idle, onPreview) ->
            if (key == null || !idle || key == failedKey) return@collectLatest
            if (!onPreview) delay(IDLE_MS)
            if (power?.isPowerSaveMode == true) return@collectLatest
            val s = store.value
            if (s.slice != SliceStatus.Idle || s.busy != null || editKey(s, s.activePlate) != key) return@collectLatest
            // Marked running at once, so a Slice tap from here on finds it; its own job, so a plate switch doesn't throw away
            // a slice that is still valid.
            store.update { it.copy(slice = SliceStatus.Running(key.plate, 0, store.str(R.string.preparing), background = true)) }
            autoJob = store.scope.launch { runSlice(key.plate, background = true) }
        }
    }

    /** Stops a running background slice (setting turned off, another plate sliced). */
    fun stopAutoSlice() {
        if ((store.value.slice as? SliceStatus.Running)?.background == true) engine.cancel()
    }

    private companion object {
        const val IDLE_MS = 1500L
        /** The native message of a cancelled slice (OrcaEngine.cpp). */
        const val CANCELLED = "Slicing cancelled"
    }

    /** Slices every non-empty plate one after the other, stopping at the first error. */
    fun sliceAll() = store.launch {
        for (p in store.value.scene.plates.indices) {
            if (store.value.scene.objectsOn(p).isEmpty()) continue
            slice(p).join()
            if (store.value.error != null || store.value.sliceError != null || userCancelled) break
        }
    }

    /** Set by [cancelSlice] while a slice runs (cleared when the next one starts), so the failure the cancel causes raises no error badge and Slice all stops. */
    @Volatile private var userCancelled = false

    fun cancelSlice() {
        if (store.value.isSlicing) userCancelled = true
        engine.cancel()
    }

    /** Shows an existing G-code file in the preview. */
    suspend fun viewGcodeFile(file: File) {
        store.update { it.copy(slice = SliceStatus.Running(-1, 0, store.str(R.string.reading_gcode))) }
        try {
            val result = engine.viewGcode(file.path) { p, t -> store.update { it.copy(slice = SliceStatus.Running(-1, p, t)) } }
            store.update { it.copy(external = result, screen = Screen.PREVIEW, preview = it.preview.showingAll(result.layers.lastIndex)) }
        } finally {
            store.update { it.copy(slice = SliceStatus.Idle) }
        }
    }

    fun closeExternal() = store.update { it.copy(external = null) }

    /**
     * Counts [result]'s tool changes off the main thread (Task 11): the G-code is streamed
     * line by line (`useLines`), never read whole — the files can be 50+ MB. The count is
     * attached to that plate's result; a re-slice replaces the result (discarding the old
     * count), and re-slicing the plate cancels its in-flight parse. Other plates' parses and
     * plate switches leave it running (each plate has its own G-code file).
     * A parse failure just leaves the result without a count (the Preview line stays hidden).
     */
    private fun parseToolChanges(plate: Int, result: SliceResult) {
        parseJobs.remove(plate)?.cancel()
        parseJobs[plate] = store.launch {
            val parsed = try {
                withContext(Dispatchers.IO) {
                    File(result.gcodeFile).bufferedReader().useLines { lines -> ColourChangeLine.parse(lines) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("OrcaSlice", "tool-change parse failed", e)
                null
            } ?: return@launch
            // Attach only if this slice is still the plate's result (not re-sliced meanwhile).
            store.update { s ->
                if (s.results[plate] === result) s.copy(results = s.results + (plate to result.copy(toolChanges = parsed))) else s
            }
        }
    }

    /** A plate chip in the Preview: shows the plate, and slices it first when it has objects but no result (user, 2026-11-04). */
    fun pickPreviewPlate(plate: Int) {
        setPreviewPlate(plate)
        val s = store.value
        if (!s.results.containsKey(plate) && s.scene.objectsOn(plate).isNotEmpty() && !s.isSlicing) slice(plate)
    }

    fun setPreviewPlate(plate: Int) = store.update { s ->
        s.copy(previewPlate = plate, external = null, preview = s.preview.showingAll(s.results[plate]?.layers?.lastIndex ?: 0))
    }

    fun updatePreview(transform: (PreviewUi) -> PreviewUi) = store.update { it.copy(preview = transform(it.preview)) }

    private fun PreviewUi.showingAll(lastLayer: Int) = copy(layerLow = 0, layerHigh = lastLayer, moveEnd = null)
}
