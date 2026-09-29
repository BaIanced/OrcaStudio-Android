package com.orcaslicer.android.net

import com.orcaslicer.android.core.HostType
import com.orcaslicer.android.core.PrinterConnection
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Uploads G-code to a printer host and optionally starts the print. */
object PrintHostClient {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 120_000

    /** Checks that the host answers; returns a short description of what was found. */
    suspend fun test(connection: PrinterConnection): String = withContext(Dispatchers.IO) {
        val base = PrinterConnection.normalizeUrl(connection.url)
        when (connection.type) {
            HostType.MOONRAKER -> {
                val info = JSONObject(get("$base/server/info", connection)).optJSONObject("result")
                "Moonraker verbunden · Klipper: ${info?.optString("klippy_state") ?: "?"}"
            }
            HostType.OCTOPRINT -> "OctoPrint ${JSONObject(get("$base/api/version", connection)).optString("server")} verbunden"
            HostType.PRUSALINK -> "PrusaLink ${JSONObject(get("$base/api/version", connection)).optString("server")} verbunden"
            HostType.OTHER -> {
                get(base, connection)
                "Weboberfläche erreichbar"
            }
        }
    }

    /**
     * Uploads [file] as [remoteName]; with [startPrint] the host starts printing it right away.
     * [onProgress] receives 0..1.
     */
    suspend fun upload(
        connection: PrinterConnection,
        file: File,
        remoteName: String,
        startPrint: Boolean,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val base = PrinterConnection.normalizeUrl(connection.url)
        when (connection.type) {
            // Moonraker: POST /server/files/upload (multipart), form field print=true starts it.
            HostType.MOONRAKER -> multipart("$base/server/files/upload", connection, file, remoteName,
                mapOf("root" to "gcodes", "print" to startPrint.toString()), onProgress)
            // OctoPrint: POST /api/files/local (multipart), same "print" field.
            HostType.OCTOPRINT -> multipart("$base/api/files/local", connection, file, remoteName,
                mapOf("select" to startPrint.toString(), "print" to startPrint.toString()), onProgress)
            // PrusaLink: PUT the raw file to /api/v1/files/usb/<name>.
            HostType.PRUSALINK -> {
                val name = URLEncoder.encode(remoteName, "UTF-8").replace("+", "%20")
                val conn = open("$base/api/v1/files/usb/$name", connection, "PUT")
                conn.setRequestProperty("Content-Type", "text/x.gcode")
                conn.setRequestProperty("Overwrite", "?1")
                if (startPrint) conn.setRequestProperty("Print-After-Upload", "?1")
                conn.setFixedLengthStreamingMode(file.length())
                conn.outputStream.use { out -> copyWithProgress(file, out, file.length(), onProgress) }
                finish(conn)
            }
            HostType.OTHER -> throw IOException("Dieser Drucker-Typ unterstützt keinen Upload aus der App.")
        }
        Unit
    }

    private suspend fun multipart(
        url: String,
        connection: PrinterConnection,
        file: File,
        remoteName: String,
        fields: Map<String, String>,
        onProgress: (Float) -> Unit,
    ) {
        val boundary = "----OrcaAndroid" + UUID.randomUUID().toString().replace("-", "")
        val head = buildString {
            for ((k, v) in fields) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${remoteName.replace("\"", "")}\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val conn = open(url, connection, "POST")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
        conn.outputStream.use { out ->
            out.write(head)
            copyWithProgress(file, out, file.length(), onProgress)
            out.write(tail)
        }
        finish(conn)
    }

    private suspend fun copyWithProgress(file: File, out: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var sent = 0L
        file.inputStream().use { input ->
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                sent += n
                onProgress(if (total > 0) sent.toFloat() / total else 1f)
            }
        }
    }

    private fun open(url: String, connection: PrinterConnection, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = method != "GET"
            if (connection.apiKey.isNotBlank()) setRequestProperty("X-Api-Key", connection.apiKey)
        }

    private fun get(url: String, connection: PrinterConnection): String = finish(open(url, connection, "GET"))

    private fun finish(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> " – API-Schlüssel prüfen"
                    404 -> " – Adresse oder Drucker-Typ prüfen"
                    else -> ""
                }
                throw IOException("HTTP $code$hint")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}
