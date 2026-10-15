package app.orcaandroid.render

import android.opengl.GLES30.*
import android.opengl.Matrix
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/** Ambient occlusion levels of the 3D view (More > Appearance): 0 off, 1 soft, 2 full. */
const val AO_OFF = 0

/**
 * Two screen effects of [PlateRenderer] (GL thread):
 * - contact shadow: the parts seen from straight above into a small texture (nearer the bed = darker),
 *   blurred and laid on the bed under them, so objects sit on the plate instead of floating;
 * - ambient occlusion: a half-resolution depth pre-pass, occlusion from it, a depth-aware blur, then the
 *   frame is multiplied by it after the opaque geometry.
 * Both draw through callbacks so they use the renderer's own mesh / toolpath drawing.
 */
internal class SceneEffects {
    private var blurProgram = 0
    private var shadowProgram = 0
    private var ssaoProgram = 0
    private var aoBlurProgram = 0
    private var compositeProgram = 0

    private val shadowTex = IntArray(2)
    private val shadowFbo = IntArray(2)
    private var shadowW = 0
    private var shadowH = 0
    /** Bed rectangle (x0, y0, x1, y1) the shadow texture covers; null until rendered. */
    private var shadowRect: FloatArray? = null
    private var shadowKey: Any? = null

    private var depthTex = 0
    private var depthFbo = 0
    private val aoTex = IntArray(2)
    private val aoFbo = IntArray(2)
    private var aoW = 0
    private var aoH = 0
    /** A render target failed on this GPU: the effects stay off. */
    private var broken = false

    /** New GL context: the old objects are gone. */
    fun create(program: (String, String) -> Int) {
        blurProgram = program(Shaders.FULLSCREEN_VS, Shaders.BLUR_FS)
        shadowProgram = program(Shaders.SHADOW_VS, Shaders.SHADOW_FS)
        ssaoProgram = program(Shaders.FULLSCREEN_VS, Shaders.SSAO_FS)
        aoBlurProgram = program(Shaders.FULLSCREEN_VS, Shaders.AO_BLUR_FS)
        compositeProgram = program(Shaders.FULLSCREEN_VS, Shaders.AO_COMPOSITE_FS)
        shadowW = 0; shadowH = 0; shadowRect = null; shadowKey = null
        aoW = 0; aoH = 0; depthTex = 0; depthFbo = 0
        shadowTex.fill(0); shadowFbo.fill(0); aoTex.fill(0); aoFbo.fill(0)
        broken = false
    }

    // --- Contact shadow ------------------------------------------------------------------------

    /**
     * Renders the shadow map for parts within [bounds] (x0, y0, x1, y1) unless [key] (whatever changes
     * the parts' footprint) is the same as last time; [draw] draws the parts with the given matrix.
     */
    fun renderShadow(bounds: FloatArray, key: Any, viewW: Int, viewH: Int, draw: (FloatArray) -> Unit) {
        if (broken) return
        if (key == shadowKey && shadowRect != null) return
        val m = SHADOW_MARGIN
        val rect = floatArrayOf(bounds[0] - m, bounds[1] - m, bounds[2] + m, bounds[3] + m)
        val ex = rect[2] - rect[0]
        val ey = rect[3] - rect[1]
        val w = if (ex >= ey) SHADOW_SIZE else max(64, (SHADOW_SIZE * ex / ey).roundToInt())
        val h = if (ey >= ex) SHADOW_SIZE else max(64, (SHADOW_SIZE * ey / ex).roundToInt())
        if (w != shadowW || h != shadowH) {
            if (!targets(shadowTex, shadowFbo, w, h)) return
            shadowW = w; shadowH = h
        }
        val ortho = FloatArray(16)
        Matrix.orthoM(ortho, 0, rect[0], rect[2], rect[1], rect[3], -1e4f, 1e4f)
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo[0])
        glViewport(0, 0, w, h)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendEquation(GL_MAX)
        glBlendFunc(GL_ONE, GL_ONE)
        draw(ortho)
        glBlendEquation(GL_FUNC_ADD)
        glDisable(GL_BLEND)
        // Three blur rounds, wide to narrow: a soft penumbra without the banding of one wide pass.
        glUseProgram(blurProgram)
        for (reach in floatArrayOf(SHADOW_BLUR_MM, SHADOW_BLUR_MM / 2, SHADOW_BLUR_MM / 4)) {
            blur(shadowTex[0], shadowFbo[1], reach / 3.23f / ex, 0f)
            blur(shadowTex[1], shadowFbo[0], 0f, reach / 3.23f / ey)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
        glEnable(GL_DEPTH_TEST)
        shadowRect = rect
        shadowKey = key
    }

    private fun blur(src: Int, dst: Int, du: Float, dv: Float) {
        glBindFramebuffer(GL_FRAMEBUFFER, dst)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, src)
        glUniform1i(glGetUniformLocation(blurProgram, "uTex"), 0)
        glUniform2f(glGetUniformLocation(blurProgram, "uStep"), du, dv)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    /** Lays the shadow on the bed (before the grid, so its lines stay on top). */
    fun drawShadow(mvp: FloatArray, strength: Float) {
        val rect = shadowRect ?: return
        if (broken) return
        glUseProgram(shadowProgram)
        glUniformMatrix4fv(glGetUniformLocation(shadowProgram, "uMvp"), 1, false, mvp, 0)
        glUniform4fv(glGetUniformLocation(shadowProgram, "uRect"), 1, rect, 0)
        glUniform1f(glGetUniformLocation(shadowProgram, "uStrength"), strength)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, shadowTex[0])
        glUniform1i(glGetUniformLocation(shadowProgram, "uTex"), 0)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    /** Forgets the shadow (no parts, or switched off). */
    fun clearShadow() { shadowRect = null; shadowKey = null }

    // --- Ambient occlusion ---------------------------------------------------------------------

    /**
     * Computes the occlusion for this frame: [draw] draws the opaque geometry with the frame's matrix (depth
     * only); [clip] = the projection's near/far. Returns false when it couldn't (then skip [compositeAo]).
     */
    fun renderAo(level: Int, viewW: Int, viewH: Int, clip: Pair<Float, Float>, draw: () -> Unit): Boolean {
        if (broken || level == AO_OFF) return false
        val w = max(1, viewW / 2)
        val h = max(1, viewH / 2)
        if (w != aoW || h != aoH) {
            if (!aoTargets(w, h)) return false
            aoW = w; aoH = h
        }
        glBindFramebuffer(GL_FRAMEBUFFER, depthFbo)
        glViewport(0, 0, w, h)
        glClear(GL_DEPTH_BUFFER_BIT)
        glColorMask(false, false, false, false)
        draw()
        glColorMask(true, true, true, true)
        glDisable(GL_DEPTH_TEST)

        val tanY = tan(Math.toRadians(Camera.FOV / 2.0)).toFloat()
        val aspect = viewW.toFloat() / viewH
        glBindFramebuffer(GL_FRAMEBUFFER, aoFbo[0])
        glUseProgram(ssaoProgram)
        bindDepth(ssaoProgram, clip, tanY * aspect, tanY)
        glUniform2f(glGetUniformLocation(ssaoProgram, "uSize"), w.toFloat(), h.toFloat())
        glUniform1f(glGetUniformLocation(ssaoProgram, "uRadius"), if (level >= 2) 6f else 3f)
        glUniform1f(glGetUniformLocation(ssaoProgram, "uIntensity"), if (level >= 2) 2.2f else 1.3f)
        glUniform1f(glGetUniformLocation(ssaoProgram, "uProjScale"), h / (2f * tanY))
        glDrawArrays(GL_TRIANGLES, 0, 3)

        glUseProgram(aoBlurProgram)
        bindDepth(aoBlurProgram, clip, tanY * aspect, tanY)
        for ((src, dst, step) in listOf(Triple(0, 1, floatArrayOf(1f / w, 0f)), Triple(1, 0, floatArrayOf(0f, 1f / h)))) {
            glBindFramebuffer(GL_FRAMEBUFFER, aoFbo[dst])
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, aoTex[src])
            glUniform1i(glGetUniformLocation(aoBlurProgram, "uAo"), 1)
            glUniform2f(glGetUniformLocation(aoBlurProgram, "uStep"), step[0], step[1])
            glDrawArrays(GL_TRIANGLES, 0, 3)
        }
        glActiveTexture(GL_TEXTURE0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
        glEnable(GL_DEPTH_TEST)
        return true
    }

    private fun bindDepth(program: Int, clip: Pair<Float, Float>, tanX: Float, tanY: Float) {
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, depthTex)
        glUniform1i(glGetUniformLocation(program, "uDepth"), 0)
        glUniform2f(glGetUniformLocation(program, "uNearFar"), clip.first, clip.second)
        glUniform2f(glGetUniformLocation(program, "uTanHalf"), tanX, tanY)
    }

    /** Multiplies the frame (opaque geometry drawn so far) by the occlusion. */
    fun compositeAo(viewW: Int, viewH: Int) {
        glUseProgram(compositeProgram)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, aoTex[0])
        glUniform1i(glGetUniformLocation(compositeProgram, "uAo"), 0)
        glUniform2f(glGetUniformLocation(compositeProgram, "uSize"), viewW.toFloat(), viewH.toFloat())
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ZERO, GL_SRC_COLOR)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glDisable(GL_BLEND)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
    }

    // --- Targets -------------------------------------------------------------------------------

    /** (Re)creates two linear R8 colour targets of w x h. */
    private fun targets(tex: IntArray, fbo: IntArray, w: Int, h: Int): Boolean {
        if (tex[0] != 0) { glDeleteTextures(2, tex, 0); glDeleteFramebuffers(2, fbo, 0) }
        glGenTextures(2, tex, 0)
        glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            glBindTexture(GL_TEXTURE_2D, tex[i])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, w, h, 0, GL_RED, GL_UNSIGNED_BYTE, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[i], 0)
            if (!complete()) return false
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return true
    }

    private fun aoTargets(w: Int, h: Int): Boolean {
        if (!targets(aoTex, aoFbo, w, h)) return false
        if (depthTex != 0) { glDeleteTextures(1, intArrayOf(depthTex), 0); glDeleteFramebuffers(1, intArrayOf(depthFbo), 0) }
        val ids = IntArray(1)
        glGenTextures(1, ids, 0); depthTex = ids[0]
        glGenFramebuffers(1, ids, 0); depthFbo = ids[0]
        glBindTexture(GL_TEXTURE_2D, depthTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindFramebuffer(GL_FRAMEBUFFER, depthFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex, 0)
        val ok = complete()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return ok
    }

    private fun complete(): Boolean {
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status == GL_FRAMEBUFFER_COMPLETE) return true
        Log.w("OrcaRender", "effect target incomplete: 0x${status.toString(16)}; shadows/AO off")
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        broken = true
        return false
    }

    private companion object {
        /** Longer side of the shadow texture, texels. */
        const val SHADOW_SIZE = 1024
        /** Room around the parts for the blur (and a dragged copy), mm. */
        const val SHADOW_MARGIN = 20f
        /** Reach of the first blur round, mm. */
        const val SHADOW_BLUR_MM = 10f
    }
}
