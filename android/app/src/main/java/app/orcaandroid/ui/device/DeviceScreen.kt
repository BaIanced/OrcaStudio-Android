package app.orcaandroid.ui.device

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.orcaandroid.net.ObnCredentials
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orcaandroid.R
import app.orcaandroid.net.BambuReport
import app.orcaandroid.net.HostType
import app.orcaandroid.net.PrintHost
import app.orcaandroid.net.PrinterAlert
import app.orcaandroid.net.PrinterConnection
import app.orcaandroid.net.PrinterStatus
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.components.ConfirmDialog
import app.orcaandroid.ui.components.PickerField
import app.orcaandroid.ui.components.PickerItem
import app.orcaandroid.ui.components.formatDuration
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Device tab: printer status and job control, plus the printer's own web UI (Mainsail, Fluidd, ...). */
@Composable
fun DeviceScreen(state: UiState, vm: AppViewModel) {
    var editing by remember { mutableStateOf(false) }
    val connection = state.connection
    if (connection == null || !connection.isConfigured) {
        NotConnected(state, vm) { editing = true }
    } else {
        LaunchedEffect(connection) {
            while (true) {
                vm.device.refreshStatus()
                delay(5000)
            }
        }
        // A slim bar above the web UI, so Mainsail/Fluidd keep the full width.
        Column(Modifier.fillMaxSize()) {
            StatusBar(state, vm, connection) { editing = true }
            WebUi(connection, Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (editing) ConnectionDialog(state, vm) { editing = false }
}

@Composable
private fun NotConnected(state: UiState, vm: AppViewModel, onSetup: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.no_connection_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.no_connection_text, state.printer.orEmpty()), style = MaterialTheme.typography.bodyMedium)
        state.suggestedConnection?.let { s ->
            Button(onClick = { vm.device.setConnection(s) }) { Text(stringResource(R.string.use_preset_host, s.url)) }
        }
        OutlinedButton(onClick = onSetup) { Text(stringResource(R.string.set_up_connection)) }
    }
}

/** Printer, state, temperatures and progress in one line; job controls and settings on the right. */
@Composable
private fun StatusBar(state: UiState, vm: AppViewModel, connection: PrinterConnection, onEdit: () -> Unit) {
    val status = state.printerStatus
    val error = state.printerStatusError
    var confirmCancel by remember { mutableStateOf(false) }
    var showAlerts by remember { mutableStateOf(false) }
    var dismissedPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    val line = when {
        !connection.type.canUpload -> stringResource(R.string.web_ui_only_short)
        status != null -> listOfNotNull(
            stateLabel(status.state),
            status.nozzleTemp?.let { "🔥 %.0f°".format(Locale.ROOT, it) },
            status.bedTemp?.let { "▭ %.0f°".format(Locale.ROOT, it) },
            status.progress?.takeIf { status.isActive }?.let { p ->
                "${(p * 100).toInt()} %" + (status.remainingSeconds?.let { " · " + formatDuration(it.toDouble()) } ?: "")
            },
            status.file?.takeIf { status.isActive },
        ).joinToString("  ·  ")
        error != null -> stringResource(R.string.status_failed, error)
        else -> stringResource(R.string.status_unknown)
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text("${state.printer.orEmpty()}  ·  ${connection.url}", style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (status == null && error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if (status?.isActive == true) {
                    if (status.state == PrinterStatus.State.PAUSED)
                        IconButton(onClick = { vm.device.controlJob(PrintHost.JobAction.RESUME) }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.resume)) }
                    else
                        IconButton(onClick = { vm.device.controlJob(PrintHost.JobAction.PAUSE) }) { Icon(Icons.Default.Pause, stringResource(R.string.pause)) }
                    IconButton(onClick = { confirmCancel = true }) { Icon(Icons.Default.Cancel, stringResource(R.string.cancel_print)) }
                    IconButton(onClick = vm.device::monitorPrinter) { Icon(Icons.Default.Notifications, stringResource(R.string.notify_progress)) }
                }
                IconButton(onClick = vm.device::refreshStatus) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Settings, stringResource(R.string.connection)) }
            }
            status?.progress?.takeIf { status.isActive }?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
            status?.alerts?.firstOrNull()?.let { first ->
                Row(Modifier.fillMaxWidth().clickable { showAlerts = true }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text(alertText(first) + if (status.alerts.size > 1) "  (+${status.alerts.size - 1})" else "",
                        Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    // The printer's prompt pops up like the desktop's error dialog, once per error until it changes.
    val prompt = status?.alerts?.firstOrNull { it.isPrintError }
    LaunchedEffect(prompt?.code) { if (prompt?.code != dismissedPrompt) dismissedPrompt = null }
    if (prompt != null && prompt.code != dismissedPrompt) AlertPrompt(prompt, vm) { dismissedPrompt = prompt.code }
    if (showAlerts && status != null) AlertList(status.alerts, { showAlerts = false }) { dismissedPrompt = null; showAlerts = false }
    if (confirmCancel) ConfirmDialog(stringResource(R.string.cancel_print), stringResource(R.string.cancel_print_text), stringResource(R.string.cancel_print),
        { vm.device.controlJob(PrintHost.JobAction.CANCEL) }) { confirmCancel = false }
}

@Composable
private fun alertText(a: PrinterAlert) = a.text ?: stringResource(R.string.printer_error_code, a.display)

/** A print error the printer waits on, with the desktop's buttons for it (DeviceErrorDialog). */
@Composable
private fun AlertPrompt(alert: PrinterAlert, vm: AppViewModel, onClose: () -> Unit) {
    val buttons = alert.buttons.filter { it in BambuReport.SUPPORTED_BUTTONS }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.printer_message)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(alertText(alert))
                Text("[${alert.display}]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (alert.buttons.any { it !in BambuReport.SUPPORTED_BUTTONS })
                    Text(stringResource(R.string.printer_message_unsupported), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                buttons.forEach { b ->
                    TextButton(onClick = {
                        if (b != BambuReport.ALERT_CANCEL) vm.device.answerAlert(alert, b)
                        // "Not Extruded Yet, Retry" keeps the prompt open, as in the desktop app.
                        if (b != BambuReport.ALERT_RETRY_EXTRUDED) onClose()
                    }) { Text(alertButtonLabel(b)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

/** Everything the printer currently reports; a print error can be answered from here again. */
@Composable
private fun AlertList(alerts: List<PrinterAlert>, onClose: () -> Unit, onAnswer: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.printer_messages)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                alerts.forEach { a ->
                    Column(Modifier.fillMaxWidth().then(if (a.isPrintError) Modifier.clickable(onClick = onAnswer) else Modifier)) {
                        Text(alertText(a), style = MaterialTheme.typography.bodyMedium)
                        Text("[${a.display}]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun alertButtonLabel(id: Int) = stringResource(
    when (id) {
        2 -> R.string.alert_btn_resume_printing
        3 -> R.string.alert_btn_resume_defects
        4 -> R.string.alert_btn_resume_solved
        5 -> R.string.alert_btn_stop
        7 -> R.string.alert_btn_extruded
        8 -> R.string.alert_btn_retry_extruded
        9 -> R.string.alert_btn_finished_continue
        11 -> R.string.ok
        12 -> R.string.alert_btn_loaded_resume
        23 -> R.string.alert_btn_no_reminder
        25 -> R.string.alert_btn_ignore_no_reminder
        27 -> R.string.alert_btn_ignore_resume
        28 -> R.string.alert_btn_solved_resume
        29 -> R.string.alert_btn_fire_alarm
        34 -> R.string.alert_btn_retry_solved
        35 -> R.string.alert_btn_stop_drying
        51 -> R.string.alert_btn_abort
        else -> R.string.cancel
    }
)

@Composable
private fun stateLabel(s: PrinterStatus.State) = stringResource(
    when (s) {
        PrinterStatus.State.IDLE -> R.string.state_idle
        PrinterStatus.State.PRINTING -> R.string.state_printing
        PrinterStatus.State.PAUSED -> R.string.state_paused
        PrinterStatus.State.FINISHED -> R.string.state_finished
        PrinterStatus.State.ERROR -> R.string.state_error
        PrinterStatus.State.OFFLINE -> R.string.state_offline
    }
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebUi(connection: PrinterConnection, modifier: Modifier) {
    if (!connection.type.hasWebUi) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_web_ui), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val url = connection.webUiUrl()
    var web by remember { mutableStateOf<WebView?>(null) }
    var loadError by remember(url) { mutableStateOf<String?>(null) }
    Surface(modifier) {
        Box {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                                if (request.isForMainFrame) loadError = "${error.description} (${error.errorCode})"
                            }
                            override fun onPageFinished(view: WebView, pageUrl: String?) {
                                if (loadError != null && view.progress == 100 && view.title?.isNotBlank() == true) loadError = null
                            }
                        }
                        loadUrl(url)
                        web = this
                    }
                },
                update = { v -> if (v.url?.startsWith(url) != true && v.tag != url) { v.tag = url; v.loadUrl(url) } },
                modifier = Modifier.fillMaxSize(),
            )
            loadError?.let { err ->
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.web_ui_error, url), style = MaterialTheme.typography.titleMedium)
                    Text(err, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { loadError = null; web?.loadUrl(url) }) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
}

@Composable
private fun ConnectionDialog(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    val initial = state.connection ?: state.suggestedConnection ?: PrinterConnection()
    var type by remember { mutableStateOf(initial.type) }
    var url by remember { mutableStateOf(initial.url) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var webUrl by remember { mutableStateOf(initial.webUrl) }
    var serial by remember { mutableStateOf(initial.serial) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = PrinterConnection(type, url.trim(), apiKey.trim(), webUrl.trim(), serial.trim())
    val permissionHint = stringResource(R.string.local_network_hint)

    DisposableEffect(Unit) { onDispose { vm.device.stopDiscovery() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connection)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerField(stringResource(R.string.host_type), type.id, HostType.entries.map { PickerItem(it.id, it.label) },
                    { type = HostType.fromId(it) })
                val bambu = type == HostType.BAMBU || type == HostType.BAMBU_SIGNED
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(if (bambu) R.string.ip_address else R.string.host_url)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type != HostType.OTHER && type != HostType.MKS) {
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text(stringResource(if (bambu) R.string.access_code else R.string.api_key)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                if (bambu) OutlinedTextField(serial, { serial = it }, label = { Text(stringResource(R.string.serial_number)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (type.hasWebUi) OutlinedTextField(webUrl, { webUrl = it }, label = { Text(stringResource(R.string.web_ui_url)) },
                    placeholder = { Text(url) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == HostType.BAMBU) Text(stringResource(R.string.bambu_lan_hint), style = MaterialTheme.typography.bodySmall)
                if (type == HostType.BAMBU_SIGNED) ObnCredentialsSection()
                if (bambu) BambuAccountSection { p ->
                    // A cloud-bound printer prints only with signed commands; the IP comes from discovery when found.
                    type = HostType.BAMBU_SIGNED
                    serial = p.serial
                    if (p.accessCode.isNotEmpty()) apiKey = p.accessCode
                    state.discovered.firstOrNull { it.serial == p.serial }?.let { url = it.address }
                    if (state.discovered.none { it.serial == p.serial } && !state.discovering) vm.device.startDiscovery()
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.discovered_printers), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (state.discovering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = vm.device::startDiscovery) { Text(stringResource(R.string.search)) }
                }
                state.discovered.forEach { d ->
                    Text("${d.name} · ${d.type.label}\n${d.address}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable {
                            // A discovered Bambu printer keeps the signed mode if the user chose it.
                            type = if (d.type == HostType.BAMBU && type == HostType.BAMBU_SIGNED) type else d.type; url = d.address; if (d.serial.isNotEmpty()) serial = d.serial
                        }.padding(vertical = 6.dp))
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = runCatching { vm.device.testConnection(current) }.fold({ "✓ $it" }, { e ->
                            val msg = e.message ?: e.toString()
                            // Blocked local network access (Android 17 permission, work profile, VPN rules).
                            if (msg.contains("EPERM") || msg.contains("not permitted", true)) "✗ $msg\n$permissionHint" else "✗ $msg"
                        })
                        testing = false
                    }
                }, enabled = current.isConfigured && !testing) { Text(stringResource(R.string.test)) }
                TextButton(onClick = { onDismiss(); vm.device.setConnection(current) }, enabled = current.isConfigured) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = {
            Row {
                if (state.connection != null) TextButton(onClick = { onDismiss(); vm.device.setConnection(null) }) { Text(stringResource(R.string.remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Import of the user's own slicer credentials for [HostType.BAMBU_SIGNED] (open-bamboo-networking). */
@Composable
private fun ObnCredentialsSection() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(ObnCredentials.missing(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        message = runCatching { ObnCredentials.import(context, uris) }.fold(
            { context.getString(R.string.obn_imported, it.joinToString()) },
            { "✗ ${it.message}" })
        missing = ObnCredentials.missing(context)
    }
    Text(stringResource(R.string.obn_hint), style = MaterialTheme.typography.bodySmall)
    Text(
        if (missing.isEmpty()) stringResource(R.string.obn_credentials_ok)
        else stringResource(R.string.obn_credentials_missing, missing.joinToString()),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.obn_import)) }
        if (missing.size < 3) TextButton(onClick = { ObnCredentials.clear(context); missing = ObnCredentials.missing(context); message = null }) {
            Text(stringResource(R.string.remove))
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
