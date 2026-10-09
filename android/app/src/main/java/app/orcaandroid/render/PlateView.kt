package app.orcaandroid.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import app.orcaandroid.core.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class InteractionMode { NAVIGATE, PAINT, LAY_ON_FACE, MEASURE }

/** What the 3D view reports back to the app. Called on the UI thread. */
interface PlateListener {
    /** A tap (not a drag); the host picks and selects / lays on face / measures depending on the mode. */
    fun onTap(x: Float, y: Float)
    /** The selected object was dragged by (dx, dy) mm on the bed. */
    fun onMoved(dx: Float, dy: Float)
    /** Paint stroke sample at screen (x, y); [newStroke] for the first sample of a stroke. */
    fun onPaint(x: Float, y: Float, newStroke: Boolean)
    /** A right click or a long press that did not drag: the host shows a context menu at (x, y). */
    fun onContextMenu(x: Float, y: Float)
}

/**
 * GLSurfaceView hosting [PlateRenderer] with touch navigation: one finger orbits (or drags the
 * selected object), two fingers pinch-zoom and pan, double tap frames the plate, long press opens
 * the context menu. With a mouse, as on the desktop: left-drag orbits, right- or middle-drag pans,
 * the wheel zooms and a right click opens the context menu.
 */
@SuppressLint("ViewConstructor")
class PlateView(context: Context) : GLSurfaceView(context) {

    val camera = Camera()
    val renderer = PlateRenderer(camera)
    var listener: PlateListener? = null
    var mode = InteractionMode.NAVIGATE
    /** World bounding boxes (min, max) of the selected object's instances; a drag starting on them moves it. */
    var selectedBounds: List<Pair<Vec3, Vec3>> = emptyList()

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false
    private var moved = false
    private var dragging = false
    private var dragStart: Vec3? = null
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    /** The gesture began with the right / middle mouse button (pans; a right click opens the menu). */
    private var rightButton = false
    private var middleButton = false
    /** A long press opened the context menu; the rest of the gesture is ignored. */
    private var longPressed = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera.zoom(detector.scaleFactor)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (mode == InteractionMode.NAVIGATE && !rightButton && !middleButton) queueEvent { renderer.frame(renderer.activePlate) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != InteractionMode.NAVIGATE || multiTouch || moved || rightButton || middleButton) return
            cancelDrag()
            longPressed = true
            listener?.onContextMenu(e.x, e.y)
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 4)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
    }

    /** Runs [block] on the GL thread. */
    fun onGl(block: PlateRenderer.() -> Unit) = queueEvent { renderer.block() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; downX = event.x; downY = event.y
                multiTouch = false; moved = false; dragging = false; longPressed = false
                // The button state is only known on DOWN; on UP it is already released.
                val mouse = event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                rightButton = mouse && event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                middleButton = mouse && event.buttonState and MotionEvent.BUTTON_TERTIARY != 0
                if (rightButton || middleButton) Unit
                else if (mode == InteractionMode.PAINT) listener?.onPaint(event.x, event.y, true)
                else if (mode == InteractionMode.NAVIGATE && hitsSelection(event.x, event.y)) {
                    dragging = true
                    dragStart = camera.rayOnPlane(event.x, event.y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                cancelDrag()
                lastFocusX = focusX(event); lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                lastFocusX = focusX(event); lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) + abs(event.y - downY) > touchSlop) moved = true
                if (longPressed) return true
                if (event.pointerCount >= 2) {
                    val fx = focusX(event); val fy = focusY(event)
                    camera.pan(fx - lastFocusX, fy - lastFocusY)
                    lastFocusX = fx; lastFocusY = fy
                } else if (!multiTouch) {
                    when {
                        rightButton || middleButton -> camera.pan(event.x - lastX, event.y - lastY)
                        mode == InteractionMode.PAINT -> {
                            // Fill gaps of fast strokes so the painted trail stays continuous.
                            val dx = event.x - lastX
                            val dy = event.y - lastY
                            val steps = (kotlin.math.hypot(dx, dy) / PAINT_STEP_PX).toInt().coerceIn(1, 32)
                            for (i in 1..steps) listener?.onPaint(lastX + dx * i / steps, lastY + dy * i / steps, false)
                        }
                        dragging -> {
                            val start = dragStart
                            val now = camera.rayOnPlane(event.x, event.y)
                            if (start != null && now != null) {
                                val d = floatArrayOf(now.x - start.x, now.y - start.y, 0f)
                                queueEvent { renderer.dragOffset = d }
                            }
                        }
                        else -> camera.orbit((event.x - lastX) * 0.3f, (event.y - lastY) * 0.3f)
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (longPressed || middleButton) {
                    // Handled: the menu is open, or the middle button only pans.
                } else if (rightButton) {
                    if (!moved && mode == InteractionMode.NAVIGATE) listener?.onContextMenu(event.x, event.y)
                } else if (dragging) {
                    val start = dragStart
                    val end = camera.rayOnPlane(event.x, event.y)
                    cancelDrag()
                    if (moved && start != null && end != null) listener?.onMoved(end.x - start.x, end.y - start.y)
                    else if (!moved) listener?.onTap(event.x, event.y)
                } else if (!moved && !multiTouch && mode != InteractionMode.PAINT) {
                    listener?.onTap(event.x, event.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            camera.zoom(if (v > 0) 1.15f else 1f / 1.15f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun cancelDrag() {
        if (dragging) queueEvent { renderer.dragOffset = floatArrayOf(0f, 0f, 0f) }
        dragging = false
        dragStart = null
    }

    /** Ray / axis-aligned box test against the selected object's instances. */
    private fun hitsSelection(x: Float, y: Float): Boolean {
        if (selectedBounds.isEmpty()) return false
        val (o, d) = camera.ray(x, y)
        return selectedBounds.any { (lo, hi) ->
            var tMin = 0f
            var tMax = Float.MAX_VALUE
            val os = floatArrayOf(o.x, o.y, o.z); val ds = floatArrayOf(d.x, d.y, d.z)
            val los = floatArrayOf(lo.x, lo.y, lo.z); val his = floatArrayOf(hi.x, hi.y, hi.z)
            for (i in 0..2) {
                if (abs(ds[i]) < 1e-8f) {
                    if (os[i] < los[i] || os[i] > his[i]) return@any false
                } else {
                    var t1 = (los[i] - os[i]) / ds[i]
                    var t2 = (his[i] - os[i]) / ds[i]
                    if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                    tMin = max(tMin, t1)
                    tMax = min(tMax, t2)
                    if (tMin > tMax) return@any false
                }
            }
            true
        }
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private fun focusX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun focusY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
}

/** Spacing of interpolated paint samples, in pixels. */
private const val PAINT_STEP_PX = 12f
