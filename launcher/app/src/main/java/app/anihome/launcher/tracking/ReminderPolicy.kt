package app.anihome.launcher.tracking

/**
 * Home-screen reminder rules.
 *
 * The reminder is "you were on episode N of this anime". Dismissing hides it until the user
 * watches a *different* episode of that anime (earlier, next, or future) on any website.
 * Watching the same episode again, even on another site, leaves it dismissed. History is
 * independent of dismiss.
 */
object ReminderPolicy {
    fun shouldShow(lastEpisode: Int, dismissed: Boolean): Boolean = lastEpisode > 0 && !dismissed

    /** After a new watch, stay dismissed only when the episode number did not change. */
    fun stillDismissed(wasDismissed: Boolean, previousEpisode: Int, watchedEpisode: Int): Boolean {
        if (!wasDismissed) return false
        if (previousEpisode <= 0) return false
        return previousEpisode == watchedEpisode
    }
}

/**
 * Full watch log: one row per sitting. Same anime + episode + site within [SAME_SITTING_MS]
 * updates the existing row instead of flooding the log from the in-page probe.
 */
object WatchLogPolicy {
    const val SAME_SITTING_MS = 6L * 60L * 60L * 1000L

    data class LastEntry(val episode: Int, val host: String, val watchedAt: Long)

    fun shouldAppend(last: LastEntry?, episode: Int, host: String, now: Long): Boolean {
        if (last == null) return true
        if (last.episode != episode || !last.host.equals(host, ignoreCase = true)) return true
        return now - last.watchedAt >= SAME_SITTING_MS
    }
}
