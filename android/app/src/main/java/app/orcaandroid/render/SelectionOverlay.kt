package app.orcaandroid.render

import android.opengl.GLES30.*
import android.util.Log

/** Selection styles (bits of the setting); several can be on at once. */
object SelectionStyle {
    const val OUTLINE = 1
    const val XRAY = 2
    const val RIM = 4
    const val WIREFRAME = 8
    const val BOX = 16
    const val TINT = 32
    const val DEFAULT = OUTLINE or BOX
    /** Display order of the settings chips. */
    val ALL = listOf(OUTLINE, XRAY, RIM, WIREFRAME, BOX, TINT)
}

/**
 * Outline and x-ray of the selection (GL thread): the selected parts go into a mask the size of the view (r: as seen,
 * g: all of them), then one full-screen pass draws the ring around them over the frame.
 */
internal class SelectionOverlay {
    private var program = 0
    private var tex = 0
    private var depth = 0
    private var fbo = 0
    private var w = 0
    private var h = 0
    private var broken = false

    /** New GL context: the old objects are gone. */
    fun create(program: (String, String) -> Int) {
        this.program = program(Shaders.FULLSCREEN_VS, Shaders.OUTLINE_FS)
        tex = 0; depth = 0; fbo = 0; w = 0; h = 0; broken = false
    }

    /** [drawMask] draws the scene mesh with the given mask pass (5 seen, 6 x-ray). */
    fun draw(viewW: Int, viewH: Int, style: Int, color: FloatArray, widthPx: Float, drawMask: (Int) -> Unit) {
        if (broken || program == 0) return
        if ((viewW != w || viewH != h) && !targets(viewW, viewH)) return
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, w, h)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glColorMask(true, false, false, false)
        drawMask(5)
        if (style and SelectionStyle.XRAY != 0) {
            glColorMask(false, true, false, false)
            glDisable(GL_DEPTH_TEST)
            drawMask(6)
            glEnable(GL_DEPTH_TEST)
        }
        glColorMask(true, true, true, true)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)

        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(glGetUniformLocation(program, "uMask"), 0)
        glUniform2f(glGetUniformLocation(program, "uTexel"), 1f / w, 1f / h)
        glUniform1f(glGetUniformLocation(program, "uWidth"), widthPx)
        glUniform3fv(glGetUniformLocation(program, "uColor"), 1, color, 0)
        glUniform1i(glGetUniformLocation(program, "uOutline"), if (style and SelectionStyle.OUTLINE != 0) 1 else 0)
        glUniform1i(glGetUniformLocation(program, "uXray"), if (style and SelectionStyle.XRAY != 0) 1 else 0)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glDisable(GL_BLEND)
        glEnable(GL_DEPTH_TEST)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    private fun targets(width: Int, height: Int): Boolean {
        if (fbo != 0) {
            glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            glDeleteTextures(1, intArrayOf(tex), 0)
            glDeleteRenderbuffers(1, intArrayOf(depth), 0)
        }
        val ids = IntArray(1)
        glGenTextures(1, ids, 0); tex = ids[0]
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, width, height, 0, GL_RG, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
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
        if (!ok) { Log.w("OrcaRender", "selection mask target incomplete; outline off"); broken = true; return false }
        w = width; h = height
        return true
    }
}
