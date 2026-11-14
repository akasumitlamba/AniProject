package app.anihome.launcher.banners

import android.content.Context
import app.anihome.launcher.data.InstalledApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps store banners ready for installed apps, so adding one to Home shows its banner at once
 * instead of fetching it then. Only banners matching an installed app are downloaded; a
 * banner is deleted again when its app is uninstalled. Nothing about installed apps is sent:
 * the catalog is fetched whole and matched on the device.
 */
object BannerPrefetch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A newly installed app: fetch its banner now. */
    fun installed(context: Context, packageName: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val repository = BannerRepository(app)
                val entry = AutomaticBannerPolicy.match(repository.catalog(), packageName) ?: return@launch
                repository.image(entry, allowNetwork = true)
            }
        }
    }

    /** An uninstalled app: drop its cached banner unless another installed app uses it. */
    fun removed(context: Context, packageName: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val catalog = BannerRepository(app).catalog()
                val entry = AutomaticBannerPolicy.match(catalog, packageName) ?: return@launch
                val stillUsed = InstalledApps.list(app).any { it.packageName != packageName && AutomaticBannerPolicy.match(catalog, it.packageName) == entry }
                if (!stillUsed) File(File(app.filesDir, "banner-store"), "${entry.sha256}.${entry.file.substringAfterLast('.')}").delete()
            }
        }
    }

    /** At most daily: banners for every installed app that the store has one for. */
    fun sweep(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("banner_prefetch", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("swept_at", 0) < 24 * 60 * 60 * 1000L) return
        scope.launch {
            runCatching {
                val repository = BannerRepository(app)
                val catalog = repository.catalog()
                InstalledApps.list(app).mapNotNull { AutomaticBannerPolicy.match(catalog, it.packageName) }.distinct().forEach { entry ->
                    runCatching { repository.image(entry, allowNetwork = true) }
                }
                prefs.edit().putLong("swept_at", now).apply()
            }
        }
    }
}
