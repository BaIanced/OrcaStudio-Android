package app.orcaandroid.plugins

import app.orcaandroid.core.OrcaNative

/**
 * JNI bindings to the Orca plugin runtime in liborca_jni.so (android/core/jni/OrcaPlugins.cpp):
 * embedded CPython running OrcaSlicer plugins. Every function returns a JSON object; failures come
 * back as `{"error": "..."}`. All calls block and must run off the main thread.
 */
object PluginNative {
    init {
        OrcaNative // loads liborca_jni
    }

    /** Callbacks from plugins; called on Python threads. */
    interface Listener {
        fun onPageMessage(pluginKey: String, capability: String, json: String)
        fun onOpenUrl(url: String)
    }

    external fun start(cloudUserId: String, language: String, listener: Listener): String
    external fun install(packagePath: String, cloudUuid: String, name: String, version: String): String
    external fun list(): String
    external fun pageOpen(pluginKey: String, capability: String): String
    external fun pageMessage(pluginKey: String, capability: String, json: String): String
    external fun pageClose(pluginKey: String, capability: String)
    external fun runScript(pluginKey: String, capability: String): String
    external fun postProcess(plate: Int, gcodePath: String, host: String, outputName: String): String
}
