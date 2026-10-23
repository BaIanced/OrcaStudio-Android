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

/** Contact shadow: geometry up to this height above the bed casts it, mm; darkness right under a part. */
private const val SHADOW_FADE_MM = 12f
private const val SHADOW_STRENGTH = 0.8f
/** Preview supersampling: samples per view pixel each way (4 a pixel), and how long the camera rests before it starts. */
private const val SUPERSAMPLE = 2
private const val STILL_NS = 200_000_000L
private const val SUPER_REFRESH_NS = 1_000_000_000L
/**
 * Temporal supersampling: jittered frames per still view, and the path shader's regime scale meanwhile. Below 1 the
 * lying cylinder's layer lines (1-2 px apart) came back as arcs (WSA 2026-10-20: 0.7 faint, 0.5 clear, 0.35 strong).
 */
private const val ACCUM_FRAMES = 32
private const val ACCUM_REGIME = 1f

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
    @Volatile var selectedIds = FloatArray(0)
    /** Paint shop: only the selected copies are drawn (objects, paint, shadows); the plates stay. */
    var solo = false
    /** Build workspace: only the build object is drawn (selection ids -1000 - volume, see Engine.setBuildObject). */
    var buildSolo = false
    /** Paint shop: filaments drawn grey (bit i = 0-based filament i). */
    var hiddenFilaments = 0
    /** A box to fit the camera to (current angles) once the surface has its size: the paint shop's entry framing. */
    var fitPending: Pair<app.orcaandroid.core.Vec3, app.orcaandroid.core.Vec3>? = null
    /** Live offset of the selected copies while they are dragged. */
    var dragOffset = floatArrayOf(0f, 0f, 0f)
        set(v) {
            field = v
            if (v[0] == 0f && v[1] == 0f) dragTower = -1
        }
    /** Live resize: the selection is drawn scaled by this (x, y, z) about [resizeAnchor] (its box's bottom centre). */
    var resizeScale = floatArrayOf(1f, 1f, 1f)
    var resizeAnchor = floatArrayOf(0f, 0f, 0f)
    /** Resize mode: the scale gizmo's handles ([ScaleGizmo]) on the selection box; [gizmoAxes] false shows only the corners. */
    var gizmo = false
    var gizmoAxes = true
    /** The handle being dragged: only it shows meanwhile, moved with the scale. */
    var gizmoGrab: ScaleGizmo.Handle? = null
    /** Half the side of a handle square, px. */
    var handlePx = 14f
    /** Rotate mode: the rotate gizmo's rings ([RotateGizmo]) round the selection box. */
    var rotateGizmo = false
    /** The ring being dragged (0 X, 1 Y, 2 Z) or -1: only it shows meanwhile, with spokes from [rotateFrom] to its turn. */
    var rotateAxis = -1
    var rotateFrom: app.orcaandroid.core.Vec3? = null
    /** Live rotate: the selection is drawn turned by this row-major 3x3 about [rotatePivot] (its box's centre); null = none. */
    var rotateMatrix: FloatArray? = null
    var rotatePivot = floatArrayOf(0f, 0f, 0f)
    /** The snap step in force on the dragged ring (0 free, [RotateGizmo.FINE_STEP] or [RotateGizmo.COARSE_STEP]): its marks show bolder. */
    var rotateStep = 0f
    /** Screen density (px per dp), for the rotate gizmo's tick lengths. */
    var density = 1f
    /** Plate whose prime tower (selection id -2 - plate) is being dragged, or -1: it takes the highlight and [dragOffset]. */
    var dragTower = -1
    var showPreview = false
    var previewOrigin = floatArrayOf(0f, 0f)
    var previewStyle = PreviewStyle()
    var previewRange = PreviewRange()
    /** Measurement points (world), drawn as markers and a connecting line. */
    var measurePoints: List<FloatArray> = emptyList()
    /** Height of the cut plane preview for the selected object's bounds (x0,y0,x1,y1,z), or null. */
    var cutPlane: FloatArray? = null
    /** Regions view: crease line segments (x, y, z per end, world), drawn over the model with depth test. */
    var creaseLines: FloatArray? = null
    /** Check mesh problem spots: x, y, z, kind (5 thin wall, 6 open edge, 7 self-intersection) per marker. */
    var meshMarkers: FloatArray? = null
    /** How far the checked copy has moved since [meshMarkers] were found. */
    var meshMarkerShift = floatArrayOf(0f, 0f, 0f)
    /** Cut tool: the plane as a disc (centre xyz, in-plane radius vectors u xyz and v xyz), drawn depth-tested; null = none. */
    var cutDisc: FloatArray? = null
    /** Cut tool: the plane (nx, ny, nz, d) the selected object shows as a red line on its surface; null = off. */
    var cutBand: FloatArray? = null
    /** Cut tool: tilt rings (X and Y only) round this centre xyz, radius of the plane disc; null = none. */
    var cutRings: FloatArray? = null

    /** Section view plane (nx, ny, nz, d): world points with n . p > d are cut away; null = off. */
    var section: FloatArray? = null
    var filamentColors: List<FloatArray> = emptyList()
    /** Plates known to the view; 0 until the first bed arrives (then the camera frames plate 1). */
    var plateOriginsCount = 0
    /** The shadow under objects (Prepare). */
    var contactShadows = true
    /** Set per frame: the lit mesh pass samples the real shadow map (a depth map was rendered this frame or before, and shadows are on). */
    private var useRealShadow = false
    /** Real shadow look (Layout lab): darkness 0-1, penumbra and depth bias in mm. */
    var shadowStrength = 0.5f
    var shadowSoftMm = 6f
    var shadowBiasMm = 2f
    /** Key light of the models (layout lab): azimuth about the plate from +X and elevation above the bed in degrees, strength 1 = as designed. */
    var keyAzimuth = -57.5f
    var keyElevation = 49f
    var keyStrength = 1f
    /** Support painting's "Highlight overhangs": > 0 tints the selected parts' faces overhanging by more than this many degrees. */
    var overhangHighlight = 0f
    private val keyDir get() = Math.toRadians(keyElevation.toDouble()).let { el ->
        Math.toRadians(keyAzimuth.toDouble()).let { az ->
            floatArrayOf((Math.cos(el) * Math.cos(az)).toFloat(), (Math.cos(el) * Math.sin(az)).toFloat(), Math.sin(el).toFloat())
        }
    }

    /** How the selection shows ([SelectionStyle] bits) and the outline width in px. */
    var selectionStyle = SelectionStyle.DEFAULT
    var outlinePx = 6f
    /** Bounds (x0, y0, z0, x1, y1, z1) of each selection id's parts in the scene mesh, for the bounding box. */
    @Volatile private var copyBounds: Map<Int, FloatArray> = emptyMap()

    /** Preview supersampling ([Supersample]). */
    var supersample = Supersample.STILL
    private val supersampler = Supersampler()
    /** Samples per view pixel each way in this frame (1 unless supersampled): px sizes (lines, points) scale by it. */
    private var pxScale = 1f
    /** The last frame's view-projection and when it last changed, for "the camera stopped". */
    private val lastMvp = FloatArray(16)
    private var movedAt = 0L
    /** What the supersampled frame in the target shows ([superKey]) and when it was drawn: an unchanged view only re-averages it. */
    private var superShown: List<Any?>? = null
    private var superAt = 0L
    /** Temporal supersampling when still (the 2x target is its fallback): what the sum shows, its frames, the next jitter. */
    private val accumulator = Accumulator()
    private var accumShown: List<Any?>? = null
    private var accumFrames = 0
    private var jitterIndex = 0
    /**
     * The path shader's view-pixel regimes go by uPxScale * this: while accumulating the samples' tent filter holds finer
     * patterns, so the layer profile and wave stay down to a smaller size (MCP view_set "regime" while tuning).
     */
    @Volatile var accumRegime = ACCUM_REGIME
    private var regimeScale = 1f
    /** False while the still frame is still being refined (the MCP screenshot waits for it). */
    @Volatile var settled = true
        private set

    private val effects = SceneEffects()
    private val selection = SelectionOverlay()
    /** Set by the view from the screen configuration (read on the GL thread, so volatile). */
    @Volatile var deviceLandscape = false
    private var frameLandscape = false
    private var viewW = 1
    private var viewH = 1
    /** x0, y0, x1, y1 of the scene mesh (null = empty) and a counter of its uploads, for the shadow. */
    private var meshBounds: FloatArray? = null
    private var meshVersion = 0
    private var previewVersion = 0

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
    private var bedVbo = 0; private var bedCount = 0
    /** The grid's quad: the bed outline's box, plate-local (x0, y0, x1, y1). */
    private var gridRect: FloatArray? = null
    private var boxVbo = 0; private var boxIbo = 0; private var boxIndexCount = 0
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
    private var gridProgram = 0
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

    /** Each plate's bed box (x0, y0, x1, y1); one unbounded box while no bed is known. */
    private fun bedRects(): List<FloatArray> {
        if (bedOutline.size < 4) return listOf(floatArrayOf(-1e9f, -1e9f, 1e9f, 1e9f))
        val xs = bedOutline.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bedOutline.filterIndexed { i, _ -> i % 2 == 1 }
        return plateOrigins.map { (ox, oy) -> floatArrayOf(xs.min() + ox, ys.min() + oy, xs.max() + ox, ys.max() + oy) }
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
        gridProgram = program(Shaders.GRID_VS, Shaders.GRID_FS)
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
        effects.create { vs, fs -> program(vs, fs) }
        selection.create { vs, fs -> program(vs, fs) }
        supersampler.create { vs, fs -> program(vs, fs) }
        accumulator.create { vs, fs -> program(vs, fs) }
        superShown = null; accumShown = null
        // A new EGL context lost all buffers; everything is uploaded again by the host.
        bedDirty = true
        meshCount = 0; paintCount = 0; extrCount = 0; travelCount = 0; markerCount = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        // Resizing the surface (pulling the panel handle) keeps the camera (user, 2026-10-15); only the first layout and a
        // rotation re-frame, since the framing depends on the aspect ratio.
        // The device orientation decides, not the surface's own aspect: a tall panel turns the canvas wide in portrait.
        val reframe = viewW <= 1 || deviceLandscape != frameLandscape
        frameLandscape = deviceLandscape
        viewW = width; viewH = height
        camera.setViewport(width, height)
        if (reframe) lastFrame?.let { (index, top) -> frame(index, top) }
    }

    /** Frame timing for the MCP perf tool. */
    val frameStats = FrameStats()

    override fun onDrawFrame(gl: GL10?) {
        val start = System.nanoTime()
        drawFrame()
        frameStats.record(start, System.nanoTime())
    }

    private fun drawFrame() {
        if (viewW > 1) fitPending?.let { (lo, hi) -> camera.fit(lo, hi, keepAngles = true); fitPending = null }
        upload()
        camera.viewProjection(mvp)
        val now = System.nanoTime()
        if (!mvp.contentEquals(lastMvp)) { mvp.copyInto(lastMvp); movedAt = now }
        // Preview only: its layer lines are what beats against the pixel grid. "Still" waits a moment, so a finger
        // resting mid-drag doesn't flip the look back and forth.
        val still = showPreview && supersample != Supersample.OFF && now - movedAt > STILL_NS
        val wantSuper = showPreview && when (supersample) {
            Supersample.ALWAYS -> true
            Supersample.STILL -> still
            else -> false
        }
        if (!showPreview) { supersampler.release(); accumulator.release(); superShown = null; accumShown = null }
        if (!still) accumShown = null
        if (still && accumulate(now)) { settled = accumFrames >= ACCUM_FRAMES; return }
        // Only the wait before a still view starts refining is unsettled (the 2x fallback is done in one frame).
        settled = !(showPreview && supersample != Supersample.OFF && !still)
        // The view redraws continuously: while nothing changes, the frame already in the target is averaged again
        // instead of drawing 4 times the pixels. A fresh one now and then covers any state the key misses.
        val key = if (wantSuper) superKey() else null
        if (key != null && key == superShown && now - superAt < SUPER_REFRESH_NS) { supersampler.end(viewW, viewH); return }
        val supered = wantSuper && supersampler.begin(viewW, viewH, SUPERSAMPLE)
        pxScale = if (supered) supersampler.factor.toFloat() else 1f
        drawScene()
        if (supered) { supersampler.end(viewW, viewH); superShown = key; superAt = now } else superShown = null
        pxScale = 1f
    }

    /**
     * One step of temporal supersampling on a still Preview: a frame with the projection shifted by a sub-pixel jitter
     * mixed into the sum, until [ACCUM_FRAMES] are in; then the sum is shown as it is, with one more frame now and then
     * (weight 1/[ACCUM_FRAMES], so no restart flashes) for any state [superKey] misses. Any change of the key restarts.
     * False when the GPU can't (the 2x path then draws).
     */
    private fun accumulate(now: Long): Boolean {
        val key = superKey() + accumRegime
        if (key != accumShown) { accumFrames = 0; jitterIndex = 0 }
        if (accumFrames >= ACCUM_FRAMES && now - superAt < SUPER_REFRESH_NS) { accumulator.show(viewW, viewH); return true }
        if (!accumulator.begin(viewW, viewH)) return false
        // "Always" draws 2x again as soon as the camera moves: keep its target then.
        if (supersample != Supersample.ALWAYS) supersampler.release()
        superShown = null
        val (jx, jy) = Accumulator.jitter(jitterIndex % ACCUM_FRAMES)
        jitterIndex++
        // Clip x += dx * w moves the image by jx px (NDC spans 2 over the view); column-major, row 0/1 += d * row 3.
        val frame = mvp.copyOf()
        val dx = 2f * jx / viewW
        val dy = 2f * jy / viewH
        for (c in 0 until 4) { mvp[4 * c] += dx * mvp[4 * c + 3]; mvp[4 * c + 1] += dy * mvp[4 * c + 3] }
        regimeScale = accumRegime
        drawScene()
        regimeScale = 1f
        frame.copyInto(mvp)
        accumFrames = minOf(accumFrames + 1, ACCUM_FRAMES)
        accumulator.end(viewW, viewH, 1f / accumFrames)
        accumShown = key; superAt = now
        return true
    }

    /** Everything the Preview frame depends on (data classes compare by value, arrays by identity unless listed). */
    private fun superKey(): List<Any?> = listOf(mvp.toList(), viewW, viewH, previewVersion, previewStyle, previewRange,
        previewOrigin.toList(), section?.toList(), filamentColors, colors, measurePoints.map { it.toList() }, cutPlane?.toList(), cutDisc?.toList(),
        bedCount, plateOrigins, activePlate, badgeCount, atlas, gridRect?.toList())

    private fun drawScene() {
        val bg = colors.background
        glClearColor(bg[0], bg[1], bg[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        // The OpenSCAD design on the plate stands alone: the project's objects are hidden meanwhile.
        val models = ghostCount == 0
        val bounds = meshBounds
        val shadow = contactShadows && !showPreview && models && meshCount > 0 && bounds != null
        useRealShadow = false
        if (shadow) {
            effects.lightDir = keyDir
            val key = listOf(meshVersion, dragOffset.toList(), resizeScale.toList(), rotateMatrix?.toList(), selectedIds.toList(), dragTower, section?.toList(), keyAzimuth, keyElevation, solo, buildSolo)
            // A dragged copy may leave the footprint: widen it by the drag.
            // A fixed widening for the whole drag: any change of the map area mid-drag rescales the texels and
            // shows as a step ("strobe") in the shadow.
            val dragging = dragOffset[0] != 0f || dragOffset[1] != 0f
            val dx = if (dragging) 150f else 0f
            val dy = dx
            val area = floatArrayOf(bounds!![0] - dx, bounds[1] - dy, bounds[2] + dx, bounds[3] + dy)
            useRealShadow = false
            effects.renderShadow(area, key, viewW, viewH) { ortho ->
                val frame = mvp.copyOf()
                ortho.copyInto(mvp)
                drawMesh(meshVbo, meshCount, pass = if (effects.isRealShadow) 4 else 3)
                frame.copyInto(mvp)
            }
            useRealShadow = effects.realShadowReady
            // The shadow pass changed the clear colour.
            glClearColor(bg[0], bg[1], bg[2], 1f)
        }
        if (shadow) effects.drawShadow(mvp, if (effects.isRealShadow) shadowStrength else SHADOW_STRENGTH, shadowSoftMm, shadowBiasMm, bedRects())
        drawBeds()
        drawBadges()
        if (showPreview) {
            drawExtrusions()
            drawPreviewExtras()
        } else {
            if (models) drawMesh(meshVbo, meshCount, pass = 0)
            if (models && paintCount > 0) {
                glEnable(GL_POLYGON_OFFSET_FILL)
                glPolygonOffset(-1f, -2f)
                drawMesh(paintVbo, paintCount, pass = 2)
                glDisable(GL_POLYGON_OFFSET_FILL)
            }
            if (models) { drawCreases(); drawMeshMarkers() }
            // Modifiers and support blockers/enforcers are drawn translucent on top.
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDepthMask(false)
            if (models) drawMesh(meshVbo, meshCount, pass = 1)
            drawMesh(ghostVbo, ghostCount, pass = 1)
            glDepthMask(true)
            glDisable(GL_BLEND)
            if (models) drawSelection()
        }
        drawOverlays()
    }

    // --- Upload --------------------------------------------------------------------------------

    private fun upload() {
        pendingMesh?.let {
            meshCount = uploadFloats(meshVbo, it)
            meshBounds = footprint(it)
            copyBounds = copyBounds(it)
            meshVersion++
            pendingMesh = null
        }
        pendingPaint?.let {
            val start = android.os.SystemClock.elapsedRealtime()
            paintCount = uploadFloats(paintVbo, it)
            pendingPaint = null
            val upload = android.os.SystemClock.elapsedRealtime() - start
            if (upload > 10) android.util.Log.w("OrcaRender", "paint mesh upload $upload ms")
        }
        pendingGhost?.let { ghostCount = uploadFloats(ghostVbo, it); pendingGhost = null }
        pendingPreview?.let {
            extrCount = uploadPadded(extrVbo, it.extrusions, PreviewData.EXTRUSION_STRIDE)
            travelCount = uploadFloats(travelVbo, it.travels)
            markerCount = uploadFloats(markerVbo, it.markers)
            previewVersion++
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

    /** x0, y0, x1, y1 of the scene mesh's vertices (stride 8, position first), or null when empty. */
    private fun footprint(data: FloatData): FloatArray? {
        if (data.count == 0) return null
        val b = data.data
        val r = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until data.count) {
            val x = b.get(i * 8); val y = b.get(i * 8 + 1)
            if (x < r[0]) r[0] = x; if (y < r[1]) r[1] = y
            if (x > r[2]) r[2] = x; if (y > r[3]) r[3] = y
        }
        return r
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

    /** The bed outline as x,y,z line vertices in plate-local coordinates; sets [gridRect] (the grid is a shader quad). */
    private fun bedLines(): FloatArray {
        val out = ArrayList<Float>()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) { out += listOf(x0, y0, 0f, x1, y1, 0f) }
        gridRect = null
        if (bedOutline.size >= 6) {
            val xs = bedOutline.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = bedOutline.filterIndexed { i, _ -> i % 2 == 1 }
            gridRect = floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            val n = bedOutline.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                line(bedOutline[2 * i], bedOutline[2 * i + 1], bedOutline[2 * j], bedOutline[2 * j + 1])
            }
        }
        return out.toFloatArray()
    }

    /**
     * Unit box for instanced toolpaths: 12 shared corners, per vertex (u, side, vertical) + a face (right, up, dir weights).
     * Vertical: 1 the top, -1 the layer's bottom, -2 the bottom of the overlap into the layer below (PATH_VS); the walls
     * have a vertex ring at -1, so only the overlap is pushed back in depth. Faces share corners (the vertex shader runs
     * 12 times a segment, not 4 per face): each triangle ends on a corner carrying its face, read as flat (last vertex).
     */
    private fun uploadBox() {
        // Corner (ring r: 0 top, 1 layer bottom, 2 overlap bottom; k: 0 start/left, 1 start/right, 2 end/right, 3 end/left) = r * 4 + k.
        val us = floatArrayOf(0f, 0f, 1f, 1f)
        val ss = floatArrayOf(-1f, 1f, 1f, -1f)
        val ts = floatArrayOf(1f, -1f, -2f)
        val faceOf = mapOf(0 to floatArrayOf(0f, 1f, 0f), 8 to floatArrayOf(0f, -1f, 0f), 5 to floatArrayOf(1f, 0f, 0f),
            7 to floatArrayOf(-1f, 0f, 0f), 6 to floatArrayOf(0f, 0f, 1f), 4 to floatArrayOf(0f, 0f, -1f))
        val v = ArrayList<Float>()
        for (r in 0..2) for (k in 0..3) {
            v += listOf(us[k], ss[k], ts[r])
            v += (faceOf[r * 4 + k] ?: floatArrayOf(0f, 0f, 0f)).toList()
        }
        val idx = ArrayList<Short>()
        /** Quad a b c d (outward winding) as a fan ending on [f], the corner that carries the face. */
        fun quad(f: Int, vararg q: Int) {
            val i = q.indexOf(f)
            val p = IntArray(4) { q[(i + it) % 4] }
            listOf(p[1], p[2], f, p[2], p[3], f).forEach { idx += it.toShort() }
        }
        quad(0, 0, 3, 2, 1)                                  // top
        quad(8, 9, 10, 11, 8)                                // bottom
        quad(5, 5, 1, 2, 6); quad(5, 9, 5, 6, 10)            // right: layer, overlap
        quad(7, 7, 3, 0, 4); quad(7, 11, 7, 4, 8)            // left
        quad(6, 6, 2, 3, 7); quad(6, 10, 6, 7, 11)           // end
        quad(4, 4, 0, 1, 5); quad(4, 8, 4, 5, 9)             // start
        boxIndexCount = idx.size
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
        gridRect?.let { rect ->
            glUseProgram(gridProgram)
            glUniformMatrix4fv(loc(gridProgram, "uMvp"), 1, false, mvp, 0)
            glUniform4fv(loc(gridProgram, "uRect"), 1, rect, 0)
            glUniform3fv(loc(gridProgram, "uColor"), 1, colors.grid, 0)
            glUniform1f(loc(gridProgram, "uPxScale"), pxScale)
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDepthMask(false)
            for ((ox, oy) in plateOrigins) {
                glUniform3f(loc(gridProgram, "uOffset"), ox, oy, 0f)
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            }
            glDepthMask(true)
            glDisable(GL_BLEND)
        }
        glUseProgram(lineProgram)
        glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, mvp, 0)
        setSection(lineProgram, on = false)
        glBindBuffer(GL_ARRAY_BUFFER, bedVbo)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        plateOrigins.forEachIndexed { i, (ox, oy) ->
            glUniform3f(loc(lineProgram, "uOffset"), ox, oy, 0f)
            glUniform3fv(loc(lineProgram, "uColor"), 1, if (i == activePlate) colors.activePlate else colors.grid, 0)
            glLineWidth((if (i == activePlate) 3f else 1f) * pxScale)
            glDrawArrays(GL_LINES, 0, bedCount)
            glLineWidth(1f)
        }
        glDisableVertexAttribArray(0)
    }

    private fun drawMesh(vbo: Int, count: Int, pass: Int) {
        if (count == 0) return
        glUseProgram(meshProgram)
        glUniformMatrix4fv(loc(meshProgram, "uMvp"), 1, false, mvp, 0)
        glUniform1i(loc(meshProgram, "uPass"), pass)
        glUniform1f(loc(meshProgram, "uShadowFade"), SHADOW_FADE_MM)
        // The real shadow map is sampled by the lit pass only (not while it is being drawn, not in the occlusion pre-pass).
        val real = useRealShadow && pass == 0
        glUniform1i(loc(meshProgram, "uUseRealShadow"), if (real) 1 else 0)
        glUniform1i(loc(meshProgram, "uRealShadowMap"), 2)
        if (real) {
            glUniformMatrix4fv(loc(meshProgram, "uLightMvp"), 1, false, effects.lightMvp, 0)
            glUniform1f(loc(meshProgram, "uShadowStrength"), shadowStrength)
            glUniform1f(loc(meshProgram, "uSoft"), shadowSoftMm / effects.lightSpanMm)
            glUniform1f(loc(meshProgram, "uBiasMm"), shadowBiasMm)
            glActiveTexture(GL_TEXTURE2)
            glBindTexture(GL_TEXTURE_2D, effects.realShadowTex)
            glActiveTexture(GL_TEXTURE0)
        }
        val ids = highlighted
        glUniform1i(loc(meshProgram, "uSelStyle"), activeSelectionStyle)
        keyDir.let { glUniform3f(loc(meshProgram, "uKeyDir"), it[0], it[1], it[2]) }
        glUniform1f(loc(meshProgram, "uKeyStrength"), keyStrength)
        // TriangleSelector::select_patch's test: world normal z below -cos(angle); -2 = off.
        glUniform1f(loc(meshProgram, "uOverhangZ"), if (overhangHighlight > 0f) -Math.cos(Math.toRadians(overhangHighlight.toDouble())).toFloat() else -2f)
        cutBand.let { b ->
            glUniform1i(loc(meshProgram, "uCutBandOn"), if (b != null) 1 else 0)
            if (b != null) glUniform4f(loc(meshProgram, "uCutBand"), b[0], b[1], b[2], b[3])
        }
        glUniform1fv(loc(meshProgram, "uSelected"), Shaders.MAX_SELECTED, ids.copyOf(Shaders.MAX_SELECTED), 0)
        glUniform1i(loc(meshProgram, "uSelectedCount"), minOf(ids.size, Shaders.MAX_SELECTED))
        glUniform1i(loc(meshProgram, "uSolo"), if (buildSolo) 2 else if (solo && ids.isNotEmpty()) 1 else 0)
        glUniform1i(loc(meshProgram, "uHidden"), if (solo) hiddenFilaments else 0)
        glUniform3fv(loc(meshProgram, "uDrag"), 1, dragOffset, 0)
        glUniform3fv(loc(meshProgram, "uScaleAnchor"), 1, resizeAnchor, 0)
        glUniform3fv(loc(meshProgram, "uScale"), 1, resizeScale, 0)
        val rot = rotateMatrix
        glUniform1i(loc(meshProgram, "uRotOn"), if (rot != null) 1 else 0)
        if (rot != null) {
            // Column major for GL.
            glUniformMatrix3fv(loc(meshProgram, "uRot"), 1, false, floatArrayOf(rot[0], rot[3], rot[6], rot[1], rot[4], rot[7], rot[2], rot[5], rot[8]), 0)
            glUniform3fv(loc(meshProgram, "uRotPivot"), 1, rotatePivot, 0)
        }
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
        // Nothing left bound on unit 2: the next pass must not sample the map while it renders into it.
        if (real) { glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, 0); glActiveTexture(GL_TEXTURE0) }
    }

    /** Selection ids shown as selected: the dragged prime tower, else the selected copies. */
    private val highlighted get() = if (dragTower >= 0) floatArrayOf(-2f - dragTower) else selectedIds

    // The cut tool shows the object plain: its own red line and disc mark the cut, not the selection look.
    /** The prime tower is always shown x-ray outlined while touched, whatever the selection style setting (user r325). */
    private val activeSelectionStyle get() = if (cutBand != null) 0 else if (dragTower >= 0) SelectionStyle.OUTLINE or SelectionStyle.XRAY else selectionStyle

    /** Outline / x-ray and the bounding box of the selection (Prepare). */
    private fun drawSelection() {
        val ids = highlighted
        if (ids.isEmpty() || meshCount == 0) return
        val style = activeSelectionStyle
        if (style and (SelectionStyle.OUTLINE or SelectionStyle.XRAY) != 0)
            selection.draw(viewW, viewH, style, colors.selected, outlinePx) { pass -> drawMesh(meshVbo, meshCount, pass) }
        val box = selectionBox(ids) ?: return
        if (style and SelectionStyle.BOX != 0 || gizmo) drawSelectionBox(box.copyOf())
        if (gizmo) drawGizmo(box)
        if (rotateGizmo) drawRings(box)
    }

    /** The cut tool's tilt rings (X red, Y green) round the plane disc's centre. */
    private fun drawCutRings() {
        val g = cutRings ?: return
        val k = g[3] / Math.sqrt(3.0).toFloat()
        drawRings(floatArrayOf(g[0] - k, g[1] - k, g[2] - k, g[0] + k, g[1] + k, g[2] + k), 2)
    }

    /** The cut plane: a translucent disc and its rim, depth-tested so the object hides the part inside it. */
    private fun drawCutDisc() {
        val d = cutDisc ?: return
        val n = 64
        val pts = FloatArray((n + 2) * 3)
        pts[0] = d[0]; pts[1] = d[1]; pts[2] = d[2]
        for (i in 0..n) {
            val a = i * 2.0 * Math.PI / n
            val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
            for (k in 0..2) pts[(i + 1) * 3 + k] = d[k] + d[3 + k] * c + d[6 + k] * s
        }
        glUseProgram(lineProgram)
        glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, mvp, 0)
        glUniform3f(loc(lineProgram, "uOffset"), 0f, 0f, 0f)
        setSection(lineProgram, on = false)
        glBindBuffer(GL_ARRAY_BUFFER, scratchVbo)
        glBufferData(GL_ARRAY_BUFFER, pts.size * 4, floats(pts), GL_STREAM_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glEnable(GL_DEPTH_TEST)
        glDepthMask(false)
        glEnable(GL_BLEND)
        glBlendColor(0f, 0f, 0f, 0.2f)
        glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA)
        glUniform3f(loc(lineProgram, "uColor"), 1f, 0.25f, 0.25f)
        glDrawArrays(GL_TRIANGLE_FAN, 0, n + 2)
        glDisable(GL_BLEND)
        glLineWidth(3f * pxScale)
        glUniform3f(loc(lineProgram, "uColor"), 1f, 0.3f, 0.3f)
        glDrawArrays(GL_LINE_STRIP, 1, n + 1)
        glLineWidth(1f)
        glDepthMask(true)
        glDisableVertexAttribArray(0)
    }

    /**
     * The rotate gizmo's rings ([RotateGizmo]) round [box]'s centre, on top of the scene as the desktop draws them. While one
     * is dragged only it shows, thicker, with spokes from where the drag started to where it has turned.
     */
    private fun drawRings(box: FloatArray, axisCount: Int = 3) {
        val lo = app.orcaandroid.core.Vec3(box[0], box[1], box[2])
        val hi = app.orcaandroid.core.Vec3(box[3], box[4], box[5])
        val c = RotateGizmo.center(lo, hi)
        val r = RotateGizmo.radius(lo, hi, camera)
        val grab = rotateAxis
        glUseProgram(lineProgram)
        glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, mvp, 0)
        glUniform3fv(loc(lineProgram, "uOffset"), 1, floatArrayOf(0f, 0f, 0f), 0)
        setSection(lineProgram, on = false)
        glDisable(GL_DEPTH_TEST)
        glBindBuffer(GL_ARRAY_BUFFER, scratchVbo)
        glEnableVertexAttribArray(0)
        fun draw(points: List<app.orcaandroid.core.Vec3>, mode: Int, color: FloatArray, width: Float) {
            val data = FloatArray(points.size * 3).also { d -> points.forEachIndexed { i, p -> d[i * 3] = p.x; d[i * 3 + 1] = p.y; d[i * 3 + 2] = p.z } }
            glUniform3fv(loc(lineProgram, "uColor"), 1, color, 0)
            glLineWidth(width * pxScale)
            glBufferData(GL_ARRAY_BUFFER, data.size * 4, floats(data), GL_STREAM_DRAW)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
            glDrawArrays(mode, 0, points.size)
        }
        val dark = floatArrayOf(0.1f, 0.1f, 0.1f)
        for (axis in 0 until axisCount) {
            if (grab >= 0 && axis != grab) continue
            val ring = RotateGizmo.ring(axis, c, r)
            val w = if (grab >= 0) 4f else 3f
            // A dark rim under the colour, as the handles have.
            draw(ring, GL_LINE_STRIP, dark, w + 2f)
            draw(ring, GL_LINE_STRIP, RotateGizmo.color(axis), w)
        }
        val from = rotateFrom
        val m = rotateMatrix
        if (grab >= 0) {
            // The desktop's scale (a tick every 5 degrees, long every 10, just outside the ring) and its 45 degree snap
            // radii (r/3 to 2r/3), counted from where the drag started: a snapped spoke lands on a mark.
            val a0 = from?.takeIf { it.length() > 1e-6f }?.let { it * (1f / it.length()) } ?: RotateGizmo.basis(grab).first
            val px = camera.pxSize(c) * density
            val color = RotateGizmo.color(grab)
            val ticks = ArrayList<app.orcaandroid.core.Vec3>()
            for (k in 0 until (360f / RotateGizmo.FINE_STEP).toInt()) {
                val dir = RotateGizmo.apply(RotateGizmo.matrix(grab, k * RotateGizmo.FINE_STEP), a0)
                ticks += c + dir * r
                ticks += c + dir * (r + px * (if (k % 2 == 0) 10f else 5f))
            }
            draw(ticks, GL_LINES, color, if (rotateStep == RotateGizmo.FINE_STEP) 2.5f else 1.5f)
            val spokes = ArrayList<app.orcaandroid.core.Vec3>()
            for (k in 0 until (360f / RotateGizmo.COARSE_STEP).toInt()) {
                val dir = RotateGizmo.apply(RotateGizmo.matrix(grab, k * RotateGizmo.COARSE_STEP), a0)
                spokes += c + dir * (r / 3f)
                spokes += c + dir * (r * 2f / 3f)
            }
            draw(spokes, GL_LINES, color, if (rotateStep == RotateGizmo.COARSE_STEP) 3f else 1.5f)
        }
        if (grab >= 0 && from != null && from.length() > 1e-6f) {
            val a = from * (r / from.length())
            val b = if (m != null) RotateGizmo.apply(m, a) else a
            draw(listOf(c, c + a, c, c + b), GL_LINES, floatArrayOf(0.85f, 0.85f, 0.85f), 2f)
        }
        glDisableVertexAttribArray(0)
        glLineWidth(1f)
        glEnable(GL_DEPTH_TEST)
    }

    /** The mesh bounding box (x0, y0, z0, x1, y1, z1) of the selection ids [ids], without the live resize; any thread. */
    private fun meshBox(ids: FloatArray): FloatArray? {
        val bounds = copyBounds
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var any = false
        for (id in ids) {
            val c = bounds[Math.round(id)] ?: continue
            any = true
            for (k in 0..2) { b[k] = minOf(b[k], c[k]); b[k + 3] = maxOf(b[k + 3], c[k + 3]) }
        }
        return b.takeIf { any }
    }

    /** The box the gizmos sit on: the selection's mesh box (the Build workspace's shapes have no box of their own); any thread. */
    fun selectionBounds(): Pair<app.orcaandroid.core.Vec3, app.orcaandroid.core.Vec3>? = meshBox(selectedIds)?.let { b ->
        app.orcaandroid.core.Vec3(b[0], b[1], b[2]) to app.orcaandroid.core.Vec3(b[3], b[4], b[5])
    }

    /** The bounding box (x0, y0, z0, x1, y1, z1) of the selection ids [ids], with the live resize. */
    private fun selectionBox(ids: FloatArray): FloatArray? {
        val b = meshBox(ids) ?: return null
        for (k in 0..2) {
            val s = resizeScale[k]
            if (s != 1f) { b[k] = resizeAnchor[k] + (b[k] - resizeAnchor[k]) * s; b[k + 3] = resizeAnchor[k] + (b[k + 3] - resizeAnchor[k]) * s }
        }
        return b
    }

    /**
     * The scale gizmo's handles ([ScaleGizmo]) on [box] as screen-size squares on top of the scene (the desktop draws its
     * grabbers on top too; only those the box doesn't hide are shown). While one is dragged, only it shows, moved with the scale.
     */
    private fun drawGizmo(box: FloatArray) {
        val lo = app.orcaandroid.core.Vec3(box[0], box[1], box[2])
        val hi = app.orcaandroid.core.Vec3(box[3], box[4], box[5])
        val grab = gizmoGrab
        val handles = if (grab == null) ScaleGizmo.visible(lo, hi, camera, gizmoAxes) else {
            val p = resizeAnchor
            val q = grab.pos
            listOf(ScaleGizmo.Handle(grab.index, app.orcaandroid.core.Vec3(
                p[0] + (q.x - p[0]) * resizeScale[0], p[1] + (q.y - p[1]) * resizeScale[1], p[2] + (q.z - p[2]) * resizeScale[2]), grab.axis))
        }
        if (handles.isEmpty()) return
        val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        glUseProgram(lineProgram)
        glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, identity, 0)
        glUniform3fv(loc(lineProgram, "uOffset"), 1, floatArrayOf(0f, 0f, 0f), 0)
        setSection(lineProgram, on = false)
        glDisable(GL_DEPTH_TEST)
        glBindBuffer(GL_ARRAY_BUFFER, scratchVbo)
        glEnableVertexAttribArray(0)
        val clip = FloatArray(4)
        for (h in handles) {
            android.opengl.Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(h.pos.x, h.pos.y, h.pos.z, 1f), 0)
            if (clip[3] <= 0f) continue
            val x = clip[0] / clip[3]
            val y = clip[1] / clip[3]
            // A dark rim, then the colour; the dragged handle a little bigger.
            val size = if (grab != null) handlePx * 1.25f else handlePx
            for ((px, color) in listOf(size + outlinePx * 0.6f to floatArrayOf(0.1f, 0.1f, 0.1f), size to ScaleGizmo.color(h.axis))) {
                val hx = px * 2f / viewW
                val hy = px * 2f / viewH
                val quad = floatArrayOf(x - hx, y - hy, 0f, x + hx, y - hy, 0f, x + hx, y + hy, 0f, x - hx, y - hy, 0f, x + hx, y + hy, 0f, x - hx, y + hy, 0f)
                glUniform3fv(loc(lineProgram, "uColor"), 1, color, 0)
                glBufferData(GL_ARRAY_BUFFER, quad.size * 4, floats(quad), GL_STREAM_DRAW)
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
                glDrawArrays(GL_TRIANGLES, 0, 6)
            }
        }
        glDisableVertexAttribArray(0)
        glEnable(GL_DEPTH_TEST)
    }

    /**
     * Corner brackets of the selection's bounding box [b], as on the desktop (Selection::render_bounding_box): at each corner
     * three strokes a quarter of the shortest side long, moved with the drag.
     */
    private fun drawSelectionBox(b: FloatArray) {
        // A little off the surface, so the strokes don't sink into faces flush with the box.
        val pad = 0.5f
        for (k in 0..2) { b[k] -= pad; b[k + 3] += pad }
        val len = minOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]).coerceAtLeast(1f) * 0.25f
        val lines = ArrayList<Float>(8 * 3 * 6)
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            val p = floatArrayOf(b[cx * 3], b[1 + cy * 3], b[2 + cz * 3])
            val dir = floatArrayOf(if (cx == 0) 1f else -1f, if (cy == 0) 1f else -1f, if (cz == 0) 1f else -1f)
            for (axis in 0..2) {
                val q = p.copyOf(); q[axis] += dir[axis] * len
                lines += p.toList(); lines += q.toList()
            }
        }
        glUseProgram(lineProgram)
        glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, mvp, 0)
        glUniform3fv(loc(lineProgram, "uOffset"), 1, dragOffset, 0)
        glUniform3fv(loc(lineProgram, "uColor"), 1, colors.selected, 0)
        setSection(lineProgram, on = false)
        glLineWidth(3f * pxScale)
        glBindBuffer(GL_ARRAY_BUFFER, scratchVbo)
        val data = lines.toFloatArray()
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, floats(data), GL_STREAM_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glDrawArrays(GL_LINES, 0, data.size / 3)
        glDisableVertexAttribArray(0)
        glLineWidth(1f)
    }

    /** Bounds of the parts (type 0, 100+) and prime towers (99) per selection id; stride 8 (position, normal, id, type). */
    private fun copyBounds(data: FloatData): Map<Int, FloatArray> {
        val out = HashMap<Int, FloatArray>()
        val f = data.data
        for (i in 0 until data.count) {
            val type = Math.round(f.get(i * 8 + 7))
            if (type != 0 && type != 99 && type < 100) continue
            val r = out.getOrPut(Math.round(f.get(i * 8 + 6))) {
                floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            }
            for (k in 0..2) {
                val v = f.get(i * 8 + k)
                if (v < r[k]) r[k] = v
                if (v > r[k + 3]) r[k + 3] = v
            }
        }
        return out
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

    private fun drawExtrusions() {
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
            glUniform1i(loc(pathProgram, "uFirst"), first)
            glUniform1f(loc(pathProgram, "uPxScale"), pxScale * regimeScale)
            camera.eye().let { glUniform3f(loc(pathProgram, "uEye"), it.x, it.y, it.z) }
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
            glDrawElementsInstanced(GL_TRIANGLES, boxIndexCount, GL_UNSIGNED_SHORT, 0, last - first)
            for ((a, _) in attribs) { glVertexAttribDivisor(a, 0); glDisableVertexAttribArray(a) }
            glDisableVertexAttribArray(0)
            glDisableVertexAttribArray(1)
        }
    }

    /** Travels and markers over the toolpaths. */
    private fun drawPreviewExtras() {
        val style = previewStyle
        val range = previewRange
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
                glLineWidth(pxScale)
                glDrawArrays(GL_LINES, 0, (tLast - tFirst) * 2)
                glLineWidth(1f)
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
                glUniform1f(loc(pointProgram, "uPxScale"), pxScale)
                glUniform1i(loc(pointProgram, "uKinds"), (if (style.showRetracts) 0b0011 else 0) or (if (style.showSeams) 0b0100 else 0))
                camera.eye().let { glUniform3f(loc(pointProgram, "uEye"), it.x, it.y, it.z) }
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

    /** Regions view outlines: depth-tested, pulled a hair toward the eye so they win over the faces they lie on. */
    private fun drawCreases() {
        val data = creaseLines?.takeIf { it.isNotEmpty() } ?: return
        glEnable(GL_DEPTH_TEST)
        glDepthRangef(0f, 0.9998f)
        glUseProgram(lineProgram)
        glUniformMatrix4fv(loc(lineProgram, "uMvp"), 1, false, mvp, 0)
        glUniform3f(loc(lineProgram, "uOffset"), 0f, 0f, 0f)
        glUniform3f(loc(lineProgram, "uColor"), 0.08f, 0.08f, 0.1f)
        setSection(lineProgram)
        glLineWidth(2f * pxScale)
        glBindBuffer(GL_ARRAY_BUFFER, scratchVbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, floats(data), GL_STREAM_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glDrawArrays(GL_LINES, 0, data.size / 3)
        glDisableVertexAttribArray(0)
        glLineWidth(1f)
        glDepthRangef(0f, 1f)
    }

    /** Measurement line and cut plane outline, drawn without depth test so they stay visible. */
    private fun drawMeshMarkers() {
        val data = meshMarkers?.takeIf { it.isNotEmpty() } ?: return
        // A live turn or resize moves the copy's surface but not the spots: hide them until it is applied.
        if (rotateMatrix != null || resizeScale.any { it != 1f }) return
        glEnable(GL_DEPTH_TEST)
        glUseProgram(pointProgram)
        glUniformMatrix4fv(loc(pointProgram, "uMvp"), 1, false, mvp, 0)
        glUniform3f(loc(pointProgram, "uOffset"), dragOffset[0] + meshMarkerShift[0], dragOffset[1] + meshMarkerShift[1], dragOffset[2] + meshMarkerShift[2])
        camera.eye().let { glUniform3f(loc(pointProgram, "uEye"), it.x, it.y, it.z) }
        glUniform1i(loc(pointProgram, "uKinds"), 0b11100000)
        glUniform1f(loc(pointProgram, "uPxScale"), pxScale)
        setSection(pointProgram)
        glBindBuffer(GL_ARRAY_BUFFER, scratchVbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, floats(data), GL_STREAM_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 16, 12)
        glDrawArrays(GL_POINTS, 0, data.size / 4)
        glDisableVertexAttribArray(0)
        glDisableVertexAttribArray(1)
    }

    private fun drawOverlays() {
        drawCutDisc()
        if (cutRings != null) drawCutRings()
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
        glLineWidth(3f * pxScale)
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
            glUniform1f(loc(pointProgram, "uPxScale"), pxScale)
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
