package app.orcaandroid.ui.settings

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orcaandroid.R
import app.orcaandroid.plugins.Plugins
import app.orcaandroid.ui.components.appIsDark
import app.orcaandroid.ui.components.themedWebView
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A plugin's page (a Pages capability), hosted like the desktop's PluginPage
 * (src-orca src/slic3r/plugin/host/PluginPages.cpp): the HTML from get_ui() with the `window.orca`
 * bridge; window.orca.postMessage() reaches the plugin's on_message(), and the plugin's
 * post_message() reaches the page's window.orca.onMessage() handlers.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun PluginPageDialog(pluginKey: String, capability: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(pluginKey, capability) {
        runCatching { Plugins.openPage(pluginKey, capability) }.fold({ html = it }, { error = it.message ?: it.toString() })
    }
    LaunchedEffect(web) {
        val view = web ?: return@LaunchedEffect
        Plugins.pageMessages.collect { m ->
            if (m.pluginKey == pluginKey && m.capability == capability) view.evaluateJavascript(dispatchScript(m.json), null)
        }
    }
    DisposableEffect(Unit) {
        onDispose { scope.launch { Plugins.closePage(pluginKey, capability) } }
    }

    val bridge = remember {
        object {
            // Called on a WebView thread.
            @JavascriptInterface
            fun postMessage(payload: String) {
                val j = runCatching { JSONObject(payload) }.getOrNull() ?: return
                if (j.optString("channel") != "orca") return
                if (j.optString("kind") != "message") {
                    Log.w("OrcaPlugins", "page used window.orca '${j.optString("kind")}', not supported by pages")
                    return
                }
                // on_message() takes the value as JSON text.
                val data = when (val d = j.opt("data")) {
                    null, JSONObject.NULL -> "null"
                    is String -> JSONObject.quote(d)
                    else -> d.toString()
                }
                scope.launch { Plugins.pageMessage(pluginKey, capability, data) }
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(capability, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val page = html
                    when {
                        error != null -> Text("✗ $error", Modifier.padding(16.dp))
                        page == null -> CircularProgressIndicator()
                        else -> {
                            val dark = appIsDark()
                            AndroidView(
                                factory = { ctx ->
                                    themedWebView(ctx, dark).apply {
                                        // AndroidView's default WRAP_CONTENT lays pages out with height 0 (CSS 100vh = 0).
                                        layoutParams = android.view.ViewGroup.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        addJavascriptInterface(bridge, "wx")
                                        webViewClient = WebViewClient()
                                        // Like the desktop's SetPage content: an opaque origin, links load in place.
                                        loadDataWithBaseURL(null, withBridge(page), "text/html", "utf-8", null)
                                        web = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The page HTML with the bridge script first in <head>, so it runs before the page's own scripts. */
private fun withBridge(html: String): String {
    val script = "<script>$PAGE_BRIDGE_JS</script>"
    val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
    return if (head != null) html.substring(0, head.range.last + 1) + script + html.substring(head.range.last + 1) else script + html
}

/** WebPanel::post_to_page: hands a plugin message to window.__orcaDispatch once the bridge exists. */
private fun dispatchScript(message: String): String {
    val json = if (runCatching { org.json.JSONTokener(message).nextValue() }.isSuccess) message else JSONObject.quote(message)
    return "(function dispatch(payload, attempts) {" +
        "if (typeof window.__orcaDispatch === 'function') { window.__orcaDispatch(payload); return; }" +
        "if (attempts < 100) window.setTimeout(function() { dispatch(payload, attempts + 1); }, 25);" +
        "})({data: $json}, 0);"
}

// PLUGIN_PAGE_BRIDGE_JS from src-orca src/slic3r/plugin/host/PluginPages.cpp.
private const val PAGE_BRIDGE_JS = """
(function () {
  if (window.top !== window.self) return;
  if (window.orca) return;
  var handlers = [];
  function deliver(payload, attempts) {
    try {
      if (window.wx && typeof window.wx.postMessage === 'function') {
        window.wx.postMessage(payload);
        return;
      }
    } catch (e) { }
    if (attempts < 100)
      window.setTimeout(function () { deliver(payload, attempts + 1); }, 25);
  }
  function send(data) {
    deliver(JSON.stringify({
      channel: 'orca', kind: 'message', data: (data === undefined ? null : data)
    }), 0);
  }
  window.orca = {
    postMessage: function (data) { send(data); },
    onMessage: function (callback) {
      if (typeof callback === 'function') handlers.push(callback);
    }
  };
  window.__orcaDispatch = function (payload) {
    var data = payload ? payload.data : null;
    for (var i = 0; i < handlers.length; i++) {
      try { handlers[i](data); } catch (e) {}
    }
  };
})();
"""
