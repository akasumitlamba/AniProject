package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.util.Size

/**
 * Small previews for recent files: Android's own cached thumbnails for images, GIFs and videos,
 * and the app icon for APKs. Other types keep their type icon. Previews are tiny (128 px),
 * loaded off the main thread and capped in memory, so low-end devices are not burdened.
 */
internal object RecentFileThumbs {
    private const val SIZE = 128
    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val missing = mutableSetOf<String>()

    fun supported(file: RecentFiles.File) = file.mime.startsWith("image/") || file.mime.startsWith("video/") ||
        file.mime == "application/vnd.android.package-archive" || file.name.endsWith(".apk", true)
    fun cached(file: RecentFiles.File): Bitmap? = cache.get(key(file))
    private fun key(file: RecentFiles.File) = "${file.uri}@${file.modified}"

    /** Blocking: call from a background dispatcher. Returns null when no preview exists. */
    fun load(context: Context, file: RecentFiles.File): Bitmap? {
        val key = key(file)
        cache.get(key)?.let { return it }
        if (key in missing || !supported(file)) return null
        val bitmap = runCatching { if (file.mime.startsWith("image/") || file.mime.startsWith("video/")) media(context, file) else apk(context, file) }.getOrNull()
        if (bitmap == null) synchronized(missing) { missing += key } else cache.put(key, bitmap)
        return bitmap
    }

    @Suppress("DEPRECATION")
    private fun media(context: Context, file: RecentFiles.File): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) return context.contentResolver.loadThumbnail(file.uri, Size(SIZE, SIZE), null)
        val id = runCatching { android.content.ContentUris.parseId(file.uri) }.getOrNull() ?: return null
        return if (file.mime.startsWith("video/")) android.provider.MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, android.provider.MediaStore.Video.Thumbnails.MINI_KIND, null)
        else android.provider.MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null)
    }

    /** Reads the APK's own icon. Needs a real file path; content-only files keep the type icon. */
    @Suppress("DEPRECATION")
    private fun apk(context: Context, file: RecentFiles.File): Bitmap? {
        val path = context.contentResolver.query(file.uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val info = context.packageManager.getPackageArchiveInfo(path, 0) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = path; app.publicSourceDir = path
        val drawable = app.loadIcon(context.packageManager)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, SIZE, SIZE); drawable.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }
}
