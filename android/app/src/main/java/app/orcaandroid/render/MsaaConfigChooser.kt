package app.orcaandroid.render

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * ES 3 surface of RGBA8 and a 24-bit depth buffer with 4x MSAA (smoother edges and layer lines while the camera moves),
 * or the same without MSAA where the GPU has no such config.
 */
internal class MsaaConfigChooser(private val samples: Int = 4) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        if (samples > 1) pick(egl, display, samples)?.let { return it }
        Log.i("OrcaRender", "no ${samples}x MSAA config; the 3D view draws without MSAA")
        return pick(egl, display, 0) ?: throw IllegalArgumentException("No RGBA8 / depth 24 ES 3 config")
    }

    private fun pick(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
        val spec = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL10.EGL_SAMPLE_BUFFERS, if (samples > 1) 1 else 0, EGL10.EGL_SAMPLES, samples,
            EGL10.EGL_NONE)
        val count = IntArray(1)
        if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) return null
        val configs = arrayOfNulls<EGLConfig>(count[0])
        if (!egl.eglChooseConfig(display, spec, configs, count[0], count)) return null
        val value = IntArray(1)
        fun attr(c: EGLConfig, a: Int) = if (egl.eglGetConfigAttrib(display, c, a, value)) value[0] else 0
        // eglChooseConfig takes the sizes as minimums: keep exactly 8 bits a channel (as GLSurfaceView's chooser does).
        return configs.filterNotNull().firstOrNull { c ->
            attr(c, EGL10.EGL_RED_SIZE) == 8 && attr(c, EGL10.EGL_GREEN_SIZE) == 8 && attr(c, EGL10.EGL_BLUE_SIZE) == 8 &&
                attr(c, EGL10.EGL_ALPHA_SIZE) == 8 && (samples <= 1 || attr(c, EGL10.EGL_SAMPLES) == samples)
        }
    }

    private companion object {
        /** EGL_OPENGL_ES3_BIT_KHR. */
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
