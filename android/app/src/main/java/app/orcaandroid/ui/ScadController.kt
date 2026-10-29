package app.orcaandroid.ui

import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.OrcaException
import app.orcaandroid.core.Scene
import app.orcaandroid.core.SceneObject
import app.orcaandroid.core.Vec3
import app.orcaandroid.core.Volume
import app.orcaandroid.core.VolumeSpec
import app.orcaandroid.core.VolumeType
import app.orcaandroid.render.PreviewMesh
import app.orcaandroid.scad.PieceMatch
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
import java.security.MessageDigest

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
    /** The preview is a coarse one made while a slider is dragged. */
    val draftPreview: Boolean = false,
    val previewing: Boolean = false,
    /** Shape-builder design the source is generated from; null = hand-written code. */
    val shapes: ShapeDesign? = null,
    /** Selected shape ids in the builder. */
    val selected: List<Int> = emptyList(),
) {
    // A shape design's sliders read its dimensions: the params re-parsed from the code lag behind while dragging.
    fun value(p: ScadParam): Any = shapes?.dim(p.name)?.value ?: values[p.name] ?: p.initial
}

/**
 * The Build workspace's object: what the open shape design put on the plate as pieces, while the design is shown on
 * the plate (eye toggle). The main view then shows it alone and edits its shapes; null otherwise.
 */
val UiState.buildObject: Int? get() {
    val s = scad?.takeIf { it.shapes != null && scadOnPlate && !scadHidden && screen == Screen.PREPARE } ?: return null
    val name = s.onPlate ?: return null
    return scene.objects.indexOfLast { it.name == name && ScadDesign.of(it)?.pieces == true }.takeIf { it >= 0 }
}

/**
 * OpenSCAD modelling: edits a .scad source with its Customizer parameters and renders it (in the
 * ":scad" process, see [ScadClient]) onto the plate. The draft survives closing the editor and the
 * app (files/openscad/draft.*).
 */
/** Delay before a preview while a slider is dragged (ms): the newest value is taken once the running render ends. */
private const val DRAG_DEBOUNCE = 40L

/** Starts the last line of a saved shape-builder .scad file; the rest of the line is the design's JSON. */
private const val SHAPES_MARK = "// OrcaStudio shapes: "

/** Rendered shape pieces kept in files/openscad/pieces (least recently used go first). */
private const val PIECE_CACHE = 500

private val IDENTITY = listOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

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
    /**
     * A Customizer slider is being dragged: previews come at once and coarse (few circle segments),
     * the full one when it is let go (user, 2026-10-13: as instant as possible).
     */
    private var dragging = false
    /** The STL [ScadEditorState.preview] was read from and the input it was rendered from. */
    private var previewStl: File? = null
    private var previewKey: String? = null

    /** Path of the STL the current preview mesh was read from, for [design_snapshot]; null when none rendered. */
    val previewStlPath: String? get() = previewStl?.takeIf { it.isFile }?.path

    private val st get() = store.value.scad

    private fun set(transform: (ScadEditorState) -> ScadEditorState) =
        store.update { s -> s.copy(scad = s.scad?.let(transform)) }

    /**
     * Opens the editor on the saved draft (or the first example). A [newDesign] (not an object's own design)
     * moves to an empty plate when the draft isn't on the plate yet.
     */
    fun open(newDesign: Boolean = true) = store.launch {
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
        val placed = st?.onPlate?.let { n -> store.value.scene.objects.any { it.name == n } } == true
        if (newDesign && !placed) useEmptyPlate()
        refreshParams(0)
        if (!buildIfShapes()) preview()
    }

    /**
     * A shape design is edited in the main view, the Build workspace (Tinkercad-like; user 2026-10-25: the separate
     * preview pane is the old view): it goes on the plate as pieces once it has a solid shape ([placeShapes]), and an
     * object placed before gets the draft's edits ([showBuild]). Code designs keep the preview pane. True for a shape design.
     */
    private fun buildIfShapes(): Boolean {
        if (st?.shapes == null) return false
        store.update { it.copy(scadOnPlate = true, scadHidden = false) }
        if (store.value.buildObject == null) placeShapes()
        return true
    }

    /** Puts the shape design on the plate as pieces (a new design, or one placed as a single mesh); empty: nothing yet. */
    private fun placeShapes() {
        if (st?.shapes?.pieces()?.any { !it.hole } == true) pushBuild()
    }

    /** The plate [useEmptyPlate] added; it goes again when the editor closes while it is still empty. */
    private var addedPlate: Int? = null

    private fun Scene.plateEmpty(plate: Int) = objects.none { o -> o.instances.any { it.plate == plate } }

    /**
     * A new design starts on an empty plate (user, 2026-10-13), so placing it can't land on or move the
     * project's objects: the active plate if it is empty, else the first empty one, else a new one.
     */
    private suspend fun useEmptyPlate() {
        val s = store.value.scene
        if (s.plates.isEmpty() || s.plateEmpty(store.value.activePlate)) return
        val free = s.plates.indices.firstOrNull { s.plateEmpty(it) }
        if (free != null) { store.update { it.copy(activePlate = free) }; return }
        // At the plate limit (or without a printer) the design stays on the active plate.
        val scene = runCatching { engine.addPlate() }.getOrNull() ?: return
        store.applyScene(scene)
        addedPlate = scene.plates.lastIndex
        store.update { it.copy(activePlate = scene.plates.lastIndex) }
    }

    /** Opens a .scad file (from the file picker, a download or a model site's zip). */
    fun openFile(file: File) = store.launch {
        val source = withContext(Dispatchers.IO) { file.readText() }
        if (st == null) open().join()
        store.update { it.copy(scadHidden = false) }
        // A shape-builder design saved by [save] reopens in the builder; if its code was edited since, it opens as
        // code without the (stale) shapes line.
        val code = source.substringBeforeLast("\n$SHAPES_MARK")
        val shapes = ShapeDesign.decode(source.substringAfterLast("\n$SHAPES_MARK", "").trim().ifEmpty { null })
        if (shapes != null && shapes.toScad().trimEnd() == code.trimEnd()) load(file.name, file.path, shapes.toScad(), shapes = shapes)
        else load(file.name, file.path, if (shapes != null) code.trimEnd() + "\n" else source)
    }

    fun openUri(uri: Uri) = store.launch {
        openFile(store.resources.importFile(uri, "openscad")).join()
    }

    /** Opens the design an object was rendered from (kept in the project, see [ScadDesign]). */
    fun editObject(index: Int) = store.launch {
        val obj = store.value.scene.objects.getOrNull(index) ?: return@launch
        val design = ScadDesign.of(obj) ?: return@launch
        if (st == null) open(newDesign = false).join()
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
        if (onPlate == null) store.launch { useEmptyPlate() }
        if (!buildIfShapes()) preview()
    }

    fun close() {
        renderJob?.cancel()
        previewDelay?.cancel()
        previewJob?.cancel()
        previewStl?.delete(); previewStl = null; previewKey = null
        saveDraft(now = true)
        store.update { it.copy(scad = null, scadOnPlate = false) }
        client.release()
        // The plate added for the design goes again if nothing was placed on it.
        addedPlate?.let { plate ->
            addedPlate = null
            store.launch {
                val s = store.value.scene
                if (s.plates.size < 2 || plate !in s.plates.indices || !s.plateEmpty(plate)) return@launch
                val scene = engine.deletePlates(listOf(plate))
                store.applyScene(scene)
                store.update { it.copy(activePlate = (if (it.activePlate > plate) it.activePlate - 1 else it.activePlate).coerceIn(0, scene.plates.lastIndex)) }
            }
        }
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
        // The Build workspace shows the object itself: every edit goes there.
        if (store.value.buildObject != null) { pushBuild(); return }
        // Not on the plate yet (or removed from it): the first solid shape puts it there.
        if (store.value.scadOnPlate) { placeShapes(); return }
        if (st?.autoRender == true) preview(if (dragging) DRAG_DEBOUNCE else 500)
    }

    fun select(ids: List<Int>) = set { it.copy(selected = ids) }

    /** Turns a shape design into plain code (the shapes are dropped, the code is kept); code is edited with the preview pane. */
    fun detachShapes() {
        set { it.copy(shapes = null, selected = emptyList(), loadId = it.loadId + 1) }
        store.update { it.copy(scadOnPlate = false) }
        saveDraft()
        preview()
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

    fun setValue(p: ScadParam, value: Any, dragging: Boolean = false) {
        this.dragging = dragging
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

    /** The slider was let go: the full-quality preview (and the object on the plate) follow. */
    fun endDrag() {
        if (!dragging) return
        dragging = false
        if (st?.autoRender == true) preview()
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
        preview(if (dragging) DRAG_DEBOUNCE else 300)
    }

    /** What a render depends on; whole-line comments don't count (renaming a shape only changes its comment). */
    private fun key(s: ScadEditorState, draft: Boolean = false) = s.file + "\u0000" +
        s.source.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n") + "\u0000" + ScadParam.encode(s.values) +
        if (draft) "\u0000draft" else ""

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
        val draft = dragging
        val k = key(s, draft)
        if (k == previewKey && s.preview != null) { set { it.copy(previewStale = false) }; pushPreview(); return }
        previewJob = store.scope.launch {
            set { it.copy(previewing = true, previewStale = it.preview != null) }
            try {
                val out = File(store.app.cacheDir, "openscad/preview/${System.nanoTime()}.stl")
                // Draft: a last top-level assignment wins in OpenSCAD, so this caps round things at 16 segments
                // (calls with their own $fn keep it). Appended, so the line numbers stay the same.
                val source = if (draft) s.source + "\n\$fn = 16;\n" else s.source
                val r = client.render(source, s.file, ScadParam.encode(s.values), out)
                val stl = r.stl
                val mesh = stl?.let { withContext(Dispatchers.IO) { runCatching { PreviewMesh.readStl(it) }.getOrNull() } }
                // Another design was loaded meanwhile: this result is not its preview.
                if (st?.let { key(it, draft) } != k) {
                    out.delete(); stl?.delete()
                    previewAgain = true
                    return@launch
                }
                if (stl != null && mesh != null) {
                    previewStl?.delete()
                    previewStl = stl
                    previewKey = k
                    // A draft's log would only warn about the overridden $fn.
                    if (draft) set { it.copy(preview = mesh, previewStale = false, draftPreview = true) }
                    else set { it.copy(log = r.log, preview = mesh, previewStale = false, status = summary(r), statusError = false, draftPreview = false) }
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
                set { it.copy(log = "", status = message(e), statusError = true) }
            } finally {
                set { it.copy(previewing = false) }
                afterRequest()
            }
        }
    }

    /** After a Customizer change, puts the fresh preview on the plate in place of the old object. */
    private fun pushPreview() {
        if (!pushToPlate || dragging) return
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
    /** Waits until no preview or render is running or queued (AI tools). */
    suspend fun awaitIdle() {
        do {
            previewDelay?.join(); previewJob?.join(); renderJob?.join()
            kotlinx.coroutines.yield()
        } while (previewDelay?.isActive == true || previewJob?.isActive == true || renderJob?.isActive == true)
    }

    fun render(asNew: Boolean = false) {
        if (renderJob?.isActive == true) return
        if (previewJob?.isActive == true) { renderAgain = true; return }
        val s = st ?: return
        renderJob = store.scope.launch {
            set { it.copy(rendering = true, status = store.str(R.string.scad_rendering), statusError = false) }
            try {
                val shapes = s.shapes
                if (shapes != null) {
                    renderPieces(s, shapes, asNew)
                    return@launch
                }
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

    /** A Blend joins piece ([ShapeDesign.BLEND_TAG]) fused by the engine into [out]; its triangles, or null after showing why not. */
    private suspend fun blend(code: String, out: File): Int? {
        val spec = code.removePrefix(ShapeDesign.BLEND_TAG)
        if (spec.startsWith("!")) {
            set { it.copy(status = store.str(R.string.shape_blend_unsupported, spec.drop(1)), statusError = true) }
            return null
        }
        set { it.copy(status = store.str(R.string.shape_blending), statusError = false) }
        return try {
            engine.blendSolids(JSONObject(spec), out)
        } catch (e: OrcaException) {
            set { it.copy(status = message(e), statusError = true) }
            null
        }
    }

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
        val oldIndex = placed(replace)
        val index: Int
        var s: Scene
        if (oldIndex != null) {
            val old = store.value.scene.objects[oldIndex]
            // A shape design built from pieces, now code: its one mesh (in the design's frame) replaces the pieces.
            s = if (ScadDesign.of(old)?.pieces == true) {
                engine.setVolumes(oldIndex, listOf(VolumeSpec(VolumeType.PART, IDENTITY, stl = stl.path)) + otherVolumes(old.volumes), keepCenter = true)
                engine.setObjectModule(oldIndex, design.encode())
            } else engine.replaceMesh(oldIndex, stl.path, design.encode())
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

    /** The object named [name] this editor placed (the one with a design first), or null when it is gone. */
    private fun placed(name: String?): Int? {
        val objects = store.value.scene.objects
        return name?.let { n ->
            objects.indexOfLast { it.name == n && ScadDesign.of(it) != null }.takeIf { it >= 0 } ?: objects.indexOfLast { it.name == n }
        }?.takeIf { it >= 0 }
    }

    /** Modifiers and support blockers / enforcers of an object, kept where they are by [Engine.setVolumes]. */
    private fun otherVolumes(volumes: List<Volume>) =
        volumes.filter { it.type != VolumeType.PART && it.type != VolumeType.NEGATIVE }.map { VolumeSpec(it.type, null, keep = it.index) }

    // --- Build workspace: a shape design as one volume per piece -----------------------------------

    private val piecesDir get() = File(dir, "pieces").apply { mkdirs() }

    /** Where piece [scad] is cached once rendered (an empty file = an empty piece, e.g. blank text). */
    private fun pieceFile(scad: String, file: String) = File(piecesDir, sha1(file + "\u0000" + scad) + ".stl")

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Shape builder Merge: renders piece code [scad] once into files/openscad/meshes (kept like imported meshes, as a
     * MESH shape's file); null after showing why not (failed, empty).
     */
    suspend fun renderMesh(scad: String): File? {
        val s = st ?: return null
        val out = File(File(dir, "meshes").apply { mkdirs() }, "merged-" + sha1(s.file + "\u0000" + scad).take(16) + ".stl")
        if (out.length() > 0) return out
        val part = File(out.path + ".part")
        set { it.copy(status = store.str(R.string.shape_merging), statusError = false) }
        try {
            if (scad.startsWith(ShapeDesign.BLEND_TAG)) {
                blend(scad, part) ?: return null
                if (!withContext(Dispatchers.IO) { part.renameTo(out) }) { fail(R.string.scad_failed); return null }
                return out
            }
            val r = client.render(scad, s.file, null, part)
            when {
                !r.ok -> { set { it.copy(log = r.log) }; fail(R.string.scad_failed); return null }
                r.stl == null -> { fail(R.string.scad_empty); return null }
            }
            if (!withContext(Dispatchers.IO) { part.renameTo(out) }) { fail(R.string.scad_failed); return null }
            set { it.copy(status = summary(r), statusError = false) }
            return out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            set { it.copy(status = message(e), statusError = true) }
            return null
        } finally {
            part.delete()
        }
    }

    /**
     * Renders a shape design piece by piece ([ShapeDesign.pieces]; only pieces not rendered before) and puts it
     * on the plate as one volume per piece.
     */
    private suspend fun renderPieces(s: ScadEditorState, shapes: ShapeDesign, asNew: Boolean) {
        val pieces = shapes.pieces()
        if (pieces.none { !it.hole }) { fail(R.string.scad_empty); return }
        val stls = pieces.map { pieceFile(it.scad, s.file) }
        var triangles = 0
        var millis = 0L
        var rendered = 0
        for ((piece, stl) in pieces.zip(stls).distinctBy { it.second.path }) {
            if (stl.isFile) { stl.setLastModified(System.currentTimeMillis()); continue }
            val part = File(stl.path + ".part")
            try {
                if (piece.scad.startsWith(ShapeDesign.BLEND_TAG)) {
                    val t0 = System.currentTimeMillis()
                    triangles += blend(piece.scad, part) ?: return
                    millis += System.currentTimeMillis() - t0; rendered++
                    withContext(Dispatchers.IO) { if (!part.renameTo(stl)) throw java.io.IOException("Unable to keep the rendered piece") }
                    continue
                }
                val r = client.render(piece.scad, s.file, null, part)
                triangles += r.triangles; millis += r.millis; rendered++
                when {
                    !r.ok -> { set { it.copy(log = r.log) }; fail(R.string.scad_failed); return }
                    r.stl == null && r.dimension == 2 -> { set { it.copy(log = r.log) }; fail(R.string.scad_2d); return }
                }
                withContext(Dispatchers.IO) {
                    if (r.stl == null) part.writeBytes(ByteArray(0))
                    if (!part.renameTo(stl)) throw java.io.IOException("Unable to keep the rendered piece")
                }
            } finally {
                part.delete()
            }
        }
        if (stls.none { it.length() > 0 }) { fail(R.string.scad_empty); return }
        val files = withContext(Dispatchers.IO) { ScadDeps.collect(s.source, s.file) }
        val name = placePieces(shapes, pieces, stls, if (asNew) null else s.onPlate,
            ScadDesign(s.name, s.source, s.values, files, shapes, pieces = true))
        val status = if (rendered == 0) store.str(R.string.scad_placed)
            else store.str(R.string.scad_rendered, "%,d".format(triangles), "%.1f".format(millis / 1000.0))
        set { it.copy(onPlate = name, status = status, statusError = false) }
        saveDraft()
        prunePieces()
    }

    /**
     * Replaces the volumes of the object named [replace] with [pieces] (rendered to [stls]), or adds a new object;
     * returns its name. A piece whose code didn't change keeps its volume (mesh and paint) and only gets its new
     * place; modifiers stay. The first time (a new object or one whole-design mesh), the object stays centred where
     * it was; after that the object's frame is the design's, so moving one shape moves only that volume.
     */
    private suspend fun placePieces(shapes: ShapeDesign, pieces: List<ShapeDesign.Piece>, stls: List<File>, replace: String?,
                                    design: ScadDesign): String {
        val oldIndex = placed(replace)
        val old = oldIndex?.let { store.value.scene.objects[it] }
        val keepCenter = old != null && old.name !in placedThisSession
        val keep = old?.let { kept(it, pieces, stls) }.orEmpty()
        val anchor = old?.takeIf { keepCenter }?.let { anchors(it, pieces, stls) }.orEmpty()
        val filaments = store.value.filaments.size
        val specs = pieces.mapIndexed { i, p ->
            val shape = shapes.shapes.firstOrNull { it.id == p.shapeId }
            val name = shape?.name.orEmpty() + if (shape?.repeat?.takeIf { it.count > 1 } != null) " ${p.copy + 1}" else ""
            val k = keep[i]
            // A part prints with its shape's filament, or the object's (also when that filament was removed).
            VolumeSpec(if (p.hole) VolumeType.NEGATIVE else VolumeType.PART, p.matrix, stl = stls[i].path.takeIf { k == null },
                keep = k ?: -1, name = name, anchor = anchor[i] ?: -1,
                extruder = if (p.hole) -1 else p.filament.takeIf { it <= filaments } ?: 0)
        }
        val index: Int
        var s: Scene
        if (old != null) {
            index = oldIndex
            s = engine.setVolumes(index, specs + otherVolumes(old.volumes), keepCenter = keepCenter)
            s = engine.setObjectModule(index, design.encode())
        } else {
            // A new object starts from its first solid piece (named after the design), then gets all of them.
            val first = stls.indices.first { !pieces[it].hole && stls[it].length() > 0 }
            val stem = design.name.substringBeforeLast('.').ifEmpty { "openscad" }
            val named = File(store.app.cacheDir, "openscad/${System.nanoTime()}/$stem.stl")
            try {
                withContext(Dispatchers.IO) { stls[first].copyTo(named, overwrite = true) }
                s = engine.loadModels(listOf(named.path), append = true, plate = store.value.activePlate)
            } finally {
                named.parentFile?.deleteRecursively()
            }
            index = s.objects.lastIndex
            s = engine.setVolumes(index, specs, keepCenter = true)
            s = engine.setObjectModule(index, design.encode())
            s = placeFree(s, index)
        }
        store.applyScene(s)
        store.update { it.copy(selection = Selection(index), multiSelection = emptyList()) }
        return s.objects[index].name.also { placedThisSession += it }
    }

    /**
     * Objects [placePieces] set up since the app started: their frame is the design's and their volume meshes are the
     * pieces as rendered. A project loaded from 3MF may have re-centred both, so such an object first gets fresh
     * meshes, its unchanged pieces staying where they are ([anchors]; none: the object stays centred).
     */
    private val placedThisSession = mutableSetOf<String>()

    /**
     * Piece index -> volume of [obj] to keep for it: pieces whose code is unchanged since the object's design was
     * placed. Empty when not placed this session (see [matched]).
     */
    private fun kept(obj: SceneObject, pieces: List<ShapeDesign.Piece>, stls: List<File>): Map<Int, Int> =
        if (obj.name !in placedThisSession) emptyMap() else matched(obj, pieces, stls).mapValues { it.value.second }

    /**
     * Piece index -> volume of [obj] for pieces neither edited nor moved since the design was last placed: they pin
     * the design in place when the object's frame may have changed (after a restart), so only the moved shapes move.
     */
    private fun anchors(obj: SceneObject, pieces: List<ShapeDesign.Piece>, stls: List<File>): Map<Int, Int> =
        matched(obj, pieces, stls).filter { (i, m) -> m.first.matrix == pieces[i].matrix }.mapValues { it.value.second }

    /**
     * Piece index -> (piece as last placed, volume of [obj]) for pieces whose code is unchanged since the object's
     * design was placed. Empty when the object's parts don't line up with its design's pieces (edited in Prepare, a
     * piece's cached render gone).
     */
    private fun matched(obj: SceneObject, pieces: List<ShapeDesign.Piece>, stls: List<File>): Map<Int, Pair<ShapeDesign.Piece, Int>> {
        val old = ScadDesign.of(obj)?.takeIf { it.pieces } ?: return emptyMap()
        val file = st?.file ?: return emptyMap()
        val oldPieces = old.shapes?.pieces() ?: return emptyMap()
        val oldFiles = oldPieces.map { pieceFile(it.scad, file) }
        if (oldFiles.any { !it.isFile }) return emptyMap()
        val parts = obj.volumes.filter { it.type == VolumeType.PART || it.type == VolumeType.NEGATIVE }
            .map { it.index to (it.type == VolumeType.NEGATIVE) }
        return PieceMatch.match(oldPieces, oldFiles.map { it.length() == 0L }, pieces, stls.map { it.length() == 0L }, parts)
    }

    /** Keeps the piece cache at its [PIECE_CACHE] most recently used renders. */
    private suspend fun prunePieces() = withContext(Dispatchers.IO) {
        val files = piecesDir.listFiles { f -> f.name.endsWith(".stl") }.orEmpty()
        if (files.size > PIECE_CACHE) files.sortedByDescending { it.lastModified() }.drop(PIECE_CACHE).forEach { it.delete() }
    }

    // --- Build workspace view: the design object alone in the main view ([buildObject]) ----------

    private var buildShown = -1

    /**
     * The Build workspace opened, closed or its object moved in the list: the engine gives that object's volumes their
     * own selection ids. Opening sends edits made in the preview pane to the object; closing brings the preview up to date.
     */
    fun showBuild(obj: Int?) {
        val o = obj ?: -1
        if (o == buildShown) return
        val opened = buildShown < 0
        buildShown = o
        store.launch { store.applyScene(engine.setBuildObject(o), dirty = false) }
        if (o >= 0 && opened && ScadDesign.of(store.value.scene.objects.getOrNull(o))?.shapes != st?.shapes) pushBuild()
        if (o < 0 && st?.shapes != null && !store.value.scadOnPlate) preview()
    }

    /** Build workspace: an edit goes straight to the object's pieces (no whole-design preview); one during a render queues the next. */
    private fun pushBuild() {
        previewDelay?.cancel()
        previewDelay = store.scope.launch {
            delay(if (dragging) DRAG_DEBOUNCE else 150)
            if (renderJob?.isActive == true || previewJob?.isActive == true) { renderAgain = true; return@launch }
            render()
        }
    }

    private var volumeShapesOf: Pair<SceneObject, Map<Int, ShapeDesign.Piece>>? = null

    /** Build workspace: volume index -> piece (top-level shape id, copy) of [obj]'s parts and holes ([placePieces]' order, empty pieces left out). */
    fun volumeShapes(obj: SceneObject): Map<Int, ShapeDesign.Piece> {
        volumeShapesOf?.takeIf { it.first === obj }?.let { return it.second }
        val pieces = ScadDesign.of(obj)?.takeIf { it.pieces }?.shapes?.pieces() ?: return emptyMap()
        val file = st?.file ?: return emptyMap()
        // A piece whose render left the cache counts as placed (only blank text renders empty).
        val placed = pieces.filter { p -> pieceFile(p.scad, file).let { !it.isFile || it.length() > 0 } }
        val parts = obj.volumes.filter { it.type == VolumeType.PART || it.type == VolumeType.NEGATIVE }
        val map = if (parts.size != placed.size) emptyMap() else parts.zip(placed).associate { (v, p) -> v.index to p }
        volumeShapesOf = obj to map
        return map
    }

    /** Build workspace: the selection ids (-1000 - volume) of the volumes of the selected shapes (a shape inside a group: the group's). */
    fun buildSelectedIds(obj: SceneObject): FloatArray {
        val d = st?.shapes ?: return FloatArray(0)
        val tops = st?.selected.orEmpty().mapNotNull { d.topOf(it)?.id }.toSet()
        return volumeShapes(obj).filterValues { it.shapeId in tops }.keys.map { -1000f - it }.toFloatArray()
    }

    /**
     * Build workspace: the copy of build object [obj] under the ray and the shapes there, outermost first (its top-level
     * shape, then inside groups the child under the hit: [ShapeDesign.pathAt]), or null.
     */
    suspend fun shapeAt(obj: Int, origin: Vec3, dir: Vec3): Pair<Int, List<Int>>? {
        val hit = engine.pick(origin, dir, only = obj) ?: return null
        val o = store.value.scene.objects.getOrNull(obj) ?: return null
        val piece = volumeShapes(o)[hit.volume] ?: return null
        val inst = o.instances.getOrNull(hit.instance)?.shown ?: return null
        // The object's own design: the pieces' matrices are its (an edit may still be rendering).
        val path = ScadDesign.of(o)?.shapes?.pathAt(piece, app.orcaandroid.scad.BuildTransform.toDesign(inst, hit.point))
        return hit.instance to path.orEmpty().ifEmpty { listOf(piece.shapeId) }
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

    /** Writes the source to a file the user picked; a shape design adds its shapes as a last comment line. */
    fun save(target: Uri) = store.launch {
        val s = st ?: return@launch
        val text = s.shapes?.let { s.source.trimEnd() + "\n\n$SHAPES_MARK" + it.encode() + "\n" } ?: s.source
        withContext(Dispatchers.IO) {
            store.app.contentResolver.openOutputStream(target, "wt")!!.use { it.write(text.toByteArray()) }
        }
        val name = store.resources.displayName(target)
        set { it.copy(name = name) }
        saveDraft()
        store.toast(store.str(R.string.saved))
    }

    /** Names the open design (MCP design_set). */
    fun rename(name: String) {
        set { it.copy(name = name) }
        saveDraft()
    }
}
