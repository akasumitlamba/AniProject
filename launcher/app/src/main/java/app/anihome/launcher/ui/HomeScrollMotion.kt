package app.anihome.launcher.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView

/**
 * When Home holds more banners than fit, banners entering from the bottom grow and fade in, and
 * those leaving at the top shrink and fade out, so the list reads as flowing through the screen.
 * A home screen that fits without scrolling stays completely still.
 */
internal class HomeScrollMotion(private val scroll: NestedScrollView, private val grid: ViewGroup, private val paused: () -> Boolean) {
    private var active = false

    fun attach() {
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ -> update() })
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scroll.post { update() } }
        grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scroll.post { update() } }
    }

    fun update() {
        val scrollable = scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)
        if (!scrollable || paused()) { if (active) reset(); return }
        active = true
        // The visible band: from the top of the scroller to the top of the dock (its bottom padding).
        val top = scroll.scrollY.toFloat()
        val bottom = top + scroll.height - scroll.paddingBottom
        val offset = grid.top + (grid.parent as? View)?.top.let { it ?: 0 }
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val h = child.height.coerceAtLeast(1).toFloat()
            val childTop = offset + child.top.toFloat(); val childBottom = childTop + h
            // How much of the tile is cut off by each edge: fully on screen = 1, even in the first and
            // last rows at the ends of the scroll; half past an edge = 0.
            val cutTop = ((top - childTop) / h).coerceIn(0f, 1f)
            val cutBottom = ((childBottom - bottom) / h).coerceIn(0f, 1f)
            val fromTop = (1f - cutTop * 2f).coerceIn(0f, 1f)
            val fromBottom = (1f - cutBottom * 2f).coerceIn(0f, 1f)
            val f = ease(minOf(fromTop, fromBottom))
            val scale = MIN_SCALE + (1f - MIN_SCALE) * f
            child.pivotX = child.width / 2f
            child.pivotY = if (fromBottom < fromTop) 0f else h // Grow up from the dock, shrink toward the top.
            child.scaleX = scale; child.scaleY = scale
            child.alpha = MIN_ALPHA + (1f - MIN_ALPHA) * f
            // A fading tile is drawn as one piece; its drop shadow would be clipped at the tile's
            // edge into a visible frame, so the shadow steps aside while it fades.
            shadow(child, f >= .99f)
        }
    }

    fun reset() {
        active = false
        for (i in 0 until grid.childCount) grid.getChildAt(i).apply { scaleX = 1f; scaleY = 1f; alpha = 1f; shadow(this, true) }
    }

    private fun shadow(tile: View, on: Boolean) {
        val surface = tile.findViewById<View>(app.anihome.launcher.R.id.card_surface) ?: return
        val wanted = if (on && surface.background !is android.graphics.drawable.ColorDrawable) 6f * tile.resources.displayMetrics.density else 0f
        if (surface.elevation != wanted) surface.elevation = wanted
    }

    private fun ease(x: Float) = 1f - (1f - x) * (1f - x) // Gentle settle, no snap.

    private companion object {
        const val MIN_SCALE = .82f
        const val MIN_ALPHA = .25f
    }
}
