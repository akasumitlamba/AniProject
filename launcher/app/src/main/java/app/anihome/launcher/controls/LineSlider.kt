package app.anihome.launcher.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

/**
 * Android-style vertical volume line: a thin track, the level drawn over it from the bottom,
 * and a round thumb. Drag anywhere along it; a tap jumps to that level.
 */
internal class LineSlider(context: Context, label: String) : View(context) {
    var max = 100
    var value = 0
        set(v) { field = v.coerceIn(0, max); invalidate() }
    var enabledLook = true
        set(v) { field = v; invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val line = 4f * density
    private val thumb = 7f * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; strokeWidth = line; strokeCap = Paint.Cap.ROUND }
    private val level = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = line; strokeCap = Paint.Cap.ROUND }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    init { contentDescription = label; isFocusable = true; isClickable = true }

    private val top get() = thumb + paddingTop
    private val bottom get() = height - thumb - paddingBottom

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val fraction = if (max == 0) 0f else value.toFloat() / max
        val y = bottom - (bottom - top) * fraction
        val active = if (enabledLook) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
        level.color = active; knob.color = active
        canvas.drawLine(x, top, x, bottom, track)
        if (fraction > 0f) canvas.drawLine(x, y, x, bottom, level)
        canvas.drawCircle(x, y, thumb, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); onStart?.invoke(); set(event.y) }
            MotionEvent.ACTION_MOVE -> set(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onEnd?.invoke(); performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun set(y: Float) {
        val span = (bottom - top).coerceAtLeast(1f)
        val next = ((1f - ((y - top) / span).coerceIn(0f, 1f)) * max).roundToInt()
        if (next != value) { value = next; onChange?.invoke(next) }
    }

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
