package app.orcaandroid.ui

import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.orcaandroid.R
import app.orcaandroid.container
import app.orcaandroid.core.Scene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The UI state and the helpers every controller needs: the app services, a coroutine launcher
 * that turns failures into the error dialog, and adopting a new scene from the engine.
 */
class Store(val app: Application, val scope: CoroutineScope) {
    val container = app.container
    val engine = container.engine
    val settings = container.settings
    val resources = container.resources

    val state = MutableStateFlow(
        UiState(
            themeMode = settings.themeMode,
            dynamicColor = settings.dynamicColor,
            themeSpec = settings.themeSpec,
            doubleBackToQuit = settings.doubleBackToQuit,
            pen = settings.pen,
            projectFilaments = settings.projectFilaments,
            allowMixedTemps = settings.allowMixedTemps,
            contactShadows = settings.contactShadows,
            realistic = settings.realistic,
            layerLines = settings.layerLines,
            edgeOutline = settings.edgeOutline,
            selectionStyle = settings.selectionStyle,
            perspective = settings.perspective,
            builderSplit = settings.builderSplit,
            snapPrepare = settings.snapPrepare,
            snapBuild = settings.snapBuild,
            buildOthers = settings.buildOthers,
            supersample = settings.supersample,
            lab = settings.layoutLab,
            undoLimit = settings.undoLimit,
            autoSaveSeconds = settings.autoSaveSeconds,
            hiddenFilamentVendors = settings.hiddenFilamentVendors,
            hiddenBedTypes = settings.hiddenBedTypes,
            preferredBedType = settings.preferredBedType,
            matchNozzle = settings.matchNozzle,
            preferMyFilaments = settings.preferMyFilaments,
            installedNozzles = settings.installedNozzles,
            recents = settings.recents,
        )
    )

    val value: UiState get() = state.value

    // Corner resizing and rotating end with any selection change, so selecting the copy again later doesn't bring them back.
    fun update(transform: (UiState) -> UiState) = state.update { s ->
        transform(s).let { if (it.resizeFor != null && !it.resizing) it.copy(resizeFor = null) else it }
            .let { if (it.rotateFor != null && !it.rotating) it.copy(rotateFor = null) else it }
    }

    fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = app.resources.getQuantityString(id, count, *args)

    fun toast(message: String, action: MessageAction? = null) = update { it.copy(message = message, messageAction = action) }

    /**
     * Runs [block], showing [busy] (if any) and turning exceptions into an error dialog. With [cancel] the busy
     * dialog gets a Cancel button; the failure the cancelled operation throws then shows no error.
     */
    fun launch(busy: String? = null, cancel: (() -> Unit)? = null, block: suspend () -> Unit): Job = scope.launch {
        var cancelled = false
        val onCancel = cancel?.let { { cancelled = true; it() } }
        if (busy != null) update { it.copy(busy = busy, busyCancel = onCancel) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelled) return@launch
            android.util.Log.w("OrcaStore", "operation failed", e)
            val unreadable = e is java.io.FileNotFoundException || e is SecurityException
            update { it.copy(error = if (unreadable) str(R.string.file_unreadable) else e.message ?: e.toString()) }
        } finally {
            if (busy != null) update { it.copy(busy = null, busyCancel = null) }
        }
    }

    /** Adopts a scene from the engine; any slice result is stale afterwards. */
    fun applyScene(scene: Scene, dirty: Boolean = true) = update { s ->
        val multi = s.multiSelection.filter { it.obj < scene.objects.size && it.instance < scene.objects[it.obj].instances.size }
        s.copy(
            scene = scene,
            selection = s.selection?.takeIf { it.obj < scene.objects.size } ?: multi.singleOrNull(),
            multiSelection = multi.takeIf { it.size > 1 }.orEmpty(),
            activePlate = s.activePlate.coerceIn(0, (scene.plates.size - 1).coerceAtLeast(0)),
            results = emptyMap(),
            projectDirty = s.projectDirty || dirty,
        )
    }

    /** Runs an engine operation that returns the new scene. */
    fun sceneOp(busy: String? = null, cancel: (() -> Unit)? = null, block: suspend () -> Scene) = launch(busy, cancel) { applyScene(block()) }
}
