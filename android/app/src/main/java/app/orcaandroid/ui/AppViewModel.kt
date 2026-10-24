package app.orcaandroid.ui

import app.orcaandroid.ui.prepare.PieTouch
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
    /** Where the finger is that opened the pie menu (the 3D view feeds it, the menu reads it). */
    val pieTouch = PieTouch()
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
                .onFailure { e -> store.update { it.copy(phase = Phase.SETUP, error = e.message ?: e.toString()) } }
        }
        viewModelScope.launch { autoSave() }
        viewModelScope.launch { awaitReady(); if (store.settings.aiTools) setAiTools(true) }
        viewModelScope.launch {
            store.container.printerStatus.collect { s ->
                store.update { it.copy(printerStatus = s) }
                s?.nozzleDiameter?.let(presets::recordNozzle)
            }
        }
        // The Build workspace's object, for the engine's per-volume selection ids.
        viewModelScope.launch {
            store.state.map { it.buildObject }.distinctUntilChanged().collect { scad.showBuild(it) }
        }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { runCatching { CrashReport.pending(store.app) }.getOrNull() }
            if (report != null) store.update { it.copy(crashReport = report) }
        }
    }

    fun dismissCrashReport() {
        CrashReport.clear(store.app)
        store.update { it.copy(crashReport = null) }
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
        store.update { it.copy(vendors = vendors, selectedPrinters = selected) }
        val installed = resources.installedVendorIds()
        if (selected.isEmpty() || installed.none { id -> vendors.any { v -> v.id == id && !v.required } }) {
            store.update { it.copy(phase = Phase.SETUP) }
        } else {
            presets.reloadPresets(emptySet())
            runCatching { files.restoreSession() }.onFailure { android.util.Log.w("Orca", "session not restored", it) }
            store.update { it.copy(phase = Phase.READY) }
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
    suspend fun awaitReady() = state.first { it.phase == Phase.READY }

    // --- App-wide UI state ---------------------------------------------------------------------------

    fun setScreen(screen: Screen) = store.update { it.copy(screen = screen, tool = toolAfterScreenChange(it.screen, screen, it.tool)) }

    /** Shows [url] in the Models tab's browser. */
    fun openInModels(url: String) = store.update { it.copy(modelsUrl = url, screen = Screen.MODELS, tool = Tool.None) }

    fun modelsUrlOpened() = store.update { it.copy(modelsUrl = null) }

    fun setThemeMode(mode: ThemeMode) {
        store.settings.themeMode = mode
        store.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(on: Boolean) {
        store.settings.dynamicColor = on
        store.update { it.copy(dynamicColor = on) }
    }

    /** New colours; [persist] = false while a slider is still moving (saved when it's let go). */
    fun setThemeSpec(spec: app.orcaandroid.core.ThemeSpec, persist: Boolean = true) {
        if (persist) store.settings.themeSpec = spec
        store.update { it.copy(themeSpec = spec) }
    }

    /** Picks a colour preset; wallpaper colours would hide it, so they go off. */
    fun setThemePreset(key: String) {
        if (state.value.dynamicColor) setDynamicColor(false)
        setThemeSpec(state.value.themeSpec.copy(preset = key))
    }

    /** Design mode: the colour panel floats over the app; opened from More, it starts on Prepare. */
    fun setDesignMode(on: Boolean) = store.update {
        it.copy(designMode = on, screen = if (on && it.screen == Screen.MORE) Screen.PREPARE else it.screen)
    }

    fun setUndoLimit(limit: Int) {
        store.settings.undoLimit = limit
        store.update { it.copy(undoLimit = limit) }
        store.sceneOp { store.engine.setUndoLimit(limit) }
    }

    fun setLab(lab: app.orcaandroid.core.LayoutLab) {
        store.settings.layoutLab = lab
        store.update { it.copy(lab = lab) }
    }

    fun setPen(pen: app.orcaandroid.core.PenConfig) {
        store.settings.pen = pen
        store.update { it.copy(pen = pen) }
    }

    fun setDoubleBackToQuit(on: Boolean) {
        store.settings.doubleBackToQuit = on
        store.update { it.copy(doubleBackToQuit = on) }
    }

    fun setProjectFilaments(mode: String) {
        store.settings.projectFilaments = mode
        store.update { it.copy(projectFilaments = mode) }
    }

    fun setContactShadows(on: Boolean) {
        store.settings.contactShadows = on
        store.update { it.copy(contactShadows = on) }
    }

    fun setSelectionStyle(style: Int) {
        store.settings.selectionStyle = style
        store.update { it.copy(selectionStyle = style) }
    }

    fun setSupersample(mode: Int) {
        store.settings.supersample = mode
        store.update { it.copy(supersample = mode) }
    }

    fun setPlateSurface(on: Boolean) {
        store.settings.plateSurface = on
        store.update { it.copy(plateSurface = on) }
    }

    fun setRealistic(on: Boolean) {
        store.settings.realistic = on
        store.update { it.copy(realistic = on) }
    }

    fun setLayerLines(on: Boolean) {
        store.settings.layerLines = on
        store.update { it.copy(layerLines = on) }
    }

    fun setPerspective(on: Boolean) {
        store.settings.perspective = on
        store.update { it.copy(perspective = on) }
    }

    fun setAutoSave(seconds: Int) {
        store.settings.autoSaveSeconds = seconds
        store.update { it.copy(autoSaveSeconds = seconds) }
    }

    /** Switches the MCP server for AI clients on or off (and remembers it). */
    fun setAiTools(on: Boolean, lan: Boolean = store.settings.aiToolsLan) {
        store.settings.aiTools = on
        store.settings.aiToolsLan = lan
        store.update { it.copy(aiTools = on, aiToolsLan = lan, aiToken = store.settings.aiToken, aiError = null) }
        viewModelScope.launch {
            val (error, addresses) = withContext(Dispatchers.IO) {
                mcp.stop()
                val error = if (!on) null else runCatching { mcp.start(viewModelScope, lan) }.exceptionOrNull()?.let { it.message ?: it.toString() }
                error to if (on && lan) lanAddresses() else emptyList()
            }
            store.update { it.copy(aiError = error, aiAddresses = addresses) }
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
        store.update { it.copy(aiToken = token) }
    }

    override fun onCleared() {
        mcp.stop()
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

    private fun sessionKey(s: UiState) = SessionKey(s.scene, s.printer, s.print, s.filaments, s.overrides, s.projectName, s.activePlate)

    /**
     * Saves the session every [UiState.autoSaveSeconds] while it has changed, so a crash or a killed
     * process loses at most that much (like the desktop's Preferences > Backup, `MainFrame.cpp:755`).
     * Waits while the app is busy or slicing; the engine thread serializes it with other engine calls.
     */
    private suspend fun autoSave() {
        awaitReady()
        var saved = sessionKey(state.value)
        while (true) {
            val seconds = state.value.autoSaveSeconds
            delay(if (seconds > 0) seconds * 1000L else 5_000L)
            val s = state.value
            if (seconds <= 0 || s.phase != Phase.READY || s.busy != null || s.isSlicing) continue
            val key = sessionKey(s)
            if (key == saved) continue
            runCatching { files.saveSession() }
                .onSuccess { saved = key }
                .onFailure { android.util.Log.w("Orca", "auto-save failed", it) }
        }
    }

    fun dismissError() = store.update { it.copy(error = null) }
    fun dismissMessage() = store.update { it.copy(message = null, messageAction = null) }
    fun setScadHidden(hidden: Boolean) = store.update { it.copy(scadHidden = hidden) }
    fun setScadOnPlate(onPlate: Boolean) = store.update { it.copy(scadOnPlate = onPlate) }
    fun toast(message: String) = store.toast(message)
    fun showError(message: String) = store.update { it.copy(error = message) }
}
