package app.anihome.launcher.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.util.Locale

/** CRUD for home tiles. All calls are synchronous; callers run them off the main thread. */
class TileStore(context: Context) {
    private val app = context.applicationContext
    private val db get() = LauncherDb.get(app).writableDatabase

    fun all(): List<HomeTile> = db.query(TABLE, null, null, null, null, null, "position ASC, id ASC").use { c ->
        buildList { while (c.moveToNext()) add(c.toTile()) }
    }

    fun byId(id: Long): HomeTile? = db.query(TABLE, null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
        if (c.moveToFirst()) c.toTile() else null
    }

    fun hasApp(packageName: String, activityName: String?): Boolean {
        val selection = if (activityName == null) "kind=? AND package_name=?" else "kind=? AND package_name=? AND activity_name=?"
        val args = listOfNotNull(HomeTile.Kind.APP.name, packageName, activityName).toTypedArray()
        return db.query(TABLE, arrayOf("id"), selection, args, null, null, null).use { it.count > 0 }
    }

    fun hasUrl(url: String): Boolean =
        db.query(TABLE, arrayOf("id"), "kind=? AND lower(url)=lower(?)", arrayOf(HomeTile.Kind.WEB.name, url), null, null, null)
            .use { it.count > 0 }

    fun hasHost(host: String): Boolean {
        val wanted = host.lowercase(Locale.ROOT).removePrefix("www.")
        return all().any { tile ->
            tile.url?.let { Uri.parse(it).host }?.lowercase(Locale.ROOT)?.removePrefix("www.") == wanted
        }
    }

    fun addApp(label: String, packageName: String, activityName: String, color: Int?): HomeTile {
        val values = ContentValues().apply {
            put("kind", HomeTile.Kind.APP.name)
            put("label", label)
            put("package_name", packageName)
            put("activity_name", activityName)
            put("color", color)
            put("position", nextPosition())
            put("created_at", System.currentTimeMillis())
        }
        val id = db.insert(TABLE, null, values)
        return byId(id)!!
    }

    fun addWeb(label: String, url: String, color: Int?): HomeTile {
        val values = ContentValues().apply {
            put("kind", HomeTile.Kind.WEB.name)
            put("label", label)
            put("url", url)
            put("color", color)
            put("position", nextPosition())
            put("created_at", System.currentTimeMillis())
        }
        val id = db.insert(TABLE, null, values)
        return byId(id)!!
    }

    /** Batch additions are atomic and skip existing tiles rather than replacing artwork. */
    fun addApps(apps: List<InstalledApp>): Int {
        val database = db
        database.beginTransaction()
        try {
            var added = 0
            apps.distinctBy { it.component }.forEach { app ->
                if (!hasApp(app.packageName, app.activityName)) {
                    addApp(app.label, app.packageName, app.activityName, null); added++
                }
            }
            database.setTransactionSuccessful()
            return added
        } finally { database.endTransaction() }
    }

    fun addSites(sites: List<Pair<String, String>>): List<HomeTile> {
        val database = db
        database.beginTransaction()
        try {
            val added = sites.mapNotNull { (url, label) -> if (hasUrl(url)) null else addWeb(label, url, null) }
            database.setTransactionSuccessful()
            return added
        } finally { database.endTransaction() }
    }

    fun rename(id: Long, label: String) {
        if (label.isBlank()) return
        db.update(TABLE, ContentValues().apply { put("label", label.trim()) }, "id=?", arrayOf(id.toString()))
    }

    fun setIcon(id: Long, iconPath: String?, color: Int?) {
        val values = ContentValues().apply {
            put("icon_path", iconPath)
            if (color != null) put("color", color)
        }
        db.update(TABLE, values, "id=?", arrayOf(id.toString()))
    }

    fun setArtwork(id: Long, path: String?) {
        val previous = byId(id)?.artworkPath
        db.update(TABLE, ContentValues().apply { put("artwork_path", path) }, "id=?", arrayOf(id.toString()))
        previous?.let { IconCache.invalidateFile(it); if (it != path) File(it).delete() }
    }

    fun remove(id: Long) {
        byId(id)?.artworkPath?.let { IconCache.invalidateFile(it); File(it).delete() }
        byId(id)?.iconPath?.let { runCatching { File(it).delete() } }
        db.delete(TABLE, "id=?", arrayOf(id.toString()))
    }

    fun removePackage(packageName: String): Int {
        val removed = db.query(TABLE, arrayOf("id"), "package_name=?", arrayOf(packageName), null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
        removed.forEach(::remove)
        return removed.size
    }

    /** Persist a new order after a drag; [orderedIds] is the complete grid order. */
    fun reorder(orderedIds: List<Long>) {
        val database = db
        database.beginTransaction()
        try {
            orderedIds.forEachIndexed { index, id ->
                database.update(TABLE, ContentValues().apply { put("position", index) }, "id=?", arrayOf(id.toString()))
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun nextPosition(): Int = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM $TABLE", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    private fun Cursor.toTile() = HomeTile(
        id = getLong(getColumnIndexOrThrow("id")),
        kind = HomeTile.Kind.valueOf(getString(getColumnIndexOrThrow("kind"))),
        label = getString(getColumnIndexOrThrow("label")),
        packageName = getStringOrNull("package_name"),
        activityName = getStringOrNull("activity_name"),
        url = getStringOrNull("url"),
        iconPath = getStringOrNull("icon_path"),
        artworkPath = getStringOrNull("artwork_path"),
        color = getColumnIndexOrThrow("color").let { if (isNull(it)) null else getInt(it) },
        position = getInt(getColumnIndexOrThrow("position")),
    )

    private fun Cursor.getStringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    companion object {
        private const val TABLE = "tiles"
    }
}
