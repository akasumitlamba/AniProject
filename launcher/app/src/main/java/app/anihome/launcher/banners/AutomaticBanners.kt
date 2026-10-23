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
                val prefs = app.getSharedPreferences("automatic-banner-catalog", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val refreshDue = now - prefs.getLong("refreshed_at", 0) >= 6 * 60 * 60 * 1000L
                val catalog = if (refreshDue) runCatching {
                    repository.catalog(refresh = true).also { prefs.edit().putLong("refreshed_at", now).apply() }
                }.getOrElse { repository.catalog() } else repository.catalog()
                // New additions arriving during a batch are processed without another catalog fetch.
                while (true) {
                    val pending = store.all().filter { it.isApp && it.artworkOrigin == "pending" }
                    if (pending.isEmpty()) { drained = true; break }
                    for (tile in pending) {
                        var output: File? = null
                        var attached = false
                        try {
                            val match = AutomaticBannerPolicy.match(catalog, tile.packageName)
                            if (match != null) {
                                val source = repository.image(match, allowNetwork = true)
                                val directory = File(app.filesDir, "tile-artwork").apply { mkdirs() }
                                output = File(directory, "auto-${tile.id}-${UUID.randomUUID()}.${match.file.substringAfterLast('.')}")
                                source.copyTo(output)
                            }
                            attached = store.finishAutomaticArtwork(tile.id, output?.absolutePath)
                            if (attached) TileEvents.notifyChanged()
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) {
                            // Offline, missing, or invalid images leave the normal application icon.
                            if (store.finishAutomaticArtwork(tile.id, null)) TileEvents.notifyChanged()
                        } finally { if (!attached) output?.delete() }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Persisted pending rows can resume after the next Home refresh. */ }
            finally {
                running.set(false)
                if (drained && runCatching { TileStore(app).all().any { it.isApp && it.artworkOrigin == "pending" } }.getOrDefault(false)) enqueue(app)
            }
        }
    }
}

internal object AutomaticBannerPolicy {
    /** Labels are not identities: unrelated apps often share names such as Calculator. */
    fun match(catalog: List<BannerEntry>, packageName: String?): BannerEntry? =
        packageName?.takeIf { it.isNotBlank() }?.let { pkg -> catalog.firstOrNull { pkg in it.packages } }
}
