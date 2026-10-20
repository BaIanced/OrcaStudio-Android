package app.orcaandroid.render

import android.opengl.GLES30.*
import android.util.Log

/**
 * Temporal supersampling (GL thread) while the view rests: each frame is drawn at the view's size with the projection
 * shifted by a sub-pixel [jitter], and mixed 1/n into a half-float buffer, so after n frames each pixel holds the mean of
 * n samples spread as a tent over +-1 px (as the 2x [Supersampler]'s filter, with 32 samples instead of 16 and a quarter
 * of its memory). Needs RGBA16F colour buffers (ES 3.2 or EXT_color_buffer_half_float / _float); [supported] is false
 * otherwise, and the 2x path stays.
 */
internal class Accumulator {
    private var program = 0
    private var sceneTex = 0
    private var sceneDepth = 0
    private var sceneFbo = 0
    private var sumTex = 0
    private var sumFbo = 0
    private var w = 0
    private var h = 0
    private var broken = false
    /** Checked once per context: ES 3.2 or an extension that makes RGBA16F renderable. */
    var supported = false
        private set

    /** New GL context: the old objects are gone. */
    fun create(program: (String, String) -> Int) {
        this.program = program(Shaders.FULLSCREEN_VS, Shaders.COPY_FS)
        sceneTex = 0; sceneDepth = 0; sceneFbo = 0; sumTex = 0; sumFbo = 0; w = 0; h = 0; broken = false
        val version = glGetString(GL_VERSION).orEmpty()
        val ext = glGetString(GL_EXTENSIONS).orEmpty().split(' ')
        val es32 = Regex("""OpenGL ES (\d+)\.(\d+)""").find(version)?.destructured?.let { (a, b) -> a.toInt() * 10 + b.toInt() >= 32 } == true
        supported = this.program != 0 && (es32 || "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext)
        if (!supported) Log.i("OrcaRender", "no half-float colour buffers ($version); still frames use 2x supersampling")
    }

    /** Binds the frame target (view size) for one jittered frame; false (nothing bound) when it can't be made. */
    fun begin(viewW: Int, viewH: Int): Boolean {
        if (!supported || broken) return false
        if ((viewW != w || viewH != h) && !targets(viewW, viewH)) return false
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo)
        glViewport(0, 0, w, h)
        return true
    }

    /** Mixes the frame just drawn into the sum with weight [weight] (1 = the first frame replaces it), then shows the sum. */
    fun end(viewW: Int, viewH: Int, weight: Float) {
        glBindFramebuffer(GL_FRAMEBUFFER, sumFbo)
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glEnable(GL_BLEND)
        glBlendColor(0f, 0f, 0f, weight)
        glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA)
        copy(sceneTex)
        glDisable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
        show(viewW, viewH)
    }

    /** Copies the sum into the view (the default framebuffer). */
    fun show(viewW: Int, viewH: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        copy(sumTex)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
    }

    private fun copy(tex: Int) {
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(glGetUniformLocation(program, "uFrame"), 0)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /** Frees the targets (leaving Preview). */
    fun release() {
        if (sceneFbo == 0 && sumFbo == 0) return
        glDeleteFramebuffers(2, intArrayOf(sceneFbo, sumFbo), 0)
        glDeleteTextures(2, intArrayOf(sceneTex, sumTex), 0)
        glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
        sceneFbo = 0; sumFbo = 0; sceneTex = 0; sumTex = 0; sceneDepth = 0; w = 0; h = 0
    }

    private fun texture(internal: Int, format: Int, type: Int, width: Int, height: Int): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun targets(width: Int, height: Int): Boolean {
        release()
        sceneTex = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, width, height)
        sumTex = texture(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, width, height)
        val ids = IntArray(2)
        glGenRenderbuffers(1, ids, 0); sceneDepth = ids[0]
        glBindRenderbuffer(GL_RENDERBUFFER, sceneDepth)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height)
        glGenFramebuffers(2, ids, 0); sceneFbo = ids[0]; sumFbo = ids[1]
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sceneTex, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, sceneDepth)
        var ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
        glBindFramebuffer(GL_FRAMEBUFFER, sumFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sumTex, 0)
        ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
        if (!ok) {
            Log.w("OrcaRender", "accumulation targets ${width}x$height incomplete; still frames use 2x supersampling")
            release(); broken = true; return false
        }
        w = width; h = height
        return true
    }

    companion object {
        /**
         * Sub-pixel offset (px) of frame [i] each way: the sum of two Halton points in -0.5..0.5, so the offsets spread
         * as a tent over +-1 px, the filter that keeps layer lines ~1.5 px apart from beating into arcs.
         */
        fun jitter(i: Int): Pair<Float, Float> =
            (halton(i + 1, 2) + halton(i + 1, 3) - 1f) to (halton(i + 1, 5) + halton(i + 1, 7) - 1f)

        private fun halton(index: Int, base: Int): Float {
            var f = 1f
            var r = 0f
            var i = index
            while (i > 0) { f /= base; r += f * (i % base); i /= base }
            return r
        }
    }
}
