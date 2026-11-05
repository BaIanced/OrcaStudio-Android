package app.orcaandroid.vr

/**
 * One controller's trigger as a state machine: the press edge picks the panel or the plate (the panel wins), and the
 * press stays with that target until release. Plain Kotlin, no allocation per frame; events go to a [Sink].
 */
internal class PointerRouter(private val sink: Sink) {
    interface Sink {
        /** [action] is [DOWN], [MOVE], [UP] or [CANCEL]; x y are panel pixels. */
        fun panel(action: Int, x: Float, y: Float)

        /** One plate tap on the press edge, at plate [index] and plate mm x y. */
        fun plateTap(index: Int, x: Float, y: Float)
    }

    private var target = NONE
    private var wasPressed = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    /**
     * One frame. [tracking] is false when the pose or action is inactive (focus lost). [onPanel] and [px] [py] are the
     * [VrMath.rayQuad] result; keep the last [px] [py] when the ray leaves the plane. [plate] is the [VrMath.plateAtPoint]
     * index or -1, at [qx] [qy]. A tap is dropped when [toolSet] or [buildOpen].
     */
    fun update(
        tracking: Boolean, pressed: Boolean,
        onPanel: Boolean, px: Float, py: Float,
        plate: Int, qx: Float, qy: Float,
        toolSet: Boolean, buildOpen: Boolean,
    ) {
        if (!tracking) {
            if (target == PANEL) sink.panel(CANCEL, lastX, lastY)
            target = NONE
            wasPressed = true // A trigger still held when focus returns is not a new press.
            return
        }
        val edge = pressed && !wasPressed
        wasPressed = pressed
        if (edge) {
            if (onPanel) {
                target = PANEL
                downX = px; downY = py; lastX = px; lastY = py
                dragging = false
                sink.panel(DOWN, px, py)
            } else {
                target = NONE
                if (plate >= 0 && !toolSet && !buildOpen) sink.plateTap(plate, qx, qy)
            }
            return
        }
        if (target != PANEL) return
        if (!pressed) {
            sink.panel(UP, lastX, lastY)
            target = NONE
            return
        }
        if (!dragging) {
            val dx = px - downX
            val dy = py - downY
            if (dx * dx + dy * dy < DEAD_ZONE_PX * DEAD_ZONE_PX) return
            dragging = true
        }
        lastX = px; lastY = py
        sink.panel(MOVE, px, py)
    }

    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        const val CANCEL = 3

        /** Panel pixels a press may wander before it becomes a drag, so a trigger pull is still a click. */
        const val DEAD_ZONE_PX = 12f
        private const val NONE = 0
        private const val PANEL = 1
    }
}
