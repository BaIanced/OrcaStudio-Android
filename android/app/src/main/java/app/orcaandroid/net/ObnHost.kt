package app.orcaandroid.net

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bambu Lab printers WITHOUT "LAN Only" / Developer Mode, through open-bamboo-networking (obn).
 *
 * Current firmware rejects unsigned print commands from third-party slicers unless Developer Mode
 * is on. obn signs them with the user's own slicer credentials (imported via [ObnCredentials]),
 * installs that certificate on the printer, and encrypts the print URL with the certificate the
 * printer sends back. Upload (FTPS :990) and status (MQTT :8883) stay on the LAN, like [BambuHost].
 *
 * Connection fields: url = printer IP, apiKey = LAN access code, serial = printer serial number.
 *
 * obn keeps one LAN session per process, so every ObnHost shares it ([Session]): the device tab's
 * status polls and the print notification no longer disconnect each other, and the session closes
 * when the last host does.
 */
internal class ObnHost(private val c: PrinterConnection) : PrintHost {
    private val host = c.baseUrl().substringAfter("://").substringBefore(':').substringBefore('/')
    private var joined = false

    /** The process-wide LAN session and the printer state its reports build up. */
    private object Session {
        var serial: String? = null
        var connected = false
        var lastError: String? = null
        var users = 0
        var report = BambuReport()
    }

    private fun ensureConnected() {
        if (c.serial.isBlank()) throw IOException("Enter the printer's serial number")
        if (host.isBlank() || c.apiKey.isBlank()) throw IOException("Enter the printer's IP address and access code")
        val ctx = ObnCredentials.appContext()
        ObnCredentials.startAgent(ctx)
        synchronized(Session) {
            if (!joined) { Session.users++; joined = true }
            drain()
            if (Session.connected && Session.serial == c.serial) return
            if (Session.serial != c.serial) Session.report = BambuReport()
            // Close a dropped session first: an immediate disconnect clears obn's per-session
            // certificate latch (see ObnCredentials.writeDefaultConf), so the new one installs again.
            if (Session.serial != null) ObnNative.disconnect()
            Session.serial = c.serial
            Session.connected = false
            Session.lastError = null
            val rc = ObnNative.connect(c.serial, host, c.apiKey)
            if (rc != 0) {
                // obn logs the reason (TLS setup, MQTT connect error) in obn.log.
                val reason = ObnCredentials.lastLogLine(ctx, "LanSession", "mqtt connect")?.let { "\n$it" }.orEmpty()
                throw IOException("Connection to the printer failed (obn $rc)$reason")
            }
            // obn connects asynchronously and reports the result as a "connect" event.
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (!Session.connected && System.currentTimeMillis() < deadline) {
                drain()
                Session.lastError?.let { throw IOException(it) }
                if (!Session.connected) Thread.sleep(200)
            }
            if (!Session.connected) throw IOException("The printer did not answer on $host (MQTT :8883)")
        }
    }

    /** Applies queued obn events: connection changes and printer reports. */
    private fun drain() {
        val events = runCatching { JSONArray(ObnNative.poll()) }.getOrNull() ?: return
        synchronized(Session) {
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                if (e.optString("dev").let { it.isNotEmpty() && it != Session.serial }) continue
                when (e.optString("kind")) {
                    "connect" -> when (e.optInt("status")) {
                        0 -> { Session.connected = true; Session.lastError = null }
                        1 -> { Session.connected = false; Session.lastError = "Printer refused the connection: ${e.optString("text")}" }
                        else -> Session.connected = false
                    }
                    "message" -> Session.report.apply(e.optString("text"))
                }
            }
        }
    }

    private fun request(payload: JSONObject) {
        ensureConnected()
        var rc = ObnNative.send(c.serial, payload.toString())
        if (rc != 0) {
            // The session may have dropped since the last report; connect again once.
            synchronized(Session) { Session.connected = false }
            ensureConnected()
            rc = ObnNative.send(c.serial, payload.toString())
        }
        if (rc != 0) throw IOException("Sending to the printer failed (obn $rc)")
    }

    private fun pushAll() = request(JSONObject().put("pushing", JSONObject().put("sequence_id", BambuReport.nextSequence()).put("command", "pushall")))

    private fun requireCredentials() {
        val missing = ObnCredentials.missing(ObnCredentials.appContext())
        if (missing.isNotEmpty()) throw IOException("Import your slicer credentials first (missing: ${missing.joinToString()})")
    }

    override fun test(): String {
        requireCredentials()
        ensureConnected()
        pushAll()
        val certified = ObnNative.installCert(c.serial, CERT_TIMEOUT_MS)
        return "Bambu Lab · ${c.serial} · " + if (certified) "signed printing ready" else "connected, certificate exchange pending"
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        if (!startPrint) {
            // Plain file transfer needs no signature.
            FtpsClient(host, "bblp", c.apiKey).upload(file, remoteName, onProgress)
            return
        }
        requireCredentials()
        ensureConnected()
        if (!ObnNative.installCert(c.serial, CERT_TIMEOUT_MS)) {
            throw IOException("The printer did not complete the certificate exchange; check the slicer credentials")
        }
        var failure: String? = null
        val rc = ObnNative.print(
            c.serial, host, c.apiKey, file.path, remoteName.removeSuffix(".gcode.3mf"),
            1, false, "",
        ) { stage, code, message ->
            when (stage) {
                ObnNative.STAGE_UPLOAD -> onProgress((code.coerceIn(0, 100)) / 100f)
                ObnNative.STAGE_ERROR -> failure = message.ifBlank { "error $code" }
            }
        }
        if (rc != 0) throw IOException("Print failed (obn $rc${failure?.let { ": $it" } ?: ""})")
        onProgress(1f)
    }

    override fun status(): PrinterStatus {
        ensureConnected()
        val report = Session.report
        if (report.isEmpty) {
            pushAll()
            report.await(4_000, ::drain) { !report.isEmpty }
        }
        drain()
        return report.status()
    }

    override fun fullStatus(): PrinterStatus {
        ensureConnected()
        val report = Session.report
        pushAll()
        report.await(5_000, ::drain) { report.hasTrays }
        return report.status()
    }

    override fun command(print: JSONObject): JSONObject? {
        val seq = BambuReport.nextSequence()
        request(JSONObject().put("print", JSONObject(print.toString()).put("sequence_id", seq)))
        val report = Session.report
        var reply: JSONObject? = null
        report.await(REPLY_TIMEOUT_MS, ::drain) { report.reply(seq)?.also { reply = it } != null }
        reply?.takeIf { it.optString("result").equals("fail", ignoreCase = true) }?.let { r ->
            val reason = r.optString("reason").ifBlank { r.optString("err_code") }
            throw IOException("The printer refused \"${print.optString("command")}\"" + if (reason.isNotBlank()) ": $reason" else "")
        }
        return reply
    }

    override fun control(action: PrintHost.JobAction): Boolean {
        val command = when (action) {
            PrintHost.JobAction.PAUSE -> "pause"
            PrintHost.JobAction.RESUME -> "resume"
            PrintHost.JobAction.CANCEL -> "stop"
        }
        // obn signs every {"print": ...} payload it sends.
        command(JSONObject().put("command", command).put("param", ""))
        return true
    }

    override fun close() {
        synchronized(Session) {
            if (!joined) return
            joined = false
            if (--Session.users == 0 && Session.connected) {
                ObnNative.disconnect()
                Session.connected = false
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val CERT_TIMEOUT_MS = 15_000
        const val REPLY_TIMEOUT_MS = 5_000L
    }
}
