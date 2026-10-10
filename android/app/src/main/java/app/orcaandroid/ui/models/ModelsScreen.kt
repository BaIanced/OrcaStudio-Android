package app.orcaandroid.ui.models

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orcaandroid.R
import app.orcaandroid.net.BambuAccount
import app.orcaandroid.net.ModelDownloads
import app.orcaandroid.ui.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A model site in the Models tab. [hosts] may post page messages (MakerWorld's slicer commands). */
enum class ModelSite(val label: String, val home: String, val hosts: List<String> = emptyList()) {
    MAKERWORLD("MakerWorld", "https://makerworld.com/en", listOf("makerworld.com", "makerworld.com.cn", "bambulab.com", "bambulab.cn")),
    PRINTABLES("Printables", "https://www.printables.com/"),
    THINGIVERSE("Thingiverse", "https://www.thingiverse.com/"),
}

/**
 * The Models tab's browser. It outlives the tab (kept by the caller), so switching tabs keeps the
 * page, its history and the sign-in.
 */
class ModelBrowser(private val context: Context, private val vm: AppViewModel) {
    private val scope: CoroutineScope = MainScope()
    var site by mutableStateOf(ModelSite.MAKERWORLD)
        private set
    var progress by mutableIntStateOf(100)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    private var started = false
    // The WebView's own (Android) user agent; read before any site changes it.
    private val mobileUserAgent by lazy { web.settings.userAgentString }

    /**
     * MakerWorld serves its phone site (with the Bambu Handy hand-over) to an Android user agent.
     * The slicer integration is on the desktop site, so MakerWorld gets a desktop Chrome user
     * agent with Bambu Studio's BBL-Slicer suffix, like the desktop's embedded WebView.
     */
    private fun makerWorldUserAgent(version: String): String {
        val chrome = Regex("Chrome/[0-9.]+").find(mobileUserAgent)?.value ?: "Chrome/118.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36 BBL-Slicer/v$version BBL-Language/en"
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    val web: WebView = WebView(context.also {
        // `adb shell setprop log.tag.OrcaWeb DEBUG` (then restart) makes the pages inspectable (chrome://inspect).
        if (Log.isLoggable(WEB_DEBUG_TAG, Log.DEBUG)) WebView.setWebContentsDebuggingEnabled(true)
    }).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Model pages open in window.open() / target=_blank; without multiple windows they load here.
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(Bridge(), "orcaNative")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (ModelDownloads.isSlicerLink(url)) {
                    ModelDownloads.parseSlicerLink(url)?.let { (file, name) -> download(file, name) }
                        ?: vm.toast(context.getString(R.string.models_bad_link))
                    return true
                }
                // Other apps' links (mailto:, intent:, market:) are not followed inside the tab.
                return request.url.scheme !in setOf("http", "https")
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(BRIDGE_JS, null)
                updateNav()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(BRIDGE_JS, null)
                updateNav()
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@ModelBrowser.progress = newProgress }
        }
        // Plain file downloads (Printables, Thingiverse, MakerWorld's "Download STL/3MF").
        setDownloadListener { url, _, disposition, mime, _ ->
            download(url, URLUtil.guessFileName(url, disposition, mime))
        }
    }

    private fun updateNav() {
        canGoBack = web.canGoBack()
        canGoForward = web.canGoForward()
    }

    fun start() {
        if (started) return
        started = true
        open(ModelSite.MAKERWORLD)
    }

    /** Opens a site's home page; MakerWorld through the account's sign-in ticket when signed in. */
    fun open(target: ModelSite) {
        site = target
        if (target != ModelSite.MAKERWORLD) {
            web.settings.userAgentString = mobileUserAgent
            web.loadUrl(target.home)
            return
        }
        scope.launch {
            val (version, ticket) = withContext(Dispatchers.IO) {
                BambuAccount.clientVersion(context) to runCatching { BambuAccount.webTicket(context) }.getOrNull()
            }
            // MakerWorld offers its "open in slicer" hand-over only to Bambu's slicers.
            web.settings.userAgentString = makerWorldUserAgent(version)
            val host = "https://makerworld.com/"
            web.loadUrl(if (ticket != null) "${host}api/sign-in/ticket?to=${Uri.encode(target.home)}&ticket=$ticket" else target.home)
        }
    }

    fun back() { if (web.canGoBack()) web.goBack() }
    fun forward() { if (web.canGoForward()) web.goForward() }
    fun reload() = web.reload()

    private fun download(url: String, name: String) {
        val ua = web.settings.userAgentString
        vm.files.openDownloaded(context.getString(R.string.models_downloading, name.ifBlank { url.substringAfterLast('/') })) {
            ModelDownloads.download(context, url, name, ua)
        }
    }

    /** Page messages, as the desktop's MakerWorld / MakerLab WebViews receive them (window.wx etc.). */
    // Public: WebView's reflection only calls public methods of public classes.
    inner class Bridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            val j = runCatching { JSONObject(message) }.getOrNull() ?: return
            scope.launch { handle(j) }
        }
    }

    private fun handle(j: JSONObject) {
        val host = web.url?.let { Uri.parse(it).host }?.lowercase().orEmpty()
        if (site.hosts.none { host == it || host.endsWith(".$it") }) {
            Log.w(TAG, "ignored page message from $host")
            return
        }
        when (val cmd = j.optString("command")) {
            "makerworld_model_open" -> j.optJSONObject("model")?.optString("url")?.takeIf { it.isNotEmpty() }?.let { link ->
                val decoded = Uri.decode(link)
                val parsed = if (ModelDownloads.isSlicerLink(decoded)) ModelDownloads.parseSlicerLink(decoded)
                    else ModelDownloads.parseSlicerLink("bambustudio://open?file=$decoded")
                parsed?.let { (file, name) -> download(file, name) }
            }
            "homepage_makerlab_open_3mf_binary" -> j.optString("3mf").takeIf { it.isNotEmpty() }?.let { data ->
                vm.files.openDownloaded(context.getString(R.string.loading_model)) {
                    ModelDownloads.saveBase64(context, data, j.optString("3mf_name").ifEmpty { "makerlab.3mf" }.let { if (it.endsWith(".3mf", true)) it else "$it.3mf" })
                }
            }
            "homepage_makerlab_stl_download" -> j.optString("file_data").takeIf { it.isNotEmpty() }?.let { data ->
                val name = j.optString("file_name").ifEmpty { "makerlab.stl" }
                vm.files.openDownloaded(context.getString(R.string.loading_model)) {
                    ModelDownloads.saveBase64(context, data, if (name.contains('.')) name else "$name.stl")
                }
                val reply = JSONObject().put("command", cmd).put("sequence_id", j.opt("sequence_id")).put("file_name", name).put("result", "success")
                web.evaluateJavascript("window.postMessage($reply)", null)
            }
            "common_openurl" -> j.optString("url").takeIf { it.startsWith("https://") }?.let {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            "homepage_login_or_register" -> vm.toast(context.getString(R.string.models_sign_in_hint))
            else -> Log.i(TAG, "ignored page command ${cmd.take(64)}")
        }
    }

    fun destroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }
}

@Composable
fun ModelsScreen(browser: ModelBrowser) {
    LaunchedEffect(browser) { browser.start() }
    // Android's back button goes back in the page history first.
    androidx.activity.compose.BackHandler(enabled = browser.canGoBack) { browser.back() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = browser::back, enabled = browser.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            IconButton(onClick = browser::forward, enabled = browser.canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.forward)) }
            IconButton(onClick = browser::reload) { Icon(Icons.Default.Refresh, stringResource(R.string.reload)) }
            IconButton(onClick = { browser.open(browser.site) }) { Icon(Icons.Default.Home, stringResource(R.string.home)) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                ModelSite.entries.forEach { s ->
                    FilterChip(selected = browser.site == s, onClick = { browser.open(s) }, label = { Text(s.label) }, modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
        }
        if (browser.progress < 100) LinearProgressIndicator(progress = { browser.progress / 100f }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { browser.web.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val TAG = "Models"
private const val WEB_DEBUG_TAG = "OrcaWeb"

private const val BRIDGE_JS = """(function(){
  if (window.__orcaBridge) return; window.__orcaBridge = 1;
  function send(m){ orcaNative.postMessage(typeof m === 'string' ? m : JSON.stringify(m)); }
  window.wx = window.wx || {}; window.wx.postMessage = send;
  window.webkit = window.webkit || {}; window.webkit.messageHandlers = window.webkit.messageHandlers || {};
  window.webkit.messageHandlers.wx = { postMessage: send };
  window.chrome = window.chrome || {}; window.chrome.webview = window.chrome.webview || {};
  window.chrome.webview.postMessage = send;
})();"""
