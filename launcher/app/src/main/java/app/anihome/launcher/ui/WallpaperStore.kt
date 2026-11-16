package app.anihome.launcher.ui

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AniHome's wallpaper collection: the image files under `banners/wallpapers/` in AniProject on
 * GitHub, listed anonymously. Downloads are kept on the device; nothing about the device is sent.
 */
internal class WallpaperStore(context: Context) {
    data class Item(val name: String, val file: String, val url: String, val size: Long, val sha: String, val group: String)

    private val folder = File(context.filesDir, "wallpaper-store").apply { mkdirs() }
    private val listFile = File(folder, "tree.json")

    /** The online list (one anonymous request); falls back to the last one saved when offline. */
    fun list(): List<Item> {
        val bytes = runCatching { request(TREE_URL, 4 * 1024 * 1024).also { listFile.writeBytes(it) } }
            .getOrElse { error -> if (listFile.isFile) listFile.readBytes() else throw error }
        val tree = org.json.JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("tree")
        val items = (0 until tree.length()).mapNotNull { i ->
            val row = tree.getJSONObject(i)
            val path = row.optString("path")
            if (row.optString("type") != "blob" || !path.startsWith(PREFIX)) return@mapNotNull null
            val parts = path.removePrefix(PREFIX).split('/')
            val file = parts.last()
            if (parts.any { !it.matches(SAFE) } || !file.matches(IMAGE) || row.optLong("size") > IMAGE_LIMIT) return@mapNotNull null
            val group = if (parts.size > 1) parts.first() else ""
            Item(title(file), file, RAW_BASE + path, row.optLong("size"), row.optString("sha"), group)
        }
        if (items.isEmpty()) throw NoSuchElementException("AniHome's wallpaper collection isn't available yet.")
        // Original review set first, then the rest by folder name.
        return items.sortedWith(compareBy<Item>({ if (it.group == "originals") "" else it.group }, { it.file }))
    }

    /** The image, downloaded once and then reused. */
    fun image(item: Item): File {
        val target = File(folder, "${item.sha.take(12).ifEmpty { "x" }}-${item.group}-${item.file}")
        if (target.isFile && target.length() > 0) return target
        val bytes = request(item.url, IMAGE_LIMIT.toInt())
        val tmp = File(folder, target.name + ".part"); tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { tmp.delete(); error("Couldn't save wallpaper") }
        return target
    }

    private fun request(address: String, limit: Int): ByteArray {
        val url = URL(address)
        require(url.protocol == "https" && (address.startsWith(TREE_URL) || address.startsWith(RAW_BASE + PREFIX)))
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000; connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json, image/*")
        connection.setRequestProperty("User-Agent", "AniHome-Wallpapers")
        try {
            val status = connection.responseCode
            if (status == 404) throw NoSuchElementException("AniHome's wallpaper collection isn't available yet.")
            check(status == 200) { "The wallpaper store answered $status." }
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "Wallpaper too large" }; out.write(buffer, 0, n) }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    companion object {
        private const val TREE_URL = "https://api.github.com/repos/akasumitlamba/AniProject/git/trees/main?recursive=1"
        private const val RAW_BASE = "https://raw.githubusercontent.com/akasumitlamba/AniProject/main/"
        /** Kept with the banner store: banners/wallpapers/<collection>/<image>. */
        private const val PREFIX = "banners/wallpapers/"
        private val SAFE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}")
        private val IMAGE = Regex(".+\\.(webp|jpg|jpeg|png)", RegexOption.IGNORE_CASE)
        private const val IMAGE_LIMIT = 12L * 1024 * 1024
        /** "05-rainy-tokyo.webp" → "Rainy Tokyo". */
        fun title(file: String) = file.substringBeforeLast('.').replace(Regex("^[0-9]+[-_ ]*"), "")
            .split('-', '_', ' ').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.ifEmpty { file }
    }
}
