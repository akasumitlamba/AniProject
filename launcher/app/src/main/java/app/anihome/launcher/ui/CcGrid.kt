package app.anihome.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup

/** Control Center tile sizes, in grid cells (columns × rows), like iOS. */
internal enum class CcSize(val columns: Int, val rows: Int, val label: String) {
    SMALL(1, 1, "1×1"), WIDE(2, 1, "2×1"), TALL(1, 2, "1×2"), LARGE(2, 2, "2×2"), BAR(4, 1, "4×1");
    companion object {
        fun parse(value: String?, fallback: CcSize) = entries.firstOrNull { it.name == value } ?: fallback
    }
}

/**
 * The Control Center grid: square cells, each control spanning its own size, packed in order
 * into the first place it fits (so a wide tile next to small ones leaves no holes when it can).
 */
internal class CcGrid(context: Context) : ViewGroup(context) {
    class Params(val size: CcSize) : LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    /** Columns; zero picks them from the width (four on a phone, more on a full-screen tablet panel). */
    var fixedColumns = 4
    private val gap = (10 * resources.displayMetrics.density).toInt()
    private var cell = 1
    private var columns = 4
    private val spots = mutableListOf<IntArray>()

    private fun columnsFor(width: Int) = if (fixedColumns > 0) fixedColumns
        else ((width + gap) / ((84 * resources.displayMetrics.density).toInt() + gap)).coerceIn(4, 12)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        columns = columnsFor(width)
        cell = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1)
        spots.clear()
        val taken = hashSetOf<Long>()
        fun key(x: Int, y: Int) = (y.toLong() shl 32) or x.toLong()
        var rows = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) { spots += intArrayOf(-1, -1, 0, 0); continue }
            val size = (child.layoutParams as? Params)?.size ?: CcSize.SMALL
            val w = size.columns.coerceAtMost(columns); val h = size.rows
            var placed: IntArray? = null
            var y = 0
            while (placed == null) {
                for (x in 0..columns - w) {
                    if ((y until y + h).all { r -> (x until x + w).all { c -> key(c, r) !in taken } }) { placed = intArrayOf(x, y, w, h); break }
                }
                y++
            }
            for (r in placed[1] until placed[1] + h) for (c in placed[0] until placed[0] + w) taken += key(c, r)
            spots += placed; rows = maxOf(rows, placed[1] + h)
            child.measure(MeasureSpec.makeMeasureSpec(w * cell + (w - 1) * gap, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h * cell + (h - 1) * gap, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, if (rows == 0) 0 else rows * cell + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val s = spots.getOrNull(i) ?: continue
            if (s[0] < 0) continue
            val left = s[0] * (cell + gap); val top = s[1] * (cell + gap)
            getChildAt(i).layout(left, top, left + s[2] * cell + (s[2] - 1) * gap, top + s[3] * cell + (s[3] - 1) * gap)
        }
    }
    override fun shouldDelayChildPressedState() = false
}

/**
 * Liquid glass in Material You colours: a translucent body lit from the top, a hairline rim,
 * and — when on — the Material primary tone showing through the glass.
 */
internal object CcGlass {
    const val ON_TOP = 0xF2B9D3FF.toInt()
    const val ON_BOTTOM = 0xE68AB4FF.toInt()
    const val ON_TEXT = 0xFF0B1D3A.toInt()
    fun tile(context: Context, radius: Float, on: Boolean, outlined: Boolean = false): Drawable {
        val d = context.resources.displayMetrics.density
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (on) intArrayOf(ON_TOP, ON_BOTTOM) else intArrayOf(0x40FFFFFF, 0x1FFFFFFF)).apply { cornerRadius = radius }
        // Light catching the upper edge of the glass.
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(if (on) 0x59FFFFFF else 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF)).apply { cornerRadius = radius }
        val rim = GradientDrawable().apply {
            cornerRadius = radius; setColor(0)
            setStroke((if (outlined) 2 * d else d).toInt().coerceAtLeast(1), if (outlined) -1 else if (on) 0x80FFFFFF.toInt() else 0x3DFFFFFF)
        }
        return LayerDrawable(arrayOf(body, sheen, rim))
    }
}
