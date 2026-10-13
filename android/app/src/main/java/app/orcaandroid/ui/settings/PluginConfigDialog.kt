package app.orcaandroid.ui.settings

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
 * A plugin capability's settings, like the config pane of the desktop Plugins dialog
 * (src-orca resources/web/dialog/PluginsConfigDialog, js/plugin-config-ui.js): the capability's own
 * settings page (get_config_ui) with window.orca getConfig/saveConfig/restoreDefaults/onConfig, or a
 * JSON editor when it has none. Edits the global config, which applies while no process preset
 * overrides it.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun PluginConfigDialog(pluginKey: String, capability: Plugins.Capability, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<Plugins.Config?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(pluginKey, capability) {
        runCatching { Plugins.openConfig(pluginKey, capability) }.fold({
            loaded = it
            text = pretty(it.config)
            if (it.error.isNotEmpty()) error = it.error
        }, { error = it.message ?: it.toString() })
    }

    // config == null restores the plugin's defaults. The page gets back what was stored.
    fun store(config: String?) = scope.launch {
        runCatching { Plugins.saveConfig(pluginKey, capability, config) }.fold({ stored ->
            error = null
            text = pretty(stored)
            web?.evaluateJavascript("window.__orcaConfig && window.__orcaConfig($stored);", null)
        }, { error = it.message ?: it.toString() })
    }

    val bridge = remember {
        object {
            // Called on a WebView thread.
            @JavascriptInterface
            fun save(config: String) {
                store(config)
            }

            @JavascriptInterface
            fun restore() {
                store(null)
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(capability.name, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
                error?.let { Text("✗ $it", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val config = loaded
                    when {
                        config == null -> if (error == null) CircularProgressIndicator()
                        config.customHtml.isNotEmpty() -> {
                            val dark = appIsDark()
                            val document = configDocument(config.customHtml, config.config, dark, themeCss(MaterialTheme.colorScheme))
                            AndroidView(
                                factory = { ctx ->
                                    themedWebView(ctx, dark).apply {
                                        layoutParams = android.view.ViewGroup.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                        settings.javaScriptEnabled = true
                                        addJavascriptInterface(bridge, "orcaConfigHost")
                                        webViewClient = app.orcaandroid.net.AdBlock.Client()
                                        loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                                        web = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        else -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val valid = runCatching { org.json.JSONTokener(text).nextValue() }.isSuccess
                            OutlinedTextField(
                                text, { text = it }, Modifier.fillMaxWidth().weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                isError = !valid,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { store(text) }, enabled = valid) { Text(stringResource(R.string.plugin_config_save)) }
                                TextButton(onClick = { store(null) }) { Text(stringResource(R.string.plugin_config_restore)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pretty(json: String) = (runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull() as? JSONObject)?.toString(2) ?: json

/** The host theme variables of the desktop's WebViewHostDialog::host_theme_vars_css, from the app theme. */
private fun themeCss(c: androidx.compose.material3.ColorScheme): String {
    fun css(color: Color) = String.format("#%06X", color.toArgb() and 0xFFFFFF)
    return ":root{--orca-bg:${css(c.surface)};--orca-fg:${css(c.onSurface)};--orca-muted:${css(c.onSurfaceVariant)};" +
        "--orca-border:${css(c.outline)};--orca-accent:${css(c.primary)};--orca-accent-fg:${css(c.onPrimary)};" +
        "--orca-font:system-ui,sans-serif;}"
}

/**
 * plugin-config-ui.js BuildCustomConfigDocument, without the sandboxed frame: the page reaches the
 * app through the `orcaConfigHost` interface, and the app hands stored config back through
 * window.__orcaConfig.
 */
private fun configDocument(html: String, config: String, dark: Boolean, themeCss: String): String {
    fun inline(json: String) = json.replace("<", "\\u003c")
    val theme = JSONObject().put("theme", if (dark) "dark" else "light").put("css", themeCss).toString()
    return """<style id="orca-host-theme-vars">${themeCss}</style>
<script>
(function () {
  var handlers = [];
  var themeHandlers = [];
  var current = ${inline(config)};
  var context = {scope: "global", readOnly: false, hasPresetOverride: false};
  var theme = ${inline(theme)};
  document.documentElement.setAttribute("data-orca-theme", theme.theme);
  window.orca = {
    getConfig: function () { return current; },
    saveConfig: function (cfg) { orcaConfigHost.save(JSON.stringify(cfg === undefined ? {} : cfg)); },
    restoreDefaults: function () { orcaConfigHost.restore(); },
    getContext: function () { return context; },
    onConfig: function (cb) {
      if (typeof cb !== "function") return;
      handlers.push(cb);
      try { cb(current); } catch (e) {}
    },
    onTheme: function (cb) {
      if (typeof cb !== "function") return;
      themeHandlers.push(cb);
      try { cb(theme.theme); } catch (e) {}
    }
  };
  window.__orcaConfig = function (cfg) {
    current = cfg || {};
    handlers.forEach(function (handler) {
      try { handler(current); } catch (e) {}
    });
  };
})();
</script>""" + html
}
