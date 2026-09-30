package app.orcaandroid.core

/** Receives progress from the native core, on the thread that called the native function. */
fun interface ProgressListener {
    fun onProgress(percent: Int, text: String)
}

/**
 * JNI bindings to liborca_jni.so (android/core/jni). Every function returns a JSON object;
 * failures come back as `{"error": "..."}`. All calls except [cancel] block and must run off the
 * main thread. [call] reaches every engine function by name (see core/jni/EngineCalls.cpp).
 */
object OrcaNative {
    init {
        System.loadLibrary("orca_jni")
    }

    external fun init(resourcesDir: String, dataDir: String, cacheDir: String): String
    external fun call(method: String, argsJson: String): String
    external fun slice(plate: Int, gcodeOut: String, previewDir: String, listener: ProgressListener): String
    external fun viewGcode(gcode: String, previewDir: String, listener: ProgressListener): String
    external fun cancel()
}
