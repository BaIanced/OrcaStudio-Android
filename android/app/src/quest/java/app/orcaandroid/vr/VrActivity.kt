package app.orcaandroid.vr

import android.app.Activity
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.orcaandroid.OrcaApp
import app.orcaandroid.core.Vec3
import app.orcaandroid.render.Camera
import app.orcaandroid.render.FloatData
import app.orcaandroid.render.PlateRenderer
import app.orcaandroid.render.ViewSource
import app.orcaandroid.ui.components.hexToRgb
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.tan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The real plate in stereo (M1, docs/handoff/NOTES-vr.md): the project drawn by [PlateRenderer] as a static, world-locked
 * tabletop at 1:1. No app-driven view motion: only the head moves the view. Kotlin owns the EGL context and render thread.
 */
class VrActivity : Activity() {
    @Volatile private var stop = false
    private var thread: Thread? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Scene results from the loader, applied on the render thread (the renderer's setters must run there). */
    private val pending = ConcurrentLinkedQueue<PlateRenderer.() -> Unit>()
    /** Plate to LOCAL space; only the render thread touches it. */
    private val world = FloatArray(16).also { VrMath.plateToLocal(it, 0f, 0f) }
    private var plateX = 0f
    private var plateY = 0f
    /** Where the user put the plate; only the render thread touches it. */
    private val grab = PlateGrab()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPlacement()
        thread = Thread(::renderThread, "OrcaXrRender").also { it.start() }
    }

    override fun onDestroy() {
        stop = true
        scope.cancel()
        // The thread can be inside xrWaitFrame; don't hold the main thread for long.
        thread?.join(JOIN_TIMEOUT_MS)
        super.onDestroy()
    }

    private fun renderThread() {
        val egl = EglHolder()
        try {
            val created = XrShim.nativeCreate(this, egl.display.nativeHandle, egl.config.nativeHandle, egl.context.nativeHandle)
            if (created) {
                frameLoop()
                XrShim.nativeDestroy()
            } else {
                Log.e(TAG, "OpenXR setup failed, see the lines above")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "OpenXR shim did not load", e)
        } finally {
            egl.release()
        }
        runOnUiThread { finishAndRemoveTask() }
    }

    /**
     * Loads the project into the engine, in the startup order of AppViewModel.start. Settings and the session file are only
     * read. A 2D app already up in this process owns the engine, so VR leaves (M2 decides how the two share it).
     */
    private fun loadScene() = scope.launch {
        runCatching {
            val container = (application as OrcaApp).container
            val engine = container.engine
            // The process outlives the activity, so a relaunch finds the engine VR itself started: that one reloads.
            if (engine.initialized && !vrOwnsEngine) {
                Log.e(TAG, "VR_SCENE the 2D app owns the engine, leaving")
                stop = true
                return@launch
            }
            vrOwnsEngine = true
            container.resources.prepare()
            engine.init(container.resources.resourcesDir, container.resources.dataDir)
            val printers = engine.loadPresets()
            val session = File(filesDir, "session/session.3mf")
            val project = projectExtra() ?: session
            val last = printers.firstOrNull { it.name == container.settings.lastPrinter }?.name ?: printers.firstOrNull()?.name
            if (last == null) {
                Log.e(TAG, "VR_SCENE no printer installed, open the 2D app once")
                stop = true
                return@launch
            }
            // As in the 2D start: a printer is selected before the project loads, then the project's own if it is installed.
            var bed = engine.selectPrinter(last).bed
            // The bed shows even if the project fails to load: never an empty void.
            showBed(bed, listOf(0f to 0f), 0)
            val info = if (project.exists()) engine.loadProject(project.path).second else null
            val own = printers.firstOrNull { it.name == info?.printer }?.name
            if (own != null) bed = engine.selectPrinter(own).bed
            val scene = engine.scene()
            val mesh = if (scene.meshFile.isEmpty()) null else FloatData.load(scene.meshFile, 8)
            val paint = if (scene.paintFile.isEmpty()) null else FloatData.load(scene.paintFile, 8)
            val origins = scene.plates.map { it.originX to it.originY }
            val active = if (project == session) savedActivePlate(session).coerceIn(0, (origins.size - 1).coerceAtLeast(0)) else 0
            val colors = info?.filamentColors.orEmpty().map { hexToRgb(it.ifBlank { null }) }
            Log.i(TAG, "VR_SCENE project ${project.exists()} plates ${origins.size} active $active vertices ${mesh?.count ?: 0}")
            showBed(bed, origins, active)
            pending += {
                filamentColors = colors
                setMesh(mesh, scene.meshVersion)
                setPaint(paint)
            }
        }.onFailure { Log.e(TAG, "VR_SCENE failed", it) }
    }

    /** Queues the beds and centres the world on plate [active]. */
    private fun showBed(bed: FloatArray, origins: List<Pair<Float, Float>>, active: Int) {
        val xs = bed.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bed.filterIndexed { i, _ -> i % 2 == 1 }
        val origin = origins.getOrElse(active) { 0f to 0f }
        val cx = origin.first + (xs.min() + xs.max()) / 2
        val cy = origin.second + (ys.min() + ys.max()) / 2
        pending += {
            setBed(bed, origins)
            plateOriginsCount = origins.size
            activePlate = active
            plateX = cx
            plateY = cy
            // Every plate counts as the grab target, not only the active one.
            grab.setBounds(
                origins.minOf { it.first } + xs.min() - cx, origins.minOf { it.second } + ys.min() - cy,
                origins.maxOf { it.first } + xs.max() - cx, origins.maxOf { it.second } + ys.max() - cy,
            )
            updateWorld()
        }
    }

    private fun updateWorld() = VrMath.plateToLocal(world, plateX, plateY, grab.x, grab.y, grab.z, grab.scale)

    private fun placementPrefs() = getSharedPreferences(PREFS_PLACEMENT, MODE_PRIVATE)

    /** `--ez resetPlacement true` brings a lost plate back. */
    private fun loadPlacement() {
        val prefs = placementPrefs()
        if (intent.getBooleanExtra(EXTRA_RESET_PLACEMENT, false)) prefs.edit().clear().apply()
        grab.place(
            prefs.getFloat("x", grab.x), prefs.getFloat("y", grab.y), prefs.getFloat("z", grab.z), prefs.getFloat("scale", grab.scale),
        )
    }

    private fun savePlacement() {
        placementPrefs().edit().putFloat("x", grab.x).putFloat("y", grab.y).putFloat("z", grab.z).putFloat("scale", grab.scale).apply()
        Log.i(TAG, "VR_PLACE x ${grab.x} y ${grab.y} z ${grab.z} scale ${grab.scale}")
    }

    /** `vr_tuning.json` in the app's external files dir, pushed over adb, applied live. Any field may be left out. */
    private fun loadTuning(file: File) {
        val json = runCatching { JSONObject(file.readText()) }.getOrElse {
            Log.e(TAG, "VR_TUNE unreadable, keeping the current values: ${it.message}")
            return
        }
        val t = grab.tuning
        fun read(name: String, now: Float) = json.optDouble(name, now.toDouble()).toFloat()
        t.minCutoffHz = read("minCutoffHz", t.minCutoffHz)
        t.beta = read("beta", t.beta)
        t.stickDeadZone = read("stickDeadZone", t.stickDeadZone)
        t.stickSmoothMs = read("stickSmoothMs", t.stickSmoothMs)
        t.stickCurve = read("stickCurve", t.stickCurve)
        t.distanceRate = read("distanceRate", t.distanceRate)
        t.zoomRate = read("zoomRate", t.zoomRate)
        t.axisLock = read("axisLock", t.axisLock)
        t.grabToggle = json.optBoolean("grabToggle", t.grabToggle)
        Log.i(TAG, "VR_TUNE applied $json")
    }

    /** `--es project <path>`, only from this app's own storage: the activity is exported, and the parser is native. */
    private fun projectExtra(): File? {
        val file = File(intent.getStringExtra(EXTRA_PROJECT) ?: return null).canonicalFile
        val allowed = listOfNotNull(filesDir, getExternalFilesDir(null)).map { it.canonicalPath + File.separator }
        if (allowed.none { file.path.startsWith(it) }) {
            Log.e(TAG, "VR_SCENE project outside the app's storage, using the session")
            return null
        }
        return file
    }

    private fun savedActivePlate(session: File): Int = runCatching {
        JSONObject(File(session.parentFile, "session.json").readText()).optInt("activePlate")
    }.getOrDefault(0)

    private fun frameLoop() {
        val renderer = PlateRenderer(Camera())
        // Both end on framebuffer 0, not on the eye's (drawEye).
        renderer.contactShadows = false
        renderer.onSurfaceCreated(null, null)
        loadScene()

        val views = FloatArray(XrShim.EYES * XrShim.FLOATS_PER_VIEW)
        val size = IntArray(2)
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val viewProj = FloatArray(16)
        val eyeProj = FloatArray(16)
        val eye = EyeView()
        var frames = 0
        var windowStart = SystemClock.elapsedRealtime()
        val rays = RayOverlay()
        val hands = FloatArray(XrShim.HANDS * XrShim.FLOATS_PER_HAND)
        var lastFrameNanos = SystemClock.elapsedRealtimeNanos()
        val tuningFile = getExternalFilesDir(null)?.let { File(it, TUNING_FILE) }
        var tuningStamp = 0L
        var tuningCheck = 0L

        while (!stop) {
            val status = XrShim.nativePollEvents()
            if (status and XrShim.EXIT != 0) break
            while (true) (pending.poll() ?: break).invoke(renderer)
            if (status and XrShim.RUNNING == 0) {
                SystemClock.sleep(IDLE_SLEEP_MS)
                continue
            }
            var render = XrShim.nativeBeginFrame(views)
            val frameNanos = SystemClock.elapsedRealtimeNanos()
            if (render) {
                XrShim.nativeInput(hands)
                when (grab.update(hands, (frameNanos - lastFrameNanos) / 1e9f)) {
                    PlateGrab.MOVED -> updateWorld()
                    PlateGrab.RELEASED -> {
                        updateWorld()
                        savePlacement()
                    }
                }
            }
            lastFrameNanos = frameNanos
            if (render) {
                for (i in 0 until XrShim.EYES) {
                    val framebuffer = XrShim.nativeAcquireImage(i)
                    if (framebuffer == 0) {
                        // Nothing valid to submit for this eye, so the frame ends without the layer.
                        render = false
                        continue
                    }
                    XrShim.nativeSwapchainSize(i, size)
                    val o = i * XrShim.FLOATS_PER_VIEW
                    VrMath.viewFromPose(view, views, o)
                    VrMath.projectionFromFov(projection, views[o + 7], views[o + 8], views[o + 9], views[o + 10], NEAR, FAR)
                    VrMath.multiply(viewProj, projection, view)
                    VrMath.multiply(eyeProj, viewProj, world)
                    eye.set(views, o, plateX, plateY, grab, size[1])
                    renderer.drawEye(framebuffer, size[0], size[1], eyeProj, eye)
                    rays.draw(viewProj, hands, grab)
                    XrShim.nativeReleaseImage(i)
                }
            }
            XrShim.nativeEndFrame(render)

            frames++
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= FPS_WINDOW_MS) {
                Log.i(TAG, "VR_FPS " + frames * 1000 / (now - windowStart))
                frames = 0
                windowStart = now
            }
            if (tuningFile != null && now - tuningCheck >= TUNING_CHECK_MS) {
                tuningCheck = now
                val stamp = tuningFile.lastModified()
                if (stamp != 0L && stamp != tuningStamp) {
                    tuningStamp = stamp
                    loadTuning(tuningFile)
                }
            }
        }
        // Left VR mid-grab: keep where the plate was.
        if (grab.holder >= 0) savePlacement()
    }

    /** The current eye in plate mm, for the shaders. */
    private class EyeView : ViewSource {
        private val position = FloatArray(3)
        private var mmPerPxAtOneMm = 0f

        fun set(views: FloatArray, offset: Int, plateX: Float, plateY: Float, grab: PlateGrab, height: Int) {
            VrMath.localToPlate(position, views, offset, plateX, plateY, grab.x, grab.y, grab.z, grab.scale)
            mmPerPxAtOneMm = (tan(views[offset + 9]) - tan(views[offset + 10])) / height
        }

        override fun eye() = Vec3(position[0], position[1], position[2])

        /** Distance stands in for depth along the view axis: close enough for sizes, and nothing in M1 uses it. */
        override fun pxSize(p: Vec3) = (eye() - p).length() * mmPerPxAtOneMm
    }

    /** A GLES 3 context with a small pbuffer: all real drawing goes to the OpenXR swapchains. */
    private class EglHolder {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val config: EGLConfig
        val context: EGLContext
        private val surface: EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
            config = configs[0]!!
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }

    private companion object {
        const val TAG = "OrcaXr"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_RESET_PLACEMENT = "resetPlacement"
        const val PREFS_PLACEMENT = "vr_placement"
        const val TUNING_FILE = "vr_tuning.json"
        const val TUNING_CHECK_MS = 1000L
        const val NEAR = 0.05f
        const val FAR = 100f
        const val IDLE_SLEEP_MS = 50L
        const val FPS_WINDOW_MS = 5000L
        const val JOIN_TIMEOUT_MS = 2000L
        /** VR started the engine in this process (the process outlives the activity). */
        @Volatile var vrOwnsEngine = false
    }
}
