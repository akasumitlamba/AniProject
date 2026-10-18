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
