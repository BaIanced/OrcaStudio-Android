package app.orcaandroid.ui

import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.AppBackup
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.core.OptionDef
import app.orcaandroid.core.OptionStates
import app.orcaandroid.core.OrcaException
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.SettingsGroup
import app.orcaandroid.core.SettingsPage
import app.orcaandroid.core.TraySource
import app.orcaandroid.core.map
import app.orcaandroid.core.printerKey
import app.orcaandroid.net.BambuAccount
import app.orcaandroid.net.HostType
import app.orcaandroid.ui.prepare.Mix
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Printer selection, presets and their edits, filament slots and the settings editor. */
class PresetController(private val store: Store, private val device: DeviceController) {
    private val engine = store.engine
    private val settings = store.settings
    private val resources = store.resources

    // --- Printers ------------------------------------------------------------------------------------

    fun openPrinterSetup() = store.update { it.copy(showPrinterSetup = true) }
    fun closePrinterSetup() = store.update { it.copy(showPrinterSetup = false) }

    /** Installs the vendors of the chosen printers (and drops unused ones), then activates a new pick. */
    fun applyPrinterSelection(keys: Set<String>) = store.launch(store.str(R.string.installing_profiles)) {
        val s = store.value
        val added = keys - s.selectedPrinters
        val vendorIds = s.vendors.filter { v -> v.models.any { m -> m.nozzles.any { printerKey(m.name, it) in keys } } }.map { it.id }.toSet()
        resources.setInstalledVendors(vendorIds)
        settings.selectedPrinters = keys
        store.update { it.copy(selectedPrinters = keys, showPrinterSetup = false) }
        reloadPresets(added)
        store.update { it.copy(phase = Phase.READY) }
    }

    /** The connected Bambu printer (the selected preset's) reported nozzle [diameter] mm: remembered for its model. */
    fun recordNozzle(diameter: Float) {
        val s = store.value
        val model = s.printerInfo?.model?.takeIf { it.isNotEmpty() } ?: return
        if (s.connection?.type != HostType.BAMBU && s.connection?.type != HostType.BAMBU_SIGNED) return
        if (s.installedNozzles[model] == diameter) return
        val nozzles = s.installedNozzles + (model to diameter)
        settings.installedNozzles = nozzles
        store.update { it.copy(installedNozzles = nozzles) }
    }

    fun setMatchNozzle(on: Boolean) {
        settings.matchNozzle = on
        store.update { it.copy(matchNozzle = on) }
    }

    /** Plate types shown in the pickers and the default of plates without their own ("" = the printer's). */
    fun setPlateTypes(hidden: Set<String>, preferred: String) = store.launch {
        settings.hiddenBedTypes = hidden
        settings.preferredBedType = preferred
        val default = engine.setDefaultBedType(preferred)
        store.update { s -> s.copy(hiddenBedTypes = hidden, preferredBedType = preferred, setup = s.setup?.copy(defaultBedType = default)) }
    }

    /** Makes [bedType] the default of plates without their own type (and keeps it shown in the pickers). */
    suspend fun rememberBedType(bedType: String) {
        if (bedType == settings.preferredBedType) return
        val hidden = store.value.hiddenBedTypes - bedType
        settings.preferredBedType = bedType
        settings.hiddenBedTypes = hidden
        val default = engine.setDefaultBedType(bedType)
        store.update { s -> s.copy(hiddenBedTypes = hidden, preferredBedType = bedType, setup = s.setup?.copy(defaultBedType = default)) }
    }

    fun setHiddenFilamentVendors(vendors: Set<String>) {
        settings.hiddenFilamentVendors = vendors
        store.update { it.copy(hiddenFilamentVendors = vendors) }
    }

    /** Reloads all presets and selects a printer: a newly added one, the last used, or any 0.4 mm one. */
    suspend fun reloadPresets(prefer: Set<String>) {
        store.update { it.copy(busy = store.str(R.string.loading_profiles)) }
        val printers = engine.loadPresets()
        store.update { it.copy(printers = printers, busy = null) }
        val visible = store.value.visiblePrinters
        val printer = visible.filter { it.system && it.key in prefer }.minByOrNull { abs(it.nozzle - 0.4) }
            ?: visible.firstOrNull { it.name == settings.lastPrinter }
            ?: visible.firstOrNull { it.system && abs(it.nozzle - 0.4) < 1e-3 }
            ?: visible.firstOrNull()
        printer?.let { selectPrinterNow(it.name) }
    }

    fun selectPrinter(name: String) = store.launch(store.str(R.string.selecting_printer)) { selectPrinterNow(name) }

    /** Activates printer [name] with [print] and [filaments] if given, else the ones last used with it. */
    suspend fun selectPrinterNow(name: String, print: String? = null, filaments: List<FilamentSlot>? = null) {
        engine.setDefaultBedType(settings.preferredBedType)
        engine.setAllowMixedTemperatures(settings.allowMixedTemps)
        val setup = engine.selectPrinter(name)
        settings.lastPrinter = name
        // A user printer preset shares the connection and loaded filaments of the system preset it inherits.
        // One without a system parent (detached, or made where its parent isn't installed) falls back to the
        // system preset of the same printer model and nozzle, else it would lose them.
        val info = store.value.printers.firstOrNull { it.name == name }
        val base = info?.base?.ifEmpty { null }
            ?: info?.takeIf { !it.system && it.model.isNotEmpty() }?.let { u ->
                store.value.printers.firstOrNull { it.system && it.model == u.model && abs(it.nozzle - u.nozzle) < 1e-3 }?.name
            }.orEmpty()
        settings.setPrinterBase(name, base)
        val p = print?.takeIf { n -> setup.prints.any { it.name == n } }
            ?: (settings.lastPrint(name) ?: base.takeIf { it.isNotEmpty() }?.let(settings::lastPrint))?.takeIf { n -> setup.prints.any { it.name == n } }
            ?: setup.defaultPrint
        // A project (the restored session too) has no AMS tray sources: a slot that still holds the preset the
        // last sync left in it keeps that tray, so picks made right after a restart stick to it. Its surface look too
        // (3MF files don't carry it).
        val saved = settings.lastFilaments(name)
        val given = filaments?.mapIndexed { i, f ->
            val same = saved.getOrNull(i)?.takeIf { it.preset == f.preset }
            f.copy(source = f.source ?: same?.source, look = f.look ?: same?.look)
        }
        val fils = withColors((given ?: saved).filter { f -> setup.filaments.any { it.name == f.preset } }
            .ifEmpty { listOf(FilamentSlot(setup.defaultFilament)) })
        store.update {
            it.copy(printer = name, setup = setup, print = p, filaments = fils, activeFilament = 0, overrides = emptyMap(), results = emptyMap())
        }
        refreshPresetValues(PresetType.entries)
        device.refreshConnection()
        store.applyScene(engine.scene(), dirty = false)
    }

    /** Reads the option values of the selected presets of [types] and pushes the selection to the engine. */
    suspend fun refreshPresetValues(types: Collection<PresetType>) {
        val s = store.value
        val values = s.presetValues.toMutableMap()
        for (type in types) s.presetName(type)?.let { values[type] = engine.presetValues(type, it) }
        store.update { it.copy(presetValues = values) }
        syncSelection()
        refreshOptionStates()
    }

    /** Sends the current preset selection and edits to the engine (needed before slicing etc.). */
    suspend fun syncSelection() {
        val s = store.value
        val print = s.print ?: return
        if (s.filaments.isEmpty()) return
        engine.setSelection(print, s.filaments, s.overrides[PresetType.PRINTER].orEmpty() + s.overrides[PresetType.PRINT].orEmpty())
    }

    /** Greyed-out / hidden options of an object / part / range editor with [settings] (desktop ObjectSettings rules). */
    suspend fun objectOptionStates(settings: Map<String, String>): OptionStates = engine.optionStates(settings)

    private suspend fun refreshOptionStates() {
        runCatching { engine.optionStates() }.onSuccess { st -> store.update { it.copy(optionStates = st) } }
    }

    fun selectPrint(name: String) = store.launch { selectPrintNow(name) }

    // --- Filament slots ------------------------------------------------------------------------------

    fun setFilament(slot: Int, name: String) = store.launch { setFilamentNow(slot, name) }

    /**
     * A preset picked for a slot an AMS sync filled sticks to that tray ([AppSettings.trayPicks]) while the
     * tray reports the same filament; picking the sync's own choice drops it again.
     */
    private fun rememberTrayPick(slot: FilamentSlot?, name: String) {
        val src = slot?.source ?: return
        val picks = settings.trayPicks
        settings.trayPicks = if (name == src.synced) picks - src.key else picks + (src.key to (src.loaded to name))
    }

    fun setAllowMixedTemps(on: Boolean) = store.launch {
        settings.allowMixedTemps = on
        engine.setAllowMixedTemperatures(on)
        store.update { it.copy(allowMixedTemps = on) }
    }

    fun setPreferMyFilaments(on: Boolean) {
        settings.preferMyFilaments = on
        store.update { it.copy(preferMyFilaments = on) }
    }
    fun addFilament() = updateFilaments { list -> list + FilamentSlot(list.last().preset, nextColor(list.size)) }
    fun removeFilament(slot: Int) = updateFilaments { list -> if (list.size <= 1) list else list.filterIndexed { i, _ -> i != slot } }
    fun setFilamentColor(slot: Int, color: String) = updateFilaments { list -> list.mapIndexed { i, f -> if (i == slot) f.copy(color = color) else f } }
    fun setFilamentLook(slot: Int, look: String?) = updateFilaments { list -> list.mapIndexed { i, f -> if (i == slot) f.copy(look = look) else f } }

    /** Makes [slot] a mix of the given (1-based slot, share) parts, or a plain filament again when [parts] is null. */
    fun setMix(slot: Int, parts: List<Pair<Int, Float>>?, sublayers: Boolean) = store.launch {
        val keys = setOf(Mix.IS_MIXED, Mix.COMPONENTS, Mix.RATIOS)
        if (parts != null) {
            val colors = parts.map { (c, _) -> store.value.filaments.getOrNull(c - 1)?.color }
            updateFilamentsNow { list -> list.mapIndexed { i, f -> if (i == slot) f.copy(color = Mix.blend(colors, parts.map { it.second })) else f } }
        }
        editOverridesNow(PresetType.FILAMENT, slot) { cur ->
            if (parts == null) cur - keys else cur + mapOf(
                Mix.IS_MIXED to "1",
                Mix.COMPONENTS to parts.joinToString(",") { it.first.toString() },
                Mix.RATIOS to parts.joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it.second) })
        }
        store.value.printer?.let { settings.setLastFilaments(it, store.value.filaments) }
        if (parts != null) editOverridesNow(PresetType.PRINT, slot) { it + ("enable_mixed_color_sublayer" to if (sublayers) "1" else "0") }
    }

    fun setActiveFilament(slot: Int) = store.launch { setActiveFilamentNow(slot) }

    private fun updateFilaments(transform: (List<FilamentSlot>) -> List<FilamentSlot>) = store.launch { updateFilamentsNow(transform) }

    private suspend fun updateFilamentsNow(transform: (List<FilamentSlot>) -> List<FilamentSlot>) {
        val s = store.value
        val list = transform(s.filaments)
        settings.setLastFilaments(s.printer ?: return, list)
        store.update { it.copy(filaments = list, activeFilament = it.activeFilament.coerceIn(0, list.size - 1), results = emptyMap()) }
        refreshPresetValues(listOf(PresetType.FILAMENT))
    }

    private fun nextColor(i: Int) = SLOT_COLORS[i % SLOT_COLORS.size]

    /**
     * Sets the filament slots to what the connected Bambu printer has loaded (AMS slots, then the
     * external spool), picking presets like the desktop's filament sync (PresetBundle::sync_ams_list).
     */
    fun syncFilamentsFromPrinter() = store.launch(store.str(R.string.syncing_filaments)) {
        val trays = device.loadedFilaments()
        if (trays.isEmpty()) {
            store.toast(store.str(R.string.sync_filaments_none))
            return@launch
        }
        val (slots, unknown) = engine.syncFilaments(trays.map { t ->
            mapOf("filament_id" to t.filamentId, "filament_type" to t.type, "color" to t.color, "colors" to t.colors,
                "color_type" to t.colorType, "ams_id" to t.amsId, "slot_id" to t.slotId, "name" to t.name)
        }, store.value.filaments)
        if (slots.isEmpty()) {
            store.toast(store.str(R.string.sync_filaments_none))
            return@launch
        }
        // One slot per tray, in order. A tray that still reports the filament and colour it had when the user
        // picked a preset for it keeps that pick; a changed tray (another spool: RFID, or set on the printer)
        // takes the sync's preset and the old pick is dropped. Never-picked trays keep the sync's preset.
        val serial = store.value.connection?.serial.orEmpty()
        val available = store.value.setup?.filaments.orEmpty()
        val picks = settings.trayPicks.toMutableMap()
        val result = if (slots.size != trays.size) slots else slots.mapIndexed { i, slot ->
            val t = trays[i]
            val src = TraySource("$serial/${t.amsId}/${t.slotId}", "${t.filamentId} ${t.color}", slot.preset)
            val pick = picks[src.key]
            if (pick != null && pick.first != src.loaded) picks.remove(src.key)
            val keep = pick?.takeIf { it.first == src.loaded && settings.preferMyFilaments && available.any { f -> f.name == it.second } }
            slot.copy(preset = keep?.second ?: slot.preset, source = src)
        }
        settings.trayPicks = picks
        updateFilaments { withColors(result) }
        store.toast(store.plural(R.plurals.sync_filaments_done, slots.size, slots.size) +
            unknown.joinToString("") { (tray, why) -> "\n$tray: $why" })
    }

    /**
     * Loads the presets saved in the signed-in Bambu account, like the desktop's cloud sync, as
     * user presets. Newer cloud versions replace local copies; presets made only in the app stay.
     */
    fun syncCloudPresets() = store.launch(store.str(R.string.syncing_cloud_presets)) {
        val version = engine.vendorVersion("BBL")
        loadCloudPresets(R.plurals.cloud_presets_synced) { BambuAccount.cloudPresets(store.app, version) }
    }

    /** The same for the presets synced to the signed-in Orca Cloud account (pull only). */
    fun syncOrcaCloudPresets() = store.launch(store.str(R.string.syncing_orca_cloud_presets)) {
        loadCloudPresets(R.plurals.orca_cloud_presets_synced) { app.orcaandroid.net.OrcaCloud.cloudPresets(store.app) }
    }

    /**
     * Uploads new and changed user presets to Orca Cloud, like the desktop's sync thread
     * (GUI_App::sync_preset). An update carries the cloud version it was based on; if the cloud has
     * a newer one (409) the preset is left as it is and reported. Deletions are never uploaded.
     */
    fun uploadOrcaCloudPresets() = store.launch(store.str(R.string.uploading_orca_cloud_presets)) {
        val user = withContext(Dispatchers.IO) { app.orcaandroid.net.OrcaCloud.user(store.app) }
            ?: throw OrcaException(store.str(R.string.orca_cloud_sign_in_first))
        val uploads = engine.cloudUploads(user.id)
        var done = 0
        val conflicts = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (i in 0 until uploads.length()) {
            val p = uploads.getJSONObject(i)
            val type = p.getString("type")
            val name = p.getString("name")
            val settingId = p.optString("setting_id")
            val values = p.getJSONObject("values")
            val create = settingId.isEmpty() || p.optString("sync_info") == "create"
            // The desktop drops a setting_id that is only the parent's (base_id) instead of uploading.
            if (!create && values.optString("base_id") == settingId) {
                engine.markUploaded(type, name, "", "", 0)
                continue
            }
            val content = org.json.JSONObject()
            for (key in values.keys()) if (key != "updated_time") content.put(key, values.get(key))
            content.put("name", name)
            val id = if (create) app.orcaandroid.net.OrcaCloud.settingId(name, user.id) else settingId
            val result = withContext(Dispatchers.IO) {
                app.orcaandroid.net.OrcaCloud.pushPreset(store.app, id, name, content, if (create) null else values.optString("updated_time"))
            }
            when (result.http) {
                200 -> { engine.markUploaded(type, name, id, "", result.updatedTime); done++ }
                409 -> conflicts += name
                413 -> { engine.markUploaded(type, name, settingId, "will_not_sync", 0); failed += "$name (> 1 MB)" }
                else -> failed += "$name (HTTP ${result.http})"
            }
        }
        store.toast(buildString {
            append(store.plural(R.plurals.orca_cloud_presets_uploaded, done, done))
            if (conflicts.isNotEmpty()) append('\n').append(store.str(R.string.orca_cloud_upload_conflicts, conflicts.joinToString()))
            if (failed.isNotEmpty()) append("\n✗ ").append(failed.joinToString())
        })
    }

    private suspend fun loadCloudPresets(doneMessage: Int, fetch: () -> org.json.JSONObject) {
        val presets = withContext(Dispatchers.IO) { fetch() }
        val (count, printers) = engine.loadCloudPresets(presets)
        store.update { it.copy(printers = printers) }
        val current = store.value.printer
        if (current != null && printers.any { it.name == current }) {
            val setup = engine.selectPrinter(current)
            store.update { it.copy(setup = setup) }
            refreshPresetValues(PresetType.entries)
        } else {
            store.value.visiblePrinters.firstOrNull()?.let { selectPrinterNow(it.name) }
        }
        store.toast(store.plural(doneMessage, count, count))
    }

    // --- Settings editor -----------------------------------------------------------------------------

    fun openEditor(target: EditorTarget) = store.launch {
        if (target is EditorTarget.Preset && target.type == PresetType.FILAMENT) refreshPresetValues(listOf(PresetType.FILAMENT))
        store.update { it.copy(editor = target) }
    }

    /** Back from a comparison returns to the preset it was opened from; everything else closes the editor. */
    fun closeEditor() = store.update { s ->
        val e = s.editor
        s.copy(editor = if (e is EditorTarget.Compare) EditorTarget.Preset(e.type) else null)
    }

    suspend fun optionDefs(type: PresetType): List<OptionDef> = engine.optionDefs(type)

    suspend fun presetValuesOf(type: PresetType, name: String): Map<String, String> = engine.presetValues(type, name)

    /** Desktop settings layout (pages and groups) of a preset type. */
    fun settingsLayout(type: PresetType): List<SettingsPage> {
        val json = store.app.assets.open("settings_layout.json").bufferedReader().use { it.readText() }
        return JSONObject(json).getJSONArray(type.id).map { p ->
            val page = p as JSONObject
            SettingsPage(page.getString("page"), page.getJSONArray("groups").map { g ->
                val group = g as JSONObject
                val rows = group.optJSONObject("lines")
                SettingsGroup(group.getString("group"), group.getJSONArray("options").map { it as String },
                    rows?.keys()?.asSequence()?.associateWith { rows.getString(it) }.orEmpty())
            })
        }
    }

    /** Sets an option; setting it back to the preset's value drops the edit. */
    fun setOption(type: PresetType, key: String, value: String) = editOverrides(type) { current ->
        val isDefault = store.value.presetValues[type]?.get(key) == value
        if (isDefault) current - key else current + (key to value)
    }

    fun resetOption(type: PresetType, key: String) = editOverrides(type) { it - key }

    fun resetAll(type: PresetType) = editOverrides(type) { emptyMap() }

    /** Applies [edit] to the edits of [type] (filament: of the active slot) and syncs the engine. */
    private fun editOverrides(type: PresetType, edit: (Map<String, String>) -> Map<String, String>) = store.launch {
        editOverridesNow(type, store.value.activeFilament, edit)
    }

    /** [editOverrides] for filament slot [slot], waiting until the engine has the new edits. */
    suspend fun editOverridesNow(type: PresetType, slot: Int, edit: (Map<String, String>) -> Map<String, String>) {
        store.update { s ->
            if (type == PresetType.FILAMENT) {
                s.copy(filaments = s.filaments.mapIndexed { i, f -> if (i == slot) f.copy(overrides = edit(f.overrides)) else f }, results = emptyMap())
            } else {
                s.copy(overrides = s.overrides + (type to edit(s.overrides[type].orEmpty())), results = emptyMap())
            }
        }
        syncSelection()
        refreshOptionStates()
    }

    /** Shows filament slot [slot] in the settings editor (its values become [AppState.presetValues]). */
    suspend fun setActiveFilamentNow(slot: Int) {
        store.update { it.copy(activeFilament = slot.coerceIn(0, it.filaments.size - 1)) }
        refreshPresetValues(listOf(PresetType.FILAMENT))
    }

    /** Selects process preset [name] (its unsaved edits are dropped). */
    suspend fun selectPrintNow(name: String) {
        settings.setLastPrint(store.value.printer ?: return, name)
        store.update { it.copy(print = name, overrides = it.overrides - PresetType.PRINT, results = emptyMap()) }
        refreshPresetValues(listOf(PresetType.PRINT))
    }

    /** Puts filament preset [name] in slot [slot] (its unsaved edits are dropped). */
    suspend fun setFilamentNow(slot: Int, name: String) {
        val s = store.value
        rememberTrayPick(s.filaments.getOrNull(slot), name)
        // A user filament with its own colour recolours the slot, as on the desktop.
        val own = s.setup?.filaments?.firstOrNull { it.name == name }?.color?.ifEmpty { null }
        val list = s.filaments.mapIndexed { i, f -> if (i == slot) FilamentSlot(name, own ?: f.color, source = f.source) else f }
        settings.setLastFilaments(s.printer ?: return, list)
        store.update { it.copy(filaments = list, results = emptyMap()) }
        refreshPresetValues(listOf(PresetType.FILAMENT))
    }

    fun savePresetAs(type: PresetType, name: String) = store.launch(store.str(R.string.saving_preset)) { savePresetNow(type, name) }

    /** Saves the selected preset of [type] (filament: the active slot's) with its edits as user preset [name] and selects it. */
    suspend fun savePresetNow(type: PresetType, name: String) {
        val s = store.value
        val base = s.presetName(type) ?: return
        val newName = name.trim()
        val setup = engine.savePreset(type, base, newName, s.overridesOf(type))
        when (type) {
            PresetType.PRINTER -> {
                val printers = engine.printerList()
                store.update { it.copy(printers = printers, overrides = it.overrides - type) }
                selectPrinterNow(newName, s.print, s.filaments)
            }
            PresetType.PRINT -> {
                s.printer?.let { settings.setLastPrint(it, newName) }
                store.update { it.copy(setup = setup, print = newName, overrides = it.overrides - type) }
            }
            PresetType.FILAMENT -> {
                store.update {
                    it.copy(setup = setup, filaments = it.filaments.mapIndexed { i, f -> if (i == it.activeFilament) FilamentSlot(newName, f.color, source = f.source) else f })
                }
                // Saving counts as picking it for the slot's AMS tray.
                rememberTrayPick(s.filaments.getOrNull(s.activeFilament), newName)
                s.printer?.let { settings.setLastFilaments(it, store.value.filaments) }
            }
        }
        refreshPresetValues(listOf(type))
    }

    fun deleteSelectedPreset(type: PresetType) = store.launch(store.str(R.string.deleting_preset)) {
        val name = store.value.presetName(type) ?: return@launch
        val setup = engine.deletePreset(type, name)
        when (type) {
            PresetType.PRINTER -> {
                val printers = engine.printerList()
                store.update { it.copy(printers = printers) }
                store.value.visiblePrinters.firstOrNull()?.let { selectPrinterNow(it.name) }
            }
            PresetType.PRINT -> store.update { it.copy(setup = setup, print = setup.defaultPrint, overrides = it.overrides - type) }
            PresetType.FILAMENT -> store.update {
                it.copy(setup = setup, filaments = it.filaments.mapIndexed { i, f -> if (i == it.activeFilament) FilamentSlot(setup.defaultFilament, f.color, source = f.source) else f })
            }
        }
        refreshPresetValues(listOf(type))
    }

    /** Deletes user preset [name] of [type] that isn't selected anywhere (the selected one: [deleteSelectedPreset]). */
    suspend fun deleteUnusedPresetNow(type: PresetType, name: String) {
        val setup = engine.deletePreset(type, name)
        if (type == PresetType.PRINTER) {
            val printers = engine.printerList()
            store.update { it.copy(printers = printers) }
        } else {
            store.update { it.copy(setup = setup) }
        }
        syncSelection()
    }

    fun isUserPreset(type: PresetType): Boolean {
        val s = store.value
        val name = s.presetName(type) ?: return false
        return when (type) {
            PresetType.PRINTER -> s.printers.any { it.name == name && !it.system }
            PresetType.PRINT -> s.setup?.prints?.any { it.name == name && !it.system } == true
            PresetType.FILAMENT -> s.setup?.filaments?.any { it.name == name && !it.system } == true
        }
    }

    fun importPresets(uris: List<Uri>) = store.launch(store.str(R.string.importing_presets)) {
        val files = uris.map { resources.importFile(it, "presets").path }
        val (printers, setup) = engine.importPresets(files)
        store.update { it.copy(printers = printers, setup = setup) }
        store.toast(store.app.resources.getQuantityString(R.plurals.presets_imported, files.size, files.size))
    }

    fun exportPreset(type: PresetType, target: Uri) = store.launch {
        val path = engine.presetFile(type, store.value.presetName(type) ?: return@launch)
        withContext(Dispatchers.IO) {
            store.app.contentResolver.openOutputStream(target, "wt")!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
        }
        store.toast(store.str(R.string.preset_exported))
    }

    // --- Backup ----------------------------------------------------------------------------------------

    /** Writes the encrypted backup (settings, connections, user presets, Bambu login) to [target]. */
    fun exportBackup(target: Uri, passphrase: CharArray) = store.launch(store.str(R.string.backup_working)) {
        try {
            withContext(Dispatchers.IO) {
                store.app.contentResolver.openOutputStream(target, "wt")!!.use { AppBackup.export(store.app, it, passphrase) }
            }
        } finally {
            passphrase.fill(' ')
        }
        store.toast(store.str(R.string.backup_saved))
    }

    /** Restores a backup over the current setup, installs its printers' profiles and restarts the app. */
    fun importBackup(source: Uri, passphrase: CharArray) = store.launch(store.str(R.string.backup_working)) {
        try {
            withContext(Dispatchers.IO) {
                store.app.contentResolver.openInputStream(source)!!.use { AppBackup.import(store.app, it, passphrase) }
            }
        } finally {
            passphrase.fill(' ')
        }
        val keys = settings.selectedPrinters
        resources.setInstalledVendors(store.value.vendors.filter { v -> v.models.any { m -> m.nozzles.any { printerKey(m.name, it) in keys } } }.map { it.id }.toSet())
        val app = store.app
        app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    // --- Profile updates -----------------------------------------------------------------------------

    fun checkProfileUpdates() = store.launch(store.str(R.string.checking_updates)) {
        val updates = resources.checkProfileUpdates()
        settings.lastProfileCheck = System.currentTimeMillis()
        store.update { it.copy(profileUpdates = updates) }
        if (updates.isEmpty()) store.toast(store.str(R.string.profiles_up_to_date))
    }

    /** At most once a day, quietly in the background: found updates show as a chip on Prepare. */
    fun autoCheckProfileUpdates() {
        val now = System.currentTimeMillis()
        if (now - settings.lastProfileCheck < 24 * 3600_000L) return
        store.container.appScope.launch {
            val updates = runCatching { resources.checkProfileUpdates() }.getOrNull() ?: return@launch
            settings.lastProfileCheck = now
            store.update { it.copy(profileUpdates = updates) }
        }
    }

    /** Installs the found updates in the background; the app stays usable meanwhile. */
    fun installProfileUpdates() {
        if (store.value.profileUpdateProgress != null) return
        store.launch {
            try {
                for (u in store.value.profileUpdates.orEmpty()) {
                    resources.updateVendor(u.vendor) { done, total ->
                        store.update { it.copy(profileUpdateProgress = store.str(R.string.updating_vendor, u.vendor, done, total)) }
                    }
                }
            } finally {
                store.update { it.copy(profileUpdateProgress = null) }
            }
            store.update { it.copy(profileUpdates = null) }
            reloadPresets(emptySet())
            store.toast(store.str(R.string.profiles_updated))
        }
    }

    companion object {
        private val SLOT_COLORS = listOf("#FF7F27", "#2F7FEF", "#2FBF4F", "#EF3F3F", "#FFFFFF", "#202020", "#FFD700", "#8F3FDF")

        /**
         * Gives every slot an explicit colour, so the plate, the G-code, thumbnails and saved
         * projects all use the colour the app shows (instead of the filament preset's own).
         */
        fun withColors(slots: List<FilamentSlot>): List<FilamentSlot> =
            slots.mapIndexed { i, f -> if (f.color.isNullOrBlank()) f.copy(color = SLOT_COLORS[i % SLOT_COLORS.size]) else f }
    }
}
