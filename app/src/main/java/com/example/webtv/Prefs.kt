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

    // ---------- Saved websites ----------

    /** Every website the user has added (home URLs), in the order added. */
    fun sites(c: Context): List<String> {
        val raw = p(c).getString("sites", null)
        val list = if (raw.isNullOrEmpty()) mutableListOf() else raw.split('\n').filter { it.isNotBlank() }.toMutableList()
        val cur = getUrl(c)
        if (cur != null && list.none { sameSite(it, cur) }) list.add(0, cur)
        return list
    }
    private fun saveSites(c: Context, l: List<String>) = p(c).edit().putString("sites", l.joinToString("\n")).apply()

    /** Adds (or replaces the entry for the same host) and makes it the current site. */
    fun addSite(c: Context, url: String) {
        val l = sites(c).filterNot { sameSite(it, url) }.toMutableList()
        l.add(url)
        saveSites(c, l)
        setUrl(c, url)
    }

    fun removeSite(c: Context, url: String) {
        val l = sites(c).filterNot { it == url }
        saveSites(c, l)
        p(c).edit().remove("last:" + host(url)).apply()
        if (getUrl(c) == url) {
            if (l.isEmpty()) p(c).edit().remove("url").apply() else setUrl(c, l.last())
        }
    }

    fun host(url: String?): String = try { Uri.parse(url ?: "").host?.lowercase()?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }
    fun sameSite(a: String, b: String) = host(a).isNotEmpty() && host(a) == host(b)
    fun siteName(url: String): String = host(url).ifEmpty { url }

    /** The page you were last on for a site, so the app reopens where you left off. */
    fun lastPage(c: Context, home: String): String? = p(c).getString("last:" + host(home), null)
    fun setLastPage(c: Context, home: String, page: String) = p(c).edit().putString("last:" + host(home), page).apply()

    // ---------- Display ----------

    /** Sites (hosts) shown in their desktop version. */
    fun isDesktop(c: Context, url: String?): Boolean {
        val h = host(url)
        return h.isNotEmpty() && (p(c).getStringSet("desktop", emptySet()) ?: emptySet()).any { h == it || h.endsWith(".$it") }
    }
    fun setDesktop(c: Context, url: String?, on: Boolean) {
        val h = host(url)
        if (h.isEmpty()) return
        val s = HashSet(p(c).getStringSet("desktop", emptySet()) ?: emptySet())
        s.removeAll { h == it || h.endsWith(".$it") }
        if (on) s.add(h)
        p(c).edit().putStringSet("desktop", s).apply()
    }

    /** Text size in percent (100 = normal). */
    fun textZoom(c: Context): Int = p(c).getInt("textZoom", 100)
    fun setTextZoom(c: Context, z: Int) = p(c).edit().putInt("textZoom", z).apply()

    /** Navigation mode remembered per website; falls back to the global choice. */
    fun siteMode(c: Context, home: String?): Int = p(c).getInt("mode:" + host(home), mode(c))
    fun setSiteMode(c: Context, home: String?, m: Int) {
        p(c).edit().putInt("mode:" + host(home), m).putInt("mode", m).apply()
    }

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
