package app.orcaandroid.net

import android.net.Uri
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The loopback redirect target of Orca Cloud's browser sign-in (Google, GitHub, ...), like the
 * desktop's HttpServer::auth_handle_request: the browser ends on
 * http://localhost:<port>/callback?code=...&orca_state=..., and the code goes to [onCode].
 *
 * Listens only on the loopback addresses (IPv4 and, where available, IPv6, since "localhost" may
 * resolve to either), on the first free port of [OrcaCloud.LOOPBACK_PORT] .. +2.
 */
internal class OrcaCloudLoopback(
    private val appPackage: String,
    private val onCode: (code: String, state: String) -> Unit,
) : Closeable {
    private val sockets = mutableListOf<ServerSocket>()
    val port: Int

    init {
        var bound = 0
        for (p in OrcaCloud.LOOPBACK_PORT..OrcaCloud.LOOPBACK_PORT + 2) {
            val v4 = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), p)) } }.getOrNull()
                ?: continue
            sockets += v4
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("::1"), p)) } }
                .onSuccess { sockets += it }
            bound = p
            break
        }
        if (bound == 0) throw IOException("No free local port for the Orca Cloud sign-in")
        port = bound
        sockets.forEach { s -> thread(name = "OrcaCloudLoopback", isDaemon = true) { serve(s) } }
    }

    private fun serve(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { socket.use(::handle) }.onFailure { Log.w("OrcaCloud", "loopback request failed: ${it.message}") }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) { if (reader.readLine().isNullOrEmpty()) break } // headers
        val target = requestLine.split(' ').getOrNull(1).orEmpty()
        val uri = Uri.parse("http://localhost$target")
        val out = socket.getOutputStream()
        if (uri.path != OrcaCloud.LOOPBACK_PATH) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("orca_state") ?: uri.getQueryParameter("state").orEmpty()
        val ok = code.isNotEmpty()
        // Back to the app: an intent link (works from a tap in Chrome; the activity is singleTask).
        val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=$appPackage;end"
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            |<style>body{font-family:sans-serif;margin:32px;}a{display:inline-block;padding:10px 16px;margin-top:12px;background:#009688;color:#fff;text-decoration:none;border-radius:6px;}</style>
            |</head><body><h2>${if (ok) "Authentication complete" else "Authentication failed"}</h2>
            |<p>${if (ok) "You can return to OrcaStudio-Android." else "Something went wrong. Please return to OrcaStudio-Android and try again."}</p>
            |<a href="$back">Return to OrcaStudio-Android</a></body></html>""".trimMargin()
        val body = html.toByteArray()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
        if (ok) onCode(code, state)
    }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
    }
}
