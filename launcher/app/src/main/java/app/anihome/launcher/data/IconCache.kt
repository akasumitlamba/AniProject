package app.anihome.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/** Small bitmap cache so the grid can bind without touching disk or PackageManager each time. */
object IconCache {
    private val cache = object : LruCache<String, Bitmap>(64) {}

    fun cachedAppIcon(packageName: String, activityName: String?): Bitmap? = cache.get("app:$packageName/$activityName")

    fun appIcon(context: Context, packageName: String, activityName: String?): Bitmap? {
        val key = "app:$packageName/$activityName"
        cache.get(key)?.let { return it }
        val drawable = InstalledApps.icon(context, packageName, activityName) ?: return null
        val bitmap = drawable.toBitmap(sizePx(context))
        cache.put(key, bitmap)
        return bitmap
    }

    fun fileIcon(path: String): Bitmap? {
        val key = "file:$path"
        cache.get(key)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    fun invalidate(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("app:$packageName/") }.forEach(cache::remove)
    }

    fun invalidateFile(path: String) { cache.remove("file:$path") }

    private fun sizePx(context: Context) = (112 * context.resources.displayMetrics.density).toInt().coerceIn(96, 320)

    private fun Drawable.toBitmap(size: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bitmap
    }
}
