package app.anihome.launcher.banners

import android.content.Context
import java.net.URI

/** Anonymous HTTPS stores use the same validated catalog and relative image paths. */
internal object BannerSource {
    const val REPOSITORY = "akasumitlamba/AniProject"
    const val DEFAULT_BASE = "https://raw.githubusercontent.com/$REPOSITORY/main/banners/"
    fun configured(context: Context): String = runCatching { normalize(context.getSharedPreferences("banner_store", 0).getString("base", null).orEmpty()) }.getOrDefault(DEFAULT_BASE)
    fun select(context: Context, input: String) {
        context.getSharedPreferences("banner_store", 0).edit().putString("base", normalize(input)).apply()
    }
    fun normalize(input: String): String {
        val value = input.trim().ifEmpty { return DEFAULT_BASE }
        val uri = URI(if ("://" in value) value else "https://github.com/$value")
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null)
        if (uri.host !in setOf("github.com", "raw.githubusercontent.com")) {
            val host = uri.host ?: throw IllegalArgumentException("Missing host")
            require(host.contains('.') && !host.matches(Regex("[0-9.]+")) && ':' !in host && !host.endsWith(".localhost") && !host.endsWith(".local"))
            require(uri.path.endsWith("/catalog.json") || uri.path.endsWith('/'))
            val segments = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
            require(segments.all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it !in setOf(".", "..") })
            val folder = if (segments.lastOrNull() == "catalog.json") segments.dropLast(1) else segments
            return "https://$host/" + folder.joinToString("/").let { if (it.isEmpty()) "" else "$it/" }
        }
        val parts = uri.path.trim('/').split('/').toMutableList()
        require(parts.size >= 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it !in setOf(".", "..") })
        val owner = parts[0]; val repo = parts[1].removeSuffix(".git")
        require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) && repo.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")))
        val tail = when (uri.host) {
            "github.com" -> when {
                parts.size == 2 -> listOf("main", "banners")
                parts.size >= 4 && parts[2] == "tree" -> parts.drop(3).let { if (it.size == 1) it + "banners" else it }
                parts.size >= 5 && parts[2] == "blob" && parts.last() == "catalog.json" -> parts.drop(3).dropLast(1)
                else -> throw IllegalArgumentException("Use a GitHub repository or catalog URL")
            }
            else -> parts.drop(2).let { if (it.lastOrNull() == "catalog.json") it.dropLast(1) else it }
        }
        require(tail.isNotEmpty())
        return "https://raw.githubusercontent.com/$owner/$repo/${tail.joinToString("/")}/"
    }
    fun url(path: String, base: String = DEFAULT_BASE): String {
        require(path == "catalog.json" || BannerRules.safeFile(path))
        require(normalize(base) == base)
        return base + path
    }
}
