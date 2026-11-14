package app.anihome.launcher.banners

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BannerRepository(private val context: Context) {
    private val base = BannerSource.configured(context)
    // Downloaded artwork is user content, not disposable Android cache.
    private val cache = File(context.filesDir, "banner-store").apply { mkdirs() }
    private val catalogFile = File(cache, "catalog-${BannerRules.hash(base.toByteArray()).take(16)}.json")
    init {
        val legacy = File(context.cacheDir, "banner-store")
        legacy.listFiles()?.filter { it.isFile && it.length() <= IMAGE_LIMIT && (it.name.matches(Regex("[a-f0-9]{64}\\.(webp|png)")) || it.name == "catalog.json" && base == BannerSource.DEFAULT_BASE) }?.forEach { file ->
            val destination = if (file.name == "catalog.json") catalogFile else File(cache, file.name)
            if (!destination.exists()) runCatching { write(destination, file.readBytes()) }
        }
    }
    private val policyPreferences = context.getSharedPreferences("banner-network-policy-${BannerRules.hash(base.toByteArray()).take(16)}", Context.MODE_PRIVATE)
    /** Files that just failed, with when; retried automatically after [FAILURE_MEMORY_MS]. */
    private val failedImages = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun pause() = BannerFetchPolicy.Pause(policyPreferences.getLong("until", 0), policyPreferences.getInt("status", 0))
    @Synchronized fun cooldownReason(): String? = pause().let {
        if (BannerFetchPolicy.permits(it, System.currentTimeMillis())) null else BannerFetchPolicy.reason(it.status)
    }
    /** Only explicit Retry or an access configuration change calls this. */
    @Synchronized fun clearCooldown() {
        policyPreferences.edit().remove("until").remove("status").apply()
        failedImages.clear()
    }
    private fun migratePublicSource() {
        if (policyPreferences.getString("source", null) == base) return
        LegacyBannerAccess.clear(context)
        policyPreferences.edit().clear().putString("source", base).apply()
        failedImages.clear()
    }
    @Synchronized fun catalog(refresh: Boolean = false): List<BannerEntry> {
        migratePublicSource()
        if (refresh) {
            failedImages.clear()
            val bytes = request("catalog.json", 1024 * 1024)
            val parsed = parse(bytes)
            write(catalogFile, bytes)
            return parsed
        }
        runCatching { parse(catalogFile.readBytes()) }.getOrNull()?.let { return it }
        return parse(context.resources.openRawResource(app.anihome.launcher.R.raw.banner_catalog).use { it.readBytes() })
    }
    /**
     * The banner's file: cached, bundled, or downloaded. Downloads run outside the repository
     * lock (at most [PARALLEL] at once, one per file), so a long store list loads quickly and a
     * slow banner never holds the others back.
     */
    fun image(entry: BannerEntry, allowNetwork: Boolean = false): File {
        require(BannerRules.safeFile(entry.file) && entry.sha256.matches(Regex("[a-f0-9]{64}")))
        val target = File(cache, "${entry.sha256}.${entry.file.substringAfterLast('.')}")
        synchronized(fileLock(target.name)) {
            if (target.exists() && target.length() <= IMAGE_LIMIT && BannerRules.hash(target.readBytes()) == entry.sha256) return target
            val bundled = runCatching { context.assets.open(entry.file).use { it.readBytes() } }.getOrNull()
            val bytes = if (bundled != null && bundled.size <= IMAGE_LIMIT && BannerRules.hash(bundled) == entry.sha256) bundled
                else if (allowNetwork) {
                    failedImages[entry.file]?.let { at ->
                        if (System.currentTimeMillis() - at < FAILURE_MEMORY_MS) throw BannerApiException(null, friendlyReason = "This banner is unavailable right now. Try again in a moment.")
                        failedImages.remove(entry.file)
                    }
                    try { download(entry) }
                    catch (error: Exception) { failedImages[entry.file] = System.currentTimeMillis(); throw error }
                } else error("Banner not cached")
            write(target, bytes)
            return target
        }
    }

    /** Downloads and verifies one banner, retrying once past a stale CDN copy. */
    private fun download(entry: BannerEntry): ByteArray {
        permits.acquire()
        try {
            // The content hash in the URL keys caches by version, so an updated banner never
            // arrives as the previous image (which would fail verification).
            var bytes = request(entry.file, IMAGE_LIMIT, "v=${entry.sha256.take(16)}")
            if (BannerRules.hash(bytes) != entry.sha256) bytes = request(entry.file, IMAGE_LIMIT, "v=${entry.sha256.take(16)}&t=${System.currentTimeMillis()}")
            if (BannerRules.hash(bytes) != entry.sha256)
                throw BannerApiException(null, friendlyReason = "This banner is being updated in the store. Try again in a few minutes.")
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 320..4096 || bounds.outHeight !in 1..4096 ||
                kotlin.math.abs(bounds.outWidth.toDouble() / bounds.outHeight - 1.62) >= 0.035)
                throw BannerApiException(null, friendlyReason = "The downloaded banner is invalid. Bundled banners remain available.")
            return bytes
        } finally { permits.release() }
    }
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun fileLock(name: String) = fileLocks.getOrPut(name) { Any() }

    private fun request(path: String, limit: Int, query: String? = null): ByteArray {
        require(path == "catalog.json" || BannerRules.safeFile(path))
        require(query == null || query.matches(Regex("v=[a-f0-9]{1,64}(&t=[0-9]{1,20})?")))
        val url = URL(BannerSource.url(path, base) + (query?.let { "?$it" } ?: ""))
        require(url.protocol == "https" && url.host == URL(base).host && url.path.startsWith(URL(base).path))
        val currentPause = pause()
        if (!BannerFetchPolicy.permits(currentPause, System.currentTimeMillis())) throw BannerApiException(currentPause.status, paused = true)
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json, image/webp")
        connection.setRequestProperty("User-Agent", "AniHome-BannerStore")
        try {
            val status = connection.responseCode
            if (status != 200) {
                val paused = BannerFetchPolicy.afterHttpFailure(status, System.currentTimeMillis())
                if (paused.untilMillis != 0L) policyPreferences.edit().putLong("until", paused.untilMillis).putInt("status", paused.status).apply()
                throw BannerApiException(status, paused = paused.untilMillis != 0L)
            }
            require(connection.contentLengthLong <= limit || connection.contentLengthLong < 0)
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + 30_000_000_000L
                var count = input.read(buffer)
                while (count != -1) {
                    check(System.nanoTime() <= deadline) { "Banner request timed out" }
                    require(output.size() + count <= limit) { "Banner download too large" }
                    output.write(buffer, 0, count); count = input.read(buffer)
                }
                output.toByteArray()
            }
        } catch (error: Exception) {
            if (error is BannerApiException) throw error
            throw BannerApiException(null, paused = false, friendlyReason = "The store couldn't provide this banner. Check your connection, then tap Retry.")
        } finally { connection.disconnect() }
    }
    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun parse(bytes: ByteArray): List<BannerEntry> {
        require(bytes.size <= 1024 * 1024)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schema") == 1)
        val entries = root.getJSONArray("entries"); require(entries.length() in 1..5000)
        val ids = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        val packages = mutableSetOf<String>()
        fun strings(row: JSONObject, key: String): List<String> {
            val array = row.optJSONArray(key) ?: return emptyList()
            require(array.length() <= 100)
            return List(array.length()) { array.getString(it).also { value -> require(value.length <= 256) } }
        }
        // A single malformed or duplicate row is skipped; it must not hide the whole catalog.
        val parsed = (0 until entries.length()).mapNotNull { i -> runCatching {
            val row = entries.getJSONObject(i)
            val id = row.getString("id"); val name = row.getString("name"); val file = row.getString("file"); val hash = row.getString("sha256")
            require(id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && ids.add(id))
            require(name.isNotBlank() && name.length <= 120 && BannerRules.safeFile(file) && files.add(file))
            require(hash.matches(Regex("[a-f0-9]{64}")))
            val appPackages = strings(row, "packages")
            val hosts = strings(row, "webHosts")
            require(appPackages.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) && packages.add(it) })
            require(hosts.all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")) })
            val category = row.optString("category", "other")
            val priority = row.optInt("tabletPriority", 0)
            require(category.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && category.length <= 40 && priority in 0..100)
            BannerEntry(id, name, file, appPackages, strings(row, "aliases"), hosts, hash, category, priority)
        }.getOrNull() }
        require(parsed.isNotEmpty())
        return parsed
    }
    private companion object {
        const val IMAGE_LIMIT = 4 * 1024 * 1024
        const val FAILURE_MEMORY_MS = 60_000L
        const val PARALLEL = 4
        /** Shared by every repository instance: the store, automatic banners and prefetch. */
        val permits = java.util.concurrent.Semaphore(PARALLEL)
    }
}
