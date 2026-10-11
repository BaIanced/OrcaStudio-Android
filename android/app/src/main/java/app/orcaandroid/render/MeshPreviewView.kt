package app.orcaandroid.render

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import app.orcaandroid.core.Vec3
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A triangle mesh for [MeshPreviewView]: interleaved position + face normal per vertex. Plain class
 * (identity equality), so UI state holding it stays cheap to compare.
 */
class PreviewMesh(val data: FloatBuffer, val triangles: Int, val min: Vec3, val max: Vec3) {
    val size: Vec3 get() = max - min

    companion object {
        /** Reads a binary STL (as OpenSCAD writes it); null when empty or not binary. */
        fun readStl(file: File): PreviewMesh? {
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
                if (map.capacity() < 84) return null
                val count = map.getInt(80)
                if (count <= 0 || 84L + count * 50L > map.capacity()) return null
                val out = ByteBuffer.allocateDirect(count * 3 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val v = FloatArray(9)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                for (t in 0 until count) {
                    val base = 84 + t * 50 + 12
                    for (i in 0 until 9) v[i] = map.getFloat(base + i * 4)
                    // Face normal from the vertices (the stored one may be zero).
                    val ax = v[3] - v[0]; val ay = v[4] - v[1]; val az = v[5] - v[2]
                    val bx = v[6] - v[0]; val by = v[7] - v[1]; val bz = v[8] - v[2]
                    var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
                    val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
                    nx /= len; ny /= len; nz /= len
                    for (k in 0 until 3) {
                        val x = v[k * 3]; val y = v[k * 3 + 1]; val z = v[k * 3 + 2]
                        out.put(x).put(y).put(z).put(nx).put(ny).put(nz)
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                    }
                }
                out.flip()
                return PreviewMesh(out, count, Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
            }
        }
    }
}

/**
 * Small 3D view of one mesh (the OpenSCAD editor's preview): the model on a grid at z = 0
 * with the X (red) and Y (green) axes. One finger orbits, two fingers pan and pinch zoom, the mouse
 * wheel zooms, a double tap frames the model again. Draws only when something changed.
 */
class MeshPreviewView(context: Context) : GLSurfaceView(context) {

    val camera = Camera()
    private val renderer = PreviewRenderer(camera)
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var pointers = 0
    private var mesh: PreviewMesh? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            reframe()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 4)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setColors(background: FloatArray, grid: FloatArray, part: FloatArray) {
        if (background.contentEquals(renderer.background) && grid.contentEquals(renderer.gridColor)) return
        queueEvent { renderer.background = background; renderer.gridColor = grid; renderer.partColor = part }
        requestRender()
    }

    /** Shows [mesh]; the view is framed again when the model's size changed noticeably. */
    fun setMesh(mesh: PreviewMesh?) {
        val old = this.mesh
        if (mesh === old) return
        this.mesh = mesh
        queueEvent { renderer.setMesh(mesh) }
        if (mesh != null && (old == null || !similar(old.size, mesh.size))) reframe() else requestRender()
    }

    fun reframe() {
        val m = mesh
        if (m != null) queueEvent { renderer.fitPending = m; requestRender() }
        else requestRender()
    }

    private fun similar(a: Vec3, b: Vec3): Boolean {
        val la = a.length(); val lb = b.length()
        return lb > 0f && la / lb in 0.8f..1.25f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The preview sits in a scrolling panel: keep the gesture.
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        val fx = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
        val fy = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; pointers = 1 }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // The focus jumps when a finger is added or lifted; start the pan from there.
                pointers = event.pointerCount - if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) 1 else 0
                lastFocusX = fx; lastFocusY = fy
                val keep = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0) 1 else 0
                lastX = event.getX(keep); lastY = event.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                val pan = event.pointerCount > 1 || event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) ||
                    event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)
                if (event.pointerCount > 1) {
                    camera.pan(fx - lastFocusX, fy - lastFocusY)
                    lastFocusX = fx; lastFocusY = fy
                } else if (pan) {
                    camera.pan(event.x - lastX, event.y - lastY)
                } else if (pointers == 1) {
                    camera.orbit((event.x - lastX) * 0.4f, (event.y - lastY) * 0.4f)
                }
                lastX = event.x; lastY = event.y
                requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointers = 0
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) { camera.zoom(if (v > 0) 1.15f else 1 / 1.15f); requestRender(); return true }
        }
        return super.onGenericMotionEvent(event)
    }
}

private class PreviewRenderer(private val camera: Camera) : GLSurfaceView.Renderer {
    @Volatile var background = floatArrayOf(0.12f, 0.13f, 0.15f)
    @Volatile var gridColor = floatArrayOf(0.4f, 0.42f, 0.46f)
    @Volatile var partColor = floatArrayOf(0.96f, 0.58f, 0.2f)
    /** Frame this mesh on the next draw (after the viewport size is known). */
    var fitPending: PreviewMesh? = null

    private var meshProgram = 0
    private var lineProgram = 0
    private var meshVbo = 0
    private var gridVbo = 0
    private var meshCount = 0
    private var gridCount = 0
    private var axisStart = 0
    private var pending: PreviewMesh? = null
    private var dirty = false
    private var hasSize = false
    private val mvp = FloatArray(16)

    fun setMesh(mesh: PreviewMesh?) {
        pending = mesh
        dirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glEnable(GL_DEPTH_TEST)
        meshProgram = program(MESH_VS, MESH_FS)
        lineProgram = program(Shaders.LINE_VS, Shaders.LINE_FS)
        val ids = IntArray(2)
        glGenBuffers(2, ids, 0)
        meshVbo = ids[0]; gridVbo = ids[1]
        // A new EGL context lost the buffers: upload the last mesh again.
        dirty = true
        meshCount = 0; gridCount = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        camera.setViewport(width, height)
        hasSize = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (dirty) upload()
        if (hasSize) fitPending?.let { camera.fit(it.min, it.max); fitPending = null }
        val bg = background
        glClearColor(bg[0], bg[1], bg[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        camera.viewProjection(mvp)

        if (gridCount > 0) {
            glUseProgram(lineProgram)
            glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
            glUniform3f(glGetUniformLocation(lineProgram, "uOffset"), 0f, 0f, 0f)
            glBindBuffer(GL_ARRAY_BUFFER, gridVbo)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
            val color = glGetUniformLocation(lineProgram, "uColor")
            glUniform3fv(color, 1, gridColor, 0)
            glDrawArrays(GL_LINES, 0, axisStart)
            glUniform3f(color, 0.9f, 0.25f, 0.25f)
            glDrawArrays(GL_LINES, axisStart, 2)
            glUniform3f(color, 0.3f, 0.8f, 0.3f)
            glDrawArrays(GL_LINES, axisStart + 2, 2)
        }
        if (meshCount > 0) {
            glUseProgram(meshProgram)
            glUniformMatrix4fv(glGetUniformLocation(meshProgram, "uMvp"), 1, false, mvp, 0)
            glUniform3fv(glGetUniformLocation(meshProgram, "uColor"), 1, partColor, 0)
            val eye = camera.towardsEye()
            glUniform3f(glGetUniformLocation(meshProgram, "uEye"), eye.x, eye.y, eye.z)
            glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
            glEnableVertexAttribArray(0)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
            glDrawArrays(GL_TRIANGLES, 0, meshCount)
            glDisableVertexAttribArray(1)
        }
    }

    private fun upload() {
        dirty = false
        val m = pending
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
        if (m == null) { meshCount = 0; gridCount = 0; return }
        m.data.position(0)
        glBufferData(GL_ARRAY_BUFFER, m.data.limit() * 4, m.data, GL_STATIC_DRAW)
        meshCount = m.triangles * 3
        uploadGrid(m)
    }

    /** 10 mm grid lines (50 mm for big models) at z = 0 under the model, then the two axes. */
    private fun uploadGrid(m: PreviewMesh) {
        val step = if (maxOf(m.size.x, m.size.y) > 400f) 50f else 10f
        val margin = maxOf(10f, maxOf(m.size.x, m.size.y) * 0.25f)
        val x0 = floor((m.min.x - margin) / step) * step; val x1 = ceil((m.max.x + margin) / step) * step
        val y0 = floor((m.min.y - margin) / step) * step; val y1 = ceil((m.max.y + margin) / step) * step
        val lines = ArrayList<Float>()
        var x = x0
        while (x <= x1 + 0.01f) { if (x != 0f) lines += listOf(x, y0, 0f, x, y1, 0f); x += step }
        var y = y0
        while (y <= y1 + 0.01f) { if (y != 0f) lines += listOf(x0, y, 0f, x1, y, 0f); y += step }
        axisStart = lines.size / 3
        // Axes from the origin, drawn on top of the grid lines they replace.
        lines += listOf(minOf(0f, x0), 0f, 0f, maxOf(x1, step), 0f, 0f)
        lines += listOf(0f, minOf(0f, y0), 0f, 0f, maxOf(y1, step), 0f)
        val a = lines.toFloatArray()
        val buf = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.flip() }
        glBindBuffer(GL_ARRAY_BUFFER, gridVbo)
        glBufferData(GL_ARRAY_BUFFER, a.size * 4, buf, GL_STATIC_DRAW)
        gridCount = a.size / 3
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
        glLinkProgram(p)
        return p
    }

    private companion object {
        const val MESH_VS = """#version 300 es
            uniform mat4 uMvp;
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            out vec3 vNormal;
            void main() {
                vNormal = aNormal;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        /** A key light from the camera's upper left plus a fixed fill, so every face reads. */
        const val MESH_FS = """#version 300 es
            precision mediump float;
            uniform vec3 uColor;
            uniform vec3 uEye;
            in vec3 vNormal;
            out vec4 color;
            void main() {
                vec3 n = normalize(vNormal);
                float key = abs(dot(n, normalize(uEye + vec3(0.0, 0.0, 0.6))));
                float fill = abs(dot(n, normalize(vec3(0.35, -0.55, 0.75))));
                color = vec4(uColor * (0.28 + 0.52 * key + 0.25 * fill), 1.0);
            }"""
    }
}
