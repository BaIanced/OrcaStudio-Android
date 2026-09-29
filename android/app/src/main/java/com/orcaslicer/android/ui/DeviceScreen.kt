package com.orcaslicer.android.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.orcaslicer.android.core.HostType
import com.orcaslicer.android.core.PrinterConnection
import com.orcaslicer.android.net.PrintHostClient
import kotlinx.coroutines.launch

/**
 * The printer's own web interface (Mainsail, Fluidd, OctoPrint, ...) with its live camera view,
 * like the desktop app's "Device" tab.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeviceScreen(state: UiState, vm: MainViewModel) {
    val connection = state.connection
    var editing by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(100) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.printer ?: "Kein Drucker", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    connection?.let { "${it.type.label} · ${it.webUiUrl()}" } ?: "Nicht verbunden",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connection != null) {
                IconButton(onClick = { loadError = null; webView?.reload() }) { Icon(Icons.Default.Refresh, "Neu laden") }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(connection.webUiUrl())))
                }) { Text("Im Browser") }
            }
            IconButton(onClick = { editing = true }, enabled = state.printer != null) { Icon(Icons.Default.Settings, "Verbindung") }
        }
        if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (connection == null) {
                Column(
                    Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Drucker verbinden", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Trage die Adresse deines Druckers ein (z. B. Klipper mit Mainsail/Fluidd oder OctoPrint). " +
                            "Hier siehst du dann seine Weboberfläche mit Live-Kamera, und gesliced G-Code lässt sich direkt senden.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { editing = true }, enabled = state.printer != null) { Text("Verbindung einrichten") }
                }
            } else {
                val url = connection.webUiUrl()
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            // Web UIs on the LAN are plain HTTP and may embed camera streams from other ports.
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) loadError = error?.description?.toString() ?: "Fehler"
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                    update = { view -> if (view.tag != url) { view.tag = url; view.loadUrl(url) } },
                    modifier = Modifier.fillMaxSize(),
                )
                loadError?.let { err ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Drucker nicht erreichbar", style = MaterialTheme.typography.titleMedium)
                        Text("$url\n$err", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { loadError = null; webView?.reload() }) { Text("Erneut versuchen") }
                    }
                }
            }
        }
    }

    // Back navigates inside the web UI first.
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    if (editing) {
        ConnectionDialog(
            initial = connection ?: state.suggestedConnection ?: PrinterConnection(),
            onDismiss = { editing = false },
            onRemove = if (connection != null) {
                { editing = false; vm.setConnection(null) }
            } else null,
            onSave = { editing = false; vm.setConnection(it) },
        )
    }
}

@Composable
fun ConnectionDialog(
    initial: PrinterConnection,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)?,
    onSave: (PrinterConnection) -> Unit,
) {
    var type by remember { mutableStateOf(initial.type) }
    var url by remember { mutableStateOf(initial.url) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var webUrl by remember { mutableStateOf(initial.webUrl) }
    var typeMenu by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val current = PrinterConnection(type, url.trim(), apiKey.trim(), webUrl.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Druckerverbindung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(type.label) }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        HostType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { type = t; typeMenu = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; testResult = null },
                    label = { Text("Adresse (z. B. 192.168.1.50 oder drucker.local:7125)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testResult = null },
                    label = { Text("API-Schlüssel (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = webUrl,
                    onValueChange = { webUrl = it },
                    label = { Text("Weboberfläche, falls abweichend (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        testResult = "Teste …"
                        scope.launch {
                            testResult = runCatching { PrintHostClient.test(current) }.getOrElse { "Fehler: ${it.message ?: it}" }
                        }
                    }, enabled = url.isNotBlank()) { Text("Verbindung testen") }
                    testResult?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }, enabled = url.isNotBlank()) { Text("Speichern") } },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Entfernen") } }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
