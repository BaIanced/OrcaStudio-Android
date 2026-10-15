package app.orcaandroid.render

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Selection id of a copy in the scene mesh: object * stride + instance (the engine's INSTANCE_ID_STRIDE). */
const val INSTANCE_ID_STRIDE = 4096

/** Plate badge size and gap, as fractions of the bed depth (desktop: 16 and 3 mm per 200 mm, a bit bigger for touch). */
private const val BADGE_SIZE = 0.1f
private const val BADGE_GAP = 0.02f

/** Colours of the 3D view, following the app theme (RGB 0..1). */
data class ViewColors(
    val background: FloatArray = floatArrayOf(0.12f, 0.13f, 0.15f),
    val grid: FloatArray = floatArrayOf(0.4f, 0.42f, 0.46f),
    val activePlate: FloatArray = floatArrayOf(0.3f, 0.7f, 0.65f),
    val part: FloatArray = floatArrayOf(0.95f, 0.6f, 0.2f),
    val selected: FloatArray = floatArrayOf(0.3f, 0.75f, 0.95f),
)

/** How the sliced toolpaths are coloured and which parts are shown. */
data class PreviewStyle(
    /** 0 feature type, 1 speed, 2 height, 3 width, 4 fan, 5 temperature, 6 volumetric rate, 7 filament. */
    val scheme: Int = 0,
    val hiddenRoles: Int = 0,
    val range: Pair<Float, Float> = 0f to 1f,
    val showTravels: Boolean = false,
    val showRetracts: Boolean = false,
    val showSeams: Boolean = false,
    val filamentColors: List<FloatArray> = emptyList(),
)

/** Visible slice of the preview buffers: [start, end) per buffer. */
data class PreviewRange(val extrusions: IntRange = IntRange.EMPTY, val travels: IntRange = IntRange.EMPTY, val markers: IntRange = IntRange.EMPTY)

/**
 * Renders the plates, the scene (objects, modifiers, paint) and the sliced toolpaths. Scene
 * setters are called on the GL thread (GLSurfaceView.queueEvent); the camera is thread-safe.
 */
class PlateRenderer(val camera: Camera) : GLSurfaceView.Renderer {

    // --- Scene state (GL thread) ---
    var colors = ViewColors()
    private var bedOutline = FloatArray(0)
    private var plateOrigins = listOf(0f to 0f)
    var activePlate = 0
    /** Selection ids (object * INSTANCE_ID_STRIDE + instance) of the selected copies. */
    var selectedIds = FloatArray(0)
    /** Live offset of the selected copies while they are dragged. */
    var dragOffset = floatArrayOf(0f, 0f, 0f)
    var showPreview = false
    var previewOrigin = floatArrayOf(0f, 0f)
    var previewStyle = PreviewStyle()
    var previewRange = PreviewRange()
    /** Measurement points (world), drawn as markers and a connecting line. */
    var measurePoints: List<FloatArray> = emptyList()
    /** Height of the cut plane preview for the selected object's bounds (x0,y0,x1,y1,z), or null. */
    var cutPlane: FloatArray? = null
    /** Section view plane (nx, ny, nz, d): world points with n . p > d are cut away; null = off. */
    var section: FloatArray? = null
    var filamentColors: List<FloatArray> = emptyList()
    /** Plates known to the view; 0 until the first bed arrives (then the camera frames plate 1). */
    var plateOriginsCount = 0

    private var pendingMesh: FloatData? = null
    private var pendingPaint: FloatData? = null
    private var pendingPreview: PreviewData? = null
    private var bedDirty = true

    private var meshVbo = 0; private var meshCount = 0
    private var paintVbo = 0; private var paintCount = 0
    /** The OpenSCAD design as a see-through ghost on the plate (type 98, translucent pass). */
    private var ghostVbo = 0; private var ghostCount = 0
    private var pendingGhost: FloatData? = null
    private var extrVbo = 0; private var extrCount = 0
    private var travelVbo = 0; private var travelCount = 0
    private var markerVbo = 0; private var markerCount = 0
    private var bedVbo = 0; private var bedCount = 0; private var outlineStart = 0
    private var boxVbo = 0; private var boxIbo = 0
    private var scratchVbo = 0
    /** Plate badges: icons per plate (top down, right of the plate; empty = numbers only), atlas, quads. */
    private var badgeIcons: List<PlateBadge> = emptyList()
    private var atlas: BadgeAtlas? = null
    private var pendingAtlas: BadgeAtlas? = null
    private var atlasTex = 0
    private var badgeVbo = 0; private var badgeCount = 0
    private var badgesDirty = true
    private var badgesActive = -1
    /** Tap targets of the badges, replaced on the GL thread and read on the UI thread. */
    @Volatile private var badgeRects: List<BadgeRect> = emptyList()

    private var meshProgram = 0
    private var lineProgram = 0
    private var pathProgram = 0
    private var pointProgram = 0
    private var badgeProgram = 0
    private val mvp = FloatArray(16)

    fun setBed(outline: FloatArray, origins: List<Pair<Float, Float>>) {
        bedOutline = outline
        plateOrigins = origins.ifEmpty { listOf(0f to 0f) }
        bedDirty = true
        badgesDirty = true
    }

    /** The badge art and which icons each plate shows (GL thread). */
    fun setBadges(atlas: BadgeAtlas, icons: List<PlateBadge>) {
        if (atlas !== this.atlas) pendingAtlas = atlas
        badgeIcons = icons
        badgesDirty = true
    }

    /** The plate badge under screen point (x, y), or null. */
    fun badgeAt(x: Float, y: Float): Pair<Int, PlateBadge>? {
        val p = camera.rayOnPlane(x, y) ?: return null
        return badgeRects.firstOrNull { p.x in it.x0..it.x1 && p.y in it.y0..it.y1 }?.let { it.plate to it.badge }
    }

    fun setMesh(data: FloatData?) { pendingMesh = data ?: FloatData(FloatData.directFloats(0), 8) }
    fun setGhost(data: FloatData?) { pendingGhost = data ?: FloatData(FloatData.directFloats(0), 8) }
    fun setPaint(data: FloatData?) { pendingPaint = data ?: FloatData(FloatData.directFloats(0), 8) }
    fun setPreview(data: PreviewData?) {
        pendingPreview = data
        if (data == null) { extrCount = 0; travelCount = 0; markerCount = 0 }
    }

    /** The plate under screen point (x, y), or null. */
    fun plateAt(x: Float, y: Float): Int? {
        if (bedOutline.size < 4) return null
        val p = camera.rayOnPlane(x, y) ?: return null
        val xs = bedOutline.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bedOutline.filterIndexed { i, _ -> i % 2 == 1 }
        return plateOrigins.indexOfFirst { (ox, oy) -> p.x in xs.min() + ox..xs.max() + ox && p.y in ys.min() + oy..ys.max() + oy }
            .takeIf { it >= 0 }
    }

    /** Whether plate [index]'s centre is on screen (then making it active leaves the camera alone). */
    fun plateInView(index: Int): Boolean {
        if (bedOutline.size < 4 || index !in plateOrigins.indices) return false
        val xs = bedOutline.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bedOutline.filterIndexed { i, _ -> i % 2 == 1 }
        val (ox, oy) = plateOrigins[index]
        val (w, h) = camera.viewportSize()
        val (px, py) = camera.project(app.orcaandroid.core.Vec3(ox + (xs.min() + xs.max()) / 2, oy + (ys.min() + ys.max()) / 2, 0f)) ?: return false
        return px in 0f..w.toFloat() && py in 0f..h.toFloat()
    }

    /** Frames plate [index] (or all plates for -1). */
    private var lastFrame: Pair<Int, Boolean>? = null

    fun frame(index: Int, top: Boolean = false) {
        lastFrame = index to top
        if (bedOutline.size < 4) return
        val xs = bedOutline.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bedOutline.filterIndexed { i, _ -> i % 2 == 1 }
        val origins = if (index in plateOrigins.indices) listOf(plateOrigins[index]) else plateOrigins
        camera.frame(xs.min() + origins.minOf { it.first }, ys.min() + origins.minOf { it.second },
            xs.max() + origins.maxOf { it.first }, ys.max() + origins.maxOf { it.second }, top)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glEnable(GL_DEPTH_TEST)
        meshProgram = program(Shaders.MESH_VS, Shaders.MESH_FS)
        lineProgram = program(Shaders.LINE_VS, Shaders.LINE_FS)
        pathProgram = program(Shaders.PATH_VS, Shaders.PATH_FS)
        pointProgram = program(Shaders.POINT_VS, Shaders.POINT_FS)
        badgeProgram = program(Shaders.BADGE_VS, Shaders.BADGE_FS)
        val ids = IntArray(11)
        glGenBuffers(11, ids, 0)
        ghostVbo = ids[9]; ghostCount = 0
        badgeVbo = ids[10]
        val tex = IntArray(1)
        glGenTextures(1, tex, 0)
        atlasTex = tex[0]
        if (pendingAtlas == null) pendingAtlas = atlas
        badgesDirty = true
        meshVbo = ids[0]; paintVbo = ids[1]; extrVbo = ids[2]; travelVbo = ids[3]; markerVbo = ids[4]
        bedVbo = ids[5]; boxVbo = ids[6]; boxIbo = ids[7]; scratchVbo = ids[8]
        uploadBox()
        // A new EGL context lost all buffers; everything is uploaded again by the host.
        bedDirty = true
        meshCount = 0; paintCount = 0; extrCount = 0; travelCount = 0; markerCount = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        camera.setViewport(width, height)
        // The framing depends on the aspect ratio; redo it for the new size (first layout, rotation).
        lastFrame?.let { (index, top) -> frame(index, top) }
    }

    override fun onDrawFrame(gl: GL10?) {
        upload()
        val bg = colors.background
        glClearColor(bg[0], bg[1], bg[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        camera.viewProjection(mvp)

        drawBeds()
        drawBadges()
        if (showPreview) {
            drawPreview()
        } else {
            // The OpenSCAD design on the plate stands alone: the project's objects are hidden meanwhile.
            val models = ghostCount == 0
            if (models) drawMesh(meshVbo, meshCount, pass = 0)
            if (models && paintCount > 0) {
                glEnable(GL_POLYGON_OFFSET_FILL)
                glPolygonOffset(-1f, -2f)
                drawMesh(paintVbo, paintCount, pass = 2)
                glDisable(GL_POLYGON_OFFSET_FILL)
            }
            // Modifiers and support blockers/enforcers are drawn translucent on top.
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDepthMask(false)
            if (models) drawMesh(meshVbo, meshCount, pass = 1)
            drawMesh(ghostVbo, ghostCount, pass = 1)
            glDepthMask(true)
            glDisable(GL_BLEND)
        }
        drawOverlays()
    }

    // --- Upload --------------------------------------------------------------------------------

    private fun upload() {
        pendingMesh?.let { meshCount = uploadFloats(meshVbo, it); pendingMesh = null }
        pendingPaint?.let { paintCount = uploadFloats(paintVbo, it); pendingPaint = null }
        pendingGhost?.let { ghostCount = uploadFloats(ghostVbo, it); pendingGhost = null }
        pendingPreview?.let {
            extrCount = uploadPadded(extrVbo, it.extrusions, PreviewData.EXTRUSION_STRIDE)
            travelCount = uploadFloats(travelVbo, it.travels)
            markerCount = uploadFloats(markerVbo, it.markers)
            pendingPreview = null
        }
        if (bedDirty) {
            val lines = bedLines()
            glBindBuffer(GL_ARRAY_BUFFER, bedVbo)
            glBufferData(GL_ARRAY_BUFFER, lines.size * 4, floats(lines), GL_STATIC_DRAW)
            bedCount = lines.size / 3
            bedDirty = false
        }
        pendingAtlas?.let { a ->
            glBindTexture(GL_TEXTURE_2D, atlasTex)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, a.bitmap, 0)
            glGenerateMipmap(GL_TEXTURE_2D)
            atlas = a
            pendingAtlas = null
            badgesDirty = true
        }
        if (badgesDirty || badgesActive != activePlate) uploadBadges()
    }

    /**
     * Badge quads beside every plate, as on the desktop (PartPlate::calc_vertex_for_icons): the icons
     * down the right side from the top, the plate number at the bottom right. Sizes follow the bed depth.
     */
    private fun uploadBadges() {
        badgesDirty = false
        badgesActive = activePlate
        val a = atlas
        if (a == null || bedOutline.size < 4) { badgeCount = 0; badgeRects = emptyList(); return }
        val xs = bedOutline.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bedOutline.filterIndexed { i, _ -> i % 2 == 1 }
        val depth = ys.max() - ys.min()
        val s = depth * BADGE_SIZE
        val g = depth * BADGE_GAP
        val v = ArrayList<Float>()
        val rects = ArrayList<BadgeRect>()
        fun quad(plate: Int, badge: PlateBadge, cell: Int, x0: Float, y0: Float) {
            val (u0, v0, u1, v1) = a.uv(cell)
            val x1 = x0 + s; val y1 = y0 + s
            // Upright when seen from the front: the cell's top row lies on the far edge.
            v += listOf(x0, y0, 0f, u0, v1, x1, y0, 0f, u1, v1, x1, y1, 0f, u1, v0)
            v += listOf(x0, y0, 0f, u0, v1, x1, y1, 0f, u1, v0, x0, y1, 0f, u0, v0)
            rects += BadgeRect(plate, badge, x0 - g / 2, y0 - g / 2, x1 + g / 2, y1 + g / 2)
        }
        plateOrigins.forEachIndexed { i, (ox, oy) ->
            val x0 = xs.max() + ox + g
            badgeIcons.forEachIndexed { k, b -> quad(i, b, b.ordinal, x0, ys.max() + oy - k * (s + g) - s) }
            if (i < a.plates) quad(i, PlateBadge.NUMBER, BadgeAtlas.numberCell(i, i == activePlate), x0, ys.min() + oy)
        }
        val data = v.toFloatArray()
        glBindBuffer(GL_ARRAY_BUFFER, badgeVbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, floats(data), GL_STATIC_DRAW)
        badgeCount = data.size / 5
        badgeRects = rects
    }

    private fun uploadFloats(vbo: Int, data: FloatData): Int {
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, data.data.limit() * 4, data.data.position(0), GL_STATIC_DRAW)
        return data.count
    }

    /**
     * [data] between two far-away dummy records, so the toolpath shader can read each segment's
     * neighbours (previous / next record) from the same buffer to mitre the joints.
     */
    private fun uploadPadded(vbo: Int, data: FloatData, stride: Int): Int {
        val bytes = data.data.limit() * 4
        val pad = stride * 4
        val far = floats(FloatArray(stride) { -1e6f })
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, bytes + 2 * pad, null, GL_STATIC_DRAW)
        glBufferSubData(GL_ARRAY_BUFFER, 0, pad, far)
        glBufferSubData(GL_ARRAY_BUFFER, pad, bytes, data.data.position(0))
        glBufferSubData(GL_ARRAY_BUFFER, pad + bytes, pad, far.position(0))
        return data.count
    }

    /** 10 mm grid then the outline, as x,y,z line vertices in plate-local coordinates. */
    private fun bedLines(): FloatArray {
        val out = ArrayList<Float>()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) { out += listOf(x0, y0, 0f, x1, y1, 0f) }
        if (bedOutline.size >= 6) {
            val xs = bedOutline.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = bedOutline.filterIndexed { i, _ -> i % 2 == 1 }
            var x = (xs.min() / 10).toInt() * 10f + 10f
            while (x < xs.max()) { line(x, ys.min(), x, ys.max()); x += 10f }
            var y = (ys.min() / 10).toInt() * 10f + 10f
            while (y < ys.max()) { line(xs.min(), y, xs.max(), y); y += 10f }
            outlineStart = out.size / 3
            val n = bedOutline.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                line(bedOutline[2 * i], bedOutline[2 * i + 1], bedOutline[2 * j], bedOutline[2 * j + 1])
            }
        }
        return out.toFloatArray()
    }

    /** Unit box for instanced toolpaths: per vertex (u, side, vertical) + face normal (right, up, dir). */
    private fun uploadBox() {
        val v = ArrayList<Float>()
        val idx = ArrayList<Short>()
        fun face(corners: List<FloatArray>, n: FloatArray) {
            val base = (v.size / 6).toShort()
            corners.forEach { c -> v += c.toList(); v += n.toList() }
            idx += listOf(base, (base + 1).toShort(), (base + 2).toShort(), base, (base + 2).toShort(), (base + 3).toShort())
        }
        fun c(u: Float, s: Float, t: Float) = floatArrayOf(u, s, t)
        face(listOf(c(0f, -1f, 1f), c(1f, -1f, 1f), c(1f, 1f, 1f), c(0f, 1f, 1f)), floatArrayOf(0f, 1f, 0f))      // top
        face(listOf(c(0f, 1f, -1f), c(1f, 1f, -1f), c(1f, -1f, -1f), c(0f, -1f, -1f)), floatArrayOf(0f, -1f, 0f))  // bottom
        face(listOf(c(0f, 1f, -1f), c(0f, 1f, 1f), c(1f, 1f, 1f), c(1f, 1f, -1f)), floatArrayOf(1f, 0f, 0f))       // right
        face(listOf(c(1f, -1f, -1f), c(1f, -1f, 1f), c(0f, -1f, 1f), c(0f, -1f, -1f)), floatArrayOf(-1f, 0f, 0f))  // left
        face(listOf(c(1f, 1f, -1f), c(1f, 1f, 1f), c(1f, -1f, 1f), c(1f, -1f, -1f)), floatArrayOf(0f, 0f, 1f))     // end
        face(listOf(c(0f, -1f, -1f), c(0f, -1f, 1f), c(0f, 1f, 1f), c(0f, 1f, -1f)), floatArrayOf(0f, 0f, -1f))    // start
        glBindBuffer(GL_ARRAY_BUFFER, boxVbo)
        glBufferData(GL_ARRAY_BUFFER, v.size * 4, floats(v.toFloatArray()), GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx.toShortArray())
        ib.flip()
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, boxIbo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GL_STATIC_DRAW)
    }

    // --- Drawing -------------------------------------------------------------------------------

    private fun drawBeds() {
        if (bedCount == 0) return
        glUseProgram(lineProgram)
        glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, mvp, 0)
        setSection(lineProgram, on = false)
        glBindBuffer(GL_ARRAY_BUFFER, bedVbo)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        plateOrigins.forEachIndexed { i, (ox, oy) ->
            glUniform3f(loc(lineProgram, "uOffset"), ox, oy, 0f)
            glUniform3fv(loc(lineProgram, "uColor"), 1, colors.grid, 0)
            glDrawArrays(GL_LINES, 0, outlineStart)
            glUniform3fv(loc(lineProgram, "uColor"), 1, if (i == activePlate) colors.activePlate else colors.grid, 0)
            glLineWidth(if (i == activePlate) 3f else 1f)
            glDrawArrays(GL_LINES, outlineStart, bedCount - outlineStart)
            glLineWidth(1f)
        }
        glDisableVertexAttribArray(0)
    }

    private fun drawMesh(vbo: Int, count: Int, pass: Int) {
        if (count == 0) return
        glUseProgram(meshProgram)
        glUniformMatrix4fv(loc(meshProgram, "uMvp"), 1, false, mvp, 0)
        glUniform1i(loc(meshProgram, "uPass"), pass)
        glUniform1fv(loc(meshProgram, "uSelected"), Shaders.MAX_SELECTED, selectedIds.copyOf(Shaders.MAX_SELECTED), 0)
        glUniform1i(loc(meshProgram, "uSelectedCount"), minOf(selectedIds.size, Shaders.MAX_SELECTED))
        glUniform3fv(loc(meshProgram, "uDrag"), 1, dragOffset, 0)
        glUniform3fv(loc(meshProgram, "uPartColor"), 1, colors.part, 0)
        glUniform3fv(loc(meshProgram, "uSelectedColor"), 1, colors.selected, 0)
        camera.eye().let { glUniform3f(loc(meshProgram, "uEye"), it.x, it.y, it.z) }
        setFilamentColors(meshProgram)
        setSection(meshProgram)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        for (a in 0..2) glEnableVertexAttribArray(a)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, 0)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 32, 12)
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 32, 24)
        glDrawArrays(GL_TRIANGLES, 0, count)
        for (a in 0..2) glDisableVertexAttribArray(a)
    }

    private fun setFilamentColors(program: Int) {
        val arr = FloatArray(16 * 3) { 0.8f }
        filamentColors.take(16).forEachIndexed { i, c -> arr[i * 3] = c[0]; arr[i * 3 + 1] = c[1]; arr[i * 3 + 2] = c[2] }
        glUniform3fv(loc(program, "uFilamentColors"), 16, arr, 0)
    }

    /** The section view's cut for [program]; [on] false draws it whole (beds, overlays). */
    private fun setSection(program: Int, on: Boolean = true) {
        val s = section.takeIf { on }
        glUniform1i(loc(program, "uSectionOn"), if (s != null) 1 else 0)
        if (s != null) glUniform4fv(loc(program, "uSection"), 1, s, 0)
    }

    private fun drawPreview() {
        val style = previewStyle
        val range = previewRange
        // Extrusions: one instanced box per segment.
        val first = range.extrusions.first.coerceIn(0, extrCount)
        val last = (range.extrusions.last + 1).coerceIn(first, extrCount)
        if (last > first) {
            glUseProgram(pathProgram)
            glUniformMatrix4fv(loc(pathProgram, "uMvp"), 1, false, mvp, 0)
            glUniform3f(loc(pathProgram, "uOrigin"), previewOrigin[0], previewOrigin[1], 0f)
            glUniform1i(loc(pathProgram, "uScheme"), style.scheme)
            glUniform1i(loc(pathProgram, "uHidden"), style.hiddenRoles)
            glUniform2f(loc(pathProgram, "uRange"), style.range.first, style.range.second)
            glUniform3fv(loc(pathProgram, "uRoleColors"), 20, Shaders.ROLE_COLORS, 0)
            setFilamentColors(pathProgram)
            setSection(pathProgram)
            glBindBuffer(GL_ARRAY_BUFFER, boxVbo)
            glEnableVertexAttribArray(0)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
            glBindBuffer(GL_ARRAY_BUFFER, extrVbo)
            val stride = PreviewData.EXTRUSION_STRIDE * 4
            // Record 0 of the buffer is padding (uploadPadded), so segment i is record i + 1.
            val base = (first + 1) * stride
            val valueColumn = when (style.scheme) { 1 -> 9; 2 -> 8; 3 -> 7; 4 -> 10; 5 -> 11; 6 -> 12; 7 -> 13; else -> 6 }
            // aP0, aP1, aRole, aWidth, aHeight, aValue, then the previous and next segments' ends
            val attribs = listOf(2 to (3 to 0), 3 to (3 to 12), 4 to (1 to 24), 5 to (1 to 28), 6 to (1 to 32), 7 to (1 to valueColumn * 4),
                8 to (3 to -stride), 9 to (3 to 12 - stride), 10 to (3 to stride), 11 to (3 to stride + 12))
            for ((a, fmt) in attribs) {
                glEnableVertexAttribArray(a)
                glVertexAttribPointer(a, fmt.first, GL_FLOAT, false, stride, base + fmt.second)
                glVertexAttribDivisor(a, 1)
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, boxIbo)
            glDrawElementsInstanced(GL_TRIANGLES, 36, GL_UNSIGNED_SHORT, 0, last - first)
            for ((a, _) in attribs) { glVertexAttribDivisor(a, 0); glDisableVertexAttribArray(a) }
            glDisableVertexAttribArray(0)
            glDisableVertexAttribArray(1)
        }
        if (style.showTravels) {
            val tFirst = range.travels.first.coerceIn(0, travelCount)
            val tLast = (range.travels.last + 1).coerceIn(tFirst, travelCount)
            if (tLast > tFirst) {
                glUseProgram(lineProgram)
                glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, mvp, 0)
                glUniform3f(loc(lineProgram, "uOffset"), previewOrigin[0], previewOrigin[1], 0f)
                glUniform3f(loc(lineProgram, "uColor"), 0.2f, 0.45f, 1f)
                setSection(lineProgram)
                glBindBuffer(GL_ARRAY_BUFFER, travelVbo)
                glEnableVertexAttribArray(0)
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, tFirst * 24)
                glDrawArrays(GL_LINES, 0, (tLast - tFirst) * 2)
                glDisableVertexAttribArray(0)
            }
        }
        if (style.showRetracts || style.showSeams) {
            val mFirst = range.markers.first.coerceIn(0, markerCount)
            val mLast = (range.markers.last + 1).coerceIn(mFirst, markerCount)
            if (mLast > mFirst) {
                glUseProgram(pointProgram)
                glUniformMatrix4fv(loc(pointProgram, "uMvp"), 1, false, mvp, 0)
                glUniform3f(loc(pointProgram, "uOffset"), previewOrigin[0], previewOrigin[1], 0f)
                glUniform1i(loc(pointProgram, "uKinds"), (if (style.showRetracts) 0b0011 else 0) or (if (style.showSeams) 0b0100 else 0))
                setSection(pointProgram)
                glBindBuffer(GL_ARRAY_BUFFER, markerVbo)
                glEnableVertexAttribArray(0)
                glEnableVertexAttribArray(1)
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, mFirst * 16)
                glVertexAttribPointer(1, 1, GL_FLOAT, false, 16, mFirst * 16 + 12)
                glDrawArrays(GL_POINTS, 0, mLast - mFirst)
                glDisableVertexAttribArray(0)
                glDisableVertexAttribArray(1)
            }
        }
    }

    private fun drawBadges() {
        if (badgeCount == 0) return
        glUseProgram(badgeProgram)
        glUniformMatrix4fv(loc(badgeProgram, "uMvp"), 1, false, mvp, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, atlasTex)
        glUniform1i(loc(badgeProgram, "uAtlas"), 0)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glBindBuffer(GL_ARRAY_BUFFER, badgeVbo)
        glEnableVertexAttribArray(0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 20, 0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 20, 12)
        glDrawArrays(GL_TRIANGLES, 0, badgeCount)
        glDisableVertexAttribArray(1)
        glDisableVertexAttribArray(0)
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    /** Measurement line and cut plane outline, drawn without depth test so they stay visible. */
    private fun drawOverlays() {
        val lines = ArrayList<Float>()
        if (measurePoints.size >= 2) measurePoints.take(2).forEach { lines += it.toList() }
        cutPlane?.let { c ->
            val x0 = c[0]; val y0 = c[1]; val x1 = c[2]; val y1 = c[3]; val z = c[4]
            listOf(x0 to y0, x1 to y0, x1 to y0, x1 to y1, x1 to y1, x0 to y1, x0 to y1, x0 to y0).forEach { (x, y) -> lines += listOf(x, y, z) }
        }
        if (lines.isEmpty() && measurePoints.isEmpty()) return
        glDisable(GL_DEPTH_TEST)
        glUseProgram(lineProgram)
        glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, mvp, 0)
        glUniform3f(loc(lineProgram, "uOffset"), 0f, 0f, 0f)
        glUniform3f(loc(lineProgram, "uColor"), 1f, 0.3f, 0.3f)
        setSection(lineProgram, on = false)
        glLineWidth(3f)
        glBindBuffer(GL_ARRAY_BUFFER, scratchVbo)
        val data = (lines + measurePoints.flatMap { it.toList() }).toFloatArray()
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, floats(data), GL_STREAM_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        if (lines.isNotEmpty()) glDrawArrays(GL_LINES, 0, lines.size / 3)
        glLineWidth(1f)
        glDisableVertexAttribArray(0)
        if (measurePoints.isNotEmpty()) {
            glUseProgram(pointProgram)
            glUniformMatrix4fv(loc(pointProgram, "uMvp"), 1, false, mvp, 0)
            glUniform3f(loc(pointProgram, "uOffset"), 0f, 0f, 0f)
            glUniform1i(loc(pointProgram, "uKinds"), 0b10000)
            setSection(pointProgram, on = false)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, lines.size * 4)
            glVertexAttrib1f(1, 4f)
            glDrawArrays(GL_POINTS, 0, measurePoints.size)
            glDisableVertexAttribArray(0)
        }
        glEnable(GL_DEPTH_TEST)
    }

    private fun loc(program: Int, name: String) = glGetUniformLocation(program, name)

    private fun floats(a: FloatArray) = ByteBuffer.allocateDirect(maxOf(a.size, 1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.flip() }

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
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: " + glGetProgramInfoLog(p) }
        return p
    }
}
