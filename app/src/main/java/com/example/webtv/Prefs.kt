package com.example.webtv

import android.content.Context
import android.net.Uri

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("webtv", Context.MODE_PRIVATE)

    fun getUrl(c: Context): String? = p(c).getString("url", null)
    fun setUrl(c: Context, u: String) = p(c).edit().putString("url", u).apply()

    /** 0 = smart focus (default), 1 = cursor, 2 = native */
    fun mode(c: Context): Int = p(c).getInt("mode", 0)
    fun setMode(c: Context, m: Int) = p(c).edit().putInt("mode", m).apply()

    /** Safe mode: plain WebView without the ad blocker / login extras (offered after a crash). */
    fun safeMode(c: Context): Boolean = p(c).getBoolean("safe", false)
    fun setSafeMode(c: Context, on: Boolean) = p(c).edit().putBoolean("safe", on).apply()

    fun hinted(c: Context): Boolean = p(c).getBoolean("hinted", false)
    fun setHinted(c: Context) = p(c).edit().putBoolean("hinted", true).apply()

    /** Returns a valid http(s) URL or null. Adds https:// when no scheme is typed. */
    fun normalize(input: String): String? {
        var t = input.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        if (!t.contains("://")) t = "https://$t"
        val uri = Uri.parse(t)
        val host = uri.host ?: return null
        val okScheme = uri.scheme == "http" || uri.scheme == "https"
        return if (okScheme && (host.contains('.') || host == "localhost")) t else null
    }
}
