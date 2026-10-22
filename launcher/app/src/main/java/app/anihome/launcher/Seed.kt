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
    private const val VERSION = 2

    private val defaults = listOf(
        "https://www.crunchyroll.com" to "Crunchyroll",
        "https://hianime.to" to "HiAnime",
        "https://anikototv.to/" to "AniKoto",
    )

    suspend fun ensure(context: Context, store: TileStore) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return
        // A completed older seed is user-owned: missing defaults may have been removed deliberately.
        if (!SeedPolicy.installDefaults(prefs.getInt(KEY_VERSION, 0), prefs.getBoolean("seeded", false))) {
            prefs.edit().putInt(KEY_VERSION, VERSION).remove("seeded").apply()
            return
        }
        defaults.forEach { (url, label) ->
            val host = hostOf(url) ?: return@forEach
            if (store.hasHost(host)) return@forEach
            val tile = store.addWeb(label, url, null)
            val path = SiteIcons.fetch(context, tile.id, url)
            if (path != null) store.setIcon(tile.id, path, null)
        }
        prefs.edit().putInt(KEY_VERSION, VERSION).remove("seeded").apply()
        TileEvents.notifyChanged()
    }

    private fun hostOf(url: String): String? =
        Uri.parse(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
}
