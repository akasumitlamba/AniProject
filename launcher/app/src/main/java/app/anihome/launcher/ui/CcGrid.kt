package app.anihome.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * The Control Center grid. Each control spans any number of columns and rows (up to the grid's
 * width and [MAX_ROWS] tall) and is packed, in [order], into the first place it fits.
 *
 * Every change of place or size animates: when a control is resized, moved, added or removed the
 * others glide to their new spots. A control being dragged ([floating]) follows the finger, and
 * one being resized ([stretching]) follows its corner handle, while the rest reflow around them.
 */
internal class CcGrid(context: Context) : ViewGroup(context) {
    class Params(val key: String, var cols: Int, var rows: Int) : LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    /** Columns; zero picks them from the width (four on a phone, more on a full-screen tablet panel). */
    var fixedColumns = 4
    private val density = resources.displayMetrics.density
    private val gap = (8 * density).toInt()
    /** Row height (Android's tiles are a fixed height); zero makes square cells. */
    var rowHeight = (64 * density).toInt()
    private var cell = 1
    private val row get() = if (rowHeight > 0) rowHeight else cell
    private var columns = 4

    /** Keys in display order; children not listed keep their child order after the listed ones. */
    var order: List<String> = emptyList()
    /** Changes animate only while this is on (edit mode); otherwise everything snaps. */
    var animateChanges = false

    /** A control being dragged: drawn at [floatX], [floatY] (grid coordinates) instead of its slot. */
    var floating: View? = null
        set(value) { field?.let(::settle); field = value }
    var floatX = 0f; var floatY = 0f
    /** A control being resized: drawn from its slot's top-left at [stretchW] × [stretchH]. */
    var stretching: View? = null
        set(value) { field?.let(::settle); field = value }
    var stretchW = 0; var stretchH = 0

    private val targets = HashMap<View, Rect>()
    private val shown = HashMap<String, Rect>()
    private val from = HashMap<String, Rect>()
    private val heading = HashMap<String, Rect>()
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private val ease = PathInterpolator(.2f, 0f, 0f, 1f)

    val columnCount get() = columns
    /** Width of one column and height of one row, for turning a handle drag into a size. */
    val cellWidth get() = cell
    val rowPitch get() = row + gap
    val colPitch get() = cell + gap
    fun spanWidth(cols: Int) = cols * cell + (cols - 1) * gap
    fun spanHeight(rows: Int) = rows * row + (rows - 1) * gap
    /** Where a child currently sits (its slot, not where it is being dragged). */
    fun slotOf(child: View): Rect? = targets[child]

    /** Forget where controls were drawn, so the next layout appears without animating. */
    fun forgetPlaces() { animator?.cancel(); shown.clear(); heading.clear(); from.clear(); progress = 1f }

    private fun columnsFor(width: Int) = if (fixedColumns > 0) fixedColumns
        else ((width + gap) / ((96 * density).toInt() + gap)).coerceIn(4, 12)

    private fun ordered(): List<View> {
        val kids = (0 until childCount).map(::getChildAt)
        val rank = order.withIndex().associate { it.value to it.index }
        return kids.sortedBy { rank[(it.layoutParams as? Params)?.key] ?: (order.size + indexOfChild(it)) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        columns = columnsFor(width)
        cell = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1)
        targets.clear()
        val taken = hashSetOf<Long>()
        fun key(x: Int, y: Int) = (y.toLong() shl 32) or x.toLong()
        var rows = 0
        var bottom = 0
        for (child in ordered()) {
            if (child.visibility == View.GONE) continue
            val p = child.layoutParams as? Params ?: continue
            val w = p.cols.coerceIn(1, columns); val h = p.rows.coerceIn(1, MAX_ROWS)
            var placed: IntArray? = null
            var y = 0
            while (placed == null) {
                for (x in 0..columns - w) {
                    if ((y until y + h).all { r -> (x until x + w).all { c -> key(c, r) !in taken } }) { placed = intArrayOf(x, y); break }
                }
                y++
            }
            for (r in placed[1] until placed[1] + h) for (c in placed[0] until placed[0] + w) taken += key(c, r)
            rows = maxOf(rows, placed[1] + h)
            val left = placed[0] * (cell + gap); val top = placed[1] * (row + gap)
            val rect = Rect(left, top, left + spanWidth(w), top + spanHeight(h))
            targets[child] = rect
            val mw = if (child === stretching) stretchW else rect.width()
            val mh = if (child === stretching) stretchH else rect.height()
            if (child === stretching) bottom = maxOf(bottom, top + stretchH)
            child.measure(MeasureSpec.makeMeasureSpec(mw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(mh, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, maxOf(if (rows == 0) 0 else spanHeight(rows), bottom))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        startAnimationIfMoved()
        placeChildren()
    }

    /** Begins a glide when any control's slot differs from where it was heading. */
    private fun startAnimationIfMoved() {
        val moved = targets.any { (child, rect) ->
            val key = (child.layoutParams as Params).key
            child !== floating && child !== stretching && (heading[key] ?: shown[key]).let { it != null && it != rect }
        }
        // Remember slots even when not animating, so the next change glides from the right place.
        if (!moved) { targets.forEach { (child, rect) -> heading[(child.layoutParams as Params).key] = Rect(rect) }; return }
        val duration = if (animateChanges) MotionUi.duration(this, 300) else 0L
        if (duration == 0L) { animator?.cancel(); progress = 1f; heading.clear(); targets.forEach { (c, r) -> heading[(c.layoutParams as Params).key] = Rect(r) }; return }
        from.clear(); from.putAll(shown.mapValues { Rect(it.value) })
        heading.clear(); targets.forEach { (c, r) -> heading[(c.layoutParams as Params).key] = Rect(r) }
        animator?.cancel()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = ease
            addUpdateListener { progress = it.animatedValue as Float; placeChildren() }
            start()
        }
    }

    private fun placeChildren() {
        val f = progress
        val hadAny = shown.isNotEmpty()
        for ((child, slot) in targets) {
            val key = (child.layoutParams as Params).key
            val rect = when {
                child === floating -> Rect(floatX.toInt(), floatY.toInt(), floatX.toInt() + slot.width(), floatY.toInt() + slot.height())
                child === stretching -> Rect(slot.left, slot.top, slot.left + stretchW, slot.top + stretchH)
                else -> {
                    val start = from[key]
                    if (start == null || f >= 1f) slot
                    else Rect(lerp(start.left, slot.left, f), lerp(start.top, slot.top, f), lerp(start.right, slot.right, f), lerp(start.bottom, slot.bottom, f))
                }
            }
            if (child.measuredWidth != rect.width() || child.measuredHeight != rect.height())
                child.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
            child.layout(rect.left, rect.top, rect.right, rect.bottom)
            // A new control fades in where it lands.
            if (animateChanges && hadAny && !shown.containsKey(key)) { child.alpha = 0f; child.animate().alpha(1f).setDuration(MotionUi.duration(this, 220)).start() }
            shown[key] = rect
        }
        shown.keys.retainAll(targets.keys.map { (it.layoutParams as Params).key }.toSet())
    }

    /** A released control glides from where it was let go into its slot. */
    private fun settle(child: View) { (child.layoutParams as? Params)?.key?.let(heading::remove) }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    override fun shouldDelayChildPressedState() = false

    companion object { const val MAX_ROWS = 4 }
}
