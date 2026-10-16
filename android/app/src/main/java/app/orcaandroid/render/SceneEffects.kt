package app.orcaandroid.render

import android.opengl.GLES30.*
import android.opengl.Matrix
import android.util.Log
import kotlin.math.max
import kotlin.math.min
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

    /** Real shadow map (user wishlist 2026-10: stage 4): the parts' depth from the key light; the old contact shadow stays as the fallback. */
    private var realTex = 0
    private var realFbo = 0
    private var realFailed = false
    /** The depth target works on this GPU (else the contact shadow is used). */
    var isRealShadow = false; private set
    /** Texture and light matrix of the last real shadow render. */
    val realShadowTex get() = realTex
    val lightMvp = FloatArray(16)
    /** Direction towards the key light (the mesh shader's `uKeyDir`): set by the renderer, read when the depth map is drawn. */
    var lightDir = floatArrayOf(LIGHT_X, LIGHT_Y, LIGHT_Z)
    /** Width of the light's view in mm (one uv unit of the depth map), to turn mm of penumbra into uv. */
    var lightSpanMm = 1f; private set
    /** True when a real shadow map is rendered and may be sampled. */
    val realShadowReady get() = isRealShadow && shadowRect != null && !broken

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
        // The old context's objects are gone: forget the ids (deleting them could hit new objects with the same names).
        realTex = 0; realFbo = 0; realFailed = false; isRealShadow = false
    }

    // --- Contact shadow ------------------------------------------------------------------------

    /**
     * Renders the shadow map for parts within [bounds] (x0, y0, x1, y1) unless [key] (whatever changes
     * the parts' footprint) is the same as last time; [draw] draws the parts with the given matrix.
     */
    fun renderShadow(bounds: FloatArray, key: Any, viewW: Int, viewH: Int, draw: (FloatArray) -> Unit) {
        if (broken) return
        if (key == shadowKey && shadowRect != null) return
        if (!isRealShadow && !realFailed) realTargets()
        if (isRealShadow) { renderReal(bounds, key, viewW, viewH, draw); return }
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

    /** Creates the depth-only target of the real shadow map; on a GPU that can't, [realFailed] keeps the contact shadow. */
    private fun realTargets() {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0); realTex = ids[0]
        glGenFramebuffers(1, ids, 0); realFbo = ids[0]
        glBindTexture(GL_TEXTURE_2D, realTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, REAL_SIZE, REAL_SIZE, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindFramebuffer(GL_FRAMEBUFFER, realFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, realTex, 0)
        // Depth only: no colour attachment to draw to or read from.
        glDrawBuffers(1, intArrayOf(GL_NONE), 0)
        glReadBuffer(GL_NONE)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
        if (status == GL_FRAMEBUFFER_COMPLETE) { isRealShadow = true; return }
        Log.w("OrcaRender", "shadow map target incomplete: 0x${status.toString(16)}; contact shadow instead")
        glDeleteTextures(1, intArrayOf(realTex), 0)
        glDeleteFramebuffers(1, intArrayOf(realFbo), 0)
        realTex = 0; realFbo = 0; realFailed = true
    }

    /** Depth of the parts seen from the key light (the mesh shader's `lk`), an orthographic box fitted round [bounds]. */
    private fun renderReal(bounds: FloatArray, key: Any, viewW: Int, viewH: Int, draw: (FloatArray) -> Unit) {
        val m = REAL_MARGIN
        val rect = floatArrayOf(bounds[0] - m, bounds[1] - m, bounds[2] + m, bounds[3] + m)
        val cx = (bounds[0] + bounds[2]) / 2f
        val cy = (bounds[1] + bounds[3]) / 2f
        val r = max(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 2f + m
        val (lx, ly, lz) = lightDir
        val len = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, cx + lx / len * REAL_DISTANCE, cy + ly / len * REAL_DISTANCE, lz / len * REAL_DISTANCE,
            cx, cy, 0f, 0f, 0f, 1f)
        val proj = FloatArray(16)
        Matrix.orthoM(proj, 0, -r, r, -r, r, 10f, REAL_DISTANCE * 3f)
        Matrix.multiplyMM(lightMvp, 0, proj, 0, view, 0)
        lightSpanMm = 2f * r
        glBindFramebuffer(GL_FRAMEBUFFER, realFbo)
        glViewport(0, 0, REAL_SIZE, REAL_SIZE)
        glEnable(GL_DEPTH_TEST)
        glDepthMask(true)
        glClear(GL_DEPTH_BUFFER_BIT)
        glColorMask(false, false, false, false)
        draw(lightMvp)
        glColorMask(true, true, true, true)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
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

    /** Lays the shadow on the beds [beds] (x0, y0, x1, y1 each; before the grid, so its lines stay on top). */
    fun drawShadow(mvp: FloatArray, strength: Float, softMm: Float, biasMm: Float, beds: List<FloatArray>) {
        val rect = shadowRect ?: return
        if (broken) return
        glUseProgram(shadowProgram)
        glUniformMatrix4fv(glGetUniformLocation(shadowProgram, "uMvp"), 1, false, mvp, 0)
        val real = isRealShadow
        // The real shadow already falls away from the light: no shift.
        val dx = if (real) 0f else SHADOW_SHIFT_X
        val dy = if (real) 0f else SHADOW_SHIFT_Y
        val shifted = floatArrayOf(rect[0] + dx, rect[1] + dy, rect[2] + dx, rect[3] + dy)
        glUniform4fv(glGetUniformLocation(shadowProgram, "uRect"), 1, shifted, 0)
        glUniform1f(glGetUniformLocation(shadowProgram, "uStrength"), strength)
        glUniform1i(glGetUniformLocation(shadowProgram, "uReal"), if (real) 1 else 0)
        // Two samplers of different types must not share a unit (INVALID_OPERATION at the draw): contact map 0, depth map 1.
        glUniform1i(glGetUniformLocation(shadowProgram, "uTex"), 0)
        glUniform1i(glGetUniformLocation(shadowProgram, "uRealShadowMap"), 1)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, if (real) 0 else shadowTex[0])
        if (real) {
            glUniformMatrix4fv(glGetUniformLocation(shadowProgram, "uLightMvp"), 1, false, lightMvp, 0)
            glUniform1f(glGetUniformLocation(shadowProgram, "uSoft"), softMm / lightSpanMm)
            glUniform1f(glGetUniformLocation(shadowProgram, "uBiasMm"), biasMm)
            val (lx, ly, lz) = lightDir
            glUniform1f(glGetUniformLocation(shadowProgram, "uBedNdl"), lz / kotlin.math.sqrt(lx * lx + ly * ly + lz * lz))
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, realTex)
            glActiveTexture(GL_TEXTURE0)
        }
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        val clipLoc = glGetUniformLocation(shadowProgram, "uClip")
        for (b in beds) {
            val x0 = max(shifted[0], b[0]); val y0 = max(shifted[1], b[1])
            val x1 = min(shifted[2], b[2]); val y1 = min(shifted[3], b[3])
            if (x0 >= x1 || y0 >= y1) continue
            glUniform4f(clipLoc, x0, y0, x1, y1)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
        if (real) { glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, 0); glActiveTexture(GL_TEXTURE0) }
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
    fun compositeAo(viewW: Int, viewH: Int, strength: Float = 1f) {
        glUseProgram(compositeProgram)
        glUniform1f(glGetUniformLocation(compositeProgram, "uStrength"), strength)
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
        const val SHADOW_BLUR_MM = 5f
        /** The shadow sits this far from the parts, away from the light (matches the mesh shader's light), mm. */
        const val SHADOW_SHIFT_X = -1.2f
        const val SHADOW_SHIFT_Y = 1.8f
        /** Real shadow map: texels per side (one render per scene change), room round the parts for shadows falling off them, mm. */
        const val REAL_SIZE = 2048
        const val REAL_MARGIN = 300f
        /** The key light's distance from the plate centre, mm (taller than any print). */
        const val REAL_DISTANCE = 350f
        /** Default direction towards the key light (azimuth -57.5, elevation 49 degrees in the layout lab). */
        const val LIGHT_X = 0.35f
        const val LIGHT_Y = -0.55f
        const val LIGHT_Z = 0.75f
    }
}
