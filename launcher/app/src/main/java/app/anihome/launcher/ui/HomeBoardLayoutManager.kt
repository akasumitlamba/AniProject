package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil

/**
 * Occupancy-based cells fill gaps beside tall widgets, rather than forcing row-wide widget sections.
 * The grid is fine-grained ([HomeGrid]) so banners and icons each get their own count per row.
 */
internal class HomeBoardLayoutManager(private val context: Context, private val board: HomeBoardAdapter, private val labels: () -> Boolean) : RecyclerView.LayoutManager() {
    var grid = HomeGrid(HomeLayout.columns(context), HomeLayout.columns(context) * 2 + 1)
        private set
    val columns: Int get() = grid.columns
    /** Width of one fine column. */
    var cellWidth = 1f
        private set
    /** Width of one widget cell (what widget sizes are measured in). */
    val widgetCellWidth get() = cellWidth * grid.widgetUnit
    var cellHeight = 1f
        private set
    /** Set before a change that should glide (a tile becoming an icon or banner, a widget added). */
    var animateNextLayout = false

    private fun calculate(width: Int): List<HomeBoardPacking.Placement> {
        val density = context.resources.displayMetrics.density
        val available = (width - paddingLeft - paddingRight).toFloat()
        val banners = HomeLayout.columns(context)
        grid = HomeGrid(banners, HomeLayout.iconColumns(context, available / density, banners))
        board.grid = grid
        cellWidth = (available / columns).coerceAtLeast(1f)
        val bannerWidth = cellWidth * grid.bannerSpan
        cellHeight = (((bannerWidth - 16 * density) / CropGeometry.ASPECT + 16 * density + if (labels()) 36 * density else 0f) / 2).coerceAtLeast(32 * density)
        return board.pack(columns)
    }
    override fun generateDefaultLayoutParams() = RecyclerView.LayoutParams(-1, -2)
    override fun onMeasure(recycler: RecyclerView.Recycler, state: RecyclerView.State, widthSpec: Int, heightSpec: Int) {
        val w = View.MeasureSpec.getSize(widthSpec)
        val placements = calculate(w)
        val rows = (placements.maxOfOrNull { it.y + it.rows } ?: 0) + if (board.editing) 2 else 0
        setMeasuredDimension(w, ceil(rows * cellHeight).toInt() + paddingTop + paddingBottom)
    }
    /** Icons sit closer together than banners, so a row of small icons doesn't waste width. */
    private fun gapFor(p: HomeBoardPacking.Placement) = ArtworkUi.dp(context, if (p.key.startsWith("tile:") && p.columns == grid.iconSpan) 4 else 8)
    private fun bounds(p: HomeBoardPacking.Placement): Rect {
        val gap = gapFor(p)
        return Rect(paddingLeft + (p.x * cellWidth).toInt() + gap, paddingTop + (p.y * cellHeight).toInt() + gap,
            paddingLeft + ((p.x + p.columns) * cellWidth).toInt() - gap, paddingTop + ((p.y + p.rows) * cellHeight).toInt() - gap)
    }
    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        // Where each item was, so a requested change can glide from there instead of jumping.
        val before = if (animateNextLayout && android.animation.ValueAnimator.areAnimatorsEnabled()) (0 until childCount).mapNotNull { i ->
            getChildAt(i)?.let { v -> board.keyAt(getPosition(v))?.let { it to Rect(v.left + v.translationX.toInt(), v.top + v.translationY.toInt(), v.right + v.translationX.toInt(), v.bottom + v.translationY.toInt()) } }
        }.toMap() else emptyMap()
        val animate = animateNextLayout
        detachAndScrapAttachedViews(recycler)
        if (state.isPreLayout) return
        animateNextLayout = false
        calculate(width).forEachIndexed { index, p ->
            val view = recycler.getViewForPosition(index)
            addView(view)
            if (!board.previewing) resetMovement(view, index)
            val r = bounds(p)
            view.measure(View.MeasureSpec.makeMeasureSpec(r.width(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(r.height(), View.MeasureSpec.EXACTLY))
            layoutDecoratedWithMargins(view, r.left, r.top, r.right, r.bottom)
            if (animate) glide(view, before[p.key], r)
        }
    }
    /**
     * Moves and resizes smoothly from the old spot: a banner shrinking into an icon contracts,
     * an icon becoming a banner expands, neighbours slide into the freed space, new items grow in.
     */
    private fun glide(view: View, old: Rect?, now: Rect) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        val endScale = view.scaleX
        view.pivotX = 0f; view.pivotY = 0f
        if (old == null) {
            view.alpha = 0f; view.scaleX = endScale * .85f; view.scaleY = endScale * .85f
            view.pivotX = now.width() / 2f; view.pivotY = now.height() / 2f
            view.animate().alpha(1f).scaleX(endScale).scaleY(endScale).setDuration(260).setInterpolator(MotionUi.EMPHASIZED_DECELERATE).start()
            return
        }
        if (old == now) return
        view.translationX = (old.left - now.left).toFloat(); view.translationY = (old.top - now.top).toFloat()
        view.scaleX = endScale * old.width() / now.width().coerceAtLeast(1); view.scaleY = endScale * old.height() / now.height().coerceAtLeast(1)
        view.animate().translationX(0f).translationY(0f).scaleX(endScale).scaleY(endScale).setDuration(300)
            .setInterpolator(MotionUi.EMPHASIZED_DECELERATE).withEndAction { view.pivotX = view.width / 2f; view.pivotY = view.height / 2f }.start()
    }
    private fun resetMovement(view: View, position: Int = RecyclerView.NO_POSITION) {
        view.animate().cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
        // Keep edit mode's slightly smaller tiles; a relayout used to snap them back to full size.
        val scale = if (board.editing && position != RecyclerView.NO_POSITION && !board.isWidget(position)) .96f else 1f
        view.pivotX = view.width / 2f; view.pivotY = view.height / 2f
        view.scaleX = scale; view.scaleY = scale; view.translationZ = 0f
    }
    /** Rebinding can interrupt a neighbor's animation; never retain that fractional offset. */
    fun settlePreview(draggedKey: String? = null) {
        for (index in 0 until childCount) {
            val view = getChildAt(index) ?: continue
            if (draggedKey != null && board.keyAt(getPosition(view)) == draggedKey) continue
            resetMovement(view, getPosition(view))
        }
    }
    /** Move neighbors immediately while ItemTouchHelper keeps the dragged view under the finger. */
    fun preview(draggedKey: String) {
        for (index in 0 until childCount) {
            val view = getChildAt(index) ?: continue
            val key = board.keyAt(getPosition(view)) ?: continue
            if (key == draggedKey) continue
            val p = board.placements.firstOrNull { it.key == key } ?: continue
            val r = bounds(p)
            if (view.left == r.left && view.top == r.top) continue
            val oldLeft = view.left + view.translationX
            val oldTop = view.top + view.translationY
            view.animate().cancel()
            layoutDecoratedWithMargins(view, r.left, r.top, r.right, r.bottom)
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                view.translationX = oldLeft - r.left; view.translationY = oldTop - r.top
                view.animate().translationX(0f).translationY(0f).setDuration(200).setInterpolator(MotionUi.STANDARD).start()
            } else { view.translationX = 0f; view.translationY = 0f }
        }
    }
    override fun supportsPredictiveItemAnimations() = false
}
