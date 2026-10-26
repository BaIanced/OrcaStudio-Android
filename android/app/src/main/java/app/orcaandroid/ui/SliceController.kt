package app.orcaandroid.ui

import app.orcaandroid.R
import app.orcaandroid.SliceProgress
import app.orcaandroid.core.SliceResult
import app.orcaandroid.service.SliceService
import app.orcaandroid.ui.preview.ColourChangeLine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** Slicing with progress, the preview state and viewing external G-code files. */
class SliceController(private val store: Store, private val presets: PresetController) {
    private val engine = store.engine
    private val progress = store.container.sliceProgress

    /** In-flight G-code tool-change parses per plate (Task 11); a plate's parse is cancelled when it is re-sliced. */
    private val parseJobs = mutableMapOf<Int, Job>()

    fun slice(plate: Int = store.value.activePlate) = store.launch {
        if (store.value.isSlicing) return@launch
        if (store.value.scene.objectsOn(plate).isEmpty()) {
            store.toast(store.str(R.string.nothing_to_slice))
            return@launch
        }
        presets.syncSelection()
        parseJobs.remove(plate)?.cancel()
        val preparing = store.str(R.string.preparing)
        store.update { it.copy(slice = SliceStatus.Running(plate, 0, preparing), sliceError = null) }
        progress.value = SliceProgress(0, preparing)
        runCatching { SliceService.start(store.app) }
        try {
            val result = engine.slice(plate) { p, t ->
                store.update { it.copy(slice = SliceStatus.Running(plate, p, t)) }
                progress.value = SliceProgress(p, t)
            }
            store.update {
                it.copy(results = it.results + (plate to result), previewPlate = plate, external = null, screen = Screen.PREVIEW,
                    preview = it.preview.showingAll(result.layers.lastIndex), upload = null)
            }
            parseToolChanges(plate, result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // On the 3D view (Notices), not in a dialog: a dialog window could open out of sight (Quest panel, user 2026-10-19).
            android.util.Log.w("OrcaSlice", "slice failed", e)
            store.update { it.copy(sliceError = plate to (e.message ?: e.toString())) }
        } finally {
            progress.value = null
            store.update { it.copy(slice = SliceStatus.Idle) }
        }
    }

    /** Slices every non-empty plate one after the other, stopping at the first error. */
    fun sliceAll() = store.launch {
        for (p in store.value.scene.plates.indices) {
            if (store.value.scene.objectsOn(p).isEmpty()) continue
            slice(p).join()
            if (store.value.error != null || store.value.sliceError != null) break
        }
    }

    fun cancelSlice() = engine.cancel()

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

    fun setPreviewPlate(plate: Int) = store.update { s ->
        s.copy(previewPlate = plate, external = null, preview = s.preview.showingAll(s.results[plate]?.layers?.lastIndex ?: 0))
    }

    fun updatePreview(transform: (PreviewUi) -> PreviewUi) = store.update { it.copy(preview = transform(it.preview)) }

    private fun PreviewUi.showingAll(lastLayer: Int) = copy(layerLow = 0, layerHigh = lastLayer, moveEnd = null)
}
