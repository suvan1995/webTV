package com.example.webtv.adblock

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brave-style "Shields" for the WebView: blocks ad/tracker requests, hides ad elements and runs
 * the anti-adblock / YouTube scriptlets, using the same default filter lists Brave ships
 * (EasyList, EasyPrivacy, uBlock Origin lists, Peter Lowe's list, Brave's own lists).
 *
 * Lists are bundled in assets/filters and refreshed from the internet every few days.
 */
object AdBlocker {
    private const val TAG = "WebTvShields"
    private const val PREFS = "shields"
    private const val UPDATE_EVERY_MS = 3L * 24 * 60 * 60 * 1000

    class FilterList(val file: String, val title: String, val urls: List<String>, val optional: Boolean = false)

    private const val UBO = "https://ublockorigin.github.io/uAssets/filters/"
    private const val UBO_RAW = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/"
    private const val BRAVE = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/"

    val LISTS = listOf(
        FilterList("easylist.txt", "EasyList", listOf("https://easylist.to/easylist/easylist.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/thirdparties/easylist/easylist.txt")),
        FilterList("easyprivacy.txt", "EasyPrivacy", listOf("https://easylist.to/easylist/easyprivacy.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/thirdparties/easylist/easyprivacy.txt")),
        FilterList("ublock-filters.txt", "uBlock filters", listOf(UBO + "filters.txt", UBO_RAW + "filters.txt")),
        FilterList("ublock-badware.txt", "uBlock badware risks", listOf(UBO + "badware.txt", UBO_RAW + "badware.txt")),
        FilterList("ublock-privacy.txt", "uBlock privacy", listOf(UBO + "privacy.txt", UBO_RAW + "privacy.txt")),
        FilterList("ublock-unbreak.txt", "uBlock unbreak", listOf(UBO + "unbreak.txt", UBO_RAW + "unbreak.txt")),
        FilterList("ublock-quick-fixes.txt", "uBlock quick fixes", listOf(UBO + "quick-fixes.txt", UBO_RAW + "quick-fixes.txt")),
        FilterList("peter-lowe.txt", "Peter Lowe's list", listOf(
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=1&mimetype=plaintext",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/thirdparties/pgl.yoyo.org/as/serverlist")),
        FilterList("brave-specific.txt", "Brave specific", listOf(BRAVE + "brave-specific.txt")),
        FilterList("brave-firstparty.txt", "Brave first-party", listOf(BRAVE + "brave-firstparty.txt")),
        FilterList("ublock-cookies.txt", "Cookie notices", listOf(UBO + "annoyances-cookies.txt", UBO_RAW + "annoyances-cookies.txt"), optional = true)
    )
    private val PSL = FilterList("public_suffix_list.dat", "Public suffixes", listOf(
        "https://publicsuffix.org/list/public_suffix_list.dat",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/thirdparties/publicsuffix.org/list/effective_tld_names.dat"))

    @Volatile var engine: FilterEngine? = null
        private set
    private var ready = CountDownLatch(1)
    @Volatile private var building = false
    private lateinit var app: Context

    /** Requests blocked since the current page started. */
    val pageBlocked = AtomicInteger(0)
    private val sessionAllow = Collections.synchronizedSet(HashSet<String>())
    private val pageFlagCache = Collections.synchronizedMap(object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Int>?) = size > 64
    })

    // ------------------------------------------------------------------ settings

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { p(c).edit().putBoolean("on", on).apply(); pageFlagCache.clear() }
    fun cookieNotices(c: Context) = p(c).getBoolean("cookies", true)
    fun setCookieNotices(c: Context, on: Boolean) { p(c).edit().putBoolean("cookies", on).apply(); rebuild(c) }
    fun totalBlocked(c: Context) = p(c).getLong("total", 0)
    fun lastUpdate(c: Context) = p(c).getLong("updated", 0)

    /** Sites (registrable domains) where the user turned shields down. */
    fun allowedSites(c: Context): Set<String> = p(c).getStringSet("allow", emptySet()) ?: emptySet()
    fun siteKey(url: String?): String {
        val host = try { Uri.parse(url ?: "").host?.lowercase() } catch (e: Exception) { null } ?: return ""
        return engine?.psl?.registrable(host) ?: host.removePrefix("www.")
    }
    fun isSiteAllowed(c: Context, url: String?): Boolean {
        val k = siteKey(url)
        return k.isNotEmpty() && allowedSites(c).contains(k)
    }
    fun setSiteAllowed(c: Context, url: String?, allowed: Boolean) {
        val k = siteKey(url)
        if (k.isEmpty()) return
        val s = HashSet(allowedSites(c))
        if (allowed) s.add(k) else s.remove(k)
        p(c).edit().putStringSet("allow", s).apply()
    }

    // ------------------------------------------------------------------ loading

    /** Starts loading the filter engine in the background (call from Application.onCreate). */
    fun init(context: Context) {
        app = context.applicationContext
        if (engine != null || building) return
        rebuild(app, thenMaybeUpdate = true)
    }

    fun awaitReady(ms: Long): Boolean = engine != null || ready.await(ms, TimeUnit.MILLISECONDS)

    private fun open(c: Context, name: String): BufferedReader {
        val updated = File(File(c.filesDir, "filters"), name)
        val input = if (updated.length() > 1000) updated.inputStream() else c.assets.open("filters/$name")
        return BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
    }

    private fun rebuild(c: Context, thenMaybeUpdate: Boolean = false) {
        building = true
        Thread({
            try {
                val t0 = SystemClock.elapsedRealtime()
                val psl = open(c, PSL.file).use { PublicSuffix(it) }
                val lists = LISTS.filter { !it.optional || cookieNotices(c) }
                val e = FilterEngine.build(psl) { lists.map { open(c, it.file) } }
                engine = e
                pageFlagCache.clear()
                Log.i(TAG, "Shields ready: ${e.networkCount} network + ${e.cosmeticCount} cosmetic rules in " +
                    "${SystemClock.elapsedRealtime() - t0} ms")
            } catch (t: Throwable) {
                Log.e(TAG, "Filter load failed", t)
            } finally {
                building = false
                ready.countDown()
            }
            try {
                if (thenMaybeUpdate && System.currentTimeMillis() - lastUpdate(c) > UPDATE_EVERY_MS) update(c, null)
            } catch (t: Throwable) { Log.w(TAG, "update check", t) }
        }, "shields-load").apply { priority = Thread.MAX_PRIORITY - 1 }.start()
    }

    /** Downloads fresh copies of every list, then rebuilds. [done] gets (updatedCount, failedCount). */
    fun update(c: Context, done: ((Int, Int) -> Unit)?) {
        Thread({ try {
            val dir = File(c.filesDir, "filters").apply { mkdirs() }
            var ok = 0
            var fail = 0
            for (l in LISTS + PSL) {
                if (download(l, dir)) ok++ else fail++
            }
            if (ok > 0) p(c).edit().putLong("updated", System.currentTimeMillis()).apply()
            if (ok > 0) {
                ready = CountDownLatch(1)
                rebuild(c)
            }
            done?.invoke(ok, fail)
        } catch (t: Throwable) { Log.w(TAG, "update failed", t); done?.invoke(0, 1) } }, "shields-update").start()
    }

    private fun download(l: FilterList, dir: File): Boolean {
        for (u in l.urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WebTV-Shields")
                }
                if (conn.responseCode != 200) continue
                val tmp = File(dir, l.file + ".tmp")
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                val head = tmp.bufferedReader().use { r -> CharArray(512).let { buf -> String(buf, 0, maxOf(0, r.read(buf))) } }
                val looksRight = tmp.length() > 2000 && !head.trimStart().startsWith("<")
                if (!looksRight) { tmp.delete(); continue }
                if (!tmp.renameTo(File(dir, l.file))) { tmp.delete(); continue }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Update failed for $u: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    // ------------------------------------------------------------------ requests

    private fun pageFlags(e: FilterEngine, pageUrl: String): Int =
        pageFlagCache[pageUrl] ?: e.pageFlags(pageUrl).also { pageFlagCache[pageUrl] = it }

    /** Shields are down for this page (user allowlist, `@@...$document` rule or switched off). */
    fun isPageExempt(c: Context, pageUrl: String?): Boolean {
        if (!isEnabled(c)) return true
        if (pageUrl.isNullOrEmpty() || !pageUrl.startsWith("http")) return false
        if (isSiteAllowed(c, pageUrl)) return true
        val e = engine ?: return false
        return pageFlags(e, pageUrl) and NetFilter.F_DOC != 0
    }

    /**
     * Called from WebViewClient.shouldInterceptRequest (background thread).
     * Returns a replacement response for blocked requests, or null to let it through.
     */
    fun intercept(c: Context, req: WebResourceRequest, topUrl: String?): WebResourceResponse? {
        val url = req.url.toString()
        if (!url.startsWith("http")) return null
        if (!isEnabled(c)) return null
        if (engine == null && !awaitReady(4000)) return null
        val e = engine ?: return null

        if (req.isForMainFrame) {
            if (req.method != "GET" || sessionAllow.contains(siteKey(url)) || isSiteAllowed(c, url)) return null
            val f = e.matchDocument(url) ?: return null
            return dangerPage(url, f)
        }
        if (isPageExempt(c, topUrl)) return null

        val headers = req.requestHeaders
        val referer = headers["Referer"] ?: headers["referer"]
        val source = if (!referer.isNullOrEmpty()) referer else topUrl
        val type = guessType(url, headers["Accept"] ?: headers["accept"])
        val f = e.match(e.request(url, source, type, req.method ?: "GET")) ?: return null
        pageBlocked.incrementAndGet()
        bumpTotal(c)
        return blockedResponse(type, f.redirect)
    }

    /** Popups and new windows: true when a `$popup` rule says this one is an ad. */
    fun isBlockedPopup(c: Context, url: String, opener: String?): Boolean {
        if (!isEnabled(c) || isPageExempt(c, opener)) return false
        val e = engine ?: return false
        return e.matchPopup(url, opener) != null
    }

    private var pendingTotal = 0
    @Synchronized private fun bumpTotal(c: Context) {
        pendingTotal++
        if (pendingTotal >= 25) flushTotal(c)
    }
    @Synchronized fun flushTotal(c: Context) {
        if (pendingTotal == 0) return
        p(c).edit().putLong("total", totalBlocked(c) + pendingTotal).apply()
        pendingTotal = 0
    }

    private fun guessType(url: String, accept: String?): Int {
        val a = accept?.lowercase() ?: ""
        if (a.startsWith("text/css")) return RT.STYLESHEET
        if (a.startsWith("image/")) return RT.IMAGE
        if (a.startsWith("text/html") || a.contains("application/xhtml")) return RT.SUBDOCUMENT
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when (ext) {
            "js", "mjs" -> RT.SCRIPT
            "css" -> RT.STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> RT.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> RT.FONT
            "mp4", "webm", "m3u8", "mpd", "mp3", "m4a", "m4s", "ts", "aac", "ogg", "vtt" -> RT.MEDIA or RT.XHR
            "html", "htm" -> RT.SUBDOCUMENT or RT.XHR
            "json" -> RT.XHR or RT.OTHER
            else -> if (a.startsWith("video/") || a.startsWith("audio/")) RT.MEDIA else RT.UNKNOWN
        }
    }

    private val GIF = byteArrayOf(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, 0, 0, 0, -1, -1, -1, 33, -7, 4, 1, 0, 0, 0, 0,
        44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59)

    private fun blockedResponse(type: Int, redirect: String?): WebResourceResponse {
        val (mime, body) = Resources.get(redirect) ?: when {
            type == RT.IMAGE -> "image/gif" to GIF
            type == RT.SCRIPT -> "application/javascript" to ByteArray(0)
            type == RT.STYLESHEET -> "text/css" to ByteArray(0)
            type and RT.SUBDOCUMENT != 0 && type and RT.XHR == 0 -> "text/html" to ByteArray(0)
            else -> "text/plain" to ByteArray(0)
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream(body))
    }

    /** Allows a blocked "dangerous" site for the rest of this app session. */
    fun proceedAnyway(url: String) { sessionAllow.add(siteKey(url)) }

    private fun dangerPage(url: String, f: NetFilter): WebResourceResponse {
        val host = esc(Uri.parse(url).host ?: url)
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<title>Blocked</title><style>body{background:#0f1014;color:#fff;font-family:sans-serif;display:flex;
align-items:center;justify-content:center;height:100vh;margin:0;text-align:center}div{max-width:70%}
h1{font-size:2.2em;margin:0 0 .4em}p{color:#a0a6b8;font-size:1.25em}button{font-size:1.2em;margin:12px;
padding:14px 28px;border-radius:10px;border:0;background:#4F8CFF;color:#fff}button.alt{background:#2a2e3a}
button:focus{outline:4px solid #fff}</style></head><body><div><h1>&#x1F6E1; Page blocked</h1>
<p><b>$host</b> is on a list of sites known for scams, malware or fake downloads, so Shields stopped it.</p>
<button id="b" onclick="history.length>1?history.back():location.replace('about:blank')">Go back</button>
<button class="alt" onclick="window.__wtvAdb&&__wtvAdb.proceed(location.href);location.reload()">Open anyway</button>
</div><script>document.getElementById('b').focus()</script></body></html>"""
        return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(html.toByteArray()))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ------------------------------------------------------------------ page script bridge

    /** Exposed to pages as window.__wtvAdb (used by assets/adblock.js). */
    class Bridge(private val c: Context, private val topUrl: () -> String?) {
        @JavascriptInterface
        fun cosmetic(url: String): String = try { cosmeticInner(url) } catch (t: Throwable) { OFF }

        private fun cosmeticInner(url: String): String {
            if (!url.startsWith("http")) return OFF
            if (isPageExempt(c, url) || isPageExempt(c, topUrl())) return OFF
            if (engine == null) awaitReady(3000)
            val e = engine ?: return OFF
            return try { e.cosmetic(url, pageFlags(e, url)).toJson() } catch (t: Throwable) { OFF }
        }

        @JavascriptInterface
        fun generic(url: String, classes: String, ids: String): String {
            val e = engine ?: return ""
            return try { e.genericFor(url, jsonList(classes), jsonList(ids)) } catch (t: Throwable) { "" }
        }

        @JavascriptInterface
        fun proceed(url: String) { try { proceedAnyway(url) } catch (t: Throwable) {} }

        private fun jsonList(s: String): List<String> {
            val a = JSONArray(s)
            val out = ArrayList<String>(a.length())
            for (i in 0 until minOf(a.length(), 5000)) out.add(a.optString(i))
            return out
        }

        companion object { private const val OFF = "{\"off\":1}" }
    }
}

/** Small stand-ins served instead of blocked scripts so pages that depend on them keep working. */
object Resources {
    private const val NOOP = "(function(){})();"
    private val gpt = """(function(){var n=function(){},s={addEventListener:function(){return s},addService:function(){return s},
setTargeting:function(){return s},clearTargeting:function(){return s},defineSizeMapping:function(){return s},
getSlotElementId:function(){return ''},setCollapseEmptyDiv:function(){return s},getTargeting:function(){return []},
getTargetingKeys:function(){return []},setClickUrl:function(){return s},getAdUnitPath:function(){return ''}},
p={addEventListener:function(){return p},removeEventListener:function(){return p},enableSingleRequest:function(){return p},
collapseEmptyDivs:function(){return p},disableInitialLoad:function(){return p},enableLazyLoad:function(){return p},
setTargeting:function(){return p},clearTargeting:function(){return p},getTargeting:function(){return []},
getSlots:function(){return []},refresh:n,display:n,setPrivacySettings:function(){return p},setRequestNonPersonalizedAds:function(){return p},
set:function(){return p},get:n,getTargetingKeys:function(){return []},setCentering:function(){return p},updateCorrelator:function(){return p},
enableVideoAds:function(){return p},setPublisherProvidedId:function(){return p}},
g=window.googletag=window.googletag||{};var c=g.cmd||[];g.apiReady=true;g.pubadsReady=true;g.pubads=function(){return p};
g.companionAds=function(){return p};g.content=function(){return p};g.defineSlot=function(){return s};
g.defineOutOfPageSlot=function(){return s};g.destroySlots=n;g.display=n;g.enableServices=n;g.sizeMapping=function(){
var m={addSize:function(){return m},build:function(){return []}};return m};g.setAdIframeTitle=n;g.openConsole=n;
g.cmd={push:function(f){try{f()}catch(e){}return 1}};c.forEach(function(f){try{f()}catch(e){}})})();"""
    private val adsbygoogle = """(function(){var a=window.adsbygoogle=window.adsbygoogle||[];var p=function(){};
window.adsbygoogle={loaded:true,push:p,length:0};})();"""
    private val ga = """(function(){var n=function(){},w=window,q=w.ga&&w.ga.q;var ga=function(){var a=arguments,l=a.length;
if(l&&typeof a[l-1]==='object'&&a[l-1].hitCallback){try{a[l-1].hitCallback()}catch(e){}}};ga.create=function(){return {get:n,set:n,send:n}};
ga.getByName=function(){return null};ga.getAll=function(){return []};ga.remove=n;ga.loaded=true;w.ga=ga;
var gaq=w._gaq=w._gaq||[];if(Array.isArray(q))q.forEach(function(x){ga.apply(null,x)});})();"""
    private val gtm = """(function(){var w=window,dl=w.dataLayer;if(dl&&dl.hide&&dl.hide.end){try{dl.hide.end()}catch(e){}}
if(Array.isArray(dl)){dl.forEach(function(e){if(e&&typeof e.eventCallback==='function'){try{e.eventCallback()}catch(x){}}})}})();"""
    private val apstag = """(function(){var n=function(){},w=window;w.apstag={_Q:[],fetchBids:function(o,cb){if(typeof cb==='function')cb([])},
init:n,setDisplayBids:n,targetingKeys:function(){return []}};})();"""

    fun get(name: String?): Pair<String, ByteArray>? {
        if (name.isNullOrEmpty()) return null
        val js = "application/javascript"
        return when (name.removeSuffix(".js").removeSuffix(":5")) {
            "googletagservices_gpt", "googletagservices.com/gpt", "gpt" -> js to gpt.toByteArray()
            "googlesyndication_adsbygoogle", "googlesyndication.com/adsbygoogle", "adsbygoogle" -> js to adsbygoogle.toByteArray()
            "google-analytics_analytics", "google-analytics_ga", "google-analytics.com/analytics", "google-analytics.com/ga" -> js to ga.toByteArray()
            "googletagmanager_gtm", "googletagmanager.com/gtm" -> js to gtm.toByteArray()
            "amazon_apstag", "amazon-adsystem.com/aax2/amzn_ads" -> js to apstag.toByteArray()
            "1x1.gif", "1x1-transparent.gif", "2x2.png", "3x2.png", "32x32.png", "noopimg" ->
                "image/gif" to byteArrayOf(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, 0, 0, 0, -1, -1, -1, 33, -7, 4, 1, 0, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59)
            "noop.html", "noopframe" -> "text/html" to "<!DOCTYPE html><html><head></head><body></body></html>".toByteArray()
            "noop.txt", "nooptext", "empty" -> "text/plain" to ByteArray(0)
            "noop.json", "noopjson" -> "application/json" to "{}".toByteArray()
            "noopmp3-0.1s", "noopmp4-1s", "noop-0.1s.mp3", "noop-1s.mp4", "noopvast-2.0", "noopvast-3.0", "noopvast-4.0", "noopvmap-1.0" ->
                "text/plain" to ByteArray(0)
            else -> if (name.endsWith(".js") || name.startsWith("noop")) js to NOOP.toByteArray() else null
        }
    }
}
