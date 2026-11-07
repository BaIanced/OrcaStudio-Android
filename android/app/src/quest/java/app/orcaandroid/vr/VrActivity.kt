package app.orcaandroid.vr

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
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
import app.orcaandroid.ui.MainActivity
import app.orcaandroid.ui.components.hexToRgb
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
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
    private val link get() = (application as OrcaApp).container.vr
    private lateinit var claim: VrLink.Claim
    /** Main thread only. Once per activity: a second release could free the claim of the next VR. */
    private var claimReleased = false
    /** Where the user put the plate; only the render thread touches it. */
    private val grab = PlateGrab()
    /** Only the render thread touches it. */
    private var panel: InfoPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logSessionHash("create")
        claim = link.claimForVr()
        loadPlacement()
        thread = Thread(::renderThread, "OrcaXrRender").also { it.start() }
    }

    // Saves are held from start to stop, not to destroy: a hung VR must not block the saves of a 2D app back in front.
    // Starting comes before the panel's onStop, stopping after its onResume (untested on a headset).
    override fun onStart() {
        super.onStart()
        link.viewOpened()
    }

    /**
     * VR ends when stopped (user, 2026-11-05): none is left behind to show a scene older than the 2D app's. It can't edit
     * yet, so there is nothing to save first; a milestone that adds edits (M4) must save here. Finishing also frees the
     * engine for a waiting 2D app when the render thread hangs, via onDestroy's bounded join (untested on a headset).
     */
    override fun onStop() {
        Log.i(TAG, "VR_LEAVE stopped")
        logSessionHash("stop")
        link.viewClosed()
        stop = true
        finishAndRemoveTask()
        super.onStop()
    }

    override fun onDestroy() {
        stop = true
        scope.cancel()
        // The thread can be inside xrWaitFrame; don't hold the main thread for long.
        thread?.join(JOIN_TIMEOUT_MS)
        releaseClaim()
        super.onDestroy()
    }

    /**
     * M2's acceptance is this hash unchanged across the round trip, and the file can't be read from outside: private storage,
     * and `.quest` isn't debuggable. Hashes on its own thread so a big project doesn't hold the main thread.
     */
    private fun logSessionHash(moment: String) {
        val file = File(filesDir, "session/session.3mf")
        thread(name = "VrSessionHash") {
            runCatching {
                if (file.exists()) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    Log.i(TAG, "VR_SESSION $moment ${file.length()} bytes sha256 $hex")
                } else {
                    Log.i(TAG, "VR_SESSION $moment none")
                }
            }.onFailure { Log.e(TAG, "VR_SESSION $moment failed", it) }
        }
    }

    private fun releaseClaim() {
        if (claim == VrLink.Claim.COLD && !claimReleased) {
            claimReleased = true
            link.releaseVr()
        }
    }

    private fun renderThread() {
        val egl = EglHolder()
        try {
            val created = XrShim.nativeCreate(this, egl.display.nativeHandle, egl.config.nativeHandle, egl.context.nativeHandle, !intent.getBooleanExtra(EXTRA_PLAIN, false))
            if (created) {
                frameLoop()
                if (link.endRequested) Log.i(TAG, "VR_LEAVE asked by the panel")
                XrShim.nativeDestroy()
            } else {
                Log.e(TAG, "OpenXR setup failed, see the lines above")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "OpenXR shim did not load", e)
        } finally {
            egl.release()
        }
        runOnUiThread {
            // Done with the engine. onDestroy can lag finishing by seconds, and a relaunch in that gap would find it held and leave.
            scope.cancel()
            releaseClaim()
            // Ended by itself over a 2D app, not destroyed by the system: back to the panel.
            if (claim == VrLink.Claim.SHARED && !isFinishing && !isDestroyed) launchPanelInHome()
            finishAndRemoveTask()
        }
    }

    /** Finishing alone isn't documented to bring the panel back: Home opens it (Meta's hybrid apps overview). */
    private fun launchPanelInHome() {
        runCatching {
            val panel = Intent(applicationContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(applicationContext, 0, panel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_HOME_PENDING_INTENT, pending))
        }.onFailure { Log.e(TAG, "VR_LEAVE could not open the panel", it) }
    }

    /** Over a 2D app: only reads what it publishes, never the engine, so its project and scene stay as they are. */
    private fun loadScene() = scope.launch {
        runCatching {
            when (claim) {
                VrLink.Claim.COLD -> loadProject()
                VrLink.Claim.SHARED -> {
                    val shared = link.scene
                    if (shared != null) show(shared) else {
                        Log.e(TAG, "VR_SCENE the 2D app is not ready, leaving")
                        stop = true
                    }
                }
                VrLink.Claim.BUSY -> {
                    Log.e(TAG, "VR_SCENE another immersive view holds the engine, leaving")
                    stop = true
                }
            }
        }.onFailure { Log.e(TAG, "VR_SCENE failed", it) }
    }

    /** Cold start: loads the project into the engine, in the startup order of AppViewModel.start. Settings and the session file are only read. */
    private suspend fun loadProject() {
        val container = (application as OrcaApp).container
        val engine = container.engine
        container.resources.prepare()
        engine.init(container.resources.resourcesDir, container.resources.dataDir)
        val printers = engine.loadPresets()
        val session = File(filesDir, "session/session.3mf")
        val project = projectExtra() ?: session
        val last = printers.firstOrNull { it.name == container.settings.lastPrinter }?.name ?: printers.firstOrNull()?.name
        if (last == null) {
            Log.e(TAG, "VR_SCENE no printer installed, open the 2D app once")
            stop = true
            return
        }
        // As in the 2D start: a printer is selected before the project loads, then the project's own if it is installed.
        var bed = engine.selectPrinter(last).bed
        // The bed shows even if the project fails to load: never an empty void.
        showBed(VrScene(bed.toList(), listOf(0f to 0f), 0, "", -1, "", emptyList()))
        val info = if (project.exists()) engine.loadProject(project.path).second else null
        val own = printers.firstOrNull { it.name == info?.printer }?.name
        if (own != null) bed = engine.selectPrinter(own).bed
        val scene = engine.scene()
        val origins = scene.plates.map { it.originX to it.originY }
        val active = if (project == session) savedActivePlate(session).coerceIn(0, (origins.size - 1).coerceAtLeast(0)) else 0
        Log.i(TAG, "VR_SCENE project ${project.exists()}")
        show(VrScene(bed.toList(), origins, active, scene.meshFile, scene.meshVersion, scene.paintFile, info?.filamentColors.orEmpty().map { it.ifBlank { null } }))
    }

    private fun show(scene: VrScene) {
        // First: a mesh that fails to load still leaves the bed.
        showBed(scene)
        val mesh = if (scene.meshFile.isEmpty()) null else FloatData.load(scene.meshFile, 8)
        val paint = if (scene.paintFile.isEmpty()) null else FloatData.load(scene.paintFile, 8)
        val colors = scene.filamentColors.map(::hexToRgb)
        Log.i(TAG, "VR_SCENE $claim plates ${scene.origins.size} active ${scene.activePlate} vertices ${mesh?.count ?: 0}")
        pending += {
            filamentColors = colors
            setMesh(mesh, scene.meshVersion)
            setPaint(paint)
        }
    }

    /** Queues the beds and centres the world on the active plate. */
    private fun showBed(scene: VrScene) {
        val bed = scene.bed.toFloatArray()
        val (cx, cy) = scene.plateCenter()
        pending += {
            setBed(bed, scene.origins)
            plateOriginsCount = scene.origins.size
            activePlate = scene.activePlate
            plateX = cx
            plateY = cy
            // Every plate counts as the grab target, not only the active one.
            val origins = scene.origins
            val xs = bed.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = bed.filterIndexed { i, _ -> i % 2 == 1 }
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
        if (json.has("refreshHz")) requestRefreshRate(json.optDouble("refreshHz").toFloat())
        panel?.let { p ->
            p.x = read("panelX", p.x)
            p.y = read("panelY", p.y)
            p.z = read("panelZ", p.z)
            p.width = read("panelWidth", p.width)
        }
        Log.i(TAG, "VR_TUNE applied $json")
    }

    private fun requestRefreshRate(wanted: Float) {
        val offered = XrShim.nativeRefreshRates()
        val rate = RefreshRates.match(wanted, offered)
        if (rate == null) {
            Log.e(TAG, "VR_REFRESH $wanted not offered, keeping the current rate: ${offered.joinToString()}")
            return
        }
        Log.i(TAG, "VR_REFRESH requested $rate accepted ${XrShim.nativeRequestRefreshRate(rate)}")
    }

    /** `vr_panel.txt` beside the tuning file, pushed over adb, shown live. Removing it brings the built-in text back; empty hides the panel. */
    private fun loadPanelText(file: File, exists: Boolean) {
        val text = if (!exists) PanelText.DEFAULT else runCatching {
            file.bufferedReader().use { reader ->
                val chars = CharArray(PanelText.MAX_CHARS)
                String(chars, 0, reader.read(chars).coerceAtLeast(0))
            }
        }.getOrElse {
            Log.e(TAG, "VR_PANEL unreadable, keeping the current text: ${it.message}")
            return
        }
        panel?.setText(text.trimEnd())
        Log.i(TAG, "VR_PANEL text ${text.length} chars")
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
        // Passthrough shows through where the scene is cleared; plain (or no passthrough) keeps the opaque background.
        renderer.transparentBackground = XrShim.nativePassthroughActive()
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
        val info = InfoPanel(scope).also { panel = it }
        val panelFile = getExternalFilesDir(null)?.let { File(it, PANEL_FILE) }
        var panelStamp = 0L
        var statusCheck = 0L
        val tracked = BooleanArray(XrShim.HANDS)
        var placeInFront = intent.getBooleanExtra(EXTRA_PLATE_IN_FRONT, false)
        val front = FloatArray(2)

        while (!stop && !link.leaveRequested && !link.endRequested) {
            val status = XrShim.nativePollEvents()
            if (status and XrShim.EXIT != 0) break
            while (true) (pending.poll() ?: break).invoke(renderer)
            if (status and XrShim.RUNNING == 0) {
                SystemClock.sleep(IDLE_SLEEP_MS)
                continue
            }
            var render = XrShim.nativeBeginFrame(views)
            val frameNanos = SystemClock.elapsedRealtimeNanos()
            if (render && placeInFront) {
                placeInFront = false
                VrMath.inFrontOfHead(front, views, 0)
                grab.place(front[0], VrMath.TABLE_Y + views[1], front[1], 1f)
                updateWorld()
                Log.i(TAG, "VR_PLACE in front of the head x ${grab.x} y ${grab.y} z ${grab.z}")
            }
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
                info.prepare()
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
                    info.draw(viewProj)
                    rays.draw(viewProj, hands, grab)
                    XrShim.nativeReleaseImage(i)
                }
            }
            XrShim.nativeEndFrame(render)

            frames++
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= FPS_WINDOW_MS) {
                Log.i(TAG, "VR_FPS " + frames * 1000 / (now - windowStart))
                Log.i(TAG, "VR_REFRESH current ${XrShim.nativeCurrentRefreshRate()} frame period ${XrShim.nativeFramePeriodHz()}")
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
                if (panelFile != null) {
                    val panelNow = panelFile.lastModified()
                    if (panelNow != panelStamp) {
                        panelStamp = panelNow
                        loadPanelText(panelFile, panelNow != 0L)
                    }
                }
            }
            if (now - statusCheck >= STATUS_CHECK_MS) {
                statusCheck = now
                info.setStatus(PanelText.status(hands, grab.holder, grab.x, grab.y, grab.z, grab.scale))
                for (hand in 0 until XrShim.HANDS) {
                    val isTracked = hands[hand * XrShim.FLOATS_PER_HAND + XrShim.HAND_ACTIVE] > 0.5f
                    if (isTracked != tracked[hand]) {
                        tracked[hand] = isTracked
                        Log.i(TAG, "VR_INPUT ${if (hand == 0) "left" else "right"} tracked $isTracked")
                    }
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
        /** `--ez plain true`: opaque background instead of the passthrough tabletop (no UI yet). */
        const val EXTRA_PLAIN = "plain"
        const val EXTRA_HOME_PENDING_INTENT = "extra_launch_in_home_pending_intent"
        const val EXTRA_RESET_PLACEMENT = "resetPlacement"
        /** `--ez plateInFront true`: debug, puts the plate in front of the head at the first frame, unsaved (for unworn screencaps). */
        const val EXTRA_PLATE_IN_FRONT = "plateInFront"
        const val PREFS_PLACEMENT = "vr_placement"
        const val TUNING_FILE = "vr_tuning.json"
        const val TUNING_CHECK_MS = 1000L
        const val PANEL_FILE = "vr_panel.txt"
        /** Often enough to read as live; each change of the words redraws the panel's texture. */
        const val STATUS_CHECK_MS = 250L
        const val NEAR = 0.05f
        const val FAR = 100f
        const val IDLE_SLEEP_MS = 50L
        const val FPS_WINDOW_MS = 5000L
        const val JOIN_TIMEOUT_MS = 2000L
    }
}
