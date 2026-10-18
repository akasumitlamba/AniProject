package app.anihome.launcher.tracking

/** Progress stored for one anime on one website. */
data class SiteProgress(
    val host: String,
    val maxEpisode: Int,
    val lastEpisode: Int,
    val lastUrl: String?,
    val positionSec: Int,
    val durationSec: Int,
    val updatedAt: Long,
)

/** Shown when the anime being opened was already watched further (or equally far) on another website. */
data class CrossSiteNotice(
    val title: String,
    val otherHost: String,
    val otherMaxEpisode: Int,
    val currentEpisode: Int,
) {
    enum class Relation { AHEAD_ELSEWHERE, SAME_EPISODE, BEHIND_ELSEWHERE }

    val relation: Relation
        get() = when {
            otherMaxEpisode > currentEpisode -> Relation.AHEAD_ELSEWHERE
            otherMaxEpisode == currentEpisode -> Relation.SAME_EPISODE
            else -> Relation.BEHIND_ELSEWHERE
        }
}

/** Pure decision logic for the pop-up, kept separate from storage so it can be unit-tested. */
object CrossSitePolicy {
    /**
     * @param others progress on hosts other than [detection].host, newest first.
     * @param thisHostMax how far the user already got on the current host (0 when unknown).
     */
    fun decide(detection: AnimeDetection, others: List<SiteProgress>, thisHostMax: Int, muted: Boolean): CrossSiteNotice? {
        if (muted || others.isEmpty()) return null
        val best = others.maxByOrNull { it.maxEpisode } ?: return null
        // Only interrupt when the other website knows more than this one does.
        if (best.maxEpisode <= thisHostMax) return null
        return CrossSiteNotice(
            title = detection.title,
            otherHost = best.host,
            otherMaxEpisode = best.maxEpisode,
            currentEpisode = detection.episode,
        )
    }
}
