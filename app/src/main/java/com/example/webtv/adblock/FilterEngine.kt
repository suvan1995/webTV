package com.example.webtv.adblock

import java.io.BufferedReader
import java.util.regex.Pattern

/**
 * A content-blocking engine for Adblock Plus / uBlock Origin / Brave filter syntax
 * (the same rule format Brave's adblock-rust consumes: EasyList, EasyPrivacy, uBO lists, ...).
 *
 *  - Network rules: `||host^`, `|https://`, `*` wildcards, `^` separators, `/regex/`,
 *    `@@` exceptions, and options ($third-party, $script, $domain=, $important, $badfilter,
 *    $redirect=, $denyallow=, $to=, $method=, $match-case, $generichide, $document, ...).
 *  - Cosmetic rules: `##selector`, `domain##selector`, `#@#` exceptions, entities (`google.*`),
 *    procedural selectors (`:has-text()`, `:upward()`, `:style()`, ...) and `##+js()` scriptlets.
 *
 * Pure Kotlin/JVM (no Android classes) so it can be unit-tested anywhere. Immutable once built,
 * so it is safe to use from WebView's request threads.
 */
object RT {
    const val OTHER = 1
    const val SCRIPT = 2
    const val IMAGE = 4
    const val STYLESHEET = 8
    const val OBJECT = 16
    const val XHR = 32
    const val SUBDOCUMENT = 64
    const val PING = 128
    const val MEDIA = 256
    const val FONT = 512
    const val WEBSOCKET = 1024
    const val DOCUMENT = 2048
    const val POPUP = 4096
    const val ALL_RES = 2047
    /** What we use when WebView doesn't tell us the type (script, fetch/XHR, beacon...). */
    const val UNKNOWN = OTHER or SCRIPT or XHR or PING
}

class AdRequest internal constructor(
    val urlOrig: String,
    val typeMask: Int,
    val method: String,
    sourceUrl: String?,
    private val psl: PublicSuffix
) {
    val url: String = urlOrig.lowercase()
    val hostStart: Int
    val hostEnd: Int
    val host: String
    val sourceHost: String

    init {
        val r = hostRange(url)
        hostStart = r.first; hostEnd = r.second
        host = if (hostStart in 0 until hostEnd) url.substring(hostStart, hostEnd) else ""
        sourceHost = if (sourceUrl.isNullOrEmpty()) "" else {
            val s = sourceUrl.lowercase()
            val sr = hostRange(s)
            if (sr.first in 0 until sr.second) s.substring(sr.first, sr.second) else ""
        }
    }

    val thirdParty: Boolean by lazy {
        sourceHost.isNotEmpty() && psl.registrable(host) != psl.registrable(sourceHost)
    }
    val sourceEntity: String? by lazy { psl.withoutSuffix(sourceHost) }
    val hostEntity: String? by lazy { psl.withoutSuffix(host) }

    val tokens: List<String> by lazy {
        val out = ArrayList<String>(24)
        var start = -1
        val u = url
        for (i in 0..u.length) {
            val tok = i < u.length && isTokenChar(u[i])
            if (tok) { if (start < 0) start = i }
            else if (start >= 0) {
                if (i - start >= 2) out.add(u.substring(start, i))
                start = -1
            }
        }
        out
    }

    companion object {
        /** [start, end) of the hostname inside a (lowercase) URL; (-1,-1) when there is none. */
        fun hostRange(u: String): Pair<Int, Int> {
            val se = u.indexOf("://")
            if (se <= 0) return Pair(-1, -1)
            var hs = se + 3
            var ae = u.length
            for (i in hs until u.length) {
                val c = u[i]
                if (c == '/' || c == '?' || c == '#') { ae = i; break }
            }
            val at = u.lastIndexOf('@', ae - 1)
            if (at >= hs) hs = at + 1
            var he = ae
            if (hs < u.length && u[hs] == '[') {
                val rb = u.indexOf(']', hs)
                if (rb in hs until ae) he = rb + 1
            } else {
                val colon = u.indexOf(':', hs)
                if (colon in hs until ae) he = colon
            }
            return Pair(hs, he)
        }
    }
}

internal fun isTokenChar(c: Char) = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

internal fun isSeparator(c: Char): Boolean =
    !((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-' || c == '.' || c == '%')

/** Domain constraint such as `domain=a.com|~b.a.com|google.*` (entities end with ".*"). */
class DomainSet(private val inc: HashSet<String>?, private val exc: HashSet<String>?) {
    val hasInc get() = inc != null
    val incList: Collection<String> get() = inc ?: emptySet()

    fun matches(host: String, entity: String?): Boolean {
        if (host.isEmpty()) return inc == null
        var i = 0
        while (true) {
            val s = if (i == 0) host else host.substring(i)
            if (exc != null && exc.contains(s)) return false
            if (inc != null && inc.contains(s)) return true
            val d = host.indexOf('.', i)
            if (d < 0) break
            i = d + 1
        }
        if (entity != null) {
            i = 0
            while (true) {
                val s = entity.substring(i) + ".*"
                if (exc != null && exc.contains(s)) return false
                if (inc != null && inc.contains(s)) return true
                val d = entity.indexOf('.', i)
                if (d < 0) break
                i = d + 1
            }
        }
        return inc == null
    }

    companion object {
        /** Parses "a.com|~b.com" (sep '|') or "a.com,~b.com" (sep ','). Null when unusable. */
        fun parse(list: String, sep: Char): DomainSet? {
            var inc: HashSet<String>? = null
            var exc: HashSet<String>? = null
            for (raw in list.split(sep)) {
                var d = raw.trim().lowercase()
                if (d.isEmpty()) continue
                val neg = d.startsWith("~")
                if (neg) d = d.substring(1)
                if (d.startsWith("/") || d.isEmpty()) return null // regex domains unsupported
                if (neg) { if (exc == null) exc = HashSet(); exc.add(d) }
                else { if (inc == null) inc = HashSet(); inc.add(d) }
            }
            if (inc == null && exc == null) return null
            return DomainSet(inc, exc)
        }
    }
}

class NetFilter(
    /** Glob (with `*` and `^`), anchors removed. For host-indexed filters: the part after the host. */
    val pattern: String,
    private val regexSrc: String?,
    val flags: Int,
    val types: Int,
    val domains: DomainSet?,
    private val to: DomainSet?,
    private val denyallow: DomainSet?,
    private val methods: String?,
    val redirect: String?,
    /** Original rule text (only kept when debugging, to save memory). */
    val text: String?
) {
    @Volatile private var regex: Pattern? = null
    @Volatile private var regexBad = false

    fun has(f: Int) = flags and f != 0

    fun matches(r: AdRequest): Boolean {
        if (types and r.typeMask == 0) return false
        if (has(F_3P) && !r.thirdParty) return false
        if (has(F_1P) && r.thirdParty) return false
        if (methods != null && !methodOk(r.method)) return false
        if (domains != null && !domains.matches(r.sourceHost, r.sourceEntity)) return false
        if (to != null && !to.matches(r.host, r.hostEntity)) return false
        if (denyallow != null && denyallow.hasInc && denyallow.matches(r.host, r.hostEntity)) return false
        return matchUrl(r)
    }

    /** URL-only test (no type/party checks), used for page-level exceptions. */
    fun matchesPage(r: AdRequest): Boolean {
        if (has(F_3P) && !r.thirdParty) return false
        if (has(F_1P) && r.thirdParty) return false
        if (domains != null && !domains.matches(r.sourceHost, r.sourceEntity)) return false
        return matchUrl(r)
    }

    private fun methodOk(m: String): Boolean {
        var anyPos = false
        for (part in methods!!.split('|')) {
            if (part.startsWith("~")) { if (part.substring(1).equals(m, true)) return false }
            else { anyPos = true; if (part.equals(m, true)) return true }
        }
        return !anyPos
    }

    private fun matchUrl(r: AdRequest): Boolean {
        val mc = has(F_MATCH_CASE)
        val u = if (mc) r.urlOrig else r.url
        val ae = has(F_ANCHOR_END)
        if (regexSrc != null) {
            if (regexBad) return false
            val re = regex ?: try {
                Pattern.compile(regexSrc, if (mc) 0 else Pattern.CASE_INSENSITIVE).also { regex = it }
            } catch (e: Exception) { regexBad = true; return false }
            return re.matcher(r.urlOrig).find()
        }
        if (has(F_HOST_INDEXED)) return globAt(u, r.hostEnd, pattern, ae)
        if (has(F_ANCHOR_HOST)) {
            if (r.hostStart < 0) return false
            var pos = r.hostStart
            while (pos < r.hostEnd) {
                if (globAt(u, pos, pattern, ae)) return true
                val d = u.indexOf('.', pos)
                if (d < 0 || d >= r.hostEnd) break
                pos = d + 1
            }
            return false
        }
        if (has(F_ANCHOR_START)) return globAt(u, 0, pattern, ae)
        if (pattern.isEmpty()) return true
        if (pattern[0] == '*') return globAt(u, 0, pattern, ae)
        var lit = 0
        while (lit < pattern.length && pattern[lit] != '*' && pattern[lit] != '^') lit++
        if (lit == pattern.length) return if (ae) u.endsWith(pattern) else u.contains(pattern)
        if (lit == 0) {
            for (i in 0..u.length) if (globAt(u, i, pattern, ae)) return true
            return false
        }
        val seg = pattern.substring(0, lit)
        var idx = u.indexOf(seg)
        while (idx >= 0) {
            if (globAt(u, idx, pattern, ae)) return true
            idx = u.indexOf(seg, idx + 1)
        }
        return false
    }

    companion object {
        const val F_EXCEPTION = 1
        const val F_IMPORTANT = 2
        const val F_ANCHOR_HOST = 4
        const val F_ANCHOR_START = 8
        const val F_ANCHOR_END = 16
        const val F_MATCH_CASE = 32
        const val F_3P = 64
        const val F_1P = 128
        const val F_HOST_INDEXED = 256
        const val F_GHIDE = 512
        const val F_EHIDE = 1024
        const val F_SHIDE = 2048
        const val F_DOC = 4096

        /**
         * Glob match of [p] against [t] starting exactly at [start]. `*` = any run, `^` = a
         * separator character or the end of the URL. Without [anchorEnd] the tail is free.
         */
        fun globAt(t: String, start: Int, p: String, anchorEnd: Boolean): Boolean {
            val n = t.length
            val m = p.length
            var ti = start
            var pi = 0
            var starP = -1
            var starT = 0
            while (true) {
                if (pi == m) {
                    if (!anchorEnd || ti == n) return true
                } else {
                    val pc = p[pi]
                    if (pc == '*') { starP = pi; starT = ti; pi++; continue }
                    if (ti < n) {
                        val tc = t[ti]
                        if (if (pc == '^') isSeparator(tc) else pc == tc) { ti++; pi++; continue }
                    } else if (pc == '^') { pi++; continue }
                }
                if (starP < 0) return false
                pi = starP + 1
                starT++
                ti = starT
                if (ti > n) return false
            }
        }
    }
}

/** Index so that each request only looks at a handful of candidate filters. */
class NetIndex {
    internal val byHost = HashMap<String, ArrayList<NetFilter>>()
    internal val byToken = HashMap<String, ArrayList<NetFilter>>()
    internal val bySource = HashMap<String, ArrayList<NetFilter>>()
    internal val generic = ArrayList<NetFilter>()
    var size = 0
        internal set

    fun find(r: AdRequest): NetFilter? {
        val h = r.host
        if (h.isNotEmpty() && byHost.isNotEmpty()) {
            var i = 0
            while (true) {
                byHost[if (i == 0) h else h.substring(i)]?.let { l -> for (f in l) if (f.matches(r)) return f }
                val d = h.indexOf('.', i)
                if (d < 0) break
                i = d + 1
            }
        }
        if (byToken.isNotEmpty()) for (t in r.tokens) {
            byToken[t]?.let { l -> for (f in l) if (f.matches(r)) return f }
        }
        val s = r.sourceHost
        if (s.isNotEmpty() && bySource.isNotEmpty()) {
            var i = 0
            while (true) {
                bySource[if (i == 0) s else s.substring(i)]?.let { l -> for (f in l) if (f.matches(r)) return f }
                val d = s.indexOf('.', i)
                if (d < 0) break
                i = d + 1
            }
            r.sourceEntity?.let { e ->
                i = 0
                while (true) {
                    bySource[e.substring(i) + ".*"]?.let { l -> for (f in l) if (f.matches(r)) return f }
                    val d = e.indexOf('.', i)
                    if (d < 0) break
                    i = d + 1
                }
            }
        }
        for (f in generic) if (f.matches(r)) return f
        return null
    }

    /** Page-level exceptions: returns OR of the flags of every matching filter. */
    fun pageFlags(r: AdRequest): Int {
        var out = 0
        fun scan(l: List<NetFilter>?) { if (l != null) for (f in l) if (f.matchesPage(r)) out = out or f.flags }
        val h = r.host
        if (h.isNotEmpty()) {
            var i = 0
            while (true) {
                scan(byHost[h.substring(i)])
                val d = h.indexOf('.', i)
                if (d < 0) break
                i = d + 1
            }
        }
        for (t in r.tokens) scan(byToken[t])
        val s = r.sourceHost
        if (s.isNotEmpty()) {
            var i = 0
            while (true) {
                scan(bySource[s.substring(i)])
                val d = s.indexOf('.', i)
                if (d < 0) break
                i = d + 1
            }
        }
        scan(generic)
        return out
    }
}

class CosmeticResult(
    val css: String,
    val procedural: List<String>,
    val scriptlets: List<List<String>>,
    val generic: Boolean
) {
    /** JSON consumed by assets/adblock.js. */
    fun toJson(): String {
        val sb = StringBuilder(css.length + 256)
        sb.append("{\"css\":"); jsonStr(sb, css)
        sb.append(",\"generic\":").append(generic)
        sb.append(",\"proc\":[")
        procedural.forEachIndexed { i, p -> if (i > 0) sb.append(','); jsonStr(sb, p) }
        sb.append("],\"sl\":[")
        scriptlets.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append('[')
            l.forEachIndexed { j, a -> if (j > 0) sb.append(','); jsonStr(sb, a) }
            sb.append(']')
        }
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        fun jsonStr(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\u2028' -> sb.append("\\u2028")
                    '\u2029' -> sb.append("\\u2029")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }
    }
}

class FilterEngine private constructor(val psl: PublicSuffix) {
    private val blocks = NetIndex()
    private val exceptions = NetIndex()
    private val important = NetIndex()
    private val docBlocks = NetIndex()
    private val pageExc = NetIndex()
    private val popups = NetIndex()
    private val popupExc = NetIndex()

    // Cosmetic
    private val genericByClass = HashMap<String, ArrayList<String>>()
    private val genericById = HashMap<String, ArrayList<String>>()
    private val genericUnkeyed = ArrayList<String>()
    private var genericUnkeyedCss = ""
    private val genericExc = HashSet<String>()
    private val specific = HashMap<String, ArrayList<String>>()     // domain -> selectors/procedural
    private val specificExc = HashMap<String, HashSet<String>>()   // domain -> unhidden selectors
    private val scriptlets = HashMap<String, ArrayList<String>>()  // domain -> "+js(...)" bodies
    private val scriptletExc = HashMap<String, HashSet<String>>()  // domain -> bodies ("" = all)
    private val genericScriptlets = ArrayList<String>()

    fun stats(): String = listOf("blocks" to blocks, "exc" to exceptions, "imp" to important, "doc" to docBlocks, "page" to pageExc)
        .joinToString("; ") { (n, i) -> "$n host=${i.byHost.size} tok=${i.byToken.size} src=${i.bySource.size} generic=${i.generic.size} total=${i.size}" } +
        "; maxTokBucket=" + blocks.byToken.maxOfOrNull { it.value.size } + " " + blocks.byToken.maxByOrNull { it.value.size }?.key +
        "; genericCss=" + genericUnkeyed.size + " classKeys=" + genericByClass.size + " idKeys=" + genericById.size

    var networkCount = 0; private set
    var cosmeticCount = 0; private set

    fun request(url: String, sourceUrl: String?, type: Int, method: String = "GET") =
        AdRequest(url, type, method, sourceUrl, psl)

    /** The filter that blocks this request, or null when it is allowed. */
    fun match(r: AdRequest): NetFilter? {
        if (r.host.isEmpty()) return null
        important.find(r)?.let { return it }
        val b = blocks.find(r) ?: return null
        if (exceptions.find(r) != null) return null
        return b
    }

    /** For a top-level navigation: a `$document` rule that marks the whole page as dangerous. */
    fun matchDocument(url: String): NetFilter? {
        val r = request(url, url, RT.DOCUMENT)
        if (r.host.isEmpty()) return null
        val b = docBlocks.find(r) ?: return null
        if (pageExc.pageFlags(r) and NetFilter.F_DOC != 0) return null
        if (exceptions.find(r) != null) return null
        return b
    }

    /** A popup / new window opened by [sourceUrl] that a `$popup` rule blocks. */
    fun matchPopup(url: String, sourceUrl: String?): NetFilter? {
        val r = request(url, sourceUrl, RT.POPUP)
        if (r.host.isEmpty()) return null
        val b = popups.find(r) ?: return null
        if (popupExc.find(r) != null) return null
        return b
    }

    /** Flags (F_DOC / F_GHIDE / F_EHIDE / F_SHIDE) granted to a page by `@@` rules. */
    fun pageFlags(pageUrl: String): Int {
        val r = request(pageUrl, pageUrl, RT.DOCUMENT)
        if (r.host.isEmpty()) return 0
        return pageExc.pageFlags(r)
    }

    private fun hostKeys(host: String, out: MutableList<String>) {
        if (host.isEmpty()) return
        var i = 0
        while (true) {
            out.add(host.substring(i))
            val d = host.indexOf('.', i)
            if (d < 0) break
            i = d + 1
        }
        val ent = psl.withoutSuffix(host) ?: return
        i = 0
        while (true) {
            out.add(ent.substring(i) + ".*")
            val d = ent.indexOf('.', i)
            if (d < 0) break
            i = d + 1
        }
    }

    fun cosmetic(pageUrl: String, pageFlags: Int): CosmeticResult {
        val lower = pageUrl.lowercase()
        val hr = AdRequest.hostRange(lower)
        val host = if (hr.first in 0 until hr.second) lower.substring(hr.first, hr.second) else ""
        val keys = ArrayList<String>(12)
        hostKeys(host, keys)

        val exc = HashSet<String>()
        val slExc = HashSet<String>()
        for (k in keys) {
            specificExc[k]?.let { exc.addAll(it) }
            scriptletExc[k]?.let { slExc.addAll(it) }
        }

        val ehide = pageFlags and NetFilter.F_EHIDE != 0
        val generic = !ehide && pageFlags and NetFilter.F_GHIDE == 0
        val specificOn = !ehide && pageFlags and NetFilter.F_SHIDE == 0

        val css = StringBuilder()
        val proc = ArrayList<String>()
        if (specificOn) {
            val seen = HashSet<String>()
            for (k in keys) specific[k]?.forEach { s ->
                if (exc.contains(s) || !seen.add(s)) return@forEach
                when {
                    isProcedural(s) -> {
                        val styled = styleRule(s)
                        if (styled != null) css.append(styled).append('\n') else proc.add(s)
                    }
                    else -> css.append(s).append("{display:none!important}\n")
                }
            }
        }
        if (generic) {
            if (exc.isEmpty() || genericUnkeyed.none { exc.contains(it) }) css.append(genericUnkeyedCss)
            else for (s in genericUnkeyed) if (!exc.contains(s)) css.append(s).append("{display:none!important}\n")
        }

        val sl = ArrayList<List<String>>()
        if (!slExc.contains("")) {
            val seen = HashSet<String>()
            val add = { body: String ->
                if (!slExc.contains(body) && seen.add(body)) parseScriptlet(body)?.let { sl.add(it) }
            }
            for (k in keys) scriptlets[k]?.forEach(add)
            genericScriptlets.forEach(add)
        }
        return CosmeticResult(css.toString(), proc, sl, generic)
    }

    /** CSS for generic rules keyed on classes/ids that exist on the page. */
    fun genericFor(pageUrl: String, classes: Collection<String>, ids: Collection<String>): String {
        val lower = pageUrl.lowercase()
        val hr = AdRequest.hostRange(lower)
        val host = if (hr.first in 0 until hr.second) lower.substring(hr.first, hr.second) else ""
        val keys = ArrayList<String>(12)
        hostKeys(host, keys)
        var exc: HashSet<String>? = null
        for (k in keys) specificExc[k]?.let { if (exc == null) exc = HashSet(); exc!!.addAll(it) }
        val sb = StringBuilder()
        fun emit(l: List<String>?) {
            if (l != null) for (s in l) if (exc?.contains(s) != true) sb.append(s).append("{display:none!important}\n")
        }
        for (c in classes) emit(genericByClass[c])
        for (i in ids) emit(genericById[i])
        return sb.toString()
    }

    // ------------------------------------------------------------------ building

    class Builder(private val psl: PublicSuffix, private val keepText: Boolean = false) {
        private val bad = HashSet<String>()
        private val pending = ArrayList<Pair<NetFilter, List<String>>>(90000)
        private val tokenCount = HashMap<String, Int>(200000)
        private val engine = FilterEngine(psl)

        /** First pass: collect `$badfilter` rules so they can cancel rules from any list. */
        fun scanBadfilters(reader: BufferedReader) {
            reader.forEachLine { l ->
                if (l.contains("badfilter")) {
                    val t = l.trim()
                    val d = t.lastIndexOf('$')
                    if (d > 0) {
                        val opts = t.substring(d + 1).split(',').filter { it != "badfilter" }
                        if (opts.size != t.substring(d + 1).split(',').size) {
                            bad.add(if (opts.isEmpty()) t.substring(0, d) else t.substring(0, d) + "$" + opts.joinToString(","))
                        }
                    }
                }
            }
        }

        fun add(reader: BufferedReader) {
            reader.forEachLine { addLine(it) }
        }

        fun addLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty()) return
            val c0 = line[0]
            if (c0 == '!' || c0 == '[') return
            if (c0 == '#') {
                if (line.length > 1 && (line[1] == '#' || line[1] == '@' || line[1] == '?')) { cosmetic(line); return }
                return // hosts-file comment
            }
            // hosts file format
            if (line.startsWith("127.0.0.1 ") || line.startsWith("0.0.0.0 ")) {
                val d = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase()
                if (d.isNotEmpty() && d != "localhost" && d != "0.0.0.0" && d.contains('.')) net("||$d^")
                return
            }
            val h = line.indexOf('#')
            if (h >= 0 && line.length > h + 1) {
                val n = line[h + 1]
                if (n == '#' || n == '@' || n == '?' || n == '$' || n == '%') {
                    val dom = line.substring(0, h)
                    if (dom.none { it == '/' || it == '|' || it == '^' || it == '$' || it == ' ' }) {
                        cosmetic(line); return
                    }
                }
            }
            net(line)
        }

        private fun net(line: String) {
            if (bad.contains(line)) return
            var s = line
            var flags = 0
            if (s.startsWith("@@")) { flags = flags or NetFilter.F_EXCEPTION; s = s.substring(2) }

            // Split options from pattern
            var opts: String? = null
            val dollar = s.lastIndexOf('$')
            if (dollar >= 0) {
                val isRegex = s.startsWith("/") && dollar > 0 && s.lastIndexOf('/') > dollar
                if (!isRegex) { opts = s.substring(dollar + 1); s = s.substring(0, dollar) }
            }

            var types = 0
            var neg = 0
            var domains: DomainSet? = null
            var to: DomainSet? = null
            var deny: DomainSet? = null
            var methods: String? = null
            var redirect: String? = null
            if (opts != null) {
                for (o0 in splitOptions(opts)) {
                    var o = o0.trim()
                    if (o.isEmpty()) continue
                    val not = o.startsWith("~")
                    if (not) o = o.substring(1)
                    val eq = o.indexOf('=')
                    val name = (if (eq >= 0) o.substring(0, eq) else o).lowercase()
                    val value = if (eq >= 0) o.substring(eq + 1) else ""
                    val t = typeBit(name)
                    if (t != 0) { if (not) neg = neg or t else types = types or t; continue }
                    when (name) {
                        "third-party", "3p" -> flags = flags or if (not) NetFilter.F_1P else NetFilter.F_3P
                        "first-party", "1p" -> flags = flags or if (not) NetFilter.F_3P else NetFilter.F_1P
                        "strict3p" -> flags = flags or NetFilter.F_3P
                        "strict1p" -> flags = flags or NetFilter.F_1P
                        "domain", "from" -> domains = DomainSet.parse(value, '|') ?: return
                        "to" -> to = DomainSet.parse(value, '|') ?: return
                        "denyallow" -> deny = DomainSet.parse(value, '|') ?: return
                        "method" -> methods = value.lowercase()
                        "important" -> flags = flags or NetFilter.F_IMPORTANT
                        "match-case" -> flags = flags or NetFilter.F_MATCH_CASE
                        "all" -> types = types or RT.ALL_RES or RT.DOCUMENT or RT.POPUP
                        "redirect", "rewrite" -> redirect = value.removePrefix("abp-resource:").substringBefore(':')
                        "empty" -> redirect = "empty"
                        "mp4" -> redirect = "noopmp4-1s"
                        "generichide", "ghide" -> flags = flags or NetFilter.F_GHIDE
                        "elemhide", "ehide" -> flags = flags or NetFilter.F_EHIDE
                        "specifichide", "shide" -> flags = flags or NetFilter.F_SHIDE
                        "cname", "reason", "_", "__", "stealth" -> {}
                        "badfilter" -> return
                        // Rules we can't honor inside WebView (response rewriting, headers...) are dropped.
                        else -> return
                    }
                }
            }
            if (types and RT.DOCUMENT != 0 && flags and NetFilter.F_EXCEPTION != 0) flags = flags or NetFilter.F_DOC
            val anyType = types != 0
            if (!anyType) types = RT.ALL_RES
            types = types and neg.inv()
            if (types and (RT.ALL_RES or RT.DOCUMENT or RT.POPUP) == 0) return

            // Pattern
            var regexSrc: String? = null
            var pat = s
            if (pat.length > 2 && pat.startsWith("/") && pat.endsWith("/")) {
                regexSrc = pat.substring(1, pat.length - 1)
                pat = ""
            } else {
                if (pat.startsWith("||")) { flags = flags or NetFilter.F_ANCHOR_HOST; pat = pat.substring(2) }
                else if (pat.startsWith("|")) { flags = flags or NetFilter.F_ANCHOR_START; pat = pat.substring(1) }
                if (pat.endsWith("|")) { flags = flags or NetFilter.F_ANCHOR_END; pat = pat.substring(0, pat.length - 1) }
                if (flags and NetFilter.F_MATCH_CASE == 0) pat = pat.lowercase()
                while (pat.contains("**")) pat = pat.replace("**", "*")
                if (flags and NetFilter.F_ANCHOR_HOST != 0 && pat.startsWith("*")) {
                    flags = flags and NetFilter.F_ANCHOR_HOST.inv()
                }
                if (flags and (NetFilter.F_ANCHOR_HOST or NetFilter.F_ANCHOR_START) == 0) pat = pat.trimStart('*')
                if (pat.endsWith("*")) { pat = pat.trimEnd('*'); flags = flags and NetFilter.F_ANCHOR_END.inv() }
                if (pat.isEmpty() && flags and NetFilter.F_ANCHOR_HOST != 0) return
                // Generic catch-alls without any constraint would block the web: skip.
                if (pat.isEmpty() && domains == null && to == null && flags and NetFilter.F_EXCEPTION == 0 &&
                    flags and NetFilter.F_ANCHOR_START == 0) return
            }

            // Host-indexable `||host.name^...`
            var hostKey: String? = null
            var tokens: List<String> = emptyList()
            if (regexSrc == null && flags and NetFilter.F_ANCHOR_HOST != 0) {
                var i = 0
                while (i < pat.length && (isTokenChar(pat[i]) || pat[i] == '.' || pat[i] == '-' || pat[i] == '_')) i++
                val next = if (i < pat.length) pat[i] else '\u0000'
                if (i > 0 && pat[i - 1] != '.' && (next == '^' || next == '/' || next == ':' ||
                        (i == pat.length && flags and NetFilter.F_ANCHOR_END != 0))) {
                    hostKey = pat.substring(0, i)
                    pat = pat.substring(i)
                    flags = (flags or NetFilter.F_HOST_INDEXED) and NetFilter.F_ANCHOR_HOST.inv()
                }
            }
            if (hostKey == null && regexSrc == null) tokens = patternTokens(pat, flags)

            val f = NetFilter(pat, regexSrc, flags, types, domains, to, deny, methods, redirect, if (keepText) line else null)
            if (hostKey != null) {
                pending.add(Pair(f, listOf("\u0001" + hostKey)))
            } else {
                for (t in tokens) tokenCount[t] = (tokenCount[t] ?: 0) + 1
                pending.add(Pair(f, tokens))
            }
        }

        private fun splitOptions(s: String): List<String> {
            if (!s.contains("\\,")) return s.split(',')
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length && s[i + 1] == ',') { cur.append(','); i += 2; continue }
                if (c == ',') { out.add(cur.toString()); cur.setLength(0) } else cur.append(c)
                i++
            }
            out.add(cur.toString())
            return out
        }

        private fun typeBit(n: String) = when (n) {
            "script" -> RT.SCRIPT
            "image" -> RT.IMAGE
            "stylesheet", "css" -> RT.STYLESHEET
            "object", "object-subrequest" -> RT.OBJECT
            "xmlhttprequest", "xhr" -> RT.XHR
            "subdocument", "frame" -> RT.SUBDOCUMENT
            "ping", "beacon" -> RT.PING
            "media" -> RT.MEDIA
            "font" -> RT.FONT
            "websocket" -> RT.WEBSOCKET
            "other" -> RT.OTHER
            "document", "doc" -> RT.DOCUMENT
            "popup", "popunder" -> RT.POPUP
            else -> 0
        }

        private fun patternTokens(p: String, flags: Int): List<String> {
            val out = ArrayList<String>(4)
            var i = 0
            while (i < p.length) {
                if (!isTokenChar(p[i])) { i++; continue }
                val s = i
                while (i < p.length && isTokenChar(p[i])) i++
                val e = i
                if (e - s < 2) continue
                val okStart = if (s == 0) flags and (NetFilter.F_ANCHOR_START or NetFilter.F_ANCHOR_HOST) != 0 else p[s - 1] != '*'
                val okEnd = if (e == p.length) flags and NetFilter.F_ANCHOR_END != 0 else p[e] != '*'
                if (okStart && okEnd) out.add(p.substring(s, e))
            }
            return out
        }

        // ---------------- cosmetic

        private fun cosmetic(line: String) {
            var h = -1
            var marker = ""
            var i = line.indexOf('#')
            while (i >= 0) {
                for (m in MARKERS) if (line.startsWith(m, i)) { marker = m; break }
                if (marker.isNotEmpty()) { h = i; break }
                i = line.indexOf('#', i + 1)
            }
            if (h < 0) return
            if (marker.contains('$') || marker.contains('%')) return // AdGuard-only syntax
            val exception = marker.contains('@')
            val dom = line.substring(0, h).let { if (it == "*") "" else it }
            var body = line.substring(h + marker.length).trim()
            if (body.isEmpty() || body.startsWith("^")) return // HTML filtering: not possible in WebView

            val isScriptlet = body.startsWith("+js(") && body.endsWith(")")
            if (!isScriptlet) {
                body = normalizeProcedural(body) ?: return
            }
            val ds = if (dom.isEmpty()) null else DomainSet.parse(dom, ',') ?: return
            engine.cosmeticCount++

            if (isScriptlet) {
                val inner = body.substring(4, body.length - 1).trim()
                if (exception) {
                    if (ds == null) return
                    for (d in ds.incList) engine.scriptletExc.getOrPut(d) { HashSet() }.add(normSig(inner))
                    return
                }
                if (ds == null || !ds.hasInc) { engine.genericScriptlets.add(normSig(inner)) }
                else for (d in ds.incList) engine.scriptlets.getOrPut(d) { ArrayList(2) }.add(normSig(inner))
                ds?.let { excludedOf(dom).forEach { d -> engine.scriptletExc.getOrPut(d) { HashSet() }.add(normSig(inner)) } }
                return
            }

            if (exception) {
                if (ds == null) engine.genericExc.add(body)
                else for (d in ds.incList) engine.specificExc.getOrPut(d) { HashSet() }.add(body)
                return
            }
            val excluded = excludedOf(dom)
            if (ds == null || !ds.hasInc) {
                if (isProcedural(body)) return // uBO doesn't allow generic procedural filters either
                addGeneric(body)
                for (d in excluded) engine.specificExc.getOrPut(d) { HashSet() }.add(body)
                return
            }
            for (d in ds.incList) engine.specific.getOrPut(d) { ArrayList(2) }.add(body)
            for (d in excluded) engine.specificExc.getOrPut(d) { HashSet() }.add(body)
        }

        private fun excludedOf(dom: String): List<String> =
            if (!dom.contains('~')) emptyList()
            else dom.split(',').map { it.trim().lowercase() }.filter { it.startsWith("~") }.map { it.substring(1) }

        private fun normSig(s: String): String = s

        private fun addGeneric(sel: String) {
            val key = genericKey(sel)
            when {
                key == null -> engine.genericUnkeyed.add(sel)
                key[0] == '.' -> engine.genericByClass.getOrPut(key.substring(1)) { ArrayList(1) }.add(sel)
                else -> engine.genericById.getOrPut(key.substring(1)) { ArrayList(1) }.add(sel)
            }
        }

        fun build(): FilterEngine {
            val e = engine
            // Generic cosmetic exceptions (`#@#.ad`) apply everywhere
            if (e.genericExc.isNotEmpty()) {
                e.genericUnkeyed.removeAll(e.genericExc)
                for (m in listOf(e.genericByClass, e.genericById)) {
                    val it = m.entries.iterator()
                    while (it.hasNext()) {
                        val en = it.next()
                        en.value.removeAll(e.genericExc)
                        if (en.value.isEmpty()) it.remove()
                    }
                }
            }
            val uniq = LinkedHashSet(e.genericUnkeyed)
            e.genericUnkeyed.clear(); e.genericUnkeyed.addAll(uniq)
            for (m in listOf(e.genericByClass, e.genericById)) for (en in m.entries) {
                if (en.value.size > 1) { val u = LinkedHashSet(en.value); en.value.clear(); en.value.addAll(u) }
            }
            for (en in e.specific.entries) {
                if (en.value.size > 1) { val u = LinkedHashSet(en.value); en.value.clear(); en.value.addAll(u) }
            }
            val css = StringBuilder(e.genericUnkeyed.size * 40)
            for (s in e.genericUnkeyed) css.append(s).append("{display:none!important}\n")
            e.genericUnkeyedCss = css.toString()

            for ((f, toks) in pending) {
                val exc = f.has(NetFilter.F_EXCEPTION)
                val pageOnly = f.flags and (NetFilter.F_GHIDE or NetFilter.F_EHIDE or NetFilter.F_SHIDE) != 0
                val targets = ArrayList<NetIndex>(2)
                if (exc) {
                    if (pageOnly || f.has(NetFilter.F_DOC)) targets.add(e.pageExc)
                    if (!pageOnly && f.types and RT.ALL_RES != 0) targets.add(e.exceptions)
                    if (f.types and RT.POPUP != 0) targets.add(e.popupExc)
                } else {
                    if (f.types and RT.POPUP != 0) targets.add(e.popups)
                    if (f.types and RT.DOCUMENT != 0) targets.add(e.docBlocks)
                    if (f.types and RT.ALL_RES != 0) targets.add(if (f.has(NetFilter.F_IMPORTANT)) e.important else e.blocks)
                }
                for (idx in targets) index(idx, f, toks)
                if (targets.isNotEmpty()) e.networkCount++
            }
            pending.clear()
            tokenCount.clear()
            return e
        }

        private fun index(idx: NetIndex, f: NetFilter, toks: List<String>) {
            idx.size++
            if (toks.size == 1 && toks[0].startsWith("\u0001")) {
                idx.byHost.getOrPut(toks[0].substring(1)) { ArrayList(1) }.add(f)
                return
            }
            var best: String? = null
            var bestScore = Int.MAX_VALUE
            for (t in toks) {
                val sc = (tokenCount[t] ?: 0) + (if (t in BAD_TOKENS) 1_000_000 else 0) - t.length
                if (sc < bestScore) { bestScore = sc; best = t }
            }
            if (best != null && bestScore < 1_000_000) {
                idx.byToken.getOrPut(best) { ArrayList(1) }.add(f)
                return
            }
            val d = f.domains
            if (d != null && d.hasInc) {
                for (dom in d.incList) idx.bySource.getOrPut(dom) { ArrayList(1) }.add(f)
                return
            }
            if (best != null) { idx.byToken.getOrPut(best) { ArrayList(1) }.add(f); return }
            idx.generic.add(f)
        }
    }

    companion object {
        private val MARKERS = arrayOf("#@?#", "#@$#", "#@%#", "#@#", "#?#", "#$#", "#%#", "##")
        private val BAD_TOKENS = hashSetOf("http", "https", "www", "com", "net", "org", "js", "html", "php")

        private val PROCEDURAL = arrayOf(
            ":has-text(", ":upward(", ":remove(", ":xpath(", ":matches-css", ":min-text-length(",
            ":watch-attr(", ":matches-attr(", ":matches-prop(", ":others(", ":remove-attr(",
            ":remove-class(", ":matches-path(", ":matches-media(", ":style(", ":-abp-"
        )

        fun isProcedural(s: String): Boolean {
            if (s.indexOf(':') < 0) return false
            for (p in PROCEDURAL) if (s.contains(p)) return true
            return false
        }

        /** Maps ABP/AdGuard spellings onto uBO's. Returns null for selectors we can't use. */
        fun normalizeProcedural(s0: String): String? {
            if (s0.indexOf(':') < 0) return s0
            if (s0.contains(":if-not(") || s0.contains(":-abp-properties(")) return null
            return s0.replace(":-abp-contains(", ":has-text(")
                .replace(":-abp-has(", ":has(")
                .replace(":contains(", ":has-text(")
                .replace(":nth-ancestor(", ":upward(")
                .replace(":if(", ":has(")
        }

        /** `sel:style(decl)` -> `sel{decl}` when the rest is plain CSS. */
        fun styleRule(s: String): String? {
            val i = s.lastIndexOf(":style(")
            if (i <= 0 || !s.endsWith(")")) {
                if (s.endsWith(":remove()")) {
                    val base = s.substring(0, s.length - ":remove()".length)
                    if (base.isNotEmpty() && !isProcedural(base)) return "$base{display:none!important}"
                }
                return null
            }
            val base = s.substring(0, i)
            if (base.isEmpty() || isProcedural(base)) return null
            val decl = s.substring(i + 7, s.length - 1)
            if (decl.contains('{') || decl.contains('}') || decl.contains("url(")) return null
            return "$base{$decl}"
        }

        /** ".class" / "#id" key of the first compound selector, or null. */
        fun genericKey(sel: String): String? {
            var depthB = 0
            var depthP = 0
            var i = 0
            while (i < sel.length) {
                val c = sel[i]
                when {
                    c == '\\' -> return null
                    c == '[' -> depthB++
                    c == ']' -> depthB--
                    c == '(' -> depthP++
                    c == ')' -> depthP--
                    depthB == 0 && depthP == 0 && (c == ' ' || c == '>' || c == '+' || c == '~' || c == ',') -> return null
                    depthB == 0 && depthP == 0 && (c == '.' || c == '#') -> {
                        var j = i + 1
                        while (j < sel.length && (sel[j].isLetterOrDigit() || sel[j] == '-' || sel[j] == '_')) j++
                        if (j == i + 1) return null
                        if (sel.indexOf(',') >= 0) return null
                        return c + sel.substring(i + 1, j)
                    }
                }
                i++
            }
            return null
        }

        /** "name, arg1, arg\, with comma, 'quoted'" -> [name, arg1, ...] */
        fun parseScriptlet(body: String): List<String>? {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var i = 0
            var atStart = true
            while (i < body.length) {
                val c = body[i]
                if (atStart && (c == ' ' || c == '\t')) { i++; continue }
                if (atStart && (c == '\'' || c == '"' || c == '`')) {
                    // Quoted argument: ends at the matching unescaped quote followed by ',' or end
                    var j = i + 1
                    val sb = StringBuilder()
                    var closed = false
                    while (j < body.length) {
                        val d = body[j]
                        if (d == '\\' && j + 1 < body.length && body[j + 1] == c) { sb.append(c); j += 2; continue }
                        if (d == c) {
                            var k = j + 1
                            while (k < body.length && body[k] == ' ') k++
                            if (k == body.length || body[k] == ',') { closed = true; j = k; break }
                        }
                        sb.append(d); j++
                    }
                    if (closed) {
                        out.add(sb.toString()); cur.setLength(0)
                        i = j + 1; atStart = true
                        continue
                    }
                }
                atStart = false
                if (c == '\\' && i + 1 < body.length && body[i + 1] == ',') { cur.append(','); i += 2; continue }
                if (c == ',') {
                    out.add(cur.toString().trim()); cur.setLength(0); atStart = true; i++; continue
                }
                cur.append(c); i++
            }
            if (!atStart || cur.isNotEmpty()) out.add(cur.toString().trim())
            else if (body.trimEnd().endsWith(",")) out.add("")
            if (out.isEmpty() || out[0].isEmpty()) return null
            var name = out[0]
            if (name.endsWith(".js")) name = name.substring(0, name.length - 3)
            out[0] = name
            return out
        }

        /** Builds an engine from filter list readers. [lists] is called twice (badfilter pre-pass). */
        fun build(psl: PublicSuffix, keepText: Boolean = false, lists: () -> List<BufferedReader>): FilterEngine {
            val b = Builder(psl, keepText)
            for (r in lists()) r.use { b.scanBadfilters(it) }
            for (r in lists()) r.use { b.add(it) }
            return b.build()
        }
    }
}
