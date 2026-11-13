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
    fun cachedDockIcon(context: Context, packageName: String, activityName: String?): Bitmap? = cache.get("dock:$packageName/$activityName:${IconShape.selected(context).name}")
    fun cachedFileIcon(path: String): Bitmap? = cache.get("file:$path")

    fun appIcon(context: Context, packageName: String, activityName: String?): Bitmap? {
        val key = "app:$packageName/$activityName"
        cache.get(key)?.let { return it }
        val drawable = InstalledApps.icon(context, packageName, activityName) ?: return null
        val bitmap = drawable.toBitmap(sizePx(context))
        cache.put(key, bitmap)
        return bitmap
    }

    /** Shared drawer/dock artwork: crop to a circle without shrinking a square inside it. */
    fun dockIcon(context: Context, packageName: String, activityName: String?): Bitmap? {
        val shape = IconShape.selected(context)
        val key = "dock:$packageName/$activityName:${shape.name}"
        cache.get(key)?.let { return it }
        val icon = InstalledApps.icon(context, packageName, activityName)?.mutate() ?: return null
        val size = sizePx(context)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xff303030.toInt())
        if (icon is android.graphics.drawable.AdaptiveIconDrawable) {
            val inset = size / 4
            listOf(icon.background, icon.foreground).forEach { layer ->
                layer?.setBounds(-inset, -inset, size + inset, size + inset)
                layer?.draw(canvas)
            }
        } else {
            val width = icon.intrinsicWidth.coerceAtLeast(1)
            val height = icon.intrinsicHeight.coerceAtLeast(1)
            val scale = maxOf(size.toFloat() / width, size.toFloat() / height)
            val drawnWidth = (width * scale).toInt(); val drawnHeight = (height * scale).toInt()
            val left = (size - drawnWidth) / 2; val top = (size - drawnHeight) / 2
            icon.setBounds(left, top, left + drawnWidth, top + drawnHeight)
            icon.draw(canvas)
        }
        val shaped = masked(bitmap, shape)
        cache.put(key, shaped)
        return shaped
    }

    /**
     * Anti-aliased shape mask. Canvas.clipPath is not anti-aliased on most devices and leaves
     * visible pixel steps around circles; drawing the shape with an AA paint and then the
     * artwork with SRC_IN gives smooth edges.
     */
    fun masked(source: Bitmap, shape: IconShape): Bitmap {
        val size = source.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawPath(shape.path(size), paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        source.recycle()
        return out
    }

    fun cachedShapedFileIcon(context: Context, path: String): Bitmap? = cache.get("shaped-file:$path:${IconShape.selected(context).name}")
    fun shapedFileIcon(context: Context, path: String): Bitmap? {
        val shape = IconShape.selected(context)
        val key = "shaped-file:$path:${shape.name}"
        cache.get(key)?.let { return it }
        val source = fileIcon(path) ?: return null
        val size = sizePx(context)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xff303030.toInt())
        val scale = maxOf(size.toFloat() / source.width, size.toFloat() / source.height)
        val width = source.width * scale; val height = source.height * scale
        canvas.drawBitmap(source, null, android.graphics.RectF((size-width)/2, (size-height)/2, (size+width)/2, (size+height)/2), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
        val shaped = masked(bitmap, shape)
        cache.put(key, shaped)
        return shaped
    }
    fun clearNormalized() { cache.snapshot().keys.filter { it.startsWith("dock:") || it.startsWith("shaped-file:") }.forEach(cache::remove) }

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

    fun invalidateFile(path: String) { cache.remove("file:$path"); cache.snapshot().keys.filter { it.startsWith("shaped-file:$path:") }.forEach(cache::remove) }

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
