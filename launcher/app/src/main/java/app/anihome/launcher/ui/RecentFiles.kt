package app.anihome.launcher.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ensureActive

/** Queries shared indexes and only folders explicitly selected by the user. No raw paths. */
internal object RecentFiles {
    data class File(val uri: Uri, val name: String, val mime: String, val modified: Long)
    fun hasMediaAccess(context: Context): Boolean = (if (Build.VERSION.SDK_INT >= 33)
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    suspend fun read(context: Context): List<File> {
        val files = mutableListOf<File>()
        val resolver = context.contentResolver
        val collections = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) +
            if (Build.VERSION.SDK_INT >= 29) listOf(MediaStore.Downloads.EXTERNAL_CONTENT_URI) else emptyList()
        collections.forEach { collection ->
            runCatching {
                resolver.query(collection, arrayOf("_id", "_display_name", "mime_type", "date_modified", "date_added"),
                    null, null, "date_modified DESC")?.use { cursor ->
                    var count = 0
                    while (count++ < 60 && cursor.moveToNext()) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        files += File(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1) ?: "File",
                            cursor.getString(2) ?: "application/octet-stream", maxOf(cursor.getLong(3), cursor.getLong(4)) * 1000)
                    }
                }
            }
        }
        resolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }.forEach { grant ->
            val pending = java.util.ArrayDeque<Pair<String, Int>>()
            pending.add(DocumentsContract.getTreeDocumentId(grant.uri) to 0)
            var visited = 0
            while (pending.isNotEmpty() && visited++ < 80 && files.size < 5000) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val (id, depth) = pending.removeFirst()
                runCatching {
                    resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(grant.uri, id), arrayOf("document_id", "_display_name", "mime_type", "last_modified"), null, null, "last_modified DESC")?.use { cursor ->
                        var rows = 0
                        while (rows++ < 500 && cursor.moveToNext()) {
                            val child = cursor.getString(0); val mime = cursor.getString(2)
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (depth < 4) pending.add(child to depth + 1)
                            } else files += File(DocumentsContract.buildDocumentUriUsingTree(grant.uri, child), cursor.getString(1) ?: "File", mime ?: "application/octet-stream", cursor.getLong(3))
                        }
                    }
                }
            }
        }
        return files.distinctBy { it.uri }.sortedByDescending { it.modified }.take(40)
    }
}
