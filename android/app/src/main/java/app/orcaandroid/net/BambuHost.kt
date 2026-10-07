package app.orcaandroid.net

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Bambu Lab printers in LAN mode: FTPS (port 990) for uploads, MQTT over TLS (port 8883) for
 * status and print commands. User "bblp", password = the LAN access code shown on the printer.
 *
 * Uploads must be ".gcode.3mf" files (see Engine.exportGcode3mf); the print command references
 * the plate G-code inside the archive.
 */
internal class BambuHost(private val c: PrinterConnection) : PrintHost {
    private val host = c.baseUrl().substringAfter("://").substringBefore(':').substringBefore('/')
    private var mqtt: MqttClient? = null
    private val report = BambuReport()

    private fun ensureConnected(): MqttClient {
        mqtt?.takeIf { it.isConnected }?.let { return it }
        if (c.serial.isBlank()) throw IOException("Enter the printer's serial number")
        val client = MqttClient(host, 8883, "orca-android-${System.currentTimeMillis()}", "bblp", c.apiKey) { _, payload ->
            report.apply(String(payload))
        }
        client.connect()
        client.subscribe("device/${c.serial}/report")
        mqtt = client
        return client
    }

    private fun request(payload: JSONObject) = ensureConnected().publish("device/${c.serial}/request", payload.toString())

    private fun pushAll() = request(JSONObject().put("pushing", JSONObject().put("sequence_id", "0").put("command", "pushall")))

    override fun test(): String {
        ensureConnected()
        pushAll()
        return "Bambu Lab · ${c.serial}"
    }

    override fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit) {
        FtpsClient(host, "bblp", c.apiKey).upload(file, remoteName, onProgress)
        if (!startPrint) return
        val md5 = MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        request(JSONObject().put("print", JSONObject()
            .put("sequence_id", "0")
            .put("command", "project_file")
            .put("param", "Metadata/plate_1.gcode")
            .put("url", "file:///sdcard/$remoteName")
            .put("subtask_name", remoteName.removeSuffix(".gcode.3mf"))
            .put("md5", md5)
            .put("bed_leveling", true)
            .put("flow_cali", false)
            .put("vibration_cali", false)
            .put("layer_inspect", false)
            .put("timelapse", false)
            .put("use_ams", false)
            .put("profile_id", "0").put("project_id", "0").put("subtask_id", "0").put("task_id", "0")))
    }

    override fun status(): PrinterStatus {
        if (report.isEmpty) {
            pushAll()
            report.await(4_000) { !report.isEmpty }
        }
        return report.status()
    }

    override fun fullStatus(): PrinterStatus {
        pushAll()
        report.await(5_000) { report.hasTrays }
        return report.status()
    }

    override fun command(print: JSONObject): JSONObject? {
        val seq = BambuReport.nextSequence()
        request(JSONObject().put("print", JSONObject(print.toString()).put("sequence_id", seq)))
        var reply: JSONObject? = null
        report.await(5_000) { report.reply(seq)?.also { reply = it } != null }
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
        command(JSONObject().put("command", command).put("param", ""))
        return true
    }

    override fun close() {
        mqtt?.close()
        mqtt = null
    }
}
