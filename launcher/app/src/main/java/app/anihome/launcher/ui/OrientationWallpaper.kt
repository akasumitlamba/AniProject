package app.anihome.launcher.ui

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.view.WindowManager
import java.io.File

/**
 * Separate portrait and landscape framing on Android versions whose wallpaper keeps only one.
 * Both crops are saved at the screen's exact size. Home draws the matching one itself, so a
 * rotation switches instantly with nothing to jitter; the system wallpaper (lock screen, other
 * apps) is updated to the same crop in the background. If the wallpaper is changed anywhere
 * else, AniHome notices the new wallpaper and steps aside.
 */
internal object OrientationWallpaper {
    private const val PREFS = "orientation_wallpaper"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(context: Context, landscape: Boolean) = File(File(context.filesDir, "wallpaper").apply { mkdirs() }, if (landscape) "landscape.jpg" else "portrait.jpg")
    private var shown: Pair<Boolean, Bitmap>? = null

    /** Saves both framings and makes AniHome responsible for switching between them. */
    fun save(context: Context, portrait: Bitmap, landscape: Bitmap, which: Int) {
        file(context, false).outputStream().use { portrait.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        file(context, true).outputStream().use { landscape.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        prefs(context).edit().putBoolean("active", true).putInt("which", which).apply()
        shown = null
    }
    fun active(context: Context) = prefs(context).getBoolean("active", false) && file(context, false).isFile && file(context, true).isFile

    /** Records the system wallpaper id AniHome itself set, to tell it apart from a wallpaper set elsewhere. */
    fun remember(context: Context) {
        val id = runCatching { WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
        prefs(context).edit().putInt("system_id", id).apply()
    }
    private fun stillOurs(context: Context): Boolean {
        val id = runCatching { WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
        val ours = prefs(context).getInt("system_id", -2)
        if (id != ours) { prefs(context).edit().putBoolean("active", false).apply(); shown = null; return false }
        return true
    }

    /** Home: draw the crop for the current orientation, or fall back to the system wallpaper. */
    fun apply(activity: Activity): Boolean {
        if (!active(activity) || !stillOurs(activity)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            activity.window.setBackgroundDrawableResource(android.R.color.transparent)
            return false
        }
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bitmap = shown?.takeIf { it.first == landscape }?.second
            ?: BitmapFactory.decodeFile(file(activity, landscape).path)?.also { shown = landscape to it } ?: return false
        activity.window.setBackgroundDrawable(CenterCropDrawable(activity, bitmap))
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        return true
    }

    /** Off the main thread: bring the system wallpaper (lock screen, other apps) in line with the orientation. */
    fun syncSystem(context: Context, landscape: Boolean) {
        if (!active(context) || !stillOurs(context)) return
        val which = prefs(context).getInt("which", WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        runCatching {
            file(context, landscape).inputStream().use { WallpaperManager.getInstance(context).setStream(it, null, true, which) }
            remember(context)
        }
    }

    /** Fills the window like a wallpaper: scaled to cover, centred. */
    private class CenterCropDrawable(context: Context, private val bitmap: Bitmap) : BitmapDrawable(context.resources, bitmap) {
        private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val scale = maxOf(b.width().toFloat() / bitmap.width, b.height().toFloat() / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(b.exactCenterX() - w / 2, b.exactCenterY() - h / 2, b.exactCenterX() + w / 2, b.exactCenterY() + h / 2), paint)
        }
    }
}
