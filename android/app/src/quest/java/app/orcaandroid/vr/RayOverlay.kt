package app.orcaandroid.vr

import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Draws each controller's aim ray as a thin solid prism, coloured by what it does, on the bound eye framebuffer. */
internal class RayOverlay {
    private val program: Int
    private val uMvp: Int
    private val uColor: Int
    private val buffer: Int
    private val pose = FloatArray(16)
    private val mvp = FloatArray(16)

    init {
        val vertex = shader(GL_VERTEX_SHADER, "#version 300 es\nlayout(location=0) in vec3 p;uniform mat4 m;void main(){gl_Position=m*vec4(p,1.);}")
        val fragment = shader(GL_FRAGMENT_SHADER, "#version 300 es\nprecision mediump float;uniform vec3 c;out vec4 o;void main(){o=vec4(c,1.);}")
        program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        uMvp = glGetUniformLocation(program, "m")
        uColor = glGetUniformLocation(program, "c")
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        buffer = ids[0]
        val data = ByteBuffer.allocateDirect(PRISM.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(PRISM).position(0)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        glBufferData(GL_ARRAY_BUFFER, PRISM.size * 4, data, GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    fun draw(viewProj: FloatArray, hands: FloatArray, grab: PlateGrab) {
        glUseProgram(program)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
        for (hand in 0 until XrShim.HANDS) {
            val state = grab.rayState[hand]
            if (state == PlateGrab.RAY_NONE) continue
            VrMath.aimMatrix(pose, hands, hand * XrShim.FLOATS_PER_HAND + XrShim.HAND_POSE, grab.rayLength[hand])
            VrMath.multiply(mvp, viewProj, pose)
            glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            when (state) {
                PlateGrab.RAY_GRABBED -> glUniform3f(uColor, 1f, 0.55f, 0.1f)
                PlateGrab.RAY_HOVER -> glUniform3f(uColor, 0.2f, 0.85f, 1f)
                else -> glUniform3f(uColor, 0.75f, 0.75f, 0.75f)
            }
            glDrawArrays(GL_TRIANGLES, 0, PRISM.size / 3)
        }
        // The renderer draws with its own attributes in the default vertex array.
        glDisableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    private fun shader(type: Int, source: String): Int {
        val id = glCreateShader(type)
        glShaderSource(id, source)
        glCompileShader(id)
        return id
    }

    private companion object {
        /** Metres. The ray keeps this thickness at any length: [VrMath.aimMatrix] only stretches z. */
        const val RADIUS = 0.0015f
        val PRISM: FloatArray = run {
            val corners = arrayOf(0f to RADIUS, 0.866f * RADIUS to -0.5f * RADIUS, -0.866f * RADIUS to -0.5f * RADIUS)
            val out = ArrayList<Float>()
            for (i in 0 until 3) {
                val a = corners[i]
                val b = corners[(i + 1) % 3]
                out += listOf(a.first, a.second, 0f, b.first, b.second, 0f, b.first, b.second, -1f)
                out += listOf(a.first, a.second, 0f, b.first, b.second, -1f, a.first, a.second, -1f)
            }
            out.toFloatArray()
        }
    }
}
