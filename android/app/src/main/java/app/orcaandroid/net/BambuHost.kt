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
    /** Bambu reports are incremental; merged into one snapshot. */
    private val report = JSONObject()
    private val lock = Object()

    private fun ensureConnected(): MqttClient {
        mqtt?.takeIf { it.isConnected }?.let { return it }
        if (c.serial.isBlank()) throw IOException("Enter the printer's serial number")
        val client = MqttClient(host, 8883, "orca-android-${System.currentTimeMillis()}", "bblp", c.apiKey) { _, payload ->
            runCatching {
                val msg = JSONObject(String(payload))
                msg.optJSONObject("print")?.let { print ->
                    synchronized(lock) {
                        print.keys().forEach { k -> report.put(k, print.get(k)) }
                        lock.notifyAll()
                    }
                }
            }
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
        synchronized(lock) {
            if (report.length() == 0) {
                pushAll()
                lock.wait(4_000)
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
        request(JSONObject().put("print", JSONObject().put("sequence_id", "0").put("command", command).put("param", "")))
        return true
    }

    override fun close() {
        mqtt?.close()
        mqtt = null
    }
}
