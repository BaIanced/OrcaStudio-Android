package com.orcaslicer.android.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orcaslicer.android.core.ModelInfo
import com.orcaslicer.android.core.OptionDef
import com.orcaslicer.android.core.OrcaRepository
import com.orcaslicer.android.core.Overrides
import com.orcaslicer.android.core.PresetType
import com.orcaslicer.android.core.PrinterInfo
import com.orcaslicer.android.core.PrinterSetup
import com.orcaslicer.android.core.SettingsPage
import com.orcaslicer.android.core.SliceResult
import com.orcaslicer.android.core.Translator
import com.orcaslicer.android.core.Vendor
import com.orcaslicer.android.core.printerKey
import com.orcaslicer.android.core.AppSettings
import com.orcaslicer.android.core.HostType
import com.orcaslicer.android.core.PrinterConnection
import com.orcaslicer.android.core.ThemeMode
import com.orcaslicer.android.net.PrintHostClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { PREPARE, DEVICE, SETTINGS }

sealed interface UploadState {
    data class Running(val progress: Float, val startPrint: Boolean) : UploadState
    data class Done(val message: String) : UploadState
}

sealed interface SliceState {
    data object Idle : SliceState
    data class Running(val percent: Int, val text: String) : SliceState
    data class Done(val result: SliceResult) : SliceState
}

data class UiState(
    val screen: Screen = Screen.PREPARE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Network connection of the selected printer, if set up. */
    val connection: PrinterConnection? = null,
    /** Connection prefilled from the printer preset's print_host settings. */
    val suggestedConnection: PrinterConnection? = null,
    val upload: UploadState? = null,
    val initializing: Boolean = true,
    val busyMessage: String? = null,
    val error: String? = null,
    val needsVendorSetup: Boolean = false,
    val vendors: List<Vendor> = emptyList(),
    val installedVendors: Set<String> = emptySet(),
    /** Printers the user picked in the setup, as printerKey(model, nozzle). */
    val selectedPrinters: Set<String> = emptySet(),
    /** All printer presets of the installed vendors (see [visiblePrinters]). */
    val printers: List<PrinterInfo> = emptyList(),
    val printer: String? = null,
    val setup: PrinterSetup? = null,
    val print: String? = null,
    val filament: String? = null,
    /** Option values of the selected presets, per type. */
    val presetValues: Map<PresetType, Map<String, String>> = emptyMap(),
    /** Unsaved edits on top of the selected presets, per type. */
    val overrides: Overrides = emptyMap(),
    /** Settings editor currently open, if any. */
    val settingsType: PresetType? = null,
    val copies: Int = 1,
    val modelUris: List<Uri> = emptyList(),
    val modelNames: List<String> = emptyList(),
    val model: ModelInfo? = null,
    val slice: SliceState = SliceState.Idle,
    /** Selected preview layer (index into SliceResult.layers). */
    val layer: Int = 0,
    val showToolpaths: Boolean = false,
    /** Models handed to the app (e.g. "open with") before a printer was ready. */
    val pendingUris: List<Uri> = emptyList(),
    /** Debug builds: slice as soon as the pending model is loaded (automated testing). */
    val autoSlice: Boolean = false,
) {
    fun presetName(type: PresetType) = when (type) {
        PresetType.PRINT -> print
        PresetType.FILAMENT -> filament
        PresetType.PRINTER -> printer
    }

    /** Effective value of an option: the unsaved edit if any, else the preset's value. */
    fun value(type: PresetType, key: String): String? = overrides[type]?.get(key) ?: presetValues[type]?.get(key)

    fun isModified(type: PresetType, key: String) = overrides[type]?.containsKey(key) == true

    /** The printers offered for selection: the chosen system printers plus all user printers. */
    val visiblePrinters: List<PrinterInfo>
        get() = if (selectedPrinters.isEmpty()) printers
        else printers.filter { !it.system || printerKey(it.model, it.nozzle) in selectedPrinters }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = OrcaRepository(app)
    private val prefs = app.getSharedPreferences("orca_ui", Context.MODE_PRIVATE)
    private val settings = AppSettings(app)

    private val _state = MutableStateFlow(UiState(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Translations of the desktop catalogs for the device language. */
    var translator: Translator = Translator.NONE
        private set

    init {
        viewModelScope.launch {
            runCatching {
                translator = withContext(Dispatchers.IO) { Translator.load(app) }
                repo.initialize()
                val vendors = withContext(Dispatchers.IO) { repo.availableVendors() }
                val installed = repo.installedVendorIds()
                val selected = prefs.getStringSet("selected_printers", emptySet()).orEmpty()
                _state.update { it.copy(vendors = vendors, installedVendors = installed, selectedPrinters = selected) }
                if (selected.isEmpty() || installed.none { id -> vendors.any { v -> v.id == id && !v.required } }) {
                    _state.update { it.copy(initializing = false, needsVendorSetup = true) }
                } else {
                    reloadPresets()
                }
            }.onFailure { e -> _state.update { it.copy(initializing = false, error = e.message) } }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun setScreen(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        settings.dynamicColor = enabled
        _state.update { it.copy(dynamicColor = enabled) }
    }

    // --- Printer connection / upload ---------------------------------------------------------

    fun setConnection(connection: PrinterConnection?) {
        val printer = _state.value.printer ?: return
        settings.setConnection(printer, connection)
        _state.update { it.copy(connection = connection) }
    }

    private fun refreshConnection() = _state.update { s ->
        val values = s.presetValues[PresetType.PRINTER].orEmpty()
        val host = values["print_host"].orEmpty().trim('"')
        val suggested = if (host.isBlank()) null else PrinterConnection(
            type = HostType.fromId(values["host_type"]),
            url = host,
            apiKey = values["printhost_apikey"].orEmpty().trim('"'),
            webUrl = values["print_host_webui"].orEmpty().trim('"'),
        )
        s.copy(connection = s.printer?.let(settings::connection), suggestedConnection = suggested)
    }

    /** Sends the sliced G-code to the connected printer, optionally starting the print. */
    fun uploadGcode(startPrint: Boolean) {
        val s = _state.value
        val done = s.slice as? SliceState.Done ?: return
        val connection = s.connection ?: return
        if (s.upload is UploadState.Running) return
        _state.update { it.copy(upload = UploadState.Running(0f, startPrint)) }
        viewModelScope.launch {
            runCatching {
                PrintHostClient.upload(connection, File(done.result.gcodeFile), gcodeFileName(), startPrint) { p ->
                    _state.update { it.copy(upload = UploadState.Running(p, startPrint)) }
                }
            }.onSuccess {
                _state.update { it.copy(upload = UploadState.Done(if (startPrint) "Druck gestartet" else "An Drucker gesendet")) }
            }.onFailure { e ->
                _state.update { it.copy(upload = null, error = "Senden fehlgeschlagen: ${e.message ?: e}") }
            }
        }
    }

    fun dismissUpload() = _state.update { it.copy(upload = null) }

    fun showError(message: String) = _state.update { it.copy(error = message) }

    // --- Vendors / presets ---------------------------------------------------------------------

    fun openVendorSetup() = _state.update { it.copy(needsVendorSetup = true) }

    fun closeVendorSetup() {
        if (_state.value.printers.isNotEmpty()) _state.update { it.copy(needsVendorSetup = false) }
    }

    /** Installs the vendors of the chosen printers (and drops unused ones), then activates a new pick. */
    fun applyPrinterSelection(keys: Set<String>) = launchBusy("Druckerprofile werden installiert …") {
        val s = _state.value
        val added = keys - s.selectedPrinters
        val vendorIds = s.vendors.filter { v -> v.models.any { m -> m.nozzles.any { printerKey(m.name, it) in keys } } }
            .map { it.id }.toSet()
        repo.setInstalledVendors(vendorIds)
        prefs.edit().putStringSet("selected_printers", keys).apply()
        _state.update { it.copy(installedVendors = repo.installedVendorIds(), selectedPrinters = keys, needsVendorSetup = false) }
        reloadPresets(prefer = added)
    }

    /** Debug hook: applies a printer selection once initialization has finished. */
    fun debugSelectPrinters(keys: Set<String>) = viewModelScope.launch {
        _state.first { !it.initializing }
        applyPrinterSelection(keys)
    }

    private suspend fun reloadPresets(prefer: Set<String> = emptySet()) {
        _state.update { it.copy(busyMessage = "Profile werden geladen …") }
        val printers = repo.loadPresets()
        _state.update { it.copy(printers = printers, initializing = false, busyMessage = null) }
        val visible = _state.value.visiblePrinters
        val remembered = prefs.getString("printer", null)
        // A printer the user just added wins (0.4 mm first), then the last used one.
        val printer = visible.filter { it.system && printerKey(it.model, it.nozzle) in prefer }
            .minByOrNull { kotlin.math.abs(it.nozzle - 0.4) }
            ?: visible.firstOrNull { it.name == remembered }
            ?: visible.firstOrNull { it.system && kotlin.math.abs(it.nozzle - 0.4) < 1e-3 }
            ?: visible.firstOrNull()
        printer?.let { selectPrinterNow(it.name) }
    }

    fun selectPrinter(name: String) = launchBusy("Drucker wird gewählt …") { selectPrinterNow(name) }

    private suspend fun selectPrinterNow(name: String) {
        applySetup(name, repo.selectPrinter(name))
        val pending = _state.value.pendingUris
        if (pending.isNotEmpty()) {
            _state.update { it.copy(pendingUris = emptyList(), modelUris = pending, modelNames = pending.map(repo::displayName)) }
            loadModelNow()
            if (_state.value.autoSlice) slice()
        } else if (_state.value.modelUris.isNotEmpty()) {
            // Re-arrange the loaded model for the new bed.
            loadModelNow()
        }
    }

    /** Adopts a (re)computed printer setup, keeping the remembered process/filament if still compatible. */
    private suspend fun applySetup(printer: String, setup: PrinterSetup, print: String? = null, filament: String? = null) {
        prefs.edit().putString("printer", printer).apply()
        val p = print?.takeIf { n -> setup.prints.any { it.name == n } }
            ?: prefs.getString("print:$printer", null)?.takeIf { n -> setup.prints.any { it.name == n } }
            ?: setup.defaultPrint
        val f = filament?.takeIf { n -> setup.filaments.any { it.name == n } }
            ?: prefs.getString("filament:$printer", null)?.takeIf { n -> setup.filaments.any { it.name == n } }
            ?: setup.defaultFilament
        _state.update {
            it.copy(
                printer = printer, setup = setup, print = p, filament = f, overrides = emptyMap(),
                slice = SliceState.Idle, showToolpaths = false,
            )
        }
        refreshPresetValues(PresetType.entries)
        refreshConnection()
    }

    private suspend fun refreshPresetValues(types: Collection<PresetType>) {
        val s = _state.value
        val values = s.presetValues.toMutableMap()
        for (type in types) s.presetName(type)?.let { values[type] = repo.presetValues(type, it) }
        _state.update { it.copy(presetValues = values) }
    }

    fun selectPrint(name: String) = launchBusy(null) {
        prefs.edit().putString("print:${_state.value.printer}", name).apply()
        _state.update { it.copy(print = name, overrides = it.overrides - PresetType.PRINT).invalidated() }
        refreshPresetValues(listOf(PresetType.PRINT))
    }

    fun selectFilament(name: String) = launchBusy(null) {
        prefs.edit().putString("filament:${_state.value.printer}", name).apply()
        _state.update { it.copy(filament = name, overrides = it.overrides - PresetType.FILAMENT).invalidated() }
        refreshPresetValues(listOf(PresetType.FILAMENT))
    }

    // --- Settings editing ----------------------------------------------------------------------

    fun openSettings(type: PresetType) = _state.update { it.copy(settingsType = type) }

    fun closeSettings() = _state.update { it.copy(settingsType = null) }

    /** Sets an option; setting it back to the preset's value drops the override. */
    fun setOption(type: PresetType, key: String, value: String) = _state.update { s ->
        val current = s.overrides[type].orEmpty()
        val updated = if (s.presetValues[type]?.get(key) == value) current - key else current + (key to value)
        s.copy(overrides = s.overrides + (type to updated)).invalidated()
    }

    fun resetOption(type: PresetType, key: String) = _state.update { s ->
        s.copy(overrides = s.overrides + (type to (s.overrides[type].orEmpty() - key))).invalidated()
    }

    fun resetAll(type: PresetType) = _state.update { it.copy(overrides = it.overrides - type).invalidated() }

    suspend fun optionDefs(type: PresetType): List<OptionDef> = repo.optionDefs(type)

    fun settingsLayout(type: PresetType): List<SettingsPage> = repo.settingsLayout(type)

    /** Saves the selected preset plus its unsaved edits as a user preset and selects it. */
    fun savePresetAs(type: PresetType, name: String) = launchBusy("Preset wird gespeichert …") {
        val s = _state.value
        val base = s.presetName(type) ?: return@launchBusy
        val setup = repo.savePreset(type, base, name.trim(), s.overrides[type].orEmpty())
        when (type) {
            PresetType.PRINTER -> applySetup(name.trim(), setup, s.print, s.filament)
            PresetType.PRINT -> {
                prefs.edit().putString("print:${s.printer}", name.trim()).apply()
                _state.update { it.copy(setup = setup, print = name.trim(), overrides = it.overrides - type) }
            }
            PresetType.FILAMENT -> {
                prefs.edit().putString("filament:${s.printer}", name.trim()).apply()
                _state.update { it.copy(setup = setup, filament = name.trim(), overrides = it.overrides - type) }
            }
        }
        if (type == PresetType.PRINTER) _state.update { it.copy(printers = repo.loadPrinterList()) }
        refreshPresetValues(listOf(type))
    }

    /** Deletes the selected user preset of [type]. */
    fun deleteSelectedPreset(type: PresetType) = launchBusy("Preset wird gelöscht …") {
        val s = _state.value
        val name = s.presetName(type) ?: return@launchBusy
        val setup = repo.deletePreset(type, name)
        when (type) {
            PresetType.PRINTER -> {
                _state.update { it.copy(printers = repo.loadPrinterList()) }
                val next = _state.value.printers.firstOrNull()?.name ?: return@launchBusy
                selectPrinterNow(next)
            }
            PresetType.PRINT -> _state.update { it.copy(setup = setup, print = setup.defaultPrint, overrides = it.overrides - type) }
            PresetType.FILAMENT -> _state.update { it.copy(setup = setup, filament = setup.defaultFilament, overrides = it.overrides - type) }
        }
        refreshPresetValues(listOf(type))
    }

    fun isUserPreset(type: PresetType): Boolean {
        val s = _state.value
        val name = s.presetName(type) ?: return false
        return when (type) {
            PresetType.PRINTER -> s.printers.any { it.name == name && !it.system }
            PresetType.PRINT -> s.setup?.prints?.any { it.name == name && !it.system } == true
            PresetType.FILAMENT -> s.setup?.filaments?.any { it.name == name && !it.system } == true
        }
    }

    // --- Model -------------------------------------------------------------------------------

    fun setCopies(copies: Int) {
        _state.update { it.copy(copies = copies.coerceIn(1, 50)) }
        if (_state.value.modelUris.isNotEmpty()) launchBusy("Modell wird angeordnet …") { loadModelNow() }
    }

    fun openModels(uris: List<Uri>, autoSlice: Boolean = false) {
        if (uris.isEmpty()) return
        if (_state.value.setup == null) {
            _state.update { it.copy(pendingUris = uris, autoSlice = autoSlice) }
            return
        }
        _state.update { it.copy(modelUris = uris, modelNames = uris.map(repo::displayName)) }
        launchBusy("Modell wird geladen …") {
            loadModelNow()
            if (autoSlice) slice()
        }
    }

    private suspend fun loadModelNow() {
        val s = _state.value
        val model = repo.loadModel(s.modelUris, s.copies)
        _state.update { it.copy(model = model).invalidated() }
    }

    // --- Slicing -----------------------------------------------------------------------------

    fun slice() {
        val s = _state.value
        val print = s.print ?: return
        val filament = s.filament ?: return
        if (s.model == null || s.slice is SliceState.Running) return
        _state.update { it.copy(slice = SliceState.Running(0, "Vorbereitung …")) }
        viewModelScope.launch {
            runCatching {
                repo.slice(print, filament, s.overrides) { p, t -> _state.update { it.copy(slice = SliceState.Running(p, t)) } }
            }.onSuccess { result ->
                _state.update {
                    it.copy(slice = SliceState.Done(result), layer = result.layers.lastIndex, showToolpaths = true, upload = null)
                }
            }.onFailure { e ->
                _state.update { it.copy(slice = SliceState.Idle, error = e.message ?: e.toString()) }
            }
        }
    }

    fun cancelSlice() = repo.cancelSlicing()

    fun setLayer(layer: Int) = _state.update { it.copy(layer = layer) }

    fun setShowToolpaths(show: Boolean) = _state.update { it.copy(showToolpaths = show && it.slice is SliceState.Done) }

    /** Copies the sliced G-code to a document the user picked. */
    fun exportGcode(target: Uri) = launchBusy("G-Code wird gespeichert …") {
        val done = _state.value.slice as? SliceState.Done ?: return@launchBusy
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.openOutputStream(target, "wt")!!.use { out -> File(done.result.gcodeFile).inputStream().use { it.copyTo(out) } }
        }
    }

    /** Suggested file name for the exported G-code. */
    fun gcodeFileName(): String {
        val base = _state.value.modelNames.firstOrNull()?.substringBeforeLast('.') ?: "plate"
        return "$base.gcode"
    }

    /** Any change to model or settings makes the last slice result stale. */
    private fun UiState.invalidated() =
        if (slice is SliceState.Running) this else copy(slice = SliceState.Idle, showToolpaths = false)

    private fun launchBusy(message: String?, block: suspend () -> Unit) = viewModelScope.launch {
        if (message != null) _state.update { it.copy(busyMessage = message) }
        runCatching { block() }.onFailure { e -> _state.update { it.copy(error = e.message ?: e.toString()) } }
        if (message != null) _state.update { it.copy(busyMessage = null) }
    }
}
