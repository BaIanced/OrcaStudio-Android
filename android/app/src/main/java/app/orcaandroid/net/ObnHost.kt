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
 */
internal class ObnHost(private val c: PrinterConnection) : PrintHost {
    private val host = c.baseUrl().substringAfter("://").substringBefore(':').substringBefore('/')
    private val report = JSONObject()
    private val lock = Object()
    @Volatile private var connected = false
    private var lastError: String? = null

    private fun ensureConnected() {
        if (c.serial.isBlank()) throw IOException("Enter the printer's serial number")
        if (host.isBlank() || c.apiKey.isBlank()) throw IOException("Enter the printer's IP address and access code")
        val ctx = ObnCredentials.appContext()
        val dir = ObnCredentials.dir(ctx)
        ObnCredentials.writeDefaultConf(ctx)
        if (ObnNative.init(dir.path).isEmpty()) throw IOException("open-bamboo-networking failed to start")
        if (connected) return
        val rc = ObnNative.connect(c.serial, host, c.apiKey)
        if (rc != 0) throw IOException("Connection to the printer failed (obn $rc)")
        // obn connects asynchronously and reports the result as a "connect" event.
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (!connected && System.currentTimeMillis() < deadline) {
            drain()
            lastError?.let { throw IOException(it) }
            if (!connected) Thread.sleep(200)
        }
        if (!connected) throw IOException("The printer did not answer on $host (MQTT :8883)")
    }

    /** Applies queued obn events: connection changes and incremental printer reports. */
    private fun drain() {
        val events = runCatching { JSONArray(ObnNative.poll()) }.getOrNull() ?: return
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (e.optString("dev").let { it.isNotEmpty() && it != c.serial }) continue
            when (e.optString("kind")) {
                "connect" -> when (e.optInt("status")) {
                    0 -> { connected = true; lastError = null }
                    1 -> { connected = false; lastError = "Printer refused the connection: ${e.optString("text")}" }
                    else -> connected = false
                }
                "message" -> runCatching { JSONObject(e.optString("text")) }.getOrNull()?.optJSONObject("print")?.let { print ->
                    synchronized(lock) {
                        print.keys().forEach { k -> report.put(k, print.get(k)) }
                        lock.notifyAll()
                    }
                }
            }
        }
    }

    private fun request(payload: JSONObject) {
        ensureConnected()
        val rc = ObnNative.send(c.serial, payload.toString())
        if (rc != 0) throw IOException("Sending to the printer failed (obn $rc)")
    }

    private fun pushAll() = request(JSONObject().put("pushing", JSONObject().put("sequence_id", "0").put("command", "pushall")))

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
        drain()
        synchronized(lock) {
            if (report.length() == 0) {
                pushAll()
                val until = System.currentTimeMillis() + 4_000
                while (report.length() == 0 && System.currentTimeMillis() < until) {
                    lock.wait(250)
                    drain()
                }
            }
            val state = when (report.optString("gcode_state")) {
                "RUNNING", "PREPARE", "SLICING" -> PrinterStatus.State.PRINTING
                "PAUSE" -> PrinterStatus.State.PAUSED
                "FINISH" -> PrinterStatus.State.FINISHED
                "FAILED" -> PrinterStatus.State.ERROR
                "" -> PrinterStatus.State.OFFLINE
                else -> PrinterStatus.State.IDLE
            }
            return PrinterStatus(
                state,
                report.optInt("mc_percent", -1).takeIf { it >= 0 }?.let { it / 100f },
                report.optString("subtask_name").ifBlank { null },
                report.optInt("mc_remaining_time", -1).takeIf { it >= 0 }?.let { it * 60L },
                report.optDouble("nozzle_temper", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                report.optDouble("bed_temper", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
            )
        }
    }

    override fun control(action: PrintHost.JobAction): Boolean {
        val command = when (action) {
            PrintHost.JobAction.PAUSE -> "pause"
            PrintHost.JobAction.RESUME -> "resume"
            PrintHost.JobAction.CANCEL -> "stop"
        }
        // obn signs every {"print": ...} payload it sends.
        request(JSONObject().put("print", JSONObject().put("sequence_id", "0").put("command", command).put("param", "")))
        return true
    }

    override fun close() {
        if (connected) ObnNative.disconnect()
        connected = false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val CERT_TIMEOUT_MS = 15_000
    }
}
