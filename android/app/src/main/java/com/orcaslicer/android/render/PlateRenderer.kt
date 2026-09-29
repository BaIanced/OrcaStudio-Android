package com.orcaslicer.android.render

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** CPU-side geometry, prepared off the GL thread and uploaded by [PlateRenderer]. */
class PlateGeometry private constructor(val vertices: FloatBuffer, val vertexCount: Int) {
    companion object {
        /** Mesh file from the core: 6 floats (x,y,z,nx,ny,nz) per vertex. */
        fun loadMesh(path: String): PlateGeometry {
            val data = readFloats(path)
            return PlateGeometry(data, data.limit() / 6)
        }

        /**
         * Toolpath file from the core: 7 floats (x0,y0,z0,x1,y1,z1,role) per segment. Expanded to
         * GL_LINES vertices of 4 floats (x,y,z,role).
         */
        fun loadToolpaths(path: String): PlateGeometry {
            val src = readFloats(path)
            val segments = src.limit() / 7
            val dst = directFloats(segments * 8)
            val seg = FloatArray(7)
            repeat(segments) {
                src.get(seg)
                dst.put(seg, 0, 3).put(seg[6]).put(seg, 3, 3).put(seg[6])
            }
            dst.flip()
            return PlateGeometry(dst, segments * 2)
        }

        private fun readFloats(path: String): FloatBuffer =
            RandomAccessFile(File(path), "r").use { f ->
                val bytes = f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
                bytes.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            }

        private fun directFloats(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}

/**
 * Renders the build plate, the loaded model and the sliced toolpaths.
 *
 * All setters are called on the GL thread (via GLSurfaceView.queueEvent).
 */
class PlateRenderer : GLSurfaceView.Renderer {

    // --- Scene state ---
    private var bedOutline = FloatArray(0)
    private var bedMin = floatArrayOf(0f, 0f)
    private var bedMax = floatArrayOf(200f, 200f)
    private var pendingMesh: PlateGeometry? = null
    private var pendingToolpaths: PlateGeometry? = null
    private var meshVbo = 0
    private var meshCount = 0
    private var pathVbo = 0
    private var pathCount = 0
    private var bedVbo = 0
    private var bedCount = 0
    private var bedDirty = true

    private var background = floatArrayOf(0.12f, 0.13f, 0.15f)
    private var gridColor = floatArrayOf(0.45f, 0.47f, 0.52f)

    var showToolpaths = false
    /** Number of toolpath segments to draw (the preview up to the selected layer). */
    var visibleSegments = Int.MAX_VALUE

    // --- Camera (orbit around a target, Z up) ---
    private var yaw = -45f
    private var pitch = 35f
    private var distance = 400f
    private val target = floatArrayOf(100f, 100f, 0f)
    private var aspect = 1f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private var meshProgram = 0
    private var lineProgram = 0

    /** Follows the app theme: view background and bed grid color (RGB 0..1). */
    fun setColors(background: FloatArray, grid: FloatArray) {
        this.background = background
        gridColor = grid
    }

    fun setBed(outline: FloatArray) {
        if (outline.size < 6) return
        bedOutline = outline
        bedMin[0] = outline.filterIndexed { i, _ -> i % 2 == 0 }.min()
        bedMin[1] = outline.filterIndexed { i, _ -> i % 2 == 1 }.min()
        bedMax[0] = outline.filterIndexed { i, _ -> i % 2 == 0 }.max()
        bedMax[1] = outline.filterIndexed { i, _ -> i % 2 == 1 }.max()
        bedDirty = true
        resetCamera()
    }

    fun setMesh(geometry: PlateGeometry?) {
        pendingMesh = geometry
        if (geometry == null) meshCount = 0
    }

    fun setToolpaths(geometry: PlateGeometry?) {
        pendingToolpaths = geometry
        if (geometry == null) pathCount = 0
    }

    fun resetCamera() {
        target[0] = (bedMin[0] + bedMax[0]) / 2
        target[1] = (bedMin[1] + bedMax[1]) / 2
        target[2] = 0f
        distance = max(bedMax[0] - bedMin[0], bedMax[1] - bedMin[1]) * 1.6f
        yaw = -45f
        pitch = 35f
    }

    fun orbit(dxDeg: Float, dyDeg: Float) {
        yaw -= dxDeg
        pitch = (pitch + dyDeg).coerceIn(-89f, 89f)
    }

    fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(10f, 5000f)
    }

    /** Pans in the view plane; dx/dy are in screen fractions. */
    fun pan(dx: Float, dy: Float) {
        val scale = distance
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        // Right and up vectors of the orbit camera.
        val rx = -sin(yr).toFloat(); val ry = cos(yr).toFloat()
        val ux = (-sin(pr) * cos(yr)).toFloat(); val uy = (-sin(pr) * sin(yr)).toFloat(); val uz = cos(pr).toFloat()
        target[0] += (-dx * rx + dy * ux) * scale
        target[1] += (-dx * ry + dy * uy) * scale
        target[2] += dy * uz * scale
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glEnable(GL_DEPTH_TEST)
        meshProgram = program(MESH_VS, MESH_FS)
        lineProgram = program(LINE_VS, LINE_FS)
        val ids = IntArray(3)
        glGenBuffers(3, ids, 0)
        meshVbo = ids[0]; pathVbo = ids[1]; bedVbo = ids[2]
        // A new EGL context lost the old buffers; re-upload whatever is current.
        bedDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        aspect = width.toFloat() / max(height, 1)
    }

    override fun onDrawFrame(gl: GL10?) {
        upload()
        glClearColor(background[0], background[1], background[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val eye = floatArrayOf(
            target[0] + (distance * cos(pr) * cos(yr)).toFloat(),
            target[1] + (distance * cos(pr) * sin(yr)).toFloat(),
            target[2] + (distance * sin(pr)).toFloat(),
        )
        Matrix.perspectiveM(proj, 0, 35f, aspect, max(1f, distance / 100f), distance * 10f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], target[0], target[1], target[2], 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        drawLines(bedVbo, bedCount, useRole = false, color = gridColor)
        if (showToolpaths && pathCount > 0) {
            val count = if (visibleSegments.toLong() * 2 >= pathCount) pathCount else visibleSegments * 2
            drawLines(pathVbo, count, useRole = true, color = null)
        } else if (meshCount > 0) {
            drawMesh()
        }
    }

    private fun upload() {
        pendingMesh?.let {
            glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
            glBufferData(GL_ARRAY_BUFFER, it.vertices.limit() * 4, it.vertices.position(0), GL_STATIC_DRAW)
            meshCount = it.vertexCount
            pendingMesh = null
        }
        pendingToolpaths?.let {
            glBindBuffer(GL_ARRAY_BUFFER, pathVbo)
            glBufferData(GL_ARRAY_BUFFER, it.vertices.limit() * 4, it.vertices.position(0), GL_STATIC_DRAW)
            pathCount = it.vertexCount
            pendingToolpaths = null
        }
        if (bedDirty) {
            val lines = bedLines()
            val buf = ByteBuffer.allocateDirect(lines.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(lines)
            buf.flip()
            glBindBuffer(GL_ARRAY_BUFFER, bedVbo)
            glBufferData(GL_ARRAY_BUFFER, lines.size * 4, buf, GL_STATIC_DRAW)
            bedCount = lines.size / 4
            bedDirty = false
        }
    }

    /** Outline plus a 10 mm grid, as x,y,z,role line vertices. */
    private fun bedLines(): FloatArray {
        val out = ArrayList<Float>()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            out += listOf(x0, y0, 0f, 0f, x1, y1, 0f, 0f)
        }
        val n = bedOutline.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            line(bedOutline[2 * i], bedOutline[2 * i + 1], bedOutline[2 * j], bedOutline[2 * j + 1])
        }
        var x = bedMin[0] + 10f
        while (x < bedMax[0]) { line(x, bedMin[1], x, bedMax[1]); x += 10f }
        var y = bedMin[1] + 10f
        while (y < bedMax[1]) { line(bedMin[0], y, bedMax[0], y); y += 10f }
        return out.toFloatArray()
    }

    private fun drawLines(vbo: Int, count: Int, useRole: Boolean, color: FloatArray?) {
        if (count <= 0) return
        glUseProgram(lineProgram)
        glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
        glUniform1i(glGetUniformLocation(lineProgram, "uUseRole"), if (useRole) 1 else 0)
        glUniform3fv(glGetUniformLocation(lineProgram, "uRoleColors"), ROLE_COLORS.size / 3, ROLE_COLORS, 0)
        glUniform3fv(glGetUniformLocation(lineProgram, "uColor"), 1, color ?: floatArrayOf(1f, 1f, 1f), 0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 16, 12)
        glDrawArrays(GL_LINES, 0, count)
        glDisableVertexAttribArray(1)
        glDisableVertexAttribArray(0)
    }

    private fun drawMesh() {
        glUseProgram(meshProgram)
        glUniformMatrix4fv(glGetUniformLocation(meshProgram, "uMvp"), 1, false, mvp, 0)
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
        glDrawArrays(GL_TRIANGLES, 0, meshCount)
        glDisableVertexAttribArray(1)
        glDisableVertexAttribArray(0)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: " + glGetShaderInfoLog(s) }
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glBindAttribLocation(p, 0, "aPos")
        glBindAttribLocation(p, 1, "aExtra")
        glLinkProgram(p)
        return p
    }

    private companion object {
        const val MESH_VS = """#version 300 es
            uniform mat4 uMvp;
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aExtra;
            out vec3 vNormal;
            void main() { vNormal = aExtra; gl_Position = uMvp * vec4(aPos, 1.0); }"""
        const val MESH_FS = """#version 300 es
            precision mediump float;
            in vec3 vNormal;
            out vec4 color;
            void main() {
                vec3 n = normalize(vNormal);
                float light = 0.35 + 0.65 * abs(dot(n, normalize(vec3(0.4, -0.5, 0.8))));
                color = vec4(vec3(0.16, 0.72, 0.55) * light, 1.0);
            }"""
        const val LINE_VS = """#version 300 es
            uniform mat4 uMvp;
            uniform bool uUseRole;
            uniform vec3 uColor;
            uniform vec3 uRoleColors[20];
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in float aExtra;
            out vec3 vColor;
            void main() {
                vColor = uUseRole ? uRoleColors[clamp(int(aExtra + 0.5), 0, 19)] : uColor;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }"""
        const val LINE_FS = """#version 300 es
            precision mediump float;
            in vec3 vColor;
            out vec4 color;
            void main() { color = vec4(vColor, 1.0); }"""

        /** Colors per libslic3r ExtrusionRole (erNone ... erMixed), close to OrcaSlicer's defaults. */
        val ROLE_COLORS = floatArrayOf(
            0.5f, 0.5f, 0.5f,   // None
            1.0f, 0.90f, 0.30f, // Perimeter
            1.0f, 0.49f, 0.22f, // External perimeter
            0.12f, 0.12f, 1.0f, // Overhang perimeter
            0.69f, 0.19f, 0.16f,// Internal infill
            0.59f, 0.33f, 0.80f,// Solid infill
            0.94f, 0.25f, 0.25f,// Top solid infill
            0.40f, 0.36f, 0.78f,// Bottom surface
            1.0f, 0.55f, 0.41f, // Ironing
            0.30f, 0.50f, 0.73f,// Bridge infill
            0.30f, 0.50f, 0.73f,// Internal bridge infill
            1.0f, 1.0f, 1.0f,   // Gap fill
            0.0f, 0.53f, 0.43f, // Skirt
            0.0f, 0.53f, 0.43f, // Brim
            0.0f, 1.0f, 0.0f,   // Support
            0.0f, 0.5f, 0.0f,   // Support interface
            0.0f, 0.8f, 0.4f,   // Support transition
            0.70f, 0.89f, 0.67f,// Wipe tower
            0.37f, 0.82f, 0.58f,// Custom
            0.5f, 0.5f, 0.5f,   // Mixed
        )
    }
}
