package app.orcaandroid.ui

import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.orcaandroid.R
import app.orcaandroid.container
import app.orcaandroid.core.PrintHistory
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
    val printHistory = PrintHistory(app)

    // The retired Layout lab's JSON moves into its own keys before anything reads the settings (a restored backup restarts the app, so it runs again).
    init { settings.migrateOldLayout() }

    val state = MutableStateFlow(
        UiState(
            workspace = SceneState(
                snapPrepare = settings.snapPrepare,
                pen = settings.pen,
                contactShadows = settings.contactShadows,
                realistic = settings.realistic,
                layerLines = settings.layerLines,
                edgeOutline = settings.edgeOutline,
                selectionStyle = settings.selectionStyle,
                perspective = settings.perspective,
                supersample = settings.supersample,
                pinchSpeed = settings.pinchSpeed,
                wheelSpeed = settings.wheelSpeed,
                plateHd = settings.plateHd,
                plateUnderAlpha = settings.plateUnderAlpha,
                lightAzimuth = settings.lightAzimuth,
                lightElevation = settings.lightElevation,
            ),
            design = DesignState(
                snapBuild = settings.snapBuild,
                buildOthers = settings.buildOthers,
                buildPrinter = settings.buildPrinter,
            ),
            slicing = SliceState(autoSlice = settings.autoSlice),
            device = DeviceState(
                hiddenFilamentVendors = settings.hiddenFilamentVendors,
                pinnedSettings = settings.pinnedSettings,
                hiddenBedTypes = settings.hiddenBedTypes,
                preferredBedType = settings.preferredBedType,
                matchNozzle = settings.matchNozzle,
                filamentTiles = settings.filamentTiles,
                preferMyFilaments = settings.preferMyFilaments,
                allowMixedTemps = settings.allowMixedTemps,
                dangerAllowed = settings.dangerAllowed,
                expertSettings = settings.expertSettings,
                installedNozzles = settings.installedNozzles,
                printHistory = printHistory.load(),
                projectFilaments = settings.projectFilaments,
            ),
            chrome = ChromeState(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                themeSpec = settings.themeSpec,
                doubleBackToQuit = settings.doubleBackToQuit,
                coachDismissed = settings.coachDismissed,
                interfaceSize = settings.interfaceSize,
                showPlates = settings.showPlates,
                panelTucked = settings.panelTucked,
                panelWidth = settings.panelWidth,
                sliceSpot = settings.sliceX to settings.sliceY,
                overlayAlpha = settings.overlayAlpha,
                plateScale = settings.plateScale,
                overlayScale = settings.overlayScale,
                sliceScale = settings.sliceScale,
                textScale = settings.textScale,
                fullscreenPortrait = settings.fullscreenPortrait,
                plates = settings.plates,
                platesOnHandle = settings.platesOnHandle,
                toolsLeft = settings.toolsLeft,
                toolsCollapsed = settings.toolsCollapsed,
                toolsRow = settings.toolsRowX to settings.toolsRowY,
                undoLimit = settings.undoLimit,
                autoSaveSeconds = settings.autoSaveSeconds,
                recents = settings.recents,
            ),
        )
    )

    val value: UiState get() = state.value

    // Corner resizing and rotating end with any selection change, so selecting the copy again later doesn't bring them back.
    fun update(transform: (UiState) -> UiState) = state.update { s ->
        transform(s).let { if (it.workspace.resizeFor != null && !it.workspace.resizing) it.copy(workspace = it.workspace.copy(resizeFor = null)) else it }
            .let { if (it.workspace.rotateFor != null && !it.workspace.rotating) it.copy(workspace = it.workspace.copy(rotateFor = null)) else it }
    }

    fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = app.resources.getQuantityString(id, count, *args)

    fun toast(message: String, action: MessageAction? = null) = update { it.copy(chrome = it.chrome.copy(message = message, messageAction = action)) }

    /**
     * Runs [block], showing [busy] (if any) and turning exceptions into an error dialog. With [cancel] the busy
     * dialog gets a Cancel button; the failure the cancelled operation throws then shows no error.
     */
    fun launch(busy: String? = null, cancel: (() -> Unit)? = null, block: suspend () -> Unit): Job = scope.launch {
        var cancelled = false
        val onCancel = cancel?.let { { cancelled = true; it() } }
        if (busy != null) update { it.copy(chrome = it.chrome.copy(busy = busy, busyCancel = onCancel)) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelled) return@launch
            android.util.Log.w("OrcaStore", "operation failed", e)
            val message = e.message ?: e.toString()
            // System file errors carry content:// URIs, cache paths and errno names; show the plain sentence instead.
            val unreadable = e is java.io.FileNotFoundException || e is SecurityException ||
                listOf("content://", "ENOENT", "EACCES", "open failed").any { it in message }
            if (e is PrinterFailure) {
                update { it.copy(chrome = it.chrome.copy(error = str(R.string.printer_no_answer), errorHint = str(R.string.printer_no_answer_hint), errorDetails = e.message)) }
                return@launch
            }
            update { it.copy(chrome = it.chrome.copy(error = when {
                e is app.orcaandroid.net.ArchiveTooLargeException -> str(R.string.archive_too_large)
                unreadable -> str(R.string.file_unreadable)
                else -> message
            })) }
        } catch (e: OutOfMemoryError) {
            // A big model or archive: the import / mesh work that ran out of heap is abandoned, the app stays up.
            if (cancelled) return@launch
            android.util.Log.w("OrcaStore", "operation ran out of memory", e)
            update { it.copy(chrome = it.chrome.copy(error = str(R.string.out_of_memory))) }
        } finally {
            if (busy != null) update { it.copy(chrome = it.chrome.copy(busy = null, busyCancel = null)) }
        }
    }

    /** Adopts a scene from the engine; any slice result is stale afterwards. */
    fun applyScene(scene: Scene, dirty: Boolean = true) = update { s ->
        val multi = s.workspace.multiSelection.filter { it.obj < scene.objects.size && it.instance < scene.objects[it.obj].instances.size }
        s.copy(
            workspace = s.workspace.copy(scene = scene, activePlate = s.workspace.activePlate.coerceIn(0, (scene.plates.size - 1).coerceAtLeast(0)), selection = s.workspace.selection?.takeIf { it.obj < scene.objects.size } ?: multi.singleOrNull(), multiSelection = multi.takeIf { it.size > 1 }.orEmpty(), projectDirty = s.workspace.projectDirty || dirty),
            slicing = s.slicing.copy(results = emptyMap()),
        )
    }

    /** Runs an engine operation that returns the new scene. */
    fun sceneOp(busy: String? = null, cancel: (() -> Unit)? = null, block: suspend () -> Scene) = launch(busy, cancel) { applyScene(block()) }
}
