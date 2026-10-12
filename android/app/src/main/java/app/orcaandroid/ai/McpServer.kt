package app.orcaandroid.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

/**
 * A small MCP server (Streamable HTTP, JSON replies only, no SSE) on 127.0.0.1 (or, opted in, on
 * every interface: WSA routes Android's loopback to Windows, phones without USB need Wi-Fi) for AI clients:
 * one endpoint `POST /mcp` carrying JSON-RPC 2.0. Every request needs `Authorization: Bearer
 * <token>`; requests from a browser (with an `Origin` header) are refused. See `docs/mcp-tool-layer.md`.
 */
class McpServer(private val port: Int, private val token: () -> String, private val tools: McpTools) {
    private var socket: ServerSocket? = null
    private var job: Job? = null
    /** Sessions issued by `initialize`; a request naming another one gets 404 so the client starts over. */
    private val sessions = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    val running get() = socket?.isClosed == false

    /** Starts listening (on all interfaces with [lan]); throws when the port can't be bound. */
    @Synchronized
    fun start(scope: CoroutineScope, lan: Boolean) {
        if (running) return
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(if (lan) java.net.InetSocketAddress(port) else java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
        }
        socket = s
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                launch { runCatching { client.use { handle(it) } }.onFailure { Log.w(TAG, "request failed", it) } }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
        job = null
        sessions.clear()
    }

    // --- HTTP ---------------------------------------------------------------------------------------

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private class Reply(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private suspend fun handle(client: Socket) {
        client.soTimeout = 15_000
        val req = read(client.getInputStream()) ?: return write(client, Reply(400, error(null, -32700, "Bad HTTP request")))
        val reply = route(req)
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "${req.method} ${req.path} from ${client.inetAddress.hostAddress} -> ${reply.code}")
        write(client, reply)
    }

    private suspend fun route(req: Request): Reply {
        if (req.path.substringBefore('?') != "/mcp") return Reply(404)
        if (req.headers.containsKey("origin")) return Reply(403, error(null, -32600, "Browser requests are not allowed"))
        val auth = req.headers["authorization"].orEmpty()
        val given = auth.removePrefix("Bearer ").trim()
        if (!auth.startsWith("Bearer ") || !MessageDigest.isEqual(given.toByteArray(), token().toByteArray()))
            return Reply(401, error(null, -32600, "Missing or wrong bearer token (see the app's Settings > AI tools)"),
                mapOf("WWW-Authenticate" to "Bearer"))
        if (req.method != "POST") return Reply(405, headers = mapOf("Allow" to "POST"))
        val session = req.headers["mcp-session-id"]
        if (session != null && session !in sessions) return Reply(404, error(null, -32001, "Unknown session; initialize again"))

        val msg = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
            ?: return Reply(400, error(null, -32700, "Parse error"))
        // Notifications and responses from the client get no reply.
        if (!msg.has("id") || !msg.has("method")) return Reply(202)
        val id = msg.get("id")
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> {
                val sid = UUID.randomUUID().toString()
                sessions += sid
                Reply(200, result(id, initialize(params)), mapOf("Mcp-Session-Id" to sid))
            }
            "ping" -> Reply(200, result(id, JSONObject()))
            "tools/list" -> Reply(200, result(id, JSONObject().put("tools", tools.list())))
            "tools/call" -> Reply(200, result(id, tools.call(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject())))
            else -> Reply(200, error(id, -32601, "Method not found: $method"))
        }
    }

    private fun initialize(params: JSONObject): JSONObject {
        val asked = params.optString("protocolVersion")
        return JSONObject()
            .put("protocolVersion", if (asked in VERSIONS) asked else VERSIONS.first())
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "orcastudio-android").put("version", app.orcaandroid.BuildConfig.VERSION_NAME))
            .put("instructions", McpTools.INSTRUCTIONS)
    }

    private fun result(id: Any, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    private fun error(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message)).toString()

    /** Request line, headers (names lower-cased) and a Content-Length body; null when malformed or too big. */
    private fun read(input: InputStream): Request? {
        val start = line(input) ?: return null
        val parts = start.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val l = line(input) ?: return null
            if (l.isEmpty()) break
            if (headers.size > 100) return null
            val colon = l.indexOf(':')
            if (colon > 0) headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) return null
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) return null
            off += n
        }
        return Request(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (out.size() > 8192) return null
            out.write(b)
        }
    }

    private fun write(client: Socket, reply: Reply) {
        val body = reply.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${reply.code} ${REASONS[reply.code] ?: "Status"}\r\n")
            if (body.isNotEmpty()) append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            reply.headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    companion object {
        private const val TAG = "OrcaMcp"
        private const val MAX_BODY = 4 shl 20
        /** Newest first; an older client gets its own version back when we know it. */
        private val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private val REASONS = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized",
            403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed")
    }
}
