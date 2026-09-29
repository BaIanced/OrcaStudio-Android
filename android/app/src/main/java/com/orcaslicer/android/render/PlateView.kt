package com.orcaslicer.android.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * GLSurfaceView hosting [PlateRenderer] with touch navigation:
 * one finger orbits, two fingers pinch-zoom and pan, double tap resets the view.
 */
@SuppressLint("ViewConstructor")
class PlateView(context: Context) : GLSurfaceView(context) {

    val renderer = PlateRenderer()

    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var multiTouch = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val f = detector.scaleFactor
            queueEvent { renderer.zoom(f) }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            queueEvent { renderer.resetCamera() }
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 4)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Runs [block] on the GL thread. */
    fun onGl(block: PlateRenderer.() -> Unit) = queueEvent { renderer.block() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                lastFocusX = focusX(event); lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor on the remaining finger(s) to avoid a jump.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                lastFocusX = focusX(event); lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val fx = focusX(event); val fy = focusY(event)
                    val dx = (fx - lastFocusX) / width
                    val dy = (fy - lastFocusY) / height
                    queueEvent { renderer.pan(dx, dy) }
                    lastFocusX = fx; lastFocusY = fy
                } else if (!multiTouch) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    queueEvent { renderer.orbit(dx * 0.3f, dy * 0.3f) }
                    lastX = event.x; lastY = event.y
                }
            }
        }
        return true
    }

    private fun focusX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun focusY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
}
