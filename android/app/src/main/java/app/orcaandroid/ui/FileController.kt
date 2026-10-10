package app.orcaandroid.ui

import android.content.Intent
import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.FilamentSlot
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.RecentFile
import app.orcaandroid.net.ModelDownloads
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opening models, projects and G-code, saving and exporting, and the recent files. */
class FileController(
    private val store: Store,
    private val presets: PresetController,
    private val scene: SceneController,
    private val slicing: SliceController,
) {
    private val engine = store.engine
    private val settings = store.settings
    private val resources = store.resources
    private val outDir get() = File(store.app.cacheDir, "out").apply { mkdirs() }

    /** Opens models (appended to the plate), a project (replacing the scene) or a G-code file (preview). */
    fun openModels(uris: List<Uri>, append: Boolean = true) = store.launch(store.str(R.string.loading_model)) {
        if (uris.isEmpty()) return@launch
        val files = uris.map { resources.importFile(it) }
        files.firstOrNull { it.name.endsWith(".gcode", true) || it.name.endsWith(".gco", true) }?.let {
            slicing.viewGcodeFile(it)
            return@launch
        }
        val project = files.singleOrNull()?.takeIf { it.name.endsWith(".3mf", true) && isProject(it) }
        if (project != null && (!append || store.value.scene.isEmpty)) {
            openProjectFile(project, uris.first())
            return@launch
        }
        val s = engine.loadModels(files.map { it.path }, append = append || !store.value.scene.isEmpty, plate = store.value.activePlate)
        store.applyScene(s)
        uris.forEachIndexed { i, u -> rememberRecent(u, files[i].name, false) }
        store.update { it.copy(selection = Selection(s.objects.lastIndex), multiSelection = emptyList(), screen = Screen.PREPARE) }
    }

    /**
     * Opens a file a model site hands over: [fetch] downloads or saves it (off the main thread);
     * a zip opens the model files inside it.
     */
    fun openDownloaded(busy: String, fetch: () -> File) = store.launch(busy) {
        val files = withContext(Dispatchers.IO) { ModelDownloads.modelFiles(fetch()) }
        if (files.isEmpty()) store.toast(store.str(R.string.models_no_files))
        else openModels(files.map { Uri.fromFile(it) })
    }

    /** 3MF files with a desktop project config are opened as projects (with plates and settings). */
    private fun isProject(file: File): Boolean = runCatching {
        ZipFile(file).use { z -> z.getEntry("Metadata/project_settings.config") != null }
    }.getOrDefault(false)

    fun openProject(uri: Uri) = store.launch(store.str(R.string.opening_project)) { openProjectFile(resources.importFile(uri), uri) }

    private suspend fun openProjectFile(file: File, uri: Uri?) {
        val (loaded, info) = engine.loadProject(file.path)
        val s = store.value
        // Use the project's presets where they exist here, with its setting changes on top.
        val printer = s.printers.firstOrNull { it.name == info.printer }?.name ?: s.printer
        val filaments = PresetController.withColors(info.filaments.mapIndexed { i, name ->
            FilamentSlot(name, info.filamentColors.getOrNull(i)?.ifBlank { null }, info.filamentOverrides.getOrNull(i).orEmpty())
        })
        if (printer != null) presets.selectPrinterNow(printer, info.print, filaments.ifEmpty { null })
        store.update { st ->
            val slots = if (filaments.isEmpty()) st.filaments
            else filaments.map { f -> if (st.setup?.filaments?.any { it.name == f.preset } == true) f else f.copy(preset = st.filaments.first().preset) }
            st.copy(filaments = slots, overrides = if (info.printOverrides.isEmpty()) st.overrides else st.overrides + (PresetType.PRINT to info.printOverrides))
        }
        presets.refreshPresetValues(listOf(PresetType.FILAMENT, PresetType.PRINT))
        store.applyScene(loaded, dirty = false)
        uri?.let { rememberRecent(it, file.name, true) }
        store.update { it.copy(projectName = file.name.removeSuffix(".3mf"), projectDirty = false, selection = null, multiSelection = emptyList(), screen = Screen.PREPARE) }
    }

    // --- Session: the app comes back as it was left, even after Android ended the process ---------------

    private val sessionDir get() = File(store.app.filesDir, "session").apply { mkdirs() }
    private val sessionProject get() = File(sessionDir, "session.3mf")
    private val sessionInfo get() = File(sessionDir, "session.json")

    /** Saves the scene, presets and setting changes as a project (called when the app goes to the background). */
    suspend fun saveSession() {
        val s = store.value
        if (s.phase != Phase.READY) return
        // A calibration test cannot be resumed from a project; an empty plate needs no session.
        if (s.scene.isEmpty || s.calibration != null) {
            sessionProject.delete(); sessionInfo.delete()
            return
        }
        presets.syncSelection()
        val tmp = File(sessionDir, "session.tmp.3mf")
        engine.saveProject(tmp.path)
        withContext(Dispatchers.IO) {
            tmp.renameTo(sessionProject)
            sessionInfo.writeText(JSONObject().apply {
                put("projectName", s.projectName ?: "")
                put("projectDirty", s.projectDirty)
                put("activePlate", s.activePlate)
            }.toString())
        }
    }

    /** Restores the last session if the engine has no scene (a fresh start of the process). */
    suspend fun restoreSession() {
        if (!store.value.scene.isEmpty || !sessionProject.exists()) return
        val info = runCatching { JSONObject(sessionInfo.readText()) }.getOrNull()
        openProjectFile(sessionProject, null)
        store.update {
            it.copy(
                projectName = info?.optString("projectName")?.ifBlank { null },
                projectDirty = info?.optBoolean("projectDirty") ?: true,
                activePlate = (info?.optInt("activePlate") ?: 0).coerceIn(0, (it.scene.plates.size - 1).coerceAtLeast(0)),
            )
        }
    }

    fun newProject() = store.launch {
        scene.deleteAll()?.join()
        if (store.value.calibration != null) store.applyScene(engine.calibStop())
        store.update { it.copy(projectName = null, projectDirty = false, calibration = null, results = emptyMap(), external = null) }
    }

    fun saveProject(target: Uri) = store.launch(store.str(R.string.saving_project)) {
        presets.syncSelection()
        val tmp = File(outDir, "project.3mf")
        engine.saveProject(tmp.path)
        copyTo(tmp, target)
        val name = resources.displayName(target).removeSuffix(".3mf")
        rememberRecent(target, "$name.3mf", true)
        store.update { it.copy(projectName = name, projectDirty = false) }
        store.toast(store.str(R.string.project_saved))
    }

    fun exportStl(target: Uri, plate: Int) = store.launch(store.str(R.string.saving)) {
        val tmp = File(outDir, "export.stl")
        engine.exportStl(tmp.path, plate)
        copyTo(tmp, target)
        store.toast(store.str(R.string.saved))
    }

    /** File name for the G-code of [plate]: project or first object name, with the plate if there are several. */
    fun gcodeFileName(plate: Int = store.value.previewPlate): String {
        val s = store.value
        val base = s.projectName ?: s.scene.objectsOn(plate).firstOrNull()?.name?.substringBeforeLast('.') ?: "plate"
        return if (s.scene.plates.size > 1) "${base}_plate${plate + 1}.gcode" else "$base.gcode"
    }

    fun saveGcode(target: Uri) = store.launch(store.str(R.string.saving)) {
        val r = store.value.shownResult ?: return@launch
        copyTo(File(r.gcodeFile), target)
        store.toast(store.str(R.string.saved))
    }

    /** Saves the preview plate as a sliced-plate archive (.gcode.3mf, as Bambu printers and the desktop use). */
    fun saveGcode3mf(target: Uri) = store.launch(store.str(R.string.saving)) {
        val archive = exportGcode3mf() ?: return@launch
        copyTo(archive, target)
        store.toast(store.str(R.string.saved))
    }

    /** Writes the shown slice result as .gcode.3mf into the cache; null without a result. */
    suspend fun exportGcode3mf(): File? {
        val s = store.value
        val r = s.shownResult ?: return null
        return File(outDir, "export.gcode.3mf").also { engine.exportGcode3mf(s.previewPlate, r.gcodeFile, it.path) }
    }

    fun openRecent(recent: RecentFile) {
        val uri = Uri.parse(recent.uri)
        if (recent.isProject) openProject(uri) else openModels(listOf(uri))
    }

    private fun rememberRecent(uri: Uri, name: String, project: Boolean) {
        // Keep access to documents across restarts where the provider allows it.
        runCatching { store.app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        settings.addRecent(RecentFile(uri.toString(), name, project, System.currentTimeMillis()))
        store.update { it.copy(recents = settings.recents) }
    }

    private suspend fun copyTo(file: File, target: Uri) = withContext(Dispatchers.IO) {
        store.app.contentResolver.openOutputStream(target, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }
}
