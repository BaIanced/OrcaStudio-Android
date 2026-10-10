package app.orcaandroid.plugins

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Orca plugin runtime: OrcaSlicer plugins (Python) installed from Orca Cloud, run by the
 * embedded CPython in the native core. Python starts once, after Orca Cloud sign-in; plugins
 * then live until the app process ends.
 */
object Plugins {
    data class Capability(val name: String, val type: String, val enabled: Boolean, val configUi: Boolean)
    data class Plugin(
        val key: String, val name: String, val version: String, val cloudUuid: String,
        val loaded: Boolean, val error: String, val capabilities: List<Capability>,
    )
    data class PageMessage(val pluginKey: String, val capability: String, val json: String)

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "orca-plugins") }.asCoroutineDispatcher()

    private val _plugins = MutableStateFlow<List<Plugin>>(emptyList())
    val plugins: StateFlow<List<Plugin>> = _plugins

    private val _status = MutableStateFlow<String?>(null)
    /** Why the runtime is not running (start error), or null. */
    val status: StateFlow<String?> = _status

    private val _pageMessages = MutableSharedFlow<PageMessage>(extraBufferCapacity = 256)
    val pageMessages: SharedFlow<PageMessage> = _pageMessages

    @Volatile private var started = false
    private lateinit var appContext: Context

    private val listener = object : PluginNative.Listener {
        override fun onPageMessage(pluginKey: String, capability: String, json: String) {
            _pageMessages.tryEmit(PageMessage(pluginKey, capability, json))
        }

        override fun onOpenUrl(url: String) {
            runCatching {
                appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w(TAG, "cannot open $url", it) }
        }
    }

    val isStarted get() = started

    /** Starts Python (once) and loads the plugins installed for this Orca Cloud user. */
    suspend fun start(context: Context, dataDir: File, cloudUserId: String) = withContext(thread) {
        appContext = context.applicationContext
        val result = runCatching {
            preparePythonHome(context, dataDir)
            JSONObject(PluginNative.start(cloudUserId, Locale.getDefault().toString(), listener))
        }.getOrElse { JSONObject().put("error", it.message ?: it.toString()) }
        apply(result)
        started = !result.has("error")
    }

    /** Installs a downloaded package and loads it; needs [start] first. */
    suspend fun install(file: File, cloudUuid: String, name: String, version: String) = withContext(thread) {
        check(started) { _status.value ?: "The plugin runtime is not running" }
        val result = JSONObject(PluginNative.install(file.path, cloudUuid, name, version))
        apply(result)
        result.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
        Unit
    }

    suspend fun openPage(pluginKey: String, capability: String): String = withContext(thread) {
        val result = JSONObject(PluginNative.pageOpen(pluginKey, capability))
        result.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
        result.getString("html")
    }

    /** A window.orca.postMessage() from the page; runs the plugin's on_message off the caller's thread. */
    suspend fun pageMessage(pluginKey: String, capability: String, json: String) = withContext(thread) {
        val result = JSONObject(PluginNative.pageMessage(pluginKey, capability, json))
        result.optString("error").takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "page message: $it") }
        Unit
    }

    suspend fun closePage(pluginKey: String, capability: String) = withContext(thread) { PluginNative.pageClose(pluginKey, capability) }

    /**
     * Runs the slicing-pipeline plugins the sliced config selects on an exported G-code file, in
     * place (desktop: PostProcessor run_post_process_plugins). Returns the plugins' messages.
     */
    suspend fun postProcess(plate: Int, gcode: File, host: String, outputName: String): List<String> = withContext(thread) {
        if (!started) return@withContext emptyList()
        val result = JSONObject(PluginNative.postProcess(plate, gcode.path, host, outputName))
        result.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
        val messages = result.optJSONArray("messages") ?: JSONArray()
        (0 until messages.length()).mapNotNull { i ->
            messages.getJSONObject(i).optJSONObject("result")?.optString("message")?.takeIf { it.isNotEmpty() }
        }
    }

    private fun apply(result: JSONObject) {
        _status.value = result.optString("error").takeIf { it.isNotEmpty() }
        val arr = result.optJSONArray("plugins") ?: return
        _plugins.value = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val caps = p.optJSONArray("capabilities") ?: JSONArray()
            Plugin(
                p.optString("key"), p.optString("name"), p.optString("version"), p.optString("cloud_uuid"),
                p.optBoolean("loaded"), p.optString("error"),
                (0 until caps.length()).map { j ->
                    val c = caps.getJSONObject(j)
                    Capability(c.optString("name"), c.optString("type"), c.optBoolean("enabled"), c.optBoolean("config_ui"))
                },
            )
        }
    }

    /**
     * Extracts the Python standard library (assets/python, android/scripts/stage_python.py) to
     * <data dir>/python, where OrcaSlicer's PythonInterpreter looks for its home; again after an
     * app update brings a different Python.
     */
    private fun preparePythonHome(context: Context, dataDir: File) {
        val version = context.assets.open("python/version").bufferedReader().use { it.readText().trim() }
        val home = File(dataDir, "python")
        val stamp = File(home, ".version")
        if (stamp.exists() && stamp.readText() == version) return
        home.deleteRecursively()
        val root = home.canonicalPath + File.separator
        ZipInputStream(context.assets.open("python/stdlib.zip").buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(home, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad entry ${entry.name}")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        stamp.writeText(version)
    }

    private const val TAG = "OrcaPlugins"
}
