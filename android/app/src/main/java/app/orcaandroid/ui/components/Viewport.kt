package app.orcaandroid.ui.components

import app.orcaandroid.ui.theme.LocalAppColors
import app.orcaandroid.ui.theme.Region
import app.orcaandroid.ui.theme.Slot
import app.orcaandroid.ui.theme.VIEW_ACTIVE_PLATE
import app.orcaandroid.ui.theme.VIEW_BACKGROUND
import app.orcaandroid.ui.theme.VIEW_GRID
import app.orcaandroid.ui.theme.VIEW_PART
import app.orcaandroid.ui.theme.VIEW_SELECTION
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orcaandroid.core.SliceResult
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.FloatData
import app.orcaandroid.render.INSTANCE_ID_STRIDE
import app.orcaandroid.render.InteractionMode
import app.orcaandroid.render.PlateBadge
import app.orcaandroid.render.PlateListener
import app.orcaandroid.render.PlateView
import app.orcaandroid.render.PreviewData
import app.orcaandroid.render.PreviewRange
import app.orcaandroid.render.PreviewStyle
import app.orcaandroid.render.TowerBox
import app.orcaandroid.render.ViewColors
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Screen
import app.orcaandroid.ui.Selection
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.prepare.PlateMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Parses "#RRGGBB" into 0..1 RGB. */
fun hexToRgb(hex: String?): FloatArray {
    val h = hex?.removePrefix("#")?.take(6) ?: return floatArrayOf(0.8f, 0.8f, 0.8f)
    val v = h.toLongOrNull(16) ?: return floatArrayOf(0.8f, 0.8f, 0.8f)
    return floatArrayOf(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f)
}

private fun Color.rgb() = floatArrayOf(red, green, blue)

/** Visible index ranges of the preview buffers for the chosen layers / moves. */
fun previewRange(result: SliceResult, low: Int, high: Int, moveEnd: Int?, counts: Triple<Int, Int, Int>): PreviewRange {
    val layers = result.layers
    if (layers.isEmpty()) return PreviewRange()
    val lo = low.coerceIn(0, layers.lastIndex)
    val hi = high.coerceIn(lo, layers.lastIndex)
    val next = layers.getOrNull(hi + 1)
    val eEnd = moveEnd?.let { layers[hi].extrusion + it } ?: (next?.extrusion ?: counts.first)
    return PreviewRange(
        layers[lo].extrusion until eEnd,
        layers[lo].travel until (next?.travel ?: counts.second),
        layers[lo].marker until (next?.marker ?: counts.third),
    )
}

/**
 * The shared 3D view of the Prepare and Preview screens. Pushes scene, selection, tool and
 * preview state to the GL thread and turns touches into view-model actions.
 */
@Composable
fun Viewport(state: UiState, vm: AppViewModel, onView: (PlateView) -> Unit, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<PlateView?>(null) }
    var preview by remember { mutableStateOf<PreviewData?>(null) }
    val showPreview = state.screen == Screen.PREVIEW
    val result = state.shownResult

    val scheme = MaterialTheme.colorScheme
    // The 3D view's colours (theme defaults or the user's, design mode).
    val appColors = LocalAppColors.current
    fun slot(s: Slot) = appColors.color(Region.VIEW_3D, s).rgb()
    val colors = ViewColors(
        background = slot(VIEW_BACKGROUND),
        grid = slot(VIEW_GRID),
        activePlate = slot(VIEW_ACTIVE_PLATE),
        part = slot(VIEW_PART),
        selected = slot(VIEW_SELECTION),
    )

    // The selection rectangle being drawn (Shift/Alt+drag), in view pixels: x0, y0, x1, y1.
    var rectangle by remember { mutableStateOf<FloatArray?>(null) }
    val dragPending = remember { booleanArrayOf(false) }
    // The plate menu opened from a plate's menu badge: plate, view x, y.
    var plateMenu by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    val rectangleColor = scheme.primary
    // Live size (mm) beside the finger while a box corner is dragged: text, view x, y.
    var resizeLabel by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    Box(modifier) {
    AndroidView(
        factory = { ctx ->
            PlateView(ctx).also { v ->
                PlateView.active = java.lang.ref.WeakReference(v)
                v.listener = object : PlateListener {
                    override fun onTap(x: Float, y: Float, additive: Boolean, part: Boolean, emptyPlate: Int?) {
                        val (o, d) = v.camera.ray(x, y)
                        vm.scene.onViewTap(o, d, additive, part, v.section.current, emptyPlate)
                    }
                    override fun onCopyAt(x: Float, y: Float, result: (Pair<Int, Int>?, Vec3, Vec3) -> Unit) {
                        val (o, d) = v.camera.ray(x, y)
                        vm.scene.copyAt(o, d, v.section.current) { copy, lo, hi -> v.post { result(copy, lo, hi) } }
                    }
                    override fun onDragCheck(x: Float, y: Float, result: (Boolean) -> Unit) {
                        val (o, d) = v.camera.ray(x, y)
                        vm.scene.checkDragStart(o, d, v.section.current) { hit -> v.post { result(hit) } }
                    }
                    override fun onRectangle(x0: Float, y0: Float, x1: Float, y1: Float, remove: Boolean, done: Boolean) {
                        rectangle = if (done) null else floatArrayOf(x0, y0, x1, y1)
                        if (!done) return
                        // Copies whose bounding box centre falls inside the rectangle, as on the desktop.
                        val (left, right) = minOf(x0, x1) to maxOf(x0, x1)
                        val (top, bottom) = minOf(y0, y1) to maxOf(y0, y1)
                        val inside = vm.state.value.scene.objects.flatMap { o ->
                            o.instances.filter { inst ->
                                v.camera.project(inst.min + inst.size * 0.5f)?.let { (px, py) -> px in left..right && py in top..bottom } == true
                            }.map { Selection(o.index, it.index) }
                        }
                        vm.scene.changeSelection(inside, remove)
                    }
                    override fun onResizeCorner(factor: Float, anchor: Vec3, x: Float, y: Float, done: Boolean) {
                        val sel = vm.state.value.selectedItems.singleOrNull()
                        val inst = sel?.let { vm.state.value.scene.objects.getOrNull(it.obj)?.instances?.getOrNull(it.instance) }
                        if (!done && inst != null) {
                            val z = inst.size * factor
                            resizeLabel = Triple("%.1f x %.1f x %.1f mm".format(z.x, z.y, z.z), x, y)
                            return
                        }
                        resizeLabel = null
                        if (!done || factor <= 0f || inst == null) { v.onGl { resizeScale = 1f }; return }
                        // The live scale stays until the scaled mesh is uploaded (cleared there), or after a timeout.
                        dragPending[0] = true
                        v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { resizeScale = 1f } } }, 1500)
                        // Uniform scale about the opposite corner: its position stays, the offset follows it (x, y; the engine drops onto the bed).
                        val off = Vec3(anchor.x + (inst.offset.x - anchor.x) * factor, anchor.y + (inst.offset.y - anchor.y) * factor, inst.offset.z)
                        vm.scene.setTransform(offset = off, scale = inst.scale * factor)
                    }
                    override fun onBrushScroll(steps: Int) = vm.scene.scaleBrush(if (steps > 0) 1.15f else 1 / 1.15f)
                    override fun onMoved(dx: Float, dy: Float) {
                        // The drag offset stays until the moved mesh is uploaded (below); the timer covers a move
                        // that changes nothing or fails.
                        dragPending[0] = true
                        vm.scene.moveSelected(dx, dy)?.invokeOnCompletion {
                            v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { dragOffset = floatArrayOf(0f, 0f, 0f) } } }, 500)
                        } ?: run { dragPending[0] = false; v.onGl { dragOffset = floatArrayOf(0f, 0f, 0f) } }
                    }
                    override fun onTowerMoved(plate: Int, dx: Float, dy: Float) {
                        val t = vm.state.value.scene.plates.getOrNull(plate)?.tower ?: return
                        dragPending[0] = true
                        // The engine keeps the position on the bed, so a drop past the edge stops at it.
                        vm.scene.setWipeTower(plate, (t[0] + dx) to (t[1] + dy)).invokeOnCompletion {
                            v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { dragOffset = floatArrayOf(0f, 0f, 0f) } } }, 500)
                        }
                    }
                    override fun onPaint(x: Float, y: Float, newStroke: Boolean) {
                        val (o, d) = v.camera.ray(x, y)
                        vm.scene.paintAt(o, d, v.camera.eye(), newStroke, v.section.current)
                    }
                    override fun onPlateDoubleTap(index: Int) = vm.scene.selectPlate(index)
                    // Icons act on their plate, which becomes the active one (except delete), as on the desktop.
                    override fun onPlateBadge(plate: Int, badge: PlateBadge, x: Float, y: Float) {
                        when (badge) {
                            PlateBadge.NUMBER -> vm.scene.selectPlate(plate)
                            PlateBadge.DELETE -> vm.scene.deletePlate(plate)
                            PlateBadge.ARRANGE -> { vm.scene.selectPlate(plate); vm.scene.arrange(false) }
                            PlateBadge.MENU -> { vm.scene.selectPlate(plate); plateMenu = Triple(plate, x, y) }
                        }
                    }

                    override fun onPenAction(action: app.orcaandroid.core.PenAction) {
                        when (action) {
                            app.orcaandroid.core.PenAction.UNDO -> vm.scene.undo()
                            app.orcaandroid.core.PenAction.REDO -> vm.scene.redo()
                            app.orcaandroid.core.PenAction.FRAME -> v.onGl { frame(activePlate) }
                            else -> Unit
                        }
                    }

                    override fun onContextMenu(x: Float, y: Float) {
                        val (o, d) = v.camera.ray(x, y)
                        vm.scene.openContextMenu(o, d, x, y, v.section.current)
                    }
                }
                view = v
                onView(v)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    rectangle?.let { r ->
        Canvas(Modifier.fillMaxSize()) {
            val topLeft = androidx.compose.ui.geometry.Offset(minOf(r[0], r[2]), minOf(r[1], r[3]))
            val size = androidx.compose.ui.geometry.Size(kotlin.math.abs(r[2] - r[0]), kotlin.math.abs(r[3] - r[1]))
            drawRect(rectangleColor.copy(alpha = 0.15f), topLeft, size)
            drawRect(rectangleColor, topLeft, size, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
    }
    val label = resizeLabel
    if (label != null) {
        androidx.compose.material3.Surface(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 72.dp), shape = MaterialTheme.shapes.small, tonalElevation = 4.dp) {
            androidx.compose.material3.Text(label.first, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
        }
    } else if (state.resizing && !showPreview) {
        // While corner resizing is on: what to do, and the way out on a touchscreen.
        androidx.compose.material3.Surface(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 72.dp), shape = MaterialTheme.shapes.small, tonalElevation = 4.dp) {
            androidx.compose.foundation.layout.Row(Modifier.padding(start = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.resize_hint), style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.TextButton(onClick = vm.scene::toggleResize) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.done))
                }
            }
        }
    }
    plateMenu?.let { (plate, x, y) ->
        Box(Modifier.offset { IntOffset(x.toInt(), y.toInt()) }) { PlateMenu(state, vm, plate) { plateMenu = null } }
    }
    view?.section?.takeIf { it.panelOpen }?.let {
        // Below the top bar (and a camera hole in fullscreen portrait) and clear of the tool buttons down the left edge.
        SectionViewPanel(it, Modifier.align(androidx.compose.ui.Alignment.TopCenter)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Top)).padding(top = 72.dp, start = 80.dp, end = 8.dp).widthIn(max = 420.dp))
    }
    }

    val v = view ?: return
    DisposableEffect(v) { onDispose { savedCamera = v.camera.state() } }
    // Restored here, not in the factory: a new view can be made before the old one's onDispose has saved.
    val restored = remember(v) { savedCamera?.also(v.camera::restore) != null }
    val setup = state.setup
    val scene = state.scene

    LaunchedEffect(v, colors) { v.onGl { this.colors = colors } }
    LaunchedEffect(v, state.contactShadows) {
        v.onGl { contactShadows = state.contactShadows }
    }
    LaunchedEffect(v, state.selectionStyle) {
        v.onGl { selectionStyle = state.selectionStyle }
    }
    LaunchedEffect(v, state.lab.shadowStrength, state.lab.shadowSoft, state.lab.shadowBias) {
        v.onGl { shadowStrength = state.lab.shadowStrength; shadowSoftMm = state.lab.shadowSoft; shadowBiasMm = state.lab.shadowBias }
    }
    LaunchedEffect(v, state.lab.lightAzimuth, state.lab.lightElevation, state.lab.lightStrength) {
        v.onGl { keyAzimuth = state.lab.lightAzimuth; keyElevation = state.lab.lightElevation; keyStrength = state.lab.lightStrength }
    }
    LaunchedEffect(v, state.lab.pinchSpeed) { v.pinchSpeed = state.lab.pinchSpeed }
    LaunchedEffect(v, state.lab.wheelSpeed) { v.wheelSpeed = state.lab.wheelSpeed }
    LaunchedEffect(v, state.perspective) { v.camera.perspective = state.perspective }
    LaunchedEffect(v, state.pen) { v.pen = state.pen }
    LaunchedEffect(v, state.resizing, showPreview) { v.resizeMode = state.resizing && !showPreview }
    // Plate numbers always; the plate icons only while arranging (Prepare).
    val atlas = rememberBadgeAtlas(scene.plates.size)
    val badges = if (showPreview) emptyList()
        else listOfNotNull(PlateBadge.DELETE.takeIf { scene.plates.size > 1 }, PlateBadge.ARRANGE, PlateBadge.MENU)
    LaunchedEffect(v, atlas, badges) { v.onGl { setBadges(atlas, badges) } }
    LaunchedEffect(v, setup?.bed, scene.plates.map { it.originX to it.originY }) {
        val bed = setup?.bed ?: return@LaunchedEffect
        val origins = scene.plates.map { it.originX to it.originY }
        v.onGl {
            val first = plateOriginsCount == 0
            setBed(bed, origins)
            plateOriginsCount = origins.size
            if (first && !restored) frame(state.activePlate)
        }
    }
    LaunchedEffect(v, scene.meshVersion, scene.meshFile) {
        if (scene.meshFile.isEmpty()) return@LaunchedEffect
        val mesh = withContext(Dispatchers.IO) { FloatData.load(scene.meshFile, 8) }
        v.onGl { setMesh(mesh) }
        // A finished drag: the moved mesh is queued first, so the old offset goes away without a jump back.
        if (dragPending[0]) { dragPending[0] = false; v.onGl { dragOffset = floatArrayOf(0f, 0f, 0f); resizeScale = 1f } }
    }
    // Eye toggle: the OpenSCAD design shown see-through, standing in the middle of the active plate (other objects hidden).
    val ghost = state.scad?.preview?.takeIf { state.scadOnPlate && !state.scadHidden && state.screen == app.orcaandroid.ui.Screen.PREPARE }
    val plate = scene.plates.getOrNull(state.activePlate)
    LaunchedEffect(v, ghost, plate?.originX, plate?.originY, setup?.bed) {
        val bed = setup?.bed
        val data = if (ghost == null || plate == null || bed == null || bed.size < 4) null else withContext(Dispatchers.Default) {
            val xs = bed.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = bed.filterIndexed { i, _ -> i % 2 == 1 }
            ghostData(ghost, plate.originX + (xs.min() + xs.max()) / 2, plate.originY + (ys.min() + ys.max()) / 2)
        }
        v.onGl { setGhost(data) }
    }
    LaunchedEffect(v, scene.paintVersion, scene.paintFile) {
        if (scene.paintFile.isEmpty()) return@LaunchedEffect
        val start = android.os.SystemClock.elapsedRealtime()
        val paint = withContext(Dispatchers.IO) { FloatData.load(scene.paintFile, 8) }
        val read = android.os.SystemClock.elapsedRealtime() - start
        // Slow reads show next to the engine's "paint:" timings in logcat (paint lag, user r316).
        if (read > 20) android.util.Log.w("OrcaRender", "paint mesh read $read ms")
        v.onGl { setPaint(paint) }
    }
    LaunchedEffect(v, result?.previewDir, result?.gcodeFile) {
        val data = result?.let { withContext(Dispatchers.IO) { PreviewData.load(it.previewDir) } }
        preview = data
        v.onGl { setPreview(data) }
    }
    // Follow the active plate when it changes (a new plate, a deleted one), not only on a chip tap.
    var framedPlate by remember { mutableStateOf(-1) }
    LaunchedEffect(v, state.activePlate) {
        val plate = state.activePlate
        // A view with a restored camera also brings the active plate on screen (it may have changed meanwhile).
        val move = framedPlate != plate && (framedPlate >= 0 || restored)
        framedPlate = plate
        // A plate already on screen becomes active without moving the camera (user, 2026-10-13); double tap frames it.
        v.onGl { activePlate = plate }
    }
    LaunchedEffect(v, state.filaments) {
        val cols = state.filaments.map { hexToRgb(it.color ?: "#FF7F27") }
        v.onGl { filamentColors = cols }
    }
    LaunchedEffect(v, state.selection, state.multiSelection, scene) {
        val items = state.selectedItems
        val ids = FloatArray(items.size) { (items[it].obj * INSTANCE_ID_STRIDE + items[it].instance).toFloat() }
        v.onGl { selectedIds = ids }
        v.towerBoxes = scene.plates.mapNotNull { p -> p.tower?.let { t ->
            val lo = Vec3(p.originX + t[0], p.originY + t[1], 0f)
            TowerBox(p.index, lo, Vec3(lo.x + t[2], lo.y + t[3], t[4]))
        } }
        v.selectedBounds = items.mapNotNull { s -> scene.objects.getOrNull(s.obj)?.instances?.getOrNull(s.instance)?.let { it.min to (it.min + it.size) } }
    }
    LaunchedEffect(v, state.tool, showPreview) {
        v.mode = when {
            showPreview -> InteractionMode.VIEW
            else -> when (state.tool) {
            is Tool.Paint -> InteractionMode.PAINT
            Tool.LayOnFace -> InteractionMode.LAY_ON_FACE
            Tool.Measure -> InteractionMode.MEASURE
            else -> InteractionMode.NAVIGATE
            }
        }
        val cut = (state.tool as? Tool.Cut)?.let { t ->
            state.selectedObject?.instances?.getOrNull(state.selection?.instance ?: 0)?.let { i ->
                floatArrayOf(i.min.x - 5, i.min.y - 5, i.min.x + i.size.x + 5, i.min.y + i.size.y + 5, t.z)
            }
        }
        v.onGl { cutPlane = cut }
    }
    // Section view: the plane sweeps the bounding box of the active plate's objects, else of all objects
    // (GLCanvas3D::_get_section_view_plane, volumes_bounding_box(true)).
    val bounds = remember(scene.objects, state.activePlate) {
        val all = scene.objects.flatMap { it.instances }
        val boxes = all.filter { it.plate == state.activePlate }.ifEmpty { all }
        if (boxes.isEmpty()) null else
            Vec3(boxes.minOf { it.min.x }, boxes.minOf { it.min.y }, boxes.minOf { it.min.z }) to
                Vec3(boxes.maxOf { it.min.x + it.size.x }, boxes.maxOf { it.min.y + it.size.y }, boxes.maxOf { it.min.z + it.size.z })
    }
    val section = v.section
    LaunchedEffect(v, section.ratio, section.normal, bounds) {
        val plane = bounds?.let { (min, max) -> section.plane(min, max) }
        section.current = plane
        v.onGl { this.section = plane }
    }
    LaunchedEffect(v, state.measure) {
        val pts = state.measure.map { floatArrayOf(it.x, it.y, it.z) }
        v.onGl { measurePoints = pts }
    }
    LaunchedEffect(v, showPreview, result, state.preview, preview) {
        val p = state.preview
        val data = preview
        val style = PreviewStyle(
            scheme = p.scheme,
            hiddenRoles = p.hiddenRoles,
            range = result?.ranges?.get(schemeKey(p.scheme)) ?: (0f to 1f),
            showTravels = p.showTravels,
            showRetracts = p.showRetracts,
            showSeams = p.showSeams,
        )
        val range = if (result != null && data != null)
            previewRange(result, p.layerLow, p.layerHigh, p.moveEnd, Triple(data.extrusions.count, data.travels.count, data.markers.count))
        else PreviewRange()
        v.onGl {
            this.showPreview = showPreview && result != null
            previewOrigin = floatArrayOf(result?.originX ?: 0f, result?.originY ?: 0f)
            previewStyle = style
            previewRange = range
        }
    }
}

/** ranges key of a colour scheme index (see PreviewStyle.scheme). */
fun schemeKey(scheme: Int) = when (scheme) {
    1 -> "speed"; 2 -> "height"; 3 -> "width"; 4 -> "fan"; 5 -> "temperature"; 6 -> "volumetric"; else -> ""
}

/** Frames plate [plate] of the given view. */
/**
 * The 3D view's camera when its view went away (the OpenSCAD design view replaced it, the screen turned), so the
 * next view looks the same instead of framing plate 1 again.
 */
private var savedCamera: FloatArray? = null

fun PlateView.framePlate(plate: Int, top: Boolean = false) = onGl { frame(plate, top) }

/** Brings plate [plate] into view: the camera stays when its centre is already on screen (user, 2026-10-13). */
fun PlateView.showPlate(plate: Int) = Unit // selecting a plate no longer moves the camera (user, 2026-10-15)

/** [mesh] (position + normal per vertex) as scene-mesh vertices of type 98, centred on (cx, cy) and standing on the bed. */
private fun ghostData(mesh: app.orcaandroid.render.PreviewMesh, cx: Float, cy: Float): FloatData {
    val src = mesh.data.duplicate().apply { rewind() }
    val vertices = src.limit() / 6
    val out = FloatData.directFloats(vertices * 8)
    val dx = cx - (mesh.min.x + mesh.max.x) / 2
    val dy = cy - (mesh.min.y + mesh.max.y) / 2
    val dz = -mesh.min.z
    repeat(vertices) {
        out.put(src.get() + dx); out.put(src.get() + dy); out.put(src.get() + dz)
        out.put(src.get()); out.put(src.get()); out.put(src.get())
        out.put(-1f); out.put(98f)
    }
    out.rewind()
    return FloatData(out, 8)
}
