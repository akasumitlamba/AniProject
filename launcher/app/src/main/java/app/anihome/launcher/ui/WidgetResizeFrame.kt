package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.FrameLayout

/** Edge targets stay outside provider content; resizing never rebinds a live widget mid-drag. */
internal class WidgetResizeFrame(context: Context) : FrameLayout(context) {
    var editing = false
    var resizeSelected = false
        set(value) { field = value; invalidate() }
    var horizontal = true
    var vertical = true
    var onStart: (() -> Unit)? = null
    var onResize: ((Float, Float) -> Unit)? = null
    var onEnd: ((Boolean) -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var edges = 0
    private var startX = 0f
    private var startY = 0f
    private fun edge(x: Float, y: Float): Int {
        val hit = 24 * density
        val xEdge = if (!horizontal) 0 else if (x < hit) 1 else if (x > width - hit) 2 else 0
        val yEdge = if (!vertical) 0 else if (y < hit) 4 else if (y > height - hit) 8 else 0
        return xEdge or yEdge
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (resizeSelected && event.actionMasked == MotionEvent.ACTION_DOWN) {
            edges = edge(event.x, event.y)
            if (edges != 0) return true
        }
        return editing || super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(event)
        if (!resizeSelected || edges == 0) {
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY
                parent.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onStart?.invoke()
            }
            MotionEvent.ACTION_MOVE -> onResize?.invoke(
                if (edges and 1 != 0) startX - event.rawX else if (edges and 2 != 0) event.rawX - startX else 0f,
                if (edges and 4 != 0) startY - event.rawY else if (edges and 8 != 0) event.rawY - startY else 0f
            )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onEnd?.invoke(event.actionMasked == MotionEvent.ACTION_CANCEL)
                edges = 0; parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!resizeSelected) return
        val inset = 3 * density
        paint.color = 0xffb6e4fa.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, 14 * density, 14 * density, paint)
        paint.style = Paint.Style.FILL
        fun handle(x: Float, y: Float, tall: Boolean) {
            val dx = (if (tall) 3 else 12) * density
            val dy = (if (tall) 12 else 3) * density
            canvas.drawRoundRect(x - dx, y - dy, x + dx, y + dy, 3 * density, 3 * density, paint)
        }
        if (horizontal) { handle(inset, height / 2f, true); handle(width - inset, height / 2f, true) }
        if (vertical) { handle(width / 2f, inset, false); handle(width / 2f, height - inset, false) }
    }
}
