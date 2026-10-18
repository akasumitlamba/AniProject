package app.anihome.launcher.tracking

/**
 * What the WebView tells the tracker about the current page. Everything here is read from
 * the page already open on the device; nothing is fetched to produce it.
 *
 * @param domEpisode episode number read from the page's active episode control, when a site exposes one.
 * @param heading the page's main heading (h1), often cleaner than `document.title`.
 */
data class PageSnapshot(
    val url: String,
    val title: String?,
    val heading: String? = null,
    val domEpisode: Int? = null,
    val domTitle: String? = null,
    val videoPositionSec: Int = 0,
    val videoDurationSec: Int = 0,
    val videoPlaying: Boolean = false,
)
