package app.orcaandroid.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.orcaandroid.R
import app.orcaandroid.SliceProgress
import app.orcaandroid.container
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.core.LayerGcode
import app.orcaandroid.core.LayerProfile
import app.orcaandroid.core.LayerRange
import app.orcaandroid.core.OptionDef
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.PrinterSetup
import app.orcaandroid.core.RecentFile
import app.orcaandroid.core.Scene
import app.orcaandroid.core.SettingsGroup
import app.orcaandroid.core.SettingsPage
import app.orcaandroid.core.SliceResult
import app.orcaandroid.core.ThemeMode
import app.orcaandroid.core.Translator
import app.orcaandroid.core.Vec3
import app.orcaandroid.core.VolumeType
import app.orcaandroid.core.map
import app.orcaandroid.core.printerKey
import app.orcaandroid.net.Discovery
import app.orcaandroid.net.HostType
import app.orcaandroid.net.PrintHost
import app.orcaandroid.net.PrinterConnection
import app.orcaandroid.service.PrintMonitorService
import app.orcaandroid.service.SliceService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * All app state and actions. The native engine keeps the scene and presets; this class mirrors
 * what the UI needs in [state] and forwards user actions.
 */
class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    private val c = app.container
    private val engine = c.engine
    private val settings = c.settings
    private val resources = c.resources

    private val _state = MutableStateFlow(UiState(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor,
        hiddenFilamentVendors = settings.hiddenFilamentVendors, recents = settings.recents))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Desktop translations of option labels for the device language. */
    var translator: Translator = Translator.NONE
        private set

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)

    init {
        viewModelScope.launch {
            runCatching {
                translator = withContext(Dispatchers.IO) { Translator.load(app) }
                resources.prepare()
                engine.init(resources.resourcesDir, resources.dataDir)
                val vendors = withContext(Dispatchers.IO) { resources.availableVendors() }
                val selected = settings.selectedPrinters
                _state.update { it.copy(vendors = vendors, selectedPrinters = selected) }
                if (selected.isEmpty() || resources.installedVendorIds().none { id -> vendors.any { v -> v.id == id && !v.required } }) {
                    _state.update { it.copy(phase = Phase.SETUP) }
                } else {
                    reloadPresets(emptySet())
                    _state.update { it.copy(phase = Phase.READY) }
                }
            }.onFailure { e -> _state.update { it.copy(phase = Phase.SETUP, error = e.message ?: e.toString()) } }
        }
        viewModelScope.launch { c.printerStatus.collect { s -> _state.update { it.copy(printerStatus = s) } } }
    }

    // --- Generic helpers -------------------------------------------------------------------------

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun showError(message: String) = _state.update { it.copy(error = message) }
    private fun toast(message: String) = _state.update { it.copy(message = message) }

    /** Runs [block], showing [busy] (if any) and turning exceptions into an error dialog. */
    private fun launch(busy: String? = null, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (busy != null) _state.update { it.copy(busy = busy) }
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            if (busy != null) _state.update { it.copy(busy = null) }
        }
    }

    /** Adopts a scene from the engine; any slice result is stale afterwards. */
    private fun applyScene(scene: Scene, dirty: Boolean = true) = _state.update { s ->
        val sel = s.selection?.takeIf { it.obj < scene.objects.size }
        s.copy(
            scene = scene,
            selection = sel,
            activePlate = s.activePlate.coerceIn(0, (scene.plates.size - 1).coerceAtLeast(0)),
            results = emptyMap(),
            projectDirty = s.projectDirty || dirty,
        )
    }

    private fun sceneOp(busy: String? = null, block: suspend () -> Scene) = launch(busy) { applyScene(block()) }

    // --- App settings ------------------------------------------------------------------------------

    fun setScreen(screen: Screen) = _state.update { it.copy(screen = screen, tool = if (screen == Screen.PREPARE) it.tool else Tool.None) }

    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(on: Boolean) {
        settings.dynamicColor = on
        _state.update { it.copy(dynamicColor = on) }
    }

    fun checkProfileUpdates() = launch(str(R.string.checking_updates)) {
        val updates = resources.checkProfileUpdates()
        _state.update { it.copy(profileUpdates = updates) }
        if (updates.isEmpty()) toast(str(R.string.profiles_up_to_date))
    }

    fun installProfileUpdates() = launch {
        val updates = _state.value.profileUpdates.orEmpty()
        for (u in updates) {
            resources.updateVendor(u.vendor) { done, total -> _state.update { it.copy(busy = str(R.string.updating_vendor, u.vendor, done, total)) } }
        }
        _state.update { it.copy(busy = null, profileUpdates = null) }
        reloadPresets(emptySet())
        toast(str(R.string.profiles_updated))
    }

    // --- Printers and presets ------------------------------------------------------------------------

    fun openPrinterSetup() = _state.update { it.copy(showPrinterSetup = true) }
    fun closePrinterSetup() = _state.update { it.copy(showPrinterSetup = false) }

    /** Installs the vendors of the chosen printers (and drops unused ones), then activates a new pick. */
    fun applyPrinterSelection(keys: Set<String>) = launch(str(R.string.installing_profiles)) {
        val s = _state.value
        val added = keys - s.selectedPrinters
        val vendorIds = s.vendors.filter { v -> v.models.any { m -> m.nozzles.any { printerKey(m.name, it) in keys } } }.map { it.id }.toSet()
        resources.setInstalledVendors(vendorIds)
        settings.selectedPrinters = keys
        _state.update { it.copy(selectedPrinters = keys, showPrinterSetup = false) }
        reloadPresets(added)
        _state.update { it.copy(phase = Phase.READY) }
    }

    fun setHiddenFilamentVendors(vendors: Set<String>) {
        settings.hiddenFilamentVendors = vendors
        _state.update { it.copy(hiddenFilamentVendors = vendors) }
    }

    private suspend fun reloadPresets(prefer: Set<String>) {
        _state.update { it.copy(busy = str(R.string.loading_profiles)) }
        val printers = engine.loadPresets()
        _state.update { it.copy(printers = printers, busy = null) }
        val visible = _state.value.visiblePrinters
        val printer = visible.filter { it.system && it.key in prefer }.minByOrNull { kotlin.math.abs(it.nozzle - 0.4) }
            ?: visible.firstOrNull { it.name == settings.lastPrinter }
            ?: visible.firstOrNull { it.system && kotlin.math.abs(it.nozzle - 0.4) < 1e-3 }
            ?: visible.firstOrNull()
        printer?.let { selectPrinterNow(it.name) }
    }

    fun selectPrinter(name: String) = launch(str(R.string.selecting_printer)) { selectPrinterNow(name) }

    private suspend fun selectPrinterNow(name: String, print: String? = null, filaments: List<FilamentSlot>? = null) {
        val setup = engine.selectPrinter(name)
        settings.lastPrinter = name
        val p = print?.takeIf { n -> setup.prints.any { it.name == n } }
            ?: settings.lastPrint(name)?.takeIf { n -> setup.prints.any { it.name == n } }
            ?: setup.defaultPrint
        val fils = (filaments ?: settings.lastFilaments(name)).filter { f -> setup.filaments.any { it.name == f.preset } }
            .ifEmpty { listOf(FilamentSlot(setup.defaultFilament)) }
        _state.update {
            it.copy(printer = name, setup = setup, print = p, filaments = fils, activeFilament = 0, overrides = emptyMap(), results = emptyMap())
        }
        refreshPresetValues(PresetType.entries)
        refreshConnection()
        applyScene(engine.scene(), dirty = false)
    }

    private suspend fun refreshPresetValues(types: Collection<PresetType>) {
        val s = _state.value
        val values = s.presetValues.toMutableMap()
        for (type in types) s.presetName(type)?.let { values[type] = engine.presetValues(type, it) }
        _state.update { it.copy(presetValues = values) }
        syncSelection()
        refreshOptionStates()
    }

    /** Sends the current preset selection and edits to the engine (needed before slicing etc.). */
    private suspend fun syncSelection() {
        val s = _state.value
        val print = s.print ?: return
        if (s.filaments.isEmpty()) return
        engine.setSelection(print, s.filaments, s.overrides[PresetType.PRINTER].orEmpty() + s.overrides[PresetType.PRINT].orEmpty())
    }

    private suspend fun refreshOptionStates() {
        runCatching { engine.optionStates() }.onSuccess { st -> _state.update { it.copy(optionStates = st) } }
    }

    fun selectPrint(name: String) = launch {
        settings.setLastPrint(_state.value.printer ?: return@launch, name)
        _state.update { it.copy(print = name, overrides = it.overrides - PresetType.PRINT, results = emptyMap()) }
        refreshPresetValues(listOf(PresetType.PRINT))
    }

    fun setFilament(slot: Int, name: String) = updateFilaments { list -> list.mapIndexed { i, f -> if (i == slot) FilamentSlot(name, f.color) else f } }

    fun addFilament() = updateFilaments { list -> list + FilamentSlot(list.last().preset, nextColor(list.size)) }

    fun removeFilament(slot: Int) = updateFilaments { list -> if (list.size <= 1) list else list.filterIndexed { i, _ -> i != slot } }

    fun setFilamentColor(slot: Int, color: String) = updateFilaments { list -> list.mapIndexed { i, f -> if (i == slot) f.copy(color = color) else f } }

    fun setActiveFilament(slot: Int) = launch {
        _state.update { it.copy(activeFilament = slot.coerceIn(0, it.filaments.size - 1)) }
        refreshPresetValues(listOf(PresetType.FILAMENT))
    }

    private fun updateFilaments(transform: (List<FilamentSlot>) -> List<FilamentSlot>) = launch {
        val s = _state.value
        val list = transform(s.filaments)
        settings.setLastFilaments(s.printer ?: return@launch, list)
        _state.update { it.copy(filaments = list, activeFilament = it.activeFilament.coerceIn(0, list.size - 1), results = emptyMap()) }
        refreshPresetValues(listOf(PresetType.FILAMENT))
    }

    private fun nextColor(i: Int) = listOf("#FF7F27", "#2F7FEF", "#2FBF4F", "#EF3F3F", "#FFFFFF", "#202020", "#FFD700", "#8F3FDF")[i % 8]

    // --- Settings editor ------------------------------------------------------------------------------

    fun openEditor(target: EditorTarget) = launch {
        if (target is EditorTarget.Preset && target.type == PresetType.FILAMENT) refreshPresetValues(listOf(PresetType.FILAMENT))
        _state.update { it.copy(editor = target) }
    }

    fun closeEditor() = _state.update { it.copy(editor = null) }

    suspend fun optionDefs(type: PresetType): List<OptionDef> = engine.optionDefs(type)

    suspend fun presetValuesOf(type: PresetType, name: String): Map<String, String> = engine.presetValues(type, name)

    /** Desktop settings layout (pages and groups) of a preset type. */
    fun settingsLayout(type: PresetType): List<SettingsPage> {
        val json = app.assets.open("settings_layout.json").bufferedReader().use { it.readText() }
        return JSONObject(json).getJSONArray(type.id).map { p ->
            val page = p as JSONObject
            SettingsPage(page.getString("page"), page.getJSONArray("groups").map { g ->
                val group = g as JSONObject
                SettingsGroup(group.getString("group"), group.getJSONArray("options").map { it as String })
            })
        }
    }

    /** Sets an option; setting it back to the preset's value drops the edit. */
    fun setOption(type: PresetType, key: String, value: String) = launch {
        _state.update { s ->
            val isDefault = s.presetValues[type]?.get(key) == value
            if (type == PresetType.FILAMENT) {
                val slots = s.filaments.mapIndexed { i, f ->
                    if (i != s.activeFilament) f else f.copy(overrides = if (isDefault) f.overrides - key else f.overrides + (key to value))
                }
                s.copy(filaments = slots, results = emptyMap())
            } else {
                val current = s.overrides[type].orEmpty()
                s.copy(overrides = s.overrides + (type to if (isDefault) current - key else current + (key to value)), results = emptyMap())
            }
        }
        syncSelection()
        refreshOptionStates()
    }

    fun resetOption(type: PresetType, key: String) = launch {
        _state.update { s ->
            if (type == PresetType.FILAMENT) s.copy(filaments = s.filaments.mapIndexed { i, f -> if (i == s.activeFilament) f.copy(overrides = f.overrides - key) else f })
            else s.copy(overrides = s.overrides + (type to (s.overrides[type].orEmpty() - key)))
        }
        syncSelection()
        refreshOptionStates()
    }

    fun resetAll(type: PresetType) = launch {
        _state.update { s ->
            if (type == PresetType.FILAMENT) s.copy(filaments = s.filaments.mapIndexed { i, f -> if (i == s.activeFilament) f.copy(overrides = emptyMap()) else f })
            else s.copy(overrides = s.overrides - type)
        }
        syncSelection()
        refreshOptionStates()
    }

    fun savePresetAs(type: PresetType, name: String) = launch(str(R.string.saving_preset)) {
        val s = _state.value
        val base = s.presetName(type) ?: return@launch
        val setup = engine.savePreset(type, base, name.trim(), s.overridesOf(type))
        when (type) {
            PresetType.PRINTER -> {
                _state.update { it.copy(printers = engine.printerList(), overrides = it.overrides - type) }
                selectPrinterNow(name.trim(), s.print, s.filaments)
            }
            PresetType.PRINT -> {
                settings.setLastPrint(s.printer!!, name.trim())
                _state.update { it.copy(setup = setup, print = name.trim(), overrides = it.overrides - type) }
            }
            PresetType.FILAMENT -> _state.update {
                it.copy(setup = setup, filaments = it.filaments.mapIndexed { i, f -> if (i == it.activeFilament) FilamentSlot(name.trim(), f.color) else f })
            }
        }
        refreshPresetValues(listOf(type))
    }

    fun deleteSelectedPreset(type: PresetType) = launch(str(R.string.deleting_preset)) {
        val s = _state.value
        val name = s.presetName(type) ?: return@launch
        val setup = engine.deletePreset(type, name)
        when (type) {
            PresetType.PRINTER -> {
                val printers = engine.printerList()
                _state.update { it.copy(printers = printers) }
                _state.value.visiblePrinters.firstOrNull()?.let { selectPrinterNow(it.name) }
            }
            PresetType.PRINT -> _state.update { it.copy(setup = setup, print = setup.defaultPrint, overrides = it.overrides - type) }
            PresetType.FILAMENT -> _state.update {
                it.copy(setup = setup, filaments = it.filaments.mapIndexed { i, f -> if (i == it.activeFilament) FilamentSlot(setup.defaultFilament, f.color) else f })
            }
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

    fun importPresets(uris: List<Uri>) = launch(str(R.string.importing_presets)) {
        val files = uris.map { resources.importFile(it, "presets").path }
        val (printers, setup) = engine.importPresets(files)
        _state.update { it.copy(printers = printers, setup = setup) }
        toast(str(R.string.presets_imported, files.size))
    }

    fun exportPreset(type: PresetType, target: Uri) = launch {
        val s = _state.value
        val path = engine.presetFile(type, s.presetName(type) ?: return@launch)
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(target, "wt")!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
        }
        toast(str(R.string.preset_exported))
    }

    /** Per-object/part setting (null removes it). */
    fun setObjectSetting(obj: Int, volume: Int, key: String, value: String?) = sceneOp { engine.setObjectSetting(obj, volume, key, value) }

    fun setRangeSetting(obj: Int, range: Int, key: String, value: String?) = sceneOp {
        val o = _state.value.scene.objects[obj]
        val ranges = o.layerRanges.mapIndexed { i, r ->
            if (i != range) r else r.copy(settings = if (value == null) r.settings - key else r.settings + (key to value))
        }
        engine.setLayerRanges(obj, ranges)
    }

    fun setLayerRanges(obj: Int, ranges: List<LayerRange>) = sceneOp { engine.setLayerRanges(obj, ranges) }

    // --- Scene ----------------------------------------------------------------------------------------

    fun selectPlate(plate: Int) = _state.update { it.copy(activePlate = plate) }

    fun select(selection: Selection?) = _state.update { it.copy(selection = selection, tool = if (selection == null) Tool.None else it.tool) }

    fun openModels(uris: List<Uri>, append: Boolean = true) = launch(str(R.string.loading_model)) {
        if (uris.isEmpty()) return@launch
        val files = uris.map { resources.importFile(it) }
        val gcode = files.firstOrNull { it.name.endsWith(".gcode", true) || it.name.endsWith(".gco", true) }
        if (gcode != null) {
            viewGcodeFile(gcode)
            return@launch
        }
        val project = files.singleOrNull()?.takeIf { it.name.endsWith(".3mf", true) && isProject(it) }
        if (project != null && !append || project != null && _state.value.scene.isEmpty) {
            openProjectFile(project, uris.first())
            return@launch
        }
        val scene = engine.loadModels(files.map { it.path }, append = append || !_state.value.scene.isEmpty, plate = _state.value.activePlate)
        applyScene(scene)
        uris.forEachIndexed { i, u -> rememberRecent(u, files[i].name, false) }
        _state.update { it.copy(selection = Selection(scene.objects.lastIndex), screen = Screen.PREPARE) }
    }

    /** 3MF files with a desktop project config are opened as projects (with plates and settings). */
    private fun isProject(file: File): Boolean = runCatching {
        java.util.zip.ZipFile(file).use { z -> z.getEntry("Metadata/project_settings.config") != null }
    }.getOrDefault(false)

    fun addSample(file: File) = sceneOp(str(R.string.loading_model)) { engine.loadModels(listOf(file.path), true, _state.value.activePlate) }

    fun addPrimitive(shape: String, size: Vec3) = sceneOp { engine.addPrimitive(shape, size, _state.value.activePlate) }

    fun addText(text: String, height: Float, depth: Float) = sceneOp(str(R.string.creating_text)) {
        engine.addText(text, resources.defaultFont(), height, depth, _state.value.activePlate)
    }

    fun addSvg(uri: Uri, width: Float, depth: Float) = sceneOp(str(R.string.loading_model)) {
        engine.addSvg(resources.importFile(uri).path, width, depth, _state.value.activePlate)
    }

    fun moveSelected(dx: Float, dy: Float) {
        val sel = _state.value.selection ?: return
        val inst = _state.value.scene.objects.getOrNull(sel.obj)?.instances?.getOrNull(sel.instance) ?: return
        sceneOp { engine.setTransform(sel.obj, sel.instance, offset = inst.offset + Vec3(dx, dy, 0f)) }
    }

    fun setTransform(offset: Vec3? = null, rotation: Vec3? = null, scale: Vec3? = null, mirror: Vec3? = null) {
        val sel = _state.value.selection ?: return
        sceneOp { engine.setTransform(sel.obj, sel.instance, offset, rotation, scale, mirror) }
    }

    fun deleteSelected() {
        val sel = _state.value.selection ?: return
        _state.update { it.copy(selection = null, tool = Tool.None) }
        sceneOp {
            if (sel.volume >= 0) engine.deleteVolume(sel.obj, sel.volume)
            else if ((_state.value.scene.objects.getOrNull(sel.obj)?.instances?.size ?: 1) > 1) engine.deleteInstance(sel.obj, sel.instance)
            else engine.deleteObject(sel.obj)
        }
    }

    fun deleteAll() = sceneOp {
        var scene = _state.value.scene
        _state.update { it.copy(selection = null) }
        for (i in scene.objects.indices.reversed()) scene = engine.deleteObject(i)
        scene
    }

    fun duplicate(copies: Int) = _state.value.selection?.let { sel -> sceneOp { engine.duplicate(sel.obj, copies) } }
    fun arrange(allPlates: Boolean) = sceneOp(str(R.string.arranging)) { engine.arrange(if (allPlates) -1 else _state.value.activePlate) }
    fun autoOrient() = sceneOp(str(R.string.orienting)) { engine.autoOrient(_state.value.selection?.obj ?: -1) }
    fun split(toParts: Boolean) = _state.value.selection?.let { sel ->
        _state.update { it.copy(selection = if (toParts) sel else null) }
        sceneOp { engine.split(sel.obj, toParts) }
    }
    fun simplify(ratio: Float) = _state.value.selection?.let { sel -> sceneOp(str(R.string.simplifying)) { engine.simplify(sel.obj, ratio) } }
    fun repair() = _state.value.selection?.let { sel -> sceneOp { engine.repair(sel.obj) } }
    fun addVolume(type: VolumeType, shape: String) = _state.value.selection?.let { sel ->
        val size = _state.value.selectedObject?.instances?.firstOrNull()?.size ?: Vec3(10f, 10f, 10f)
        val s = maxOf(5f, minOf(size.x, size.y, size.z) / 2)
        sceneOp { engine.addVolume(sel.obj, type, shape, Vec3(s, s, s)) }
    }
    fun addPlate() = launch {
        applyScene(engine.addPlate())
        _state.update { it.copy(activePlate = it.scene.plates.lastIndex) }
    }
    fun deletePlate(plate: Int) = sceneOp { engine.deletePlate(plate) }
    fun setPlateBedType(plate: Int, bedType: String) = sceneOp { engine.setPlateBedType(plate, bedType) }
    fun undo() = sceneOp { engine.undo() }
    fun redo() = sceneOp { engine.redo() }

    fun setTool(tool: Tool) = launch {
        _state.update { it.copy(tool = tool, measure = if (tool == Tool.Measure) emptyList() else it.measure) }
        if (tool == Tool.LayerHeight) loadLayerProfile { engine.layerProfile(it) }
    }

    fun cut(z: Float, keepUpper: Boolean, keepLower: Boolean, flip: Boolean) {
        val sel = _state.value.selection ?: return
        _state.update { it.copy(selection = null, tool = Tool.None) }
        sceneOp(str(R.string.cutting)) { engine.cut(sel.obj, sel.instance, z, keepUpper, keepLower, flip) }
    }

    /** Handles a tap on the 3D view according to the current tool. */
    fun onViewTap(origin: Vec3, dir: Vec3) = launch {
        val hit = engine.pick(origin, dir)
        val s = _state.value
        when (s.tool) {
            Tool.LayOnFace -> if (hit != null) {
                applyScene(engine.layOnFace(hit.obj, hit.instance, hit.normal))
                _state.update { it.copy(tool = Tool.None, selection = Selection(hit.obj, hit.instance)) }
            }
            Tool.Measure -> if (hit != null) _state.update { st -> st.copy(measure = (st.measure + hit.point).takeLast(2)) }
            else -> _state.update { st ->
                st.copy(selection = hit?.let { Selection(it.obj, it.instance) }, tool = if (hit == null) Tool.None else st.tool)
            }
        }
    }

    private val paintLock = Mutex()
    private val pendingPaint = ArrayDeque<Triple<Vec3, Vec3, Boolean>>()

    /** Paint sample from the 3D view; samples are queued and applied in order on the engine thread. */
    fun paintAt(origin: Vec3, dir: Vec3, camera: Vec3, newStroke: Boolean) {
        val tool = _state.value.tool as? Tool.Paint ?: return
        synchronized(pendingPaint) { pendingPaint.addLast(Triple(origin, dir, newStroke)) }
        if (paintLock.isLocked) return
        launch {
            paintLock.withLock {
                while (true) {
                    val (o, d, stroke) = synchronized(pendingPaint) { pendingPaint.removeFirstOrNull() } ?: break
                    val hit = engine.pick(o, d) ?: continue
                    applyScene(engine.paint(hit, camera, tool.radius, tool.kind, tool.state, stroke))
                }
            }
        }
    }

    fun paintClear(kind: String) = _state.value.selection?.let { sel -> sceneOp { engine.paintClear(sel.obj, kind) } }

    private suspend fun loadLayerProfile(op: suspend (Int) -> Pair<Scene, LayerProfile>) {
        val sel = _state.value.selection ?: return
        syncSelection()
        val (scene, profile) = op(sel.obj)
        applyScene(scene)
        _state.update { it.copy(layerProfile = profile) }
    }

    fun layerAdaptive(quality: Float) = launch { loadLayerProfile { engine.layerAdaptive(it, quality) } }
    fun layerSmooth(radius: Int, keepMin: Boolean) = launch { loadLayerProfile { engine.layerSmooth(it, radius, keepMin) } }
    fun layerAdjust(z: Float, delta: Float, band: Float) = launch { loadLayerProfile { engine.layerAdjust(it, z, delta, band) } }
    fun layerReset() = launch { loadLayerProfile { engine.layerReset(it) } }

    fun setLayerGcodes(plate: Int, items: List<LayerGcode>) = sceneOp { engine.setLayerGcodes(plate, items) }
    fun setWipeTower(plate: Int, pos: Pair<Float, Float>?) = sceneOp { engine.setWipeTower(plate, pos) }

    /** Fills the process's flushing matrix from the filament colours (like the desktop's auto-calc). */
    fun autoFlushMatrix() = launch {
        syncSelection()
        val m = engine.flushMatrix()
        setOption(PresetType.PRINT, "flush_volumes_matrix", m.joinToString(",") { it.toInt().toString() })
    }

    fun startCalibration(type: String, params: Map<String, Any?>) = launch(str(R.string.preparing_calibration)) {
        syncSelection()
        val (scene, calib) = engine.calibStart(type, params)
        applyScene(scene)
        _state.update { it.copy(calibration = calib, selection = null, screen = Screen.PREPARE, projectName = calib.name) }
    }

    fun stopCalibration() = launch {
        applyScene(engine.calibStop())
        _state.update { it.copy(calibration = null) }
    }

    // --- Slicing and preview -----------------------------------------------------------------------------

    fun slice(plate: Int = _state.value.activePlate) = launch {
        if (_state.value.isSlicing) return@launch
        syncSelection()
        _state.update { it.copy(slice = SliceStatus.Running(plate, 0, str(R.string.preparing))) }
        c.sliceProgress.value = SliceProgress(0, str(R.string.preparing))
        runCatching { SliceService.start(app) }
        try {
            val result = engine.slice(plate) { p, t ->
                _state.update { it.copy(slice = SliceStatus.Running(plate, p, t)) }
                c.sliceProgress.value = SliceProgress(p, t)
            }
            _state.update {
                it.copy(results = it.results + (plate to result), previewPlate = plate, external = null, screen = Screen.PREVIEW,
                    preview = it.preview.copy(layerLow = 0, layerHigh = result.layers.lastIndex, moveEnd = null), upload = null)
            }
        } finally {
            c.sliceProgress.value = null
            _state.update { it.copy(slice = SliceStatus.Idle) }
        }
    }

    /** Slices every non-empty plate one after the other. */
    fun sliceAll() = launch {
        for (p in _state.value.scene.plates.indices) {
            if (_state.value.scene.objectsOn(p).isEmpty()) continue
            slice(p).join()
            if (_state.value.error != null) break
        }
    }

    fun cancelSlice() = engine.cancelSlicing()

    private suspend fun viewGcodeFile(file: File) {
        _state.update { it.copy(slice = SliceStatus.Running(-1, 0, str(R.string.reading_gcode))) }
        try {
            val result = engine.viewGcode(file.path) { p, t -> _state.update { it.copy(slice = SliceStatus.Running(-1, p, t)) } }
            _state.update {
                it.copy(external = result, screen = Screen.PREVIEW, preview = it.preview.copy(layerLow = 0, layerHigh = result.layers.lastIndex, moveEnd = null))
            }
        } finally {
            _state.update { it.copy(slice = SliceStatus.Idle) }
        }
    }

    fun closeExternal() = _state.update { it.copy(external = null) }

    fun setPreviewPlate(plate: Int) = _state.update { s ->
        val r = s.results[plate]
        s.copy(previewPlate = plate, external = null, preview = s.preview.copy(layerLow = 0, layerHigh = (r?.layers?.lastIndex ?: 0), moveEnd = null))
    }

    fun updatePreview(transform: (PreviewUi) -> PreviewUi) = _state.update { it.copy(preview = transform(it.preview)) }

    // --- Files ----------------------------------------------------------------------------------------------

    fun gcodeFileName(plate: Int = _state.value.previewPlate): String {
        val s = _state.value
        val base = s.projectName ?: s.scene.objectsOn(plate).firstOrNull()?.name?.substringBeforeLast('.') ?: "plate"
        return if (s.scene.plates.size > 1) "${base}_plate${plate + 1}.gcode" else "$base.gcode"
    }

    fun saveGcode(target: Uri) = launch(str(R.string.saving)) {
        val r = _state.value.shownResult ?: return@launch
        copyTo(File(r.gcodeFile), target)
        toast(str(R.string.saved))
    }

    /** Saves the preview plate as a sliced-plate archive (.gcode.3mf, as Bambu printers and the desktop use). */
    fun saveGcode3mf(target: Uri) = launch(str(R.string.saving)) {
        val s = _state.value
        val r = s.shownResult ?: return@launch
        val tmp = File(app.cacheDir, "out/export.gcode.3mf")
        engine.exportGcode3mf(s.previewPlate, r.gcodeFile, tmp.path)
        copyTo(tmp, target)
        toast(str(R.string.saved))
    }

    fun saveProject(target: Uri) = launch(str(R.string.saving_project)) {
        syncSelection()
        val tmp = File(app.cacheDir, "out/project.3mf")
        engine.saveProject(tmp.path)
        copyTo(tmp, target)
        val name = resources.displayName(target).removeSuffix(".3mf")
        rememberRecent(target, "$name.3mf", true)
        _state.update { it.copy(projectName = name, projectDirty = false) }
        toast(str(R.string.project_saved))
    }

    fun openProject(uri: Uri) = launch(str(R.string.opening_project)) { openProjectFile(resources.importFile(uri), uri) }

    private suspend fun openProjectFile(file: File, uri: Uri) {
        val (scene, info) = engine.loadProject(file.path)
        val s = _state.value
        // Use the project's presets where they exist here, with its setting changes on top.
        val printer = s.printers.firstOrNull { it.name == info.printer }?.name ?: s.printer
        val filaments = info.filaments.mapIndexed { i, name ->
            FilamentSlot(name, info.filamentColors.getOrNull(i)?.ifBlank { null }, info.filamentOverrides.getOrNull(i).orEmpty())
        }
        if (printer != null) selectPrinterNow(printer, info.print, filaments.ifEmpty { null })
        _state.update { st ->
            val slots = if (filaments.isEmpty()) st.filaments else filaments.map { f -> if (st.setup?.filaments?.any { it.name == f.preset } == true) f else f.copy(preset = st.filaments.first().preset) }
            st.copy(filaments = slots, overrides = if (info.printOverrides.isEmpty()) st.overrides else st.overrides + (PresetType.PRINT to info.printOverrides))
        }
        refreshPresetValues(listOf(PresetType.FILAMENT, PresetType.PRINT))
        applyScene(scene, dirty = false)
        rememberRecent(uri, file.name, true)
        _state.update { it.copy(projectName = file.name.removeSuffix(".3mf"), projectDirty = false, selection = null, screen = Screen.PREPARE) }
    }

    fun newProject() = launch {
        deleteAll().join()
        if (_state.value.calibration != null) applyScene(engine.calibStop())
        _state.update { it.copy(projectName = null, projectDirty = false, calibration = null, results = emptyMap(), external = null) }
    }

    fun exportStl(target: Uri, plate: Int) = launch(str(R.string.saving)) {
        val tmp = File(app.cacheDir, "out/export.stl")
        engine.exportStl(tmp.path, plate)
        copyTo(tmp, target)
        toast(str(R.string.saved))
    }

    fun openRecent(recent: RecentFile) {
        val uri = Uri.parse(recent.uri)
        if (recent.isProject) openProject(uri) else openModels(listOf(uri))
    }

    private fun rememberRecent(uri: Uri, name: String, project: Boolean) {
        // Keep access to documents across restarts where the provider allows it.
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        settings.addRecent(RecentFile(uri.toString(), name, project, System.currentTimeMillis()))
        _state.update { it.copy(recents = settings.recents) }
    }

    private suspend fun copyTo(file: File, target: Uri) = withContext(Dispatchers.IO) {
        app.contentResolver.openOutputStream(target, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    // --- Printer connection -------------------------------------------------------------------------------------

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

    suspend fun testConnection(connection: PrinterConnection): String = withContext(Dispatchers.IO) {
        val host = PrintHost.create(connection)
        try { host.test() } finally { host.close() }
    }

    private var discoveryJob: Job? = null

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        _state.update { it.copy(discovered = emptyList(), discovering = true) }
        discoveryJob = viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                Discovery(app).scan().collect { p -> _state.update { it.copy(discovered = it.discovered + p) } }
            }
            _state.update { it.copy(discovering = false) }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        _state.update { it.copy(discovering = false) }
    }

    /** Sends the shown G-code to the connected printer, optionally starting the print. */
    fun upload(startPrint: Boolean) = launch {
        val s = _state.value
        val result = s.shownResult ?: return@launch
        val connection = s.connection ?: return@launch
        if (s.upload is UploadState.Running) return@launch
        _state.update { it.copy(upload = UploadState.Running(0f, startPrint)) }
        try {
            // Bambu printers print .gcode.3mf archives.
            val (file, name) = if (connection.type == HostType.BAMBU && !result.external) {
                val out = File(app.cacheDir, "out/upload.gcode.3mf")
                engine.exportGcode3mf(s.previewPlate, result.gcodeFile, out.path)
                out to gcodeFileName().removeSuffix(".gcode") + ".gcode.3mf"
            } else {
                File(result.gcodeFile) to gcodeFileName()
            }
            withContext(Dispatchers.IO) {
                val host = PrintHost.create(connection)
                try {
                    host.upload(file, name, startPrint) { p -> _state.update { it.copy(upload = UploadState.Running(p, startPrint)) } }
                } finally {
                    host.close()
                }
            }
            _state.update { it.copy(upload = UploadState.Done(str(if (startPrint) R.string.print_started else R.string.sent_to_printer))) }
            if (startPrint) runCatching { PrintMonitorService.start(app, s.printer!!) }
        } catch (e: Exception) {
            _state.update { it.copy(upload = null) }
            throw e
        }
    }

    fun monitorPrinter() = _state.value.printer?.let { runCatching { PrintMonitorService.start(app, it) } }

    fun controlJob(action: PrintHost.JobAction) = launch {
        val connection = _state.value.connection ?: return@launch
        withContext(Dispatchers.IO) {
            val host = PrintHost.create(connection)
            try { host.control(action) } finally { host.close() }
        }
    }

    /** Refreshes the printer status once (device tab). */
    fun refreshStatus() = launch {
        val connection = _state.value.connection ?: return@launch
        val status = withContext(Dispatchers.IO) {
            val host = PrintHost.create(connection)
            try { runCatching { host.status() }.getOrNull() } finally { host.close() }
        }
        c.printerStatus.value = status
    }

    fun sampleModels() = resources.sampleModels()

    /** Recent files as JSON for tests. */
    @Suppress("unused")
    private fun recentsJson() = JSONArray(settings.recents.map { it.name })

    /** Waits until startup finished (debug hooks). */
    suspend fun awaitReady() = _state.first { it.phase == Phase.READY }
}
