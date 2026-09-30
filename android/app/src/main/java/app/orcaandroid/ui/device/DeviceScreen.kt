package app.orcaandroid.ui.device

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
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
import app.orcaandroid.net.HostType
import app.orcaandroid.net.PrintHost
import app.orcaandroid.net.PrinterConnection
import app.orcaandroid.net.PrinterStatus
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.UiState
import app.orcaandroid.ui.WIDE_LAYOUT
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
                vm.refreshStatus()
                delay(5000)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_LAYOUT
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusCard(state, vm, connection) { editing = true }
                    }
                    WebUi(connection, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(8.dp)) { StatusCard(state, vm, connection, compact = true) { editing = true } }
                    WebUi(connection, Modifier.weight(1f).fillMaxWidth())
                }
            }
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
            Button(onClick = { vm.setConnection(s) }) { Text(stringResource(R.string.use_preset_host, s.url)) }
        }
        OutlinedButton(onClick = onSetup) { Text(stringResource(R.string.set_up_connection)) }
    }
}

@Composable
private fun StatusCard(state: UiState, vm: AppViewModel, connection: PrinterConnection, compact: Boolean = false, onEdit: () -> Unit) {
    val status = state.printerStatus
    var confirmCancel by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.printer.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(connection.type.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(connection.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = vm::refreshStatus) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Settings, stringResource(R.string.connection)) }
            }
            if (status == null) {
                Text(stringResource(R.string.status_unknown), style = MaterialTheme.typography.bodySmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stateLabel(status.state), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    status.nozzleTemp?.let { Text("🔥 %.0f°".format(Locale.ROOT, it), style = MaterialTheme.typography.bodySmall) }
                    status.bedTemp?.let { Text("▭ %.0f°".format(Locale.ROOT, it), style = MaterialTheme.typography.bodySmall) }
                }
                status.file?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                status.progress?.takeIf { status.isActive || it > 0f }?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()} %" + (status.remainingSeconds?.let { " · " + formatDuration(it.toDouble()) } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                status.message?.takeIf { !compact }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (status.isActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.state == PrinterStatus.State.PAUSED)
                            FilledTonalIconButton(onClick = { vm.controlJob(PrintHost.JobAction.RESUME) }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.resume)) }
                        else
                            FilledTonalIconButton(onClick = { vm.controlJob(PrintHost.JobAction.PAUSE) }) { Icon(Icons.Default.Pause, stringResource(R.string.pause)) }
                        FilledTonalIconButton(onClick = { confirmCancel = true }) { Icon(Icons.Default.Cancel, stringResource(R.string.cancel_print)) }
                        if (!compact) TextButton(onClick = vm::monitorPrinter) { Text(stringResource(R.string.notify_progress)) }
                    }
                }
            }
        }
    }
    if (confirmCancel) ConfirmDialog(stringResource(R.string.cancel_print), stringResource(R.string.cancel_print_text), stringResource(R.string.cancel_print),
        { vm.controlJob(PrintHost.JobAction.CANCEL) }) { confirmCancel = false }
}

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

    DisposableEffect(Unit) { onDispose { vm.stopDiscovery() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connection)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerField(stringResource(R.string.host_type), type.id, HostType.entries.map { PickerItem(it.id, it.label) },
                    { type = HostType.fromId(it) })
                val bambu = type == HostType.BAMBU
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
                if (bambu) Text(stringResource(R.string.bambu_lan_hint), style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.discovered_printers), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (state.discovering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = vm::startDiscovery) { Text(stringResource(R.string.search)) }
                }
                state.discovered.forEach { d ->
                    Text("${d.name} · ${d.type.label}\n${d.address}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable {
                            type = d.type; url = d.address; if (d.serial.isNotEmpty()) serial = d.serial
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
                        testResult = runCatching { vm.testConnection(current) }.fold({ "✓ $it" }, { e ->
                            val msg = e.message ?: e.toString()
                            // Blocked local network access (Android 17 permission, work profile, VPN rules).
                            if (msg.contains("EPERM") || msg.contains("not permitted", true)) "✗ $msg\n$permissionHint" else "✗ $msg"
                        })
                        testing = false
                    }
                }, enabled = current.isConfigured && !testing) { Text(stringResource(R.string.test)) }
                TextButton(onClick = { onDismiss(); vm.setConnection(current) }, enabled = current.isConfigured) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = {
            Row {
                if (state.connection != null) TextButton(onClick = { onDismiss(); vm.setConnection(null) }) { Text(stringResource(R.string.remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
