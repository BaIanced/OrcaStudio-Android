package app.orcaandroid.net

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ad blocking for the app's WebViews ([Client]): requests to ad networks get an empty answer, and
 * the slots the sites keep for them are hidden. Thingiverse runs Raptive (AdThrive) with header bidding;
 * the hosts are the ones its pages requested (2026-10-12) plus common ad exchanges.
 * The user can switch it off ([setEnabled]) and hide more page elements per site ([block], the element
 * picker in [PICKER_JS]); both are kept in the app's "orca" preferences.
 */
object AdBlock {
    private val HOSTS = setOf(
        // Raptive / AdThrive and its consent and identity helpers
        "adthrive.com", "raptive.com", "raptivecdn.com", "optable.co", "privacymanager.io",
        "confiant-integrations.net", "id5-sync.com", "liadm.com", "rlcdn.com", "rkdms.com", "ay.delivery",
        // Google ads
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        // Exchanges and bidders
        "amazon-adsystem.com", "rubiconproject.com", "pubmatic.com", "openx.net", "adnxs.com",
        "casalemedia.com", "indexww.com", "criteo.com", "criteo.net", "3lift.com", "sharethrough.com",
        "smartadserver.com", "gumgum.com", "kargo.com", "onetag-sys.com", "undertone.com", "teads.tv",
        "adsrvr.org", "postrelease.com", "pubgw.yahoo.com", "analytics.yahoo.com", "ads.yahoo.com",
        "33across.com", "yieldmo.com", "lijit.com", "sovrn.com", "contextweb.com", "media.net",
        "adform.net", "bidswitch.net", "seedtag.com", "taboola.com", "outbrain.com", "connatix.com",
        "primis.tech", "aniview.com", "freestar.com", "pub.network", "nitropay.com", "playwire.com",
        "ezoic.net", "mediavine.com",
        // Ad measurement
        "scorecardresearch.com", "moatads.com", "doubleverify.com", "adsafeprotected.com",
        "adlightning.com", "quantserve.com", "quantcount.com",
    )
    private const val PREF_ENABLED = "adblock_enabled"
    private const val PREF_RULES = "adblock_rules"

    // Read on the WebView's network thread too.
    @Volatile private var enabled: Boolean? = null
    /** Site (host without "www.") -> CSS selectors of the elements the user blocked there. */
    private var rules: MutableMap<String, MutableList<String>>? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("orca", Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        enabled ?: prefs(context).getBoolean(PREF_ENABLED, true).also { enabled = it }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        prefs(context).edit().putBoolean(PREF_ENABLED, on).apply()
    }

    /** The rules' key for a page address: its host without "www.". */
    fun site(url: String?): String = url?.let { Uri.parse(it).host }?.lowercase()?.removePrefix("www.").orEmpty()

    @Synchronized
    private fun rules(context: Context): MutableMap<String, MutableList<String>> = rules ?: run {
        val map = LinkedHashMap<String, MutableList<String>>()
        runCatching {
            val j = JSONObject(prefs(context).getString(PREF_RULES, null) ?: "{}")
            j.keys().forEach { k -> val a = j.getJSONArray(k); map[k] = MutableList(a.length()) { a.getString(it) } }
        }
        map.also { rules = it }
    }

    @Synchronized
    private fun saveRules(context: Context) {
        val j = JSONObject()
        rules(context).forEach { (k, v) -> if (v.isNotEmpty()) j.put(k, JSONArray(v)) }
        prefs(context).edit().putString(PREF_RULES, j.toString()).apply()
    }

    /** The selectors blocked on [site], oldest first. */
    @Synchronized
    fun blocked(context: Context, site: String): List<String> = rules(context)[site].orEmpty().toList()

    @Synchronized
    fun block(context: Context, site: String, selector: String) {
        // The page writes the selector: no braces, so it can't add rules of its own to the style.
        if (site.isEmpty() || selector.isBlank() || '{' in selector || '}' in selector) return
        val list = rules(context).getOrPut(site) { mutableListOf() }
        if (selector !in list) list += selector
        saveRules(context)
    }

    /** Removes one blocked selector, or all of [site]'s when [selector] is null. */
    @Synchronized
    fun unblock(context: Context, site: String, selector: String? = null) {
        val all = rules(context)
        if (selector == null) all.remove(site) else all[site]?.remove(selector)
        saveRules(context)
    }

    /**
     * Hides the built-in ad slots and the user's blocked elements of [url]'s site (re-run after a rule change).
     * Also drops Sentry's `sentry-trace` / `baggage` XHR headers: with the ad scripts blocked, Thingiverse's Sentry
     * puts them on `/download:<id>`, which redirects to cdn.thingiverse.com, whose CORS preflight rejects them
     * ("Error downloading file").
     */
    fun applyJs(context: Context, url: String?): String {
        val on = isEnabled(context)
        val user = if (on) blocked(context, site(url)) else emptyList()
        return "(function(){" +
            "var x=XMLHttpRequest.prototype;if(!x.__orcaNoTrace){x.__orcaNoTrace=1;var h=x.setRequestHeader;" +
            "x.setRequestHeader=function(k){if(/^(sentry-trace|baggage)$/i.test(k))return;return h.apply(this,arguments)}}" +
            "function put(id,css){var s=document.getElementById(id);" +
            "if(!css){if(s)s.remove();return}" +
            "var r=document.head||document.documentElement;if(!r)return;" +
            "if(!s){s=document.createElement('style');s.id=id;r.appendChild(s)}" +
            "if(s.textContent!==css)s.textContent=css}" +
            "put('orca-adblock',${JSONObject.quote(if (on) HIDE_CSS else "")});" +
            "put('orca-userblock',${JSONObject.quote(if (user.isEmpty()) "" else user.joinToString(",") + "{display:none!important}")});" +
            "})()"
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** The client every app WebView uses; overrides of the page callbacks call super. */
    open class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (enabled != false && blocks(request.url.host) && isEnabled(view.context)) emptyResponse() else null
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = view.evaluateJavascript(applyJs(view.context, url), null)
        override fun onPageFinished(view: WebView, url: String?) = view.evaluateJavascript(applyJs(view.context, url), null)
    }

    /** True when [host] or one of its parent domains is an ad host. */
    fun blocks(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in HOSTS) return true
            h = h.substringAfter('.', "").ifEmpty { return false }
        }
    }

    /** The ad slots left behind (Thingiverse's own wrappers keep their size and border). */
    private const val HIDE_CSS = "[class*=\"AdCard__adCard--\"],[class*=\"AdBanner__adBanner--\"],[class*=\"Ad__ad--\"],.tv-ad-free-cta," +
        ".adthrive-ad,.adthrive-comscore,[class*=\"kargo-ad\"],ins.adsbygoogle,[id^=\"div-gpt-ad\"],iframe[id^=\"google_ads_iframe\"]" +
        "{display:none!important}"

    /**
     * The element picker, for a page with the `orcaPicker` interface. A right-click / long-press
     * (contextmenu, when no text is selected) or, after `__orcaPick.start()`, a tap picks the element under
     * it: it is outlined and `orcaPicker.picked(selector, label)` reports it. `__orcaPick.up()` / `down()`
     * widen or narrow the pick, `stop()` ends picking. Hashed CSS-module class parts ("Foo__bar--x1y2") are
     * matched by prefix so the rule survives the site's next deploy.
     */
    const val PICKER_JS = """(function(){
if (window.__orcaPick) return;
var box = null, cur = null, trail = [], picking = false;
function outline(el){
  if (!box){ box = document.createElement('div'); box.id = 'orca-pick-box';
    box.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #e53935;background:rgba(229,57,53,.18);box-sizing:border-box'; }
  if (!box.parentNode) document.documentElement.appendChild(box);
  var r = el.getBoundingClientRect();
  box.style.left = r.left + 'px'; box.style.top = r.top + 'px'; box.style.width = r.width + 'px'; box.style.height = r.height + 'px';
}
function part(el){
  var s = el.tagName.toLowerCase();
  if (el.id && !/\d{3,}/.test(el.id)) return s + '#' + CSS.escape(el.id);
  var cls = Array.prototype.slice.call(el.classList).filter(function(c){ return !/\d{4,}/.test(c) || /--/.test(c); }).slice(0, 3);
  cls.forEach(function(c){
    var m = /^([A-Za-z][\w-]*?__[\w-]+?--)[\w-]+$/.exec(c);
    s += m ? '[class*="' + m[1] + '"]' : '.' + CSS.escape(c);
  });
  var p = el.parentElement;
  if (p){ var same = Array.prototype.filter.call(p.children, function(c){ return c.tagName === el.tagName; });
    if (same.length > 1 && !cls.length) s += ':nth-of-type(' + (same.indexOf(el) + 1) + ')'; }
  return s;
}
function selector(el){
  var parts = [], e = el;
  while (e && e.nodeType === 1 && e !== document.body && e !== document.documentElement){
    var p = part(e); parts.unshift(p);
    if (p.indexOf('#') > 0) break;
    var sel = parts.join(' > ');
    try { if (document.querySelectorAll(sel).length === 1) return sel; } catch (x) {}
    e = e.parentElement;
  }
  return parts.join(' > ') || el.tagName.toLowerCase();
}
function report(){
  if (!cur) return; outline(cur);
  var label = cur.tagName.toLowerCase() + (cur.id ? '#' + cur.id : '') + ' ' + Math.round(cur.getBoundingClientRect().width) + '×' + Math.round(cur.getBoundingClientRect().height);
  orcaPicker.picked(selector(cur), label);
}
function choose(el){ if (!el || el === box) return; cur = el; trail = []; report(); }
function pick(e){ if (!picking) return; e.preventDefault(); e.stopImmediatePropagation(); picking = false; choose(e.target); }
function stop(){ picking = false; cur = null; trail = []; if (box && box.parentNode) box.parentNode.removeChild(box); }
document.addEventListener('click', pick, true);
document.addEventListener('mouseover', function(e){ if (picking && e.target !== box) outline(e.target); }, true);
document.addEventListener('contextmenu', function(e){
  if (String(window.getSelection() || '').length) return;
  e.preventDefault(); choose(e.target);
}, true);
window.addEventListener('scroll', function(){ if (cur) outline(cur); }, true);
window.__orcaPick = {
  start: function(){ stop(); picking = true; },
  up: function(){ if (cur && cur.parentElement && cur.parentElement !== document.body && cur.parentElement !== document.documentElement){ trail.push(cur); cur = cur.parentElement; report(); } },
  down: function(){ if (trail.length){ cur = trail.pop(); report(); } },
  stop: stop
};
})()"""
}
