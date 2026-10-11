package app.orcaandroid.ui

import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.Vec3
import app.orcaandroid.scad.ScadClient
import app.orcaandroid.scad.ScadExamples
import app.orcaandroid.scad.ScadParam
import app.orcaandroid.scad.ScadRender
import app.orcaandroid.scad.ScadUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** The OpenSCAD editor beside the 3D view (null in [UiState.scad] while closed). */
data class ScadEditorState(
    /** Shown name, also the name of the object on the plate. */
    val name: String,
    /** Path include / use resolve against: the opened file, else the draft. */
    val file: String,
    /** Source as last synced from the editor. */
    val source: String,
    /** Bumped when a different source is loaded, so the editor replaces its text. */
    val loadId: Int = 0,
    val params: List<ScadParam> = emptyList(),
    /** Customizer values that differ from the parameters' defaults. */
    val values: Map<String, Any> = emptyMap(),
    /** The source doesn't parse (the Customizer keeps the last good parameters). */
    val parseError: Boolean = false,
    val log: String = "",
    val status: String? = null,
    val statusError: Boolean = false,
    val rendering: Boolean = false,
    /** Name of the object this editor put on the plate; Render replaces it. */
    val onPlate: String? = null,
    /** Customizer changes re-render the object on the plate. */
    val autoRender: Boolean = true,
) {
    fun value(p: ScadParam): Any = values[p.name] ?: p.initial
}

/**
 * OpenSCAD modelling: edits a .scad source with its Customizer parameters and renders it (in the
 * ":scad" process, see [ScadClient]) onto the plate. The draft survives closing the editor and the
 * app (files/openscad/draft.*).
 */
class ScadController(private val store: Store) {
    private val engine = store.engine
    private val client = ScadClient(store.app)
    private val dir get() = File(store.app.filesDir, "openscad").apply { mkdirs() }
    private val draftFile get() = File(dir, "draft.scad")
    private val draftMeta get() = File(dir, "draft.json")
    private var paramsJob: Job? = null
    private var draftJob: Job? = null
    private var renderJob: Job? = null
    private var autoJob: Job? = null
    private var renderAgain = false

    private val st get() = store.value.scad

    private fun set(transform: (ScadEditorState) -> ScadEditorState) =
        store.update { s -> s.copy(scad = s.scad?.let(transform)) }

    /** Opens the editor on the saved draft (or the first example). */
    fun open() = store.launch {
        if (st != null) return@launch
        val (source, meta) = withContext(Dispatchers.IO) {
            (draftFile.takeIf { it.isFile }?.readText() ?: ScadExamples.BOX.source) to
                runCatching { JSONObject(draftMeta.readText()) }.getOrNull()
        }
        val file = meta?.optString("file")?.takeIf { it.isNotEmpty() && File(it).isFile } ?: draftFile.path
        store.update {
            it.copy(scad = ScadEditorState(
                name = meta?.optString("name")?.takeIf { n -> n.isNotEmpty() } ?: ScadExamples.BOX.file,
                file = file, source = source,
                values = ScadParam.decode(meta?.optString("values")),
                onPlate = meta?.optString("onPlate")?.takeIf { n -> n.isNotEmpty() },
                autoRender = meta?.optBoolean("autoRender", true) ?: true,
            ), screen = Screen.PREPARE)
        }
        refreshParams(0)
    }

    /** Opens a .scad file (from the file picker, a download or a model site's zip). */
    fun openFile(file: File) = store.launch {
        val source = withContext(Dispatchers.IO) { file.readText() }
        if (st == null) open().join()
        load(file.name, file.path, source)
    }

    fun openUri(uri: Uri) = store.launch {
        openFile(store.resources.importFile(uri, "openscad")).join()
    }

    fun loadExample(example: ScadExamples.Example) = load(example.file, draftFile.path, example.source)

    /** A new design from the box template. */
    fun new() = load("untitled.scad", draftFile.path, ScadExamples.BOX.source)

    private fun load(name: String, file: String, source: String) {
        set { it.copy(name = name, file = file, source = source, loadId = it.loadId + 1, values = emptyMap(),
            params = emptyList(), log = "", status = null, onPlate = null) }
        refreshParams(0)
        saveDraft()
    }

    fun close() {
        renderJob?.cancel()
        saveDraft(now = true)
        store.update { it.copy(scad = null) }
        client.release()
    }

    /** The editor's text changed. */
    fun edit(source: String) {
        if (st?.source == source) return
        set { it.copy(source = source) }
        refreshParams(600)
        saveDraft()
    }

    fun setValue(p: ScadParam, value: Any) {
        if (st?.value(p) == value) return
        set { s -> s.copy(values = if (value == p.initial) s.values - p.name else s.values + (p.name to value)) }
        saveDraft()
        autoRender()
    }

    fun resetValues() {
        set { it.copy(values = emptyMap()) }
        saveDraft()
        autoRender()
    }

    fun setAutoRender(on: Boolean) {
        set { it.copy(autoRender = on) }
        saveDraft()
    }

    /** After a Customizer change, re-renders the object already on the plate (never adds one). */
    private fun autoRender() {
        val s = st ?: return
        if (!s.autoRender || s.onPlate == null || store.value.scene.objects.none { it.name == s.onPlate }) return
        autoJob?.cancel()
        autoJob = store.scope.launch {
            delay(400)
            if (renderJob?.isActive == true) renderAgain = true else render()
        }
    }

    private fun refreshParams(debounce: Long) {
        paramsJob?.cancel()
        paramsJob = store.scope.launch {
            delay(debounce)
            val s = st ?: return@launch
            val json = try {
                client.parameters(s.source, s.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set { it.copy(status = message(e), statusError = true) }
                return@launch
            }
            if (json == null) { set { it.copy(parseError = true) }; return@launch }
            val params = ScadParam.parse(json)
            set { cur ->
                // Keep values whose parameter still exists with the same kind.
                val kept = cur.values.filter { (k, v) -> params.any { p -> p.name == k && p.initial::class == v::class } }
                cur.copy(params = params, values = kept, parseError = false)
            }
        }
    }

    private fun saveDraft(now: Boolean = false) {
        draftJob?.cancel()
        val write: suspend () -> Unit = {
            val s = st
            if (s != null) withContext(Dispatchers.IO) {
                runCatching {
                    if (s.file == draftFile.path || !File(s.file).isFile || File(s.file).readText() != s.source) {
                        draftFile.writeText(s.source)
                    }
                    draftMeta.writeText(JSONObject()
                        .put("name", s.name)
                        .put("file", if (s.file != draftFile.path && File(s.file).readText() == s.source) s.file else "")
                        .put("values", ScadParam.encode(s.values))
                        .put("onPlate", s.onPlate ?: "")
                        .put("autoRender", s.autoRender)
                        .toString())
                }
            }
        }
        if (now) store.container.appScope.launch { write() }
        else draftJob = store.scope.launch { delay(1500); write() }
    }

    /**
     * Renders the design. The result replaces the object this editor placed before (keeping its
     * position, rotation and scale) unless [asNew].
     */
    fun render(asNew: Boolean = false) {
        if (renderJob?.isActive == true) return
        val s = st ?: return
        renderJob = store.scope.launch {
            set { it.copy(rendering = true, status = store.str(R.string.scad_rendering), statusError = false) }
            try {
                val stem = s.name.substringBeforeLast('.').ifEmpty { "openscad" }
                val out = File(store.app.cacheDir, "openscad/${System.nanoTime()}/$stem.stl")
                val r = client.render(s.source, s.file, ScadParam.encode(s.values), out)
                set { it.copy(log = r.log) }
                val stl = r.stl
                when {
                    !r.ok -> fail(R.string.scad_failed)
                    stl == null && r.dimension == 2 -> fail(R.string.scad_2d)
                    stl == null -> fail(R.string.scad_empty)
                    else -> {
                        val name = place(stl, if (asNew) null else s.onPlate)
                        set { it.copy(onPlate = name, status = summary(r), statusError = false) }
                        saveDraft()
                    }
                }
                out.parentFile?.deleteRecursively()
            } catch (e: CancellationException) {
                set { it.copy(status = store.str(R.string.scad_stopped), statusError = false) }
                throw e
            } catch (e: Exception) {
                set { it.copy(status = message(e), statusError = true) }
            } finally {
                set { it.copy(rendering = false) }
                if (renderAgain) {
                    renderAgain = false
                    store.scope.launch { renderJob?.join(); render() }
                }
            }
        }
    }

    fun stop() {
        renderAgain = false
        renderJob?.cancel()
    }

    private fun fail(res: Int) = set { it.copy(status = store.str(res), statusError = true) }

    private fun summary(r: ScadRender) =
        store.str(R.string.scad_rendered, "%,d".format(r.triangles), "%.1f".format(r.millis / 1000.0))

    private fun message(e: Exception) = when (e) {
        is ScadUnavailable -> if (e.message?.contains("does not include") == true) store.str(R.string.scad_not_in_build) else e.message.orEmpty()
        else -> e.message ?: e.toString()
    }

    /** Adds the mesh to the plate, replacing the object named [replace] if it is still there; returns its name. */
    private suspend fun place(stl: File, replace: String?): String {
        val scene = store.value.scene
        val oldIndex = replace?.let { n -> scene.objects.indexOfLast { it.name == n } }?.takeIf { it >= 0 }
        val old = oldIndex?.let { scene.objects[it].instances.firstOrNull() }
        var s = engine.loadModels(listOf(stl.path), append = true, plate = old?.plate ?: store.value.activePlate)
        if (oldIndex != null && old != null) {
            s = engine.deleteObject(oldIndex)
            val placed = s.objects.last().instances.first()
            s = engine.setTransform(s.objects.lastIndex, 0, offset = Vec3(old.offset.x, old.offset.y, placed.offset.z),
                rotation = old.rotation, scale = old.scale, mirror = old.mirror)
        }
        store.applyScene(s)
        store.update { it.copy(selection = Selection(s.objects.lastIndex), multiSelection = emptyList()) }
        return s.objects.last().name
    }

    /** Writes the source to a file the user picked. */
    fun save(target: Uri) = store.launch {
        val s = st ?: return@launch
        withContext(Dispatchers.IO) {
            store.app.contentResolver.openOutputStream(target, "wt")!!.use { it.write(s.source.toByteArray()) }
        }
        val name = store.resources.displayName(target)
        set { it.copy(name = name) }
        saveDraft()
        store.toast(store.str(R.string.saved))
    }
}
