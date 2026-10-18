package app.anihome.launcher.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** Contrast against the displayed top-right artwork region, rather than an unrelated brand colour. */
internal object BannerBadgeColors {
    data class Palette(val background: Int, val text: Int)
    fun choose(artwork: Bitmap?, fill: Int): Palette {
        var background = fill
        if (artwork != null) {
            val width = minOf(artwork.width.toFloat(), artwork.height * CropGeometry.ASPECT)
            val height = minOf(artwork.height.toFloat(), artwork.width / CropGeometry.ASPECT)
            val left = (artwork.width - width) / 2f
            val top = (artwork.height - height) / 2f
            var red = 0; var green = 0; var blue = 0
            for (y in 0..7) for (x in 0..7) {
                val px = (left + width * (.80f + x * .025f)).roundToInt().coerceIn(0, artwork.width - 1)
                val py = (top + height * (.02f + y * .025f)).roundToInt().coerceIn(0, artwork.height - 1)
                val color = ColorUtils.compositeColors(artwork.getPixel(px, py), fill)
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
            }
            background = Color.rgb(red / 64, green / 64, blue / 64)
        }
        val dark = 0xff101820.toInt()
        val light = 0xfff8fafc.toInt()
        return if (ColorUtils.calculateContrast(light, background) > ColorUtils.calculateContrast(dark, background))
            Palette(light, dark) else Palette(dark, light)
    }
}
