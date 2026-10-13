package app.orcaandroid.net

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Ad blocking for the app's WebViews ([Client]): requests to ad networks get an empty answer, and
 * the slots the sites keep for them are hidden. Thingiverse runs Raptive (AdThrive) with header bidding;
 * the hosts are the ones its pages requested (2026-10-12) plus common ad exchanges.
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

    /** True when [host] or one of its parent domains is an ad host. */
    fun blocks(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in HOSTS) return true
            h = h.substringAfter('.', "").ifEmpty { return false }
        }
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** The client every app WebView uses; overrides of the page callbacks call super. */
    open class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (blocks(request.url.host)) emptyResponse() else null
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = view.evaluateJavascript(HIDE_JS, null)
        override fun onPageFinished(view: WebView, url: String?) = view.evaluateJavascript(HIDE_JS, null)
    }

    /** Hides the ad slots left behind (Thingiverse's own wrappers keep their size and border). */
    const val HIDE_JS = """(function(){
if (document.getElementById('orca-adblock')) return;
var s = document.createElement('style'); s.id = 'orca-adblock';
s.textContent = '[class*="AdCard__adCard--"],[class*="AdBanner__adBanner--"],[class*="Ad__ad--"],.tv-ad-free-cta,' +
  '.adthrive-ad,.adthrive-comscore,[class*="kargo-ad"],ins.adsbygoogle,[id^="div-gpt-ad"],iframe[id^="google_ads_iframe"]' +
  '{display:none!important}';
(document.head || document.documentElement).appendChild(s);
})()"""
}
