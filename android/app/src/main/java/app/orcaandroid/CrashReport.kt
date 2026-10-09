package app.orcaandroid

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Keeps a report of the last crash, so a crash that closed the app without a message can be shown
 * and copied on the next start. Kotlin crashes are written by an uncaught-exception handler; for a
 * crash in the native core, Android's exit record (API 30+) says that it happened and the app's own
 * log lines (libc "Fatal signal", the crash_dump backtrace) say where.
 */
object CrashReport {
    private const val FILE = "crash_report.txt"
    private const val PREFS = "crash_report"
    private const val SEEN = "seen_exit"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { File(app.filesDir, FILE).writeText("Crash in thread ${thread.name}\n${e.stackTraceToString()}") }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The report of a crash since the last [clear], or null. Reads the log, so call it off the main thread. */
    fun pending(context: Context): String? {
        val file = File(context.filesDir, FILE)
        val exit = unseenCrash(context)
        if (file.isFile) return file.readText()
        if (exit == null) return null
        val report = buildString {
            append(if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "Native crash" else "Crash")
            exit.description?.let { append(": ").append(it) }
            append("\n").append(crashLog())
        }
        file.writeText(report)
        return report
    }

    /** Android's record of the last exit if it was a crash not reported yet (API 30+); marks it reported. */
    private fun unseenCrash(context: Context): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        if (exit.reason != ApplicationExitInfo.REASON_CRASH_NATIVE && exit.reason != ApplicationExitInfo.REASON_CRASH) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(SEEN, 0L) >= exit.timestamp) return null
        prefs.edit().putLong(SEEN, exit.timestamp).apply()
        return exit
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    /** The crash lines of this app's log: Kotlin's FATAL EXCEPTION block and the native crash dump. */
    private fun crashLog(): String = runCatching {
        val lines = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
            .inputStream.bufferedReader().readLines()
        val start = lines.indexOfLast { "FATAL EXCEPTION" in it || "Fatal signal" in it }
        if (start < 0) return@runCatching "(no crash lines in the log)"
        lines.drop(start).filter { l ->
            "AndroidRuntime" in l || "/DEBUG" in l || "/libc" in l || "crash_dump" in l || "Fatal signal" in l
        }.take(150).joinToString("\n")
    }.getOrElse { "(log not readable: ${it.message})" }
}
