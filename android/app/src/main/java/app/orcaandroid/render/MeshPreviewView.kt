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

/** A shape-builder box drawn over the preview: [selected] ones can be dragged, holes are always shown. */
data class PreviewBox(val id: Int, val min: Vec3, val max: Vec3, val selected: Boolean, val hole: Boolean)

/** Shape-builder input from the preview: picks and drags of [PreviewBox]es. */
interface PreviewPickListener {
    /** Tap on box [id] (null = empty space); [add] with Ctrl / Shift held. */
    fun onPick(id: Int?, add: Boolean)
    /** A drag of the selected boxes ended, moved by [dx], [dy] mm (snapped to 1 mm). */
    fun onMove(dx: Float, dy: Float)
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
    private var boxes: List<PreviewBox> = emptyList()
    var pickListener: PreviewPickListener? = null
    /** Box under the finger at ACTION_DOWN, the drag plane height and start point, and the drag state. */
    private var downBox: Int? = null
    private var downX = 0f
    private var downY = 0f
    private var dragZ = 0f
    private var dragStart: Vec3? = null
    private var dragging = false
    private var moved = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

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

    /** Shape-builder boxes over the mesh (empty outside the builder). */
    fun setBoxes(list: List<PreviewBox>) {
        if (list == boxes) return
        val wasEmpty = boxes.isEmpty() && mesh == null
        boxes = list
        queueEvent { renderer.setBoxes(list) }
        if (wasEmpty && list.isNotEmpty()) reframe() else requestRender()
    }

    fun reframe() {
        val m = mesh
        val b = boxes.takeIf { it.isNotEmpty() }
        if (m != null) queueEvent { renderer.fitPending = m.min to m.max; requestRender() }
        else if (b != null) {
            val min = Vec3(b.minOf { it.min.x }, b.minOf { it.min.y }, b.minOf { it.min.z })
            val max = Vec3(b.maxOf { it.max.x }, b.maxOf { it.max.y }, b.maxOf { it.max.z })
            queueEvent { renderer.fitPending = min to max; requestRender() }
        } else requestRender()
    }

    /** Nearest box the ray through (x, y) hits (slab test), or null. */
    private fun hit(x: Float, y: Float): PreviewBox? {
        val (o, d) = camera.ray(x, y)
        val os = floatArrayOf(o.x, o.y, o.z)
        val ds = floatArrayOf(d.x, d.y, d.z)
        var best: PreviewBox? = null
        var bestT = Float.MAX_VALUE
        for (b in boxes) {
            val mn = floatArrayOf(b.min.x, b.min.y, b.min.z)
            val mx = floatArrayOf(b.max.x, b.max.y, b.max.z)
            var t0 = -Float.MAX_VALUE
            var t1 = Float.MAX_VALUE
            var miss = false
            for (i in 0 until 3) {
                if (kotlin.math.abs(ds[i]) < 1e-9f) {
                    if (os[i] < mn[i] || os[i] > mx[i]) { miss = true; break }
                    continue
                }
                var a = (mn[i] - os[i]) / ds[i]
                var c = (mx[i] - os[i]) / ds[i]
                if (a > c) { val t = a; a = c; c = t }
                t0 = maxOf(t0, a); t1 = minOf(t1, c)
            }
            if (miss || t0 > t1 || t1 < 0f) continue
            // Selected boxes win near ties, so a picked shape inside a hole's box can be dragged.
            val t = maxOf(t0, 0f) - if (b.selected) 0.01f else 0f
            if (t < bestT) { bestT = t; best = b }
        }
        return best
    }

    /** Shape-builder picking and dragging; true when the event was used for it. */
    private fun shapeInput(event: MotionEvent): Boolean {
        val l = pickListener ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; dragging = false; moved = false; downBox = null
                if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) || event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) return false
                val b = hit(event.x, event.y) ?: return false
                downBox = b.id
                dragZ = b.min.z
                dragStart = camera.rayOnPlane(event.x, event.y, dragZ)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && kotlin.math.hypot(event.x - downX, event.y - downY) > slop) moved = true
                val id = downBox ?: return false
                if (event.pointerCount > 1) { downBox = null; return false }
                if (moved && !dragging) {
                    dragging = true
                    if (boxes.none { it.id == id && it.selected }) l.onPick(id, false)
                }
                if (dragging) {
                    val start = dragStart ?: return true
                    val p = camera.rayOnPlane(event.x, event.y, dragZ) ?: return true
                    val off = Vec3(Math.round(p.x - start.x).toFloat(), Math.round(p.y - start.y).toFloat(), 0f)
                    queueEvent { renderer.dragOffset = off }
                    requestRender()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val id = downBox
                downBox = null
                if (dragging) {
                    dragging = false
                    val start = dragStart
                    val p = camera.rayOnPlane(event.x, event.y, dragZ)
                    if (start != null && p != null) l.onMove(Math.round(p.x - start.x).toFloat(), Math.round(p.y - start.y).toFloat())
                    queueEvent { renderer.dragOffset = Vec3.ZERO }
                    requestRender()
                    return true
                }
                // A tap: picks the shape, or clears the selection on empty space.
                if (!moved) l.onPick(id, (event.metaState and (android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON)) != 0)
                return id != null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { queueEvent { renderer.dragOffset = Vec3.ZERO }; requestRender() }
                downBox = null; dragging = false
            }
        }
        return false
    }

    private fun similar(a: Vec3, b: Vec3): Boolean {
        val la = a.length(); val lb = b.length()
        return lb > 0f && la / lb in 0.8f..1.25f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The preview sits in a scrolling panel: keep the gesture.
        parent?.requestDisallowInterceptTouchEvent(true)
        // Shape builder: taps pick shapes, a drag that starts on one moves the selection.
        if (shapeInput(event)) {
            lastX = event.x; lastY = event.y
            return true
        }
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
    /** Frame these bounds on the next draw (after the viewport size is known). */
    var fitPending: Pair<Vec3, Vec3>? = null
    /** Live offset of the selected boxes while they are dragged. */
    var dragOffset = Vec3.ZERO
    private var boxList: List<PreviewBox> = emptyList()
    private var boxVbo = 0
    private var boxesDirty = false
    private var holeCount = 0
    private var selStart = 0
    private var selCount = 0

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

    fun setBoxes(list: List<PreviewBox>) {
        boxList = list
        boxesDirty = true
        // Without a mesh the grid follows the boxes.
        if (pending == null) dirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glEnable(GL_DEPTH_TEST)
        meshProgram = program(MESH_VS, MESH_FS)
        lineProgram = program(Shaders.LINE_VS, Shaders.LINE_FS)
        val ids = IntArray(3)
        glGenBuffers(3, ids, 0)
        meshVbo = ids[0]; gridVbo = ids[1]; boxVbo = ids[2]
        // A new EGL context lost the buffers: upload the last mesh again.
        dirty = true
        boxesDirty = true
        meshCount = 0; gridCount = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        camera.setViewport(width, height)
        hasSize = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (dirty) upload()
        if (boxesDirty) uploadBoxes()
        if (hasSize) fitPending?.let { camera.fit(it.first, it.second); fitPending = null }
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
        if (holeCount + selCount > 0) {
            // Box outlines on top: holes grey (the render shows them only as cuts), the selection blue.
            glDisable(GL_DEPTH_TEST)
            glUseProgram(lineProgram)
            glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
            glBindBuffer(GL_ARRAY_BUFFER, boxVbo)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
            val color = glGetUniformLocation(lineProgram, "uColor")
            val offset = glGetUniformLocation(lineProgram, "uOffset")
            glUniform3f(offset, 0f, 0f, 0f)
            glUniform3fv(color, 1, gridColor, 0)
            if (holeCount > 0) glDrawArrays(GL_LINES, 0, holeCount)
            val d = dragOffset
            glUniform3f(offset, d.x, d.y, d.z)
            glUniform3f(color, 0.2f, 0.55f, 1f)
            if (selCount > 0) glDrawArrays(GL_LINES, selStart, selCount)
            glEnable(GL_DEPTH_TEST)
        }
    }

    /** 12 edges per box: the unselected holes, then the selection. */
    private fun uploadBoxes() {
        boxesDirty = false
        val lines = ArrayList<Float>()
        fun box(b: PreviewBox) {
            val x = floatArrayOf(b.min.x, b.max.x)
            val y = floatArrayOf(b.min.y, b.max.y)
            val z = floatArrayOf(b.min.z, b.max.z)
            for (i in 0..1) for (j in 0..1) {
                lines += listOf(x[0], y[i], z[j], x[1], y[i], z[j])
                lines += listOf(x[i], y[0], z[j], x[i], y[1], z[j])
                lines += listOf(x[i], y[j], z[0], x[i], y[j], z[1])
            }
        }
        boxList.filter { !it.selected && it.hole }.forEach(::box)
        holeCount = lines.size / 3
        selStart = holeCount
        boxList.filter { it.selected }.forEach(::box)
        selCount = lines.size / 3 - selStart
        if (lines.isEmpty()) return
        val a = lines.toFloatArray()
        val buf = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.flip() }
        glBindBuffer(GL_ARRAY_BUFFER, boxVbo)
        glBufferData(GL_ARRAY_BUFFER, a.size * 4, buf, GL_STATIC_DRAW)
    }

    private fun upload() {
        dirty = false
        val m = pending
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
        if (m == null) {
            meshCount = 0
            // The shape builder's workplane without a mesh: around the boxes, else 100 mm.
            val b = boxList
            if (b.isEmpty()) uploadGrid(Vec3(-50f, -50f, 0f), Vec3(50f, 50f, 0f))
            else uploadGrid(Vec3(b.minOf { it.min.x }, b.minOf { it.min.y }, 0f), Vec3(b.maxOf { it.max.x }, b.maxOf { it.max.y }, 0f))
            return
        }
        m.data.position(0)
        glBufferData(GL_ARRAY_BUFFER, m.data.limit() * 4, m.data, GL_STATIC_DRAW)
        meshCount = m.triangles * 3
        uploadGrid(m.min, m.max)
    }

    /** 10 mm grid lines (50 mm for big models) at z = 0 under the model, then the two axes. */
    private fun uploadGrid(min: Vec3, max: Vec3) {
        val sx = max.x - min.x
        val sy = max.y - min.y
        val step = if (maxOf(sx, sy) > 400f) 50f else 10f
        val margin = maxOf(10f, maxOf(sx, sy) * 0.25f)
        val x0 = floor((min.x - margin) / step) * step; val x1 = ceil((max.x + margin) / step) * step
        val y0 = floor((min.y - margin) / step) * step; val y1 = ceil((max.y + margin) / step) * step
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
