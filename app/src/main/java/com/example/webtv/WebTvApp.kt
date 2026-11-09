package com.example.webtv

import android.app.Application
import android.content.Context
import android.os.Build
import com.example.webtv.adblock.AdBlocker
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashRecorder(this)
        // Test builds only (debuggable): lets the automated YouTube test inspect the page.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            try { android.webkit.WebView.setWebContentsDebuggingEnabled(true) } catch (t: Throwable) {}
        }
        // Start loading the ad-block filter lists right away, so they're ready by the time the page loads.
        if (!Prefs.safeMode(this)) AdBlocker.init(this)
    }

    companion object {
        fun crashFile(c: Context) = File(c.filesDir, "last_crash.txt")

        /** Saves the error of a crash so the app can show it on the next launch. */
        private fun installCrashRecorder(c: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    val sw = StringWriter()
                    error.printStackTrace(PrintWriter(sw))
                    val info = packageVersion(c)
                    crashFile(c).writeText(
                        "Web TV $info crashed " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) +
                            "\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
                            "\nWebView: ${webViewVersion(c)}\nThread: ${thread.name}\n\n$sw"
                    )
                } catch (t: Throwable) { /* nothing more we can do */ }
                previous?.uncaughtException(thread, error)
            }
        }

        private fun packageVersion(c: Context): String = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
        } catch (t: Throwable) { "" }

        fun webViewVersion(c: Context): String = try {
            if (Build.VERSION.SDK_INT >= 26) {
                val p = android.webkit.WebView.getCurrentWebViewPackage()
                if (p != null) "${p.packageName} ${p.versionName}" else "unknown"
            } else "unknown"
        } catch (t: Throwable) { "unknown" }
    }
}
