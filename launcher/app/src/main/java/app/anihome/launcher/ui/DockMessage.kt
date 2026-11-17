package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * A short message of the user's own in the dock. If it fits, it stays still and centred.
 * If it is longer than the space, it slides through like an LED board: in from one end, out
 * the other, then again. In the side dock it reads along the rail (rotated), sliding upward.
 */
internal class DockMessage(context: Context) : View(context) {
    var text: String = ""
        set(value) { field = value; contentDescription = value; offset = 0f; requestLayout(); invalidate() }
    var vertical = false
        set(value) { field = value; offset = 0f; requestLayout(); invalidate() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    /** Slide speed: an easy reading pace, independent of screen density. */
    private val speed = 48f * density
    private var offset = 0f
    private var lastFrame = 0L
    private var running = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textLength = paint.measureText(text).toInt() + (16 * density).toInt()
        val thickness = (paint.fontSpacing + 8 * density).toInt()
        val w = if (vertical) resolveSize(thickness, widthMeasureSpec) else resolveSize(textLength, widthMeasureSpec)
        val h = if (vertical) resolveSize(textLength, heightMeasureSpec) else resolveSize(thickness, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val length = (if (vertical) height else width).toFloat()
        val textWidth = paint.measureText(text)
        val baseline = (if (vertical) width else height) / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.save()
        if (vertical) { canvas.rotate(-90f); canvas.translate(-height.toFloat(), 0f) } // Reads bottom to top.
        canvas.clipRect(0f, 0f, length, (if (vertical) width else height).toFloat())
        val fits = textWidth <= length - 8 * density
        // Animations off: never move. Long text then simply ends at the edge.
        if (fits || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            running = false
            // Starts where its space starts (right after the clock, or at the dock's edge), like
            // any other line of text; centring it left it floating oddly in an empty dock.
            canvas.drawText(text, 4 * density, baseline, paint)
        } else {
            // Enters from the far end, leaves past the near end, then repeats.
            val cycle = length + textWidth
            val x = length - (offset % cycle)
            canvas.drawText(text, x, baseline, paint)
            advance()
        }
        canvas.restore()
    }

    private fun advance() {
        if (!isAttachedToWindow || !isShown) { running = false; return }
        val now = android.os.SystemClock.uptimeMillis()
        if (running && lastFrame != 0L) offset += speed * ((now - lastFrame).coerceAtMost(64)) / 1000f
        lastFrame = now; running = true
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = 0L; invalidate() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) { lastFrame = 0L; invalidate() }
    }

    companion object { const val KEY = "dock_message" }
}
