package app.orcaandroid.ui

import android.content.Context
import android.graphics.Rect
import android.widget.FrameLayout

/**
 * Holds the Compose root and keeps focus out of it while it measures or lays out.
 *
 * A layout switch inside a measure pass (BoxWithConstraints: portrait column -> wide row on rotation)
 * removes the focused 3D view; Android then hands focus to the root, Compose's focus code runs a
 * measure-and-layout of its own and throws "performMeasureAndLayout called during measure layout"
 * (phone r278). Refused here, focus stays nowhere until the next touch or hover re-requests it.
 */
class FocusGuardLayout(context: Context) : FrameLayout(context) {
    private var busy = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        busy = true
        try { super.onMeasure(widthMeasureSpec, heightMeasureSpec) } finally { busy = false }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        busy = true
        try { super.onLayout(changed, left, top, right, bottom) } finally { busy = false }
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean =
        !busy && super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
}
