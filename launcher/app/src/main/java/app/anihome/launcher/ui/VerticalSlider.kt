package app.anihome.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

/**
 * Tall pill slider (Control Center style): the fill rises from the bottom, the icon sits near the
 * bottom and inverts over the fill. Drag up or down anywhere on it; tap jumps to that level.
 */
internal class VerticalSlider(context: Context, private val icon: Drawable?, label: String, private val tintIcon: Boolean = true) : View(context) {
    var max = 100
    var value = 0
        set(v) { field = v.coerceIn(0, max); invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ControlCenter.SURFACE_HIGH }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val rect = RectF()

    init { contentDescription = label; isFocusable = true; isClickable = true }

    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val trackIn = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN) }
    private val fillAtop = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP) }

    /**
     * A capsule (fully round ends, like iOS) drawn as an anti-aliased mask: the track and the
     * level fill are painted inside it, so the edges stay smooth at any size.
     */
    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = minOf(width, height) / 2f
        val layer = canvas.saveLayer(rect, null)
        canvas.drawRoundRect(rect, radius, radius, mask)
        trackIn.color = track.color
        canvas.drawRect(rect, trackIn)
        val level = if (max == 0) 0f else value.toFloat() / max
        val top = height * (1f - level)
        fillAtop.color = fill.color
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fillAtop)
        canvas.restoreToCount(layer)
        icon?.let {
            val size = (20 * density).toInt(); val left = (width - size) / 2; val bottom = height - (16 * density).toInt()
            it.setBounds(left, bottom - size, left + size, bottom)
            // Dark icon over the light fill, light icon over the track.
            if (tintIcon) it.setTint(if (bottom - size / 2 >= top) 0xFF15171C.toInt() else 0xFFFFFFFF.toInt())
            it.draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); onStart?.invoke(); isPressed = true; set(event.y) }
            MotionEvent.ACTION_MOVE -> set(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isPressed = false; onEnd?.invoke(); performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun set(y: Float) {
        val next = ((1f - (y / height).coerceIn(0f, 1f)) * max).roundToInt()
        if (next != value) { value = next; onChange?.invoke(next) }
    }

    // Accessibility: behaves like a seek bar with scroll actions.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, max.toFloat(), value.toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val step = (max / 10).coerceAtLeast(1)
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { value += step; onChange?.invoke(value); true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { value -= step; onChange?.invoke(value); true }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }
}
