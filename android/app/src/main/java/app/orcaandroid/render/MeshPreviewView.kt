package app.orcaandroid.render

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import app.orcaandroid.core.Vec3
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A triangle mesh for [MeshPreviewView]: interleaved position + face normal per vertex. Plain class
 * (identity equality), so UI state holding it stays cheap to compare.
 */
class PreviewMesh(val data: FloatBuffer, val triangles: Int, val min: Vec3, val max: Vec3) {
    val size: Vec3 get() = max - min

    companion object {
        /** Reads a binary STL (as OpenSCAD writes it); null when empty or not binary. */
        fun readStl(file: File): PreviewMesh? {
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
                if (map.capacity() < 84) return null
                val count = map.getInt(80)
                if (count <= 0 || 84L + count * 50L > map.capacity()) return null
                val out = ByteBuffer.allocateDirect(count * 3 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val v = FloatArray(9)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                for (t in 0 until count) {
                    val base = 84 + t * 50 + 12
                    for (i in 0 until 9) v[i] = map.getFloat(base + i * 4)
                    // Face normal from the vertices (the stored one may be zero).
                    val ax = v[3] - v[0]; val ay = v[4] - v[1]; val az = v[5] - v[2]
                    val bx = v[6] - v[0]; val by = v[7] - v[1]; val bz = v[8] - v[2]
                    var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
                    val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
                    nx /= len; ny /= len; nz /= len
                    for (k in 0 until 3) {
                        val x = v[k * 3]; val y = v[k * 3 + 1]; val z = v[k * 3 + 2]
                        out.put(x).put(y).put(z).put(nx).put(ny).put(nz)
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                    }
                }
                out.flip()
                return PreviewMesh(out, count, Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
            }
        }
    }
}

/**
 * A shape-builder box drawn over the preview: [selected] ones can be dragged, holes are always shown.
 * A lone selected box gets size handles when [scalable] (the box is the shape's own size) and a turn
 * handle about Z through [pivot] when that is set.
 */
data class PreviewBox(
    val id: Int, val min: Vec3, val max: Vec3, val selected: Boolean, val hole: Boolean,
    val scalable: Boolean = false, val pivot: Vec3? = null,
)

/** World-space handle dots (x, y, z, r, g, b), guide lines and the live drag result (lines). */
class PreviewOverlay(val points: FloatArray, val guide: FloatArray, val ghost: FloatArray) {
    companion object { val EMPTY = PreviewOverlay(FloatArray(0), FloatArray(0), FloatArray(0)) }
}

/** The 12 edges of a box given as 8 corners (bit 0 = x, bit 1 = y, bit 2 = z side), as line pairs. */
private fun boxLines(out: MutableList<Float>, c: List<Vec3>) {
    for (i in 0 until 8) for (bit in intArrayOf(1, 2, 4)) if (i and bit == 0) {
        val a = c[i]; val b = c[i or bit]
        out += listOf(a.x, a.y, a.z, b.x, b.y, b.z)
    }
}

/** Shape-builder input from the preview: picks and drags of [PreviewBox]es. */
interface PreviewPickListener {
    /** Tap on box [id] (null = empty space); [add] with Ctrl / Shift held. */
    fun onPick(id: Int?, add: Boolean)
    /** A drag of the selected boxes ended, moved by [dx], [dy] mm (snapped to 1 mm). */
    fun onMove(dx: Float, dy: Float)
    /** A size handle drag ended: the selected shape grows by [mm] along [axis] (0..2), its min side kept. */
    fun onResize(axis: Int, mm: Float)
    /** The turn handle drag ended: the selected shape turns by [degrees] about Z. */
    fun onRotate(degrees: Float)
}

/**
 * Small 3D view of one mesh (the OpenSCAD editor's preview): the model on a grid at z = 0
 * with the X (red) and Y (green) axes. One finger orbits, two fingers pan and pinch zoom, the mouse
 * wheel zooms, a double tap frames the model again. Draws only when something changed.
 */
class MeshPreviewView(context: Context) : GLSurfaceView(context) {

    val camera = Camera()
    private val renderer = PreviewRenderer(camera)
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var pointers = 0
    private var mesh: PreviewMesh? = null
    private var boxes: List<PreviewBox> = emptyList()
    var pickListener: PreviewPickListener? = null
    /** Box under the finger at ACTION_DOWN, the drag plane height and start point, and the drag state. */
    private var downBox: Int? = null
    private var downX = 0f
    private var downY = 0f
    private var dragZ = 0f
    private var dragStart: Vec3? = null
    private var dragging = false
    private var moved = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    /** Handle being dragged (-1 = none), its live value (mm or degrees) and the turn's start angle. */
    private var handleKind = -1
    private var handleValue = 0f
    private var startAngle = 0f
    private val density = resources.displayMetrics.density

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            reframe()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 4)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.handleSize = 14 * density
    }

    fun setColors(background: FloatArray, grid: FloatArray, part: FloatArray) {
        if (background.contentEquals(renderer.background) && grid.contentEquals(renderer.gridColor)) return
        queueEvent { renderer.background = background; renderer.gridColor = grid; renderer.partColor = part }
        requestRender()
    }

    /** Shows [mesh]; the view is framed again when the model's size changed noticeably or it grew out of the view. */
    fun setMesh(mesh: PreviewMesh?) {
        val old = this.mesh
        if (mesh === old) return
        this.mesh = mesh
        queueEvent { renderer.setMesh(mesh) }
        if (mesh != null && (old == null || !similar(old.size, mesh.size) || outgrown())) reframe() else requestRender()
    }

    /** Shape-builder boxes over the mesh (empty outside the builder); a shape added out of view re-frames it. */
    fun setBoxes(list: List<PreviewBox>) {
        if (list == boxes) return
        val wasEmpty = boxes.isEmpty() && mesh == null
        boxes = list
        if (handleKind >= 0 && handleBox() == null) handleKind = -1
        val overlay = overlay()
        queueEvent { renderer.setBoxes(list); renderer.overlay = overlay }
        if ((wasEmpty && list.isNotEmpty()) || outgrown()) reframe() else requestRender()
    }

    /** What was framed last (model space), so growth past it can re-frame. */
    private var framed: Pair<Vec3, Vec3>? = null

    /** The mesh and the builder's boxes together. */
    private fun content(): Pair<Vec3, Vec3>? {
        val all = listOfNotNull(mesh?.let { it.min to it.max }) + boxes.map { it.min to it.max }
        if (all.isEmpty()) return null
        return Vec3(all.minOf { it.first.x }, all.minOf { it.first.y }, all.minOf { it.first.z }) to
            Vec3(all.maxOf { it.second.x }, all.maxOf { it.second.y }, all.maxOf { it.second.z })
    }

    /** The content reaches past the last framed box by more than 5 % of its size. */
    private fun outgrown(): Boolean {
        val f = framed ?: return false
        val c = content() ?: return false
        val m = (f.second - f.first).length() * 0.05f
        return c.first.x < f.first.x - m || c.first.y < f.first.y - m || c.first.z < f.first.z - m ||
            c.second.x > f.second.x + m || c.second.y > f.second.y + m || c.second.z > f.second.z + m
    }

    fun reframe() {
        var fit = content()
        // Keep the turn handle's ring in view.
        val hb = handleBox()
        val p = hb?.pivot
        if (fit != null && p != null) {
            val r = ringRadius(hb, p)
            fit = Vec3(minOf(fit.first.x, p.x - r), minOf(fit.first.y, p.y - r), fit.first.z) to
                Vec3(maxOf(fit.second.x, p.x + r), maxOf(fit.second.y, p.y + r), fit.second.z)
        }
        framed = fit
        if (fit != null) queueEvent { renderer.fitPending = fit; requestRender() } else requestRender()
    }

    // --- Handles of a lone selected box: 0..2 = size along X / Y / Z (on the max faces), 3 = turn about Z ---

    private fun handleBox(): PreviewBox? = boxes.singleOrNull { it.selected }

    /** Radius of the turn ring: clear of the box's corners. */
    private fun ringRadius(b: PreviewBox, p: Vec3): Float {
        val r = kotlin.math.hypot(maxOf(p.x - b.min.x, b.max.x - p.x), maxOf(p.y - b.min.y, b.max.y - p.y))
        return r + maxOf(3f, r * 0.2f)
    }

    private fun handles(b: PreviewBox): List<Pair<Int, Vec3>> {
        val c = (b.min + b.max) * 0.5f
        val out = ArrayList<Pair<Int, Vec3>>()
        if (b.scalable) {
            out += 0 to Vec3(b.max.x, c.y, c.z)
            out += 1 to Vec3(c.x, b.max.y, c.z)
            out += 2 to Vec3(c.x, c.y, b.max.z)
        }
        b.pivot?.let { p -> out += 3 to Vec3(p.x, p.y - ringRadius(b, p), b.min.z) }
        return out
    }

    /** The handle within reach of screen point (x, y), or null. */
    private fun handleAt(x: Float, y: Float): Int? {
        val b = handleBox() ?: return null
        val reach = 24 * density
        return handles(b).mapNotNull { (k, at) -> camera.project(at)?.let { (px, py) -> k to kotlin.math.hypot(px - x, py - y) } }
            .filter { it.second <= reach }.minByOrNull { it.second }?.first
    }

    private fun axis(k: Int) = Vec3(if (k == 0) 1f else 0f, if (k == 1) 1f else 0f, if (k == 2) 1f else 0f)

    /** Size change (whole mm, the box kept at least 1 mm) for a drag of size handle [k] to (x, y). */
    private fun handleMm(b: PreviewBox, k: Int, x: Float, y: Float): Float {
        val at = handles(b).first { it.first == k }.second
        val a = camera.project(at) ?: return 0f
        val e = camera.project(at + axis(k) * 10f) ?: return 0f
        val sx = e.first - a.first
        val sy = e.second - a.second
        val len2 = sx * sx + sy * sy
        // The axis points at the camera: no reliable direction on screen.
        if (len2 < 16f) return 0f
        val mm = Math.round(((x - downX) * sx + (y - downY) * sy) / len2 * 10f).toFloat()
        val extent = (b.max - b.min).let { floatArrayOf(it.x, it.y, it.z)[k] }
        return maxOf(mm, kotlin.math.ceil(1f - extent))
    }

    private fun angleAt(b: PreviewBox, x: Float, y: Float): Float? {
        val p = b.pivot ?: return null
        val q = camera.rayOnPlane(x, y, b.min.z) ?: return null
        return Math.toDegrees(kotlin.math.atan2((q.y - p.y).toDouble(), (q.x - p.x).toDouble())).toFloat()
    }

    /** Turn (15° steps, 1° with Shift) for a drag of the turn handle to (x, y). */
    private fun handleDegrees(b: PreviewBox, event: MotionEvent): Float {
        val a = angleAt(b, event.x, event.y) ?: return handleValue
        var d = (a - startAngle) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        val step = if (event.metaState and android.view.KeyEvent.META_SHIFT_ON != 0) 1f else 15f
        return Math.round(d / step) * step
    }

    /** Handle dots, guide lines and the live result of a handle drag, in world space. */
    private fun overlay(): PreviewOverlay {
        val b = handleBox() ?: return PreviewOverlay.EMPTY
        val hs = handles(b)
        if (hs.isEmpty()) return PreviewOverlay.EMPTY
        val colors = arrayOf(floatArrayOf(0.9f, 0.25f, 0.25f), floatArrayOf(0.3f, 0.8f, 0.3f), floatArrayOf(0.25f, 0.5f, 1f), floatArrayOf(1f, 0.7f, 0.1f))
        val points = ArrayList<Float>()
        val guide = ArrayList<Float>()
        val ghost = ArrayList<Float>()
        val kind = handleKind
        for ((k, at0) in hs) {
            var at = at0
            if (k == kind && k < 3) at = at + axis(k) * handleValue
            points += listOf(at.x, at.y, at.z) + colors[k].toList()
        }
        b.pivot?.let { p ->
            // An arc through the turn handle; the whole ring while turning.
            val r = ringRadius(b, p)
            val (from, to) = if (kind == 3) -180f to 180f else -115f to -65f
            val n = if (kind == 3) 64 else 10
            for (i in 0 until n) {
                val a0 = Math.toRadians((from + (to - from) * i / n).toDouble())
                val a1 = Math.toRadians((from + (to - from) * (i + 1) / n).toDouble())
                guide += listOf(p.x + r * kotlin.math.cos(a0).toFloat(), p.y + r * kotlin.math.sin(a0).toFloat(), b.min.z,
                    p.x + r * kotlin.math.cos(a1).toFloat(), p.y + r * kotlin.math.sin(a1).toFloat(), b.min.z)
            }
        }
        if (kind in 0..2) {
            val max = b.max + axis(kind) * handleValue
            boxLines(ghost, (0 until 8).map { i -> Vec3(if (i and 1 == 0) b.min.x else max.x, if (i and 2 == 0) b.min.y else max.y, if (i and 4 == 0) b.min.z else max.z) })
        } else if (kind == 3) {
            val p = b.pivot!!
            val a = Math.toRadians(handleValue.toDouble())
            val c = kotlin.math.cos(a).toFloat()
            val s = kotlin.math.sin(a).toFloat()
            boxLines(ghost, (0 until 8).map { i ->
                val x = (if (i and 1 == 0) b.min.x else b.max.x) - p.x
                val y = (if (i and 2 == 0) b.min.y else b.max.y) - p.y
                Vec3(p.x + x * c - y * s, p.y + x * s + y * c, if (i and 4 == 0) b.min.z else b.max.z)
            })
        }
        return PreviewOverlay(points.toFloatArray(), guide.toFloatArray(), ghost.toFloatArray())
    }

    private fun pushOverlay() {
        val o = overlay()
        queueEvent { renderer.overlay = o }
        requestRender()
    }

    /** Nearest box the ray through (x, y) hits (slab test), or null. */
    private fun hit(x: Float, y: Float): PreviewBox? {
        val (o, d) = camera.ray(x, y)
        val os = floatArrayOf(o.x, o.y, o.z)
        val ds = floatArrayOf(d.x, d.y, d.z)
        var best: PreviewBox? = null
        var bestT = Float.MAX_VALUE
        for (b in boxes) {
            val mn = floatArrayOf(b.min.x, b.min.y, b.min.z)
            val mx = floatArrayOf(b.max.x, b.max.y, b.max.z)
            var t0 = -Float.MAX_VALUE
            var t1 = Float.MAX_VALUE
            var miss = false
            for (i in 0 until 3) {
                if (kotlin.math.abs(ds[i]) < 1e-9f) {
                    if (os[i] < mn[i] || os[i] > mx[i]) { miss = true; break }
                    continue
                }
                var a = (mn[i] - os[i]) / ds[i]
                var c = (mx[i] - os[i]) / ds[i]
                if (a > c) { val t = a; a = c; c = t }
                t0 = maxOf(t0, a); t1 = minOf(t1, c)
            }
            if (miss || t0 > t1 || t1 < 0f) continue
            // Selected boxes win near ties, so a picked shape inside a hole's box can be dragged.
            val t = maxOf(t0, 0f) - if (b.selected) 0.01f else 0f
            if (t < bestT) { bestT = t; best = b }
        }
        return best
    }

    /** Shape-builder picking and dragging; true when the event was used for it. */
    private fun shapeInput(event: MotionEvent): Boolean {
        val l = pickListener ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; dragging = false; moved = false; downBox = null; handleKind = -1
                if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) || event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) return false
                handleAt(event.x, event.y)?.let { k ->
                    val hb = handleBox()!!
                    if (k == 3) startAngle = angleAt(hb, event.x, event.y) ?: return false
                    handleKind = k; handleValue = 0f
                    pushOverlay()
                    return true
                }
                val b = hit(event.x, event.y) ?: return false
                downBox = b.id
                dragZ = b.min.z
                dragStart = camera.rayOnPlane(event.x, event.y, dragZ)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && kotlin.math.hypot(event.x - downX, event.y - downY) > slop) moved = true
                if (handleKind >= 0) {
                    val hb = handleBox()
                    if (event.pointerCount > 1 || hb == null) { handleKind = -1; pushOverlay(); return false }
                    val v = if (handleKind == 3) handleDegrees(hb, event) else handleMm(hb, handleKind, event.x, event.y)
                    if (v != handleValue) { handleValue = v; pushOverlay() }
                    return true
                }
                val id = downBox ?: return false
                if (event.pointerCount > 1) { downBox = null; return false }
                if (moved && !dragging) {
                    dragging = true
                    if (boxes.none { it.id == id && it.selected }) l.onPick(id, false)
                }
                if (dragging) {
                    val start = dragStart ?: return true
                    val p = camera.rayOnPlane(event.x, event.y, dragZ) ?: return true
                    val off = Vec3(Math.round(p.x - start.x).toFloat(), Math.round(p.y - start.y).toFloat(), 0f)
                    queueEvent { renderer.dragOffset = off }
                    requestRender()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (handleKind >= 0) {
                    val k = handleKind
                    val v = handleValue
                    handleKind = -1; handleValue = 0f
                    pushOverlay()
                    if (v != 0f) { if (k == 3) l.onRotate(v) else l.onResize(k, v) }
                    return true
                }
                val id = downBox
                downBox = null
                if (dragging) {
                    dragging = false
                    val start = dragStart
                    val p = camera.rayOnPlane(event.x, event.y, dragZ)
                    if (start != null && p != null) l.onMove(Math.round(p.x - start.x).toFloat(), Math.round(p.y - start.y).toFloat())
                    queueEvent { renderer.dragOffset = Vec3.ZERO }
                    requestRender()
                    return true
                }
                // A tap: picks the shape, or clears the selection on empty space.
                if (!moved) l.onPick(id, (event.metaState and (android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON)) != 0)
                return id != null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { queueEvent { renderer.dragOffset = Vec3.ZERO }; requestRender() }
                if (handleKind >= 0) { handleKind = -1; handleValue = 0f; pushOverlay() }
                downBox = null; dragging = false
            }
        }
        return false
    }

    private fun similar(a: Vec3, b: Vec3): Boolean {
        val la = a.length(); val lb = b.length()
        return lb > 0f && la / lb in 0.8f..1.25f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The preview sits in a scrolling panel: keep the gesture.
        parent?.requestDisallowInterceptTouchEvent(true)
        // Shape builder: taps pick shapes, a drag that starts on one moves the selection.
        if (shapeInput(event)) {
            lastX = event.x; lastY = event.y
            return true
        }
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        val fx = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
        val fy = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; pointers = 1 }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // The focus jumps when a finger is added or lifted; start the pan from there.
                pointers = event.pointerCount - if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) 1 else 0
                lastFocusX = fx; lastFocusY = fy
                val keep = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0) 1 else 0
                lastX = event.getX(keep); lastY = event.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                val pan = event.pointerCount > 1 || event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) ||
                    event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)
                if (event.pointerCount > 1) {
                    camera.pan(fx - lastFocusX, fy - lastFocusY)
                    lastFocusX = fx; lastFocusY = fy
                } else if (pan) {
                    camera.pan(event.x - lastX, event.y - lastY)
                } else if (pointers == 1) {
                    camera.orbit((event.x - lastX) * 0.4f, (event.y - lastY) * 0.4f)
                }
                lastX = event.x; lastY = event.y
                requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointers = 0
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) { camera.zoom(if (v > 0) 1.15f else 1 / 1.15f); requestRender(); return true }
        }
        return super.onGenericMotionEvent(event)
    }
}

private class PreviewRenderer(private val camera: Camera) : GLSurfaceView.Renderer {
    @Volatile var background = floatArrayOf(0.12f, 0.13f, 0.15f)
    @Volatile var gridColor = floatArrayOf(0.4f, 0.42f, 0.46f)
    @Volatile var partColor = floatArrayOf(0.96f, 0.58f, 0.2f)
    /** Frame these bounds on the next draw (after the viewport size is known). */
    var fitPending: Pair<Vec3, Vec3>? = null
    /** Live offset of the selected boxes while they are dragged. */
    var dragOffset = Vec3.ZERO
    /** Shape-builder handles; uploaded on the next draw when replaced. */
    var overlay = PreviewOverlay.EMPTY
        set(v) { field = v; overlayDirty = true }
    private var overlayDirty = false
    /** Handle dot diameter in pixels. */
    @Volatile var handleSize = 14f
    private var overlayVbo = 0
    private var handleProgram = 0
    private var boxList: List<PreviewBox> = emptyList()
    private var boxVbo = 0
    private var boxesDirty = false
    private var holeCount = 0
    private var selStart = 0
    private var selCount = 0

    private var meshProgram = 0
    private var lineProgram = 0
    private var meshVbo = 0
    private var gridVbo = 0
    private var meshCount = 0
    private var gridCount = 0
    private var axisStart = 0
    private var pending: PreviewMesh? = null
    private var dirty = false
    private var hasSize = false
    private val mvp = FloatArray(16)

    fun setMesh(mesh: PreviewMesh?) {
        pending = mesh
        dirty = true
    }

    fun setBoxes(list: List<PreviewBox>) {
        boxList = list
        boxesDirty = true
        // Without a mesh the grid follows the boxes.
        if (pending == null) dirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glEnable(GL_DEPTH_TEST)
        meshProgram = program(MESH_VS, MESH_FS)
        lineProgram = program(Shaders.LINE_VS, Shaders.LINE_FS)
        handleProgram = program(HANDLE_VS, Shaders.POINT_FS)
        val ids = IntArray(4)
        glGenBuffers(4, ids, 0)
        meshVbo = ids[0]; gridVbo = ids[1]; boxVbo = ids[2]; overlayVbo = ids[3]
        // A new EGL context lost the buffers: upload the last mesh again.
        dirty = true
        boxesDirty = true
        overlayDirty = true
        meshCount = 0; gridCount = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        camera.setViewport(width, height)
        hasSize = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (dirty) upload()
        if (boxesDirty) uploadBoxes()
        val ov = overlay
        if (overlayDirty) uploadOverlay(ov)
        if (hasSize) fitPending?.let { camera.fit(it.first, it.second); fitPending = null }
        val bg = background
        glClearColor(bg[0], bg[1], bg[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        camera.viewProjection(mvp)

        if (gridCount > 0) {
            glUseProgram(lineProgram)
            glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
            glUniform3f(glGetUniformLocation(lineProgram, "uOffset"), 0f, 0f, 0f)
            glBindBuffer(GL_ARRAY_BUFFER, gridVbo)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
            val color = glGetUniformLocation(lineProgram, "uColor")
            glUniform3fv(color, 1, gridColor, 0)
            glDrawArrays(GL_LINES, 0, axisStart)
            glUniform3f(color, 0.9f, 0.25f, 0.25f)
            glDrawArrays(GL_LINES, axisStart, 2)
            glUniform3f(color, 0.3f, 0.8f, 0.3f)
            glDrawArrays(GL_LINES, axisStart + 2, 2)
        }
        if (meshCount > 0) {
            glUseProgram(meshProgram)
            glUniformMatrix4fv(glGetUniformLocation(meshProgram, "uMvp"), 1, false, mvp, 0)
            glUniform3fv(glGetUniformLocation(meshProgram, "uColor"), 1, partColor, 0)
            val eye = camera.towardsEye()
            glUniform3f(glGetUniformLocation(meshProgram, "uEye"), eye.x, eye.y, eye.z)
            glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
            glEnableVertexAttribArray(0)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
            glDrawArrays(GL_TRIANGLES, 0, meshCount)
            glDisableVertexAttribArray(1)
        }
        if (holeCount + selCount > 0) {
            // Box outlines on top: holes grey (the render shows them only as cuts), the selection blue.
            glDisable(GL_DEPTH_TEST)
            glUseProgram(lineProgram)
            glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
            glBindBuffer(GL_ARRAY_BUFFER, boxVbo)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
            val color = glGetUniformLocation(lineProgram, "uColor")
            val offset = glGetUniformLocation(lineProgram, "uOffset")
            glUniform3f(offset, 0f, 0f, 0f)
            glUniform3fv(color, 1, gridColor, 0)
            if (holeCount > 0) glDrawArrays(GL_LINES, 0, holeCount)
            val d = dragOffset
            glUniform3f(offset, d.x, d.y, d.z)
            glUniform3f(color, 0.2f, 0.55f, 1f)
            // While a handle is dragged its result (the ghost) stands in for the selection.
            if (selCount > 0 && ov.ghost.isEmpty()) glDrawArrays(GL_LINES, selStart, selCount)
            glEnable(GL_DEPTH_TEST)
        }
        if (ov.points.isNotEmpty()) drawOverlay(ov)
    }

    /** Buffer layout: points (6 floats each), then guide and ghost lines (3 floats per vertex). */
    private fun uploadOverlay(o: PreviewOverlay) {
        overlayDirty = false
        val a = o.points + o.guide + o.ghost
        if (a.isEmpty()) return
        val buf = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.flip() }
        glBindBuffer(GL_ARRAY_BUFFER, overlayVbo)
        glBufferData(GL_ARRAY_BUFFER, a.size * 4, buf, GL_DYNAMIC_DRAW)
    }

    /** Ghost (blue) and guide lines (amber), then the handle dots, all on top of the model. */
    private fun drawOverlay(o: PreviewOverlay) {
        glDisable(GL_DEPTH_TEST)
        val d = dragOffset
        glBindBuffer(GL_ARRAY_BUFFER, overlayVbo)
        glUseProgram(lineProgram)
        glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
        val offset = glGetUniformLocation(lineProgram, "uOffset")
        val color = glGetUniformLocation(lineProgram, "uColor")
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, o.points.size * 4)
        if (o.guide.isNotEmpty()) {
            glUniform3f(offset, d.x, d.y, d.z)
            glUniform3f(color, 1f, 0.7f, 0.1f)
            glDrawArrays(GL_LINES, 0, o.guide.size / 3)
        }
        if (o.ghost.isNotEmpty()) {
            glUniform3f(offset, 0f, 0f, 0f)
            glUniform3f(color, 0.2f, 0.55f, 1f)
            glDrawArrays(GL_LINES, o.guide.size / 3, o.ghost.size / 3)
        }
        glUseProgram(handleProgram)
        glUniformMatrix4fv(glGetUniformLocation(handleProgram, "uMvp"), 1, false, mvp, 0)
        glUniform3f(glGetUniformLocation(handleProgram, "uOffset"), d.x, d.y, d.z)
        glUniform1f(glGetUniformLocation(handleProgram, "uSize"), handleSize)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
        glDrawArrays(GL_POINTS, 0, o.points.size / 6)
        glDisableVertexAttribArray(1)
        glEnable(GL_DEPTH_TEST)
    }

    /** 12 edges per box: the unselected holes, then the selection. */
    private fun uploadBoxes() {
        boxesDirty = false
        val lines = ArrayList<Float>()
        fun box(b: PreviewBox) {
            val x = floatArrayOf(b.min.x, b.max.x)
            val y = floatArrayOf(b.min.y, b.max.y)
            val z = floatArrayOf(b.min.z, b.max.z)
            for (i in 0..1) for (j in 0..1) {
                lines += listOf(x[0], y[i], z[j], x[1], y[i], z[j])
                lines += listOf(x[i], y[0], z[j], x[i], y[1], z[j])
                lines += listOf(x[i], y[j], z[0], x[i], y[j], z[1])
            }
        }
        boxList.filter { !it.selected && it.hole }.forEach(::box)
        holeCount = lines.size / 3
        selStart = holeCount
        boxList.filter { it.selected }.forEach(::box)
        selCount = lines.size / 3 - selStart
        if (lines.isEmpty()) return
        val a = lines.toFloatArray()
        val buf = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.flip() }
        glBindBuffer(GL_ARRAY_BUFFER, boxVbo)
        glBufferData(GL_ARRAY_BUFFER, a.size * 4, buf, GL_STATIC_DRAW)
    }

    private fun upload() {
        dirty = false
        val m = pending
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
        if (m == null) {
            meshCount = 0
            // The shape builder's workplane without a mesh: around the boxes, else 100 mm.
            val b = boxList
            if (b.isEmpty()) uploadGrid(Vec3(-50f, -50f, 0f), Vec3(50f, 50f, 0f))
            else uploadGrid(Vec3(b.minOf { it.min.x }, b.minOf { it.min.y }, 0f), Vec3(b.maxOf { it.max.x }, b.maxOf { it.max.y }, 0f))
            return
        }
        m.data.position(0)
        glBufferData(GL_ARRAY_BUFFER, m.data.limit() * 4, m.data, GL_STATIC_DRAW)
        meshCount = m.triangles * 3
        uploadGrid(m.min, m.max)
    }

    /** 10 mm grid lines (50 mm for big models) at z = 0 under the model, then the two axes. */
    private fun uploadGrid(min: Vec3, max: Vec3) {
        val sx = max.x - min.x
        val sy = max.y - min.y
        val step = if (maxOf(sx, sy) > 400f) 50f else 10f
        val margin = maxOf(10f, maxOf(sx, sy) * 0.25f)
        val x0 = floor((min.x - margin) / step) * step; val x1 = ceil((max.x + margin) / step) * step
        val y0 = floor((min.y - margin) / step) * step; val y1 = ceil((max.y + margin) / step) * step
        val lines = ArrayList<Float>()
        var x = x0
        while (x <= x1 + 0.01f) { if (x != 0f) lines += listOf(x, y0, 0f, x, y1, 0f); x += step }
        var y = y0
        while (y <= y1 + 0.01f) { if (y != 0f) lines += listOf(x0, y, 0f, x1, y, 0f); y += step }
        axisStart = lines.size / 3
        // Axes from the origin, drawn on top of the grid lines they replace.
        lines += listOf(minOf(0f, x0), 0f, 0f, maxOf(x1, step), 0f, 0f)
        lines += listOf(0f, minOf(0f, y0), 0f, 0f, maxOf(y1, step), 0f)
        val a = lines.toFloatArray()
        val buf = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.flip() }
        glBindBuffer(GL_ARRAY_BUFFER, gridVbo)
        glBufferData(GL_ARRAY_BUFFER, a.size * 4, buf, GL_STATIC_DRAW)
        gridCount = a.size / 3
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: " + glGetShaderInfoLog(s) }
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        return p
    }

    private companion object {
        /** Handle dots: a fixed pixel size, coloured per vertex (round in [Shaders.POINT_FS]). */
        const val HANDLE_VS = """#version 300 es
            uniform mat4 uMvp;
            uniform vec3 uOffset;
            uniform float uSize;
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aColor;
            out vec3 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMvp * vec4(aPos + uOffset, 1.0);
                gl_PointSize = uSize;
            }"""

        const val MESH_VS = """#version 300 es
            uniform mat4 uMvp;
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            out vec3 vNormal;
            void main() {
                vNormal = aNormal;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        /** A key light from the camera's upper left plus a fixed fill, so every face reads. */
        const val MESH_FS = """#version 300 es
            precision mediump float;
            uniform vec3 uColor;
            uniform vec3 uEye;
            in vec3 vNormal;
            out vec4 color;
            void main() {
                vec3 n = normalize(vNormal);
                float key = abs(dot(n, normalize(uEye + vec3(0.0, 0.0, 0.6))));
                float fill = abs(dot(n, normalize(vec3(0.35, -0.55, 0.75))));
                color = vec4(uColor * (0.28 + 0.52 * key + 0.25 * fill), 1.0);
            }"""
    }
}
