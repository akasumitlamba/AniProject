package app.anihome.launcher.tracking

/**
 * A recognised "anime X, episode N" on some host.
 *
 * @param key normalised identity used to match the same anime across different websites.
 * @param title human-readable series title as it appeared on the page.
 */
data class AnimeDetection(
    val key: String,
    val title: String,
    val episode: Int,
    val season: Int?,
    val host: String,
    val url: String,
)
