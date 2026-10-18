package app.anihome.launcher.data

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Picks a card colour from an icon the way AniHome's web grid did: the most common opaque
 * colour along the edges wins (that is usually the brand background), otherwise a dark glass.
 */
object TileColors {
    const val DEFAULT = 0xFF263B57.toInt()

    fun fromIcon(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.width < 4 || bitmap.height < 4) return DEFAULT
        val sample = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val counts = HashMap<Int, Int>()
        var luminance = 0.0
        var visible = 0
        for (y in 0 until 48) for (x in 0 until 48) {
            val pixel = sample.getPixel(x, y)
            if (Color.alpha(pixel) < 200) continue
            luminance += 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
            visible++
            val edge = x < 4 || y < 4 || x > 43 || y > 43
            if (!edge) continue
            val quantized = Color.rgb(Color.red(pixel) and 0xF0, Color.green(pixel) and 0xF0, Color.blue(pixel) and 0xF0)
            counts[quantized] = (counts[quantized] ?: 0) + 1
        }
        if (sample !== bitmap) sample.recycle()
        val edgeTotal = counts.values.sum()
        val dominant = counts.maxByOrNull { it.value }
        if (dominant != null && edgeTotal > 0 && dominant.value * 100 / edgeTotal >= 35) {
            return dominant.key or 0xFF000000.toInt()
        }
        val bright = visible > 0 && luminance / visible > 190
        return if (bright) 0xFF1C2737.toInt() else 0xFFE8EEF7.toInt()
    }

    /** Text/icon tint that stays readable on [background]. */
    fun onColor(background: Int): Int {
        val l = 0.2126 * Color.red(background) + 0.7152 * Color.green(background) + 0.0722 * Color.blue(background)
        return if (l > 160) 0xFF0B1220.toInt() else Color.WHITE
    }
}
