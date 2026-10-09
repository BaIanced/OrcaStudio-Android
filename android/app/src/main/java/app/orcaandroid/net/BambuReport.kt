package app.orcaandroid.net

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Bambu printer's state from its MQTT reports (topic device/<serial>/report), shared by
 * [BambuHost] and [ObnHost]. Reports are incremental, so their "print" fields are merged into one
 * snapshot. A report that answers one of our commands (its "command" is not push_status) is kept
 * apart by sequence_id, as the desktop reads command results (DeviceManager.cpp: "result" "fail").
 */
internal class BambuReport {
    private val snapshot = JSONObject()
    private val replies = HashMap<String, JSONObject>()
    private val lock = Object()
    private var trayReports = 0

    /** Applies one report (the whole MQTT payload). */
    fun apply(payload: String) {
        val print = runCatching { JSONObject(payload) }.getOrNull()?.optJSONObject("print") ?: return
        synchronized(lock) {
            val command = print.optString("command")
            val seq = print.optString("sequence_id")
            if (command.isNotEmpty() && command != "push_status" && seq.isNotEmpty()) replies[seq] = print
            else {
                print.keys().forEach { k -> snapshot.put(k, print.get(k)) }
                if (print.has("ams") || print.has("vt_tray") || print.has("vir_slot")) trayReports++
            }
            lock.notifyAll()
        }
    }

    val isEmpty get() = synchronized(lock) { snapshot.length() == 0 }

    /**
     * Waits up to [timeoutMs] until [done] holds. [pump] delivers pending reports (obn queues them
     * for polling); a pushed host notifies through [apply] instead.
     */
    fun await(timeoutMs: Long, pump: () -> Unit = {}, done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            pump()
            synchronized(lock) {
                if (done()) return true
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(minOf(left, 200L))
            }
        }
    }

    /** The printer's answer to the command sent with [seq], if it has arrived. */
    fun reply(seq: String): JSONObject? = synchronized(lock) { replies.remove(seq) }

    /** How many reports carried AMS / external spool data; a newer count means fresh tray data. */
    val trayCount get() = synchronized(lock) { trayReports }

    fun status(): PrinterStatus = synchronized(lock) {
        val r = snapshot
        val alerts = alerts(r)
        val state = when (r.optString("gcode_state")) {
            "RUNNING", "PREPARE", "SLICING" -> PrinterStatus.State.PRINTING
            "PAUSE" -> PrinterStatus.State.PAUSED
            "FINISH" -> PrinterStatus.State.FINISHED
            // A print the user stopped also ends in FAILED, but without an error code or message.
            "FAILED" -> if (alerts.isEmpty()) PrinterStatus.State.STOPPED else PrinterStatus.State.ERROR
            "" -> PrinterStatus.State.OFFLINE
            else -> PrinterStatus.State.IDLE
        }
        PrinterStatus(
            state,
            r.optInt("mc_percent", -1).takeIf { it >= 0 }?.let { it / 100f },
            r.optString("subtask_name").ifBlank { null },
            r.optInt("mc_remaining_time", -1).takeIf { it >= 0 }?.let { it * 60L },
            r.optDouble("nozzle_temper", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
            r.optDouble("bed_temper", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
            alerts = alerts,
            trays = trays(r),
            jobId = r.optString("job_id").ifBlank { null },
            subtaskId = r.optString("subtask_id").ifBlank { null },
        )
    }

    private fun alerts(r: JSONObject): List<PrinterAlert> {
        val out = ArrayList<PrinterAlert>()
        val printError = r.optLong("print_error", 0L).toInt()
        if (printError != 0) PrinterAlert("%08X".format(printError), printError).takeUnless { it.ignored }?.let(out::add)
        // HMS items: attr = module, module number, part, reserved; code = level << 16 | message.
        // The catalog keys them as "%08X%08X" (DevHMS.cpp parse_hms_info / get_long_error_code).
        val hms = r.optJSONArray("hms") ?: JSONArray()
        for (i in 0 until hms.length()) {
            val item = hms.optJSONObject(i) ?: continue
            val alert = PrinterAlert("%08X%08X".format(item.optLong("attr"), item.optLong("code")))
            if (!alert.ignored && out.none { it.code == alert.code }) out += alert
        }
        return out
    }

    /** Loaded trays in the desktop's sync order: AMS slots (by AMS, then slot), then the external spool(s). */
    private fun trays(r: JSONObject): List<PrinterTray> {
        val out = ArrayList<PrinterTray>()
        val units = r.optJSONObject("ams")?.optJSONArray("ams") ?: JSONArray()
        for (u in 0 until units.length()) {
            val unit = units.optJSONObject(u) ?: continue
            val amsId = unit.optString("id").toIntOrNull() ?: continue
            val slots = unit.optJSONArray("tray") ?: continue
            for (t in 0 until slots.length()) {
                val tray = slots.optJSONObject(t) ?: continue
                val slotId = tray.optString("id").toIntOrNull() ?: continue
                tray(tray, amsId.toString(), slotId.toString(), amsName(amsId, slotId))?.let(out::add)
            }
        }
        // Newer firmware lists the external spools in vir_slot; older reports have one vt_tray, which the desktop files under id 255.
        val virtual = r.optJSONArray("vir_slot")?.let { a -> List(a.length()) { a.optJSONObject(it) }.filterNotNull().map { it to it.optString("id") } }
            ?: listOfNotNull(r.optJSONObject("vt_tray")).map { it to "255" }
        for ((v, id) in virtual) tray(v, id, "0", "Ext")?.let(out::add)
        return out
    }

    /** A loaded tray; null for an empty slot (the desktop's sync skips those as well). */
    private fun tray(t: JSONObject, amsId: String, slotId: String, name: String): PrinterTray? {
        val type = t.optString("tray_type")
        val id = t.optString("tray_info_idx")
        if (type.isEmpty() && id.isEmpty()) return null
        val cols = t.optJSONArray("cols")?.let { a -> List(a.length()) { htmlColor(a.optString(it)) } }.orEmpty()
        return PrinterTray(amsId, slotId, name, id, type, htmlColor(t.optString("tray_color")), cols, t.optInt("ctype", 0).toString())
    }

    companion object {
        private val sequence = AtomicLong(20_000)

        /** A fresh sequence_id, so a command's reply can be told apart. */
        fun nextSequence(): String = sequence.incrementAndGet().toString()

        /** AMS slot names as the desktop shows them (A1, B4, HT-A ...). */
        private fun amsName(ams: Int, slot: Int) = when (ams) {
            in 0..25 -> "${'A' + ams}${slot + 1}"
            in 128..152 -> "HT-${'A' + (ams - 128)}"
            else -> "$ams-$slot"
        }

        /** Report colours are RRGGBBAA; the slicer uses #RRGGBB. */
        private fun htmlColor(c: String) = if (c.length >= 6) "#" + c.substring(0, 6).uppercase() else ""

        /**
         * The command behind a button of the printer's error prompt, as the desktop's
         * DeviceErrorDialog::on_button_click sends it (DeviceManager.cpp command_*). Null: the button
         * only closes the prompt ([ALERT_CANCEL]) or is not supported here.
         */
        fun alertCommand(button: Int, alert: PrinterAlert, status: PrinterStatus): JSONObject? {
            val err = alert.printError.toString()
            val job = status.jobId.orEmpty()
            fun cmd(command: String, vararg fields: Pair<String, Any>) =
                JSONObject().put("command", command).also { o -> fields.forEach { (k, v) -> o.put(k, v) } }
            return when (button) {
                2, 3, 4, 12, 28 -> cmd("resume", "err" to err, "param" to "reserve", "job_id" to job)
                5 -> cmd("stop", "err" to err, "param" to "reserve", "job_id" to job)
                7 -> cmd("ams_control", "param" to "done")
                8, 9, 34 -> cmd("ams_control", "param" to "resume")
                51 -> cmd("ams_control", "param" to "abort")
                11 -> cmd("clean_print_error", "subtask_id" to status.subtaskId.orEmpty(), "print_error" to alert.printError)
                23 -> cmd("idle_ignore", "err" to err, "type" to 0)
                25, 27 -> cmd("ignore", "err" to err, "param" to "reserve", "job_id" to job)
                29 -> cmd("buzzer_ctrl", "mode" to 0)
                35 -> cmd("auto_stop_ams_dry")
                else -> null
            }
        }

        /** Prompt buttons this app can act on (see [alertCommand]); the rest need the desktop or the printer. */
        val SUPPORTED_BUTTONS = setOf(2, 3, 4, 5, 7, 8, 9, 11, 12, 23, 25, 27, 28, 29, 34, 35, 37, 51)
        const val ALERT_CANCEL = 37
        /** "Not Extruded Yet, Retry" keeps the prompt open in the desktop app. */
        const val ALERT_RETRY_EXTRUDED = 8
    }
}

/** A loaded filament: an AMS slot or the external spool ("Ext"). Colours are #RRGGBB. */
data class PrinterTray(
    val amsId: String,
    val slotId: String,
    val name: String,
    val filamentId: String,
    val type: String,
    val color: String,
    val colors: List<String>,
    val colorType: String,
)

/**
 * A message the printer shows: a print error (8 hex digits, e.g. "07FF8007", with its number in
 * [printError]) or an HMS item (16 hex digits).
 */
data class PrinterAlert(
    val code: String,
    val printError: Int = 0,
    /** The printer's own text for it ([HmsCatalog]); null if unknown. */
    val text: String? = null,
    /** The desktop's prompt buttons for a print error (DeviceErrorDialog::ActionButton ids). */
    val buttons: List<Int> = emptyList(),
) {
    val isPrintError get() = code.length == 8

    /** As the printer and Bambu Handy write it: "07FF-8007", "0300-0200-0001-0008". */
    val display get() = code.chunked(4).joinToString("-")

    /** Codes the desktop never shows (HMS.cpp is_auto_ignored_hms_error_code / is_expected_task_cancel_error_code). */
    val ignored get() = code.take(8) in setOf("0500409D", "0501409D", "0502409D", "0503409D", "0300400C")
}
