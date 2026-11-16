package app.anihome.launcher.ui

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Frosted panel surface: a deep tint at the user's chosen opacity, a hairline edge and rounded
 * inner corners. Android 12+ blurs whatever is behind in real time. Older versions cannot blur
 * other apps, so over Home they use a blurred copy of the wallpaper (what is actually behind)
 * and over other apps just the translucent tint.
 */
internal object PanelBackdrop {
    private var cacheKey: String? = null
    private var cache: Bitmap? = null

    fun apply(activity: AppCompatActivity, window: Window?, root: View, rightSide: Boolean, wide: Boolean, overApps: Boolean, fromTop: Boolean = false) {
        val radius = ArtworkUi.dp(activity, if (wide) 0 else 28).toFloat()
        // Apple-style material: a light tint over a heavy blur. Where nothing can be blurred
        // (other apps on Android 11 and older) a near-solid tint keeps text readable.
        val blur = PanelStyle.blur(activity)
        // The user's glass opacity applies only where something is actually blurred. Unblurred
        // (no blur support, blur switched off by Android, or over other apps on Android 11 and
        // older) the panel stays nearly solid so text is always readable over busy banners.
        val crossBlur = Build.VERSION.SDK_INT >= 31 && runCatching { activity.windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
        val glass = PanelStyle.opacity(activity) * 255 / 100
        val solid = maxOf(glass, 245)
        fun tint(alpha: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(0xFF14161D.toInt(), alpha), withAlpha(0xFF0B0C11.toInt(), alpha)))
        // No outline stroke: under the rounded clip it showed as stray lines along the edges.
        val edge = GradientDrawable().apply { setColor(0) }
        val layers = listOf<Drawable>(tint(if (!blur) 255 else if (crossBlur) glass else solid), edge)
        if (blur && crossBlur && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = ArtworkUi.dp(activity, 30) }
            window.setBackgroundBlurRadius(ArtworkUi.dp(activity, 60))
        } else {
            if (Build.VERSION.SDK_INT >= 31 && window != null) { window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); window.setBackgroundBlurRadius(0) }
            if (blur && !overApps) root.post { // Needs the laid-out panel size to cut the matching wallpaper region.
                wallpaperBlur(activity, root, rightSide)?.let { bitmap ->
                    root.background = LayerDrawable(arrayOf(BitmapDrawable(activity.resources, bitmap), tint(glass), edge))
                }
            }
        }
        root.background = LayerDrawable(layers.toTypedArray())
        // From the side: round only the inner edge. Dropped from the top: a card rounded on
        // its free bottom corners, which reads naturally as a sheet pulled down.
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = radius.toInt()
                if (r == 0) outline.setRect(0, 0, view.width, view.height)
                else if (fromTop) outline.setRoundRect(0, -r, view.width, view.height, radius)
                else if (rightSide) outline.setRoundRect(0, 0, view.width + r, view.height, radius)
                else outline.setRoundRect(-r, 0, view.width, view.height, radius)
            }
        }
        root.clipToOutline = true
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * The wallpaper region under the panel, scaled down and box-blurred (cached). Reading the
     * wallpaper may be refused on some versions/OEMs; that falls back to the tint alone.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun wallpaperBlur(activity: AppCompatActivity, root: View, rightSide: Boolean): Bitmap? = runCatching {
        val metrics = activity.resources.displayMetrics
        val screenW = SidePanelLayout.screenWidth(activity); val screenH = metrics.heightPixels
        val w = root.width.coerceAtLeast(1); val h = root.height.coerceAtLeast(1)
        val manager = WallpaperManager.getInstance(activity)
        val key = "${manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}:$screenW:$screenH:$w:$h:$rightSide"
        cache?.takeIf { cacheKey == key && !it.isRecycled }?.let { return@runCatching it }
        val wallpaper = manager.drawable ?: return@runCatching null
        val scale = 10
        val full = Bitmap.createBitmap((screenW / scale).coerceAtLeast(1), (screenH / scale).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // Centre-crop like the launcher's wallpaper window.
        val iw = wallpaper.intrinsicWidth.coerceAtLeast(1); val ih = wallpaper.intrinsicHeight.coerceAtLeast(1)
        val s = maxOf(full.width.toFloat() / iw, full.height.toFloat() / ih)
        val dw = (iw * s).toInt(); val dh = (ih * s).toInt()
        wallpaper.setBounds((full.width - dw) / 2, (full.height - dh) / 2, (full.width + dw) / 2, (full.height + dh) / 2)
        wallpaper.draw(Canvas(full))
        val x = if (rightSide) ((screenW - w) / scale).coerceAtLeast(0) else 0
        val region = Bitmap.createBitmap(full, x, 0, (w / scale).coerceIn(1, full.width - x), full.height)
        full.recycle()
        boxBlur(region, 4); boxBlur(region, 4); boxBlur(region, 4)
        cache?.recycle(); cache = region; cacheKey = key
        region
    }.getOrNull() // No wallpaper access (permission/OEM): the tint alone still looks right.

    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val tmp = IntArray(w * h)
        fun pass(input: IntArray, output: IntArray, horizontal: Boolean) {
            val outer = if (horizontal) h else w; val inner = if (horizontal) w else h
            for (o in 0 until outer) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (k in -radius..radius) {
                    val i = k.coerceIn(0, inner - 1); val p = input[if (horizontal) o * w + i else i * w + o]
                    r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255; count++
                }
                for (i in 0 until inner) {
                    output[if (horizontal) o * w + i else i * w + o] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                    val outIdx = (i - radius).coerceIn(0, inner - 1); val inIdx = (i + radius + 1).coerceIn(0, inner - 1)
                    val po = input[if (horizontal) o * w + outIdx else outIdx * w + o]; val pi = input[if (horizontal) o * w + inIdx else inIdx * w + o]
                    r += (pi shr 16 and 255) - (po shr 16 and 255); g += (pi shr 8 and 255) - (po shr 8 and 255); b += (pi and 255) - (po and 255)
                }
            }
        }
        pass(src, tmp, true); pass(tmp, src, false)
        bitmap.setPixels(src, 0, w, 0, 0, w, h)
    }
}
