package app.anihome.launcher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Battery level is both a fill height and a color; charging has its own adjacent icon. */
internal class BatteryIconDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var percent = -1
    private var opacity = 255
    fun update(value: Int) { percent = value.coerceIn(-1, 100); invalidateSelf() }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 14f, bounds.height() / 24f)
        paint.color = when {
            percent < 0 -> 0xffa0a0a0.toInt()
            percent <= 20 -> 0xfff87171.toInt()
            percent < 50 -> 0xfffbbf24.toInt()
            else -> 0xff4ade80.toInt()
        }
        paint.alpha = opacity
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(4f, 1f, 10f, 4.5f, .5f, .5f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f
        canvas.drawRoundRect(1.5f, 4.5f, 12.5f, 22.5f, .8f, .8f, paint)
        if (percent > 0) {
            paint.style = Paint.Style.FILL
            canvas.drawRect(3.2f, 20.8f - 14.6f * percent / 100f, 10.8f, 20.8f, paint)
        }
        canvas.restoreToCount(save)
    }
    override fun getIntrinsicWidth() = (14 * density).toInt()
    override fun getIntrinsicHeight() = (24 * density).toInt()
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
