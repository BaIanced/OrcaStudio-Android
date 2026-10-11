package app.orcaandroid.scad

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Outcome of one render: [stl] is set for a non-empty 3D result. */
data class ScadRender(val ok: Boolean, val dimension: Int, val triangles: Int, val log: String, val stl: File?, val millis: Long)

class ScadUnavailable(message: String) : Exception(message)

/**
 * The app side of [ScadService]: binds the ":scad" process on first use and sends it one request at
 * a time. Cancelling a request ends that process (OpenSCAD has no way to interrupt a render); the
 * binding brings up a fresh one for the next request. A crash there fails the request instead of
 * the app.
 */
class ScadClient(private val context: Context) {

    private val mutex = Mutex()
    private var service: CompletableDeferred<Messenger> = CompletableDeferred()
    private var bound = false
    private var pending: CancellableContinuation<String>? = null
    private var nextId = 0

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        val result = msg.data.getString(ScadService.KEY_RESULT).orEmpty()
        pending?.takeIf { it.isActive && msg.arg1 == nextId }?.resume(result)
        pending = null
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            if (service.isCompleted) service = CompletableDeferred()
            service.complete(Messenger(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The process died (crash or [cancel]); Android restarts it while we stay bound.
            if (service.isCompleted) service = CompletableDeferred()
            pending?.takeIf { it.isActive }?.resumeWithException(ScadUnavailable("OpenSCAD stopped"))
            pending = null
        }
    }

    private fun bind() {
        if (bound) return
        bound = context.bindService(Intent(context, ScadService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) throw ScadUnavailable("OpenSCAD could not be started")
    }

    /** Ends the OpenSCAD process when it is no longer needed (the editor closed). */
    fun release() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
        service = CompletableDeferred()
    }

    /** Ends the OpenSCAD process: the only way to stop a render. */
    private fun kill() {
        val name = context.packageName + ":scad"
        context.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.processName == name }?.let { android.os.Process.killProcess(it.pid) }
    }

    private suspend fun request(what: Int, data: Bundle): String = mutex.withLock {
        bind()
        val target = service.await()
        suspendCancellableCoroutine { cont ->
            val id = ++nextId
            pending = cont
            val msg = Message.obtain(null, what, id, 0).apply { this.data = data; replyTo = replies }
            runCatching { target.send(msg) }.onFailure { cont.resumeWithException(ScadUnavailable("OpenSCAD stopped")) }
            cont.invokeOnCancellation { kill() }
        }
    }

    private fun checkError(json: String): JSONObject? {
        if (!json.startsWith("{")) return null
        val o = JSONObject(json)
        val error = o.optString("error")
        if (error == ScadService.NOT_IN_BUILD) throw ScadUnavailable("This build does not include OpenSCAD.")
        if (error.isNotEmpty()) throw ScadUnavailable(error)
        return o
    }

    /** Customizer parameters (OpenSCAD's parameter JSON), or null when the source doesn't parse. */
    suspend fun parameters(source: String, file: String): JSONObject? {
        val json = request(ScadService.MSG_PARAMETERS, Bundle().apply {
            putString(ScadService.KEY_SOURCE, source); putString(ScadService.KEY_FILE, file)
        })
        if (json.isEmpty()) return null
        return checkError(json)
    }

    /** Renders [source] with Customizer [params] (a flat JSON object) to [out]. */
    suspend fun render(source: String, file: String, params: String?, out: File): ScadRender {
        out.parentFile?.mkdirs()
        out.delete()
        val start = System.currentTimeMillis()
        val json = request(ScadService.MSG_RENDER, Bundle().apply {
            putString(ScadService.KEY_SOURCE, source); putString(ScadService.KEY_FILE, file)
            putString(ScadService.KEY_PARAMS, params); putString(ScadService.KEY_OUT, out.path)
        })
        val o = checkError(json) ?: throw ScadUnavailable("No answer from OpenSCAD")
        val triangles = o.optInt("triangles")
        return ScadRender(o.optBoolean("ok"), o.optInt("dimension"), triangles, o.optString("log"),
            out.takeIf { triangles > 0 && it.isFile }, System.currentTimeMillis() - start)
    }

    suspend fun version(): String = request(ScadService.MSG_VERSION, Bundle()).also { checkError(it) }
}
