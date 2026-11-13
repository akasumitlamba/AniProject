package app.anihome.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
internal class VerticalSlider(context: Context, private val icon: Drawable?, label: String) : View(context) {
    var max = 100
    var value = 0
        set(v) { field = v.coerceIn(0, max); invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ControlCenter.SURFACE_HIGH }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val clip = Path()
    private val rect = RectF()

    init { contentDescription = label; isFocusable = true; isClickable = true }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = minOf(width, height) * .32f
        clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        canvas.drawRect(rect, track)
        val level = if (max == 0) 0f else value.toFloat() / max
        val top = height * (1f - level)
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fill)
        icon?.let {
            val size = (20 * density).toInt(); val left = (width - size) / 2; val bottom = height - (14 * density).toInt()
            it.setBounds(left, bottom - size, left + size, bottom)
            // Dark icon over the light fill, light icon over the track.
            it.setTint(if (bottom - size / 2 >= top) 0xFF15171C.toInt() else 0xFFFFFFFF.toInt())
            it.draw(canvas)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); onStart?.invoke(); isPressed = true; set(event.y) }
            MotionEvent.ACTION_MOVE -> set(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isPressed = false; performClick() }
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
