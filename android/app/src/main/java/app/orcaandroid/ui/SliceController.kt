package app.orcaandroid.ui

import app.orcaandroid.R
import app.orcaandroid.SliceProgress
import app.orcaandroid.service.SliceService
import java.io.File

/** Slicing with progress, the preview state and viewing external G-code files. */
class SliceController(private val store: Store, private val presets: PresetController) {
    private val engine = store.engine
    private val progress = store.container.sliceProgress

    fun slice(plate: Int = store.value.activePlate) = store.launch {
        if (store.value.isSlicing) return@launch
        presets.syncSelection()
        val preparing = store.str(R.string.preparing)
        store.update { it.copy(slice = SliceStatus.Running(plate, 0, preparing)) }
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
            if (store.value.error != null) break
        }
    }

    fun cancelSlice() = engine.cancelSlicing()

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

    fun setPreviewPlate(plate: Int) = store.update { s ->
        s.copy(previewPlate = plate, external = null, preview = s.preview.showingAll(s.results[plate]?.layers?.lastIndex ?: 0))
    }

    fun updatePreview(transform: (PreviewUi) -> PreviewUi) = store.update { it.copy(preview = transform(it.preview)) }

    private fun PreviewUi.showingAll(lastLayer: Int) = copy(layerLow = 0, layerHigh = lastLayer, moveEnd = null)
}
