package app.orcaandroid.ui

import app.orcaandroid.R
import app.orcaandroid.core.PresetType
import app.orcaandroid.net.Discovery
import app.orcaandroid.net.HostType
import app.orcaandroid.net.PrintHost
import app.orcaandroid.net.PrinterConnection
import app.orcaandroid.service.PrintMonitorService
import java.io.File
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

    /** Sends the shown G-code to the connected printer, optionally starting the print. */
    fun upload(startPrint: Boolean) = store.launch {
        val s = store.value
        val result = s.shownResult ?: return@launch
        val connection = s.connection ?: return@launch
        if (s.upload is UploadState.Running) return@launch
        store.update { it.copy(upload = UploadState.Running(0f, startPrint)) }
        try {
            // Bambu printers print .gcode.3mf archives.
            val (file, name) = if (connection.type == HostType.BAMBU && !result.external) {
                files.exportGcode3mf()!! to files.gcodeFileName().removeSuffix(".gcode") + ".gcode.3mf"
            } else {
                File(result.gcodeFile) to files.gcodeFileName()
            }
            withHost(connection) { host ->
                host.upload(file, name, startPrint) { p -> store.update { it.copy(upload = UploadState.Running(p, startPrint)) } }
            }
            store.update { it.copy(upload = UploadState.Done(store.str(if (startPrint) R.string.print_started else R.string.sent_to_printer))) }
            if (startPrint) monitorPrinter()
        } catch (e: Exception) {
            store.update { it.copy(upload = null) }
            throw e
        }
    }

    /** Shows the print progress as a notification (foreground service). */
    fun monitorPrinter() = store.value.printer?.let { runCatching { PrintMonitorService.start(store.app, it) } }

    fun controlJob(action: PrintHost.JobAction) = store.launch {
        val connection = store.value.connection ?: return@launch
        withHost(connection) { it.control(action) }
    }

    /** Refreshes the printer status once (device tab). */
    fun refreshStatus() = store.launch {
        val connection = store.value.connection ?: return@launch
        val result = withHost(connection) { runCatching { it.status() } }
        store.container.printerStatus.value = result.getOrNull()
        store.update { it.copy(printerStatusError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }) }
    }

    private suspend fun <T> withHost(connection: PrinterConnection, block: (PrintHost) -> T): T = withContext(Dispatchers.IO) {
        val host = PrintHost.create(connection)
        try { block(host) } finally { host.close() }
    }

    private companion object {
        const val DISCOVERY_MS = 20_000L
    }
}
