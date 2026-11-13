package app.orcaandroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.orcaandroid.CrashReport
import app.orcaandroid.ai.McpServer
import app.orcaandroid.ai.McpTools
import app.orcaandroid.core.AI_TOOLS_PORT
import app.orcaandroid.core.ThemeMode
import app.orcaandroid.core.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point of the UI to the app's state and actions. The native engine keeps the scene and
 * presets; [state] mirrors what the UI needs. Actions are grouped by topic into controllers that
 * share one [Store].
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app, viewModelScope)

    val state: StateFlow<UiState> = store.state.asStateFlow()

    val device = DeviceController(store)
    val presets = PresetController(store, device)
    val scene = SceneController(store, presets)
    val slicing = SliceController(store, presets)
    val files = FileController(store, presets, scene, slicing)
    val scad = ScadController(store)
    val shapes = ShapeController(store, scad)

    /** MCP server for AI clients (Settings > AI tools); runs while this view model lives and the switch is on. */
    private val aiTools = McpTools(store, scad, shapes, presets, slicing)
    private val mcp = McpServer(AI_TOOLS_PORT, { store.settings.aiToken }, aiTools)

    /** Desktop translations of option labels for the device language. */
    var translator: Translator = Translator.NONE
        private set

    init {
        device.files = files
        files.scad = scad
        viewModelScope.launch {
            runCatching { start() }
                .onFailure { e -> store.update { it.copy(chrome = it.chrome.copy(phase = Phase.SETUP, error = e.message ?: e.toString())) } }
        }
        viewModelScope.launch { autoSave() }
        viewModelScope.launch { slicing.autoSlice() }
        viewModelScope.launch { awaitReady(); if (store.settings.aiTools) setAiTools(true) }
        viewModelScope.launch {
            store.container.printerStatus.collect { s ->
                store.update { it.copy(device = it.device.copy(printerStatus = s)) }
                s?.nozzleDiameter?.let(presets::recordNozzle)
            }
        }
        // The Build workspace's object, for the engine's per-volume selection ids.
        viewModelScope.launch {
            store.state.map { it.buildObject }.distinctUntilChanged().collect { scad.showBuild(it) }
        }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { runCatching { CrashReport.pending(store.app) }.getOrNull() }
            if (report != null) store.update { it.copy(chrome = it.chrome.copy(crashReport = report)) }
        }
    }

    fun dismissCrashReport() {
        CrashReport.clear(store.app)
        store.update { it.copy(chrome = it.chrome.copy(crashReport = null)) }
    }

    /** Unpacks the resources, starts the engine and loads the printers chosen before (or asks for them). */
    private suspend fun start() {
        val resources = store.resources
        translator = withContext(Dispatchers.IO) { Translator.load(store.app) }
        resources.prepare()
        store.engine.init(resources.resourcesDir, resources.dataDir)
        runCatching { store.engine.setUndoLimit(store.settings.undoLimit) }
        val vendors = withContext(Dispatchers.IO) { resources.availableVendors() }
        val selected = store.settings.selectedPrinters
        store.update { it.copy(device = it.device.copy(vendors = vendors, selectedPrinters = selected)) }
        val installed = resources.installedVendorIds()
        if (selected.isEmpty() || installed.none { id -> vendors.any { v -> v.id == id && !v.required } }) {
            store.update { it.copy(chrome = it.chrome.copy(phase = Phase.SETUP)) }
        } else {
            presets.reloadPresets(emptySet())
            runCatching { files.restoreSession() }.onFailure { android.util.Log.w("Orca", "session not restored", it) }
            store.update { it.copy(chrome = it.chrome.copy(phase = Phase.READY)) }
            startPlugins()
            presets.autoCheckProfileUpdates()
        }
    }

    /** Starts the Orca plugin runtime when the signed-in Orca Cloud user has plugins installed. */
    private fun startPlugins() = store.container.appScope.launch {
        val user = withContext(Dispatchers.IO) { runCatching { app.orcaandroid.net.OrcaCloud.user(store.app) }.getOrNull() } ?: return@launch
        val dir = java.io.File(store.resources.dataDir, "orca_plugins/_subscribed/${user.id}")
        if (dir.list().isNullOrEmpty()) return@launch
        app.orcaandroid.plugins.Plugins.start(store.app, store.resources.dataDir, user.id)
    }

    /** Waits until startup has finished (intents that arrive while the app starts). */
    suspend fun awaitReady() = state.first { it.chrome.phase == Phase.READY }

    // --- App-wide UI state ---------------------------------------------------------------------------

    fun dismissCoach() {
        store.settings.coachDismissed = true
        store.update { it.copy(chrome = it.chrome.copy(coachDismissed = true)) }
    }

    fun setScreen(screen: Screen) = store.update { it.copy(workspace = it.workspace.copy(tool = toolAfterScreenChange(it.chrome.screen, screen, it.workspace.tool)), chrome = it.chrome.copy(screen = screen)) }

    /** Shows [url] in the Models tab's browser. */
    fun openInModels(url: String) = store.update { it.copy(workspace = it.workspace.copy(tool = Tool.None), chrome = it.chrome.copy(modelsUrl = url, screen = Screen.MODELS)) }

    fun modelsUrlOpened() = store.update { it.copy(chrome = it.chrome.copy(modelsUrl = null)) }

    fun setThemeMode(mode: ThemeMode) {
        store.settings.themeMode = mode
        store.update { it.copy(chrome = it.chrome.copy(themeMode = mode)) }
    }

    fun setDynamicColor(on: Boolean) {
        store.settings.dynamicColor = on
        store.update { it.copy(chrome = it.chrome.copy(dynamicColor = on)) }
    }

    /** New colours; [persist] = false while a slider is still moving (saved when it's let go). */
    fun setThemeSpec(spec: app.orcaandroid.core.ThemeSpec, persist: Boolean = true) {
        if (persist) store.settings.themeSpec = spec
        store.update { it.copy(chrome = it.chrome.copy(themeSpec = spec)) }
    }

    /** Picks a colour preset; wallpaper colours would hide it, so they go off. */
    fun setThemePreset(key: String) {
        if (state.value.chrome.dynamicColor) setDynamicColor(false)
        setThemeSpec(state.value.chrome.themeSpec.copy(preset = key))
    }

    /** Design mode: the colour panel floats over the app; opened from More, it starts on Prepare. */
    fun setDesignMode(on: Boolean) = store.update {
        it.copy(chrome = it.chrome.copy(designMode = on, screen = if (on && it.chrome.screen == Screen.MORE) Screen.PREPARE else it.chrome.screen))
    }

    fun setUndoLimit(limit: Int) {
        store.settings.undoLimit = limit
        store.update { it.copy(chrome = it.chrome.copy(undoLimit = limit)) }
        store.sceneOp { store.engine.setUndoLimit(limit) }
    }

    fun setInterfaceSize(size: Float) {
        val snapped = app.orcaandroid.core.ViewDefaults.snapInterfaceSize(size)
        store.settings.interfaceSize = snapped
        store.update { it.copy(chrome = it.chrome.copy(interfaceSize = snapped)) }
    }

    fun setShowPlates(on: Boolean) {
        store.settings.showPlates = on
        store.update { it.copy(chrome = it.chrome.copy(showPlates = on)) }
    }

    fun setPanelTucked(on: Boolean) {
        store.settings.panelTucked = on
        store.update { it.copy(chrome = it.chrome.copy(panelTucked = on)) }
    }

    /** The settings panel's width in dp (0 = default). */
    fun setPanelWidth(dp: Float) {
        val w = dp.coerceAtLeast(0f)
        store.settings.panelWidth = w
        store.update { it.copy(chrome = it.chrome.copy(panelWidth = w)) }
    }

    /** The floating Slice button's spot as fractions of the free room (-1 = bottom right). */
    fun setSliceSpot(x: Float, y: Float) {
        store.settings.sliceX = x
        store.settings.sliceY = y
        store.update { it.copy(chrome = it.chrome.copy(sliceSpot = x.coerceAtMost(1f) to y.coerceAtMost(1f))) }
    }

    fun setPinchSpeed(speed: Float) {
        val v = speed.coerceIn(app.orcaandroid.core.ViewDefaults.PINCH_SPEEDS)
        store.settings.pinchSpeed = v
        store.update { it.copy(workspace = it.workspace.copy(pinchSpeed = v)) }
    }

    fun setWheelSpeed(speed: Float) {
        val v = speed.coerceIn(app.orcaandroid.core.ViewDefaults.WHEEL_SPEEDS)
        store.settings.wheelSpeed = v
        store.update { it.copy(workspace = it.workspace.copy(wheelSpeed = v)) }
    }

    fun setPlateHd(on: Boolean) {
        store.settings.plateHd = on
        store.update { it.copy(workspace = it.workspace.copy(plateHd = on)) }
    }

    fun setPlateUnderAlpha(v: Float) {
        val a = v.coerceIn(app.orcaandroid.core.ViewDefaults.PLATE_UNDER_ALPHAS)
        store.settings.plateUnderAlpha = a
        store.update { it.copy(workspace = it.workspace.copy(plateUnderAlpha = a)) }
    }

    fun setOverlayAlpha(v: Float) {
        val a = v.coerceIn(app.orcaandroid.core.ViewDefaults.OVERLAY_ALPHAS)
        store.settings.overlayAlpha = a
        store.update { it.copy(chrome = it.chrome.copy(overlayAlpha = a)) }
    }

    fun setPlateScale(v: Float) {
        val s = v.coerceIn(app.orcaandroid.core.ViewDefaults.PLATE_SCALES)
        store.settings.plateScale = s
        store.update { it.copy(chrome = it.chrome.copy(plateScale = s)) }
    }

    fun setOverlayScale(v: Float) {
        val s = v.coerceIn(app.orcaandroid.core.ViewDefaults.OVERLAY_SCALES)
        store.settings.overlayScale = s
        store.update { it.copy(chrome = it.chrome.copy(overlayScale = s)) }
    }

    fun setSliceScale(v: Float) {
        val s = v.coerceIn(app.orcaandroid.core.ViewDefaults.SLICE_SCALES)
        store.settings.sliceScale = s
        store.update { it.copy(chrome = it.chrome.copy(sliceScale = s)) }
    }

    fun setTextScale(v: Float) {
        val s = v.coerceIn(app.orcaandroid.core.ViewDefaults.TEXT_SCALES)
        store.settings.textScale = s
        store.update { it.copy(chrome = it.chrome.copy(textScale = s)) }
    }

    fun setFullscreenPortrait(on: Boolean) {
        store.settings.fullscreenPortrait = on
        store.update { it.copy(chrome = it.chrome.copy(fullscreenPortrait = on)) }
    }

    /** The plate switcher style; the retired chips style is stored as tabs. */
    fun setPlates(style: app.orcaandroid.core.PlateSwitcher) {
        store.settings.plates = style
        store.update { it.copy(chrome = it.chrome.copy(plates = store.settings.plates)) }
    }

    fun setPlatesOnHandle(on: Boolean) {
        store.settings.platesOnHandle = on
        store.update { it.copy(chrome = it.chrome.copy(platesOnHandle = on)) }
    }

    fun setToolsLeft(on: Boolean) {
        store.settings.toolsLeft = on
        store.update { it.copy(chrome = it.chrome.copy(toolsLeft = on)) }
    }

    /** The portrait tool row collapsed to the + button (long press on it). */
    fun setToolsCollapsed(on: Boolean) {
        store.settings.toolsCollapsed = on
        store.update { it.copy(chrome = it.chrome.copy(toolsCollapsed = on)) }
    }

    /** The portrait tool row's offset in dp (-1 = default), set by dragging it. */
    fun setToolsRow(x: Float, y: Float) {
        store.settings.toolsRowX = x
        store.settings.toolsRowY = y
        store.update { it.copy(chrome = it.chrome.copy(toolsRow = x to y)) }
    }

    /** The key light's direction (view menu > Light), in degrees; clamped to the allowed ranges. */
    fun setLight(azimuth: Float, elevation: Float) {
        val az = azimuth.coerceIn(app.orcaandroid.core.ViewDefaults.LIGHT_AZIMUTHS)
        val el = elevation.coerceIn(app.orcaandroid.core.ViewDefaults.LIGHT_ELEVATIONS)
        store.settings.lightAzimuth = az
        store.settings.lightElevation = el
        store.update { it.copy(workspace = it.workspace.copy(lightAzimuth = az, lightElevation = el)) }
    }

    fun setPen(pen: app.orcaandroid.core.PenConfig) {
        store.settings.pen = pen
        store.update { it.copy(workspace = it.workspace.copy(pen = pen)) }
    }

    fun setDoubleBackToQuit(on: Boolean) {
        store.settings.doubleBackToQuit = on
        store.update { it.copy(chrome = it.chrome.copy(doubleBackToQuit = on)) }
    }

    fun setAutoSlice(on: Boolean) {
        store.settings.autoSlice = on
        store.update { it.copy(slicing = it.slicing.copy(autoSlice = on)) }
        if (!on) slicing.stopAutoSlice()
    }

    fun setProjectFilaments(mode: String) {
        store.settings.projectFilaments = mode
        store.update { it.copy(device = it.device.copy(projectFilaments = mode)) }
    }

    fun setContactShadows(on: Boolean) {
        store.settings.contactShadows = on
        store.update { it.copy(workspace = it.workspace.copy(contactShadows = on)) }
    }

    fun setSelectionStyle(style: Int) {
        store.settings.selectionStyle = style
        store.update { it.copy(workspace = it.workspace.copy(selectionStyle = style)) }
    }

    fun setSupersample(mode: Int) {
        store.settings.supersample = mode
        store.update { it.copy(workspace = it.workspace.copy(supersample = mode)) }
    }

    fun setRealistic(on: Boolean) {
        store.settings.realistic = on
        store.update { it.copy(workspace = it.workspace.copy(realistic = on)) }
    }

    fun setLightPanel(on: Boolean) = store.update { it.copy(workspace = it.workspace.copy(lightPanel = on)) }
    fun setDimensions(on: Boolean) = store.update { it.copy(workspace = it.workspace.copy(dimensions = on)) }

    fun setWireframe(on: Boolean) = store.update { it.copy(workspace = it.workspace.copy(wireframe = on)) }

    fun setEdgeOutline(on: Boolean) {
        store.settings.edgeOutline = on
        store.update { it.copy(workspace = it.workspace.copy(edgeOutline = on)) }
    }

    fun setLayerLines(on: Boolean) {
        store.settings.layerLines = on
        store.update { it.copy(workspace = it.workspace.copy(layerLines = on)) }
    }

    /** Snap on or off where the user is: the Build workspace when it is open, else the plate. */
    fun toggleSnap() {
        val s = state.value
        if (s.buildObject != null) { store.settings.snapBuild = !s.design.snapBuild; store.update { it.copy(design = it.design.copy(snapBuild = !s.design.snapBuild)) } }
        else { store.settings.snapPrepare = !s.workspace.snapPrepare; store.update { it.copy(workspace = it.workspace.copy(snapPrepare = !s.workspace.snapPrepare)) } }
    }

    /** Build workspace: the plate's other objects cycle ghosted -> solid -> hidden. */
    fun cycleBuildOthers() {
        val next = (state.value.design.buildOthers + 1) % 3
        store.settings.buildOthers = next
        store.update { it.copy(design = it.design.copy(buildOthers = next)) }
    }

    /** Build workspace: the printer volume ghost on or off. */
    fun toggleBuildPrinter() {
        val next = !state.value.design.buildPrinter
        store.settings.buildPrinter = next
        store.update { it.copy(design = it.design.copy(buildPrinter = next)) }
    }

    /** The move lock button: free -> X -> Y -> free. */
    fun cycleMoveLock() = store.update { it.copy(workspace = it.workspace.copy(moveLock = if (it.workspace.moveLock >= 1) -1 else it.workspace.moveLock + 1)) }

    fun setPerspective(on: Boolean) {
        store.settings.perspective = on
        store.update { it.copy(workspace = it.workspace.copy(perspective = on)) }
    }

    fun setAutoSave(seconds: Int) {
        store.settings.autoSaveSeconds = seconds
        store.update { it.copy(chrome = it.chrome.copy(autoSaveSeconds = seconds)) }
    }

    /** Switches the MCP server for AI clients on or off (and remembers it). */
    fun setAiTools(on: Boolean, lan: Boolean = store.settings.aiToolsLan) {
        store.settings.aiTools = on
        store.settings.aiToolsLan = lan
        store.update { it.copy(chrome = it.chrome.copy(aiTools = on, aiToolsLan = lan, aiToken = store.settings.aiToken, aiError = null)) }
        viewModelScope.launch {
            val (error, addresses) = withContext(Dispatchers.IO) {
                mcp.stop()
                val error = if (!on) null else runCatching { mcp.start(viewModelScope, lan) }.exceptionOrNull()?.let { it.message ?: it.toString() }
                error to if (on && lan) lanAddresses() else emptyList()
            }
            store.update { it.copy(chrome = it.chrome.copy(aiError = error, aiAddresses = addresses)) }
        }
    }

    /** This device's IPv4 addresses other than loopback (where LAN clients reach the AI tools). */
    private fun lanAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLinkLocalAddress }.map { it.hostAddress.orEmpty() }
    }.getOrDefault(emptyList())

    /** The user's Allow / Deny for an AI tool waiting on screen ([UiState.aiConfirm]). */
    fun answerAi(allow: Boolean) = aiTools.answer(allow)

    /** A new token for AI clients: the old one stops working at once. */
    fun newAiToken() {
        val token = store.settings.newAiToken()
        store.update { it.copy(chrome = it.chrome.copy(aiToken = token)) }
    }

    override fun onCleared() {
        mcp.stop()
        // The notification's Cancel must not reach this cleared controller.
        if (store.container.cancelSlice == slicing::cancelSlice) store.container.cancelSlice = null
        super.onCleared()
    }

    /** Keeps the current state for the next start; runs outside the view model so it survives the activity. */
    fun saveSession() = store.container.appScope.launch {
        runCatching { files.saveSession() }.onFailure { android.util.Log.w("Orca", "session not saved", it) }
    }

    /** What the session file holds; a change of any of it makes the session stale. */
    private data class SessionKey(
        val scene: app.orcaandroid.core.Scene, val printer: String?, val print: String?, val filaments: List<app.orcaandroid.core.FilamentSlot>,
        val overrides: Map<app.orcaandroid.core.PresetType, Map<String, String>>, val projectName: String?, val activePlate: Int,
    )

    private fun sessionKey(s: UiState) = SessionKey(s.workspace.scene, s.device.printer, s.device.print, s.device.filaments, s.device.overrides, s.workspace.projectName, s.workspace.activePlate)

    /**
     * Saves the session every [UiState.autoSaveSeconds] while it has changed, so a crash or a killed
     * process loses at most that much (like the desktop's Preferences > Backup, `MainFrame.cpp:755`).
     * Waits while the app is busy or slicing; the engine thread serializes it with other engine calls.
     */
    private suspend fun autoSave() {
        awaitReady()
        var saved = sessionKey(state.value)
        while (true) {
            val seconds = state.value.chrome.autoSaveSeconds
            delay(if (seconds > 0) seconds * 1000L else 5_000L)
            val s = state.value
            if (seconds <= 0 || s.chrome.phase != Phase.READY || s.chrome.busy != null || s.slicing.slice != SliceStatus.Idle) continue
            val key = sessionKey(s)
            if (key == saved) continue
            runCatching { files.saveSession() }
                .onSuccess { saved = key }
                .onFailure { android.util.Log.w("Orca", "auto-save failed", it) }
        }
    }

    fun dismissError() = store.update { it.copy(chrome = it.chrome.copy(error = null)) }
    fun dismissSliceError() = store.update { it.copy(slicing = it.slicing.copy(sliceError = null)) }
    fun dismissFit() = store.update { it.copy(slicing = it.slicing.copy(fitIssue = null)) }
    fun dismissMessage() = store.update { it.copy(chrome = it.chrome.copy(message = null, messageAction = null)) }
    fun setScadHidden(hidden: Boolean) = store.update { it.copy(design = it.design.copy(scadHidden = hidden)) }
    fun setScadOnPlate(onPlate: Boolean) = store.update { it.copy(design = it.design.copy(scadOnPlate = onPlate)) }
    fun toast(message: String) = store.toast(message)
    fun showError(message: String) = store.update { it.copy(chrome = it.chrome.copy(error = message)) }
}
