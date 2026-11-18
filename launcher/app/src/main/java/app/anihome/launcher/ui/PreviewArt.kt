package app.anihome.launcher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Small illustrations for settings: a phone screen sketch showing what a choice looks like
 * (banners and icons per row, arrangement, list or grid, panel layout and background).
 */
internal object PreviewArt {
    private const val FRAME = 0xFF6F7480.toInt()
    private const val BANNER = 0xFFA8C7FA.toInt()
    private const val ICON = 0xFFE3E2E9.toInt()
    private const val DIM = 0x66E3E2E9

    private class Art(private val paint: (Canvas, RectF, Paint) -> Unit) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
            // Phone outline.
            val phone = RectF(b.left + w * .18f, b.top + 2f, b.right - w * .18f, b.bottom - 2f)
            p.style = Paint.Style.STROKE; p.strokeWidth = w * .03f; p.color = FRAME
            canvas.drawRoundRect(phone, w * .1f, w * .1f, p)
            p.style = Paint.Style.FILL
            val screen = RectF(phone.left + w * .06f, phone.top + w * .08f, phone.right - w * .06f, phone.bottom - w * .08f)
            paint(canvas, screen, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { p.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Rows of banners [perRow] across, then a row of icons [icons] across. */
    fun home(perRow: Int, icons: Int, gaps: Boolean = false): Drawable = Art { c, s, p ->
        val gap = s.width() * .06f
        val bw = (s.width() - gap * (perRow - 1)) / perRow; val bh = bw / 1.62f
        var y = s.top
        repeat(2) { row ->
            for (i in 0 until perRow) {
                if (gaps && row == 1 && i == 0) continue // Free arrangement: a gap stays.
                p.color = BANNER; c.drawRoundRect(RectF(s.left + i * (bw + gap), y, s.left + i * (bw + gap) + bw, y + bh), bw * .14f, bw * .14f, p)
            }
            y += bh + gap
        }
        val r = (s.width() - gap * (icons - 1)) / icons / 2f
        repeat(2) { row ->
            for (i in 0 until icons) {
                if (gaps && row == 0 && i == icons - 1) continue
                p.color = ICON; c.drawCircle(s.left + r + i * (2 * r + gap), y + r, r, p)
            }
            y += 2 * r + gap
        }
    }
    fun tile(banner: Boolean): Drawable = Art { c, s, p ->
        p.color = if (banner) BANNER else ICON
        if (banner) { val w = s.width() * .8f; val h = w / 1.62f; c.drawRoundRect(RectF(s.centerX() - w / 2, s.centerY() - h / 2, s.centerX() + w / 2, s.centerY() + h / 2), w * .12f, w * .12f, p) }
        else c.drawCircle(s.centerX(), s.centerY(), s.width() * .22f, p)
    }
    fun appMenu(list: Boolean): Drawable = Art { c, s, p ->
        val gap = s.width() * .08f
        if (list) {
            val r = s.width() * .09f; var y = s.top + r
            repeat(6) { p.color = ICON; c.drawCircle(s.left + r, y, r, p); p.color = DIM; c.drawRoundRect(RectF(s.left + 3 * r, y - r * .35f, s.right - s.width() * .1f, y + r * .35f), r, r, p); y += 2 * r + gap }
        } else {
            val r = (s.width() - gap * 3) / 8f; var y = s.top + r
            repeat(5) { for (i in 0 until 4) { p.color = ICON; c.drawCircle(s.left + r + i * (2 * r + gap), y, r, p) }; y += 2 * r + gap }
        }
    }
    fun panel(full: Boolean, glass: Boolean = true): Drawable = Art { c, s, p ->
        // Home behind, the panel over it.
        p.color = 0x33A8C7FA; c.drawRect(s, p)
        val area = if (full) s else RectF(s.left, s.top, s.left + s.width() * .7f, s.bottom)
        p.color = if (glass) 0xB31D2027.toInt() else 0xFF1D2027.toInt(); c.drawRect(area, p)
        val gap = s.width() * .05f; val cell = (area.width() - gap * 5) / 4f; var y = area.top + gap
        val sizes = listOf(2, 2, 1, 1, 2)
        var x = area.left + gap
        sizes.forEach { span ->
            val w = cell * span + gap * (span - 1)
            if (x + w > area.right - gap + 1) { x = area.left + gap; y += cell + gap }
            p.color = if (span == 2) BANNER else ICON
            c.drawRoundRect(RectF(x, y, x + w, y + cell), cell / 2, cell / 2, p); x += w + gap
        }
    }
}
