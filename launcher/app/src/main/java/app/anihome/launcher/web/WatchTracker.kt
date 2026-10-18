package app.anihome.launcher.web

import android.content.Context
import app.anihome.launcher.tracking.AnimeDetection
import app.anihome.launcher.tracking.AnimeDetector
import app.anihome.launcher.tracking.CrossSiteNotice
import app.anihome.launcher.tracking.PageSnapshot
import app.anihome.launcher.tracking.WatchRepository

/**
 * Per-WebActivity glue between page snapshots and the local repository.
 * Debounces identical detections and makes sure each cross-site notice is shown once per
 * anime per website visit.
 */
class WatchTracker(context: Context) {
    private val repo = WatchRepository(context)
    private var last: AnimeDetection? = null
    private var lastWriteAt = 0L
    private val noticed = HashSet<String>()

    data class Outcome(val detection: AnimeDetection?, val notice: CrossSiteNotice?)

    /** Must be called off the main thread. */
    fun onSnapshot(snapshot: PageSnapshot): Outcome {
        val detection = AnimeDetector.detect(snapshot) ?: return Outcome(null, null)
        val now = System.currentTimeMillis()
        val same = last?.let { it.key == detection.key && it.episode == detection.episode && it.host == detection.host } == true
        // Write on every new episode immediately; otherwise throttle playback-position updates.
        if (same && now - lastWriteAt < WRITE_INTERVAL_MS) return Outcome(detection, null)
        val notice = repo.record(detection, snapshot)
        last = detection
        lastWriteAt = now
        val noticeKey = detection.key + "@" + detection.host
        return if (notice != null && noticed.add(noticeKey)) Outcome(detection, notice) else Outcome(detection, null)
    }

    fun mute(detection: AnimeDetection) = repo.setMuted(detection.key, true)

    private companion object { const val WRITE_INTERVAL_MS = 20_000L }
}
