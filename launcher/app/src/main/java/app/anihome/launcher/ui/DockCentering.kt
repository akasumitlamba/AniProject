package app.anihome.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Dock apps sit on the dock's own centre, not the middle of whatever space the clock and buttons
 * leave. As more apps are added the row grows from that centre and slides only as far as needed
 * to stay clear of both sides; beyond the free space it scrolls instead of overlapping.
 */
internal object DockCentering {
    /** Pure placement: where the row starts inside the free area (0 = flush with its start). */
    fun start(dockCenter: Float, areaStart: Float, areaSize: Int, rowSize: Int): Int {
        if (rowSize >= areaSize) return 0
        return ((dockCenter - areaStart) - rowSize / 2f).roundToInt().coerceIn(0, areaSize - rowSize)
    }

    fun attach(dock: View, area: View, row: LinearLayout, vertical: Boolean) {
        fun update() {
            if (!area.isLaidOut || !dock.isLaidOut) return
            var size = 0
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i); if (child.visibility == View.GONE) continue
                val lp = child.layoutParams as ViewGroup.MarginLayoutParams
                size += if (vertical) lp.height.coerceAtLeast(child.measuredHeight) + lp.topMargin + lp.bottomMargin
                    else lp.width.coerceAtLeast(child.measuredWidth) + lp.leftMargin + lp.rightMargin
            }
            val offset = if (vertical) start(dock.height / 2f, area.top.toFloat(), area.height, size)
                else start(dock.width / 2f, area.left.toFloat(), area.width, size)
            val current = if (vertical) row.paddingTop else row.paddingLeft
            if (offset != current) {
                if (vertical) row.setPadding(row.paddingLeft, offset, row.paddingRight, 0)
                else row.setPadding(offset, row.paddingTop, 0, row.paddingBottom)
            }
        }
        area.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> area.post { update() } }
        dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> area.post { update() } }
    }
}
