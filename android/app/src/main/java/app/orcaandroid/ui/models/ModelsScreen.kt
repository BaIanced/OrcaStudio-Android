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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Refresh
import app.orcaandroid.ui.components.SplitAroundCutout
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orcaandroid.R
import app.orcaandroid.net.AdBlock
import app.orcaandroid.net.BambuAccount
import app.orcaandroid.net.FilamentProfiles
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
    FILAMENT_PROFILES("3D Filament Profiles", FilamentProfiles.HOME),
}

/**
 * The Models tab's browser. It outlives the tab (kept by the caller), so switching tabs keeps the
 * page, its history and the sign-in.
 */
class ModelBrowser(private val context: Context, private val vm: AppViewModel, dark: Boolean) {
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
    private val prefs = context.getSharedPreferences("orca", Context.MODE_PRIVATE)
    /**
     * MakerWorld's full site: with the slicer marker in the user agent MakerWorld leaves out its
     * own header and sidebar (account, Collections, History), as the desktop slicer shows those.
     * Without it the "Open in Bambu Studio" import is not offered to the app, so this is a switch.
     */
    var fullSite by mutableStateOf(prefs.getBoolean(PREF_FULL_SITE, false))
        private set
    var adBlockOn by mutableStateOf(AdBlock.isEnabled(context))
        private set
    /** Listing pages with page numbers (Thingiverse, Printables) load the next page as you scroll. */
    var infiniteScroll by mutableStateOf(prefs.getBoolean(PREF_INFINITE_SCROLL, true))
        private set
    /** The element picker: waiting for a tap, and the outlined element (selector to label). */
    var picking by mutableStateOf(false)
        private set
    var picked by mutableStateOf<Pair<String, String>?>(null)
        private set
    /** Bumped when the blocked elements change, so their list re-reads them. */
    var rulesVersion by mutableIntStateOf(0)
        private set
    private var slicerVersion = "02.08.01.99"
    // The WebView's own (Android) user agent; read before any site changes it.
    private val mobileUserAgent by lazy { web.settings.userAgentString }

    /**
     * MakerWorld serves its phone site (with the Bambu Handy hand-over) to an Android user agent.
     * The slicer integration is on the desktop site, so MakerWorld gets a desktop Chrome user
     * agent with Bambu Studio's BBL-Slicer suffix, like the desktop's embedded WebView.
     */
    private fun makerWorldUserAgent(): String {
        val chrome = Regex("Chrome/[0-9.]+").find(mobileUserAgent)?.value ?: "Chrome/118.0.0.0"
        val desktop = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        return if (fullSite) desktop else "$desktop BBL-Slicer/v$slicerVersion BBL-Language/en"
    }

    /** Switches MakerWorld between its full site and slicer mode, keeping the current page. */
    fun toggleFullSite() {
        fullSite = !fullSite
        prefs.edit().putBoolean(PREF_FULL_SITE, fullSite).apply()
        if (site != ModelSite.MAKERWORLD) return
        web.settings.userAgentString = makerWorldUserAgent()
        web.reload()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    val web: WebView = app.orcaandroid.ui.components.themedWebView(context.also {
        // `adb shell setprop log.tag.OrcaWeb DEBUG` (then restart) makes the pages inspectable (chrome://inspect).
        if (Log.isLoggable(WEB_DEBUG_TAG, Log.DEBUG)) WebView.setWebContentsDebuggingEnabled(true)
    }, dark).apply {
        // AndroidView's default WRAP_CONTENT makes WebView lay pages out with height 0 (CSS 100vh = 0).
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Model pages open in window.open() / target=_blank; without multiple windows they load here.
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // Pinch zoom, without the old on-screen +/- buttons; Ctrl+wheel zooms like a desktop browser.
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        setOnGenericMotionListener { v, e ->
            if (e.action != android.view.MotionEvent.ACTION_SCROLL || (e.metaState and android.view.KeyEvent.META_CTRL_ON) == 0) return@setOnGenericMotionListener false
            val wheel = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
            if (wheel != 0f) (v as WebView).zoomBy(if (wheel > 0) 1.1f else 1 / 1.1f)
            true
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(Bridge(), "orcaNative")
        addJavascriptInterface(Picker(), "orcaPicker")
        webViewClient = object : AdBlock.Client() {
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
                super.onPageStarted(view, url, favicon)
                view.evaluateJavascript(BRIDGE_JS, null)
                picking = false
                picked = null
                updateNav()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(BRIDGE_JS, null)
                view.evaluateJavascript(ZOOM_JS, null)
                if (adBlockOn) view.evaluateJavascript(AdBlock.PICKER_JS, null)
                if (site ==ModelSite.MAKERWORLD && !fullSite) view.evaluateJavascript(MW_TRIM_JS, null)
                if (infiniteScroll) view.evaluateJavascript(MORE_JS, null)
                updateNav()
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateNav()
                // MakerWorld's home cards only add ?modelid=<id> to the address, for a model pop-up
                // that never shows here. Open the model page instead, as the desktop does
                // (WebViewPanel::get_model_mall_detail_url: <host><lang>/models/<id>).
                val u = url?.let(Uri::parse) ?: return
                val id = u.getQueryParameter("modelid")?.takeIf { it.all(Char::isDigit) } ?: return
                if (site != ModelSite.MAKERWORLD || u.host?.endsWith("makerworld.com") != true) return
                val lang = u.pathSegments.firstOrNull()?.takeIf { it.length in 2..5 } ?: "en"
                view.loadUrl("https://${u.host}/$lang/models/$id")
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@ModelBrowser.progress = newProgress }
        }
        // Plain file downloads (Printables, Thingiverse, MakerWorld's "Download STL/3MF").
        setDownloadListener { url, _, disposition, mime, _ ->
            val name = ModelDownloads.dispositionName(disposition) ?: URLUtil.guessFileName(url, disposition, mime)
            when {
                // Files the page builds itself (profile exports): the page reads them back for us.
                url.startsWith("blob:") -> {
                    pendingBlobs[url] = name
                    evaluateJavascript(
                        "var k=window.__orcaBlobs&&window.__orcaBlobs[${JSONObject.quote(url)}];" +
                            "(k?Promise.resolve(k):fetch(${JSONObject.quote(url)}).then(function(r){return r.blob()})).then(function(b){" +
                            "var f=new FileReader();f.onload=function(){orcaNative.blob(${JSONObject.quote(url)},f.result," +
                            "window.__orcaNames&&window.__orcaNames[${JSONObject.quote(url)}]||'')};f.readAsDataURL(b)})", null)
                }
                url.startsWith("data:") -> saveData(url, name)
                else -> download(url, name)
            }
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
            slicerVersion = version
            web.settings.userAgentString = makerWorldUserAgent()
            val host = "https://makerworld.com/"
            web.loadUrl(if (ticket != null) "${host}api/sign-in/ticket?to=${Uri.encode(target.home)}&ticket=$ticket" else target.home)
        }
    }

    /** Opens [url] of a site other than MakerWorld (which needs its sign-in ticket and user agent). */
    fun openUrl(url: String) {
        started = true
        val host = Uri.parse(url).host.orEmpty()
        site = ModelSite.entries.firstOrNull { Uri.parse(it.home).host?.removePrefix("www.") == host.removePrefix("www.") } ?: site
        web.settings.userAgentString = mobileUserAgent
        web.loadUrl(url)
    }

    /** A MakerWorld page by path ("following", "contests"); slicer mode has no header to reach them. */
    fun openMakerWorld(path: String) = web.loadUrl("https://makerworld.com/en/$path")

    /**
     * A page of the signed-in MakerWorld user ("collections", "browsing-history", "" = profile).
     * The page looks the handle up itself (/api/v1/user-service/my/profile, "name"), with its cookies.
     */
    fun openMakerWorldUser(path: String) = web.evaluateJavascript(
        "fetch('/api/v1/user-service/my/profile').then(function(r){return r.json()}).then(function(j){" +
            "location.href='/en/@'+encodeURIComponent(j.name)+'${if (path.isEmpty()) "" else "/$path"}'})" +
            ".catch(function(){location.href='/en'})", null)

    fun searchMakerWorld(query: String) = openMakerWorld("search/models?keyword=" + Uri.encode(query.trim()))

    fun back() { if (web.canGoBack()) web.goBack() }
    fun forward() { if (web.canGoForward()) web.goForward() }
    fun reload() = web.reload()

    /** Ad blocking on / off for every app WebView; the page reloads without (or with) it. */
    fun toggleAdBlock() {
        adBlockOn = !adBlockOn
        AdBlock.setEnabled(context, adBlockOn)
        cancelPick()
        web.reload()
    }

    fun toggleInfiniteScroll() {
        infiniteScroll = !infiniteScroll
        prefs.edit().putBoolean(PREF_INFINITE_SCROLL, infiniteScroll).apply()
        web.reload()
    }

    /** The current page's site, the key of its blocked elements. */
    fun currentSite() = AdBlock.site(web.url)
    fun blockedHere() = AdBlock.blocked(context, currentSite())

    /** Picks the next tapped / clicked element (a right-click or long-press picks one directly). */
    fun startPicking() {
        picked = null
        picking = true
        web.evaluateJavascript(AdBlock.PICKER_JS + ";__orcaPick.start()", null)
    }
    fun pickWider() = web.evaluateJavascript("window.__orcaPick&&__orcaPick.up()", null)
    fun pickNarrower() = web.evaluateJavascript("window.__orcaPick&&__orcaPick.down()", null)
    fun cancelPick() {
        picking = false
        picked = null
        web.evaluateJavascript("window.__orcaPick&&__orcaPick.stop()", null)
    }

    /** Hides the outlined element (and the ones its selector matches) on this site from now on. */
    fun blockPicked() {
        val selector = picked?.first ?: return
        AdBlock.block(context, currentSite(), selector)
        cancelPick()
        rulesChanged()
    }

    /** Shows one blocked element again, or all of this site's when [selector] is null. */
    fun unblock(selector: String?) {
        AdBlock.unblock(context, currentSite(), selector)
        rulesChanged()
    }

    private fun rulesChanged() {
        rulesVersion++
        web.evaluateJavascript(AdBlock.applyJs(context, web.url), null)
    }

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

        /** A blob download's content as a data: URL and its link's download name; only for blobs the listener asked for. */
        @JavascriptInterface
        fun blob(url: String, data: String, pageName: String) {
            scope.launch { pendingBlobs.remove(url)?.let { name -> saveData(data, pageName.ifBlank { name }) } }
        }
    }

    /** The element picker's reports ([AdBlock.PICKER_JS]); blocking still needs the user's tap on Block. */
    inner class Picker {
        @JavascriptInterface
        fun picked(selector: String, label: String) {
            scope.launch {
                if (!adBlockOn) return@launch
                picking = false
                picked = selector.take(1000) to label.take(80)
            }
        }
    }

    private val pendingBlobs = HashMap<String, String>()

    private fun saveData(data: String, name: String) =
        vm.files.openDownloaded(context.getString(R.string.models_downloading, name)) { ModelDownloads.saveBase64(context, data, name) }

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
        // Fullscreen portrait: the buttons and the site chips share the strip beside the camera hole.
        SplitAroundCutout(Modifier.padding(horizontal = 4.dp), left = {
            val small = Modifier.size(40.dp)
            IconButton(onClick = browser::back, small, enabled = browser.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            IconButton(onClick = browser::forward, small, enabled = browser.canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.forward)) }
            IconButton(onClick = browser::reload, small) { Icon(Icons.Default.Refresh, stringResource(R.string.reload)) }
            IconButton(onClick = { browser.open(browser.site) }, small) { Icon(Icons.Default.Home, stringResource(R.string.home)) }
        }, right = {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                ModelSite.entries.forEach { s ->
                    FilterChip(selected = browser.site == s, onClick = { browser.open(s) }, label = { Text(s.label) }, modifier = Modifier.padding(horizontal = 3.dp))
                }
                if (browser.site == ModelSite.MAKERWORLD) {
                    FilterChip(selected = browser.fullSite, onClick = browser::toggleFullSite, label = { Text(stringResource(R.string.models_full_site)) },
                        modifier = Modifier.padding(start = 12.dp, end = 3.dp))
                }
            }
            AdBlockMenu(browser)
        })
        if (browser.site == ModelSite.MAKERWORLD && !browser.fullSite) MakerWorldLinks(browser)
        if (browser.picking || browser.picked != null) PickerBar(browser)
        if (browser.progress < 100) LinearProgressIndicator(progress = { browser.progress / 100f }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { browser.web.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The toolbar's three-dot menu: ad blocking on / off, the element picker and the site's blocked elements. */
@Composable
private fun AdBlockMenu(browser: ModelBrowser) {
    var menu by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.adblock_toggle)) }, leadingIcon = { Icon(Icons.Default.Shield, null) },
                trailingIcon = { Checkbox(checked = browser.adBlockOn, onCheckedChange = null) },
                onClick = { menu = false; browser.toggleAdBlock() })
            DropdownMenuItem(text = { Text(stringResource(R.string.models_infinite_scroll)) }, leadingIcon = { Icon(Icons.Default.KeyboardDoubleArrowDown, null) },
                trailingIcon = { Checkbox(checked = browser.infiniteScroll, onCheckedChange = null) },
                onClick = { menu = false; browser.toggleInfiniteScroll() })
            DropdownMenuItem(text = { Text(stringResource(R.string.adblock_pick)) }, leadingIcon = { Icon(Icons.Default.Block, null) },
                enabled = browser.adBlockOn, onClick = { menu = false; browser.startPicking() })
            val count = remember(menu, browser.rulesVersion) { browser.blockedHere().size }
            DropdownMenuItem(text = { Text(stringResource(R.string.adblock_blocked, count)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                enabled = count > 0, onClick = { menu = false; list = true })
        }
    }
    if (list) {
        val site = browser.currentSite()
        val blocked = remember(browser.rulesVersion, site) { browser.blockedHere() }
        AlertDialog(
            onDismissRequest = { list = false },
            title = { Text(stringResource(R.string.adblock_blocked_on, site)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (blocked.isEmpty()) Text(stringResource(R.string.adblock_none))
                    blocked.forEach { selector ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selector, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            IconButton(onClick = { browser.unblock(selector) }) { Icon(Icons.Default.Close, stringResource(R.string.adblock_unblock)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { list = false }) { Text(stringResource(R.string.close)) } },
            dismissButton = { TextButton(onClick = { browser.unblock(null) }, enabled = blocked.isNotEmpty()) { Text(stringResource(R.string.adblock_undo_all)) } },
        )
    }
}

/** The picker's bar: what is outlined, widen / narrow the pick, Block or Cancel. */
@Composable
private fun PickerBar(browser: ModelBrowser) {
    val picked = browser.picked
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        // The label gives way so the buttons always fit on a phone.
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(picked?.second ?: stringResource(R.string.adblock_pick_hint), Modifier.weight(1f).padding(end = 4.dp),
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val pad = PaddingValues(horizontal = 8.dp)
            if (picked != null) {
                TextButton(onClick = browser::pickNarrower, contentPadding = pad) { Text(stringResource(R.string.adblock_smaller)) }
                TextButton(onClick = browser::pickWider, contentPadding = pad) { Text(stringResource(R.string.adblock_larger)) }
                TextButton(onClick = browser::blockPicked, contentPadding = pad) { Text(stringResource(R.string.adblock_block)) }
            }
            TextButton(onClick = browser::cancelPick, contentPadding = pad) { Text(stringResource(R.string.cancel)) }
        }
    }
}

/** MakerWorld's header links, which its slicer mode leaves out (the desktop slicer has its own). */
@Composable
private fun MakerWorldLinks(browser: ModelBrowser) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
        TextButton(onClick = { browser.openMakerWorld("following") }) { Text(stringResource(R.string.mw_following)) }
        TextButton(onClick = { browser.openMakerWorld("makerlab") }) { Text("MakerLab") }
        TextButton(onClick = { browser.openMakerWorld("contests") }) { Text(stringResource(R.string.mw_contests)) }
        TextButton(onClick = { browser.openMakerWorldUser("browsing-history") }) { Text(stringResource(R.string.mw_history)) }
        TextButton(onClick = { browser.openMakerWorldUser("collections") }) { Text(stringResource(R.string.mw_collections)) }
        TextButton(onClick = { browser.openMakerWorldUser("") }) { Text(stringResource(R.string.mw_profile)) }
    }
    if (searching) AlertDialog(
        onDismissRequest = { searching = false },
        title = { Text(stringResource(R.string.search)) },
        text = {
            OutlinedTextField(query, { query = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) { browser.searchMakerWorld(query); searching = false } }))
        },
        confirmButton = { TextButton(onClick = { browser.searchMakerWorld(query); searching = false }, enabled = query.isNotBlank()) { Text(stringResource(R.string.search)) } },
        dismissButton = { TextButton(onClick = { searching = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val TAG = "Models"
private const val WEB_DEBUG_TAG = "OrcaWeb"
private const val PREF_FULL_SITE = "makerworld_full_site"
private const val PREF_INFINITE_SCROLL = "models_infinite_scroll"

/**
 * Slicer mode leaves a blank strip where MakerWorld's header would be: the category bar stays
 * fixed 60px down. Moves it to the top; re-applied when the page re-renders (client-side navigation).
 */
private const val MW_TRIM_JS = """(function(){
  if (window.__orcaTrim) return; window.__orcaTrim = 1;
  function trim(){ document.querySelectorAll('.global_new').forEach(function(g){
    var s = getComputedStyle(g); if (s.position === 'fixed' && s.top === '60px') g.style.setProperty('top', '0px', 'important'); }); }
  var pending = false;
  new MutationObserver(function(){ if (pending) return; pending = true; requestAnimationFrame(function(){ pending = false; trim(); }); })
    .observe(document.documentElement, { childList: true, subtree: true });
  trim();
})();"""

/**
 * MakerWorld's viewport meta has `maximum-scale=1.0, user-scalable=no`, which blocks pinch and
 * Ctrl+wheel zoom (WebView.zoomBy). Drops those limits, like Chrome's "Force enable zoom";
 * re-applied when the page rewrites its head.
 */
private const val ZOOM_JS = """(function(){
  if (window.__orcaZoom) return; window.__orcaZoom = 1;
  function unlock(){ document.querySelectorAll('meta[name=viewport]').forEach(function(m){
    var c = m.content || '', u = c.replace(/,?\s*(maximum-scale|minimum-scale|user-scalable)\s*=\s*[^,]*/gi, '');
    if (u !== c) m.content = u; }); }
  new MutationObserver(unlock).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
  unlock();
})();"""

private const val BRIDGE_JS = """(function(){
  if (window.__orcaBridge) return; window.__orcaBridge = 1;
  function send(m){ orcaNative.postMessage(typeof m === 'string' ? m : JSON.stringify(m)); }
  window.wx = window.wx || {}; window.wx.postMessage = send;
  window.webkit = window.webkit || {}; window.webkit.messageHandlers = window.webkit.messageHandlers || {};
  window.webkit.messageHandlers.wx = { postMessage: send };
  window.chrome = window.chrome || {}; window.chrome.webview = window.chrome.webview || {};
  window.chrome.webview.postMessage = send;
  // Pages revoke a download's blob: URL right after clicking it; keep the blob so the app can read it.
  window.__orcaBlobs = {};
  var create = URL.createObjectURL, revoke = URL.revokeObjectURL;
  URL.createObjectURL = function(o){ var u = create.apply(URL, arguments); if (o instanceof Blob) window.__orcaBlobs[u] = o; return u; };
  URL.revokeObjectURL = function(u){ setTimeout(function(){ delete window.__orcaBlobs[u]; revoke.call(URL, u); }, 60000); };
  // The download listener gets no <a download="name"> for blobs (Thingiverse's single files): note it on the click.
  window.__orcaNames = {};
  function named(a){ if (a && a.download && /^blob:/.test(a.href)) window.__orcaNames[a.href] = a.download; }
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){ named(this); return click.apply(this, arguments); };
  document.addEventListener('click', function(e){ named(e.target.closest && e.target.closest('a[download]')); }, true);
})();"""

/**
 * Infinite scroll for listings with page numbers (Thingiverse; Printables' lists): near the end,
 * the next page loads in a hidden frame and a copy of its cards goes below the current ones (the
 * page's pager is hidden meanwhile), or a "Load more" button is pressed. Back from a model loads
 * the same pages again and returns to the same spot. MakerWorld already loads more by itself.
 */
private const val MORE_JS = """(function(){
  // MakerWorld loads more on its own as you scroll.
  if (window.__orcaMore || /makerworld/.test(location.host)) return; window.__orcaMore = 1;
  var ITEM = /thing:\d|\/model\/\d|\/models\/\d/;
  var url = location.href, grid = null, box = null, pager = null, next = null, pages = [], busy = false, done = false;
  // Back from a model: load the same pages again and return to the same spot.
  var KEY = 'orcaMore:' + url, saved = null;
  try { var nav = performance.getEntriesByType('navigation')[0];
    if (nav && nav.type === 'back_forward') saved = JSON.parse(sessionStorage.getItem(KEY) || 'null'); } catch (e) {}
  function save(){ try { if (pages.length) sessionStorage.setItem(KEY, JSON.stringify({ pages: pages, y: (document.scrollingElement || document.documentElement).scrollTop })); } catch (e) {} }
  addEventListener('pagehide', save);
  document.addEventListener('click', function(e){ if (box && e.target.closest && e.target.closest('a[href]') && box.contains(e.target)) save(); }, true);
  function vis(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  // The results grid: the element with the most children that hold model links.
  function findGrid(doc){
    var kids = new Map();
    [].forEach.call(doc.querySelectorAll('a[href]'), function(a){
      if (!ITEM.test(a.getAttribute('href')) || (box && box.contains(a))) return;
      for (var e = a; e.parentElement && e.parentElement !== doc.body; e = e.parentElement) {
        var s = kids.get(e.parentElement); if (!s) kids.set(e.parentElement, s = new Set()); s.add(e);
      }
    });
    var best = null, n = 5;
    kids.forEach(function(s, p){ if (s.size > n || (s.size === n && best && best.contains(p))) { best = p; n = s.size; } });
    return best;
  }
  function findNext(doc){
    var a = doc.querySelector('a[rel~=next][href], a[aria-label="Next page" i][href], a[aria-label="Next" i][href]');
    if (a) return a;
    return [].find.call(doc.querySelectorAll('[class*=agination] a[href], nav a[href]'), function(x){
      return /^\s*(next|›|»|>)\s*$/i.test(x.textContent) || /next/i.test(x.getAttribute('aria-label') || '');
    }) || null;
  }
  function findMore(){
    var gb = grid.getBoundingClientRect().bottom;
    return [].find.call(document.querySelectorAll('button, a[role=button], [role=button]'), function(b){
      return !b.disabled && vis(b) && b.getBoundingClientRect().top >= gb - 4 && /^\s*(load|show|view|see) more\b/i.test(b.innerText || '');
    }) || null;
  }
  function reset(){ if (box) box.remove(); if (pager) pager.style.display = '';
    url = location.href; KEY = 'orcaMore:' + url; saved = null; grid = box = pager = next = null; pages = []; busy = done = false; }
  function label(n){ var d = document.createElement('div'); d.textContent = 'Page ' + n;
    d.style.cssText = 'margin:16px 0 8px;padding:6px 0;text-align:center;opacity:.6;font:600 13px sans-serif;border-top:1px solid rgba(128,128,128,.35)';
    return d; }
  // Loads a page in a hidden frame, waits for its grid and adds a copy of it below ours.
  function load(href, n){
    return new Promise(function(ok){
      var f = document.createElement('iframe'), t0 = Date.now(), tg = 0;
      f.setAttribute('aria-hidden', 'true'); f.tabIndex = -1;
      f.style.cssText = 'position:fixed;left:-10000px;top:0;width:' + innerWidth + 'px;height:' + (innerHeight * 4) + 'px;border:0;visibility:hidden';
      f.src = href; document.body.appendChild(f);
      (function poll(){
        var g = null, d = null;
        try { d = f.contentDocument; g = d && findGrid(d); } catch (e) {}
        var ready = g && g.children.length >= Math.min(grid.children.length, 8);
        // The pager can render after the cards: give it a few seconds more.
        if (ready && !findNext(d)) { tg = tg || Date.now(); if (Date.now() - tg < 4000) return setTimeout(poll, 400); }
        if (!ready && Date.now() - t0 < 20000) return setTimeout(poll, 400);
        if (ready) {
          var c = document.importNode(g, true);
          [].forEach.call(c.querySelectorAll('img'), function(i){
            var s = i.getAttribute('data-src'); if (s && !i.getAttribute('src')) i.setAttribute('src', s);
            var ss = i.getAttribute('data-srcset'); if (ss && !i.getAttribute('srcset')) i.setAttribute('srcset', ss);
          });
          [].forEach.call(c.querySelectorAll('a[href]'), function(a){ a.href = a.href; });
          // Keep the view where it is: scroll anchoring would follow the footer below the new cards.
          var se = document.scrollingElement || document.documentElement, y = se.scrollTop;
          box.appendChild(label(n)); box.appendChild(c);
          se.scrollTop = y;
          var nx = findNext(d); next = nx ? nx.href : null;
        }
        f.remove(); ok(!!ready);
      })();
    });
  }
  function tick(){
    if (location.href !== url) reset();
    if (busy || done) return;
    var se = document.scrollingElement || document.documentElement;
    if (!saved && se.scrollTop + innerHeight < se.scrollHeight - innerHeight * 2) return;
    if (!grid || !grid.isConnected) { grid = findGrid(document); if (!grid) return; }
    if (!pages.length) {
      var m = !saved && findMore();
      if (m) { busy = true; m.click(); setTimeout(function(){ busy = false; }, 1500); return; }
      var a = findNext(document); if (!a) { done = true; return; }
      next = a.href;
      pager = a.closest('[class*=agination], nav') || a.parentElement;
    }
    if (saved && pages.length < saved.pages.length) next = saved.pages[pages.length];
    if (!next || pages.length >= 50) { done = true; if (pager) pager.style.display = ''; return; }
    if (!box) { box = document.createElement('div'); box.className = 'orca-more'; grid.parentNode.insertBefore(box, grid.nextSibling); }
    busy = true; var href = next; pages.push(href);
    if (pager) pager.style.display = 'none';
    load(href, pages.length + 1).then(function(ok){
      busy = false;
      if (!ok) { done = true; saved = null; if (pager) pager.style.display = ''; }
      else if (saved && pages.length >= saved.pages.length) { (document.scrollingElement || document.documentElement).scrollTop = saved.y; saved = null; }
      else tick();
    });
  }
  var queued = false;
  addEventListener('scroll', function(){ if (queued) return; queued = true; setTimeout(function(){ queued = false; tick(); }, 250); }, true);
  setInterval(tick, 1500);
})();"""
