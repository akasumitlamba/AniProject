package app.anihome.launcher

import android.content.Context
import android.net.Uri
import app.anihome.launcher.data.SiteIcons
import app.anihome.launcher.data.TileEvents
import app.anihome.launcher.data.TileStore
import java.util.Locale

/** First-run (and one-time upgrade) defaults: the three default supported services. */
object Seed {
    private const val PREF = "anihome"
    private const val KEY_VERSION = "seed_version"
    private const val VERSION = 3

    /** New installs start with the apps every Android device has, plus AniBrave (offered for install). */
    private data class App(val label: String, val packageName: String, val fallbackUrl: String?)
    private val defaults = listOf(
        App("AniBrave", "app.anibrave.main", null),
        App("YouTube", "com.google.android.youtube", "https://www.youtube.com"),
        App("Google", "com.google.android.googlequicksearchbox", "https://www.google.com"),
    )

    suspend fun ensure(context: Context, store: TileStore) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return
        // A completed older seed is user-owned: missing defaults may have been removed deliberately.
        if (!SeedPolicy.installDefaults(prefs.getInt(KEY_VERSION, 0), prefs.getBoolean("seeded", false))) {
            prefs.edit().putInt(KEY_VERSION, VERSION).remove("seeded").apply()
            return
        }
        val pm = context.packageManager
        defaults.forEach { app ->
            val launch = pm.getLaunchIntentForPackage(app.packageName)?.component
            when {
                launch != null -> if (!store.hasApp(app.packageName, launch.className)) store.addApp(app.label, app.packageName, launch.className, null)
                app.fallbackUrl == null -> if (!store.hasApp(app.packageName, null)) store.addApp(app.label, app.packageName, "", null)
                else -> {
                    val host = hostOf(app.fallbackUrl) ?: return@forEach
                    if (store.hasHost(host)) return@forEach
                    val tile = store.addWeb(app.label, app.fallbackUrl, null)
                    SiteIcons.fetch(context, tile.id, app.fallbackUrl)?.let { store.setIcon(tile.id, it, null) }
                }
            }
        }
        prefs.edit().putInt(KEY_VERSION, VERSION).remove("seeded").apply()
        TileEvents.notifyChanged()
    }

    private fun hostOf(url: String): String? =
        Uri.parse(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
}
