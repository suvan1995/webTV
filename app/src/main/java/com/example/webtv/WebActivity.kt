package com.example.webtv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.webtv.adblock.AdBlocker
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

class WebActivity : Activity() {

    companion object {
        const val SMART = 0
        const val CURSOR = 1
        const val NATIVE = 2

        /** Click-tracking parameters Brave strips from links ("query filtering"). */
        private val TRACKING_PARAMS = setOf(
            "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "yclid", "_openstat",
            "twclid", "ttclid", "igshid", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_conv", "vero_id",
            "wickedid", "__hsfp", "__hssc", "__hstc", "_hsenc", "hsctatracking", "rb_clickid", "s_cid",
            "ml_subscriber", "ml_subscriber_hash", "ss_email_id", "epik", "srsltid", "_kx", "irclickid"
        )
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView

    /** A real pop-up window (e.g. "Sign in with Google") shown on top of the page. */
    private var popup: WebView? = null
    private val active: WebView get() = popup ?: web

    private var navScript = ""
    private var loginScript = ""
    private var adblockScript = ""
    private var docStartSupported = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var mode = SMART
    private var errorShown = false
    private var cx = 0f
    private var cy = 0f
    private var cursorSize = 0
    private var lastNav = 0L
    private var textDialog: AlertDialog? = null
    @Volatile private var topUrl: String? = null

    // Login saving
    private val pendingUser = HashMap<String, String>()
    private var lastOffer = ""
    private var saveDialog: AlertDialog? = null

    private val chrome = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customCallback = callback
            active.visibility = View.GONE
            root.addView(
                view, root.indexOfChild(cursor),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            root.removeView(v)
            customView = null
            active.visibility = View.VISIBLE
            customCallback?.onCustomViewHidden()
            customCallback = null
            active.requestFocus()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture && AdBlocker.isEnabled(this@WebActivity)) {
                toast(getString(R.string.popup_blocked))
                return false
            }
            val opener = view.url
            val child = WebView(this@WebActivity)
            setupWebView(child)
            var decided = false
            child.webViewClient = object : PageClient() {
                fun decide(u: String?): Boolean {
                    if (decided) return false
                    if (u == null || !(u.startsWith("http:") || u.startsWith("https:"))) return false
                    decided = true
                    if (AdBlocker.isBlockedPopup(applicationContext, u, opener)) {
                        toast(getString(R.string.popup_blocked))
                        child.post { child.stopLoading(); child.destroy() }
                        return true
                    }
                    if (popup != null || view !== web) {
                        // Already showing a pop-up: open in it instead of stacking windows.
                        child.post { child.destroy() }
                        active.loadUrl(u)
                        return true
                    }
                    showPopup(child)
                    return false
                }

                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                    if (!decided) decide(request.url.toString()) else super.shouldOverrideUrlLoading(v, request)

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (!decided && decide(url)) return
                    super.onPageStarted(v, url, favicon)
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popup) closePopup()
        }
    }

    /** Methods the page script (tvnav.js) can call. */
    private inner class Bridge {
        @JavascriptInterface
        fun askText(value: String, hint: String, type: String, search: Boolean) {
            runOnUiThread { showTextDialog(value, hint, type, search) }
        }

        @JavascriptInterface
        fun tap(x: Double, y: Double, viewportCssWidth: Double) {
            runOnUiThread {
                val w = active
                if (viewportCssWidth > 0 && w.width > 0) {
                    val s = w.width / viewportCssWidth.toFloat()
                    sendTap(w, (x * s).toFloat(), (y * s).toFloat())
                }
            }
        }

        @JavascriptInterface
        fun hideKeyboard() {
            runOnUiThread { hideIme() }
        }
    }

    /** Shared by the main page and pop-up windows. */
    private open inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val s = uri.scheme
            if (s != "http" && s != "https") return true
            if (request.isForMainFrame && request.method == "GET") {
                val clean = stripTracking(uri)
                if (clean != null) { view.loadUrl(clean); return true }
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val top = if (view === web) topUrl else popupUrl
            if (safeMode) return null
            return try { AdBlocker.intercept(applicationContext, request, top) } catch (t: Throwable) { null }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view === web) {
                if (url != null && url.startsWith("http")) { errorShown = false; topUrl = url }
                AdBlocker.pageBlocked.set(0)
            } else popupUrl = url
            inject(view, true)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) = inject(view, false)

        override fun onPageFinished(view: WebView, url: String?) {
            inject(view, false)
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null && url.startsWith("http")) { if (view === web) topUrl = url else popupUrl = url }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && view === web) {
                errorShown = true
                val html = "<html><body style='background:#0f1014;color:#fff;font-family:sans-serif;" +
                    "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>" +
                    "<div><h1>Can't load the page</h1><p>Check your connection, then press Back " +
                    "and choose Reload page.</p></div></body></html>"
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
    }

    @Volatile private var popupUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!Prefs.safeMode(this)) AdBlocker.init(applicationContext)


        navScript = asset("tvnav.js")
        loginScript = asset("logins.js")
        adblockScript = if (Prefs.safeMode(this)) "" else asset("adblock.js")
        safeMode = Prefs.safeMode(this)
        docStartSupported = !safeMode && feature(WebViewFeature.DOCUMENT_START_SCRIPT)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cursorSize = dp(26).toInt()
        cursor = CursorView(this).apply { isFocusable = false }
        root.addView(cursor, FrameLayout.LayoutParams(cursorSize, cursorSize))
        setContentView(root)
        hideSystemBars()

        setupWebView(web)
        web.webViewClient = PageClient()

        // Service workers fetch on their own: run their requests through the shields too.
        if (!safeMode && feature(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            feature(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            try {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                        try { AdBlocker.intercept(applicationContext, request, topUrl) } catch (t: Throwable) { null }
                })
            } catch (t: Throwable) { Log.w("WebTV", "service worker hook", t) }
        }


        mode = Prefs.mode(this)
        applyMode()
        root.post {
            cx = root.width / 2f
            cy = root.height / 2f
            updateCursor()
        }

        if (!Prefs.hinted(this)) {
            Toast.makeText(this, "Tip: OK selects, Back opens the menu, Menu/Info key also opens it", Toast.LENGTH_LONG).show()
            Prefs.setHinted(this)
        }

        web.loadUrl(Prefs.getUrl(this) ?: "about:blank")
        web.requestFocus()
    }

    @Deprecated("Back is handled here for every Android version")
    override fun onBackPressed() {
        val p = popup
        when {
            customView != null -> chrome.onHideCustomView()
            p != null -> if (p.canGoBack()) p.goBack() else closePopup()
            web.canGoBack() && !errorShown -> web.goBack()
            else -> showMenu()
        }
    }

    private fun hideSystemBars() = try { hideSystemBarsInner() } catch (t: Throwable) { Log.w("WebTV", "system bars", t) }

    private fun hideSystemBarsInner() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private var safeMode = false

    /** androidx.webkit feature check that can never crash the app. */
    private fun feature(name: String): Boolean = try {
        WebViewFeature.isFeatureSupported(name)
    } catch (t: Throwable) {
        Log.w("WebTV", "feature check $name", t); false
    }

    private fun asset(name: String) = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) { "" }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(w: WebView) {
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            // Look like regular Chrome: some sites (and Google sign-in) refuse the WebView markers.
            userAgentString = userAgentString.replace("; wv", "").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }
        w.addJavascriptInterface(Bridge(), "AndroidTV")
        w.addJavascriptInterface(AdBlocker.Bridge(applicationContext) { if (w === web) topUrl else popupUrl }, "__wtvAdb")
        w.webChromeClient = chrome

        if (docStartSupported && adblockScript.isNotEmpty()) {
            try { WebViewCompat.addDocumentStartJavaScript(w, adblockScript, setOf("*")) }
            catch (t: Throwable) { Log.w("WebTV", "document-start script", t); docStartSupported = false }
        }
        if (!safeMode && feature(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(w, "wtvLogin", setOf("*"),
                    WebViewCompat.WebMessageListener { _: WebView, message: WebMessageCompat, sourceOrigin: Uri,
                                                       isMainFrame: Boolean, reply: JavaScriptReplyProxy ->
                        try { onLoginMessage(message.data, sourceOrigin, isMainFrame, reply) } catch (t: Throwable) { Log.w("WebTV", "login", t) }
                    })
            } catch (t: Throwable) { Log.w("WebTV", "login listener", t) }
        }
    }

    private fun inject(view: WebView, starting: Boolean) {
        // Without document-start support, run the shields as early as WebView allows.
        if (!docStartSupported && adblockScript.isNotEmpty()) view.evaluateJavascript(adblockScript, null)
        if (navScript.isNotEmpty()) view.evaluateJavascript(navScript, null)
        if (!starting && loginScript.isNotEmpty()) view.evaluateJavascript(loginScript, null)
    }

    private fun stripTracking(uri: Uri): String? {
        if (!AdBlocker.isEnabled(this)) return null
        val names = try { uri.queryParameterNames } catch (e: Exception) { return null }
        if (names.none { it.lowercase() in TRACKING_PARAMS || it.lowercase().startsWith("utm_") }) return null
        val b = uri.buildUpon().clearQuery()
        for (n in names) {
            val l = n.lowercase()
            if (l in TRACKING_PARAMS || l.startsWith("utm_")) continue
            for (v in uri.getQueryParameters(n)) b.appendQueryParameter(n, v)
        }
        return b.build().toString()
    }

    // ---------- Pop-up windows ----------

    private fun showPopup(child: WebView) {
        popup = child
        child.setBackgroundColor(Color.WHITE)
        root.addView(child, root.indexOfChild(cursor),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.visibility = View.INVISIBLE
        child.requestFocus()
        toast(getString(R.string.popup_opened))
    }

    private fun closePopup() {
        val p = popup ?: return
        popup = null
        popupUrl = null
        root.removeView(p)
        p.destroy()
        web.visibility = View.VISIBLE
        web.requestFocus()
        CookieManager.getInstance().flush()
    }

    // ---------- Saved logins ----------

    private fun onLoginMessage(data: String?, sourceOrigin: Uri, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        if (!isMainFrame || data == null || !LoginStore.isEnabled(this)) return
        val origin = sourceOrigin.toString().trimEnd('/')
        if (!origin.startsWith("https://") && !origin.startsWith("http://")) return
        val msg = try { JSONObject(data) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "need" -> {
                val l = LoginStore.forOrigin(this, origin).firstOrNull() ?: return
                reply.postMessage(JSONObject().put("type", "creds").put("user", l.user).put("pass", l.pass).toString())
            }
            "user" -> pendingUser[origin] = msg.optString("user").take(200)
            "submit" -> {
                val pass = msg.optString("pass")
                if (pass.isEmpty() || pass.length > 500) return
                val user = msg.optString("user").ifEmpty { pendingUser[origin] ?: "" }.take(200)
                offerSave(origin, user, pass)
            }
        }
    }

    private fun offerSave(origin: String, user: String, pass: String) {
        if (LoginStore.isNever(this, origin)) return
        val existing = LoginStore.find(this, origin, user)
        if (existing != null && existing.pass == pass) { LoginStore.touch(this, existing); return }
        val key = "$origin\u0000$user\u0000$pass"
        if (key == lastOffer || saveDialog?.isShowing == true) return
        lastOffer = key
        val host = Uri.parse(origin).host ?: origin
        val who = if (user.isNotEmpty()) "\n\n$user" else ""
        saveDialog = AlertDialog.Builder(this)
            .setTitle(if (existing != null) getString(R.string.update_password_title) else getString(R.string.save_password_title))
            .setMessage(getString(R.string.save_password_msg, host) + who)
            .setPositiveButton(if (existing != null) "Update" else "Save") { _, _ ->
                if (LoginStore.save(this, origin, user, pass)) toast(getString(R.string.password_saved))
                else toast(getString(R.string.password_save_failed))
            }
            .setNeutralButton("Never for this site") { _, _ -> LoginStore.setNever(this, origin) }
            .setNegativeButton("Not now", null)
            .create()
        saveDialog?.setOnDismissListener { active.requestFocus() }
        saveDialog?.show()
    }

    private fun showSavedLogins() {
        val list = LoginStore.all(this).sortedBy { it.origin }
        if (list.isEmpty()) {
            AlertDialog.Builder(this).setTitle(R.string.saved_passwords)
                .setMessage(R.string.no_saved_passwords).setPositiveButton("Close", null).show()
            return
        }
        val labels = list.map { (Uri.parse(it.origin).host ?: it.origin) + "  —  " + it.user.ifEmpty { "(no username)" } }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.saved_passwords)
            .setItems(labels) { _, which ->
                val l = list[which]
                AlertDialog.Builder(this)
                    .setTitle(labels[which])
                    .setMessage(R.string.delete_password_q)
                    .setPositiveButton("Delete") { _, _ -> LoginStore.remove(this, l); toast("Deleted") }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun signOutEverywhere() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sign_out_all)
            .setMessage(R.string.sign_out_all_msg)
            .setPositiveButton("Sign out") { _, _ ->
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    web.clearCache(true)
                    toast(getString(R.string.signed_out))
                    web.loadUrl(Prefs.getUrl(this) ?: "about:blank")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Shields ----------

    private fun showShields() {
        val url = active.url
        val on = AdBlocker.isEnabled(this)
        val siteAllowed = AdBlocker.isSiteAllowed(this, url)
        val site = AdBlocker.siteKey(url).ifEmpty { "this site" }
        val updated = AdBlocker.lastUpdate(this).let {
            if (it == 0L) "built-in lists" else "updated " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
        }
        val items = arrayOf(
            "Shields for $site: " + if (siteAllowed) "DOWN (ads allowed)" else "UP",
            "Ad & tracker blocking (all sites): " + if (on) "ON" else "OFF",
            "Hide cookie consent pop-ups: " + if (AdBlocker.cookieNotices(this)) "ON" else "OFF",
            "Save passwords: " + if (LoginStore.isEnabled(this)) "ON" else "OFF",
            "Update filter lists now ($updated)"
        )
        AdBlocker.flushTotal(this)
        AlertDialog.Builder(this)
            .setTitle("Shields — ${AdBlocker.pageBlocked.get()} blocked on this page, " +
                "${AdBlocker.totalBlocked(this)} in total")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { AdBlocker.setSiteAllowed(this, url, !siteAllowed); reloadPage() }
                    1 -> { AdBlocker.setEnabled(this, !on); reloadPage() }
                    2 -> {
                        AdBlocker.setCookieNotices(this, !AdBlocker.cookieNotices(this))
                        toast(getString(R.string.applies_after_reload))
                    }
                    3 -> LoginStore.setEnabled(this, !LoginStore.isEnabled(this))
                    4 -> {
                        toast(getString(R.string.updating_lists))
                        AdBlocker.update(applicationContext) { ok, fail ->
                            runOnUiThread {
                                toast(if (ok > 0) getString(R.string.lists_updated, ok) + if (fail > 0) " ($fail failed)" else ""
                                      else getString(R.string.lists_update_failed))
                            }
                        }
                    }
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun js(code: String) = active.evaluateJavascript(code, null)
    private fun mediaAction(a: String) = js("window.__tv&&__tv.media('$a')")

    // ---------- Remote control handling ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP

        // Keys that work in every mode
        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_PROG_YELLOW -> { if (up) showMenu(); return true }
            KeyEvent.KEYCODE_PROG_RED -> { if (up) reloadPage(); return true }
            KeyEvent.KEYCODE_PROG_BLUE -> { if (up) showShields(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down && e.repeatCount == 0) mediaAction("toggle"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) mediaAction("play"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) mediaAction("pause"); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) mediaAction("ff"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) mediaAction("rw"); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) js("window.__tv&&__tv.scroll('up')"); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) js("window.__tv&&__tv.scroll('down')"); return true }
        }

        // Fullscreen video: OK = play/pause, Left/Right = seek 10s
        if (customView != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (up) toggleOrTapVideo()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) mediaAction("rw"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) mediaAction("ff"); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
            return super.dispatchKeyEvent(e)
        }

        when (mode) {
            SMART -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) {
                        val now = SystemClock.uptimeMillis()
                        if (e.repeatCount == 0 || now - lastNav > 90) {
                            lastNav = now
                            val dir = when (code) {
                                KeyEvent.KEYCODE_DPAD_UP -> "up"
                                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                                else -> "right"
                            }
                            js("window.__tv&&__tv.nav('$dir')")
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (up) js("window.__tv&&__tv.ok()")
                    return true
                }
            }
            CURSOR -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) moveCursor(code, e.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (up) sendTap(active, cx, cy)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun toggleOrTapVideo() {
        active.evaluateJavascript("window.__tv ? __tv.media('toggle') : 'none'") { r ->
            if (r == null || !r.contains("ok")) {
                val v: View = customView ?: active
                sendTap(v, v.width / 2f, v.height / 2f)
            }
        }
    }

    /** A real touch tap (counts as a genuine user gesture, so fullscreen etc. work). */
    private fun sendTap(target: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        target.dispatchTouchEvent(down)
        down.recycle()
        target.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, x, y, 0)
            target.dispatchTouchEvent(up)
            up.recycle()
        }, 50)
    }

    private fun moveCursor(code: Int, repeat: Int) {
        val step = dp(10) * (1f + min(repeat, 24) / 4f)
        var dx = 0f
        var dy = 0f
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
        }
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        cx = (cx + dx).coerceIn(0f, w)
        cy = (cy + dy).coerceIn(0f, h)
        val edge = dp(8)
        val view = active
        if (dy > 0 && cy >= h - edge) view.scrollBy(0, dy.toInt())
        if (dy < 0 && cy <= edge) view.scrollBy(0, dy.toInt())
        if (dx > 0 && cx >= w - edge) view.scrollBy(dx.toInt(), 0)
        if (dx < 0 && cx <= edge) view.scrollBy(dx.toInt(), 0)
        updateCursor()
    }

    private fun updateCursor() {
        cursor.translationX = cx - cursorSize / 2f
        cursor.translationY = cy - cursorSize / 2f
    }

    private fun applyMode() {
        cursor.visibility = if (mode == CURSOR) View.VISIBLE else View.GONE
        active.requestFocus()
    }

    private fun modeName() = when (mode) {
        SMART -> "Smart focus"
        CURSOR -> "Cursor"
        else -> "Native"
    }

    private fun reloadPage() {
        if (popup != null) { active.reload(); return }
        if (errorShown) web.loadUrl(Prefs.getUrl(this) ?: "about:blank") else web.reload()
    }

    // ---------- Typing ----------

    private fun showTextDialog(value: String, hint: String, type: String, search: Boolean) {
        if (textDialog?.isShowing == true) return
        val pad = dp(20).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            textSize = 24f
            this.hint = hint
            inputType = when (type) {
                "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                "number" -> InputType.TYPE_CLASS_NUMBER
                "tel" -> InputType.TYPE_CLASS_PHONE
                "multiline" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (type != "multiline") imeOptions = if (search) EditorInfo.IME_ACTION_SEARCH else EditorInfo.IME_ACTION_DONE
            setText(value)
            setSelection(text.length)
        }
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (type == "password") {
            val show = CheckBox(this).apply {
                text = "Show password"
                setOnCheckedChangeListener { _, checked ->
                    input.inputType = InputType.TYPE_CLASS_TEXT or
                        (if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    input.setSelection(input.text.length)
                }
            }
            box.addView(show)
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (hint.isBlank()) "Type text" else hint)
            .setView(box)
            .setPositiveButton(if (search) "Search" else "Submit") { _, _ -> sendText(input.text.toString(), true) }
            .setNeutralButton("Done") { _, _ -> sendText(input.text.toString(), false) }
            .setNegativeButton("Cancel", null)
            .create()

        input.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO -> {
                    sendText(input.text.toString(), true); dlg.dismiss(); true
                }
                EditorInfo.IME_ACTION_DONE -> {
                    sendText(input.text.toString(), false); dlg.dismiss(); true
                }
                else -> false
            }
        }
        dlg.setOnDismissListener { textDialog = null; active.requestFocus() }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        textDialog = dlg
        dlg.show()
        input.requestFocus()
        input.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendText(text: String, submit: Boolean) {
        js("window.__tv&&__tv.setText(${JSONObject.quote(text)},$submit)")
    }

    private fun hideIme() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(active.windowToken, 0)
    }

    // ---------- Menu ----------

    private fun showMenu() {
        val shieldsLabel = if (!AdBlocker.isEnabled(this)) "Shields: OFF"
            else if (AdBlocker.isSiteAllowed(this, active.url)) "Shields: down for this site"
            else "Shields: ${AdBlocker.pageBlocked.get()} ads & trackers blocked here"
        val items = arrayOf(
            "Resume",
            "Reload page",
            "Go to home page",
            "$shieldsLabel  (select for options)",
            "Navigation: ${modeName()}  (select to change)",
            "Saved passwords",
            "Sign out of all websites",
            "Remote help",
            "Change website",
            "Exit app"
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                when (which) {
                    1 -> reloadPage()
                    2 -> { closePopup(); web.loadUrl(Prefs.getUrl(this) ?: "about:blank") }
                    3 -> showShields()
                    4 -> {
                        mode = (mode + 1) % 3
                        Prefs.setMode(this, mode)
                        applyMode()
                        Toast.makeText(this, "Navigation: ${modeName()}", Toast.LENGTH_SHORT).show()
                    }
                    5 -> showSavedLogins()
                    6 -> signOutEverywhere()
                    7 -> showHelp()
                    8 -> {
                        startActivity(Intent(this, SetupActivity::class.java))
                        finish()
                    }
                    9 -> {
                        CookieManager.getInstance().flush()
                        finishAffinity()
                    }
                }
            }
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Remote help")
            .setMessage(
                "Arrow keys: jump between links and buttons\n" +
                "OK: select / open the typing box on a text field\n" +
                "Back: previous page (menu on the first page)\n" +
                "Menu / Info / Settings / Yellow: open this menu\n" +
                "Blue: Shields (ad blocker) options\n" +
                "Red: reload\n" +
                "Play/Pause, Fast-forward, Rewind: control video\n" +
                "Channel +/- or Page Up/Down: scroll\n" +
                "Fullscreen video: OK = play/pause, Left/Right = 10 s seek\n\n" +
                "You stay signed in to websites until you sign out on the site " +
                "(or use \"Sign out of all websites\" in this menu).\n\n" +
                "If a site doesn't respond well, change Navigation to Cursor from the menu. " +
                "If a site breaks, turn Shields down for that site."
            )
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------- Lifecycle ----------

    override fun onResume() {
        super.onResume()
        web.onResume()
        popup?.onResume()
    }

    override fun onPause() {
        popup?.onPause()
        web.onPause()
        // Write cookies (logins) to disk now: the TV may kill the app at any moment once it's in the background.
        CookieManager.getInstance().flush()
        AdBlocker.flushTotal(this)
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        popup?.destroy()
        popup = null
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density
}

private class CursorView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        canvas.drawCircle(r, r, r - 4f, fill)
        canvas.drawCircle(r, r, r - 4f, stroke)
    }
}
