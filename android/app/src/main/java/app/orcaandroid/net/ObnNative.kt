package app.orcaandroid.net

/**
 * JNI entry points of open-bamboo-networking, linked into liborca_jni.so
 * (android/core/jni/ObnBridge.cpp). All calls block; use them off the main thread.
 */
internal object ObnNative {
    init {
        System.loadLibrary("orca_jni")
    }

    /** Progress of [print]: stage is obn's BBL::PrintingStage (1 upload, 6 finished, 7 error). */
    fun interface PrintListener {
        fun onProgress(stage: Int, code: Int, message: String)
    }

    const val STAGE_UPLOAD = 1
    const val STAGE_FINISHED = 6
    const val STAGE_ERROR = 7

    /** Creates the agent (once) with [dir] as config/log directory; returns obn's version or "". */
    external fun init(dir: String): String
    external fun connect(devId: String, ip: String, accessCode: String): Int
    external fun disconnect()
    external fun send(devId: String, json: String): Int
    /** Certificate exchange with the printer; true once the printer has answered with its own. */
    external fun installCert(devId: String, timeoutMs: Int): Boolean
    /** Queued events as a JSON array of {kind: "connect"|"message", dev, status, text}. */
    external fun poll(): String
    external fun cancelPrint()
    external fun print(
        devId: String, ip: String, accessCode: String, file: String, projectName: String,
        plateIndex: Int, useAms: Boolean, amsMapping: String, listener: PrintListener,
    ): Int

    // Bambu account. The HTTP-style calls return {"rc": Int, "http": Int, "body": String}.
    /** Base URL of the Bambu sign-in site, e.g. https://bambulab.com. */
    external fun loginHost(): String
    external fun getMyToken(ticket: String): String
    external fun getMyProfile(token: String): String
    /** Hands the user_login JSON (tokens + profile) to obn, which keeps the session in obn.auth.json. */
    external fun changeUser(userInfo: String): Int
    /** Signed-in user name, or "" when signed out. */
    external fun userName(): String
    external fun logout()
    /** The account's printers ("devices": dev_id, dev_name, dev_access_code, dev_online, ...). */
    external fun userPrintInfo(): String
    /** The account's cloud presets for a profile bundle version: {"rc": Int, "presets": {name: {key: value}}}. */
    external fun cloudPresets(bundleVersion: String): String
}
