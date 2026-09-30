package app.orcaandroid.net

import java.io.File
import org.json.JSONObject

/** The printer-host protocols the app can talk to. */
enum class HostType(val id: String, val label: String, val canUpload: Boolean, val hasWebUi: Boolean) {
    MOONRAKER("moonraker", "Klipper (Moonraker)", true, true),
    OCTOPRINT("octoprint", "OctoPrint", true, true),
    PRUSALINK("prusalink", "PrusaLink", true, true),
    DUET("duet", "Duet / RepRapFirmware", true, true),
    REPETIER("repetier", "Repetier-Server", true, true),
    ESP3D("esp3d", "ESP3D", true, true),
    MKS("mks", "MKS WiFi", true, false),
    BAMBU("bambu", "Bambu Lab (LAN)", true, false),
    OTHER("other", "Web UI only", false, true);

    companion object {
        /** Maps both our ids and the desktop's host_type values. */
        fun fromId(id: String?): HostType = entries.firstOrNull { it.id == id } ?: when (id) {
            null, "" -> MOONRAKER
            else -> OTHER
        }
    }
}

/**
 * Network access to a physical printer. For Bambu printers [url] is the IP address, [apiKey] the
 * LAN access code and [serial] the printer's serial number.
 */
data class PrinterConnection(
    val type: HostType = HostType.MOONRAKER,
    val url: String = "",
    val apiKey: String = "",
    /** Web UI to show in the device tab; empty = [url]. */
    val webUrl: String = "",
    val serial: String = "",
) {
    val isConfigured get() = url.isNotBlank()

    fun baseUrl(): String = normalizeUrl(url)
    fun webUiUrl(): String = normalizeUrl(webUrl.ifBlank { url })

    fun toJson(): String = JSONObject()
        .put("type", type.id).put("url", url).put("apiKey", apiKey).put("webUrl", webUrl).put("serial", serial).toString()

    companion object {
        fun fromJson(json: String): PrinterConnection = JSONObject(json).let {
            PrinterConnection(HostType.fromId(it.optString("type")), it.optString("url"), it.optString("apiKey"),
                it.optString("webUrl"), it.optString("serial"))
        }

        /** Adds "http://" when the user typed a bare host name or IP. */
        fun normalizeUrl(url: String): String {
            val u = url.trim().trimEnd('/')
            return if (u.isEmpty() || u.contains("://")) u else "http://$u"
        }
    }
}

/** A printer's current job state, for the device tab and the progress notification. */
data class PrinterStatus(
    val state: State,
    /** 0..1, or null when unknown. */
    val progress: Float? = null,
    val file: String? = null,
    val remainingSeconds: Long? = null,
    val nozzleTemp: Float? = null,
    val bedTemp: Float? = null,
    val message: String? = null,
) {
    enum class State { IDLE, PRINTING, PAUSED, FINISHED, ERROR, OFFLINE }

    val isActive get() = state == State.PRINTING || state == State.PAUSED
}

/** One printer-host protocol. Implementations block; call them off the main thread. */
interface PrintHost {
    /** Checks that the host answers; returns a short description. */
    fun test(): String

    /** Uploads [file] as [remoteName]; with [startPrint] the host starts printing it right away. */
    fun upload(file: File, remoteName: String, startPrint: Boolean, onProgress: (Float) -> Unit)

    /** Current job state, or null if the protocol cannot report it. */
    fun status(): PrinterStatus?

    /** Pause/resume/cancel of the running job; false if unsupported. */
    fun control(action: JobAction): Boolean = false

    fun close() {}

    enum class JobAction { PAUSE, RESUME, CANCEL }

    companion object {
        fun create(c: PrinterConnection): PrintHost = when (c.type) {
            HostType.MOONRAKER -> MoonrakerHost(c)
            HostType.OCTOPRINT -> OctoPrintHost(c)
            HostType.PRUSALINK -> PrusaLinkHost(c)
            HostType.DUET -> DuetHost(c)
            HostType.REPETIER -> RepetierHost(c)
            HostType.ESP3D -> Esp3dHost(c)
            HostType.MKS -> MksHost(c)
            HostType.BAMBU -> BambuHost(c)
            HostType.OTHER -> WebOnlyHost(c)
        }
    }
}
