package app.orcaandroid.scad

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch

/** libopenscad.so's JNI entry points (android/openscad/bridge/scad_jni.cc). Used only in ScadService. */
internal object ScadNative {
    external fun init(resourceDir: String, cacheDir: String): Int
    /** Renders [source]; a 3D result goes to [outStl]. Returns {ok, dimension, triangles, log}. */
    external fun render(source: String, filename: String, paramsJson: String?, outStl: String): String
    /** OpenSCAD's Customizer parameter JSON, or null on a syntax error. */
    external fun parameters(source: String, filename: String): String?
    external fun version(): String
}

/**
 * Runs OpenSCAD in the app's ":scad" process (see the manifest): its geometry kernels can crash or
 * run for minutes on a user's design, and the only way to stop them is to end the process, which
 * [ScadClient] does. Requests are serialised on one thread with a large stack (OpenSCAD recurses
 * deeply); replies carry the result JSON in [KEY_RESULT].
 */
class ScadService : Service() {

    private lateinit var messenger: Messenger
    private var ready: String? = null // null = not tried, "" = ok, else the error

    override fun onCreate() {
        super.onCreate()
        val started = CountDownLatch(1)
        var handler: Handler? = null
        Thread(null, {
            Looper.prepare()
            handler = Handler(Looper.myLooper()!!) { handle(it); true }
            started.countDown()
            Looper.loop()
        }, "openscad", 256L shl 20).start()
        started.await()
        messenger = Messenger(handler!!)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(msg: Message) {
        val data = msg.data
        val result = runCatching {
            val error = ensureReady()
            if (error.isNotEmpty()) return@runCatching errorJson(error)
            when (msg.what) {
                MSG_PARAMETERS -> ScadNative.parameters(data.getString(KEY_SOURCE).orEmpty(), data.getString(KEY_FILE).orEmpty()) ?: ""
                MSG_RENDER -> ScadNative.render(data.getString(KEY_SOURCE).orEmpty(), data.getString(KEY_FILE).orEmpty(),
                    data.getString(KEY_PARAMS), data.getString(KEY_OUT).orEmpty())
                MSG_VERSION -> ScadNative.version()
                else -> errorJson("unknown request ${msg.what}")
            }
        }.getOrElse { errorJson(it.message ?: it.toString()) }
        val reply = Message.obtain(null, msg.what, msg.arg1, android.os.Process.myPid())
        reply.data = Bundle().apply { putString(KEY_RESULT, result) }
        runCatching { msg.replyTo?.send(reply) }.onFailure { Log.w(TAG, "reply lost", it) }
    }

    /** Loads the library and unpacks OpenSCAD's resources (fonts, MCAD) once per app update. */
    private fun ensureReady(): String = ready ?: run {
        val error = runCatching {
            // Libraries the user adds (BOSL2 and the like) go here; searched before the bundled MCAD.
            android.system.Os.setenv("OPENSCADPATH", File(filesDir, "openscad/libraries").apply { mkdirs() }.path, true)
            System.loadLibrary("openscad")
            val res = unpackResources()
            val cache = File(cacheDir, "openscad-fc").apply { mkdirs() }
            check(ScadNative.init(res.path, cache.path) == 0) { "OpenSCAD failed to start" }
            ""
        }.getOrElse { e ->
            Log.w(TAG, "OpenSCAD unavailable", e)
            if (e is UnsatisfiedLinkError) NOT_IN_BUILD else e.message ?: e.toString()
        }
        error.also { ready = it }
    }

    private fun unpackResources(): File {
        val dir = File(filesDir, "openscad-resources")
        val stamp = File(dir, ".stamp")
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == version) return dir
        dir.deleteRecursively()
        fun copy(asset: String, to: File) {
            val children = assets.list(asset).orEmpty()
            if (children.isEmpty()) {
                to.parentFile?.mkdirs()
                assets.open(asset).use { input -> to.outputStream().use { input.copyTo(it) } }
            } else children.forEach { copy("$asset/$it", File(to, it)) }
        }
        copy("openscad", dir)
        stamp.writeText(version)
        return dir
    }

    companion object {
        private const val TAG = "OrcaScad"
        const val MSG_PARAMETERS = 1
        const val MSG_RENDER = 2
        const val MSG_VERSION = 3
        const val KEY_SOURCE = "source"
        const val KEY_FILE = "file"
        const val KEY_PARAMS = "params"
        const val KEY_OUT = "out"
        const val KEY_RESULT = "result"
        /** Error text when the APK was built without libopenscad.so. */
        const val NOT_IN_BUILD = "not-in-build"

        private fun errorJson(text: String) = org.json.JSONObject().put("ok", false).put("error", text).toString()
    }
}
