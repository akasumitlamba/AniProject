package app.anihome.launcher.banners

import java.net.URI
import java.util.Locale

/** Catalog contains only public app metadata; device app lists never form network queries. */
data class BannerEntry(val id: String, val name: String, val file: String, val packages: List<String>,
    val aliases: List<String>, val webHosts: List<String>, val sha256: String,
    val category: String = "other", val tabletPriority: Int = 0)

object BannerRules {
    fun ordered(entries: List<BannerEntry>, packageName: String?, label: String, url: String?) = entries.sortedWith(
        compareByDescending<BannerEntry> { matchPriority(it, packageName, label, url) }
            .thenByDescending { it.tabletPriority }.thenBy { it.name })
    fun normalizedName(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
    fun safeFile(value: String) = Regex("images/[a-z0-9]+(?:-[a-z0-9]+)*\\.(?:png|webp)").matches(value)
    fun matchPriority(entry: BannerEntry, packageName: String?, label: String, url: String?): Int {
        if (packageName != null && entry.packages.contains(packageName)) return 3
        val host = runCatching { URI(url ?: "").host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull()
        if (host != null && entry.webHosts.any { it.lowercase(Locale.ROOT).removePrefix("www.") == host }) return 2
        val name = normalizedName(label)
        if (name.isNotEmpty() && (listOf(entry.name) + entry.aliases).any { normalizedName(it) == name }) return 1
        return 0
    }
    fun matches(entry: BannerEntry, packageName: String?, label: String, url: String?) = matchPriority(entry, packageName, label, url) > 0
    fun hash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

/**
 * Identifies which store banner a website (or a website shortcut) belongs to, without any
 * per-site rules. The address and the page title are broken into identity words and compared
 * with each banner's name, aliases, id, websites and app package, most certain first:
 *
 *  1. the address is one of the banner's websites (or a subdomain of one);
 *  2. a whole identity word equals a banner key ("hotstar.com" → "hotstar" = alias "Hotstar");
 *  3. a title segment, or the brand word leading it, equals a banner key ("JioHotstar - Watch TV…");
 *  4. one contains the other, at least 5 letters and covering ≥ 60% of the longer word.
 *
 * A tier that matches two different banners equally well is treated as unknown (the icon stays),
 * so a guess never puts the wrong banner on a tile. The same catalog entry carries an app's
 * package and its websites, so an app and its site always get the same banner.
 */
object SiteMatcher {
    /** Host labels that never identify a site. */
    private val prefixes = setOf("www", "m", "mobile", "web", "app", "play", "watch", "en", "in", "beta", "home")
    private val suffixes = setOf("com", "net", "org", "co", "in", "io", "tv", "app", "me", "gg", "ai", "us", "uk", "au", "ca",
        "de", "jp", "fr", "info", "biz", "live", "xyz", "site", "online", "gov", "edu", "ac", "nic")
    /** Package segments that never identify an app. */
    private val genericSegments = setOf("android", "app", "apps", "application", "client", "mediaclient", "mobile", "main",
        "release", "launcher", "lite", "free", "pro", "plus", "com", "org", "net", "in", "co", "io", "google", "the")

    private fun normalize(value: String) = BannerRules.normalizedName(value)

    /** "www.music.youtube.com" → ["musicyoutube", "youtube", "music"] (whole name first). */
    fun hostWords(host: String): List<String> {
        val labels = host.lowercase(Locale.ROOT).split('.').filter { it.isNotBlank() }.toMutableList()
        while (labels.size > 1 && labels.last() in suffixes) labels.removeAt(labels.lastIndex)
        while (labels.size > 1 && labels.first() in prefixes) labels.removeAt(0)
        val words = labels.map(::normalize).filter { it.isNotEmpty() }
        return (listOf(words.joinToString("")) + words.reversed()).filter { it.isNotEmpty() }.distinct()
    }

    /** Page-title segments, e.g. "JioHotstar - Watch TV Shows" → ["jiohotstar", "watchtvshows"]. */
    fun titleWords(label: String): List<String> =
        label.split(Regex("\\s+[-–—|:·•]\\s+|\\s*[|·•]\\s*")).map(::normalize).filter { it.isNotEmpty() }.distinct()

    private fun packageWord(pkg: String): String? = pkg.lowercase(Locale.ROOT).split('.').reversed()
        .map(::normalize).firstOrNull { it.length >= 4 && it !in genericSegments }

    /** Everything that names this banner. */
    fun keys(entry: BannerEntry): Set<String> = buildSet {
        add(normalize(entry.name)); entry.aliases.forEach { add(normalize(it)) }; add(normalize(entry.id))
        entry.webHosts.forEach { host -> addAll(hostWords(host).take(2)) }
        entry.packages.forEach { pkg -> packageWord(pkg)?.let(::add) }
    }.filter { it.length >= 2 }.toSet()

    private fun host(url: String?) = runCatching { URI(url ?: "").host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull()

    fun match(catalog: List<BannerEntry>, url: String?, label: String): BannerEntry? {
        val host = host(url)
        // 1. Exact website, or a subdomain of it (the longest, most specific website wins).
        if (host != null) {
            catalog.mapNotNull { entry ->
                entry.webHosts.map { it.lowercase(Locale.ROOT).removePrefix("www.") }
                    .filter { site -> host == site || host.endsWith(".$site") }.maxOfOrNull { it.length }?.let { entry to it }
            }.maxByOrNull { it.second }?.let { return it.first }
        }
        val keyed = catalog.map { it to keys(it) }
        fun unique(found: List<BannerEntry>) = found.distinct().singleOrNull()
        val hostWords = host?.let(::hostWords).orEmpty()
        // 2. A word of the address names exactly one banner (whole host name first).
        for (word in hostWords) {
            if (word.length < 3) continue
            val found = keyed.filter { (_, keys) -> word in keys }.map { it.first }
            if (found.isNotEmpty()) return unique(found)
        }
        // 3. A title segment names exactly one banner.
        val titles = titleWords(label)
        for (word in titles) {
            if (word.length < 3) continue
            val found = keyed.filter { (_, keys) -> word in keys }.map { it.first }
            if (found.size == 1) return found[0]
        }
        // 3b. A brand usually leads its segment ("Netflix India - Watch TV" → "netflix").
        for (segment in label.split(Regex("\\s+[-–—|:·•]\\s+|\\s*[|·•]\\s*"))) {
            val first = normalize(segment.trim().split(Regex("\\s+")).firstOrNull().orEmpty())
            if (first.length < 5) continue
            val found = keyed.filter { (_, keys) -> first in keys }.map { it.first }
            if (found.size == 1) return found[0]
        }
        // 4. Containment, strong enough to be unambiguous.
        val words = (hostWords + titles).filter { it.length >= 5 }
        var best: BannerEntry? = null; var bestScore = 0.0; var tie = false
        for ((entry, keys) in keyed) for (key in keys) {
            if (key.length < 5) continue
            for (word in words) {
                if (!key.contains(word) && !word.contains(key)) continue
                val score = minOf(key.length, word.length).toDouble() / maxOf(key.length, word.length)
                if (score < 0.6) continue
                when {
                    score > bestScore -> { best = entry; bestScore = score; tie = false }
                    score == bestScore && entry != best -> tie = true
                }
            }
        }
        return if (tie) null else best
    }
}
