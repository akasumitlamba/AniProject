package app.anihome.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Website artwork. Bundled logos are used when we have them; otherwise the icon is fetched
 * from the website itself (its own HTML `<link rel=icon>` tags or `/favicon.ico`). No
 * third-party icon service is contacted.
 */
object SiteIcons {
    private const val MAX_EDGE = 384
    private const val MAX_BYTES = 1024 * 1024

    private val bundled = mapOf(
        "youtube.com" to "youtube.png", "youtu.be" to "youtube.png",
        "crunchyroll.com" to "crunchyroll.png",
        "anikototv.to" to "anikoto.png", "anikoto.cz" to "anikoto.png", "anikoto.to" to "anikoto.png",
        "netflix.com" to "netflix.png",
        "primevideo.com" to "primevideo.png",
        "twitch.tv" to "twitch.png",
        "spotify.com" to "spotify.png",
        "reddit.com" to "reddit.png",
        "github.com" to "github.png",
        "wikipedia.org" to "wikipedia.png",
        "tv.apple.com" to "appletv.png",
    )

    fun dir(context: Context): File = File(context.filesDir, "icons").apply { mkdirs() }

    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains(' ')) return null
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
        val uri = Uri.parse(withScheme)
        val host = uri.host ?: return null
        if (!host.contains('.') && host != "localhost") return null
        return withScheme
    }

    fun smartTitle(url: String): String {
        val host = (Uri.parse(url).host ?: url).lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        known.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.let { return it.value }
        val part = host.split('.').firstOrNull { it.isNotBlank() } ?: host
        return part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private val known = mapOf(
        "youtube.com" to "YouTube", "crunchyroll.com" to "Crunchyroll", "netflix.com" to "Netflix",
        "primevideo.com" to "Prime Video", "tv.apple.com" to "Apple TV", "twitch.tv" to "Twitch",
        "github.com" to "GitHub", "reddit.com" to "Reddit", "disneyplus.com" to "Disney+", "hulu.com" to "Hulu",
        "hianime.to" to "HiAnime", "hianime.tv" to "HiAnime", "hianimez.to" to "HiAnime",
        "anikototv.to" to "AniKoto", "anikoto.cz" to "AniKoto",
        "anilist.co" to "AniList", "myanimelist.net" to "MyAnimeList",
        "wikipedia.org" to "Wikipedia", "x.com" to "X", "spotify.com" to "Spotify", "animepahe.ru" to "AnimePahe",
    )

    /** Returns the saved PNG path for [url] or null when nothing usable could be found. */
    suspend fun fetch(context: Context, tileId: Long, url: String): String? = withContext(Dispatchers.IO) {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return@withContext null
        val target = File(dir(context), "web-$tileId.png")
        bundled.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.let { entry ->
            runCatching {
                context.assets.open("defaults/" + entry.value).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            }.onSuccess { IconCache.invalidateFile(target.absolutePath); return@withContext target.absolutePath }
        }
        val candidates = discoverIconUrls(url) + listOf("https://$host/apple-touch-icon.png", "https://$host/favicon.ico")
        for (candidate in candidates.distinct()) {
            val bitmap = download(candidate) ?: continue
            val saved = save(bitmap, target)
            bitmap.recycle()
            if (saved) { IconCache.invalidateFile(target.absolutePath); return@withContext target.absolutePath }
        }
        null
    }

    private fun save(bitmap: Bitmap, file: File): Boolean = runCatching {
        val largest = maxOf(bitmap.width, bitmap.height)
        val out = if (largest > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / largest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 95, it) }
        if (out !== bitmap) out.recycle()
        true
    }.getOrDefault(false)

    private fun download(candidate: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(candidate).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; if (total > MAX_BYTES) return null
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            if (bytes.size < 120) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < 32) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads the site's own `<link rel="...icon">` tags, biggest declared size first. */
    private fun discoverIconUrls(siteUrl: String): List<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(siteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 4000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val base = connection.url
            val html = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(256 * 1024)
                var total = 0
                while (total < chars.size) { val n = reader.read(chars, total, chars.size - total); if (n < 0) break; total += n }
                String(chars, 0, total)
            }
            Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
                .map { it.value }
                .filter { Regex("""\brel\s*=\s*["'][^"']*icon[^"']*["']""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .mapNotNull { tag ->
                    val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                    val sizes = Regex("""\bsizes\s*=\s*["'](\d+)x""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    val isTouch = tag.contains("apple-touch", true)
                    val rank = (sizes ?: if (isTouch) 180 else 32) + if (href.endsWith(".svg", true)) -1000 else 0
                    runCatching { URL(base, href.replace("&amp;", "&")).toString() }.getOrNull()?.let { it to rank }
                }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(4)
                .toList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
}
