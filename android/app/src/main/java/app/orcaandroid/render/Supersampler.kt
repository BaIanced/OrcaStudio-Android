package app.orcaandroid.render

import android.opengl.GLES30.*
import android.util.Log

/** Preview supersampling setting: off, once the camera stops (default), or always (GPU cost while moving). */
object Supersample {
    const val OFF = 0
    const val STILL = 1
    const val ALWAYS = 2
}

/**
 * Supersampled frame (GL thread): the scene is drawn into a target [factor] times the view's size each way, then one
 * full-screen pass filters the samples down to view pixels (Shaders.DOWNSAMPLE_FS). Layer lines a pixel or two apart
 * then show as the eye sees them instead of beating against the pixel grid.
 */
internal class Supersampler {
    private var program = 0
    private var tex = 0
    private var depth = 0
    private var fbo = 0
    private var w = 0
    private var h = 0
    private var broken = false
    /** Samples per view pixel each way of the current target. */
    var factor = 1
        private set

    /** New GL context: the old objects are gone. */
    fun create(program: (String, String) -> Int) {
        this.program = program(Shaders.FULLSCREEN_VS, Shaders.DOWNSAMPLE_FS)
        tex = 0; depth = 0; fbo = 0; w = 0; h = 0; broken = false; factor = 1
    }

    /**
     * Binds the big target and its viewport, for [wanted] samples each way (fewer when the GPU's size limit or memory
     * says so); false (nothing bound) when it can't be made, then the frame is drawn as usual.
     */
    fun begin(viewW: Int, viewH: Int, wanted: Int): Boolean {
        if (broken || program == 0) return false
        val max = IntArray(2)
        glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, max, 0)
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, max, 1)
        val limit = minOf(max[0], max[1])
        var f = wanted
        while (f > 1 && (viewW * f > limit || viewH * f > limit)) f--
        if (f < 2) return false
        if ((viewW * f != w || viewH * f != h) && !targets(viewW * f, viewH * f)) return false
        factor = f
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, w, h)
        return true
    }

    /** Averages the big target into the view (the default framebuffer). */
    fun end(viewW: Int, viewH: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(glGetUniformLocation(program, "uFrame"), 0)
        glUniform1i(glGetUniformLocation(program, "uFactor"), factor)
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /** Frees the target (leaving Preview): it is several times the screen's memory. */
    fun release() {
        if (fbo == 0) return
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteTextures(1, intArrayOf(tex), 0)
        glDeleteRenderbuffers(1, intArrayOf(depth), 0)
        fbo = 0; tex = 0; depth = 0; w = 0; h = 0
    }

    private fun targets(width: Int, height: Int): Boolean {
        release()
        val ids = IntArray(1)
        glGenTextures(1, ids, 0); tex = ids[0]
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenRenderbuffers(1, ids, 0); depth = ids[0]
        glBindRenderbuffer(GL_RENDERBUFFER, depth)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height)
        glGenFramebuffers(1, ids, 0); fbo = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth)
        val ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
        if (!ok) {
            Log.w("OrcaRender", "supersample target ${width}x$height incomplete; supersampling off")
            release(); broken = true; return false
        }
        w = width; h = height
        return true
    }
}
