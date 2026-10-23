package app.orcaandroid.ui

import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.LayerGcode
import app.orcaandroid.core.LayerProfile
import app.orcaandroid.core.LayerRange
import app.orcaandroid.core.PickHit
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.Scene
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.SectionView
import app.orcaandroid.ui.prepare.PaintMode
import app.orcaandroid.core.VolumeType
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Plates, objects and their parts, the 3D tools, custom G-code and calibration. */
class SceneController(private val store: Store, private val presets: PresetController) {
    private val engine = store.engine
    private val resources = store.resources

    private val activePlate get() = store.value.activePlate
    private val selection get() = store.value.selection
    private val selectedItems get() = store.value.selectedItems.map { it.obj to it.instance }

    // --- Selection -------------------------------------------------------------------------------------

    fun selectPlate(plate: Int) = store.update { it.copy(activePlate = plate) }

    fun select(selection: Selection?) = store.update {
        it.copy(selection = selection, multiSelection = emptyList(), tool = if (selection == null) Tool.None else it.tool)
    }

    /** Adds every copy of the object to the selection, or removes them (the object list in select mode). */
    fun toggleObject(obj: Int) = store.update { st ->
        val copies = st.scene.objects.getOrNull(obj)?.instances?.map { Selection(obj, it.index) } ?: return@update st
        st.toggled(copies)
    }

    /** Selects every copy on the active plate. */
    fun selectAll() = store.update { st ->
        st.withSelected(st.scene.objects.flatMap { o -> o.instances.filter { it.plate == st.activePlate }.map { Selection(o.index, it.index) } })
    }

    fun setSelectMode(on: Boolean) = store.update { it.copy(selectMode = on) }

    /** Adds the copies to the selection, or removes them (the desktop's Shift / Alt rectangle). */
    fun changeSelection(items: List<Selection>, remove: Boolean) = store.update { st ->
        val current = st.selectedItems.map { it.copy(volume = -1) }
        st.withSelected(if (remove) current - items.toSet() else current + items.filterNot { it in current })
    }

    /** Selects every copy on every plate (Ctrl+Shift+A). */
    fun selectAllPlates() = store.update { st ->
        st.withSelected(st.scene.objects.flatMap { o -> o.instances.map { Selection(o.index, it.index) } })
    }

    /** Copies kept by Ctrl+C for Ctrl+V. */
    private var clipboard: List<Pair<Int, Int>> = emptyList()

    fun copySelected() { clipboard = selectedItems }

    /** Ctrl+V: a copy of each copied object, placed next to the others (like Duplicate). */
    fun paste() {
        val scene = store.value.scene
        val items = clipboard.filter { (o, i) -> scene.objects.getOrNull(o)?.instances?.getOrNull(i) != null }.ifEmpty { return }
        store.sceneOp { engine.duplicate(items, 1) }
    }

    /** Arrow keys: moves the selected copies by (dx, dy) mm. */
    fun nudge(dx: Float, dy: Float) = moveSelected(dx, dy)

    /** Page up / down: rotates the selected copy about Z by [degrees]. */
    fun rotateSelected(degrees: Float) {
        val sel = selection ?: return
        val inst = store.value.scene.objects.getOrNull(sel.obj)?.instances?.getOrNull(sel.instance) ?: return
        store.sceneOp { engine.setTransform(sel.obj, sel.instance, rotation = inst.rotation.copy(z = inst.rotation.z + degrees)) }
    }

    /** "-" key: removes the selected object's last copy (never the only one). */
    fun removeCopy() {
        val sel = selection ?: return
        val copies = store.value.scene.objects.getOrNull(sel.obj)?.instances?.size ?: return
        if (copies > 1) store.sceneOp { engine.deleteInstance(sel.obj, copies - 1) }
    }

    /** Ctrl+wheel while painting: scales the brush within the panel slider's range. */
    fun scaleBrush(factor: Float) {
        val tool = (store.value.tool as? Tool.Paint)?.takeIf { it.shape != "gap" } ?: return
        store.update { it.copy(tool = tool.copy(radius = (tool.radius * factor).coerceIn(0.5f, 15f))) }
    }

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

    fun moveSelected(dx: Float, dy: Float): Job? {
        val items = selectedItems.ifEmpty { return null }
        return store.sceneOp { engine.moveItems(items, dx, dy) }
    }

    /** A typed 0 or negative scale would flatten or flip the part, a huge one hang the viewer: keep 0.1 % to 100x. */
    private fun Vec3.sane() = Vec3(x.coerceIn(0.001f, 100f), y.coerceIn(0.001f, 100f), z.coerceIn(0.001f, 100f))

    fun setTransform(offset: Vec3? = null, rotation: Vec3? = null, scale: Vec3? = null, mirror: Vec3? = null) {
        val sel = selection ?: return
        store.sceneOp { engine.setTransform(sel.obj, sel.instance, offset, rotation, scale?.sane(), mirror) }
    }

    /** Moves, turns or scales the selected part (or modifier) inside its object. */
    fun setVolumeTransform(offset: Vec3? = null, rotation: Vec3? = null, scale: Vec3? = null) {
        val sel = selection?.takeIf { it.volume >= 0 } ?: return
        store.sceneOp { engine.setTransform(sel.obj, sel.instance, offset, rotation, scale?.sane(), volume = sel.volume) }
    }

    /** Deletes the selected part, else the selected copy, else the whole object; or all selected copies. */
    fun deleteSelected() {
        val multi = store.value.multiSelection
        if (multi.isNotEmpty()) {
            store.update { it.copy(multiSelection = emptyList(), tool = Tool.None) }
            store.sceneOp { engine.deleteItems(multi.map { it.obj to it.instance }) }
            return
        }
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

    /** Deletes every object, as one undo step. */
    fun deleteAll(): Job? {
        val all = store.value.scene.objects.flatMap { o -> o.instances.map { o.index to it.index } }.ifEmpty { return null }
        store.update { it.copy(selection = null, multiSelection = emptyList(), tool = Tool.None) }
        return store.sceneOp { engine.deleteItems(all) }
    }

    /** Opens the cut tool at the middle height of the selected copy. */
    fun startCut() {
        val sel = selection ?: return
        val inst = store.value.scene.objects.getOrNull(sel.obj)?.instances?.getOrNull(sel.instance)
        setTool(Tool.Cut(inst?.let { it.min.z + it.size.z / 2 } ?: 5f))
    }

    /** Adds copies of every selected copy. */
    fun duplicate(copies: Int) {
        val items = selectedItems.ifEmpty { return }
        store.sceneOp { engine.duplicate(items, copies) }
    }
    fun arrange(allPlates: Boolean) = store.sceneOp(store.str(R.string.arranging)) { engine.arrange(if (allPlates) -1 else activePlate) }
    fun autoOrient() = store.sceneOp(store.str(R.string.orienting)) { engine.autoOrient(selection?.obj ?: -1) }

    fun split(toParts: Boolean) = selection?.let { sel ->
        store.update { it.copy(selection = if (toParts) sel else null) }
        store.sceneOp { engine.split(sel.obj, toParts) }
    }

    /** Desktop "Assemble": the selected copies become the parts of one object, each staying in place. */
    fun assemble() {
        val items = selectedItems.takeIf { it.size >= 2 } ?: return
        store.update { it.copy(selection = null, multiSelection = emptyList(), selectMode = false) }
        store.sceneOp { engine.assemble(items) }
    }

    /** Union / difference / intersection of two parts of an object (desktop Mesh Boolean gizmo). */
    fun meshBoolean(obj: Int, src: Int, tool: Int, op: String, keepTool: Boolean) {
        store.update { it.copy(selection = Selection(obj)) }
        store.sceneOp { engine.meshBoolean(obj, src, tool, op, keepTool) }
    }

    fun simplify(ratio: Float) = selection?.let { sel -> store.sceneOp(store.str(R.string.simplifying)) { engine.simplify(sel.obj, ratio) } }
    fun repair() = selection?.let { sel -> store.sceneOp { engine.repair(sel.obj) } }

    /** Report of the selected copy's mesh problems (null without a selection); [thickness] is the thin-wall limit in mm. */
    suspend fun meshReport(thickness: Float): String? = selection?.let { sel ->
        val r = store.engine.checkMesh(sel.obj, sel.instance, thickness)
        lastCheck = sel to app.orcaandroid.core.MeshReport.markers(r)
        checkBase = store.value.scene.objects.getOrNull(sel.obj)?.instances?.getOrNull(sel.instance)
        app.orcaandroid.core.MeshReport.text(r)
    }

    private var lastCheck: Pair<Selection, FloatArray>? = null

    /** The checked copy as it was when the spots were found: the spots follow its moves, and go when it turns or scales. */
    var checkBase: app.orcaandroid.core.Instance? = null
        private set

    /** Problem spots (x, y, z, kind quadruples) of the copy they were found on, drawn in the 3D view until the selection changes. */
    val meshMarkers = kotlinx.coroutines.flow.MutableStateFlow<Pair<Selection, FloatArray>?>(null)

    /** True when the last report found spots to show. */
    val hasMeshMarkers get() = lastCheck?.second?.isNotEmpty() == true

    fun showMeshMarkers() { meshMarkers.value = lastCheck }
    fun clearMeshMarkers() { meshMarkers.value = null }

    fun cut(z: Float, rot: Vec3, keepUpper: Boolean, keepLower: Boolean, flip: Boolean, floatUpper: Boolean) {
        val sel = selection ?: return
        store.update { it.copy(selection = null, tool = Tool.None) }
        store.sceneOp(store.str(R.string.cutting)) { engine.cut(sel.obj, sel.instance, z, keepUpper, keepLower, flip, floatUpper, rot) }
    }

    /** Adds a modifier, blocker, enforcer or negative volume sized to the selected object. */
    fun addVolume(type: VolumeType, shape: String) = selection?.let { sel ->
        val size = store.value.selectedObject?.instances?.firstOrNull()?.size ?: Vec3(10f, 10f, 10f)
        val s = maxOf(5f, minOf(size.x, size.y, size.z) / 2)
        store.sceneOp { engine.addVolume(sel.obj, type, shape, Vec3(s, s, s)) }
    }

    /** Per-object/part setting (null removes it). */
    fun setObjectSetting(obj: Int, volume: Int, key: String, value: String?) = store.sceneOp { engine.setObjectSetting(obj, volume, key, value) }

    /** The same setting on every selected object (e.g. the filament of a multi-selection). */
    fun setSelectedObjectsSetting(key: String, value: String?) {
        val objs = store.value.selectedItems.map { it.obj }.distinct().ifEmpty { return }
        store.sceneOp { engine.setObjectsSetting(objs, key, value) }
    }

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

    fun deletePlate(plate: Int) = deletePlates(listOf(plate))

    /** Deletes the plates as one undo step (their objects move off the grid); the active plate stays the same plate. */
    fun deletePlates(plates: Collection<Int>) = store.launch {
        val active = store.state.value.activePlate
        val scene = engine.deletePlates(plates.toList())
        store.applyScene(scene)
        store.update { it.copy(activePlate = (active - plates.count { p -> p < active }).coerceIn(0, scene.plates.lastIndex)) }
        lastDelete = scene.plates.size to active
    }

    /** Plate count after the last plate delete and the plate that was active before it, so its undo can restore it. */
    private var lastDelete: Pair<Int, Int>? = null
    /**
     * Sets the plate's type. It also becomes the default for new plates until the user picks another
     * (user, 2026-10-13); other plates that followed the old default keep it.
     */
    fun setPlateBedType(plate: Int, bedType: String) = store.sceneOp {
        val s = store.value
        val old = s.setup?.defaultBedType.orEmpty()
        if (bedType.isNotEmpty() && old.isNotEmpty() && bedType != old)
            s.scene.plates.filter { it.index != plate && it.bedType.isEmpty() }.forEach { engine.setPlateBedType(it.index, old) }
        if (bedType.isNotEmpty()) presets.rememberBedType(bedType)
        engine.setPlateBedType(plate, bedType)
    }
    fun setLayerGcodes(plate: Int, items: List<LayerGcode>) = store.sceneOp { engine.setLayerGcodes(plate, items) }
    fun setWipeTower(plate: Int, pos: Pair<Float, Float>?) = store.sceneOp { engine.setWipeTower(plate, pos) }

    fun undo() = store.launch {
        val before = store.value.scene.plates.size
        val scene = engine.undo()
        store.applyScene(scene)
        lastDelete?.let { (after, active) ->
            if (before == after && scene.plates.size > after) {
                store.update { it.copy(activePlate = active.coerceIn(0, scene.plates.lastIndex)) }
                lastDelete = null
            }
        }
        if (store.value.calibration != null && !scene.calibrationActive)
            store.update { it.copy(calibration = null, projectName = it.nameBeforeCalibration) }
    }
    fun redo() = store.sceneOp { engine.redo() }

    /** Fills the process's flushing matrix from the filament colours (like the desktop's auto-calc). */
    fun autoFlushMatrix() = store.launch {
        presets.syncSelection()
        val m = engine.flushMatrix()
        presets.setOption(PresetType.PRINT, "flush_volumes_matrix", m.joinToString(",") { it.toInt().toString() })
    }

    // --- 3D tools ----------------------------------------------------------------------------------------

    /** The paint tool each kind was last left with (session-only), for [openPaint]. */
    private val lastPaint = HashMap<String, Tool.Paint>()

    /** Opens paint [kind] as it was last left (PaintMode.reopened). */
    fun openPaint(kind: String, state: Int) = setTool(PaintMode.reopened(lastPaint[kind], kind, state))

    fun setTool(tool: Tool) = store.launch {
        (store.value.tool as? Tool.Paint)?.let { lastPaint[it.kind] = it }
        store.update { it.copy(tool = tool, measure = if (tool == Tool.Measure) emptyList() else it.measure) }
        if (tool == Tool.LayerHeight) loadLayerProfile { engine.layerProfile(it) }
    }

    /**
     * Handles a tap on the 3D view according to the current tool. [additive] (Ctrl/Shift held) or
     * select mode adds the tapped copy to the selection or removes it. [section] = the section view's
     * plane: what it cuts away can't be picked.
     */
    fun onViewTap(origin: Vec3, dir: Vec3, additive: Boolean = false, part: Boolean = false, section: FloatArray? = null, emptyPlate: Int? = null) = store.launch {
        val hit = pick(origin, dir, section)
        if (hit == null && emptyPlate != null && store.value.tool == Tool.None) {
            selectPlate(emptyPlate)
            return@launch
        }
        when (store.value.tool) {
            Tool.LayOnFace -> if (hit != null) {
                store.applyScene(engine.layOnFace(hit.obj, hit.instance, hit.normal))
                store.update { it.withSelected(listOf(Selection(hit.obj, hit.instance))).copy(tool = Tool.None) }
            }
            Tool.Measure -> if (hit != null) store.update { st -> st.copy(measure = (st.measure + hit.point).takeLast(2)) }
            else -> store.update { st ->
                when {
                    // Alt+click: the part under the pointer, as on the desktop.
                    part && hit != null -> st.withSelected(listOf(Selection(hit.obj, hit.instance, hit.volume)))
                    additive || st.selectMode -> if (hit == null) st else st.toggled(listOf(Selection(hit.obj, hit.instance)))
                    else -> st.withSelected(listOfNotNull(hit?.let { Selection(it.obj, it.instance) }))
                }
            }
        }
    }

    /** Whether the ray first hits a copy in the selection: the bounding-box test of a drag start is only a pre-check. */
    fun checkDragStart(origin: Vec3, dir: Vec3, section: FloatArray?, result: (Boolean) -> Unit) {
        store.launch {
            val hit = pick(origin, dir, section)
            result(hit != null && store.value.selectedItems.any { it.obj == hit.obj && it.instance == hit.instance })
        }
    }

    /** A drag began on a prime tower's box at [entry]: [result] gets false when an object is hit nearer than the box. */
    fun checkTowerStart(origin: Vec3, dir: Vec3, entry: Vec3, section: FloatArray?, result: (Boolean) -> Unit) {
        store.launch {
            val hit = pick(origin, dir, section)
            result(hit == null || (hit.point - origin).length() >= (entry - origin).length() - 0.5f)
        }
    }

    /** The copy under the ray, as (object, copy, box min, box max), or null; [result] runs on the engine's coroutine. */
    fun copyAt(origin: Vec3, dir: Vec3, section: FloatArray?, result: (Pair<Int, Int>?, Vec3, Vec3) -> Unit) {
        store.launch {
            val hit = pick(origin, dir, section)
            val copy = hit?.let { store.value.scene.objects.getOrNull(it.obj)?.instances?.getOrNull(it.instance) }
            if (hit == null || copy == null) result(null, Vec3.ZERO, Vec3.ZERO)
            else result(hit.obj to hit.instance, copy.min, copy.min + copy.size)
        }
    }

    /** Right click / long press on the 3D view: selects what lies there (or nothing), then opens the menu at (x, y). */
    fun openContextMenu(origin: Vec3, dir: Vec3, x: Float, y: Float, section: FloatArray? = null) = store.launch {
        if (store.value.screen != Screen.PREPARE) return@launch
        val hit = pick(origin, dir, section)
        store.update { st ->
            // On a copy of the multi-selection the menu acts on all of them, as on the desktop.
            val inMulti = hit != null && st.multiSelection.any { it.obj == hit.obj && it.instance == hit.instance }
            (if (inMulti) st else st.withSelected(listOfNotNull(hit?.let { Selection(it.obj, it.instance) }))).copy(contextMenu = x to y)
        }
    }

    fun closeContextMenu() = store.update { it.copy(contextMenu = null) }

    /** Turns corner resizing of the lone selected copy on, or off when it is already on. */
    fun toggleResize() = store.update {
        val one = it.selectedItems.singleOrNull()
        it.copy(resizeFor = if (it.resizeFor != null && it.resizeFor == one) null else one, rotateFor = null)
    }

    /** Turns the rotate gizmo on the lone selected copy on, or off when it is already on (resizing goes off). */
    fun toggleRotate() = store.update {
        val one = it.selectedItems.singleOrNull()
        it.copy(rotateFor = if (it.rotateFor != null && it.rotateFor == one) null else one, resizeFor = null)
    }

    fun openAddDialog(name: String?) = store.update { it.copy(addDialog = name) }

    /** The first hit along the ray that the section view [section] leaves visible. */
    private suspend fun pick(origin: Vec3, dir: Vec3, section: FloatArray?): PickHit? {
        if (section == null) return engine.pick(origin, dir)
        val start = SectionView.rayStart(section, origin, dir) ?: return null
        return engine.pick(start, dir)?.takeIf { SectionView.kept(section, it.point) }
    }

    private val paintLock = Mutex()
    private class PaintSample(val origin: Vec3, val dir: Vec3, val camera: Vec3, val newStroke: Boolean, val section: FloatArray?)
    private val pendingPaint = ArrayDeque<PaintSample>()
    /** A stroke began but none of its samples hit yet: its first hit takes the undo snapshot. Guarded by [paintLock]. */
    private var strokePending = false
    /** The stroke's last sample hit the model: the next hit joins it (a miss breaks the line, as on the desktop). */
    private var paintLastHit = false
    /** Hold-to-preview fill: this stroke's fill is on the model (native FillPreview). Guarded by [paintLock]. */
    private var fillShown = false

    /**
     * Paint sample from the 3D view; samples are queued and applied in order on the engine thread. Every sample that
     * queued up while the engine painted goes into the next engine call together (up to the next stroke's start):
     * one paint per sample fell further and further behind the finger, since each pass rebuilds the whole paint mesh
     * (user r314).
     */
    fun paintAt(origin: Vec3, dir: Vec3, camera: Vec3, newStroke: Boolean, section: FloatArray? = null) {
        val tool = (store.value.tool as? Tool.Paint)?.takeIf { it.shape != "gap" } ?: return
        synchronized(pendingPaint) { pendingPaint.addLast(PaintSample(origin, dir, camera, newStroke, section)) }
        if (paintLock.isLocked) return
        store.launch {
            paintLock.withLock {
                while (true) {
                    val batch = synchronized(pendingPaint) {
                        val out = mutableListOf<PaintSample>()
                        while (pendingPaint.isNotEmpty() && (out.isEmpty() || !pendingPaint.first().newStroke)) out += pendingPaint.removeFirst()
                        out
                    }
                    if (batch.isEmpty()) break
                    if (batch.first().newStroke) { strokePending = true; fillShown = false }
                    if (tool.shape == "pick") {
                        // Eyedropper: the state under the finger becomes the brush's, then the previous shape is back.
                        // Unpainted colour = the part's own filament.
                        val last = batch.last()
                        val hit = pick(last.origin, last.dir, last.section) ?: continue
                        val (picked, base) = engine.paintStateAt(hit, tool.kind)
                        val s = if (picked == 0 && tool.kind == "color") base.coerceAtLeast(1) else picked
                        (store.value.tool as? Tool.Paint)?.let { t -> store.update { it.copy(tool = t.copy(state = s, shape = t.back)) } }
                        synchronized(pendingPaint) { pendingPaint.clear() }
                        strokePending = false
                        continue
                    }
                    if (tool.shape == "fill" || tool.shape == "regions") {
                        // Hold-to-preview fill: the fill follows the finger (only its latest position counts) and stays
                        // when it lifts; sliding off the model drops it. Back on the model = a new fill.
                        val last = batch.last()
                        val hit = pick(last.origin, last.dir, last.section)
                        if (hit == null) {
                            if (fillShown) store.applyScene(engine.paintFillCancel())
                            fillShown = false
                        } else {
                            store.applyScene(engine.paint(listOf(hit), last.camera, tool.radius, tool.kind, tool.state, !fillShown, last.section,
                                listOf(false), tool.edgeAngle, "fill", tool.height, tool.overhang, tool.guardZ.takeIf { tool.guardOn },
                                tool.overColours || tool.shape == "regions", tool.locked))
                            fillShown = true
                        }
                        strokePending = false
                        continue
                    }
                    val hits = mutableListOf<PickHit>()
                    val joins = mutableListOf<Boolean>()
                    for (s in batch) {
                        if (s.newStroke) paintLastHit = false
                        val hit = pick(s.origin, s.dir, s.section)
                        if (hit != null) { hits += hit; joins += paintLastHit }
                        paintLastHit = hit != null
                    }
                    if (hits.isEmpty()) continue
                    val last = batch.last()
                    store.applyScene(engine.paint(hits, last.camera, tool.radius, tool.kind, tool.state, strokePending, last.section, joins, tool.edgeAngle,
                        tool.shape, tool.height, tool.overhang, tool.guardZ.takeIf { tool.guardOn }, locked = tool.locked))
                    strokePending = false
                }
            }
        }
    }

    fun paintClear(kind: String) = selection?.let { sel -> store.sceneOp { engine.paintClear(sel.obj, kind) } }
    fun paintRemap(dest: List<Int>, base: Boolean = false) = selection?.let { sel -> store.sceneOp { engine.paintRemap(sel.obj, dest, base) } }
    suspend fun paintCoverage(): FloatArray? = selection?.let { engine.paintCoverage(it.obj) }
    /** Regions view: the selected copy's crease lines, or null without a selection. */
    suspend fun creaseLines(angle: Float): FloatArray? = selection?.let { engine.creaseLines(it.obj, it.instance, angle) }
    fun paintByPart() = selection?.let { sel -> store.sceneOp { engine.paintByPart(sel.obj) } }
    fun paintGapFill(kind: String, area: Float, locked: Int = 0) = selection?.let { sel -> store.sceneOp { engine.paintGapFill(sel.obj, kind, area, locked) } }

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
        store.update { it.copy(calibration = calib, selection = null, screen = Screen.PREPARE, projectName = calib.name,
            nameBeforeCalibration = if (it.calibration == null) it.projectName else it.nameBeforeCalibration) }
    }

    fun stopCalibration() = store.launch {
        store.applyScene(engine.calibStop())
        store.update { it.copy(calibration = null) }
    }
}
