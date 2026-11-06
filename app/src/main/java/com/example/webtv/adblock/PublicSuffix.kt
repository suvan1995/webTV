package com.example.webtv.adblock

import java.io.BufferedReader

/**
 * Public Suffix List lookups (publicsuffix.org), used to decide what is "first party"
 * and to resolve entity rules such as `google.*`.
 */
class PublicSuffix(reader: BufferedReader) {
    private val exact = HashSet<String>(16000)
    private val wildcard = HashSet<String>(64)   // "*.ck"   -> "ck"
    private val exception = HashSet<String>(32)  // "!www.ck" -> "www.ck"

    init {
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) return@forEachLine
            val rule = line.substringBefore(' ').lowercase()
            when {
                rule.startsWith("!") -> exception.add(rule.substring(1))
                rule.startsWith("*.") -> wildcard.add(rule.substring(2))
                else -> exact.add(rule)
            }
        }
    }

    /** Length of the public suffix at the end of [host] (e.g. "co.uk" for "a.b.co.uk"). */
    fun suffix(host: String): String {
        if (host.isEmpty() || isIp(host)) return ""
        var i = 0
        while (true) {
            val cand = host.substring(i)
            if (exception.contains(cand)) {
                val dot = cand.indexOf('.')
                return if (dot < 0) "" else cand.substring(dot + 1)
            }
            if (exact.contains(cand)) return cand
            val dot = cand.indexOf('.')
            if (dot >= 0 && wildcard.contains(cand.substring(dot + 1))) return cand
            if (dot < 0) return cand // unknown TLD: the last label is the suffix
            i += dot + 1
        }
    }

    /** eTLD+1 ("example.co.uk"), or the host itself when there is nothing above the suffix. */
    fun registrable(host: String): String {
        if (host.isEmpty() || isIp(host)) return host
        val sfx = suffix(host)
        if (sfx.length >= host.length) return host
        val rest = host.substring(0, host.length - sfx.length - 1)
        val dot = rest.lastIndexOf('.')
        return if (dot < 0) host else host.substring(dot + 1)
    }

    /** Host with its public suffix removed ("www.google" for "www.google.co.uk"). */
    fun withoutSuffix(host: String): String? {
        if (host.isEmpty() || isIp(host)) return null
        val sfx = suffix(host)
        if (sfx.length >= host.length) return null
        return host.substring(0, host.length - sfx.length - 1)
    }

    companion object {
        fun isIp(host: String): Boolean {
            if (host.startsWith("[") || host.contains(':')) return true
            var dots = 0
            for (c in host) {
                if (c == '.') dots++ else if (c !in '0'..'9') return false
            }
            return dots == 3
        }
    }
}
