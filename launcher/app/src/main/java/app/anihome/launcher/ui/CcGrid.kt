package app.anihome.launcher.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Control Center tile sizes in grid cells (columns × rows): compact icon tiles and wide labelled ones, like Android 16. */
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
    private val gap = (8 * resources.displayMetrics.density).toInt()
    /** Row height (Android's tiles are a fixed height); zero makes square cells. */
    var rowHeight = (64 * resources.displayMetrics.density).toInt()
    private var cell = 1
    private val row get() = if (rowHeight > 0) rowHeight else cell
    private var columns = 4
    private val spots = mutableListOf<IntArray>()

    private fun columnsFor(width: Int) = if (fixedColumns > 0) fixedColumns
        else ((width + gap) / ((96 * resources.displayMetrics.density).toInt() + gap)).coerceIn(4, 12)

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
            child.measure(MeasureSpec.makeMeasureSpec(w * cell + (w - 1) * gap, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h * row + (h - 1) * gap, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, if (rows == 0) 0 else rows * row + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val s = spots.getOrNull(i) ?: continue
            if (s[0] < 0) continue
            val left = s[0] * (cell + gap); val top = s[1] * (row + gap)
            getChildAt(i).layout(left, top, left + s[2] * cell + (s[2] - 1) * gap, top + s[3] * row + (s[3] - 1) * gap)
        }
    }
    /** Width of one column, for turning a handle drag into a size change. */
    val cellWidth get() = cell
    override fun shouldDelayChildPressedState() = false
}
