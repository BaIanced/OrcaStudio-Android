package app.orcaandroid.vr

import android.app.Activity
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

/**
 * M0 of the VR port: proves an OpenXR GLES session by drawing one static grid in stereo (docs/handoff/NOTES-vr.md).
 * Kotlin owns the EGL context and the render thread, so a later milestone can draw the real plate here.
 */
class VrActivity : Activity() {
    @Volatile private var stop = false
    private var thread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread = Thread(::renderThread, "OrcaXrRender").also { it.start() }
    }

    override fun onDestroy() {
        stop = true
        // The thread can be inside xrWaitFrame; don't hold the main thread for long.
        thread?.join(JOIN_TIMEOUT_MS)
        super.onDestroy()
    }

    private fun renderThread() {
        val egl = EglHolder()
        try {
            val created = XrShim.nativeCreate(this, egl.display.nativeHandle, egl.config.nativeHandle, egl.context.nativeHandle)
            if (created) {
                frameLoop()
                XrShim.nativeDestroy()
            } else {
                Log.e(TAG, "OpenXR setup failed, see the lines above")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "OpenXR shim did not load", e)
        } finally {
            egl.release()
        }
        runOnUiThread { finishAndRemoveTask() }
    }

    private fun frameLoop() {
        val grid = VrGrid()
        val framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        val views = FloatArray(XrShim.EYES * XrShim.FLOATS_PER_VIEW)
        val size = IntArray(2)
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val viewProj = FloatArray(16)
        var frames = 0
        var windowStart = SystemClock.elapsedRealtime()

        while (!stop) {
            val status = XrShim.nativePollEvents()
            if (status and XrShim.EXIT != 0) break
            if (status and XrShim.RUNNING == 0) {
                SystemClock.sleep(IDLE_SLEEP_MS)
                continue
            }
            val render = XrShim.nativeBeginFrame(views)
            if (render) {
                for (eye in 0 until XrShim.EYES) {
                    val texture = XrShim.nativeAcquireImage(eye)
                    if (texture == 0) continue
                    XrShim.nativeSwapchainSize(eye, size)
                    val o = eye * XrShim.FLOATS_PER_VIEW
                    VrMath.viewFromPose(view, views, o)
                    VrMath.projectionFromFov(projection, views[o + 7], views[o + 8], views[o + 9], views[o + 10], NEAR, FAR)
                    VrMath.multiply(viewProj, projection, view)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
                    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
                    GLES30.glViewport(0, 0, size[0], size[1])
                    GLES30.glClearColor(0.04f, 0.05f, 0.07f, 1f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    grid.draw(viewProj)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    GLES30.glFlush()
                    XrShim.nativeReleaseImage(eye)
                }
            }
            XrShim.nativeEndFrame(render)

            frames++
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= FPS_WINDOW_MS) {
                Log.i(TAG, "VR_FPS " + frames * 1000 / (now - windowStart))
                frames = 0
                windowStart = now
            }
        }
    }

    /** A GLES 3 context with a small pbuffer: all real drawing goes to the OpenXR swapchains. */
    private class EglHolder {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val config: EGLConfig
        val context: EGLContext
        private val surface: EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
            config = configs[0]!!
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }

    private companion object {
        const val TAG = "OrcaXr"
        const val NEAR = 0.05f
        const val FAR = 100f
        const val IDLE_SLEEP_MS = 50L
        const val FPS_WINDOW_MS = 5000L
        const val JOIN_TIMEOUT_MS = 2000L
    }
}
