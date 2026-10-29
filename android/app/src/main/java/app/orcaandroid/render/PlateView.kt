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

/**
 * A face grabbed by push / pull, in plate coordinates: [point] on it, [dir] its outward normal per design millimetre,
 * [length] the size the push changes (design mm). [live]: the shape is a volume of its own, drawn stretched by [axes]
 * (unit) about [anchor] (the opposite side) while dragged.
 */
class PushGrab(val point: Vec3, val dir: Vec3, val length: Float, val anchor: Vec3, val axes: List<Vec3>, val live: Boolean)

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
    /** The lift dot ([PlateView.liftMode]) was dragged: the selection moves [dz] mm up (down when negative). */
    fun onLifted(dz: Float) {}
    /**
     * A handle of the scale gizmo on the single selected copy is being dragged ([ScaleGizmo]): the copy scales by [scale]
     * (world x, y, z) about [pivot], its box's bottom centre; (x, y) is the finger. [scale] is null when the drag was
     * cancelled; [done] once let go.
     */
    fun onResize(scale: Vec3?, pivot: Vec3, x: Float, y: Float, done: Boolean) {}
    /**
     * A ring of the rotate gizmo on the single selected copy is being dragged ([RotateGizmo]): the copy turns by [degrees]
     * about world [axis] (0 X, 1 Y, 2 Z) through [pivot], its box's centre; (x, y) is the finger. [degrees] is null when the
     * drag was cancelled; [done] once let go.
     */
    fun onRotate(axis: Int, degrees: Float?, pivot: Vec3, x: Float, y: Float, done: Boolean) {}
    /** Cut tool: the plane was dragged by [d] mm along [CutPlane.slideAxis] (up, or sideways for an upright plane). */
    fun onCutMove(d: Float) {}
    /**
     * Cut tool: a tilt ring is dragged ([RotateGizmo] about world [axis] 0 X or 1 Y through the plane's centre): the plane turns
     * by [degrees] from its orientation [base] (Euler degrees) at the drag's start; (x, y) is the finger. [degrees] null = cancelled.
     */
    fun onCutTilt(axis: Int, degrees: Float?, base: Vec3, x: Float, y: Float, done: Boolean) {}
    /** The prime tower of [plate] was dragged by (dx, dy) mm on the bed. */
    fun onTowerMoved(plate: Int, dx: Float, dy: Float) {}
    /** Paint stroke sample at screen (x, y); [newStroke] for the first sample of a stroke. */
    fun onPaint(x: Float, y: Float, newStroke: Boolean)
    /** The paint (or sculpt) finger lifted. */
    fun onPaintEnd() {}
    /** Paint mode: a mouse or pen hovers at (x, y) with no button held; [inside] false once it left the view. */
    fun onPaintHover(x: Float, y: Float, inside: Boolean) {}
    /** Push / pull mode ([PlateView.pushMode]): [result] gets the flat face under (x, y), or null (the drag turns the camera). */
    fun onPushCheck(x: Float, y: Float, result: (PushGrab?) -> Unit) = result(null)
    /** Push / pull: [grab]'s face moved [mm] (design mm, snapped; null = cancelled); (x, y) is the finger, [done] once let go. */
    fun onPush(grab: PushGrab, mm: Float?, x: Float, y: Float, done: Boolean) {}
    /** A drag began inside the selection's bounding box: [result] gets whether the mesh itself lies under (x, y). */
    fun onDragCheck(x: Float, y: Float, result: (Boolean) -> Unit) = result(true)
    /** A drag began inside a prime tower's box at [entry]: [result] gets false when an object lies in front of it (the touch is the camera's). */
    fun onTowerCheck(x: Float, y: Float, entry: Vec3, result: (Boolean) -> Unit) = result(true)
    /** A right click or a long press that did not drag: the host shows a context menu at (x, y). */
    fun onContextMenu(x: Float, y: Float)
    /** After a long press opened the menu the finger is still down: it moved to (x, y) ([held] false = it lifted there). */
    fun onLongPressTouch(x: Float, y: Float, held: Boolean) {}
    /**
     * Measure mode: the pointer is at (x, y). [phase] 0 = down, 1 = move, 2 = up (places), 3 = cancel, 4 = hover (shows only).
     * [exact]: a mouse or pen, whose point sits right under the pointer (a finger's sits above it).
     */
    fun onMeasureLoupe(x: Float, y: Float, phase: Int, exact: Boolean = false) {}
    /** Measure mode: the press at (x, y) picks a placed point up at once (under a mouse or pen, or a finger on the selected one). */
    fun onMeasureGrab(x: Float, y: Float, exact: Boolean): Boolean = false
    /** Measure loupe held: a second finger came down ([frozen]) or lifted again (the steered camera holds still meanwhile). */
    fun onMeasureFreeze(frozen: Boolean) {}
    /** The copy under view point (x, y) for a double tap: [result] gets its (object, copy) and box, or null (on the UI thread). */
    fun onCopyAt(x: Float, y: Float, result: (Pair<Int, Int>?, Vec3, Vec3) -> Unit) = result(null, Vec3.ZERO, Vec3.ZERO)
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

/** A body drag's edge snap: the moved box ([lo], [hi]) and the boxes its edges stick to (plate mm). */
class DragSnap(val lo: Vec3, val hi: Vec3, val others: List<Pair<Vec3, Vec3>>, val mid: Boolean = false) {
    /** The lines one other box offers along an axis: its two edges, plus its middle when [mid] (Prepare's smart guides). */
    fun targets(a: Float, b: Float) = if (mid) listOf(a, b, (a + b) / 2) else listOf(a, b)
}

@SuppressLint("ViewConstructor")
class PlateView(context: Context) : GLSurfaceView(context) {

    val camera = Camera()
    val renderer = PlateRenderer(camera).also {
        it.deviceLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        it.outlinePx = 2.5f * context.resources.displayMetrics.density
        it.handlePx = 7f * context.resources.displayMetrics.density
        it.density = context.resources.displayMetrics.density
    }
    val section = SectionView(camera)
    var listener: PlateListener? = null
    var mode = InteractionMode.NAVIGATE
        set(value) {
            if (value != InteractionMode.MEASURE && hoverLoupe) { hoverLoupe = false; listener?.onMeasureLoupe(-1f, -1f, 3, true) }
            field = value
        }
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
    /** Build workspace: body drags stick edges to the other shapes' (null = a free drag). */
    var dragSnap: DragSnap? = null
    /** The snap toggle (Shift+Tab or the magnet button); Ctrl held during a drag flips it, as in Blender and Figma. */
    var snapOn = true
    /** World x / y the live body drag sticks to (null = none), to tick once per new snap. */
    private var dragSnapX: Float? = null
    private var dragSnapY: Float? = null
    /** Where the drag grabbed the selection's box (or tower): the drag plane goes through it. */
    private var dragAnchor: Vec3? = null
    /** The lift drag's box (the gizmo box at its start), null = no lift drag; [liftMm] / [liftSnap] its live lift and snap height. */
    private var liftBox: Pair<Vec3, Vec3>? = null
    private var liftMm = 0f
    private var liftSnap: Float? = null
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    /** The two fingers' gap at the last move, 0 = not yet known (a finger came or went). */
    private var lastSpan = 0f
    /** Pan or pinch ([TwoFingerGesture]); lazy because [touchSlop] is declared further down. */
    private var twoFingerDownAt = 0L
    private val twoFinger by lazy { TwoFingerGesture(touchSlop.toFloat()) }
    /** Pinch zoom speed (Layout lab): the exponent on the fingers' gap ratio, 1 = the gap ratio itself. */
    var pinchSpeed = 0.6f
    /** Wheel zoom speed (LayoutLab.wheelSpeed): 15 % per notch raised to this power. */
    var wheelSpeed = 1f
    /** Pen buttons and eraser mapping, and palm rejection (More > Pen). */
    var pen = PenConfig()
    /** When a pen last touched or hovered (uptime ms); 0 after it left the hover range. */
    private var lastPenEvent = 0L
    private val penNear get() = lastPenEvent != 0L && android.os.SystemClock.uptimeMillis() - lastPenEvent < PEN_NEAR_MS
    /** The gesture is a finger touch that came while the pen was near (a resting palm): ignored to its end. */
    private var ignoreGesture = false
    /** The gesture began with a pen button / eraser mapped to Orbit, or to an instant action ([penInstant], fires on a tap). */
    private var penOrbit = false
    /** Paint mode: this touch started off the selected object, so it orbits the camera instead of painting. */
    private var paintOrbit = false
    private var penInstant: PenAction? = null
    private var lastInstantTime = 0L
    /** The gesture began with the right / middle mouse button (pans; a right click opens the menu). */
    private var rightButton = false
    /** A mouse button is down (DOWN came from a mouse; cleared on UP / CANCEL). */
    private var mouseDown = false
    private var middleButton = false
    private var hoverPanning = false
    /** A long press opened the context menu; the rest of the gesture is ignored. */
    private var longPressed = false
    /** The long press is the measure loupe (not the pie menu). */
    private var loupeOn = false
    /** The gesture is a mouse's or pen's: the measure point sits right under it, no lift. */
    private var loupeExact = false
    /** The finger the loupe follows (a second one only freezes the steered camera) and its last spot. */
    private var loupePointer = -1
    private var loupeX = 0f
    private var loupeY = 0f
    /** Measure mode: the mouse or pen hovers and the loupe previews what a click would place. */
    private var hoverLoupe = false
    /** The pointer left the view: the preview goes, unless a press follows at once (a mouse press ends the hover first). */
    private val hoverGone = Runnable { if (hoverLoupe && !loupeOn) listener?.onMeasureLoupe(-1f, -1f, 3, true); hoverLoupe = false }
    /** Farthest the finger got from the touch-down point: a slow orbit stays inside the touch slop but is no long press. */
    private var maxTravel = 0f
    /** Shift / Alt held when the gesture began: a drag draws a selection rectangle. */
    private var rectangle = false
    private var rectangleRemove = false
    /** When a badge was last tapped: the second tap of a double tap must not hit the next plate's badge. */
    private var badgeTapTime = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Only double-tap-and-drag (one finger). Two fingers zoom in ACTION_MOVE: the detector ends its gesture
            // once they are closer than ~27 mm, so a pinch-out stopped as the fingers closed in (user r311).
            if (multiTouch) return true
            pinchZoom(detector.previousSpan, detector.currentSpan, detector.focusX, detector.focusY)
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
            if (other == null) {
                val x = e.x; val y = e.y
                listener?.onCopyAt(x, y) { copy, lo, hi -> focusCopy(copy, lo, hi) } ?: queueEvent { renderer.frame(renderer.activePlate) }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (loupeOn) return
            if (mode == InteractionMode.MEASURE && !multiTouch && !moved && maxTravel <= touchSlop / 3 && !rightButton && !middleButton) {
                cancelDrag()
                longPressed = true
                loupeOn = true
                listener?.onMeasureLoupe(e.x, e.y, 0, loupeExact)
                return
            }
            if (mode != InteractionMode.NAVIGATE || multiTouch || moved || maxTravel > touchSlop / 3 || rightButton || middleButton || rectangle || penOrbit || penInstant != null) return
            cancelDrag()
            longPressed = true
            listener?.onContextMenu(e.x, e.y)
            listener?.onLongPressTouch(e.x, e.y, true)
        }
    })

    /** The copy a double tap focused on and the camera from before it (user 2026-10-19), or null. */
    private var focused: Pair<Int, Int>? = null
    private var beforeFocus: FloatArray? = null

    /**
     * Double tap on a copy: frames it head on at its mid height (level, facing the side nearest the current view);
     * the same copy again goes back to the view from before. Empty space re-frames the plate as before.
     */
    private fun focusCopy(copy: Pair<Int, Int>?, lo: Vec3, hi: Vec3) {
        when {
            copy == null -> { focused = null; beforeFocus = null; queueEvent { renderer.frame(renderer.activePlate) } }
            copy == focused -> { beforeFocus?.let { camera.restore(it) }; focused = null; beforeFocus = null }
            else -> {
                if (focused == null) beforeFocus = camera.state()
                focused = copy
                camera.focus(lo, hi)
            }
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MsaaConfigChooser())
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
        /** A two-finger touch shorter than this, without pan or pinch, is a tap (context menu). */
        private const val TWO_FINGER_TAP_MS = 300L
        /** A mouse press ends the hover just before it lands: the preview waits this long before it goes. */
        private const val HOVER_GONE_MS = 120L
    }

    /** A mouse or pen: it points exactly, so the measure point sits right under it. */
    private fun exactPointer(e: MotionEvent) = e.isFromSource(android.view.InputDevice.SOURCE_MOUSE) ||
        e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || isPenTool(e.getToolType(0))
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

    private inline fun inputLog(msg: () -> String) {
        if (android.util.Log.isLoggable(INPUT_TAG, android.util.Log.DEBUG)) android.util.Log.d(INPUT_TAG, msg())
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
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) inputLog { "2f blocked: palm rejection (pen near)" }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignoreGesture = false
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && mouseDown) lateButton(event.buttonState)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hasFocus()) requestFocus()
                lastX = event.x; lastY = event.y; downX = event.x; downY = event.y
                multiTouch = false; moved = false; dragging = false; longPressed = false; loupeOn = false; maxTravel = 0f; resizeTried = false
                loupePointer = event.getPointerId(0); loupeX = event.x; loupeY = event.y
                pushPending = false; pushGrab = null
                removeCallbacks(hoverGone)
                // The button state is only known on DOWN; on UP it is already released. Some devices send it only
                // with the BUTTON_PRESS or MOVE that follows ([lateButton]).
                val mouse = isMouse(event)
                mouseDown = mouse
                rightButton = mouse && event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                middleButton = mouse && event.buttonState and MotionEvent.BUTTON_TERTIARY != 0
                rectangleRemove = event.metaState and KeyEvent.META_ALT_ON != 0
                rectangle = mode == InteractionMode.NAVIGATE && !rightButton && !middleButton &&
                    (rectangleRemove || event.metaState and KeyEvent.META_SHIFT_ON != 0)
                penOrbit = false; penInstant = null; paintOrbit = false
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
                loupeExact = exactPointer(event)
                if (mode == InteractionMode.MEASURE && !rightButton && !middleButton && !penOrbit && penInstant == null &&
                    listener?.onMeasureGrab(event.x, event.y, loupeExact) == true) {
                    // A placed point picked up at once (a mouse or pen on any, a finger on the selected one): the drag moves it, not the camera.
                    longPressed = true; loupeOn = true; hoverLoupe = false
                    listener?.onMeasureLoupe(event.x, event.y, 0, loupeExact)
                }
                if (rightButton || middleButton || rectangle || penOrbit || penInstant != null) Unit
                else if (mode == InteractionMode.PAINT) {
                    // A touch that starts off the selected object orbits the camera instead of painting (user r325).
                    if (selectedBounds.isNotEmpty() && rayBoxEntry(event.x, event.y, selectedBounds) == null) paintOrbit = true
                    else {
                        listener?.onPaint(event.x, event.y, true)
                        if (selectedBounds.isNotEmpty()) {
                            val id = ++dragCheckId
                            listener?.onDragCheck(event.x, event.y) { hit -> if (!hit && id == dragCheckId) paintOrbit = true }
                        }
                    }
                }
                else if (mode == InteractionMode.NAVIGATE && pushMode) {
                    // Push / pull: the view holds still until the face check answers; no face = the camera's drag.
                    pushPending = true
                    val id = ++dragCheckId
                    val x = event.x
                    val y = event.y
                    listener?.onPushCheck(x, y) { g ->
                        if (id != dragCheckId || !pushPending) return@onPushCheck
                        pushPending = false
                        pushGrab = g
                        pushMm = 0f
                        if (g != null) {
                            pushScreen = pushAlong(g, x, y) == null
                            pushFrom = if (pushScreen) y else pushAlong(g, x, y)!!
                        }
                    }
                }
                else if (mode == InteractionMode.NAVIGATE && startLift(event.x, event.y)) Unit
                else if (mode == InteractionMode.NAVIGATE && rayBoxEntry(event.x, event.y, selectedBounds)?.also { dragAnchor = it } != null) {
                    dragging = true
                    dragStart = dragAt(event.x, event.y)
                    // The box is bigger than the mesh at some angles: a miss on the mesh hands the touch back to the camera.
                    val id = ++dragCheckId
                    listener?.onDragCheck(event.x, event.y) { hit -> if (!hit && id == dragCheckId && dragging) cancelDrag() }
                } else if (mode == InteractionMode.NAVIGATE) {
                    towerBoxes.firstOrNull { rayHitsBox(event.x, event.y, listOf(it.lo to it.hi)) }?.let { t ->
                        towerPlate = t.plate
                        dragging = true
                        dragAnchor = rayBoxEntry(event.x, event.y, listOf(t.lo to t.hi))
                        dragStart = dragAt(event.x, event.y)
                        queueEvent { renderer.dragTower = t.plate }
                        val id = ++dragCheckId
                        dragAnchor?.let { entry ->
                            listener?.onTowerCheck(event.x, event.y, entry) { clear -> if (!clear && id == dragCheckId && dragging) cancelDrag() }
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                cancelDrag()
                if (loupeOn && event.pointerCount == 2) listener?.onMeasureFreeze(true)
                lastFocusX = focusX(event); lastFocusY = focusY(event)
                lastSpan = 0f
                if (event.pointerCount == 2) twoFingerDownAt = event.eventTime
                if (event.pointerCount == 2) twoFinger.down((event.eventTime - event.downTime).toFloat(), span(event), lastFocusX, lastFocusY)
                inputLog { "2f down n=${event.pointerCount} span=${span(event).toInt()} slop=$touchSlop after=${event.eventTime - event.downTime}ms longPressed=$longPressed moved=$moved mode=$mode" }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                inputLog { "2f up n=${event.pointerCount} ${twoFinger.mode} gap=${twoFinger.gap(span(event)).toInt()} shift=${twoFinger.shift(focusX(event), focusY(event)).toInt()} longPressed=$longPressed" }
                // A quick two-finger tap that neither pans nor pinches = right-click (trackpad style).
                if (event.pointerCount == 2 && !longPressed && mode == InteractionMode.NAVIGATE && twoFinger.mode == TwoFingerGesture.Mode.UNDECIDED &&
                    event.eventTime - twoFingerDownAt < TWO_FINGER_TAP_MS && twoFinger.gap(span(event)) < touchSlop && twoFinger.shift(focusX(event), focusY(event)) < touchSlop)
                    listener?.onContextMenu(focusX(event), focusY(event))
                lastSpan = 0f
                if (loupeOn && event.pointerCount == 2) listener?.onMeasureFreeze(false)
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                lastFocusX = focusX(event); lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                maxTravel = max(maxTravel, abs(event.x - downX) + abs(event.y - downY))
                if (maxTravel > touchSlop) moved = true
                // The camera turns: the hover preview would point at the wrong spot (it comes back with the next hover).
                if (moved && hoverLoupe && !loupeOn) { hoverLoupe = false; listener?.onMeasureLoupe(-1f, -1f, 3, true) }
                // A corner drag only starts once a single finger has really moved from the corner, so a two-finger gesture
                // landing on the selection never starts (or leaves behind) a resize.
                if (moved && !resizeTried) {
                    resizeTried = true
                    if (!multiTouch && liftBox == null && event.pointerCount == 1 && mode == InteractionMode.NAVIGATE && !rightButton && !middleButton && !rectangle &&
                        !penOrbit && penInstant == null && (startCut(downX, downY, event.x, event.y) || startResize(downX, downY) || startRotate(downX, downY, event.x, event.y))) {
                        dragCheckId++
                        dragging = false; dragStart = null
                        queueEvent { renderer.dragOffset = floatArrayOf(0f, 0f, 0f) }
                    }
                }
                if (longPressed) {
                    if (loupeOn) {
                        val i = event.findPointerIndex(loupePointer)
                        if (i >= 0) { loupeX = event.getX(i); loupeY = event.getY(i); listener?.onMeasureLoupe(loupeX, loupeY, 1, loupeExact) }
                    } else if (event.pointerCount >= 2) inputLog { "2f blocked: long press" }
                    else listener?.onLongPressTouch(event.x, event.y, true)
                    return true
                }
                if (event.pointerCount >= 2) {
                    val fx = focusX(event); val fy = focusY(event)
                    val step = twoFinger.move((event.eventTime - event.downTime).toFloat(), span(event), fx, fy, lastFocusX, lastFocusY)
                    step.log?.let { inputLog { "2f $it after=${event.eventTime - event.downTime}ms" } }
                    if (step.pans) camera.panGrab(step.panFromX, step.panFromY, fx, fy)
                    val sp = span(event)
                    if (twoFinger.mode == TwoFingerGesture.Mode.PINCH && lastSpan > 0f) pinchZoom(lastSpan, sp, fx, fy)
                    lastSpan = sp
                    lastFocusX = fx; lastFocusY = fy
                } else if (!multiTouch) {
                    when {
                        rectangle -> if (moved) listener?.onRectangle(downX, downY, event.x, event.y, rectangleRemove, false)
                        rightButton || middleButton -> camera.panGrab(lastX, lastY, event.x, event.y)
                        penOrbit -> camera.orbit((event.x - lastX) * 0.3f, (event.y - lastY) * 0.3f)
                        penInstant != null -> Unit
                        mode == InteractionMode.PAINT && paintOrbit -> camera.orbit((event.x - lastX) * 0.3f, (event.y - lastY) * 0.3f)
                        mode == InteractionMode.PAINT -> {
                            // Fill gaps of fast strokes so the painted trail stays continuous.
                            val dx = event.x - lastX
                            val dy = event.y - lastY
                            val steps = (kotlin.math.hypot(dx, dy) / PAINT_STEP_PX).toInt().coerceIn(1, 32)
                            for (i in 1..steps) listener?.onPaint(lastX + dx * i / steps, lastY + dy * i / steps, false)
                        }
                        rotateDrag != null -> {
                            val d = rotateDrag!!
                            val before = d.degrees
                            val deg = d.update(event.x, event.y)
                            // A click per snap step, as the view cube's press.
                            if (d.step > 0f && deg != before) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            val m = RotateGizmo.matrix(d.axis, deg)
                            val c = d.c
                            val step = d.step
                            if (cutBase != null) {
                                queueEvent { renderer.rotateStep = step }
                                listener?.onCutTilt(d.axis, deg, cutBase!!, event.x, event.y, false)
                            } else {
                                queueEvent { renderer.rotatePivot = floatArrayOf(c.x, c.y, c.z); renderer.rotateMatrix = m; renderer.rotateStep = step }
                                listener?.onRotate(d.axis, deg, c, event.x, event.y, false)
                            }
                        }
                        cutMoving -> {
                            val dz = cutDz(lastX, lastY, event.x, event.y)
                            if (dz != 0f) listener?.onCutMove(dz)
                        }
                        resizeHandle != null -> {
                            val h = resizeHandle!!
                            val (lo, hi) = resizeBox!!
                            val s = ScaleGizmo.scale(h, ScaleGizmo.ratio(h, lo, hi, camera, downX, downY, event.x, event.y))
                            resizeScale = s
                            if (moved) {
                                val p = ScaleGizmo.pivot(lo, hi)
                                queueEvent { renderer.resizeAnchor = floatArrayOf(p.x, p.y, p.z); renderer.resizeScale = floatArrayOf(s.x, s.y, s.z) }
                                listener?.onResize(s, p, event.x, event.y, false)
                            }
                        }
                        pushGrab != null -> if (moved) {
                            val g = pushGrab!!
                            val t = if (pushScreen) (pushFrom - event.y) * camera.pxSize(g.point) / g.dir.length()
                                else pushAlong(g, event.x, event.y)?.let { it - pushFrom }
                            if (t != null) {
                                // Half-millimetre steps; at least 1 mm stays.
                                val mm = maxOf(Math.round(t / PUSH_STEP) * PUSH_STEP, 1f - g.length)
                                if (mm != pushMm) {
                                    pushMm = mm
                                    if (g.live) {
                                        val m = pushMatrix(g, mm)
                                        queueEvent { renderer.resizeAnchor = floatArrayOf(g.anchor.x, g.anchor.y, g.anchor.z); renderer.pushMatrix = m }
                                    }
                                }
                                listener?.onPush(g, mm, event.x, event.y, false)
                            }
                        }
                        pushPending -> Unit
                        liftBox != null -> if (moved) {
                            val before = liftSnap
                            val (dz, s) = liftAt(event.x, event.y, event.metaState and KeyEvent.META_SHIFT_ON != 0, event.metaState and KeyEvent.META_CTRL_ON != 0)
                            if (s != null && s != before) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            liftMm = dz; liftSnap = s
                            queueEvent { renderer.dragOffset = floatArrayOf(0f, 0f, dz) }
                        }
                        dragging -> dragOffsetAt(event.x, event.y, event.metaState and KeyEvent.META_SHIFT_ON != 0, event.metaState and KeyEvent.META_CTRL_ON != 0)?.let { o ->
                            val d = floatArrayOf(o.x, o.y, 0f)
                            queueEvent { renderer.dragOffset = d }
                        }
                        // In resize mode the view holds still until the touch slop decides between resize and orbit
                        // (a corner resize also turned the camera ~1.5 degrees, WSA r305).
                        (resizeMode || rotateMode) && !resizeTried -> Unit
                        else -> camera.orbit((event.x - lastX) * 0.3f, (event.y - lastY) * 0.3f)
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                mouseDown = false
                if (mode == InteractionMode.PAINT) listener?.onPaintEnd()
                val additive = event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) != 0
                val part = event.metaState and KeyEvent.META_ALT_ON != 0
                if (rectangle && moved) {
                    listener?.onRectangle(downX, downY, event.x, event.y, rectangleRemove, true)
                } else if (penInstant != null || penOrbit) {
                    val action = penInstant
                    if (action != null && !moved && android.os.SystemClock.uptimeMillis() - lastInstantTime > INSTANT_REPEAT_MS) fireInstant(action)
                } else if (longPressed || middleButton) {
                    // Handled: the menu is open, or the middle button only pans.
                    if (loupeOn) listener?.onMeasureLoupe(loupeX, loupeY, 2, loupeExact)
                    else if (longPressed) listener?.onLongPressTouch(event.x, event.y, false)
                } else if (rightButton) {
                    if (!moved && mode == InteractionMode.NAVIGATE) listener?.onContextMenu(event.x, event.y)
                } else if (cutMoving) {
                    cutMoving = false
                    queueEvent { renderer.cutXray = false }
                } else if (rotateDrag != null) {
                    endRotate(if (moved) rotateDrag!!.degrees else null, event.x, event.y)
                } else if (resizeHandle != null) {
                    endResize(if (moved) resizeScale else null, event.x, event.y)
                } else if (pushGrab != null || pushPending) {
                    val g = pushGrab
                    pushPending = false; pushGrab = null
                    dragCheckId++
                    // The live stretch stays until the pushed shape's mesh arrives (the listener clears it).
                    if (g != null) listener?.onPush(g, if (moved) pushMm else null, event.x, event.y, true)
                    if (!moved) tap(event.x, event.y, additive, part)
                } else if (liftBox != null) {
                    val dz = liftMm
                    liftBox = null
                    queueEvent { renderer.liftGrab = false }
                    // As for a move, the listener clears the live offset once the lifted mesh is drawn.
                    if (moved && dz != 0f) listener?.onLifted(dz) else queueEvent { renderer.dragOffset = floatArrayOf(0f, 0f, 0f) }
                } else if (dragging) {
                    val o = dragOffsetAt(event.x, event.y, event.metaState and KeyEvent.META_SHIFT_ON != 0, event.metaState and KeyEvent.META_CTRL_ON != 0)
                    clearDragGuides()
                    if (moved && o != null) {
                        // The listener clears the drag offset once the moved scene is drawn; clearing it here
                        // made the object jump back to its old spot until the engine answered.
                        dragging = false
                        dragStart = null
                        val plate = towerPlate
                        towerPlate = -1
                        if (plate >= 0) listener?.onTowerMoved(plate, o.x, o.y)
                        else listener?.onMoved(o.x, o.y)
                    } else {
                        cancelDrag()
                        if (!moved) tap(event.x, event.y, additive, part)
                    }
                } else if (!moved && !multiTouch && mode == InteractionMode.MEASURE && loupeExact) {
                    // A mouse or pen click places the point the hover showed, right under the pointer.
                    hoverLoupe = false
                    listener?.onMeasureLoupe(event.x, event.y, 2, true)
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
                mouseDown = false
                if (mode == InteractionMode.PAINT) listener?.onPaintEnd()
                if (loupeOn) listener?.onMeasureLoupe(-1f, -1f, 3, loupeExact)
                else if (longPressed) listener?.onLongPressTouch(-1f, -1f, false)
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
        if (mode == InteractionMode.PAINT) when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                if (event.buttonState == 0) listener?.onPaintHover(event.x, event.y, true)
            MotionEvent.ACTION_HOVER_EXIT -> listener?.onPaintHover(event.x, event.y, false)
        }
        // Measure: a mouse or pen in the air previews what a click would place (user: the point jumped away from the mouse).
        if (mode == InteractionMode.MEASURE) when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> if (event.buttonState == 0 && !loupeOn) {
                removeCallbacks(hoverGone)
                hoverLoupe = true
                listener?.onMeasureLoupe(event.x, event.y, 4, true)
            }
            MotionEvent.ACTION_HOVER_EXIT -> postDelayed(hoverGone, HOVER_GONE_MS)
        }
        // A pen near the screen (palm rejection) and a button pressed in the air fire an instant action (undo, redo, frame).
        if (isPenTool(event.getToolType(0))) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> lastPenEvent = android.os.SystemClock.uptimeMillis()
                MotionEvent.ACTION_HOVER_EXIT -> lastPenEvent = 0L
                MotionEvent.ACTION_BUTTON_PRESS -> penActionOf(event.getToolType(0), event.actionButton).takeIf { it.instant }?.let(::fireInstant)
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && mouseDown) lateButton(event.actionButton)
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
            // By the scroll amount: a thumbstick (Quest) or touchpad sends many fractional steps, a wheel notch 1.
            else camera.zoomAt(1.15f.pow(v.coerceIn(-3f, 3f) * wheelSpeed), event.x, event.y)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private var dragCheckId = 0

    private var resizeHandle: ScaleGizmo.Handle? = null
    private var resizeBox: Pair<Vec3, Vec3>? = null
    private var resizeScale = Vec3(1f, 1f, 1f)
    private var resizeTried = false

    /** Build workspace push / pull ([PushGrab]): a drag on a flat face moves the face; elsewhere it turns the camera. */
    @Volatile var pushMode = false
    private var pushPending = false
    private var pushGrab: PushGrab? = null
    /** Measured on screen (the face's normal points at the camera): [pushFrom] is the finger's y at the start. */
    private var pushScreen = false
    private var pushFrom = 0f
    private var pushMm = 0f

    /**
     * Where along [g]'s normal line (design mm from its point) the finger's ray at (x, y) passes closest; null when the
     * line runs nearly along the view (within ~10 degrees), where the ray can't tell.
     */
    private fun pushAlong(g: PushGrab, x: Float, y: Float): Float? {
        val (o, d) = camera.ray(x, y)
        val v = g.dir
        val w = g.point - o
        val a = v.dot(v); val b = v.dot(d); val c = d.dot(d)
        val den = a * c - b * b
        if (den < 0.03f * a * c) return null
        return (b * d.dot(w) - c * v.dot(w)) / den
    }

    /** The live stretch of a push by [mm]: I + (k - 1) * sum of a a^T over [g]'s stretch axes, row-major. */
    private fun pushMatrix(g: PushGrab, mm: Float): FloatArray {
        val k = (g.length + mm) / g.length - 1f
        val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (a in g.axes) {
            val u = floatArrayOf(a.x, a.y, a.z)
            for (i in 0..2) for (j in 0..2) m[i * 3 + j] += k * u[i] * u[j]
        }
        return m
    }

    /** Resizing is on (Resize in the context menu, Ctrl+T or S): the scale gizmo's handles show on the lone selection. */
    @Volatile var resizeMode = false
        set(value) { field = value; queueEvent { renderer.gizmo = value } }

    /** The selected copy is turned only in 90 degree steps about Z, so X, Y and Z can be scaled alone (else corners only). */
    @Volatile var resizeAxes = true
        set(value) { field = value; queueEvent { renderer.gizmoAxes = value } }

    /** The gizmos sit on the selection's mesh box (the Build workspace's shapes) instead of the lone [selectedBounds] box. */
    @Volatile var gizmoOnMesh = false

    /** The box the gizmos sit on, or null when they don't show. */
    fun gizmoBox(): Pair<Vec3, Vec3>? = if (gizmoOnMesh) renderer.selectionBounds() else selectedBounds.singleOrNull()

    /** The lift dot shows above the gizmo box (Build workspace): dragging it moves the selection up or down. */
    @Volatile var liftMode = false
        set(value) { field = value; queueEvent { renderer.liftHandle = value } }

    /** In [liftMode], a touch within 24 dp of the lift dot ([ZRail.handle]) starts a lift drag. */
    private fun startLift(x: Float, y: Float): Boolean {
        if (!liftMode) return false
        val box = gizmoBox() ?: return false
        val (px, py) = camera.project(ZRail.handle(box.first, box.second)) ?: return false
        if (kotlin.math.hypot(px - x, py - y) > 24 * resources.displayMetrics.density) return false
        liftBox = box; liftMm = 0f; liftSnap = null
        queueEvent { renderer.liftGrab = true }
        return true
    }

    /**
     * The lift (mm) for the finger at (x, y) and the height it sticks to: the finger's travel along the Z axis as drawn, snapped
     * to the floor and the tops and bottoms of the other shapes overlapping the box in XY (the snap toggle and Ctrl as for a move;
     * whole mm, tenths with Shift). The live values while the axis points at the camera.
     */
    private fun liftAt(x: Float, y: Float, fine: Boolean, flip: Boolean): Pair<Float, Float?> {
        val (lo, hi) = liftBox ?: return 0f to null
        val keep = liftMm to liftSnap
        val at = ZRail.handle(lo, hi)
        val a = camera.project(at) ?: return keep
        val e = camera.project(at + Vec3(0f, 0f, 10f)) ?: return keep
        val sx = e.first - a.first
        val sy = e.second - a.second
        val len2 = sx * sx + sy * sy
        if (len2 < 16f) return keep
        val raw = ((x - downX) * sx + (y - downY) * sy) / len2 * 10f
        val pxPerMm = kotlin.math.sqrt(len2) / 10f
        val s = dragSnap?.takeIf { snapOn != flip }
        val targets = ArrayList<Float>()
        if (s != null) {
            targets += 0f
            for ((olo, ohi) in s.others) if (olo.x < hi.x && ohi.x > lo.x && olo.y < hi.y && ohi.y > lo.y) { targets += olo.z; targets += ohi.z }
        }
        return ZRail.lift(raw, lo.z, hi.z, targets, 10 * resources.displayMetrics.density / pxPerMm, if (fine) 0.1f else 1f)
    }

    /** In [resizeMode], a touch on a visible handle of the lone selected box starts a resize ([ScaleGizmo]). */
    private fun startResize(x: Float, y: Float): Boolean {
        if (!resizeMode) return false
        val box = gizmoBox() ?: return false
        // 48 dp grabbed too much of a small box (user, r304). On the object only a touch right on a handle grabs it, so a
        // drag of the object near a handle moves it (user 2026-10-28: a repeat's end copy dragged changed its spacing).
        val reach = (if (onBox(box, x, y)) 12f else 32f) * resources.displayMetrics.density
        val h = ScaleGizmo.visible(box.first, box.second, camera, resizeAxes).mapNotNull { h ->
            camera.project(h.pos)?.let { (px, py) -> h to kotlin.math.hypot(px - x, py - y) }
        }.filter { it.second < reach }.minByOrNull { it.second }?.first ?: return false
        resizeHandle = h; resizeBox = box; resizeScale = Vec3(1f, 1f, 1f)
        queueEvent { renderer.gizmoGrab = h }
        return true
    }

    /** Whether screen (x, y) falls on the box ([lo], [hi]) as drawn: inside one of its faces' outlines. */
    private fun onBox(box: Pair<Vec3, Vec3>, x: Float, y: Float): Boolean {
        val (lo, hi) = box
        val c = (0 until 8).map { k -> camera.project(Vec3(if (k and 1 == 0) lo.x else hi.x, if (k and 2 == 0) lo.y else hi.y, if (k and 4 == 0) lo.z else hi.z)) ?: return false }
        // The six faces by corner index (bit 0 x, bit 1 y, bit 2 z), each as an outline in order round the face.
        val faces = listOf(intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6))
        return faces.any { f -> RotateGizmo.inside(f.map { c[it] }, x, y) }
    }

    /** Ends a resize: applies [scale], or cancels with null. */
    private fun endResize(scale: Vec3?, x: Float = 0f, y: Float = 0f) {
        resizeHandle ?: return
        val box = resizeBox!!
        resizeHandle = null; resizeBox = null
        queueEvent { renderer.gizmoGrab = null }
        listener?.onResize(scale, ScaleGizmo.pivot(box.first, box.second), x, y, true)
    }

    private fun cancelResize() {
        endResize(null); endRotate(null)
        if (cutMoving) { cutMoving = false; queueEvent { renderer.cutXray = false } }
    }

    private var rotateDrag: RotateGizmo.Drag? = null

    /** Rotating is on (Rotate in the context menu or R): the rotate gizmo's rings show on the lone selection. */
    @Volatile var rotateMode = false
        set(value) { field = value; queueEvent { renderer.rotateGizmo = value } }

    /**
     * In [rotateMode], a touch on a ring of the lone selected box starts a turn ([RotateGizmo]) from where the finger landed
     * ([x0], [y0]); ([x], [y]) is where it is now, past the touch slop, so the turn so far isn't lost.
     */
    private fun startRotate(x0: Float, y0: Float, x: Float, y: Float): Boolean {
        if (!rotateMode) return false
        val box = gizmoBox() ?: return false
        // On the object only a touch right on a ring grabs it; the parts of the rings the box hides can't be grabbed at all.
        val reach = (if (onBox(box, x0, y0)) 10f else 24f) * resources.displayMetrics.density
        val axis = RotateGizmo.pick(box.first, box.second, camera, x0, y0, reach, occluded = true)
        if (axis < 0) return false
        val c = RotateGizmo.center(box.first, box.second)
        val d = RotateGizmo.Drag(axis, c, RotateGizmo.radius(box.first, box.second, camera), camera, x0, y0,
            RotateGizmo.FREE_BAND_DP * resources.displayMetrics.density)
        rotateDrag = d
        val from = d.start
        queueEvent { renderer.rotateAxis = axis; renderer.rotateFrom = from }
        d.update(x, y)
        return true
    }

    /** Ends a turn: applies [degrees], or cancels with null. */
    private fun endRotate(degrees: Float?, x: Float = 0f, y: Float = 0f) {
        val d = rotateDrag ?: return
        rotateDrag = null
        queueEvent { renderer.rotateAxis = -1; renderer.rotateFrom = null; renderer.rotateStep = 0f }
        val base = cutBase
        cutBase = null
        if (base != null) queueEvent { renderer.cutXray = false }
        if (base != null) listener?.onCutTilt(d.axis, degrees, base, x, y, true)
        else listener?.onRotate(d.axis, degrees, d.c, x, y, true)
    }

    /** The cut plane while the cut tool is open (centre, disc radius, orientation as Euler degrees); null = no cut tool. */
    class CutGeom(val c: Vec3, val r: Float, val rot: Vec3)
    @Volatile var cutGeom: CutGeom? = null
    private var cutBase: Vec3? = null
    private var cutMoving = false

    private fun cutBox(g: CutGeom): Pair<Vec3, Vec3> {
        val k = g.r / Math.sqrt(3.0).toFloat()
        return Vec3(g.c.x - k, g.c.y - k, g.c.z - k) to Vec3(g.c.x + k, g.c.y + k, g.c.z + k)
    }

    /**
     * With the cut tool open, a drag that starts on a tilt ring turns the plane about that axis; one that starts on the plane
     * moves it up or down (a thumb can do both with the tool window open); anything else is the camera's.
     */
    private fun startCut(x0: Float, y0: Float, x: Float, y: Float): Boolean {
        val g = cutGeom ?: return false
        val (lo, hi) = cutBox(g)
        val reach = 28f * resources.displayMetrics.density
        val axis = RotateGizmo.pick(lo, hi, camera, x0, y0, reach, 2)
        if (axis >= 0) {
            val d = RotateGizmo.Drag(axis, g.c, RotateGizmo.radius(lo, hi, camera), camera, x0, y0, RotateGizmo.FREE_BAND_DP * resources.displayMetrics.density)
            rotateDrag = d
            cutBase = g.rot
            val from = d.start
            queueEvent { renderer.rotateAxis = axis; renderer.rotateFrom = from; renderer.cutXray = true }
            d.update(x, y)
            return true
        }
        val n = CutPlane.normal(g.rot)
        val (u, v) = CutPlane.axes(g.rot)
        val rim = (0 until 48).mapNotNull { i ->
            val a = i * 2.0 * Math.PI / 48
            camera.project(g.c + u * (g.r * Math.cos(a).toFloat()) + v * (g.r * Math.sin(a).toFloat()))
        }
        if (rim.size < 24 || !RotateGizmo.inside(rim, x0, y0)) return false
        cutMoving = true
        lastX = x0; lastY = y0
        queueEvent { renderer.cutXray = true }
        return true
    }

    /** Plane move for a finger move: its part along the slide axis' direction on screen (world Z, or level for an upright plane), over that axis' length there. */
    private fun cutDz(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val g = cutGeom ?: return 0f
        val a = camera.project(g.c) ?: return 0f
        val b = camera.project(g.c + CutPlane.slideAxis(g.rot) * 10f) ?: return 0f
        val vx = (b.first - a.first) / 10f
        val vy = (b.second - a.second) / 10f
        val l2 = vx * vx + vy * vy
        if (l2 < 0.05f) return 0f
        return ((x1 - x0) * vx + (y1 - y0) * vy) / l2
    }

    private fun isMouse(event: MotionEvent) = event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) ||
        event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE

    /**
     * A mouse press whose DOWN came without the button (WSA sends it on DOWN; user r459: a right click only opened the
     * menu when held, as a finger's long press): the right / middle button seen afterwards takes the gesture over.
     */
    private fun lateButton(buttons: Int) {
        if (rightButton || middleButton) return
        val right = buttons and MotionEvent.BUTTON_SECONDARY != 0
        if (!right && buttons and MotionEvent.BUTTON_TERTIARY == 0) return
        inputLog { "late button $buttons" }
        cancelDrag()
        rectangle = false
        if (right) rightButton = true else middleButton = true
    }

    private fun cancelDrag() {
        cancelResize()
        pushGrab?.let { g -> pushGrab = null; listener?.onPush(g, null, 0f, 0f, true) }
        pushPending = false
        dragCheckId++
        if (dragging || liftBox != null) queueEvent { renderer.dragOffset = floatArrayOf(0f, 0f, 0f); renderer.liftGrab = false }
        liftBox = null
        clearDragGuides()
        dragging = false
        dragStart = null
        towerPlate = -1
    }

    /**
     * The body drag's offset (mm) for the finger at (x, y): with [dragSnap], the moved box's edges stick (flush or aligned)
     * to the other boxes' edges within about 10 dp, else whole mm (tenths with Shift), and thin guides show what lines up.
     * Null when the ray misses the drag plane.
     */
    private fun dragOffsetAt(x: Float, y: Float, fine: Boolean, flip: Boolean = false): Vec3? {
        val start = dragStart ?: return null
        val now = dragAt(x, y) ?: return null
        val s = dragSnap?.takeIf { towerPlate < 0 && snapOn != flip } ?: return Vec3(now.x - start.x, now.y - start.y, 0f).also { clearDragGuides() }
        val a = camera.project(start)
        fun px(d: Vec3) = camera.project(start + d)?.let { e -> if (a == null) 0f else kotlin.math.hypot(e.first - a.first, e.second - a.second) }
        val pxPerMm = max(px(Vec3(10f, 0f, 0f)) ?: 0f, px(Vec3(0f, 10f, 0f)) ?: 0f) / 10f
        val reach = if (pxPerMm < 0.01f) 0f else 10 * resources.displayMetrics.density / pxPerMm
        val step = if (fine) 0.1f else 1f
        val (dx, sx) = ZRail.lift(now.x - start.x, s.lo.x, s.hi.x, s.others.flatMap { s.targets(it.first.x, it.second.x) }, reach, step, s.mid)
        val (dy, sy) = ZRail.lift(now.y - start.y, s.lo.y, s.hi.y, s.others.flatMap { s.targets(it.first.y, it.second.y) }, reach, step, s.mid)
        if ((sx != null || sy != null) && (sx != dragSnapX || sy != dragSnapY)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        dragSnapX = sx; dragSnapY = sy
        val g = snapGuides(s, dx, dy, sx, sy)
        queueEvent { renderer.dragGuides = g }
        return Vec3(dx, dy, 0f)
    }

    /** Amber lines along the edges the drag sticks to, from the moved box to the boxes it lines up with (null = none). */
    private fun snapGuides(s: DragSnap, dx: Float, dy: Float, sx: Float?, sy: Float?): FloatArray? {
        if (sx == null && sy == null) return null
        val z = s.lo.z
        val guide = ArrayList<Float>()
        sx?.let { t ->
            val hit = s.others.filter { s.targets(it.first.x, it.second.x).any { v -> abs(v - t) < 1e-3f } }
            val y0 = minOf(s.lo.y + dy, hit.minOfOrNull { it.first.y } ?: Float.MAX_VALUE)
            val y1 = maxOf(s.hi.y + dy, hit.maxOfOrNull { it.second.y } ?: -Float.MAX_VALUE)
            guide += listOf(t, y0, z, t, y1, z)
        }
        sy?.let { t ->
            val hit = s.others.filter { s.targets(it.first.y, it.second.y).any { v -> abs(v - t) < 1e-3f } }
            val x0 = minOf(s.lo.x + dx, hit.minOfOrNull { it.first.x } ?: Float.MAX_VALUE)
            val x1 = maxOf(s.hi.x + dx, hit.maxOfOrNull { it.second.x } ?: -Float.MAX_VALUE)
            guide += listOf(x0, t, z, x1, t, z)
        }
        return guide.toFloatArray()
    }

    private fun clearDragGuides() {
        if (dragSnapX == null && dragSnapY == null) return
        dragSnapX = null; dragSnapY = null
        queueEvent { renderer.dragGuides = null }
    }

    /** The drag's point under (x, y) on its plane (Camera.dragPoint), or on the bed without an anchor. */
    private fun dragAt(x: Float, y: Float) = dragAnchor?.let { camera.dragPoint(x, y, it) } ?: camera.rayOnPlane(x, y)

    private fun rayHitsBox(x: Float, y: Float, boxes: List<Pair<Vec3, Vec3>>) = rayBoxEntry(x, y, boxes) != null

    /** Where the ray through (x, y) first enters one of [boxes] (the nearest), or null on a miss. */
    private fun rayBoxEntry(x: Float, y: Float, boxes: List<Pair<Vec3, Vec3>>): Vec3? {
        if (boxes.isEmpty()) return null
        val (o, d) = camera.ray(x, y)
        var nearest = Float.MAX_VALUE
        for ((lo, hi) in boxes) {
            var tMin = 0f
            var tMax = Float.MAX_VALUE
            val os = floatArrayOf(o.x, o.y, o.z); val ds = floatArrayOf(d.x, d.y, d.z)
            val los = floatArrayOf(lo.x, lo.y, lo.z); val his = floatArrayOf(hi.x, hi.y, hi.z)
            var hit = true
            for (i in 0..2) {
                if (abs(ds[i]) < 1e-8f) {
                    if (os[i] < los[i] || os[i] > his[i]) hit = false
                } else {
                    var t1 = (los[i] - os[i]) / ds[i]
                    var t2 = (his[i] - os[i]) / ds[i]
                    if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                    tMin = max(tMin, t1)
                    tMax = min(tMax, t2)
                    if (tMin > tMax) hit = false
                }
            }
            if (hit) nearest = min(nearest, tMin)
        }
        return if (nearest == Float.MAX_VALUE) null else o + d * nearest
    }

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private fun focusX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun focusY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
    /** Zooms by the fingers' gap ratio at ([x], [y]). */
    private fun pinchZoom(previous: Float, current: Float, x: Float, y: Float) {
        // The pad keeps fingers close together from zooming in leaps (a 1 mm wobble is a big gap ratio there);
        // the camera's own limits are unchanged, so the deep zoom-in only takes more pinching.
        val pad = PINCH_PAD_DP * resources.displayMetrics.density
        camera.zoomAt(((current + pad) / (previous + pad)).pow(pinchSpeed), x, y)
    }

    private fun span(e: MotionEvent) = kotlin.math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))


}

/** `adb shell setprop log.tag.OrcaInput DEBUG` logs the view's mouse / touch events. */
private const val INPUT_TAG = "OrcaInput"

/** Spacing of interpolated paint samples, in pixels. */
private const val PAINT_STEP_PX = 12f
/** Push / pull moves a face in these steps (mm). */
private const val PUSH_STEP = 0.5f
