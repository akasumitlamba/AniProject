package app.anihome.launcher.tracking

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.anihome.launcher.data.LauncherDb

/** Local-only persistence for anime progress and the per-anime watch log. */
class WatchRepository(context: Context) {
    private val db: SQLiteDatabase get() = LauncherDb.get(app).writableDatabase
    private val app = context.applicationContext

    data class AnimeSummary(
        val key: String,
        val title: String,
        val muted: Boolean,
        val reminderDismissed: Boolean,
        val maxEpisode: Int,
        val episodesWatched: Int,
        val lastHost: String?,
        val lastUrl: String?,
        val lastEpisode: Int,
        val updatedAt: Long,
        val sites: List<SiteProgress>,
    )

    data class WatchLogEntry(
        val id: Long,
        val episode: Int,
        val host: String,
        val url: String?,
        val watchedAt: Long,
    )

    /** Records the snapshot and returns a notice when another website already knows more. */
    fun record(detection: AnimeDetection, snapshot: PageSnapshot): CrossSiteNotice? {
        val now = System.currentTimeMillis()
        val database = db
        val others = sitesFor(detection.key).filter { it.host != detection.host }
        val thisHostMax = sitesFor(detection.key).firstOrNull { it.host == detection.host }?.maxEpisode ?: 0
        val muted = isMuted(detection.key)
        val notice = CrossSitePolicy.decide(detection, others, thisHostMax, muted)

        database.beginTransaction()
        try {
            val existing = database.query(
                "anime",
                arrayOf("title", "reminder_dismissed", "last_episode"),
                "key=?",
                arrayOf(detection.key),
                null, null, null,
            ).use {
                if (it.moveToFirst()) Triple(it.getString(0), it.getInt(1) == 1, it.getInt(2)) else null
            }
            val title = if (existing != null && existing.first.length >= detection.title.length) existing.first else detection.title
            val dismissed = ReminderPolicy.stillDismissed(existing?.second == true, existing?.third ?: 0, detection.episode)
            val animeValues = ContentValues().apply {
                put("title", title)
                put("updated_at", now)
                put("reminder_dismissed", if (dismissed) 1 else 0)
                put("last_episode", detection.episode)
                put("last_host", detection.host)
                put("last_url", snapshot.url)
            }
            if (existing == null) {
                database.insert("anime", null, animeValues.apply {
                    put("key", detection.key); put("muted", 0)
                })
            } else {
                database.update("anime", animeValues, "key=?", arrayOf(detection.key))
            }

            val siteValues = ContentValues().apply {
                put("last_episode", detection.episode)
                put("last_url", snapshot.url)
                put("position_sec", snapshot.videoPositionSec)
                put("duration_sec", snapshot.videoDurationSec)
                put("updated_at", now)
                put("max_episode", maxOf(thisHostMax, detection.episode))
            }
            val siteArgs = arrayOf(detection.key, detection.host)
            if (database.update("site_progress", siteValues, "anime_key=? AND host=?", siteArgs) == 0) {
                database.insert("site_progress", null, siteValues.apply { put("anime_key", detection.key); put("host", detection.host) })
            }

            val lastLog = database.rawQuery(
                "SELECT episode, host, watched_at FROM episode_log WHERE anime_key=? ORDER BY watched_at DESC LIMIT 1",
                arrayOf(detection.key),
            ).use { c ->
                if (c.moveToFirst()) WatchLogPolicy.LastEntry(c.getInt(0), c.getString(1), c.getLong(2)) else null
            }
            if (WatchLogPolicy.shouldAppend(lastLog, detection.episode, detection.host, now)) {
                database.insert("episode_log", null, ContentValues().apply {
                    put("anime_key", detection.key)
                    put("episode", detection.episode)
                    put("host", detection.host)
                    put("url", snapshot.url)
                    put("watched_at", now)
                })
            } else {
                database.execSQL(
                    "UPDATE episode_log SET watched_at=?, url=?, host=? WHERE id=(SELECT id FROM episode_log WHERE anime_key=? ORDER BY watched_at DESC LIMIT 1)",
                    arrayOf(now, snapshot.url, detection.host, detection.key),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return notice
    }

    fun sitesFor(key: String): List<SiteProgress> = db.query(
        "site_progress", null, "anime_key=?", arrayOf(key), null, null, "updated_at DESC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                SiteProgress(
                    host = c.getString(c.getColumnIndexOrThrow("host")),
                    maxEpisode = c.getInt(c.getColumnIndexOrThrow("max_episode")),
                    lastEpisode = c.getInt(c.getColumnIndexOrThrow("last_episode")),
                    lastUrl = c.getString(c.getColumnIndexOrThrow("last_url")),
                    positionSec = c.getInt(c.getColumnIndexOrThrow("position_sec")),
                    durationSec = c.getInt(c.getColumnIndexOrThrow("duration_sec")),
                    updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
                ),
            )
        }
    }

    fun history(key: String): List<WatchLogEntry> = db.query(
        "episode_log", null, "anime_key=?", arrayOf(key), null, null, "watched_at DESC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                WatchLogEntry(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    episode = c.getInt(c.getColumnIndexOrThrow("episode")),
                    host = c.getString(c.getColumnIndexOrThrow("host")),
                    url = c.getString(c.getColumnIndexOrThrow("url")),
                    watchedAt = c.getLong(c.getColumnIndexOrThrow("watched_at")),
                ),
            )
        }
    }

    fun isMuted(key: String): Boolean = db.query("anime", arrayOf("muted"), "key=?", arrayOf(key), null, null, null).use {
        it.moveToFirst() && it.getInt(0) == 1
    }

    fun setMuted(key: String, muted: Boolean) {
        db.update("anime", ContentValues().apply { put("muted", if (muted) 1 else 0) }, "key=?", arrayOf(key))
    }

    fun dismissReminder(key: String) {
        db.update("anime", ContentValues().apply { put("reminder_dismissed", 1) }, "key=?", arrayOf(key))
    }

    fun forget(key: String) {
        val database = db
        database.beginTransaction()
        try {
            database.delete("episode_log", "anime_key=?", arrayOf(key))
            database.delete("site_progress", "anime_key=?", arrayOf(key))
            database.delete("anime", "key=?", arrayOf(key))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun clearAll() {
        val database = db
        database.beginTransaction()
        try {
            database.delete("episode_log", null, null)
            database.delete("site_progress", null, null)
            database.delete("anime", null, null)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun summaries(limit: Int = 200): List<AnimeSummary> {
        val animes = db.query("anime", null, null, null, null, null, "updated_at DESC", limit.toString()).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    AnimeRow(
                        key = c.getString(c.getColumnIndexOrThrow("key")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        muted = c.getInt(c.getColumnIndexOrThrow("muted")) == 1,
                        reminderDismissed = columnOrZero(c, "reminder_dismissed") == 1,
                        lastEpisode = columnOrZero(c, "last_episode"),
                        lastHost = columnOrNull(c, "last_host"),
                        lastUrl = columnOrNull(c, "last_url"),
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
                    ),
                )
            }
        }
        return animes.map { row ->
            val sites = sitesFor(row.key)
            val latest = sites.firstOrNull()
            val watched = db.rawQuery("SELECT COUNT(*) FROM episode_log WHERE anime_key=?", arrayOf(row.key)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            AnimeSummary(
                key = row.key,
                title = row.title,
                muted = row.muted,
                reminderDismissed = row.reminderDismissed,
                maxEpisode = sites.maxOfOrNull { it.maxEpisode } ?: 0,
                episodesWatched = watched,
                lastHost = row.lastHost ?: latest?.host,
                lastUrl = row.lastUrl ?: latest?.lastUrl,
                lastEpisode = if (row.lastEpisode > 0) row.lastEpisode else latest?.lastEpisode ?: 0,
                updatedAt = row.updatedAt,
                sites = sites,
            )
        }
    }

    fun reminders(): List<AnimeSummary> = summaries(limit = 24).filter {
        ReminderPolicy.shouldShow(it.lastEpisode, it.reminderDismissed)
    }

    private data class AnimeRow(
        val key: String,
        val title: String,
        val muted: Boolean,
        val reminderDismissed: Boolean,
        val lastEpisode: Int,
        val lastHost: String?,
        val lastUrl: String?,
        val updatedAt: Long,
    )

    private fun columnOrZero(c: android.database.Cursor, name: String): Int {
        val i = c.getColumnIndex(name)
        return if (i < 0 || c.isNull(i)) 0 else c.getInt(i)
    }

    private fun columnOrNull(c: android.database.Cursor, name: String): String? {
        val i = c.getColumnIndex(name)
        return if (i < 0 || c.isNull(i)) null else c.getString(i)
    }
}
