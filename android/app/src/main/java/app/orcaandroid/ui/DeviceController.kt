package app.orcaandroid.ui

import app.orcaandroid.R
import app.orcaandroid.core.PresetType
import app.orcaandroid.net.AmsMapping
import app.orcaandroid.net.BambuReport
import app.orcaandroid.net.Discovery
import app.orcaandroid.net.HmsCatalog
import app.orcaandroid.net.HostType
import app.orcaandroid.net.PrintHost
import app.orcaandroid.net.PrinterAlert
import app.orcaandroid.net.PrinterConnection
import app.orcaandroid.net.PrinterModel
import app.orcaandroid.net.PrinterTray
import app.orcaandroid.service.PrintMonitorService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The connection to the physical printer: setup, discovery, upload, job status and control. */
class DeviceController(private val store: Store) {
    private val settings = store.settings

    /** Set by the view model; builds the file name and the .gcode.3mf for uploads. */
    lateinit var files: FileController

    fun setConnection(connection: PrinterConnection?) {
        val printer = store.value.printer ?: return
        settings.setConnection(printer, connection)
        store.update { it.copy(connection = connection) }
    }

    /** Loads the saved connection of the printer and the print host its preset names, if any. */
    fun refreshConnection() = store.update { s ->
        val values = s.presetValues[PresetType.PRINTER].orEmpty()
        val host = values["print_host"].orEmpty().trim('"')
        val suggested = if (host.isBlank()) null else PrinterConnection(
            type = HostType.fromId(values["host_type"]),
            url = host,
            apiKey = values["printhost_apikey"].orEmpty().trim('"'),
            webUrl = values["print_host_webui"].orEmpty().trim('"'),
        )
        s.copy(connection = s.printer?.let(settings::connection), suggestedConnection = suggested)
    }

    suspend fun testConnection(connection: PrinterConnection): String = withHost(connection) { it.test() }

    private var discoveryJob: Job? = null

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        store.update { it.copy(discovered = emptyList(), discovering = true) }
        discoveryJob = store.scope.launch {
            withTimeoutOrNull(DISCOVERY_MS) {
                Discovery(store.app).scan().collect { p -> store.update { it.copy(discovered = it.discovered + p) } }
            }
            store.update { it.copy(discovering = false) }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        store.update { it.copy(discovering = false) }
    }

    /**
     * Sends the shown G-code to the connected printer, optionally starting the print. A Bambu print
     * with an AMS first asks which tray each used filament prints from ([UiState.amsPrompt]), as the
     * desktop's print dialog does; [confirmPrint] then starts it.
     */
    fun upload(startPrint: Boolean, confirmed: Boolean = false) = store.launch {
        val s = store.value
        val result = s.shownResult ?: return@launch
        val connection = s.connection ?: return@launch
        if (s.upload is UploadState.Running) return@launch
        if (!confirmed && !result.external) {
            PrinterModel.mismatch(s.printer, connection.serial)?.let { (sliced, connected) ->
                store.update { it.copy(mismatchPrompt = MismatchPrompt(sliced, connected, startPrint)) }
                return@launch
            }
        }
        store.update { it.copy(upload = UploadState.Running(0f, startPrint), amsPrompt = null) }
        try {
            val bambu = (connection.type == HostType.BAMBU || connection.type == HostType.BAMBU_SIGNED) && !result.external
            // Bambu printers print .gcode.3mf archives.
            val (file, name) = if (bambu) {
                files.exportGcode3mf(connection.type.name)!! to files.gcodeFileName().removeSuffix(".gcode") + ".gcode.3mf"
            } else {
                files.exportedGcode(connection.type.name)!! to files.gcodeFileName()
            }
            if (startPrint && bambu) {
                val used = withContext(Dispatchers.IO) { AmsMapping.usedFilaments(file) }
                val trays = if (used.isEmpty()) emptyList() else withHost(connection) { it.fullStatus()?.trays.orEmpty() }
                if (trays.isNotEmpty()) {
                    pendingPrint = file to name
                    val count = maxOf(s.filaments.size, used.maxOf { it.id } + 1)
                    store.update { it.copy(upload = null, amsPrompt = AmsPrompt(used, trays, AmsMapping.auto(used, trays), count)) }
                    return@launch
                }
            }
            send(connection, file, name, startPrint, null)
        } catch (e: Exception) {
            store.update { it.copy(upload = null) }
            throw e
        }
    }

    fun dismissMismatch() = store.update { it.copy(mismatchPrompt = null) }

    /** Sends anyway after the user confirmed the model warning. */
    fun confirmMismatch() {
        val prompt = store.value.mismatchPrompt ?: return
        dismissMismatch()
        upload(prompt.startPrint, confirmed = true)
    }

    /** The exported archive waiting for the user's tray choice. */
    private var pendingPrint: Pair<File, String>? = null

    /** Starts the print waiting in [UiState.amsPrompt] with [choice] (filament slot → tray). */
    fun confirmPrint(choice: Map<Int, PrinterTray>) = store.launch {
        val prompt = store.value.amsPrompt ?: return@launch
        val connection = store.value.connection ?: return@launch
        val (file, name) = pendingPrint ?: return@launch
        pendingPrint = null
        store.update { it.copy(amsPrompt = null, upload = UploadState.Running(0f, true)) }
        try {
            send(connection, file, name, true, AmsMapping(prompt.filamentCount, choice))
        } catch (e: Exception) {
            store.update { it.copy(upload = null) }
            throw e
        }
    }

    fun cancelPrint() {
        pendingPrint = null
        store.update { it.copy(amsPrompt = null) }
    }

    private suspend fun send(connection: PrinterConnection, file: File, name: String, startPrint: Boolean, ams: AmsMapping?) {
        withHost(connection) { host ->
            host.upload(file, name, startPrint, ams) { p -> store.update { it.copy(upload = UploadState.Running(p, startPrint)) } }
        }
        store.update { it.copy(upload = UploadState.Done(store.str(if (startPrint) R.string.print_started else R.string.sent_to_printer))) }
        if (startPrint) monitorPrinter()
    }

    /** Shows the print progress as a notification (foreground service). */
    fun monitorPrinter() = store.value.printer?.let { runCatching { PrintMonitorService.start(store.app, it) } }

    fun controlJob(action: PrintHost.JobAction) = store.launch {
        val connection = store.value.connection ?: return@launch
        withHost(connection) { it.control(action) }
        refreshStatus()
    }

    /** Answers the printer's prompt for [alert] with one of its buttons, as the desktop's error dialog does. */
    fun answerAlert(alert: PrinterAlert, button: Int) = store.launch {
        val connection = store.value.connection ?: return@launch
        val status = store.value.printerStatus ?: return@launch
        val command = BambuReport.alertCommand(button, alert, status) ?: return@launch
        withHost(connection) { it.command(command) }
        refreshStatus()
    }

    /** Refreshes the printer status once (device tab). */
    fun refreshStatus() = store.launch {
        val connection = store.value.connection ?: return@launch
        val result = withHost(connection) { host -> runCatching { host.status()?.let { HmsCatalog.describe(store.app, connection.serial, it) } } }
        store.container.printerStatus.value = result.getOrNull()
        store.update { it.copy(printerStatusError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }) }
    }

    /** The filaments loaded in the connected printer (AMS slots, external spool), from a full report. */
    suspend fun loadedFilaments(): List<PrinterTray> {
        val connection = store.value.connection?.takeIf { it.type == HostType.BAMBU || it.type == HostType.BAMBU_SIGNED }
            ?: throw IOException(store.str(R.string.sync_filaments_no_bambu))
        return withHost(connection) { it.fullStatus()?.trays.orEmpty() }
    }

    private suspend fun <T> withHost(connection: PrinterConnection, block: (PrintHost) -> T): T = withContext(Dispatchers.IO) {
        val host = PrintHost.create(connection)
        try { block(host) } finally { host.close() }
    }

    private companion object {
        const val DISCOVERY_MS = 20_000L
    }
}
