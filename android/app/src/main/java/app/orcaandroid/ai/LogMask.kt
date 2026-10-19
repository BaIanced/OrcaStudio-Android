package app.orcaandroid.ai

/** Masks secret-looking values in log lines before log_tail hands them to an AI client. */
object LogMask {
    /** A secret-looking key and its separator; the value after it is masked. */
    private val SECRET = Regex("""(?i)((?:token|code|password|passwd|secret|key|cookie|auth\w*)["']?\s*[:=]\s*["']?(?:Bearer\s+)?)[^\s"',;&}]+""")

    fun mask(line: String): String = SECRET.replace(line) { it.groupValues[1] + "***" }
}
