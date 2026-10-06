package app.orcaandroid.ui.device

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orcaandroid.R
import app.orcaandroid.net.BambuAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bambu account for the Bambu printer types: sign in once, then pick a printer of the account to
 * fill in its serial number and LAN access code (no need to read the code off the printer).
 */
@Composable
internal fun BambuAccountSection(onPrinter: (BambuAccount.Printer) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<String?>(null) }
    var printers by remember { mutableStateOf<List<BambuAccount.Printer>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }

    fun loadPrinters() {
        busy = true
        message = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { BambuAccount.printers(context) } }.fold(
                { list ->
                    printers = list
                    if (list.isEmpty()) message = context.getString(R.string.bambu_account_no_printers)
                    else if (list.size == 1) onPrinter(list[0])
                },
                { message = "✗ ${it.message}" })
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        user = runCatching { withContext(Dispatchers.IO) { BambuAccount.userName(context) } }.getOrNull()
    }

    Text(
        user?.let { stringResource(R.string.bambu_account_signed_in, it) } ?: stringResource(R.string.bambu_account_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (user == null) {
            OutlinedButton(onClick = { showLogin = true }, enabled = !busy) { Text(stringResource(R.string.bambu_account_sign_in)) }
        } else {
            OutlinedButton(onClick = ::loadPrinters, enabled = !busy) { Text(stringResource(R.string.bambu_account_use_printer)) }
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { BambuAccount.logout(context) } }
                    user = null
                    printers = null
                }
            }, enabled = !busy) { Text(stringResource(R.string.bambu_account_sign_out)) }
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    printers?.takeIf { it.size > 1 }?.forEach { p ->
        Text("${p.name} · ${p.model} · ${p.serial}" + if (p.online) "" else " (offline)", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().clickable { onPrinter(p) }.padding(vertical = 6.dp))
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (showLogin) BambuLoginDialog { name, error ->
        showLogin = false
        if (name != null) {
            user = name
            loadPrinters()
        } else if (error != null) {
            message = "✗ $error"
        }
    }
}

/**
 * Bambu's sign-in page in a WebView. Like in Bambu Studio, the page reports the result through a
 * "wx" script message handler: {"command":"user_ticket_login","data":{"ticket":...}} (or a ready
 * "user_login" message). The bridge below provides that handler under the names the desktop
 * WebViews use (window.wx, webkit.messageHandlers.wx, chrome.webview).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun BambuLoginDialog(onResult: (name: String?, error: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var working by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    fun finish(name: String?, error: String?) {
        if (done) return
        done = true
        onResult(name, error)
    }

    fun complete(block: () -> String) {
        if (working) return
        working = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }.fold({ finish(it, null) }, { finish(null, it.message ?: it.toString()) })
        }
    }

    val bridge = remember {
        LoginJsBridge { message ->
            val j = runCatching { JSONObject(message) }.getOrNull() ?: return@LoginJsBridge
            // Called on a WebView thread; the page's address is read on the main thread.
            scope.launch {
                if (!isBambuSite(web?.url)) {
                    Log.w("BambuLogin", "ignored message from ${web?.url}")
                    return@launch
                }
                when (j.optString("command")) {
                    "user_ticket_login" -> {
                        val ticket = j.optJSONObject("data")?.optString("ticket").orEmpty()
                        if (ticket.isNotEmpty()) complete { BambuAccount.loginWithTicket(context, ticket) }
                    }
                    "user_login" -> complete { BambuAccount.loginWithUserInfo(context, message) }
                    else -> Log.i("BambuLogin", "ignored sign-in page command ${j.optString("command")}")
                }
            }
        }
    }

    Dialog(onDismissRequest = { finish(null, null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bambu_account_sign_in), Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleMedium)
                    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { finish(null, null) }) { Text(stringResource(R.string.cancel)) }
                }
                Box(Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // The sign-in page offers the slicer hand-over only to Bambu's slicers.
                                settings.userAgentString = "${settings.userAgentString} BBL-Slicer/v${BambuAccount.clientVersion(ctx)} (dark) BBL-Language/en"
                                addJavascriptInterface(bridge, "orcaNative")
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) =
                                        view.evaluateJavascript(BRIDGE_JS, null)
                                    override fun onPageFinished(view: WebView, url: String?) = view.evaluateJavascript(BRIDGE_JS, null)
                                }
                                web = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        val url = runCatching { withContext(Dispatchers.IO) { BambuAccount.loginUrl(context) } }
            .getOrElse { finish(null, it.message); return@LaunchedEffect }
        w.loadUrl(url)
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
}

/** JavaScript interface object; a named class so WebView's reflection sees a public method. */
internal class LoginJsBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(message: String) = onMessage(message)
}

/** Only Bambu's own sign-in sites may complete a sign-in. */
private fun isBambuSite(url: String?): Boolean {
    val host = url?.let { Uri.parse(it).host }?.lowercase() ?: return false
    return listOf("bambulab.com", "bambulab.cn").any { host == it || host.endsWith(".$it") }
}

private const val BRIDGE_JS = """(function(){
  if (window.__orcaBridge) return; window.__orcaBridge = 1;
  function send(m){ orcaNative.postMessage(typeof m === 'string' ? m : JSON.stringify(m)); }
  window.wx = window.wx || {}; window.wx.postMessage = send;
  window.webkit = window.webkit || {}; window.webkit.messageHandlers = window.webkit.messageHandlers || {};
  window.webkit.messageHandlers.wx = { postMessage: send };
  window.chrome = window.chrome || {}; window.chrome.webview = window.chrome.webview || {};
  window.chrome.webview.postMessage = send;
})();"""
