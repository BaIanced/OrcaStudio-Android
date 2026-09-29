package com.orcaslicer.android.core

/** Receives slicing progress from the native core, on the thread that called [OrcaNative.slice]. */
fun interface ProgressListener {
    fun onProgress(percent: Int, text: String)
}

/**
 * JNI bindings to liborca_jni.so (android/core/jni/orca_jni.cpp).
 *
 * Every call returns a JSON object; failures come back as `{"error": "..."}`. All calls except
 * [cancel] block and must run off the main thread.
 */
object OrcaNative {
    init {
        System.loadLibrary("orca_jni")
    }

    external fun init(resourcesDir: String, dataDir: String, cacheDir: String): String
    external fun loadPresets(): String
    external fun printerList(): String
    external fun selectPrinter(printer: String): String
    external fun loadModel(paths: Array<String>, copies: Int, meshOut: String): String
    external fun slice(
        printPreset: String,
        filamentPreset: String,
        overridesJson: String,
        gcodeOut: String,
        previewOut: String,
        listener: ProgressListener,
    ): String

    external fun optionDefs(type: String): String
    external fun presetValues(type: String, name: String): String
    external fun savePreset(type: String, base: String, newName: String, overridesJson: String): String
    external fun deletePreset(type: String, name: String): String

    external fun cancel()
}
