package app.orcaandroid.ui

import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.AppBackup
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.core.OptionDef
import app.orcaandroid.core.OrcaException
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.SettingsGroup
import app.orcaandroid.core.SettingsPage
import app.orcaandroid.core.map
import app.orcaandroid.core.printerKey
import app.orcaandroid.net.BambuAccount
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val setup = engine.selectPrinter(name)
        settings.lastPrinter = name
        // A user printer preset shares the connection and loaded filaments of the system preset it inherits.
        val base = store.value.printers.firstOrNull { it.name == name }?.base.orEmpty()
        settings.setPrinterBase(name, base)
        val p = print?.takeIf { n -> setup.prints.any { it.name == n } }
            ?: (settings.lastPrint(name) ?: base.takeIf { it.isNotEmpty() }?.let(settings::lastPrint))?.takeIf { n -> setup.prints.any { it.name == n } }
            ?: setup.defaultPrint
        val fils = withColors((filaments ?: settings.lastFilaments(name)).filter { f -> setup.filaments.any { it.name == f.preset } }
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

    private suspend fun refreshOptionStates() {
        runCatching { engine.optionStates() }.onSuccess { st -> store.update { it.copy(optionStates = st) } }
    }

    fun selectPrint(name: String) = store.launch {
        settings.setLastPrint(store.value.printer ?: return@launch, name)
        store.update { it.copy(print = name, overrides = it.overrides - PresetType.PRINT, results = emptyMap()) }
        refreshPresetValues(listOf(PresetType.PRINT))
    }

    // --- Filament slots ------------------------------------------------------------------------------

    fun setFilament(slot: Int, name: String) = updateFilaments { list -> list.mapIndexed { i, f -> if (i == slot) FilamentSlot(name, f.color) else f } }
    fun addFilament() = updateFilaments { list -> list + FilamentSlot(list.last().preset, nextColor(list.size)) }
    fun removeFilament(slot: Int) = updateFilaments { list -> if (list.size <= 1) list else list.filterIndexed { i, _ -> i != slot } }
    fun setFilamentColor(slot: Int, color: String) = updateFilaments { list -> list.mapIndexed { i, f -> if (i == slot) f.copy(color = color) else f } }

    fun setActiveFilament(slot: Int) = store.launch {
        store.update { it.copy(activeFilament = slot.coerceIn(0, it.filaments.size - 1)) }
        refreshPresetValues(listOf(PresetType.FILAMENT))
    }

    private fun updateFilaments(transform: (List<FilamentSlot>) -> List<FilamentSlot>) = store.launch {
        val s = store.value
        val list = transform(s.filaments)
        settings.setLastFilaments(s.printer ?: return@launch, list)
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
        updateFilaments { withColors(slots) }
        store.toast(store.str(R.string.sync_filaments_done, slots.size) +
            unknown.joinToString("") { (tray, why) -> "\n$tray: $why" })
    }

    /**
     * Loads the presets saved in the signed-in Bambu account, like the desktop's cloud sync, as
     * user presets. Newer cloud versions replace local copies; presets made only in the app stay.
     */
    fun syncCloudPresets() = store.launch(store.str(R.string.syncing_cloud_presets)) {
        val version = engine.vendorVersion("BBL")
        loadCloudPresets(R.string.cloud_presets_synced) { BambuAccount.cloudPresets(store.app, version) }
    }

    /** The same for the presets synced to the signed-in Orca Cloud account (pull only). */
    fun syncOrcaCloudPresets() = store.launch(store.str(R.string.syncing_orca_cloud_presets)) {
        loadCloudPresets(R.string.orca_cloud_presets_synced) { app.orcaandroid.net.OrcaCloud.cloudPresets(store.app) }
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
            append(store.str(R.string.orca_cloud_presets_uploaded, done))
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
        store.toast(store.str(doneMessage, count))
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
                SettingsGroup(group.getString("group"), group.getJSONArray("options").map { it as String })
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
        store.update { s ->
            if (type == PresetType.FILAMENT) {
                s.copy(filaments = s.filaments.mapIndexed { i, f -> if (i == s.activeFilament) f.copy(overrides = edit(f.overrides)) else f }, results = emptyMap())
            } else {
                s.copy(overrides = s.overrides + (type to edit(s.overrides[type].orEmpty())), results = emptyMap())
            }
        }
        syncSelection()
        refreshOptionStates()
    }

    fun savePresetAs(type: PresetType, name: String) = store.launch(store.str(R.string.saving_preset)) {
        val s = store.value
        val base = s.presetName(type) ?: return@launch
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
            PresetType.FILAMENT -> store.update {
                it.copy(setup = setup, filaments = it.filaments.mapIndexed { i, f -> if (i == it.activeFilament) FilamentSlot(newName, f.color) else f })
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
                it.copy(setup = setup, filaments = it.filaments.mapIndexed { i, f -> if (i == it.activeFilament) FilamentSlot(setup.defaultFilament, f.color) else f })
            }
        }
        refreshPresetValues(listOf(type))
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
        store.toast(store.str(R.string.presets_imported, files.size))
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
        store.update { it.copy(profileUpdates = updates) }
        if (updates.isEmpty()) store.toast(store.str(R.string.profiles_up_to_date))
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
