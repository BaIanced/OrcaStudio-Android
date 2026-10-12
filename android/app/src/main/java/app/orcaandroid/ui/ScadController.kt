package app.orcaandroid.ui

import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.Scene
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.PreviewMesh
import app.orcaandroid.scad.ScadClient
import app.orcaandroid.scad.ScadDeps
import app.orcaandroid.scad.ScadDesign
import app.orcaandroid.scad.ScadExamples
import app.orcaandroid.scad.ScadParam
import app.orcaandroid.scad.ScadRender
import app.orcaandroid.scad.ScadUnavailable
import app.orcaandroid.scad.ShapeDesign
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
    /** Live preview: edits re-render the preview, Customizer changes also the object on the plate. */
    val autoRender: Boolean = true,
    /** The editor's 3D preview: the last design that rendered. */
    val preview: PreviewMesh? = null,
    /** The preview shows an older version (the current one failed or is being rendered). */
    val previewStale: Boolean = false,
    val previewing: Boolean = false,
    /** Shape-builder design the source is generated from; null = hand-written code. */
    val shapes: ShapeDesign? = null,
    /** Selected shape ids in the builder. */
    val selected: List<Int> = emptyList(),
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
    private var renderAgain = false
    private var previewDelay: Job? = null
    private var previewJob: Job? = null
    private var previewAgain = false
    /** The next preview replaces the object on the plate (a Customizer change). */
    private var pushToPlate = false
    /** The STL [ScadEditorState.preview] was read from and the input it was rendered from. */
    private var previewStl: File? = null
    private var previewKey: String? = null

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
                shapes = meta?.optJSONObject("shapes")?.let { j -> runCatching { ShapeDesign.of(j) }.getOrNull() },
            ), screen = Screen.PREPARE)
        }
        refreshParams(0)
        preview()
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

    /** Opens the design an object was rendered from (kept in the project, see [ScadDesign]). */
    fun editObject(index: Int) = store.launch {
        val obj = store.value.scene.objects.getOrNull(index) ?: return@launch
        val design = ScadDesign.of(obj) ?: return@launch
        if (st == null) open().join()
        // A design with its own include files gets them back in a folder of its own.
        val file = if (design.files.isEmpty()) draftFile.path else withContext(Dispatchers.IO) {
            design.unpack(File(dir, "designs/" + Integer.toHexString(design.encode().hashCode()))).path
        }
        load(design.name, file, design.shapes?.toScad() ?: design.source, design.values, onPlate = obj.name, shapes = design.shapes)
    }

    fun loadExample(example: ScadExamples.Example) = load(example.file, draftFile.path, example.source)

    /** A new design from the box template. */
    fun new() = load("untitled.scad", draftFile.path, ScadExamples.BLANK.source)

    /** A new design built from shapes (no code). */
    fun newShapes() = store.launch {
        if (st == null) open().join()
        val d = ShapeDesign()
        load(store.str(R.string.shape_new_name), draftFile.path, d.toScad(), shapes = d)
    }

    private fun load(name: String, file: String, source: String, values: Map<String, Any> = emptyMap(), onPlate: String? = null,
                     shapes: ShapeDesign? = null) {
        set { it.copy(name = name, file = file, source = source, loadId = it.loadId + 1, values = values,
            params = emptyList(), log = "", status = null, onPlate = onPlate, preview = null, shapes = shapes, selected = emptyList()) }
        refreshParams(0)
        saveDraft()
        pushToPlate = false
        preview()
    }

    fun close() {
        renderJob?.cancel()
        previewDelay?.cancel()
        previewJob?.cancel()
        previewStl?.delete(); previewStl = null; previewKey = null
        saveDraft(now = true)
        store.update { it.copy(scad = null) }
        client.release()
    }

    /**
     * The shape builder changed the design: the code is generated again and previewed. Customizer
     * values are dropped, the dimensions hold them.
     */
    fun setShapes(design: ShapeDesign, selected: List<Int>) {
        val s = st ?: return
        val source = design.toScad()
        // An empty design has nothing to preview: drop the last mesh.
        set { it.copy(shapes = design, selected = selected, source = source, values = emptyMap(),
            preview = if (design.shapes.isEmpty()) null else it.preview) }
        if (s.source == source) return
        refreshParams(300)
        saveDraft()
        // A dimension change is a Customizer-like change: the object on the plate follows.
        pushToPlate = s.shapes?.let { it.dims != design.dims && it.withDims(design.dims) == design } == true
        if (st?.autoRender == true) preview(500)
    }

    fun select(ids: List<Int>) = set { it.copy(selected = ids) }

    /** Turns a shape design into plain code (the shapes are dropped, the code is kept). */
    fun detachShapes() {
        set { it.copy(shapes = null, selected = emptyList(), loadId = it.loadId + 1) }
        saveDraft()
    }

    /** The editor's text changed. */
    fun edit(source: String) {
        if (st?.shapes != null) return
        if (st?.source == source) return
        set { it.copy(source = source) }
        refreshParams(600)
        saveDraft()
        pushToPlate = false
        if (st?.autoRender == true) preview(1200)
    }

    fun setValue(p: ScadParam, value: Any) {
        // Shape designs keep slider values in their dimensions.
        val sd = st?.shapes
        if (sd != null && value is Double && sd.dim(p.name) != null) {
            setShapes(sd.withDims(sd.dims.map { if (it.name == p.name) it.copy(value = value) else it }), st?.selected.orEmpty())
            return
        }
        if (st?.value(p) == value) return
        set { s -> s.copy(values = if (value == p.initial) s.values - p.name else s.values + (p.name to value)) }
        saveDraft()
        onCustomizerChange()
    }

    fun resetValues() {
        set { it.copy(values = emptyMap()) }
        saveDraft()
        onCustomizerChange()
    }

    fun setAutoRender(on: Boolean) {
        set { it.copy(autoRender = on) }
        saveDraft()
        if (on) preview()
    }

    /** Live preview: re-renders the preview, then the object already on the plate (never adds one). */
    private fun onCustomizerChange() {
        if (st?.autoRender != true) return
        pushToPlate = true
        preview(300)
    }

    private fun key(s: ScadEditorState) = s.file + "\u0000" + s.source + "\u0000" + ScadParam.encode(s.values)

    /** Renders the current design into the preview after [debounce] ms (F5). */
    fun preview(debounce: Long = 0) {
        previewDelay?.cancel()
        previewDelay = store.scope.launch {
            delay(debounce)
            // One request at a time: the newest state is previewed when the running one is done.
            if (previewJob?.isActive == true || renderJob?.isActive == true) { previewAgain = true; return@launch }
            runPreview()
        }
    }

    private fun runPreview() {
        val s = st ?: return
        val k = key(s)
        if (k == previewKey && s.preview != null) { set { it.copy(previewStale = false) }; pushPreview(); return }
        previewJob = store.scope.launch {
            set { it.copy(previewing = true, previewStale = it.preview != null) }
            try {
                val out = File(store.app.cacheDir, "openscad/preview/${System.nanoTime()}.stl")
                val r = client.render(s.source, s.file, ScadParam.encode(s.values), out)
                val stl = r.stl
                val mesh = stl?.let { withContext(Dispatchers.IO) { runCatching { PreviewMesh.readStl(it) }.getOrNull() } }
                // Another design was loaded meanwhile: this result is not its preview.
                if (st?.let(::key) != k) {
                    out.delete(); stl?.delete()
                    previewAgain = true
                    return@launch
                }
                if (stl != null && mesh != null) {
                    previewStl?.delete()
                    previewStl = stl
                    previewKey = k
                    set { it.copy(log = r.log, preview = mesh, previewStale = false, status = summary(r), statusError = false) }
                    pushPreview()
                } else {
                    out.delete()
                    val res = when { !r.ok -> R.string.scad_failed; r.dimension == 2 -> R.string.scad_2d; else -> R.string.scad_empty }
                    set { it.copy(log = r.log, previewStale = it.preview != null, status = store.str(res), statusError = true) }
                }
            } catch (e: CancellationException) {
                set { it.copy(status = store.str(R.string.scad_stopped), statusError = false) }
                throw e
            } catch (e: Exception) {
                set { it.copy(status = message(e), statusError = true) }
            } finally {
                set { it.copy(previewing = false) }
                afterRequest()
            }
        }
    }

    /** After a Customizer change, puts the fresh preview on the plate in place of the old object. */
    private fun pushPreview() {
        if (!pushToPlate) return
        pushToPlate = false
        val s = st ?: return
        if (s.onPlate == null || store.value.scene.objects.none { it.name == s.onPlate }) return
        render()
    }

    private fun afterRequest() {
        if (renderAgain) {
            renderAgain = false
            store.scope.launch { renderJob?.join(); previewJob?.join(); render() }
        } else if (previewAgain) {
            previewAgain = false
            preview()
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
                    // The source always goes to the draft; an opened file stays the include<> base.
                    draftFile.writeText(s.source)
                    draftMeta.writeText(JSONObject()
                        .put("name", s.name)
                        .put("file", if (s.file != draftFile.path) s.file else "")
                        .put("values", ScadParam.encode(s.values))
                        .put("onPlate", s.onPlate ?: "")
                        .put("autoRender", s.autoRender)
                        .apply { s.shapes?.let { put("shapes", it.json()) } }
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
        if (previewJob?.isActive == true) { renderAgain = true; return }
        val s = st ?: return
        renderJob = store.scope.launch {
            set { it.copy(rendering = true, status = store.str(R.string.scad_rendering), statusError = false) }
            try {
                // The preview already shows this exact design: place its mesh, no second render.
                val cached = previewStl?.takeIf { previewKey == key(s) && it.isFile }
                if (cached != null) {
                    val files = withContext(Dispatchers.IO) { ScadDeps.collect(s.source, s.file) }
                    // The object is named after its file: a copy named after the design, not the preview's.
                    val stem = s.name.substringBeforeLast('.').ifEmpty { "openscad" }
                    val named = File(store.app.cacheDir, "openscad/${System.nanoTime()}/$stem.stl")
                    withContext(Dispatchers.IO) { cached.copyTo(named, overwrite = true) }
                    val name = try {
                        place(named, if (asNew) null else s.onPlate, ScadDesign(s.name, s.source, s.values, files, s.shapes))
                    } finally {
                        named.parentFile?.deleteRecursively()
                    }
                    set { it.copy(onPlate = name, status = store.str(R.string.scad_placed), statusError = false) }
                    saveDraft()
                    return@launch
                }
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
                        val files = withContext(Dispatchers.IO) { ScadDeps.collect(s.source, s.file) }
                        val name = place(stl, if (asNew) null else s.onPlate, ScadDesign(s.name, s.source, s.values, files, s.shapes))
                        set { it.copy(onPlate = name, status = summary(r), statusError = false) }
                        saveDraft()
                        // The render doubles as the preview.
                        withContext(Dispatchers.IO) { runCatching { PreviewMesh.readStl(stl) }.getOrNull() }?.let { mesh ->
                            val keep = File(store.app.cacheDir, "openscad/preview/${System.nanoTime()}.stl")
                            keep.parentFile?.mkdirs()
                            if (stl.renameTo(keep)) {
                                previewStl?.delete(); previewStl = keep; previewKey = key(s)
                                set { it.copy(preview = mesh, previewStale = false) }
                            }
                        }
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
                afterRequest()
            }
        }
    }

    fun stop() {
        renderAgain = false
        previewAgain = false
        pushToPlate = false
        previewDelay?.cancel()
        previewJob?.cancel()
        renderJob?.cancel()
    }

    private fun fail(res: Int) = set { it.copy(status = store.str(res), statusError = true) }

    private fun summary(r: ScadRender) =
        store.str(R.string.scad_rendered, "%,d".format(r.triangles), "%.1f".format(r.millis / 1000.0))

    private fun message(e: Exception) = when (e) {
        is ScadUnavailable -> if (e.message?.contains("does not include") == true) store.str(R.string.scad_not_in_build) else e.message.orEmpty()
        else -> e.message ?: e.toString()
    }

    /**
     * Adds the mesh to the plate tagged with its [design], or replaces the mesh of the object named
     * [replace] if it is still there (keeping its settings, modifiers, copies and painting); returns
     * the object's name.
     */
    private suspend fun place(stl: File, replace: String?, design: ScadDesign): String {
        val objects = store.value.scene.objects
        val oldIndex = replace?.let { n ->
            objects.indexOfLast { it.name == n && ScadDesign.of(it) != null }.takeIf { it >= 0 } ?: objects.indexOfLast { it.name == n }
        }?.takeIf { it >= 0 }
        val index: Int
        var s: Scene
        if (oldIndex != null) {
            s = engine.replaceMesh(oldIndex, stl.path, design.encode())
            index = oldIndex
        } else {
            s = engine.loadModels(listOf(stl.path), append = true, plate = store.value.activePlate)
            index = s.objects.lastIndex
            s = engine.setObjectModule(index, design.encode())
            s = placeFree(s, index)
        }
        store.applyScene(s)
        store.update { it.copy(selection = Selection(index), multiSelection = emptyList()) }
        return s.objects[index].name
    }

    /**
     * Moves the new object [idx] to the free spot of its plate nearest the plate centre (5 mm grid,
     * 5 mm gap), so it doesn't land inside another; the other objects stay where they are.
     */
    private suspend fun placeFree(s: Scene, idx: Int): Scene {
        val inst = s.objects[idx].instances.firstOrNull() ?: return s
        val plate = s.plates.getOrNull(inst.plate) ?: return s
        val gap = 5f
        val w = inst.size.x
        val d = inst.size.y
        val others = s.objects.filterIndexed { i, _ -> i != idx }.flatMap { o -> o.instances.filter { it.plate == inst.plate } }
        fun free(x: Float, y: Float) = others.none { o ->
            x < o.min.x + o.size.x + gap && x + w + gap > o.min.x && y < o.min.y + o.size.y + gap && y + d + gap > o.min.y
        }
        if (free(inst.min.x, inst.min.y) || w > s.plateWidth || d > s.plateDepth) return s
        val cx = plate.originX + (s.plateWidth - w) / 2
        val cy = plate.originY + (s.plateDepth - d) / 2
        var best: Pair<Float, Float>? = null
        var bestDist = Float.MAX_VALUE
        var x = plate.originX
        while (x + w <= plate.originX + s.plateWidth) {
            var y = plate.originY
            while (y + d <= plate.originY + s.plateDepth) {
                val dist = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (dist < bestDist && free(x, y)) { bestDist = dist; best = x to y }
                y += 5f
            }
            x += 5f
        }
        val (bx, by) = best ?: return s
        return engine.setTransform(idx, 0, offset = Vec3(inst.offset.x + bx - inst.min.x, inst.offset.y + by - inst.min.y, inst.offset.z))
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
