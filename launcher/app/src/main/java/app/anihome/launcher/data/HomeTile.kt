package app.anihome.launcher.data

/**
 * One entry on the home grid. Native apps and websites are the same kind of thing
 * to the launcher: both have a label, artwork, a brand colour and a position.
 */
data class HomeTile(
    val id: Long,
    val kind: Kind,
    val label: String,
    val packageName: String? = null,
    val activityName: String? = null,
    val url: String? = null,
    val iconPath: String? = null,
    val artworkPath: String? = null,
    val color: Int? = null,
    val position: Int,
) {
    enum class Kind { APP, WEB }

    val isApp get() = kind == Kind.APP
    val isWeb get() = kind == Kind.WEB

    companion object {
        const val NO_ID = -1L
    }
}
