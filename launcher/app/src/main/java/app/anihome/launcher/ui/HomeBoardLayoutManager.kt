package app.anihome.launcher.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil

/** Occupancy-based cells fill gaps beside tall widgets, rather than forcing row-wide widget sections. */
internal class HomeBoardLayoutManager(private val context: Context, private val board: HomeBoardAdapter, private val labels: () -> Boolean) : RecyclerView.LayoutManager() {
    val columns get() = HomeLayout.columns(context) * 2
    var cellWidth = 1f
        private set
    var cellHeight = 1f
        private set
    private fun calculate(width: Int): List<HomeBoardPacking.Placement> {
        val density = context.resources.displayMetrics.density
        cellWidth = ((width - paddingLeft - paddingRight).toFloat() / columns).coerceAtLeast(1f)
        cellHeight = (((cellWidth * 2 - 16 * density) / CropGeometry.ASPECT + 16 * density + if (labels()) 36 * density else 0f) / 2).coerceAtLeast(32 * density)
        return board.pack(columns)
    }
    override fun generateDefaultLayoutParams() = RecyclerView.LayoutParams(-1, -2)
    override fun onMeasure(recycler: RecyclerView.Recycler, state: RecyclerView.State, widthSpec: Int, heightSpec: Int) {
        val w = View.MeasureSpec.getSize(widthSpec)
        val placements = calculate(w)
        val rows = (placements.maxOfOrNull { it.y + it.rows } ?: 0) + if (board.editing) 2 else 0
        setMeasuredDimension(w, ceil(rows * cellHeight).toInt() + paddingTop + paddingBottom)
    }
    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (state.isPreLayout) return
        calculate(width).forEachIndexed { index, p ->
            val view = recycler.getViewForPosition(index)
            addView(view)
            if (!board.previewing) resetMovement(view)
            val gap = ArtworkUi.dp(context, 8)
            val left = paddingLeft + (p.x * cellWidth).toInt() + gap
            val top = paddingTop + (p.y * cellHeight).toInt() + gap
            val right = paddingLeft + ((p.x + p.columns) * cellWidth).toInt() - gap
            val bottom = paddingTop + ((p.y + p.rows) * cellHeight).toInt() - gap
            view.measure(View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY))
            layoutDecoratedWithMargins(view, left, top, right, bottom)
        }
    }
    private fun resetMovement(view: View) {
        view.animate().cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f; view.scaleY = 1f; view.translationZ = 0f
    }
    /** Rebinding can interrupt a neighbor's animation; never retain that fractional offset. */
    fun settlePreview(draggedKey: String? = null) {
        for (index in 0 until childCount) {
            val view = getChildAt(index) ?: continue
            if (draggedKey != null && board.keyAt(getPosition(view)) == draggedKey) continue
            resetMovement(view)
        }
    }
    /** Move neighbors immediately while ItemTouchHelper keeps the dragged view under the finger. */
    fun preview(draggedKey: String) {
        for (index in 0 until childCount) {
            val view = getChildAt(index) ?: continue
            val key = board.keyAt(getPosition(view)) ?: continue
            if (key == draggedKey) continue
            val p = board.placements.firstOrNull { it.key == key } ?: continue
            val gap = ArtworkUi.dp(context, 8)
            val left = paddingLeft + (p.x * cellWidth).toInt() + gap
            val top = paddingTop + (p.y * cellHeight).toInt() + gap
            val right = paddingLeft + ((p.x + p.columns) * cellWidth).toInt() - gap
            val bottom = paddingTop + ((p.y + p.rows) * cellHeight).toInt() - gap
            if (view.left == left && view.top == top) continue
            val oldLeft = view.left + view.translationX
            val oldTop = view.top + view.translationY
            view.animate().cancel()
            layoutDecoratedWithMargins(view, left, top, right, bottom)
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                view.translationX = oldLeft - left; view.translationY = oldTop - top
                view.animate().translationX(0f).translationY(0f).setDuration(200).setInterpolator(MotionUi.STANDARD).start()
            } else { view.translationX = 0f; view.translationY = 0f }
        }
    }
    override fun supportsPredictiveItemAnimations() = false
}
