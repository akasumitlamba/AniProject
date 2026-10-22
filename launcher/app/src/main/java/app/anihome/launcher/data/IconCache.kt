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
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).coerceIn(4L * 1024 * 1024, 24L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cachedAppIcon(packageName: String, activityName: String?): Bitmap? = cache.get("app:$packageName/$activityName")

    fun appIcon(context: Context, packageName: String, activityName: String?): Bitmap? {
        val key = "app:$packageName/$activityName"
        cache.get(key)?.let { return it }
        val drawable = InstalledApps.icon(context, packageName, activityName) ?: return null
        val bitmap = drawable.toBitmap(sizePx(context))
        cache.put(key, bitmap)
        return bitmap
    }

    /** Dock-only normalization: adaptive layers and legacy art share a real circular silhouette. */
    fun dockIcon(context: Context, packageName: String, activityName: String?): Bitmap? {
        val key = "dock:$packageName/$activityName"
        cache.get(key)?.let { return it }
        val icon = InstalledApps.icon(context, packageName, activityName)?.mutate() ?: return null
        val size = sizePx(context)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val circle = android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) }
        canvas.clipPath(circle)
        canvas.drawColor(0xff303030.toInt())
        if (icon is android.graphics.drawable.AdaptiveIconDrawable) {
            val inset = size / 4
            listOf(icon.background, icon.foreground).forEach { layer ->
                layer?.setBounds(-inset, -inset, size + inset, size + inset)
                layer?.draw(canvas)
            }
        } else {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
        }
        cache.put(key, bitmap)
        return bitmap
    }

    fun fileIcon(path: String): Bitmap? {
        val key = "file:$path"
        cache.get(key)?.let { return it }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth < 1 || options.outHeight < 1) return null
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 640) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    fun invalidate(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("app:$packageName/") || it.startsWith("dock:$packageName/") }.forEach(cache::remove)
    }

    fun invalidateFile(path: String) { cache.remove("file:$path") }

    private fun sizePx(context: Context) = (112 * context.resources.displayMetrics.density).toInt().coerceIn(96, 320)

    private fun Drawable.toBitmap(size: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null && bitmap.width <= size && bitmap.height <= size) return bitmap
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bitmap
    }
}
