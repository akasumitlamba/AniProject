package app.anihome.launcher.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single on-device SQLite database. There is deliberately no network layer anywhere
 * near this file: tiles and anime progress never leave the phone.
 */
class LauncherDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tiles(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                label TEXT NOT NULL,
                package_name TEXT,
                activity_name TEXT,
                shortcut_id TEXT,
                shortcut_user INTEGER,
                url TEXT,
                icon_path TEXT,
                artwork_path TEXT,
                color INTEGER,
                position INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX tiles_position ON tiles(position)")
        db.execSQL(
            """CREATE TABLE anime(
                key TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                muted INTEGER NOT NULL DEFAULT 0,
                reminder_dismissed INTEGER NOT NULL DEFAULT 0,
                last_episode INTEGER NOT NULL DEFAULT 0,
                last_host TEXT,
                last_url TEXT,
                updated_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE site_progress(
                anime_key TEXT NOT NULL,
                host TEXT NOT NULL,
                max_episode INTEGER NOT NULL,
                last_episode INTEGER NOT NULL,
                last_url TEXT,
                position_sec INTEGER NOT NULL DEFAULT 0,
                duration_sec INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(anime_key, host)
            )""",
        )
        createEpisodeLog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addColumnIfMissing(db, "anime", "reminder_dismissed", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "anime", "last_episode", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "anime", "last_host", "TEXT")
            addColumnIfMissing(db, "anime", "last_url", "TEXT")
            val logHasId = db.rawQuery("PRAGMA table_info(episode_log)", null).use { c ->
                val name = c.getColumnIndex("name")
                var found = false
                while (c.moveToNext()) if (c.getString(name) == "id") found = true
                found
            }
            if (!logHasId) {
                db.execSQL(
                    """CREATE TABLE episode_log_v2(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        anime_key TEXT NOT NULL,
                        episode INTEGER NOT NULL,
                        host TEXT NOT NULL,
                        url TEXT,
                        watched_at INTEGER NOT NULL
                    )""",
                )
                db.execSQL(
                    """INSERT INTO episode_log_v2(anime_key, episode, host, url, watched_at)
                       SELECT anime_key, episode, host, NULL, updated_at FROM episode_log""",
                )
                db.execSQL("DROP TABLE IF EXISTS episode_log")
                db.execSQL("ALTER TABLE episode_log_v2 RENAME TO episode_log")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS episode_log_anime ON episode_log(anime_key, watched_at)")
            db.execSQL(
                """UPDATE anime SET
                     last_episode = COALESCE((SELECT last_episode FROM site_progress WHERE site_progress.anime_key = anime.key ORDER BY updated_at DESC LIMIT 1), 0),
                     last_host = (SELECT host FROM site_progress WHERE site_progress.anime_key = anime.key ORDER BY updated_at DESC LIMIT 1),
                     last_url = (SELECT last_url FROM site_progress WHERE site_progress.anime_key = anime.key ORDER BY updated_at DESC LIMIT 1)
                """,
            )
        }
        if (oldVersion < 4) {
            addColumnIfMissing(db, "tiles", "shortcut_id", "TEXT")
            addColumnIfMissing(db, "tiles", "shortcut_user", "INTEGER")
        }
        if (oldVersion < 3) addColumnIfMissing(db, "tiles", "artwork_path", "TEXT")
    }

    private fun createEpisodeLog(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE episode_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                anime_key TEXT NOT NULL,
                episode INTEGER NOT NULL,
                host TEXT NOT NULL,
                url TEXT,
                watched_at INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX episode_log_anime ON episode_log(anime_key, watched_at)")
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, spec: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndex("name")
            var found = false
            while (c.moveToNext()) if (c.getString(name) == column) found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $spec")
    }

    companion object {
        const val NAME = "anihome.db"
        const val VERSION = 4

        @Volatile private var instance: LauncherDb? = null
        fun get(context: Context): LauncherDb = instance ?: synchronized(this) {
            instance ?: LauncherDb(context).also { instance = it }
        }
    }
}
