package app.orcaandroid.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import app.orcaandroid.core.PenAction
import app.orcaandroid.core.PenConfig
import app.orcaandroid.core.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** VIEW: camera only (the Preview tab: nothing to pick, select or move, as on the desktop). */
enum class InteractionMode { NAVIGATE, PAINT, LAY_ON_FACE, MEASURE, VIEW }

/** What the 3D view reports back to the app. Called on the UI thread. */
interface PlateListener {
    /**
     * A tap (not a drag); the host picks and selects / lays on face / measures depending on the mode.
     * [additive]: Ctrl or Shift was held (adds to the selection); [part]: Alt was held (selects the
     * part under the pointer), as on the desktop.
     */
    /** [emptyPlate]: another plate under the touch; it becomes active when the tap hits no object. */
    fun onTap(x: Float, y: Float, additive: Boolean, part: Boolean, emptyPlate: Int? = null)
    /**
     * Shift+drag (add) or Alt+drag (remove) draws a selection rectangle from (x0, y0) to (x1, y1);
     * [done] once the button is released.
     */
    fun onRectangle(x0: Float, y0: Float, x1: Float, y1: Float, remove: Boolean, done: Boolean)
    /** Ctrl+wheel while painting: [steps] > 0 grows the brush. */
    fun onBrushScroll(steps: Int)
    /** The selected copies were dragged by (dx, dy) mm on the bed. */
    fun onMoved(dx: Float, dy: Float)
    /**
     * A corner of the single selected copy's box is being dragged to scale it uniformly about the opposite corner [anchor];
     * (x, y) is the finger. [factor] <= 0 when the drag was cancelled; [done] once let go.
     */
    fun onResizeCorner(factor: Float, anchor: Vec3, x: Float, y: Float, done: Boolean) {}
    /** The prime tower of [plate] was dragged by (dx, dy) mm on the bed. */
    fun onTowerMoved(plate: Int, dx: Float, dy: Float) {}
    /** Paint stroke sample at screen (x, y); [newStroke] for the first sample of a stroke. */
    fun onPaint(x: Float, y: Float, newStroke: Boolean)
    /** A drag began inside the selection's bounding box: [result] gets whether the mesh itself lies under (x, y). */
    fun onDragCheck(x: Float, y: Float, result: (Boolean) -> Unit) = result(true)
    /** A right click or a long press that did not drag: the host shows a context menu at (x, y). */
    fun onContextMenu(x: Float, y: Float)
    /** A double tap on plate [index] that isn't the active one: the host makes it active (the view frames it). */
    fun onPlateDoubleTap(index: Int) {}
    /** A tap on a badge beside plate [plate] (its number or one of its icons) at view point (x, y). */
    fun onPlateBadge(plate: Int, badge: PlateBadge, x: Float, y: Float) {}
    /** A pen button or the eraser end was mapped to an instant action (undo, redo, frame). */
    fun onPenAction(action: PenAction) {}
}

/**
 * GLSurfaceView hosting [PlateRenderer] with touch navigation: one finger orbits (or drags the
 * selected object), two fingers pinch-zoom and pan, double tap frames the plate, long press opens
 * the context menu. With a mouse, as on the desktop: left-drag orbits, right- or middle-drag pans,
 * the wheel zooms, a right click opens the context menu, Shift+drag / Alt+drag selects / deselects
 * by rectangle, Alt+click selects a part, Ctrl+wheel sizes the paint brush and Alt+wheel moves the
 * section view's cut.
 */
/** A plate's prime tower: its world bounding box. */
class TowerBox(val plate: Int, val lo: Vec3, val hi: Vec3)

@SuppressLint("ViewConstructor")
class PlateView(context: Context) : GLSurfaceView(context) {

    val camera = Camera()
    val renderer = PlateRenderer(camera).also {
        it.deviceLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        it.outlinePx = 2.5f * context.resources.displayMetrics.density
    }
    val section = SectionView(camera)
    var listener: PlateListener? = null
    var mode = InteractionMode.NAVIGATE
    /** World bounding boxes (min, max) of the selected copies; a drag starting on them moves them. */
    var selectedBounds: List<Pair<Vec3, Vec3>> = emptyList()
    /** The prime towers (world mm); a drag starting on one moves it. */
    var towerBoxes: List<TowerBox> = emptyList()
    private var towerPlate = -1

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
    /**
     * What a two-finger gesture turned out to be: undecided until the fingers' gap or their midpoint moves past
     * the touch slop. A pinch only zooms (the midpoint wanders as fingers spread, which panned the view, user
     * 2026-10-17); a two-finger drag pans and zooms.
     */
    private var twoFinger = TwoFinger.UNDECIDED
    private var startSpan = 0f
    private var startFocusX = 0f
    private var startFocusY = 0f
    /** Pinch zoom speed (Layout lab): the exponent on the fingers' gap ratio, 1 = the gap ratio itself. */
    var pinchSpeed = 0.6f
    /** Pen buttons and eraser mapping, and palm rejection (More > Pen). */
    var pen = PenConfig()
    /** When a pen last touched or hovered (uptime ms); 0 after it left the hover range. */
    private var lastPenEvent = 0L
    private val penNear get() = lastPenEvent != 0L && android.os.SystemClock.uptimeMillis() - lastPenEvent < PEN_NEAR_MS
    /** The gesture is a finger touch that came while the pen was near (a resting palm): ignored to its end. */
    private var ignoreGesture = false
    /** The gesture began with a pen button / eraser mapped to Orbit, or to an instant action ([penInstant], fires on a tap). */
    private var penOrbit = false
    private var penInstant: PenAction? = null
    private var lastInstantTime = 0L
    /** The gesture began with the right / middle mouse button (pans; a right click opens the menu). */
    private var rightButton = false
    private var middleButton = false
    private var hoverPanning = false
    /** A long press opened the context menu; the rest of the gesture is ignored. */
    private var longPressed = false
    /** Farthest the finger got from the touch-down point: a slow orbit stays inside the touch slop but is no long press. */
    private var maxTravel = 0f
    /** Shift / Alt held when the gesture began: a drag draws a selection rectangle. */
    private var rectangle = false
    private var rectangleRemove = false
    /** When a badge was last tapped: the second tap of a double tap must not hit the next plate's badge. */
    private var badgeTapTime = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Double-tap-and-drag (one finger) zooms at once.
            if (multiTouch && twoFinger == TwoFinger.UNDECIDED) return true
            // The pad keeps fingers close together from zooming in leaps (a 1 mm wobble is a big gap ratio there);
            // the camera's own limits are unchanged, so the deep zoom-in only takes more pinching.
            val pad = PINCH_PAD_DP * resources.displayMetrics.density
            val ratio = (detector.currentSpan + pad) / (detector.previousSpan + pad)
            camera.zoomAt(ratio.pow(pinchSpeed), detector.focusX, detector.focusY)
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        renderer.deviceLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        super.onSizeChanged(w, h, oldw, oldh)
    }

    /** A plain tap on empty bed of another plate makes it the active one (user, 2026-10-15); the scene decides after the pick. */
    private fun tap(x: Float, y: Float, additive: Boolean, part: Boolean) {
        val other = if (mode == InteractionMode.NAVIGATE && !additive && !part) renderer.plateAt(x, y)?.takeIf { it != renderer.activePlate } else null
        listener?.onTap(x, y, additive, part, other)
    }

    private val gestureDetector =GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (mode == InteractionMode.VIEW && !rightButton && !middleButton) { queueEvent { renderer.frame(renderer.activePlate) }; return true }
            if (mode != InteractionMode.NAVIGATE || rightButton || middleButton) return true
            // The first tap hit a badge (the view may have moved since): the second one does nothing.
            if (badgeTapTime >= e.eventTime) return true
            // On another plate: focus that one (user, 2026-10-13); else re-frame the active plate.
            val other = renderer.plateAt(e.x, e.y)?.takeIf { it != renderer.activePlate }
            if (other != null) listener?.onPlateDoubleTap(other)
            // Selecting another plate leaves the camera alone (user, 2026-10-15); only the active plate is re-framed.
            if (other == null) queueEvent { renderer.frame(renderer.activePlate) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != InteractionMode.NAVIGATE || multiTouch || moved || maxTravel > touchSlop / 3 || rightButton || middleButton || rectangle || penOrbit || penInstant != null) return
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
        // Takes keyboard focus on touch, like the desktop's canvas, for the 3D view's shortcuts.
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Runs [block] on the GL thread. */
    fun onGl(block: PlateRenderer.() -> Unit) = queueEvent { renderer.block() }

    // Compose would otherwise use arrow keys and Tab to move its focus before the activity sees them.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = onKey?.invoke(keyCode, event) == true || super.onKeyDown(keyCode, event)

    companion object {
        /** The app's 3D view, for keyboard shortcuts that move the camera. */
        var active: java.lang.ref.WeakReference<PlateView>? = null
        /** Single-key shortcuts while the view has focus (set by the activity); true when handled. */
        var onKey: ((Int, KeyEvent) -> Boolean)? = null
        /** Added to both finger gaps of a pinch step (see onScale). */
        private const val PINCH_PAD_DP = 40f
        /** Fingers stay ignored this long after the pen's last event (a hovering pen sends none while it rests). */
        private const val PEN_NEAR_MS = 1000L
        /** A button press already fired its instant action; the tap that follows within this time does not fire it again. */
        private const val INSTANT_REPEAT_MS = 1500L
    }

    private fun isPenTool(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    /** Some pens report their button as the mouse's secondary / tertiary button instead of the stylus buttons. */
    private fun penActionOf(tool: Int, buttons: Int): PenAction = when {
        tool == MotionEvent.TOOL_TYPE_ERASER -> pen.eraser
        tool != MotionEvent.TOOL_TYPE_STYLUS -> PenAction.TIP
        buttons and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0 -> pen.button1
        buttons and (MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_TERTIARY) != 0 -> pen.button2
        else -> PenAction.TIP
    }

    private fun fireInstant(action: PenAction) {
        lastInstantTime = android.os.SystemClock.uptimeMillis()
        listener?.onPenAction(action)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (android.util.Log.isLoggable(INPUT_TAG, android.util.Log.DEBUG))
            android.util.Log.d(INPUT_TAG, "focus $gainFocus view=${System.identityHashCode(this)} root=${rootView.findFocus()?.javaClass?.simpleName}")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (android.util.Log.isLoggable(INPUT_TAG, android.util.Log.DEBUG))
            android.util.Log.d(INPUT_TAG, "touch ${MotionEvent.actionToString(event.action)} buttons=${event.buttonState} meta=${event.metaState} tool=${event.getToolType(0)} source=${event.source}")
        // Compose's interop may hand the wheel over as a touch event.
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) return onGenericMotionEvent(event)
        val penTouch = isPenTool(event.getToolType(0))
        if (penTouch) lastPenEvent = android.os.SystemClock.uptimeMillis()
        // Palm rejection: a finger that lands while the pen is near is ignored until it lifts.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoreGesture = !penTouch && pen.ignoreFingers && penNear
        if (ignoreGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignoreGesture = false
            return true
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hasFocus()) requestFocus()
                lastX = event.x; lastY = event.y; downX = event.x; downY = event.y
                multiTouch = false; moved = false; dragging = false; longPressed = false; maxTravel = 0f
                // The button state is only known on DOWN; on UP it is already released.
                val mouse = event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                rightButton = mouse && event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                middleButton = mouse && event.buttonState and MotionEvent.BUTTON_TERTIARY != 0
                rectangleRemove = event.metaState and KeyEvent.META_ALT_ON != 0
                rectangle = mode == InteractionMode.NAVIGATE && !rightButton && !middleButton &&
                    (rectangleRemove || event.metaState and KeyEvent.META_SHIFT_ON != 0)
                penOrbit = false; penInstant = null
                val penAction = penActionOf(event.getToolType(0), event.buttonState)
                when (penAction) {
                    PenAction.PAN -> middleButton = true
                    PenAction.MENU -> rightButton = true
                    PenAction.ORBIT -> penOrbit = true
                    PenAction.SELECT_ADD, PenAction.SELECT_REMOVE -> if (mode == InteractionMode.NAVIGATE) {
                        rectangle = true
                        rectangleRemove = penAction == PenAction.SELECT_REMOVE
                    }
                    PenAction.UNDO, PenAction.REDO, PenAction.FRAME -> penInstant = penAction
                    PenAction.TIP -> Unit
                }
                if (rightButton || middleButton || rectangle || penOrbit || penInstant != null) Unit
                else if (mode == InteractionMode.NAVIGATE && startResize(event.x, event.y)) Unit
                else if (mode == InteractionMode.PAINT) listener?.onPaint(event.x, event.y, true)
                else if (mode == InteractionMode.NAVIGATE && hitsSelection(event.x, event.y)) {
                    dragging = true
                    dragStart = camera.rayOnPlane(event.x, event.y)
                    // The box is bigger than the mesh at some angles: a miss on the mesh hands the touch back to the camera.
                    val id = ++dragCheckId
                    listener?.onDragCheck(event.x, event.y) { hit -> if (!hit && id == dragCheckId && dragging) cancelDrag() }
                } else if (mode == InteractionMode.NAVIGATE) {
                    towerBoxes.firstOrNull { rayHitsBox(event.x, event.y, listOf(it.lo to it.hi)) }?.let { t ->
                        towerPlate = t.plate
                        dragging = true
                        dragStart = camera.rayOnPlane(event.x, event.y)
                        queueEvent { renderer.dragTower = t.plate }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                cancelDrag()
                lastFocusX = focusX(event); lastFocusY = focusY(event)
                if (event.pointerCount == 2) {
                    twoFinger = TwoFinger.UNDECIDED
                    startSpan = span(event); startFocusX = lastFocusX; startFocusY = lastFocusY
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                lastFocusX = focusX(event); lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                maxTravel = max(maxTravel, abs(event.x - downX) + abs(event.y - downY))
                if (maxTravel > touchSlop) moved = true
                if (longPressed) return true
                if (event.pointerCount >= 2) {
                    val fx = focusX(event); val fy = focusY(event)
                    if (twoFinger == TwoFinger.UNDECIDED) {
                        // One finger still while the other spreads moves the midpoint half as far as the gap changes.
                        val gap = abs(span(event) - startSpan)
                        val shift = kotlin.math.hypot(fx - startFocusX, fy - startFocusY)
                        if (gap > touchSlop && gap >= shift) twoFinger = TwoFinger.PINCH
                        else if (shift > touchSlop) twoFinger = TwoFinger.PAN
                    }
                    if (twoFinger == TwoFinger.PAN) camera.panGrab(lastFocusX, lastFocusY, fx, fy)
                    lastFocusX = fx; lastFocusY = fy
                } else if (!multiTouch) {
                    when {
                        rectangle -> if (moved) listener?.onRectangle(downX, downY, event.x, event.y, rectangleRemove, false)
                        rightButton || middleButton -> camera.panGrab(lastX, lastY, event.x, event.y)
                        penOrbit -> camera.orbit((event.x - lastX) * 0.3f, (event.y - lastY) * 0.3f)
                        penInstant != null -> Unit
                        mode == InteractionMode.PAINT -> {
                            // Fill gaps of fast strokes so the painted trail stays continuous.
                            val dx = event.x - lastX
                            val dy = event.y - lastY
                            val steps = (kotlin.math.hypot(dx, dy) / PAINT_STEP_PX).toInt().coerceIn(1, 32)
                            for (i in 1..steps) listener?.onPaint(lastX + dx * i / steps, lastY + dy * i / steps, false)
                        }
                        resizeAnchor != null -> {
                            val a = resizeAnchor!!
                            resizeFactor = (kotlin.math.hypot(event.x - resizeAnchorPx.first, event.y - resizeAnchorPx.second) / resizeStartDist).coerceIn(0.1f, 10f)
                            if (moved) {
                                val f = resizeFactor
                                queueEvent { renderer.resizeAnchor = floatArrayOf(a.x, a.y, 0f); renderer.resizeScale = f }
                                listener?.onResizeCorner(f, a, event.x, event.y, false)
                            }
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
                val additive = event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) != 0
                val part = event.metaState and KeyEvent.META_ALT_ON != 0
                if (rectangle && moved) {
                    listener?.onRectangle(downX, downY, event.x, event.y, rectangleRemove, true)
                } else if (penInstant != null || penOrbit) {
                    val action = penInstant
                    if (action != null && !moved && android.os.SystemClock.uptimeMillis() - lastInstantTime > INSTANT_REPEAT_MS) fireInstant(action)
                } else if (longPressed || middleButton) {
                    // Handled: the menu is open, or the middle button only pans.
                } else if (rightButton) {
                    if (!moved && mode == InteractionMode.NAVIGATE) listener?.onContextMenu(event.x, event.y)
                } else if (resizeAnchor != null) {
                    val a = resizeAnchor!!
                    resizeAnchor = null
                    listener?.onResizeCorner(if (moved) resizeFactor else 0f, a, event.x, event.y, true)
                } else if (dragging) {
                    val start = dragStart
                    val end = camera.rayOnPlane(event.x, event.y)
                    if (moved && start != null && end != null) {
                        // The listener clears the drag offset once the moved scene is drawn; clearing it here
                        // made the object jump back to its old spot until the engine answered.
                        dragging = false
                        dragStart = null
                        val plate = towerPlate
                        towerPlate = -1
                        if (plate >= 0) listener?.onTowerMoved(plate, end.x - start.x, end.y - start.y)
                        else listener?.onMoved(end.x - start.x, end.y - start.y)
                    } else {
                        cancelDrag()
                        if (!moved) tap(event.x, event.y, additive, part)
                    }
                } else if (!moved && !multiTouch && mode != InteractionMode.PAINT && mode != InteractionMode.VIEW) {
                    val badge = if (mode == InteractionMode.NAVIGATE && !additive && !part) renderer.badgeAt(event.x, event.y) else null
                    if (badge == null) tap(event.x, event.y, additive, part)
                    else if (event.eventTime - badgeTapTime > ViewConfiguration.getDoubleTapTimeout()) {
                        badgeTapTime = event.eventTime
                        listener?.onPlateBadge(badge.first, badge.second, event.x, event.y)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (rectangle && moved) listener?.onRectangle(downX, downY, downX, downY, rectangleRemove, true)
                rectangle = false
                cancelDrag()
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (android.util.Log.isLoggable(INPUT_TAG, android.util.Log.DEBUG))
            android.util.Log.d(INPUT_TAG, "generic ${MotionEvent.actionToString(event.action)} buttons=${event.buttonState} meta=${event.metaState} " +
                "v=${event.getAxisValue(MotionEvent.AXIS_VSCROLL)} h=${event.getAxisValue(MotionEvent.AXIS_HSCROLL)} source=${event.source}")
        // A pen near the screen (palm rejection) and a button pressed in the air fire an instant action (undo, redo, frame).
        if (isPenTool(event.getToolType(0))) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> lastPenEvent = android.os.SystemClock.uptimeMillis()
                MotionEvent.ACTION_HOVER_EXIT -> lastPenEvent = 0L
                MotionEvent.ACTION_BUTTON_PRESS -> penActionOf(event.getToolType(0), event.actionButton).takeIf { it.instant }?.let(::fireInstant)
            }
            return true
        }
        // WSA delivers a right / middle drag as hover moves with the button held, not as touch moves.
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE &&
            event.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY) != 0) {
            if (hoverPanning) camera.panGrab(lastX, lastY, event.x, event.y)
            hoverPanning = true
            moved = true
            lastX = event.x; lastY = event.y
            return true
        }
        hoverPanning = false
        // The desktop canvas takes keyboard focus when the mouse enters it, unless a text field has
        // it (GLCanvas3D.cpp on_mouse Entering), so its shortcuts work without a click first. WSA
        // sends no enter when a closed menu leaves the pointer over the view, so any hover counts.
        if (!hasFocus() && rootView.findFocus()?.onCheckIsTextEditor() != true) requestFocus()
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            // Shift turns the wheel into a horizontal scroll; it still zooms, as on the desktop.
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (v == 0f) return true
            if (event.metaState and KeyEvent.META_ALT_ON != 0) section.step(v > 0)
            else if (mode ==InteractionMode.PAINT && event.metaState and KeyEvent.META_CTRL_ON != 0) listener?.onBrushScroll(if (v > 0) 1 else -1)
            else camera.zoomAt(if (v > 0) 1.15f else 1f / 1.15f, event.x, event.y)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private var dragCheckId = 0

    private var resizeAnchor: Vec3? = null
    private var resizeAnchorPx = 0f to 0f
    private var resizeStartDist = 1f
    private var resizeFactor = 1f

    /** A touch on a corner of the lone selected box starts a uniform resize about the opposite corner. */
    private fun startResize(x: Float, y: Float): Boolean {
        val (lo, hi) = selectedBounds.singleOrNull() ?: return false
        var best: Vec3? = null
        var bestD = 48f * resources.displayMetrics.density
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            val c = Vec3(if (cx == 0) lo.x else hi.x, if (cy == 0) lo.y else hi.y, if (cz == 0) lo.z else hi.z)
            val (px, py) = camera.project(c) ?: continue
            val d = kotlin.math.hypot(px - x, py - y)
            if (d < bestD) { bestD = d; best = c }
        }
        val corner = best ?: return false
        val anchor = Vec3(lo.x + hi.x - corner.x, lo.y + hi.y - corner.y, lo.z + hi.z - corner.z)
        val a = camera.project(anchor) ?: return false
        resizeAnchor = anchor; resizeAnchorPx = a
        resizeStartDist = kotlin.math.hypot(x - a.first, y - a.second).coerceAtLeast(1f)
        resizeFactor = 1f
        return true
    }

    private fun cancelResize(x: Float = 0f, y: Float = 0f) {
        val a = resizeAnchor ?: return
        resizeAnchor = null
        listener?.onResizeCorner(0f, a, x, y, true)
    }

    private fun cancelDrag() {
        cancelResize()
        dragCheckId++
        if (dragging) queueEvent { renderer.dragOffset = floatArrayOf(0f, 0f, 0f) }
        dragging = false
        dragStart = null
        towerPlate = -1
    }

    /** Ray / axis-aligned box test against the selected object's instances. */
    private fun hitsSelection(x: Float, y: Float) = rayHitsBox(x, y, selectedBounds)

    private fun rayHitsBox(x: Float, y: Float, boxes: List<Pair<Vec3, Vec3>>): Boolean {
        if (boxes.isEmpty()) return false
        val (o, d) = camera.ray(x, y)
        return boxes.any { (lo, hi) ->
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
    private fun span(e: MotionEvent) = kotlin.math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

    private enum class TwoFinger { UNDECIDED, PINCH, PAN }

}

/** `adb shell setprop log.tag.OrcaInput DEBUG` logs the view's mouse / touch events. */
private const val INPUT_TAG = "OrcaInput"

/** Spacing of interpolated paint samples, in pixels. */
private const val PAINT_STEP_PX = 12f
