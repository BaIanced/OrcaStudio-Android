package app.orcaandroid.net

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Minimal blocking HTTP helper shared by the HTTP-based hosts. */
internal class Http(private val headers: Map<String, String> = emptyMap()) {

    fun open(url: String, method: String = "GET"): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 8_000
        readTimeout = 120_000
        doOutput = method != "GET" && method != "DELETE"
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }

    fun get(url: String): String = finish(open(url))
    fun getJson(url: String): JSONObject {
        val body = get(url)
        return try {
            JSONObject(body)
        } catch (e: org.json.JSONException) {
            // Typically a web UI page instead of the API (wrong port or path).
            throw IOException("No printer API at $url (got a web page instead of data)")
        }
    }

    fun send(url: String, method: String, body: String = "", contentType: String = "application/json"): String {
        val c = open(url, method)
        if (method != "GET" && method != "DELETE") {
            c.setRequestProperty("Content-Type", contentType)
            val bytes = body.toByteArray()
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        return finish(c)
    }

    /** Streams [file] as a multipart field [fileField] after the plain [fields]. */
    fun multipart(url: String, file: File, remoteName: String, fields: Map<String, String>, fileField: String = "file", onProgress: (Float) -> Unit): String {
        val boundary = "----OrcaAndroid" + UUID.randomUUID().toString().replace("-", "")
        val head = buildString {
            for ((k, v) in fields) append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            append("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${remoteName.replace("\"", "")}\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = open(url, "POST")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
        c.outputStream.use { out ->
            out.write(head)
            copyWithProgress(file, out, onProgress)
            out.write(tail)
        }
        return finish(c)
    }

    /** Sends [file] as the raw request body. */
    fun raw(url: String, method: String, file: File, contentType: String, extraHeaders: Map<String, String> = emptyMap(), onProgress: (Float) -> Unit): String {
        val c = open(url, method)
        c.setRequestProperty("Content-Type", contentType)
        extraHeaders.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setFixedLengthStreamingMode(file.length())
        c.outputStream.use { out -> copyWithProgress(file, out, onProgress) }
        return finish(c)
    }

    private fun finish(c: HttpURLConnection): String {
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> " – check the API key / password (Moonraker: or add this device to trusted_clients)"
                    404 -> " – check the address and printer type"
                    409 -> " – the printer is busy"
                    else -> ""
                }
                throw IOException("HTTP $code$hint")
            }
            return body
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun copyWithProgress(file: File, out: OutputStream, onProgress: (Float) -> Unit) {
            val total = file.length().coerceAtLeast(1)
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            file.inputStream().use { input ->
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onProgress(sent.toFloat() / total)
                }
            }
        }

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

private fun keyHeaders(c: PrinterConnection) = if (c.apiKey.isBlank()) emptyMap() else mapOf("X-Api-Key" to c.apiKey)

// --- Klipper / Moonraker -------------------------------------------------------------------------------

internal class MoonrakerHost(private val c: PrinterConnection) : PrintHost {
    private val http = Http(keyHeaders(c))

    /**
     * Moonraker's API is usually proxied by the web UI's nginx on port 80 (Mainsail, Fluidd,
     * Qidi, Creality); if not, it listens on its own port 7125.
     */
    private val base: String by lazy {
        val configured = c.baseUrl()
        val candidates = listOf(configured) + (PrinterConnection.withPort(configured, 7125)?.let { listOf(it) } ?: emptyList())
        candidates.firstOrNull { url -> runCatching { http.getJson("$url/server/info") }.isSuccess } ?: configured
    }

    override fun test(): String {
        val info = http.getJson("$base/server/info").optJSONObject("result")
        return "Moonraker · Klipper: ${info?.optString("klippy_state") ?: "?"}"
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        http.multipart("$base/server/files/upload", file, remoteName, mapOf("root" to "gcodes", "print" to startPrint.toString()), onProgress = onProgress)
    }

    override fun status(): PrinterStatus {
        val r = http.getJson("$base/printer/objects/query?print_stats&display_status&virtual_sdcard&extruder&heater_bed")
            .getJSONObject("result").getJSONObject("status")
        val ps = r.optJSONObject("print_stats")
        val progress = r.optJSONObject("virtual_sdcard")?.optDouble("progress")?.toFloat()
        val duration = ps?.optDouble("print_duration") ?: 0.0
        val state = when (ps?.optString("state")) {
            "printing" -> PrinterStatus.State.PRINTING
            "paused" -> PrinterStatus.State.PAUSED
            "complete" -> PrinterStatus.State.FINISHED
            "error" -> PrinterStatus.State.ERROR
            else -> PrinterStatus.State.IDLE
        }
        val remaining = if (progress != null && progress > 0.01f) ((duration / progress) - duration).toLong() else null
        return PrinterStatus(
            state, progress, ps?.optString("filename"), remaining,
            r.optJSONObject("extruder")?.optDouble("temperature")?.toFloat(),
            r.optJSONObject("heater_bed")?.optDouble("temperature")?.toFloat(),
            ps?.optString("message")?.ifBlank { null },
        )
    }

    override fun control(action: PrintHost.JobAction): Boolean {
        val path = when (action) {
            PrintHost.JobAction.PAUSE -> "pause"
            PrintHost.JobAction.RESUME -> "resume"
            PrintHost.JobAction.CANCEL -> "cancel"
        }
        http.send("$base/printer/print/$path", "POST")
        return true
    }
}

// --- OctoPrint -----------------------------------------------------------------------------------------

internal class OctoPrintHost(private val c: PrinterConnection) : PrintHost {
    private val http = Http(keyHeaders(c))
    private val base = c.baseUrl()

    override fun test() = "OctoPrint ${http.getJson("$base/api/version").optString("server")}"

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        http.multipart("$base/api/files/local", file, remoteName, mapOf("select" to startPrint.toString(), "print" to startPrint.toString()), onProgress = onProgress)
    }

    override fun status(): PrinterStatus {
        val job = http.getJson("$base/api/job")
        val stateText = job.optString("state")
        val state = when {
            stateText.startsWith("Printing") -> PrinterStatus.State.PRINTING
            stateText.startsWith("Paus") -> PrinterStatus.State.PAUSED
            stateText.startsWith("Error") -> PrinterStatus.State.ERROR
            stateText.startsWith("Offline") -> PrinterStatus.State.OFFLINE
            else -> PrinterStatus.State.IDLE
        }
        val p = job.optJSONObject("progress")
        val temps = runCatching { http.getJson("$base/api/printer?exclude=sd,state").optJSONObject("temperature") }.getOrNull()
        return PrinterStatus(
            state,
            p?.optDouble("completion")?.takeIf { !it.isNaN() }?.let { (it / 100).toFloat() },
            job.optJSONObject("job")?.optJSONObject("file")?.optString("name"),
            p?.optLong("printTimeLeft")?.takeIf { it > 0 },
            temps?.optJSONObject("tool0")?.optDouble("actual")?.toFloat(),
            temps?.optJSONObject("bed")?.optDouble("actual")?.toFloat(),
        )
    }

    override fun control(action: PrintHost.JobAction): Boolean {
        val body = when (action) {
            PrintHost.JobAction.PAUSE -> """{"command":"pause","action":"pause"}"""
            PrintHost.JobAction.RESUME -> """{"command":"pause","action":"resume"}"""
            PrintHost.JobAction.CANCEL -> """{"command":"cancel"}"""
        }
        http.send("$base/api/job", "POST", body)
        return true
    }
}

// --- PrusaLink -----------------------------------------------------------------------------------------

internal class PrusaLinkHost(private val c: PrinterConnection) : PrintHost {
    private val http = Http(keyHeaders(c))
    private val base = c.baseUrl()

    override fun test() = "PrusaLink ${http.getJson("$base/api/version").optString("server")}"

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        val headers = mutableMapOf("Overwrite" to "?1")
        if (startPrint) headers["Print-After-Upload"] = "?1"
        http.raw("$base/api/v1/files/usb/${Http.enc(remoteName)}", "PUT", file, "text/x.gcode", headers, onProgress)
    }

    override fun status(): PrinterStatus {
        val s = http.getJson("$base/api/v1/status")
        val printer = s.optJSONObject("printer")
        val job = s.optJSONObject("job")
        val state = when (printer?.optString("state")) {
            "PRINTING" -> PrinterStatus.State.PRINTING
            "PAUSED" -> PrinterStatus.State.PAUSED
            "FINISHED" -> PrinterStatus.State.FINISHED
            "ERROR", "ATTENTION" -> PrinterStatus.State.ERROR
            else -> PrinterStatus.State.IDLE
        }
        return PrinterStatus(
            state, job?.optDouble("progress")?.let { (it / 100).toFloat() }, null, job?.optLong("time_remaining"),
            printer?.optDouble("temp_nozzle")?.toFloat(), printer?.optDouble("temp_bed")?.toFloat(),
        )
    }

    override fun control(action: PrintHost.JobAction): Boolean {
        val id = http.getJson("$base/api/v1/job").optInt("id", -1)
        if (id < 0) return false
        when (action) {
            PrintHost.JobAction.PAUSE -> http.send("$base/api/v1/job/$id/pause", "PUT")
            PrintHost.JobAction.RESUME -> http.send("$base/api/v1/job/$id/resume", "PUT")
            PrintHost.JobAction.CANCEL -> http.send("$base/api/v1/job/$id", "DELETE")
        }
        return true
    }
}

// --- Duet / RepRapFirmware (standalone HTTP API) --------------------------------------------------------

internal class DuetHost(private val c: PrinterConnection) : PrintHost {
    private val http = Http()
    private val base = c.baseUrl()

    private fun connect() {
        val r = http.getJson("$base/rr_connect?password=${Http.enc(c.apiKey)}&time=${System.currentTimeMillis() / 1000}")
        if (r.optInt("err", 0) != 0) throw IOException("Duet refused the connection (password?)")
    }

    private fun gcode(code: String) = http.get("$base/rr_gcode?gcode=${Http.enc(code)}")

    override fun test(): String {
        connect()
        return "RepRapFirmware ${http.getJson("$base/rr_status?type=2").optString("firmwareVersion")}"
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        connect()
        val path = "0:/gcodes/$remoteName"
        val r = JSONObject(http.raw("$base/rr_upload?name=${Http.enc(path)}", "POST", file, "application/octet-stream", onProgress = onProgress))
        if (r.optInt("err", 0) != 0) throw IOException("Upload rejected by the printer")
        if (startPrint) gcode("M32 \"$path\"")
    }

    override fun status(): PrinterStatus {
        connect()
        val s = http.getJson("$base/rr_status?type=3")
        val state = when (s.optString("status")) {
            "P", "R" -> PrinterStatus.State.PRINTING
            "S", "D", "A" -> PrinterStatus.State.PAUSED
            "H" -> PrinterStatus.State.ERROR
            else -> PrinterStatus.State.IDLE
        }
        val temps = s.optJSONObject("temps")
        return PrinterStatus(
            state, s.optDouble("fractionPrinted", Double.NaN).takeIf { !it.isNaN() }?.let { (it / 100).toFloat() }, null,
            s.optJSONObject("timesLeft")?.optLong("file")?.takeIf { it > 0 },
            temps?.optJSONArray("current")?.optDouble(1)?.toFloat(), temps?.optJSONObject("bed")?.optDouble("current")?.toFloat(),
        )
    }

    override fun control(action: PrintHost.JobAction): Boolean {
        connect()
        gcode(when (action) { PrintHost.JobAction.PAUSE -> "M25"; PrintHost.JobAction.RESUME -> "M24"; PrintHost.JobAction.CANCEL -> "M0" })
        return true
    }
}

// --- Repetier-Server -------------------------------------------------------------------------------------

internal class RepetierHost(private val c: PrinterConnection) : PrintHost {
    private val http = Http()
    private val base = c.baseUrl()
    private fun key() = if (c.apiKey.isBlank()) "" else "apikey=${Http.enc(c.apiKey)}"

    private fun slug(): String {
        val list = JSONArray(http.get("$base/printer/list?${key()}"))
        if (list.length() == 0) throw IOException("No printer configured in Repetier-Server")
        return list.getJSONObject(0).getString("slug")
    }

    override fun test() = "Repetier-Server · ${slug()}"

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        // "job" uploads start printing, "model" uploads are stored only.
        val kind = if (startPrint) "job" else "model"
        http.multipart("$base/printer/$kind/${slug()}?a=upload&${key()}", file, remoteName, mapOf("name" to remoteName.substringBeforeLast('.')),
            fileField = "filename", onProgress = onProgress)
    }

    override fun status(): PrinterStatus {
        val list = JSONArray(http.get("$base/printer/list?${key()}"))
        val p = list.optJSONObject(0) ?: return PrinterStatus(PrinterStatus.State.OFFLINE)
        val printing = p.optString("job").let { it.isNotBlank() && it != "none" }
        val state = when {
            p.optInt("online", 1) == 0 -> PrinterStatus.State.OFFLINE
            printing && p.optBoolean("paused") -> PrinterStatus.State.PAUSED
            printing -> PrinterStatus.State.PRINTING
            else -> PrinterStatus.State.IDLE
        }
        return PrinterStatus(state, p.optDouble("done", Double.NaN).takeIf { !it.isNaN() }?.let { (it / 100).toFloat() }, p.optString("job"))
    }

    override fun control(action: PrintHost.JobAction): Boolean {
        if (action != PrintHost.JobAction.CANCEL) return false
        http.get("$base/printer/api/${slug()}?a=stopJob&${key()}")
        return true
    }
}

// --- ESP3D ---------------------------------------------------------------------------------------------------

internal class Esp3dHost(private val c: PrinterConnection) : PrintHost {
    private val http = Http()
    private val base = c.baseUrl()

    private fun command(cmd: String) = http.get("$base/command?plain=${Http.enc(cmd)}")

    override fun test(): String {
        command("[ESP800]")
        return "ESP3D"
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        http.multipart("$base/upload", file, remoteName, mapOf("path" to "/", "/${remoteName}S" to file.length().toString()),
            fileField = "myfile[]", onProgress = onProgress)
        if (startPrint) {
            command("M23 /$remoteName")
            command("M24")
        }
    }

    override fun status(): PrinterStatus? = null
}

// --- MKS WiFi --------------------------------------------------------------------------------------------------

internal class MksHost(private val c: PrinterConnection) : PrintHost {
    private val http = Http()
    private val host = c.baseUrl().substringAfter("://").substringBefore(':').substringBefore('/')

    /** MKS boards take G-code commands on a raw TCP port. */
    private fun send(cmd: String) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 8080), 5_000)
            s.getOutputStream().write("$cmd\n".toByteArray())
        }
    }

    override fun test(): String {
        send("M115")
        return "MKS WiFi"
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        http.raw("http://$host/upload?X-Filename=${Http.enc(remoteName)}", "POST", file, "application/octet-stream", onProgress = onProgress)
        if (startPrint) {
            send("M23 $remoteName")
            send("M24")
        }
    }

    override fun status(): PrinterStatus? = null

    override fun control(action: PrintHost.JobAction): Boolean {
        send(when (action) { PrintHost.JobAction.PAUSE -> "M25"; PrintHost.JobAction.RESUME -> "M24"; PrintHost.JobAction.CANCEL -> "M26" })
        return true
    }
}

// --- Web UI only -------------------------------------------------------------------------------------------------

internal class WebOnlyHost(private val c: PrinterConnection) : PrintHost {
    override fun test(): String {
        Http().get(c.webUiUrl())
        return "Web UI reachable"
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) =
        throw IOException("This printer type does not support uploads from the app")

    override fun status(): PrinterStatus? = null
}
