package app.orcaandroid.ui.components

import app.orcaandroid.core.PresetType
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orcaandroid.core.SliceResult
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.CutPlane
import app.orcaandroid.render.DragSnap
import app.orcaandroid.render.FilamentMaterial
import app.orcaandroid.render.FloatData
import app.orcaandroid.render.PushGrab
import app.orcaandroid.render.INSTANCE_ID_STRIDE
import app.orcaandroid.render.InteractionMode
import app.orcaandroid.render.PlateBadge
import app.orcaandroid.render.PlateListener
import androidx.compose.ui.platform.LocalContext
import app.orcaandroid.render.PlateLook
import app.orcaandroid.render.PlateView
import app.orcaandroid.render.PreviewData
import app.orcaandroid.render.PreviewRange
import app.orcaandroid.render.PreviewStyle
import app.orcaandroid.render.RotateGizmo
import app.orcaandroid.render.TowerBox
import app.orcaandroid.render.ViewColors
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.Screen
import app.orcaandroid.ui.buildObject
import app.orcaandroid.ui.measureLockColor
import app.orcaandroid.ui.Selection
import app.orcaandroid.ui.Tool
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.prepare.PlateMenu
import app.orcaandroid.ui.prepare.inPaintShop
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
/** Measure loupe: a finger's dot sits this far above it; a placed point within the grab radius (a mouse's or pen's: the small one) is picked up. */
private val LOUPE_LIFT_DP get() = app.orcaandroid.core.Tuning["loupe.lift"]

/** Rays on a ring round (x, y) as wide as a held snap reaches: aimed just off the model, they find the face whose edge to catch. */
private fun snapRing(v: PlateView, x: Float, y: Float) = List(8) { i ->
    val a = i * Math.PI / 4
    val r = app.orcaandroid.ui.SNAP_PX * app.orcaandroid.ui.SNAP_RELEASE
    v.camera.ray(x + r * kotlin.math.cos(a).toFloat(), y + r * kotlin.math.sin(a).toFloat())
}

/** Where the ray through (x, y) meets the bed of a plate, or null (off every plate): the measure tool's bed pick. */
private fun bedAt(v: PlateView, x: Float, y: Float) = if (v.renderer.plateAt(x, y) != null) v.camera.rayOnPlane(x, y) else null

/** The measure loupe's colour for what the snap caught (SnapFeature.kind): snaps green, a hole's rim amber, a face blue. */
private fun snapColor(kind: String?) = when (kind) {
    "vertex", "edge", "curve" -> Color(0xFF4CAF50)
    "ring" -> Color(0xFFFFB300)
    "face" -> Color(0xFF4FC3F7)
    else -> Color.White
}
private const val LOUPE_GRAB_DP = 40f
/** The magnified loupe: its side, and its gap to the aim ring (dp). */
private val LOUPE_INSET_DP get() = app.orcaandroid.core.Tuning["loupe.size"]
private val LOUPE_INSET_GAP_DP get() = app.orcaandroid.core.Tuning["loupe.gap"]

/**
 * Where the magnified loupe sits for an aim spot at ([x], [y]) in a [w] x [h] view: left, top, size (px). Above the aim ring
 * (a finger is below it); near the top edge beside it instead, on the side with room.
 */
private fun loupeInset(x: Float, y: Float, w: Float, h: Float, density: Float): FloatArray {
    val size = minOf(LOUPE_INSET_DP * density, minOf(w, h) * 0.45f)
    val gap = LOUPE_INSET_GAP_DP * density
    var left = x - size / 2
    var top = y - gap - size
    if (top < 0f) {
        left = if (x + gap + size <= w) x + gap else x - gap - size
        top = y - size / 2
    }
    return floatArrayOf(left.coerceIn(0f, maxOf(0f, w - size)), top.coerceIn(0f, maxOf(0f, h - size)), size)
}
private const val LOUPE_GRAB_EXACT_DP = 12f

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
                        // Measure: a tap on a placed point selects it (a drag from it then moves it); with one selected, a tap
                        // elsewhere sets it (deselects) instead of placing a new point (user 2026-10-27).
                        if (vm.state.value.tool == app.orcaandroid.ui.Tool.Measure) {
                            val near = placedNear(x, y, LOUPE_GRAB_DP * v.resources.displayMetrics.density, LOUPE_LIFT_DP * v.resources.displayMetrics.density)
                            if (near != null) { vm.scene.measureSelect(near.first); return }
                            if (vm.state.value.measureActive != null) { vm.scene.measureSelect(null); return }
                        }
                        val (o, d) = v.camera.ray(x, y)
                        // The Build workspace: a tap picks a shape of the design.
                        if (vm.state.value.buildObject != null) vm.shapes.tapAt(o, d, additive || part || vm.state.value.selectMode)
                        else vm.scene.onViewTap(o, d, additive, part, v.section.current, emptyPlate, v.camera::pxSize,
                            vm.state.value.tool.let { t -> if (t == app.orcaandroid.ui.Tool.Measure || t is Tool.Cut) snapRing(v, x, y) else emptyList() },
                            if (vm.state.value.tool == app.orcaandroid.ui.Tool.Measure) bedAt(v, x, y) else null)
                    }
                    override fun onCopyAt(x: Float, y: Float, result: (Pair<Int, Int>?, Vec3, Vec3) -> Unit) {
                        val (o, d) = v.camera.ray(x, y)
                        vm.scene.copyAt(o, d, v.section.current) { copy, lo, hi -> v.post { result(copy, lo, hi) } }
                    }
                    override fun onDragCheck(x: Float, y: Float, result: (Boolean) -> Unit) {
                        val (o, d) = v.camera.ray(x, y)
                        if (vm.state.value.buildObject != null) vm.shapes.grabAt(o, d) { hit -> v.post { result(hit) } }
                        else vm.scene.checkDragStart(o, d, v.section.current) { hit -> v.post { result(hit) } }
                    }
                    override fun onPushCheck(x: Float, y: Float, result: (PushGrab?) -> Unit) {
                        val (o, d) = v.camera.ray(x, y)
                        vm.shapes.pushAt(o, d) { g -> v.post { result(g) } }
                    }
                    override fun onPush(grab: PushGrab, mm: Float?, x: Float, y: Float, done: Boolean) {
                        if (!done && mm != null) {
                            resizeLabel = Triple("%+.1f mm → %.1f mm".format(mm, grab.length + mm), x, y)
                            return
                        }
                        resizeLabel = null
                        if (mm == null || kotlin.math.abs(mm) < 0.05f) { v.onGl { pushMatrix = null }; return }
                        // The live stretch stays until the re-rendered piece is drawn (below), or the timeout.
                        dragPending[0] = true
                        v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { pushMatrix = null } } }, BUILD_RENDER_WAIT)
                        vm.shapes.push(mm.toDouble())
                    }
                    override fun onTowerCheck(x: Float, y: Float, entry: Vec3, result: (Boolean) -> Unit) {
                        val (o, d) = v.camera.ray(x, y)
                        vm.scene.checkTowerStart(o, d, entry, v.section.current) { clear -> v.post { result(clear) } }
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
                    override fun onResize(scale: Vec3?, pivot: Vec3, x: Float, y: Float, done: Boolean) {
                        if (vm.state.value.buildObject != null) {
                            // The Build workspace: the selected shapes are resized; the re-rendered pieces end the live scale.
                            val box = v.gizmoBox()
                            if (!done && box != null && scale != null) {
                                val s = box.second - box.first
                                resizeLabel = Triple("%.1f x %.1f x %.1f mm".format(s.x * scale.x, s.y * scale.y, s.z * scale.z), x, y)
                                return
                            }
                            resizeLabel = null
                            if (!done || scale == null) { v.onGl { resizeScale = floatArrayOf(1f, 1f, 1f) }; return }
                            dragPending[0] = true
                            v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { resizeScale = floatArrayOf(1f, 1f, 1f) } } }, BUILD_RENDER_WAIT)
                            vm.shapes.resizeOnPlate(scale, pivot)
                            return
                        }
                        val sel = vm.state.value.selectedItems.singleOrNull()
                        val inst = sel?.let { vm.state.value.scene.objects.getOrNull(it.obj)?.instances?.getOrNull(it.instance) }
                        if (!done && inst != null && scale != null) {
                            resizeLabel = Triple("%.1f x %.1f x %.1f mm".format(inst.size.x * scale.x, inst.size.y * scale.y, inst.size.z * scale.z), x, y)
                            return
                        }
                        resizeLabel = null
                        val noScale = floatArrayOf(1f, 1f, 1f)
                        if (!done || scale == null || inst == null) { v.onGl { resizeScale = noScale }; return }
                        // The live scale stays until the scaled mesh is uploaded (cleared there), or after a timeout.
                        dragPending[0] = true
                        v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { resizeScale = noScale } } }, 1500)
                        // World scale about the pivot: it stays put, the offset follows it (x, y; the engine drops onto the bed).
                        // On the copy's own axes, which lie along the world's in some order ([ownAxes]; a uniform scale needs none).
                        val f = floatArrayOf(scale.x, scale.y, scale.z)
                        val own = ownAxes(inst.rotation)?.let { m -> FloatArray(3).also { o -> for (i in 0..2) o[m[i]] = f[i] } }
                            ?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3(scale.x, scale.x, scale.x)
                        val off = Vec3(pivot.x + (inst.offset.x - pivot.x) * scale.x, pivot.y + (inst.offset.y - pivot.y) * scale.y, inst.offset.z)
                        vm.scene.setTransform(offset = off, scale = Vec3(inst.scale.x * own.x, inst.scale.y * own.y, inst.scale.z * own.z))
                    }
                    override fun onRotate(axis: Int, degrees: Float?, pivot: Vec3, x: Float, y: Float, done: Boolean) {
                        val sel = vm.state.value.selectedItems.singleOrNull()
                        val inst = sel?.let { vm.state.value.scene.objects.getOrNull(it.obj)?.instances?.getOrNull(it.instance) }
                        if (!done && degrees != null) {
                            resizeLabel = Triple("%s %.1f°".format("XYZ"[axis], degrees), x, y)
                            return
                        }
                        resizeLabel = null
                        if (vm.state.value.buildObject != null && degrees != null && kotlin.math.abs(degrees) >= 0.05f) {
                            // The Build workspace: the selected shapes turn (new matrices only, no OpenSCAD run).
                            dragPending[0] = true
                            v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { rotateMatrix = null } } }, BUILD_RENDER_WAIT)
                            vm.shapes.turnOnPlate(axis, degrees, pivot)
                            return
                        }
                        if (degrees == null || inst == null || kotlin.math.abs(degrees) < 0.05f) { v.onGl { rotateMatrix = null }; return }
                        // The live turn stays until the turned mesh is uploaded (cleared there), or after a timeout.
                        dragPending[0] = true
                        v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { rotateMatrix = null } } }, 1500)
                        // A world turn about the box centre: the rotation is composed in front of the copy's own
                        // (Rz * Ry * Rx) and the offset turns round the centre; the engine drops the copy onto the bed.
                        val m = RotateGizmo.matrix(axis, degrees)
                        val rotation = RotateGizmo.toEuler(RotateGizmo.mul(m, RotateGizmo.fromEuler(inst.rotation)))
                        vm.scene.setTransform(offset = pivot + RotateGizmo.apply(m, inst.offset - pivot), rotation = rotation)
                    }
                    override fun onCutMove(d: Float) {
                        val t = vm.state.value.tool as? Tool.Cut ?: return
                        val i = vm.state.value.selectedObject?.instances?.getOrNull(vm.state.value.selection?.instance ?: 0) ?: return
                        if (!CutPlane.upright(t.rot)) { vm.scene.setTool(t.copy(z = (t.z + d).coerceIn(i.min.z, i.min.z + i.size.z))); return }
                        // An upright plane slides sideways, within the copy's box.
                        val a = CutPlane.slideAxis(t.rot)
                        val reach = (kotlin.math.abs(a.x) * i.size.x + kotlin.math.abs(a.y) * i.size.y) / 2
                        val s = (a.x * t.dx + a.y * t.dy + d).coerceIn(-reach, reach)
                        vm.scene.setTool(t.copy(dx = a.x * s, dy = a.y * s))
                    }
                    override fun onCutTilt(axis: Int, degrees: Float?, base: Vec3, x: Float, y: Float, done: Boolean) {
                        val t = vm.state.value.tool as? Tool.Cut ?: return
                        if (degrees == null) { resizeLabel = null; vm.scene.setTool(t.copy(rot = base)); return }
                        val rot = RotateGizmo.toEuler(RotateGizmo.mul(RotateGizmo.matrix(axis, degrees), RotateGizmo.fromEuler(base)))
                        vm.scene.setTool(t.copy(rot = rot))
                        resizeLabel = if (done) null else Triple("%.1f°".format(CutPlane.tilt(rot)), x, y)
                    }
                    override fun onBrushScroll(steps: Int) = vm.scene.scaleBrush(if (steps > 0) 1.15f else 1 / 1.15f)
                    override fun onMoved(dx: Float, dy: Float) {
                        // The drag offset stays until the moved mesh is uploaded (below); the timer covers a move
                        // that changes nothing or fails.
                        dragPending[0] = true
                        if (vm.state.value.buildObject != null) {
                            // The shapes move in the design; the pieces' new places arrive with the next scene mesh.
                            vm.shapes.moveOnPlate(dx, dy)
                            v.postDelayed({ if (dragPending[0]) { dragPending[0] = false; v.onGl { dragOffset = floatArrayOf(0f, 0f, 0f) } } }, 3000)
                            return
                        }
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
                    private var strokeStart = 0f to 0f
                    override fun onPaint(x: Float, y: Float, newStroke: Boolean) {
                        if (newStroke) strokeStart = x to y
                        if (vm.state.value.tool is Tool.Sculpt) {
                            val (o, d) = v.camera.ray(x, y)
                            vm.scene.sculptAt(o, d, newStroke)
                            return
                        }
                        val lock = (vm.state.value.tool as? Tool.Paint)?.lock
                        val (o, d) = v.camera.ray(if (lock == "v") strokeStart.first else x, if (lock == "h") strokeStart.second else y)
                        vm.scene.paintAt(o, d, v.camera.eye(), newStroke, v.section.current)
                    }
                    override fun onPaintEnd() { vm.scene.sculptEnd(); vm.scene.paintEnd() }
                    override fun onPaintHover(x: Float, y: Float, inside: Boolean) {
                        val ray = if (inside) v.camera.ray(x, y) else null
                        if (vm.state.value.tool is Tool.Paint) vm.scene.paintHover(ray, v.section.current) else vm.scene.sculptHover(ray)
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
                        vm.pieTouch.reset()
                        val (o, d) = v.camera.ray(x, y)
                        if (vm.state.value.tool is Tool.Cut) vm.scene.cutAlignAt(o, d, x, y, v.section.current)
                        else vm.scene.openContextMenu(o, d, x, y, v.section.current)
                    }

                    override fun onLongPressTouch(x: Float, y: Float, held: Boolean) = vm.pieTouch.update(x, y, held)

                    /** This gesture's aim, as an offset from the pointer: a finger's dot sits above it; a picked-up point stays put and moves 1:1. */
                    private var aimOffset = floatArrayOf(0f, 0f)

                    /** The placed point within [radius] px of (x, y), or of the aim [lift] px above it: its index and screen spot. */
                    private fun placedNear(x: Float, y: Float, radius: Float, lift: Float) = vm.state.value.measure.withIndex().mapNotNull { (i, m) ->
                        v.camera.project(m.point)?.let { s -> Triple(i, s, minOf(kotlin.math.hypot(s.first - x, s.second - y), kotlin.math.hypot(s.first - x, s.second - y + lift))) }
                    }.filter { it.third <= radius }.minByOrNull { it.third }

                    override fun onMeasureGrab(x: Float, y: Float, exact: Boolean): Boolean {
                        val density = v.resources.displayMetrics.density
                        if (exact) return placedNear(x, y, LOUPE_GRAB_EXACT_DP * density, 0f) != null
                        val active = vm.state.value.measureActive ?: return false
                        return placedNear(x, y, LOUPE_GRAB_DP * density, LOUPE_LIFT_DP * density)?.first == active
                    }

                    /** The steered-point camera (Steer camera toggle, a finger's loupe only): each frame it moved, the dot is picked again. */
                    private val steer = app.orcaandroid.render.MeasureSteer(v, v.camera, {
                        vm.state.value.loupe?.let { l ->
                            l.point?.let { p -> app.orcaandroid.render.MeasureSteer.focusOf(p, l.feature?.kind, l.feature?.lines ?: FloatArray(0),
                                l.feature?.diameter, l.preview?.normal, v.camera.towardsEye()) }
                        }
                    }) { ax, ay -> loupeAt(ax, ay, 1, null) }

                    private fun loupeAt(ax: Float, ay: Float, phase: Int, grab: Int?) {
                        val (o, d) = v.camera.ray(ax, ay)
                        vm.scene.measureLoupe(o, d, ax, ay, phase, v.section.current, v.camera::pxSize, if (phase == 3) emptyList() else snapRing(v, ax, ay),
                            grab, bedAt(v, ax, ay))
                    }

                    override fun onMeasureFreeze(frozen: Boolean) = steer.freeze(frozen)

                    private var press = floatArrayOf(0f, 0f)
                    private var easeLift = 0f

                    override fun onMeasureLoupe(x: Float, y: Float, phase: Int, exact: Boolean) {
                        val density = v.resources.displayMetrics.density
                        val lift = if (exact) 0f else LOUPE_LIFT_DP * density
                        val near = if (phase != 0) null else placedNear(x, y, (if (exact) LOUPE_GRAB_EXACT_DP else LOUPE_GRAB_DP) * density, lift)
                        // A mouse or pen aims right under itself; a finger's dot sits above it so the finger doesn't hide it.
                        // A picked-up point keeps its spot and follows the pointer 1:1 (it used to jump to the aim).
                        if (phase == 0 || phase == 4)
                            {
                                press = floatArrayOf(x, y)
                                aimOffset = near?.let { floatArrayOf(it.second.first - x, it.second.second - y) } ?: floatArrayOf(0f, 0f)
                                easeLift = if (near == null) lift else 0f
                            }
                        else if (easeLift > 0f && phase == 1) {
                            // A long press starts the dot under the finger (where a tap would set it); moving eases it up to the lift.
                            aimOffset = floatArrayOf(0f, -easeLift * minOf(1f, kotlin.math.hypot(x - press[0], y - press[1]) / easeLift))
                        }
                        if (phase == 0 && !exact && vm.state.value.measureSteer) steer.begin(x, y, x + aimOffset[0], y + aimOffset[1])
                        if (steer.active) {
                            // Steered: the finger's moves steer the dot, the frames pick it; the lift places it before the camera goes back.
                            when (phase) {
                                0 -> loupeAt(steer.aimX, steer.aimY, 0, near?.first)
                                1 -> steer.move(x, y)
                                else -> { loupeAt(steer.aimX, steer.aimY, phase, null); steer.end() }
                            }
                            return
                        }
                        loupeAt(x + aimOffset[0], y + aimOffset[1], phase, near?.first)
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
    // Redraws the selected point's ring every frame while there is one (the camera moves under it).
    val activeFrame = androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(state.measureActive != null && state.tool == Tool.Measure) {
        if (state.measureActive != null && state.tool == Tool.Measure) while (true) androidx.compose.runtime.withFrameNanos { activeFrame.longValue = it }
    }
    state.measureActive?.let { state.measure.getOrNull(it) }?.takeIf { state.loupe == null && state.tool == Tool.Measure }?.let { m ->
        // The selected point: a ring round it (drag from it to move it, tap elsewhere to set it).
        Canvas(Modifier.fillMaxSize()) {
            activeFrame.longValue
            view?.camera?.project(m.point)?.let { (px, py) ->
                val c = androidx.compose.ui.geometry.Offset(px, py)
                drawCircle(Color.Black.copy(alpha = 0.5f), 14.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                drawCircle(Color.White, 14.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
            }
        }
    }
    state.loupe?.let { l ->
        // What the snap caught, named under the ring; the view outlines it in the same colour (snapLines).
        val kind = l.feature?.kind
        val label = when (kind) {
            "vertex" -> androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.measure_snap_corner)
            "edge" -> androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.measure_snap_edge)
            "ring" -> androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.measure_snap_ring, "%.2f".format(l.feature?.diameter ?: 0f))
            "curve" -> androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.measure_snap_curve)
            "face" -> androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.measure_snap_face)
            "bed" -> androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.measure_snap_bed)
            else -> null
        }
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge.copy(color = Color.White)
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        Canvas(Modifier.fillMaxSize()) {
            // The magnified loupe (the renderer draws the scene into it): a frame, a small crosshair on the aim and the snapped point.
            view?.let { vw ->
                val (il, itop, isz) = loupeInset(l.x, l.y, vw.width.toFloat(), vw.height.toFloat(), density).let { Triple(it[0], it[1], it[2]) }
                val tl = androidx.compose.ui.geometry.Offset(il, itop)
                val box = androidx.compose.ui.geometry.Size(isz, isz)
                drawRect(Color.Black.copy(alpha = 0.5f), tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                drawRect(snapColor(kind), tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
                val m = tl + androidx.compose.ui.geometry.Offset(isz / 2, isz / 2)
                val arm = 8.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.7f), m.copy(x = m.x - arm), m.copy(x = m.x + arm), 1.5f)
                drawLine(Color.White.copy(alpha = 0.7f), m.copy(y = m.y - arm), m.copy(y = m.y + arm), 1.5f)
                l.point?.let { p -> vw.camera.project(p) }?.let { (px, py) ->
                    val d = m + androidx.compose.ui.geometry.Offset((px - l.x) * app.orcaandroid.render.LOUPE_ZOOM, (py - l.y) * app.orcaandroid.render.LOUPE_ZOOM)
                    if (d.x in il..il + isz && d.y in itop..itop + isz) {
                        drawCircle(Color.Black.copy(alpha = 0.5f), 5.dp.toPx(), d)
                        drawCircle(snapColor(kind), 3.5f.dp.toPx(), d)
                    }
                }
            }
            // Crosshair ring on the aim spot (above a finger, under a mouse or pen), in the snap kind's colour; white when nothing was hit.
            val c = androidx.compose.ui.geometry.Offset(l.x, l.y)
            val color = snapColor(kind)
            val r = 22.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
            drawCircle(color, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
            drawLine(color, c.copy(x = c.x - r * 1.5f), c.copy(x = c.x - r * 0.4f), 2.5f)
            drawLine(color, c.copy(x = c.x + r * 0.4f), c.copy(x = c.x + r * 1.5f), 2.5f)
            drawLine(color, c.copy(y = c.y - r * 1.5f), c.copy(y = c.y - r * 0.4f), 2.5f)
            drawLine(color, c.copy(y = c.y + r * 0.4f), c.copy(y = c.y + r * 1.5f), 2.5f)
            // The snapped point itself, tied to the aim spot when the snap pulled it away.
            l.point?.let { p -> view?.camera?.project(p) }?.let { (px, py) ->
                val d = androidx.compose.ui.geometry.Offset(px, py)
                if ((d - c).getDistance() > 3f) drawLine(color.copy(alpha = 0.8f), c, d, 2f)
                drawCircle(Color.Black.copy(alpha = 0.5f), 6.dp.toPx(), d)
                drawCircle(color, 4.5f.dp.toPx(), d)
            }
            if (label != null) {
                val t = measurer.measure(label, labelStyle)
                val tl = androidx.compose.ui.geometry.Offset(c.x - t.size.width / 2f, c.y + r * 1.7f)
                val pad = 4.dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = 0.6f), tl - androidx.compose.ui.geometry.Offset(pad, pad / 2),
                    androidx.compose.ui.geometry.Size(t.size.width + 2 * pad, t.size.height + pad), androidx.compose.ui.geometry.CornerRadius(pad))
                drawText(t, topLeft = tl)
                drawRoundRect(color, tl - androidx.compose.ui.geometry.Offset(pad, pad / 2),
                    androidx.compose.ui.geometry.Size(t.size.width + 2 * pad, t.size.height + pad), androidx.compose.ui.geometry.CornerRadius(pad),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            }
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
    } else if (state.rotating && !showPreview) {
        androidx.compose.material3.Surface(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 72.dp), shape = MaterialTheme.shapes.small, tonalElevation = 4.dp) {
            androidx.compose.foundation.layout.Row(Modifier.padding(start = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(app.orcaandroid.R.string.rotate_hint), style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.TextButton(onClick = vm.scene::toggleRotate) {
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
    LaunchedEffect(v, state.realistic) { v.onGl { realistic = state.realistic } }
    val plateTypes = scene.plates.indices.map { state.bedTypeOf(it) }
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(v, state.lab.plateHd, plateTypes) {
        v.onGl {
            plateSurface = state.lab.plateHd; plateLooks = plateTypes.map { PlateLook.of(it) }
            decalLoader = { name -> runCatching { appContext.assets.open("plate_decals/$name.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull() }
        }
    }
    // Layer lines: the print preset's first layer and layer heights (one value each; 0 = off).
    val layers = if (!state.layerLines) floatArrayOf(0f, 0f) else {
        fun mm(key: String) = state.value(PresetType.PRINT, key)?.split(',', ';')?.firstOrNull()?.trim()?.toFloatOrNull()?.takeIf { it > 0f } ?: 0.2f
        floatArrayOf(mm("initial_layer_print_height"), mm("layer_height"))
    }
    LaunchedEffect(v, layers[0], layers[1]) { v.onGl { layerLines = layers } }
    LaunchedEffect(v, state.supersample) {
        v.onGl { supersample = state.supersample }
    }
    val solo = state.inPaintShop
    LaunchedEffect(v, solo) { v.onGl { this.solo = solo } }
    val hidden = (state.tool as? Tool.Paint)?.takeIf { solo && it.kind == "color" }?.hidden ?: 0
    LaunchedEffect(v, hidden) { v.onGl { hiddenFilaments = hidden } }
    // The paint shop's view is smaller than the plate view: it frames the painted copy on entry (same view angles) and
    // Done puts the plate camera back. Runs after the old view's onDispose, so savedCamera is the plate camera here.
    // The Build workspace does the same with its object.
    val focused = solo || state.buildObject != null
    LaunchedEffect(v, focused) {
        val plate = shopCamera
        if (focused && plate == null) {
            val box = if (solo) state.selectedObject?.instances?.getOrNull(state.selection?.instance ?: 0)?.let { it.min to (it.min + it.size) }
                else state.buildObject?.let { state.scene.objects.getOrNull(it) }?.instances?.takeIf { it.isNotEmpty() }?.map { it.shown }?.let { insts ->
                    Vec3(insts.minOf { it.min.x }, insts.minOf { it.min.y }, insts.minOf { it.min.z }) to
                        Vec3(insts.maxOf { it.min.x + it.size.x }, insts.maxOf { it.min.y + it.size.y }, insts.maxOf { it.min.z + it.size.z })
                }
            box ?: return@LaunchedEffect
            shopCamera = savedCamera ?: v.camera.state()
            shopCamera?.let(v.camera::restore)
            v.onGl { fitPending = box }
        } else if (!focused && plate != null) {
            v.camera.restore(plate)
            shopCamera = null
        }
    }
    LaunchedEffect(v, state.selectionStyle) {
        v.onGl { selectionStyle = state.selectionStyle }
    }
    LaunchedEffect(v, state.lab.shadowStrength, state.lab.shadowSoft, state.lab.shadowBias) {
        v.onGl { shadowStrength = state.lab.shadowStrength; shadowSoftMm = state.lab.shadowSoft; shadowBiasMm = state.lab.shadowBias }
    }
    LaunchedEffect(v, state.lab.lightAzimuth, state.lab.lightElevation, state.lab.lightStrength, state.lab.cameraLight) {
        v.onGl { keyAzimuth = state.lab.lightAzimuth; keyElevation = state.lab.lightElevation; keyStrength = state.lab.lightStrength; cameraLight = state.lab.cameraLight }
    }
    LaunchedEffect(v, state.lab.pinchSpeed) { v.pinchSpeed = state.lab.pinchSpeed }
    LaunchedEffect(v, state.lab.wheelSpeed) { v.wheelSpeed = state.lab.wheelSpeed }
    LaunchedEffect(v, state.perspective) { v.camera.perspective = state.perspective }
    LaunchedEffect(v, state.pen) { v.pen = state.pen }
    // The Build workspace's selection is shapes: their handles and rings always show (one copy of the object only; groups
    // only turn), on the selected volumes' mesh box.
    val building = state.buildObject != null
    val buildGizmo = if (!building) null else remember(state.scene, state.scad?.selected, state.scad?.shapes) { vm.shapes.buildGizmoTarget() }
    // Push / pull: a drag on a face moves it; the gizmos go meanwhile.
    val pushing = building && state.buildPush
    LaunchedEffect(v, building) { v.gizmoOnMesh = building }
    LaunchedEffect(v, pushing) { v.pushMode = pushing }
    LaunchedEffect(v, state.resizing, showPreview, building, buildGizmo, pushing) {
        v.resizeMode = if (building) !pushing && buildGizmo?.second?.none { it.isGroup } == true else state.resizing && !showPreview
    }
    LaunchedEffect(v, state.rotating, showPreview, building, buildGizmo, pushing) {
        v.rotateMode = if (building) !pushing && buildGizmo != null else state.rotating && !showPreview
    }
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
    // Live sculpt: the stroke's changed triangles go straight into the scene buffer.
    LaunchedEffect(v) { vm.scene.meshPatches.collect { p -> v.onGl { applyPatch(p) } } }
    // The brush ring, at the brush's size (Ctrl+wheel resizes it in place): sculpt brushes and the paint brushes with a size.
    val sculptCursor by vm.scene.sculptCursor.collectAsState()
    val sculptRadius = (state.tool as? Tool.Sculpt)?.radius
        ?: (state.tool as? Tool.Paint)?.takeIf { it.shape == "sphere" || it.shape == "circle" }?.radius
    LaunchedEffect(v, sculptCursor, sculptRadius) {
        val c = sculptCursor?.takeIf { sculptRadius != null }
        val ring = c?.let { (p, n) -> floatArrayOf(p.x, p.y, p.z, n.x, n.y, n.z, sculptRadius!!) }
        v.onGl { brushCursor = ring }
    }
    LaunchedEffect(v, scene.meshVersion, scene.meshFile) {
        if (scene.meshFile.isEmpty()) return@LaunchedEffect
        val mesh = withContext(Dispatchers.IO) { FloatData.load(scene.meshFile, 8) }
        val version = scene.meshVersion
        v.onGl { setMesh(mesh, version) }
        // A finished drag: the moved mesh is queued first, so the old offset goes away without a jump back.
        if (dragPending[0]) { dragPending[0] = false; v.onGl { dragOffset = floatArrayOf(0f, 0f, 0f); resizeScale = floatArrayOf(1f, 1f, 1f); pushMatrix = null; rotateMatrix = null } }
    }
    // Eye toggle: the OpenSCAD design shown see-through, standing in the middle of the active plate (other objects hidden).
    // A shape design placed as pieces shows its object instead (the Build workspace).
    val ghost = state.scad?.preview?.takeIf { state.scadOnPlate && !state.scadHidden && state.screen == app.orcaandroid.ui.Screen.PREPARE && !building }
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
        val mats = state.filaments.map { FilamentMaterial.entries.firstOrNull { m -> m.name == it.look } ?: FilamentMaterial.of(it.preset, it.overrides["filament_type"]) }
        v.onGl { filamentColors = cols; filamentMaterials = mats }
    }
    val build = state.buildObject?.let { scene.objects.getOrNull(it) }
    LaunchedEffect(v, build != null) { v.onGl { buildSolo = build != null } }
    // Build workspace: the selected shapes' volumes are the selection; a drag starts on the object's box (the pick decides).
    LaunchedEffect(v, build, state.scad?.selected, state.scad?.shapes) {
        if (build == null) { v.dragSnap = null; v.onGl { boxOverride = null }; return@LaunchedEffect }
        val ids = vm.scad.buildSelectedIds(build)
        // Shapes inside a group: the gizmos sit on their own box, not the group's mesh.
        val box = vm.shapes.buildGizmoBox()?.let { (lo, hi) -> floatArrayOf(lo.x, lo.y, lo.z, hi.x, hi.y, hi.z) }
        v.onGl { selectedIds = ids; boxOverride = box }
        // The design pose's box: Build draws and picks the copies there, not where they lie on the plate.
        v.selectedBounds = if (ids.isEmpty()) emptyList() else build.instances.map { it.shown.let { s -> s.min to (s.min + s.size) } }
        v.resizeAxes = vm.shapes.buildGizmoTarget()?.let { (inst, tops) -> state.scad?.shapes?.let { app.orcaandroid.scad.BuildTransform.axesFree(it, inst, tops) } } ?: true
        v.towerBoxes = emptyList()
        // A body drag sticks the selection's edges to the other shapes' edges.
        v.dragSnap = vm.shapes.buildSnapBoxes()
    }
    LaunchedEffect(v, state.selection, state.multiSelection, scene, build != null) {
        if (build != null) return@LaunchedEffect
        val items = state.selectedItems
        val ids = FloatArray(items.size) { (items[it].obj * INSTANCE_ID_STRIDE + items[it].instance).toFloat() }
        v.onGl { selectedIds = ids }
        v.towerBoxes = scene.plates.mapNotNull { p -> p.tower?.let { t ->
            val lo = Vec3(p.originX + t[0], p.originY + t[1], 0f)
            TowerBox(p.index, lo, Vec3(lo.x + t[2], lo.y + t[3], t[4]))
        } }
        val insts = items.mapNotNull { s -> scene.objects.getOrNull(s.obj)?.instances?.getOrNull(s.instance) }
        v.selectedBounds = insts.map { it.min to (it.min + it.size) }
        v.resizeAxes = insts.singleOrNull()?.let { ownAxes(it.rotation) != null } ?: true
        // Smart guides: a body drag sticks the selection's edges and middle to the plate's edges and middle and to the other objects' edges and middles.
        v.dragSnap = insts.firstOrNull()?.let { first ->
            val plate = scene.plates.getOrNull(first.plate.takeIf { it >= 0 } ?: state.activePlate)
            if (plate == null) null else {
                val lo = Vec3(insts.minOf { it.min.x }, insts.minOf { it.min.y }, insts.minOf { it.min.z })
                val hi = Vec3(insts.maxOf { it.min.x + it.size.x }, insts.maxOf { it.min.y + it.size.y }, insts.maxOf { it.min.z + it.size.z })
                val bed = Vec3(plate.originX, plate.originY, 0f) to Vec3(plate.originX + scene.plateWidth, plate.originY + scene.plateDepth, 0f)
                val sel = items.map { it.obj to it.instance }.toSet()
                val others = arrayListOf(bed)
                scene.objects.forEachIndexed { o, obj -> obj.instances.forEachIndexed { i, inst ->
                    if ((o to i) !in sel && inst.plate == first.plate) others.add(inst.min to (inst.min + inst.size))
                } }
                DragSnap(lo, hi, others, mid = true)
            }
        }
    }
    LaunchedEffect(v, state.tool, showPreview, state.overhangView, state.overhangDrag, state.value(PresetType.PRINT, "support_threshold_angle")) {
        v.mode = when {
            showPreview -> InteractionMode.VIEW
            else -> when (state.tool) {
            is Tool.Paint -> if (state.tool.shape == "gap") InteractionMode.VIEW else InteractionMode.PAINT
            is Tool.Sculpt -> InteractionMode.PAINT
            Tool.LayOnFace -> InteractionMode.LAY_ON_FACE
            Tool.Measure -> InteractionMode.MEASURE
            else -> InteractionMode.NAVIGATE
            }
        }
        // The cut tool's plane: a disc round the object's middle, at the cut height, turned by the tilt.
        val cutGeom = (state.tool as? Tool.Cut)?.takeIf { !showPreview }?.let { t ->
            state.selectedObject?.instances?.getOrNull(state.selection?.instance ?: 0)?.let { i ->
                PlateView.CutGeom(Vec3(i.min.x + i.size.x / 2 + t.dx, i.min.y + i.size.y / 2 + t.dy, t.z), i.size.length() / 2 * 1.1f, t.rot)
            }
        }
        v.cutGeom = cutGeom
        v.onGl {
            cutDisc = cutGeom?.let { CutPlane.disc(it.c, it.r, it.rot) }
            cutBand = cutGeom?.let { CutPlane.band(it.c, it.rot) }
            cutRings = cutGeom?.let { floatArrayOf(it.c.x, it.c.y, it.c.z, it.r) }
            cutXrayPanel = cutGeom != null && (state.tool as? Tool.Cut)?.placing == true
        }
        val cut = (state.tool as? Tool.Paint)?.takeIf { it.guardOn && !showPreview }?.let { t ->
            // The paint guard plane, drawn as the cut plane.
            state.selectedObject?.instances?.getOrNull(state.selection?.instance ?: 0)?.let { i ->
                floatArrayOf(i.min.x - 5, i.min.y - 5, i.min.x + i.size.x + 5, i.min.y + i.size.y + 5, t.guardZ)
            }
        }
        val highlight = (state.tool as? Tool.Paint)?.takeIf { !showPreview && it.kind == "support" }?.highlight ?: 0f
        // "Show overhangs": every part, at the support threshold angle (0 = auto: tree supports' 30).
        val viewAngle = if (!state.overhangView || showPreview) 0f
            else (state.overhangDrag ?: state.value(PresetType.PRINT, "support_threshold_angle")?.toFloatOrNull() ?: 30f).let { if (it < 0.5f) 30f else it }
        v.onGl { cutPlane = cut; overhangHighlight = if (highlight > 0f) highlight else viewAngle; overhangAll = highlight <= 0f && viewAngle > 0f }
    }
    // Check mesh: the problem spots stay up until another copy is selected.
    val marks by vm.scene.meshMarkers.collectAsState()
    LaunchedEffect(v, marks, state.selection, state.scene) {
        // The spots follow the copy's moves; a turn, scale or mirror makes them wrong, so they go.
        val base = vm.scene.checkBase
        val now = state.selection?.let { s -> state.scene.objects.getOrNull(s.obj)?.instances?.getOrNull(s.instance) }
        fun near(a: Vec3, b: Vec3) = (a - b).length() < 1e-3f
        val unchanged = base != null && now != null && near(now.rotation, base.rotation) && near(now.scale, base.scale) &&
            near(now.mirror, base.mirror) && near(now.size, base.size)
        if (marks != null && (marks?.first != state.selection || !unchanged)) vm.scene.clearMeshMarkers()
        val data = marks?.takeIf { it.first == state.selection && unchanged }?.second
        val shift = if (data != null && base != null && now != null) now.min - base.min else Vec3.ZERO
        v.onGl { meshMarkers = data; meshMarkerShift = floatArrayOf(shift.x, shift.y, shift.z) }
    }
    // Regions view: the selected copy's crease lines, again after the model or the angle changes (a paint stroke changes
    // the scene too, but the lines only depend on the geometry; recomputing them is cheap next to a stroke).
    val regions = (state.tool as? Tool.Paint)?.takeIf { it.shape == "regions" && !showPreview }
    val regionAngle = regions?.edgeAngle?.takeIf { it > 0f } ?: 30f
    // The View menu's edge outline: the same lines for every copy on the active plate (the Regions view wins on the selected copy).
    val outline = state.edgeOutline && regions == null && !showPreview
    LaunchedEffect(v, regions != null, outline, regionAngle, state.selection, scene.objects, state.activePlate) {
        val lines = if (regions != null) runCatching { vm.scene.creaseLines(regionAngle) }.getOrNull()
            else if (outline) runCatching { vm.scene.plateCreaseLines(regionAngle) }.getOrNull() else null
        v.onGl { creaseLines = lines }
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
    // Compose's density (the UI scale), as the overlay's frame uses.
    val loupeDensity = androidx.compose.ui.platform.LocalDensity.current.density
    LaunchedEffect(v, state.loupe?.x, state.loupe?.y) {
        val l = state.loupe
        val r = l?.let { loupeInset(it.x, it.y, v.width.toFloat(), v.height.toFloat(), loupeDensity) }
        v.onGl { loupe = if (l == null || r == null) null else floatArrayOf(l.x, l.y, r[0], r[1], r[2]) }
    }
    val measuring = state.tool == Tool.Measure
    LaunchedEffect(v, state.loupe?.feature, measuring) {
        val f = state.loupe?.feature?.takeIf { measuring }
        val c = snapColor(f?.kind)
        v.onGl { snapLines = f?.lines; snapColor = floatArrayOf(c.red, c.green, c.blue) }
    }
    val measureLine = state.measureLine
    LaunchedEffect(v, state.measure, state.loupe?.point, measureLine, measuring) {
        // The line ends where a click would place B (a ring: its centre).
        val pts = if (!measuring) emptyList() else (state.measure.map { it.point } + listOfNotNull(state.loupe?.let { it.preview?.point ?: it.point })).takeLast(2)
            .map { floatArrayOf(it.x, it.y, it.z) }
        val lock = measureLine?.takeIf { measuring }?.third?.let { l -> val c = measureLockColor(l.axis); floatArrayOf(l.foot.x, l.foot.y, l.foot.z, c.red, c.green, c.blue) }
        v.onGl { measurePoints = pts; measureLock = lock }
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

/** The plate camera from before the paint shop or the Build workspace, restored when it closes. */
private var shopCamera: FloatArray? = null

/** Build workspace: how long a live resize / turn waits for the re-rendered pieces (a resize runs OpenSCAD) before it lets go. */
private const val BUILD_RENDER_WAIT = 8000L

/**
 * For a copy turned [rotation] (degrees, applied X then Y then Z as the engine's Geometry::rotation_transform) only in
 * 90 degree steps: for each world axis the copy's own axis along it. Null for other turns, where one world axis can't be
 * scaled alone.
 */
private fun ownAxes(rotation: Vec3): IntArray? {
    fun cs(deg: Float): Pair<Int, Int>? {
        val q = deg / 90f
        val k = Math.round(q)
        if (kotlin.math.abs(q - k) > 0.01f) return null
        return when (Math.floorMod(k, 4)) { 0 -> 1 to 0; 1 -> 0 to 1; 2 -> -1 to 0; else -> 0 to -1 }
    }
    val (cx, sx) = cs(rotation.x) ?: return null
    val (cy, sy) = cs(rotation.y) ?: return null
    val (cz, sz) = cs(rotation.z) ?: return null
    fun mul(a: Array<IntArray>, b: Array<IntArray>) = Array(3) { i -> IntArray(3) { j -> (0..2).sumOf { a[i][it] * b[it][j] } } }
    val rx = arrayOf(intArrayOf(1, 0, 0), intArrayOf(0, cx, -sx), intArrayOf(0, sx, cx))
    val ry = arrayOf(intArrayOf(cy, 0, sy), intArrayOf(0, 1, 0), intArrayOf(-sy, 0, cy))
    val rz = arrayOf(intArrayOf(cz, -sz, 0), intArrayOf(sz, cz, 0), intArrayOf(0, 0, 1))
    val r = mul(rz, mul(ry, rx))
    return IntArray(3) { i -> (0..2).first { r[i][it] != 0 } }
}

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
