package app.orcaandroid.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import app.orcaandroid.net.OrcaCloud
import app.orcaandroid.net.OrcaCloudLoopback
import app.orcaandroid.ui.device.LoginJsBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Orca Cloud account (More screen): sign in / out, and the plugins the account subscribed to. */
@Composable
internal fun OrcaCloudSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<OrcaCloud.User?>(null) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var plugins by remember { mutableStateOf<List<OrcaCloud.Plugin>?>(null) }

    LaunchedEffect(Unit) {
        user = onIo { OrcaCloud.user(context) }.getOrNull()
        busy = false
    }

    fun signIn(login: () -> OrcaCloud.User) {
        showLogin = false
        busy = true
        message = null
        scope.launch {
            onIo(login).fold({ user = it }, { message = "✗ ${it.message}" })
            busy = false
        }
    }

    Text(
        user?.let { u -> stringResource(R.string.orca_cloud_signed_in, u.nickname.ifEmpty { u.userName }) } ?: stringResource(R.string.orca_cloud_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (user == null) {
            OutlinedButton(onClick = { showLogin = true }, enabled = !busy) { Text(stringResource(R.string.orca_cloud_sign_in)) }
        } else {
            OutlinedButton(onClick = {
                busy = true
                message = null
                scope.launch {
                    onIo { OrcaCloud.subscribedPlugins(context) }.fold({ plugins = it }, { message = "✗ ${it.message}" })
                    // A rejected session signs out while loading.
                    user = onIo { OrcaCloud.user(context) }.getOrNull()
                    busy = false
                }
            }, enabled = !busy) { Text(stringResource(R.string.orca_cloud_plugins)) }
            TextButton(onClick = {
                busy = true
                scope.launch {
                    onIo { OrcaCloud.logout(context) }
                    user = null
                    busy = false
                }
            }, enabled = !busy) { Text(stringResource(R.string.bambu_account_sign_out)) }
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (showLogin) OrcaCloudLoginDialog(
        onClose = { error ->
            showLogin = false
            if (error != null) message = "✗ $error"
        },
        onLogin = ::signIn,
    )
    plugins?.let { list ->
        AlertDialog(
            onDismissRequest = { plugins = null },
            title = { Text(stringResource(R.string.orca_cloud_plugins)) },
            text = {
                if (list.isEmpty()) Text(stringResource(R.string.orca_cloud_no_plugins))
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.id }) { p ->
                        var status by remember(p.id) {
                            mutableStateOf(OrcaCloud.pluginDir(context, p.id).list()?.firstOrNull { it.startsWith("plugin.") })
                        }
                        var installing by remember(p.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name + if (p.version.isNotEmpty()) " ${p.version}" else "", style = MaterialTheme.typography.bodyLarge)
                                val sub = listOf(p.author, p.types.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                            }
                            if (installing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else TextButton(onClick = {
                                installing = true
                                scope.launch {
                                    status = onIo { OrcaCloud.downloadPlugin(context, p) }
                                        .fold({ "✓ " + context.getString(R.string.orca_cloud_plugin_installed, it.name) }, { "✗ ${it.message}" })
                                    installing = false
                                }
                            }) { Text(stringResource(R.string.orca_cloud_plugin_install)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { plugins = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

/** Runs [block] on the IO dispatcher. Failures become a [Result]; cancellation is not a failure. */
private suspend fun <T> onIo(block: () -> T): Result<T> = try {
    Result.success(withContext(Dispatchers.IO) { block() })
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * Orca Cloud's sign-in page in a WebView, driven like the desktop's ZUserLogin
 * (src-orca src/slic3r/GUI/WebUserLoginDialog.cpp): the page finds the slicer through `window.wx`
 * and asks for `get_login_cmd`; the reply is the PKCE `login_config`. An e-mail sign-in ends with a
 * `user_login` message carrying the tokens; Google / GitHub etc. ask for `thirdparty_login`, which
 * opens the browser and comes back through the [OrcaCloudLoopback] redirect with a code.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun OrcaCloudLoginDialog(onClose: (error: String?) -> Unit, onLogin: (login: () -> OrcaCloud.User) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var done by remember { mutableStateOf(false) }
    var waitingForBrowser by remember { mutableStateOf(false) }

    fun close(error: String?) {
        if (done) return
        done = true
        onClose(error)
    }

    // The sign-in itself runs in the caller's scope, which outlives this dialog.
    fun login(block: () -> OrcaCloud.User) {
        if (done) return
        done = true
        onLogin(block)
    }

    val loopback = remember {
        runCatching {
            OrcaCloudLoopback(context.packageName) { code, state ->
                scope.launch { login { OrcaCloud.loginWithCode(context, code, state) } }
            }
        }.onFailure { Log.w("OrcaCloud", "loopback: ${it.message}") }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { loopback?.close() } }

    fun reply(json: String) = web?.evaluateJavascript("window.postMessage($json, '*')", null)

    fun openExternal(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    val bridge = remember {
        LoginJsBridge { message ->
            val j = runCatching { JSONObject(message) }.getOrNull() ?: return@LoginJsBridge
            // Called on a WebView thread; the page's address is read on the main thread.
            scope.launch {
                if (!isOrcaSite(web?.url)) {
                    Log.w("OrcaCloud", "ignored message from ${web?.url}")
                    return@launch
                }
                val data = j.optJSONObject("data") ?: JSONObject()
                when (j.optString("command")) {
                    "get_login_cmd" -> reply(OrcaCloud.loginConfig(loopback?.port ?: OrcaCloud.LOOPBACK_PORT))
                    "user_login" -> login { OrcaCloud.loginWithMessage(context, data) }
                    "thirdparty_login" -> data.optString("url").takeIf { it.isNotEmpty() }?.let {
                        if (loopback == null) close(context.getString(R.string.orca_cloud_no_loopback))
                        else { waitingForBrowser = true; openExternal(it) }
                    }
                    "new_webpage" -> data.optString("url").takeIf { it.isNotEmpty() }?.let { openExternal(it) }
                    "get_localhost_url" -> reply(JSONObject()
                        .put("command", "get_localhost_url")
                        .put("sequence_id", j.optString("sequence_id"))
                        .put("response", JSONObject()
                            .put("base_url", "http://localhost:${loopback?.port ?: OrcaCloud.LOOPBACK_PORT}")
                            .put("result", "success"))
                        .toString())
                    else -> Log.i("OrcaCloud", "ignored sign-in page command ${j.optString("command")}")
                }
            }
        }
    }

    Dialog(onDismissRequest = { close(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.orca_cloud_sign_in), Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { close(null) }) { Text(stringResource(R.string.cancel)) }
                }
                if (waitingForBrowser) Text(stringResource(R.string.orca_cloud_browser_hint), Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) {
                    val dark = app.orcaandroid.ui.components.appIsDark()
                    AndroidView(
                        factory = { ctx ->
                            app.orcaandroid.ui.components.themedWebView(ctx, dark).apply {
                                // AndroidView's default WRAP_CONTENT makes WebView lay pages out with height 0 (CSS 100vh = 0).
                                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // The page looks for window.wx (or webkit.messageHandlers.wx) when it loads.
                                addJavascriptInterface(bridge, "wx")
                                webViewClient = WebViewClient()
                                web = this
                                loadUrl(OrcaCloud.loginUrl())
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
}

/** Only Orca's own sites may drive the sign-in. */
private fun isOrcaSite(url: String?): Boolean {
    val host = url?.let { Uri.parse(it).host }?.lowercase() ?: return false
    return host == "orcaslicer.com" || host.endsWith(".orcaslicer.com")
}
