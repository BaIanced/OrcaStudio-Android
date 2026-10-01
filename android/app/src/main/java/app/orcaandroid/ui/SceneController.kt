package app.orcaandroid.ui

import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.LayerGcode
import app.orcaandroid.core.LayerProfile
import app.orcaandroid.core.LayerRange
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.Scene
import app.orcaandroid.core.Vec3
import app.orcaandroid.core.VolumeType
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Plates, objects and their parts, the 3D tools, custom G-code and calibration. */
class SceneController(private val store: Store, private val presets: PresetController) {
    private val engine = store.engine
    private val resources = store.resources

    private val activePlate get() = store.value.activePlate
    private val selection get() = store.value.selection

    // --- Selection -------------------------------------------------------------------------------------

    fun selectPlate(plate: Int) = store.update { it.copy(activePlate = plate) }

    fun select(selection: Selection?) = store.update { it.copy(selection = selection, tool = if (selection == null) Tool.None else it.tool) }

    // --- Adding objects --------------------------------------------------------------------------------

    fun sampleModels() = resources.sampleModels()

    fun addSample(file: File) = store.sceneOp(store.str(R.string.loading_model)) { engine.loadModels(listOf(file.path), true, activePlate) }

    fun addPrimitive(shape: String, size: Vec3) = store.sceneOp { engine.addPrimitive(shape, size, activePlate) }

    fun addText(text: String, height: Float, depth: Float) = store.sceneOp(store.str(R.string.creating_text)) {
        engine.addText(text, resources.defaultFont(), height, depth, activePlate)
    }

    fun addSvg(uri: Uri, width: Float, depth: Float) = store.sceneOp(store.str(R.string.loading_model)) {
        engine.addSvg(resources.importFile(uri).path, width, depth, activePlate)
    }

    // --- Objects -----------------------------------------------------------------------------------------

    fun moveSelected(dx: Float, dy: Float) {
        val sel = selection ?: return
        val inst = store.value.scene.objects.getOrNull(sel.obj)?.instances?.getOrNull(sel.instance) ?: return
        store.sceneOp { engine.setTransform(sel.obj, sel.instance, offset = inst.offset + Vec3(dx, dy, 0f)) }
    }

    fun setTransform(offset: Vec3? = null, rotation: Vec3? = null, scale: Vec3? = null, mirror: Vec3? = null) {
        val sel = selection ?: return
        store.sceneOp { engine.setTransform(sel.obj, sel.instance, offset, rotation, scale, mirror) }
    }

    /** Deletes the selected part, else the selected copy, else the whole object. */
    fun deleteSelected() {
        val sel = selection ?: return
        val copies = store.value.scene.objects.getOrNull(sel.obj)?.instances?.size ?: 1
        store.update { it.copy(selection = null, tool = Tool.None) }
        store.sceneOp {
            when {
                sel.volume >= 0 -> engine.deleteVolume(sel.obj, sel.volume)
                copies > 1 -> engine.deleteInstance(sel.obj, sel.instance)
                else -> engine.deleteObject(sel.obj)
            }
        }
    }

    fun deleteAll() = store.sceneOp {
        var scene = store.value.scene
        store.update { it.copy(selection = null) }
        for (i in scene.objects.indices.reversed()) scene = engine.deleteObject(i)
        scene
    }

    fun duplicate(copies: Int) = selection?.let { sel -> store.sceneOp { engine.duplicate(sel.obj, copies) } }
    fun arrange(allPlates: Boolean) = store.sceneOp(store.str(R.string.arranging)) { engine.arrange(if (allPlates) -1 else activePlate) }
    fun autoOrient() = store.sceneOp(store.str(R.string.orienting)) { engine.autoOrient(selection?.obj ?: -1) }

    fun split(toParts: Boolean) = selection?.let { sel ->
        store.update { it.copy(selection = if (toParts) sel else null) }
        store.sceneOp { engine.split(sel.obj, toParts) }
    }

    fun simplify(ratio: Float) = selection?.let { sel -> store.sceneOp(store.str(R.string.simplifying)) { engine.simplify(sel.obj, ratio) } }
    fun repair() = selection?.let { sel -> store.sceneOp { engine.repair(sel.obj) } }

    fun cut(z: Float, keepUpper: Boolean, keepLower: Boolean, flip: Boolean) {
        val sel = selection ?: return
        store.update { it.copy(selection = null, tool = Tool.None) }
        store.sceneOp(store.str(R.string.cutting)) { engine.cut(sel.obj, sel.instance, z, keepUpper, keepLower, flip) }
    }

    /** Adds a modifier, blocker, enforcer or negative volume sized to the selected object. */
    fun addVolume(type: VolumeType, shape: String) = selection?.let { sel ->
        val size = store.value.selectedObject?.instances?.firstOrNull()?.size ?: Vec3(10f, 10f, 10f)
        val s = maxOf(5f, minOf(size.x, size.y, size.z) / 2)
        store.sceneOp { engine.addVolume(sel.obj, type, shape, Vec3(s, s, s)) }
    }

    /** Per-object/part setting (null removes it). */
    fun setObjectSetting(obj: Int, volume: Int, key: String, value: String?) = store.sceneOp { engine.setObjectSetting(obj, volume, key, value) }

    fun setRangeSetting(obj: Int, range: Int, key: String, value: String?) = store.sceneOp {
        val ranges = store.value.scene.objects[obj].layerRanges.mapIndexed { i, r ->
            if (i != range) r else r.copy(settings = if (value == null) r.settings - key else r.settings + (key to value))
        }
        engine.setLayerRanges(obj, ranges)
    }

    fun setLayerRanges(obj: Int, ranges: List<LayerRange>) = store.sceneOp { engine.setLayerRanges(obj, ranges) }

    // --- Plates ------------------------------------------------------------------------------------------

    fun addPlate() = store.launch {
        store.applyScene(engine.addPlate())
        store.update { it.copy(activePlate = it.scene.plates.lastIndex) }
    }

    fun deletePlate(plate: Int) = store.sceneOp { engine.deletePlate(plate) }
    fun setPlateBedType(plate: Int, bedType: String) = store.sceneOp { engine.setPlateBedType(plate, bedType) }
    fun setLayerGcodes(plate: Int, items: List<LayerGcode>) = store.sceneOp { engine.setLayerGcodes(plate, items) }
    fun setWipeTower(plate: Int, pos: Pair<Float, Float>?) = store.sceneOp { engine.setWipeTower(plate, pos) }

    fun undo() = store.sceneOp { engine.undo() }
    fun redo() = store.sceneOp { engine.redo() }

    /** Fills the process's flushing matrix from the filament colours (like the desktop's auto-calc). */
    fun autoFlushMatrix() = store.launch {
        presets.syncSelection()
        val m = engine.flushMatrix()
        presets.setOption(PresetType.PRINT, "flush_volumes_matrix", m.joinToString(",") { it.toInt().toString() })
    }

    // --- 3D tools ----------------------------------------------------------------------------------------

    fun setTool(tool: Tool) = store.launch {
        store.update { it.copy(tool = tool, measure = if (tool == Tool.Measure) emptyList() else it.measure) }
        if (tool == Tool.LayerHeight) loadLayerProfile { engine.layerProfile(it) }
    }

    /** Handles a tap on the 3D view according to the current tool. */
    fun onViewTap(origin: Vec3, dir: Vec3) = store.launch {
        val hit = engine.pick(origin, dir)
        when (store.value.tool) {
            Tool.LayOnFace -> if (hit != null) {
                store.applyScene(engine.layOnFace(hit.obj, hit.instance, hit.normal))
                store.update { it.copy(tool = Tool.None, selection = Selection(hit.obj, hit.instance)) }
            }
            Tool.Measure -> if (hit != null) store.update { st -> st.copy(measure = (st.measure + hit.point).takeLast(2)) }
            else -> store.update { st ->
                st.copy(selection = hit?.let { Selection(it.obj, it.instance) }, tool = if (hit == null) Tool.None else st.tool)
            }
        }
    }

    private val paintLock = Mutex()
    private val pendingPaint = ArrayDeque<Triple<Vec3, Vec3, Boolean>>()

    /** Paint sample from the 3D view; samples are queued and applied in order on the engine thread. */
    fun paintAt(origin: Vec3, dir: Vec3, camera: Vec3, newStroke: Boolean) {
        val tool = store.value.tool as? Tool.Paint ?: return
        synchronized(pendingPaint) { pendingPaint.addLast(Triple(origin, dir, newStroke)) }
        if (paintLock.isLocked) return
        store.launch {
            paintLock.withLock {
                while (true) {
                    val (o, d, stroke) = synchronized(pendingPaint) { pendingPaint.removeFirstOrNull() } ?: break
                    val hit = engine.pick(o, d) ?: continue
                    store.applyScene(engine.paint(hit, camera, tool.radius, tool.kind, tool.state, stroke))
                }
            }
        }
    }

    fun paintClear(kind: String) = selection?.let { sel -> store.sceneOp { engine.paintClear(sel.obj, kind) } }

    // --- Variable layer height ------------------------------------------------------------------------

    private suspend fun loadLayerProfile(op: suspend (Int) -> Pair<Scene, LayerProfile>) {
        val sel = selection ?: return
        presets.syncSelection()
        val (scene, profile) = op(sel.obj)
        store.applyScene(scene)
        store.update { it.copy(layerProfile = profile) }
    }

    fun layerAdaptive(quality: Float) = store.launch { loadLayerProfile { engine.layerAdaptive(it, quality) } }
    fun layerSmooth(radius: Int, keepMin: Boolean) = store.launch { loadLayerProfile { engine.layerSmooth(it, radius, keepMin) } }
    fun layerAdjust(z: Float, delta: Float, band: Float) = store.launch { loadLayerProfile { engine.layerAdjust(it, z, delta, band) } }
    fun layerReset() = store.launch { loadLayerProfile { engine.layerReset(it) } }

    // --- Calibration -------------------------------------------------------------------------------------

    fun startCalibration(type: String, params: Map<String, Any?>) = store.launch(store.str(R.string.preparing_calibration)) {
        presets.syncSelection()
        val (scene, calib) = engine.calibStart(type, params)
        store.applyScene(scene)
        store.update { it.copy(calibration = calib, selection = null, screen = Screen.PREPARE, projectName = calib.name) }
    }

    fun stopCalibration() = store.launch {
        store.applyScene(engine.calibStop())
        store.update { it.copy(calibration = null) }
    }
}
