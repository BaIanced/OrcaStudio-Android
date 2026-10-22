package app.orcaandroid.ai

/** The pure argument handling of the MCP `log_tail` tool (the tool itself lives in McpTools). */
object LogTail {
    /** Lines returned when the call gives no count. */
    const val DEFAULT_LINES = 100

    /** The most lines one call returns, however many it asks for. */
    const val MAX_LINES = 500

    /** How many lines a call returns: the requested count clamped to 1..[MAX_LINES]. */
    fun lineCount(requested: Int): Int = requested.coerceIn(1, MAX_LINES)
}
