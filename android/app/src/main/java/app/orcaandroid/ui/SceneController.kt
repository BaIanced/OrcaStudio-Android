package app.orcaandroid.ui

import android.net.Uri
import app.orcaandroid.R
import app.orcaandroid.core.AlignKind
import app.orcaandroid.core.AlignMath
import app.orcaandroid.core.Footprint
import app.orcaandroid.core.Instance
import app.orcaandroid.core.LayerGcode
import app.orcaandroid.core.LayerProfile
import app.orcaandroid.core.LayerRange
import app.orcaandroid.core.MeshPatch
import app.orcaandroid.core.PickHit
import app.orcaandroid.core.PresetType
import app.orcaandroid.core.Scene
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.CutPlane
import app.orcaandroid.render.SectionView
import app.orcaandroid.ui.prepare.PaintMode
import app.orcaandroid.core.VolumeType
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Plates, objects and their parts, the 3D tools, custom G-code and calibration. */
/** Measure tool: how far (screen pixels) a point reaches to snap to a corner or sharp edge. */
const val SNAP_PX = 14f
/** A caught corner or edge lets go only past this many times the capture distance (no flicker at the rim). */
const val SNAP_RELEASE = 1.6f
const val SCULPT_MIN_MM = 0.5f
const val SCULPT_MAX_MM = 30f

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
        (store.value.tool as? Tool.Sculpt)?.let { t -> store.update { it.copy(tool = t.copy(radius = (t.radius * factor).coerceIn(SCULPT_MIN_MM, SCULPT_MAX_MM))) }; return }
        val tool = (store.value.tool as? Tool.Paint)?.takeIf { it.shape != "gap" } ?: return
        store.update { it.copy(tool = tool.copy(radius = (tool.radius * factor).coerceIn(0.5f, 15f))) }
    }

    // --- Adding objects --------------------------------------------------------------------------------

    fun sampleModels() = resources.sampleModels()

    fun addSample(file: File) = store.sceneOp(store.str(R.string.loading_model)) { engine.loadModels(listOf(file.path), true, activePlate) }

    fun addPrimitive(shape: String, size: Vec3, round: Float = 0f) = store.sceneOp { engine.addPrimitive(shape, size, activePlate, round) }

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

    /** The selected copies as footprints on the plate (one per copy, volumes folded into their copy). */
    private fun selectedFootprints(): List<Triple<Int, Int, Instance>> {
        val scene = store.value.scene
        val out = ArrayList<Triple<Int, Int, Instance>>()
        for ((o, i) in selectedItems.distinct()) {
            val inst = scene.objects.getOrNull(o)?.instances?.getOrNull(i) ?: continue
            out.add(Triple(o, i, inst.shown))
        }
        return out
    }

    /** Prepare "Center": the selection as one group, on the middle of its plate (objects may overlap afterwards). */
    fun centerSelected() {
        val items = selectedFootprints().ifEmpty { return }
        val scene = store.value.scene
        val plate = scene.plates.getOrNull(items.first().third.plate.takeIf { it >= 0 } ?: activePlate) ?: return
        val (dx, dy) = AlignMath.center(items.map { Footprint.of(it.third) },
            plate.originX + scene.plateWidth / 2, plate.originY + scene.plateDepth / 2)
        if (dx != 0f || dy != 0f) store.sceneOp { engine.moveItems(items.map { it.first to it.second }, dx, dy) }
    }

    /** Prepare "Align": each selected copy moves to the group's left / centre / right / front / middle / back edge. */
    fun alignSelected(kind: AlignKind) {
        val items = selectedFootprints()
        if (items.size < 2) return
        val shifts = AlignMath.align(items.map { Footprint.of(it.third) }, kind)
        store.sceneOp {
            var scene = store.value.scene
            items.zip(shifts).forEach { (item, d) ->
                if (d.first != 0f || d.second != 0f) scene = engine.moveItems(listOf(item.first to item.second), d.first, d.second)
            }
            scene
        }
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
    fun hollow(wall: Float) =
        selection?.let { sel -> store.sceneOp(store.str(R.string.hollowing), engine::cancel) { engine.hollowObject(sel.obj, sel.instance, wall) } }
    fun smoothBlend(radius: Float) =
        selection?.let { sel -> store.sceneOp(store.str(R.string.blending), engine::cancel) { engine.smoothBlend(sel.obj, sel.instance, radius) } }
    fun deform(kind: String, axis: Int, amount: Float) =
        selection?.let { sel -> store.sceneOp(store.str(R.string.deforming)) { engine.deform(sel.obj, sel.instance, kind, axis, amount) } }

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

    fun cut(t: Tool.Cut, keepUpper: Boolean, keepLower: Boolean, flip: Boolean, floatUpper: Boolean) {
        val sel = selection ?: return
        store.update { it.copy(selection = null, tool = Tool.None) }
        store.sceneOp(store.str(R.string.cutting)) { engine.cut(sel.obj, sel.instance, t.z, keepUpper, keepLower, flip, floatUpper, t.rot, t.dx, t.dy) }
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
        // The picked region's applied marks would no longer match the model.
        regionPick.value = null
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
        val old = store.value.tool as? Tool.Paint
        old?.let { lastPaint[it.kind] = it }
        store.update { it.copy(tool = tool, measure = if (tool == Tool.Measure) emptyList() else it.measure) }
        if (tool !is Tool.Paint || tool.shape != "regions" || tool.kind != old?.kind) regionPick.value = null
        if (tool is Tool.Paint && old != null && tool.kind == old.kind && tool.shape == old.shape && (tool.shape == "fill" || tool.shape == "regions")
            && (tool.edgeAngle != old.edgeAngle || tool.overColours != old.overColours)) refillLast()
        if (tool == Tool.LayerHeight) loadLayerProfile { engine.layerProfile(it) }
        if (tool !is Tool.MeshEdit) meshEditSel.value = null
    }

    /**
     * Handles a tap on the 3D view according to the current tool. [additive] (Ctrl/Shift held) or
     * select mode adds the tapped copy to the selection or removes it. [section] = the section view's
     * plane: what it cuts away can't be picked. [bed]: where the ray meets a plate's bed (measure: placed there when no part is hit).
     */
    fun onViewTap(origin: Vec3, dir: Vec3, additive: Boolean = false, part: Boolean = false, section: FloatArray? = null, emptyPlate: Int? = null,
                  pxSize: ((Vec3) -> Float)? = null, around: List<Pair<Vec3, Vec3>> = emptyList(), bed: Vec3? = null) = store.launch {
        // Measure: Alt held (or Snap off) takes the bare hit.
        val hit = if ((store.value.tool == Tool.Measure || store.value.tool is Tool.Cut) && pxSize != null)
            measurePick(origin, dir, section, pxSize, around, store.value.measureSnap && !part)
            else pick(origin, dir, section)
        if (hit == null && emptyPlate != null && store.value.tool == Tool.None) {
            selectPlate(emptyPlate)
            return@launch
        }
        when (store.value.tool) {
            Tool.LayOnFace -> if (hit != null) {
                store.applyScene(engine.layOnFace(hit.obj, hit.instance, hit.normal))
                store.update { it.withSelected(listOf(Selection(hit.obj, hit.instance))).copy(tool = Tool.None) }
            }
            Tool.Measure -> (hit?.let { MeasurePoint.of(it) } ?: bed?.let { MeasurePoint.bed(it) })?.let { m ->
                store.update { st -> st.copy(measure = (st.measure + m).takeLast(2), measureShiftPlaced = st.measureShift) }
            }
            // Cut: a tap on the cut copy moves the plane through the snapped spot (a hole's rim: its centre), keeping its tilt.
            is Tool.Cut -> if (hit != null && !additive && !part && hit.obj == selection?.obj && hit.instance == selection?.instance) cutThrough(hit, false)
                else store.update { st -> if (hit == null) st else st.withSelected(listOf(Selection(hit.obj, hit.instance))) }
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

    /**
     * Cut tool, long press: on the cut copy the plane turns parallel to the pressed face (facing up) and goes through the spot;
     * anywhere else the context menu opens as usual.
     */
    fun cutAlignAt(origin: Vec3, dir: Vec3, x: Float, y: Float, section: FloatArray?) = store.launch {
        val hit = pick(origin, dir, section)
        if (hit != null && hit.obj == selection?.obj && hit.instance == selection?.instance) cutThrough(hit, true)
        else openContextMenu(origin, dir, x, y, section)
    }

    /** Puts the cut plane through [hit] ([align]: parallel to its face first): by its height, or an upright plane by its sideways shift. */
    private fun cutThrough(hit: PickHit, align: Boolean) {
        val t = store.value.tool as? Tool.Cut ?: return
        val inst = store.value.scene.objects.getOrNull(hit.obj)?.instances?.getOrNull(hit.instance) ?: return
        val rot = if (align) CutPlane.rotFacing(hit.normal) else t.rot
        val p = if (align) hit.point else MeasurePoint.of(hit).point
        val cx = inst.min.x + inst.size.x / 2
        val cy = inst.min.y + inst.size.y / 2
        val z = CutPlane.heightThrough(cx + t.dx, cy + t.dy, rot, p)
        val next = if (z != null) t.copy(z = z, rot = rot, placing = false)
            else CutPlane.shiftThrough(cx, cy, rot, p).let { (dx, dy) -> t.copy(z = inst.min.z + inst.size.z / 2, rot = rot, placing = false, dx = dx, dy = dy) }
        store.update { it.copy(tool = next) }
    }

    fun closeContextMenu() = store.update { it.copy(contextMenu = null) }

    fun setMeasureSnap(on: Boolean) = store.update { it.copy(measureSnap = on) }
    fun setMeasureAxes(on: Boolean) = store.update { it.copy(measureAxes = on) }
    fun setMeasureSteps(on: Boolean) = store.update { it.copy(measureSteps = on) }
    fun setMeasureSteer(on: Boolean) = store.update { it.copy(measureSteer = on) }
    fun setOverhangView(on: Boolean, drag: Float? = null) = store.update { it.copy(overhangView = on, overhangDrag = drag) }
    fun setMeasureShift(held: Boolean) { if (store.value.measureShift != held) store.update { it.copy(measureShift = held) } }

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
    private suspend fun pick(origin: Vec3, dir: Vec3, section: FloatArray?, snap: Double = 0.0, release: Double = 0.0, stick: Vec3? = null,
                             seed: Pair<Vec3, Vec3>? = null): PickHit? {
        if (section == null) return engine.pick(origin, dir, snap = snap, release = release, stick = stick, seed = seed)
        val start = SectionView.rayStart(section, origin, dir) ?: return null
        val seedStart = seed?.let { (o, d) -> SectionView.rayStart(section, o, d)?.let { it to d } }
        return engine.pick(start, dir, snap = snap, release = release, stick = stick, seed = seedStart)?.takeIf { SectionView.kept(section, it.point) }
    }

    /**
     * The measure tool's point: the hit, moved to a corner or sharp edge within [SNAP_PX] screen pixels of it ([snapOn] false: the bare hit).
     * Aimed just off the model, [around] (rays on a ring round the aim, [SNAP_RELEASE] x [SNAP_PX] wide) still find the edge. [sticky] (the loupe moving):
     * the last catch holds out to [SNAP_RELEASE] times that distance.
     */
    private suspend fun measurePick(origin: Vec3, dir: Vec3, section: FloatArray?, pxSize: (Vec3) -> Float, around: List<Pair<Vec3, Vec3>>,
                                    snapOn: Boolean = true, sticky: Boolean = false): PickHit? {
        if (!snapOn) return pick(origin, dir, section)
        val hit = pick(origin, dir, section)
        val seed = if (hit != null) null else around.mapNotNull { r -> pick(r.first, r.second, section)?.let { r to it.point } }
            .minByOrNull { (_, p) -> (p - origin).let { v -> (v - dir * v.dot(dir)).length() } }
        val mm = pxSize(hit?.point ?: seed?.second ?: return null) * SNAP_PX
        val stick = if (sticky) measureStick else null
        val snapped = pick(origin, dir, section, snap = mm.toDouble(), release = (mm * SNAP_RELEASE).toDouble(), stick = stick, seed = seed?.first)
        if (sticky) measureStick = snapped?.takeIf { it.snap != null }?.point
        // Aimed off the model, only a caught corner or edge counts: the face the seed found is not under the aim (the bed is).
        if (hit == null && snapped?.snap == null) return null
        return snapped ?: hit
    }

    /** The loupe's last snap (corner or edge point), which holds while the aim stays near it. */
    @Volatile private var measureStick: Vec3? = null
    @Volatile private var loupeActive = false

    /**
     * Measure tool, touch and hold (or a mouse / pen hovering): the dot follows the aim (x, y) and snaps to corners and sharp
     * edges; lifting (a click) places it. [phase] 0 = down, 1 = move, 2 = up (places), 3 = cancel, 4 = hover (shows only).
     * [grab] (down only): the placed point under the pointer, picked up to be placed again. [bed]: where the aim meets a
     * plate's bed (used when no part is hit).
     */
    fun measureLoupe(origin: Vec3, dir: Vec3, x: Float, y: Float, phase: Int, section: FloatArray?, pxSize: (Vec3) -> Float,
                     around: List<Pair<Vec3, Vec3>>, grab: Int? = null, bed: Vec3? = null) {
        // A hover's first move, or a click with no hover before it (a pen without hover), starts fresh.
        if (phase == 0 || (phase == 4 || phase == 2) && !loupeActive) measureStick = null
        if (phase == 0 || phase == 4) loupeActive = true
        else if (phase == 2 || phase == 3) loupeActive = false
        // A picked-up point is selected again where it lands; a new point or a cancel leaves nothing selected.
        if (phase == 0) measureGrabbed = grab != null
        val reselect = phase == 2 && measureGrabbed
        if (phase == 2 || phase == 3) measureGrabbed = false
        store.launch {
            if (phase == 0 && grab != null) store.update { st -> st.copy(measure = st.measure.filterIndexed { i, _ -> i != grab }) }
            if (phase == 3) { store.update { it.copy(loupe = null, measureActive = null) }; return@launch }
            val hit = measurePick(origin, dir, section, pxSize, around, store.value.measureSnap, sticky = true)
            if (app.orcaandroid.BuildConfig.DEBUG)
                android.util.Log.d("SNAPDBG", "phase $phase snap ${hit?.snap} feature ${hit?.feature?.kind} d ${hit?.feature?.diameter} at ${hit?.point}")
            if (phase == 2) {
                val m = hit?.let { MeasurePoint.of(it) } ?: bed?.let { MeasurePoint.bed(it) }
                store.update { st ->
                    val placed = if (m != null) (st.measure + m).takeLast(2) else st.measure
                    st.copy(loupe = null, measure = placed, measureActive = if (reselect && m != null) placed.lastIndex else null,
                        measureShiftPlaced = if (m != null) st.measureShift else st.measureShiftPlaced)
                }
            } else if (loupeActive) {
                val l = if (hit == null && bed != null) Loupe(x, y, bed, null, app.orcaandroid.core.SnapFeature("bed", FloatArray(0), null), MeasurePoint.bed(bed))
                    else Loupe(x, y, hit?.point, hit?.snap, hit?.feature, hit?.let { MeasurePoint.of(it) })
                store.update { it.copy(loupe = l) }
            }
        }
    }

    /** Measure: the loupe began on a placed point (picked up). */
    private var measureGrabbed = false

    /** Measure: selects placed point [index] (null = none; the point stays where it is). */
    fun measureSelect(index: Int?) = store.update { it.copy(measureActive = index?.takeIf { i -> i in it.measure.indices }) }

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
        if (newStroke) paintDown = true
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
                            regionPick.value = null
                        } else {
                            store.applyScene(fill(tool, hit, last.camera, last.section, !fillShown))
                            lastFill = Triple(hit, last.camera, last.section)
                            fillShown = true
                            regionPick.value = if (tool.shape == "regions")
                                RegionPick(hit, last.camera, last.section, tool.edgeAngle, mapOf(tool.kind to tool.state)) else null
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
                    if (paintDown && brushRing(tool)) sculptCursor.value = hits.last().let { it.point to it.normal }
                    val last = batch.last()
                    store.applyScene(engine.paint(hits, last.camera, tool.radius, tool.kind, tool.state, strokePending, last.section, joins, tool.edgeAngle,
                        tool.shape, tool.height, tool.overhang, tool.guardZ.takeIf { tool.guardOn }, locked = tool.locked))
                    strokePending = false
                }
            }
        }
    }

    /** The last fill's seed (hit, camera, section): a crease angle change redoes that fill from it while it is the live preview. */
    private var lastFill: Triple<PickHit, Vec3, FloatArray?>? = null
    private var refillJob: Job? = null
    private var refillWanted = false

    private suspend fun fill(tool: Tool.Paint, hit: PickHit, camera: Vec3, section: FloatArray?, newStroke: Boolean, refill: Boolean = false) =
        engine.paint(listOf(hit), camera, tool.radius, tool.kind, tool.state, newStroke, section, listOf(false), tool.edgeAngle, "fill", tool.height,
            tool.overhang, tool.guardZ.takeIf { tool.guardOn }, tool.overColours || tool.shape == "regions", tool.locked, refill)

    /**
     * Mask as explanation (interaction plan step 7): the crease angle (or "Fill over colours") changed, so the last fill grows or shrinks
     * live from the same seed. The engine skips it once another edit came after the fill. Slider moves are conflated: only the newest runs.
     */
    private fun refillLast() {
        synchronized(pendingPaint) {
            refillWanted = true
            if (refillJob != null) return
            refillJob = store.launch {
                while (true) {
                    val go = synchronized(pendingPaint) { refillWanted.also { refillWanted = false; if (!it) refillJob = null } }
                    if (!go) break
                    paintLock.withLock {
                        // Not live (another region edit came after it): the region it showed no longer matches the angle.
                        val f = lastFill ?: run { regionPick.value = null; return@withLock }
                        val tool = (store.value.tool as? Tool.Paint)?.takeIf { it.shape == "fill" || it.shape == "regions" } ?: return@withLock
                        runCatching { fill(tool, f.first, f.second, f.third, newStroke = false, refill = true) }.getOrNull()?.let { store.applyScene(it) }
                        regionPick.value = regionPick.value?.let { RegionPick(it.hit, it.camera, it.section, tool.edgeAngle, it.applied) }
                    }
                }
            }
        }
    }

    /**
     * Per-region edits (interaction plan step 8b): the last Regions tap's seed, its crease angle and what was put on it (kind -> state).
     * A Regions fill ignores existing paint, so a fill from the same seed covers the same faces in any paint kind.
     */
    class RegionPick(val hit: PickHit, val camera: Vec3, val section: FloatArray?, val edgeAngle: Float, val applied: Map<String, Int>)
    val regionPick = kotlinx.coroutines.flow.MutableStateFlow<RegionPick?>(null)

    /** Paints the picked region with [kind] / [state] (0 = clears that kind there); one undo step each. */
    fun paintRegion(kind: String, state: Int) = store.launch {
        paintLock.withLock {
            val r = regionPick.value ?: return@withLock
            val tool = store.value.tool as? Tool.Paint ?: return@withLock
            runCatching {
                engine.paint(listOf(r.hit), r.camera, tool.radius, kind, state, true, r.section, listOf(false), r.edgeAngle, "fill", tool.height, 0f,
                    tool.guardZ.takeIf { tool.guardOn }, true, if (kind == "color") tool.locked else 0)
            }.getOrNull()?.let { store.applyScene(it) } ?: return@withLock
            // The live fill is now this one: a crease change would redo it with the tool's kind and state, so it no longer refills.
            lastFill = null
            regionPick.value = RegionPick(r.hit, r.camera, r.section, r.edgeAngle, r.applied + (kind to state))
        }
    }

    /**
     * Per-region geometry edits (step 8b slice 2): "smooth" = [amount] Taubin iterations on the picked region, border pinned (same faces,
     * so the pick and its painting stay); "modifier" = a modifier volume [amount] mm into the part under the region. One undo step each.
     */
    fun editRegion(op: String, amount: Float) = store.launch(busy = store.str(R.string.region_working)) {
        paintLock.withLock {
            val r = regionPick.value ?: return@withLock
            store.applyScene(engine.regionEdit(r.hit.obj, r.hit.instance, r.hit.volume, r.hit.facet, r.edgeAngle, op, amount))
            // A crease change must not redo the fill over the edited mesh.
            lastFill = null
        }
    }

    /** The paint brush ring (in [sculptCursor]): a finger or button is down painting, so the stroke's hits move it, not the hover. */
    @Volatile private var paintDown = false
    /** The latest paint hover ray (and section) not yet probed; only the newest counts. Guarded by [pendingPaint]. */
    private var paintHoverRay: Pair<Pair<Vec3, Vec3>, FloatArray?>? = null
    private var paintHoverJob: Job? = null

    /** Brushes with a size show their ring on the surface (interaction plan step 7). */
    private fun brushRing(tool: Tool?) = tool is Tool.Paint && (tool.shape == "sphere" || tool.shape == "circle")

    /** A paint stroke ended: the ring goes until the next hover or stroke. */
    fun paintEnd() {
        if (store.value.tool !is Tool.Paint) return
        paintDown = false
        sculptCursor.value = null
    }

    /** A mouse or pen hovers along this ray (null: it left the view): the paint brush ring follows the surface under it. */
    fun paintHover(ray: Pair<Vec3, Vec3>?, section: FloatArray?) {
        if (!brushRing(store.value.tool)) return
        synchronized(pendingPaint) {
            paintHoverRay = ray?.let { it to section }
            if (ray == null) { sculptCursor.value = null; return }
            if (paintHoverJob != null) return
            paintHoverJob = store.launch {
                while (true) {
                    // The job ends (and a new ray starts the next) inside the lock, so no ray waits unprobed.
                    val (r, sec) = synchronized(pendingPaint) { paintHoverRay.also { paintHoverRay = null; if (it == null) paintHoverJob = null } } ?: break
                    val hit = runCatching { pick(r.first, r.second, sec) }.getOrNull()
                    if (!paintDown && brushRing(store.value.tool)) sculptCursor.value = hit?.let { it.point to it.normal }
                }
            }
        }
    }

    /**
     * Mesh edit ([Tool.MeshEdit]): the selection on [target] in world mm, as the engine sent it: [edges] = crease segments
     * (x0 y0 z0 x1 y1 z1 each), [verts] = selected vertices (first 500). Holds for scene mesh [version] only.
     */
    class MeshEditSel(val target: PickHit, val count: Int, val centroid: Vec3, val normal: Vec3, val edges: FloatArray,
                      val verts: FloatArray, val version: Long)
    val meshEditSel = kotlinx.coroutines.flow.MutableStateFlow<MeshEditSel?>(null)
    private val meshEditLock = Any()
    /** The newest arrow distance (mm) and whether the finger was lifted, not yet sent. Guarded by [meshEditLock]. */
    private var meshEditPending: Pair<Float, Boolean>? = null
    private var meshEditRunning = false
    /** A move went out since the last end. Used on the drain's coroutine only. */
    private var meshEditMoved = false

    private fun meshEditSelOf(target: PickHit, o: org.json.JSONObject): MeshEditSel? {
        val count = o.optInt("count")
        if (count == 0) return null
        fun v(k: String) = o.getJSONArray(k).let { Vec3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
        fun f(k: String) = o.optJSONArray(k)?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } ?: FloatArray(0)
        return MeshEditSel(target, count, v("centroid"), v("normal"), f("edges"), f("verts"), store.value.scene.meshVersion.toLong())
    }

    private fun meshEditAngle() = (store.value.tool as? Tool.MeshEdit)?.let { if (it.triangles) 0f else 30f } ?: 30f

    /** The current selection, dropped when the scene mesh changed since (undo, another edit): the engine dropped it too. */
    private fun liveMeshEditSel() = meshEditSel.value?.takeIf { it.version == store.value.scene.meshVersion.toLong() }
        ?: null.also { meshEditSel.value = null }

    /**
     * Mesh edit tap: toggles the pick under the ray on the part it hits (that object becomes the selection). [reach]: world mm
     * of 24 dp at a point (vertex picks snap to a feature corner that close). A miss keeps the selection.
     */
    fun meshEditTap(origin: Vec3, dir: Vec3, section: FloatArray?, reach: (Vec3) -> Float) = store.launch {
        val tool = store.value.tool as? Tool.MeshEdit ?: return@launch
        val hit = pick(origin, dir, section) ?: return@launch
        if (hit.obj != selection?.obj || hit.instance != selection?.instance) store.update { it.withSelected(listOf(Selection(hit.obj, hit.instance))) }
        if (tool.cut in 0..2) {
            // Cut ring: the plane through the tap across the axis splits the band there; the ring becomes the selection.
            val axis = List(3) { if (it == tool.cut) 1.0 else 0.0 }
            val step = engine.meshEdit(hit, "cut", origin to dir, tool.kind, meshEditAngle(), axis = axis)
            step.scene?.let { store.applyScene(it) }
            meshEditSel.value = meshEditSelOf(hit, step.selection)
            return@launch
        }
        val same = liveMeshEditSel()?.target?.let { it.obj == hit.obj && it.instance == hit.instance && it.volume == hit.volume } == true
        val step = engine.meshEdit(hit, "select", origin to dir, tool.kind, meshEditAngle(), toggle = same, reach = reach(hit.point))
        step.scene?.let { store.applyScene(it) }
        meshEditSel.value = meshEditSelOf(hit, step.selection)
    }

    /** Mesh edit "clear", "all" or "grow" on the current selection. */
    fun meshEditOp(op: String) = store.launch {
        val sel = liveMeshEditSel() ?: return@launch
        val step = engine.meshEdit(sel.target, op, kind = (store.value.tool as? Tool.MeshEdit)?.kind ?: "face", angle = meshEditAngle())
        meshEditSel.value = meshEditSelOf(sel.target, step.selection)
    }

    /** Mesh edit typed transform: [matrix] ([meshEditMatrix]) about the selection's centroid, one undo step. */
    fun meshEditTransform(matrix: List<Double>) = store.launch {
        val sel = liveMeshEditSel() ?: return@launch
        engine.meshEdit(sel.target, "move", matrix = matrix)
        val step = engine.meshEdit(sel.target, "end", kind = (store.value.tool as? Tool.MeshEdit)?.kind ?: "face", angle = meshEditAngle())
        step.scene?.let { store.applyScene(it) }
        meshEditSel.value = meshEditSelOf(sel.target, step.selection)
    }

    /** Mesh edit typed extrude ("extrude": [mm] along the selection's normal, < 0 = pocket), "inset" ([mm] in), "fillet" or "chamfer" ([mm] radius / size on the selection's creases), one undo step. */
    fun meshEditFaces(op: String, mm: Float) = store.launch {
        val sel = liveMeshEditSel() ?: return@launch
        val kind = (store.value.tool as? Tool.MeshEdit)?.kind ?: "face"
        val t = sel.normal * mm
        val step = if (op != "extrude") engine.meshEdit(sel.target, op, kind = kind, angle = meshEditAngle(), amount = mm)
        else {
            engine.meshEdit(sel.target, "extrude", matrix = translation(t))
            engine.meshEdit(sel.target, "end", kind = kind, angle = meshEditAngle())
        }
        step.scene?.let { store.applyScene(it) }
        meshEditSel.value = meshEditSelOf(sel.target, step.selection)
    }

    private fun translation(t: Vec3) = listOf(1.0, 0.0, 0.0, t.x.toDouble(), 0.0, 1.0, 0.0, t.y.toDouble(), 0.0, 0.0, 1.0, t.z.toDouble(), 0.0, 0.0, 0.0, 1.0)

    /**
     * The selection's arrow is dragged: the selection moves [d] mm along its normal, live (only the newest distance is sent);
     * [done] once let go (one undo step per drag), [d] null when cancelled (back to the start). With [Tool.MeshEdit.extrude]
     * the first step extrudes the selected faces instead (new mesh and selection, then the cap moves).
     */
    fun meshEditDrag(d: Float?, done: Boolean) {
        synchronized(meshEditLock) {
            meshEditPending = (d ?: 0f) to done
            if (meshEditRunning) return
            meshEditRunning = true
        }
        store.launch {
            try {
                while (true) {
                    val (mm, end) = synchronized(meshEditLock) { meshEditPending.also { meshEditPending = null } } ?: break
                    val sel = liveMeshEditSel() ?: continue
                    if (mm != 0f || meshEditMoved) {
                        val extrude = !meshEditMoved && (store.value.tool as? Tool.MeshEdit)?.extrude == true
                        val step = engine.meshEdit(sel.target, if (extrude) "extrude" else "move", matrix = translation(sel.normal * mm))
                        if (extrude) {
                            // A new mesh (walls, cap copies): its scene first, then the cap as the selection for the version check.
                            step.scene?.let { store.applyScene(it) }
                            meshEditSel.value = meshEditSelOf(sel.target, step.selection)
                        }
                        step.patch?.let { meshPatches.tryEmit(it) }
                        meshEditMoved = true
                    }
                    if (end && meshEditMoved) {
                        meshEditMoved = false
                        val step = engine.meshEdit(sel.target, "end", kind = (store.value.tool as? Tool.MeshEdit)?.kind ?: "face", angle = meshEditAngle())
                        step.scene?.let { store.applyScene(it) }
                        meshEditSel.value = meshEditSelOf(sel.target, step.selection)
                    }
                }
            } finally {
                synchronized(meshEditLock) { meshEditRunning = false }
            }
            // A sample queued after the loop's last look but before the flag was down.
            synchronized(meshEditLock) { meshEditPending?.also { meshEditPending = null } }?.let { (mm, end) -> meshEditDrag(mm, end) }
        }
    }

    /** The live sculpt's changed triangles, for the 3D view to copy into its scene buffer. */
    val meshPatches = kotlinx.coroutines.flow.MutableSharedFlow<MeshPatch>(extraBufferCapacity = 64)
    private class SculptSample(val origin: Vec3, val dir: Vec3, val newStroke: Boolean, val end: Boolean)
    private val pendingSculpt = ArrayDeque<SculptSample>()
    /** The part this stroke sculpts (picked at its first sample), or null before / without a hit. Guarded by [paintLock]. */
    private var sculptTarget: PickHit? = null
    /** The brush ring: world point and surface normal under the finger, mouse or pen, or null (lifted, off the part). */
    val sculptCursor = kotlinx.coroutines.flow.MutableStateFlow<Pair<Vec3, Vec3>?>(null)
    /** The latest hover ray not yet probed (only the newest counts). Guarded by [pendingSculpt]. */
    private var hoverRay: Pair<Vec3, Vec3>? = null
    /** The part the last hover or stroke was on, probed first. Guarded by [paintLock]. */
    private var hoverTarget: PickHit? = null

    /**
     * Sculpt sample from the 3D view, queued like [paintAt]: every sample that waited while the engine worked goes into
     * the next call. The engine hits the rays on the part as sculpted so far and sends back only the changed triangles.
     */
    fun sculptAt(origin: Vec3, dir: Vec3, newStroke: Boolean) = queueSculpt(SculptSample(origin, dir, newStroke, false))

    /** The finger lifted: the stroke's mesh goes into the part (and the scene, its outline and slicing). */
    fun sculptEnd() = queueSculpt(SculptSample(Vec3.ZERO, Vec3.ZERO, false, true))

    /** A mouse or pen hovers along this ray (null: it left the view): the brush ring follows the surface under it. */
    fun sculptHover(ray: Pair<Vec3, Vec3>?) {
        if (store.value.tool !is Tool.Sculpt) return
        synchronized(pendingSculpt) { hoverRay = ray }
        if (ray == null) sculptCursor.value = null else drainSculpt()
    }

    /** The surface under [ray] on the selected object: a hit test on the sculpt session's part, else a scene pick. */
    private suspend fun probeHover(ray: Pair<Vec3, Vec3>) {
        val tool = store.value.tool as? Tool.Sculpt
        val sel = selection
        if (tool == null || sel == null) { sculptCursor.value = null; return }
        val step = hoverTarget?.takeIf { it.obj == sel.obj }?.let {
            runCatching { engine.sculpt(it, listOf(ray), tool.brush, tool.radius, tool.strength, tool.invert, newStroke = false, probe = true) }.getOrNull()
        }
        if (step?.cursor != null) { sculptCursor.value = step.cursor; return }
        val hit = runCatching { engine.pick(ray.first, ray.second, only = sel.obj) }.getOrNull()
        hoverTarget = hit
        sculptCursor.value = hit?.let { it.point to it.normal }
    }

    private fun queueSculpt(sample: SculptSample) {
        if (store.value.tool !is Tool.Sculpt) return
        synchronized(pendingSculpt) { pendingSculpt.addLast(sample) }
        drainSculpt()
    }

    private fun drainSculpt() {
        if (paintLock.isLocked) return
        store.launch {
            paintLock.withLock {
                while (true) {
                    val batch = synchronized(pendingSculpt) {
                        val out = mutableListOf<SculptSample>()
                        while (pendingSculpt.isNotEmpty() && (out.isEmpty() || !pendingSculpt.first().newStroke && !out.last().end)) out += pendingSculpt.removeFirst()
                        out
                    }
                    if (batch.isEmpty()) {
                        // Strokes first; a hover waits for them, then only its newest ray is probed.
                        val ray = synchronized(pendingSculpt) { hoverRay.also { hoverRay = null } } ?: break
                        probeHover(ray)
                        continue
                    }
                    val tool = store.value.tool as? Tool.Sculpt
                    val first = batch.first()
                    if (first.newStroke) {
                        val sel = selection
                        sculptTarget = if (tool == null || sel == null) null else engine.pick(first.origin, first.dir, only = sel.obj)
                    }
                    val target = sculptTarget
                    val rays = batch.filter { !it.end }.map { it.origin to it.dir }
                    if (target != null && tool != null && rays.isNotEmpty()) {
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        val step = engine.sculpt(target, rays, tool.brush, tool.radius, tool.strength, tool.invert, first.newStroke, mirror = tool.mirror)
                        android.util.Log.d("OrcaSculpt", "${rays.size} ray(s) ${android.os.SystemClock.elapsedRealtime() - t0} ms, patch ${step.patch?.counts?.sum() ?: 0} tris")
                        step.scene?.let { store.applyScene(it) }
                        step.patch?.let { meshPatches.tryEmit(it) }
                        step.cursor?.let { sculptCursor.value = it }
                        hoverTarget = target
                    }
                    if (batch.last().end) sculptCursor.value = null
                    if (batch.last().end && target != null) {
                        sculptTarget = null
                        store.applyScene(engine.sculptEnd())
                    }
                }
            }
            // A sample (the finger's lift above all) queued after the loop's last look but before the lock was free.
            if (synchronized(pendingSculpt) { pendingSculpt.isNotEmpty() || hoverRay != null }) drainSculpt()
        }
    }

    fun paintClear(kind: String) = selection?.let { sel -> store.sceneOp { engine.paintClear(sel.obj, kind) } }
    fun paintRemap(dest: List<Int>, base: Boolean = false) = selection?.let { sel -> store.sceneOp { engine.paintRemap(sel.obj, dest, base) } }
    suspend fun paintCoverage(): FloatArray? = selection?.let { engine.paintCoverage(it.obj) }
    /** Regions view: the selected copy's crease lines, or null without a selection. */
    suspend fun creaseLines(angle: Float): FloatArray? = selection?.let { engine.creaseLines(it.obj, it.instance, angle) }
    /** Edge outline: crease lines of every copy on the active plate (capped so a big plate stays cheap). */
    suspend fun plateCreaseLines(angle: Float): FloatArray {
        val s = store.value
        val out = ArrayList<FloatArray>()
        var total = 0
        for ((o, obj) in s.scene.objects.withIndex()) for ((i, inst) in obj.instances.withIndex()) {
            if (inst.plate != s.activePlate || total > 600_000) continue
            val l = engine.creaseLines(o, i, angle)
            total += l.size; out += l
        }
        val all = FloatArray(total)
        var at = 0
        for (l in out) { l.copyInto(all, at); at += l.size }
        return all
    }
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
