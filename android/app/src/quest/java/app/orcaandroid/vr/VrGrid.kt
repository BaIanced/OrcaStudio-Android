package app.orcaandroid.vr

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One static floor grid with its own tiny shader: the M0 test content. Needs a current GL context. */
internal class VrGrid {
    private val program = link()
    private val uViewProj = GLES30.glGetUniformLocation(program, "uViewProj")
    private val vao: Int
    private val vertexCount: Int

    init {
        val lines = VrMath.gridLines(half = 2f, step = 0.25f, y = FLOOR_Y)
        vertexCount = lines.size / 3
        val data = ByteBuffer.allocateDirect(lines.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(lines).position(0)
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lines.size * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    fun draw(viewProj: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    private fun link(): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, compile(GLES30.GL_VERTEX_SHADER, VERTEX))
        GLES30.glAttachShader(program, compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Grid shader link failed: ${GLES30.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Grid shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        /** The LOCAL space's origin is near the head at start, so this is roughly a seated floor (metres). */
        const val FLOOR_Y = -1.2f

        const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }
"""
        const val FRAGMENT = """#version 300 es
precision mediump float;
out vec4 color;
void main() { color = vec4(0.35, 0.8, 0.9, 1.0); }
"""
    }
}
