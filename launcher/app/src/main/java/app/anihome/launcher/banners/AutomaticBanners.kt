package app.anihome.launcher.banners

import android.content.Context
import app.anihome.launcher.data.TileEvents
import app.anihome.launcher.data.TileStore
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One automatic selection per new app tile. No installed-app list is sent to the catalog. */
object AutomaticBanners {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    fun enqueue(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            var drained = false
            try {
                val store = TileStore(app)
                val repository = BannerRepository(app)
                val prefs = app.getSharedPreferences("automatic-banner-catalog-${BannerRules.hash(BannerSource.configured(app).toByteArray()).take(16)}", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val refreshDue = now - prefs.getLong("refreshed_at", 0) >= 6 * 60 * 60 * 1000L
                val catalog = if (refreshDue) runCatching {
                    repository.catalog(refresh = true).also { prefs.edit().putLong("refreshed_at", now).apply() }
                }.getOrElse { repository.catalog() } else repository.catalog()
                // New additions arriving during a batch are processed without another catalog fetch.
                while (true) {
                    val pending = store.all().filter { it.artworkOrigin == "pending" }
                    if (pending.isEmpty()) { drained = true; break }
                    val missing = mutableListOf<String>(); val failed = mutableListOf<String>()
                    for (tile in pending) {
                        var output: File? = null
                        var attached = false
                        try {
                            val match = AutomaticBannerPolicy.match(catalog, tile) { pkg -> isBrowser(app, pkg) }
                            if (match != null) {
                                val source = repository.image(match, allowNetwork = true)
                                val directory = File(app.filesDir, "tile-artwork").apply { mkdirs() }
                                output = File(directory, "auto-${tile.id}-${UUID.randomUUID()}.${match.file.substringAfterLast('.')}")
                                source.copyTo(output)
                            }
                            attached = store.finishAutomaticArtwork(tile.id, output?.absolutePath)
                            if (attached) {
                                TileEvents.notifyChanged()
                                if (match == null) missing += tile.label // Not in the store: keeps its icon.
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) {
                            // Offline, missing, or invalid images leave the normal application icon.
                            if (store.finishAutomaticArtwork(tile.id, null)) { TileEvents.notifyChanged(); failed += tile.label }
                        } finally { if (!attached) output?.delete() }
                    }
                    announce(app, missing, failed)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Persisted pending rows can resume after the next Home refresh. */ }
            finally {
                running.set(false)
                if (drained && runCatching { TileStore(app).all().any { it.artworkOrigin == "pending" } }.getOrDefault(false)) enqueue(app)
            }
        }
    }

    /** One short message per batch: which tiles keep their icon because there's no banner. */
    private fun announce(context: Context, missing: List<String>, failed: List<String>) {
        fun names(list: List<String>) = when (list.size) {
            1 -> list[0]
            2 -> "${list[0]} and ${list[1]}"
            else -> "${list[0]}, ${list[1]} and ${list.size - 2} more"
        }
        val text = when {
            missing.isNotEmpty() -> "The banner store has no banner for ${names(missing)}, so ${if (missing.size == 1) "its" else "their"} icon is shown"
            failed.isNotEmpty() -> "Couldn't get a banner for ${names(failed)} right now, so ${if (failed.size == 1) "its" else "their"} icon is shown"
            else -> return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Browsers publish shortcuts for many websites; their own banner must not label them all. */
    private fun isBrowser(context: Context, packageName: String): Boolean = runCatching {
        val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        context.packageManager.queryIntentActivities(web, 0).any { it.activityInfo.packageName == packageName }
    }.getOrDefault(false)
}

internal object AutomaticBannerPolicy {
    /** Labels are not identities: unrelated apps often share names such as Calculator. */
    fun match(catalog: List<BannerEntry>, packageName: String?): BannerEntry? =
        packageName?.takeIf { it.isNotBlank() }?.let { pkg -> catalog.firstOrNull { pkg in it.packages } }

    /**
     * Apps match by package only. Websites match by host, then by the site's name. Shortcuts
     * match the publishing app's banner, unless that app is a browser (a website shortcut),
     * in which case only the shortcut's name can identify the site.
     */
    fun match(catalog: List<BannerEntry>, tile: app.anihome.launcher.data.HomeTile, isBrowser: (String) -> Boolean): BannerEntry? {
        fun byName() = catalog.firstOrNull { BannerRules.matchPriority(it, null, tile.label, null) == 1 }
        // AniHome's own Settings app shares the launcher package; it has its own banner.
        if (tile.activityName?.endsWith(".HomeSettingsActivity") == true) return catalog.firstOrNull { it.id == "anihome-settings" }
        return when {
            tile.isWeb -> catalog.firstOrNull { BannerRules.matchPriority(it, null, "", tile.url) == 2 } ?: byName()
            tile.isShortcut -> tile.packageName?.takeIf { !isBrowser(it) }?.let { match(catalog, it) } ?: byName()
            else -> match(catalog, tile.packageName)
        }
    }
}
