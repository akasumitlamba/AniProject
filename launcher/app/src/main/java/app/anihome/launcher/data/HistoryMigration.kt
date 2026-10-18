package app.anihome.launcher.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit, local-only copy. The source database is retained even after successful import. */
object HistoryMigration {
    const val TARGET_PACKAGE = "app.anibrave.main"
    const val TARGET_ACTIVITY = "app.anibrave.HistoryImportActivity"

    fun hasHistory(context: Context): Boolean = LauncherDb.get(context).readableDatabase
        .rawQuery("SELECT EXISTS(SELECT 1 FROM anime)", null).use { it.moveToFirst() && it.getInt(0) != 0 }

    fun intent(context: Context): Intent {
        val db = LauncherDb.get(context).readableDatabase
        val payload = JSONObject().put("version", 1).put("source", "app.anihome.launcher")
        // One transaction gives a consistent snapshot; no update/delete is performed.
        db.beginTransaction()
        try {
            for (table in listOf("anime", "site_progress", "episode_log")) {
                val rowLimit = when (table) { "anime" -> 10_000; "site_progress" -> 30_000; else -> 100_000 }
                val rows = JSONArray()
                db.query(table, null, null, null, null, null, null).use { cursor ->
                    require(cursor.count <= rowLimit) { "History exceeds AniBrave import limits; original data preserved" }
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        cursor.columnNames.forEachIndexed { index, name ->
                            val value: Any = when (cursor.getType(index)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                else -> cursor.getString(index)
                            }
                            row.put(name, value)
                        }
                        rows.put(row)
                    }
                }
                payload.put(table, rows)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val folder = File(context.cacheDir, "history-transfer").apply { mkdirs() }
        val file = File(folder, "history.json")
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024 * 1024) { "History exceeds AniBrave import limits; original data preserved" }
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.history-transfer", file)
        return Intent(Intent.ACTION_SEND).setClassName(TARGET_PACKAGE, TARGET_ACTIVITY)
            .setDataAndType(uri, "application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Local watch history", uri) }
    }

    fun removeTemporaryCopy(context: Context) { File(context.cacheDir, "history-transfer/history.json").delete() }
}
