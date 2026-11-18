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
    /** Lies on its side (fills from the left) when the Control Center tile is wider than tall. */
    var horizontal = false
        set(v) { field = v; invalidate() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ControlCenter.SURFACE_HIGH }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val rect = RectF()
    private var lastX = 0f

    init { contentDescription = label; isFocusable = true; isClickable = true }

    private val shape = android.graphics.Path()
    private val level = android.graphics.Path()

    /**
     * Rounded pill. Both the track and the level are plain anti-aliased shapes (the level is the
     * pill cut at the current height), so the corners stay smooth and the level stays bright.
     */
    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = minOf(width, height) * .38f
        // Glass track: translucent with a soft light from the top and a hairline rim.
        track.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), 0x38FFFFFF, 0x1AFFFFFF, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, track)
        val fraction = if (max == 0) 0f else value.toFloat() / max
        val top = height * (1f - fraction)
        val right = width * fraction
        if (fraction > 0f) {
            shape.reset(); shape.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            level.reset()
            if (horizontal) level.addRect(0f, 0f, right, height.toFloat(), android.graphics.Path.Direction.CW)
            else level.addRect(0f, top, width.toFloat(), height.toFloat(), android.graphics.Path.Direction.CW)
            level.op(shape, android.graphics.Path.Op.INTERSECT)
            canvas.drawPath(level, fill)
        }
        rim.strokeWidth = density; rim.color = 0x40FFFFFF
        rect.inset(density / 2, density / 2); canvas.drawRoundRect(rect, radius, radius, rim)
        icon?.let {
            val size = (20 * density).toInt()
            val left = if (horizontal) (16 * density).toInt() else (width - size) / 2
            val bottom = if (horizontal) (height + size) / 2 else height - (16 * density).toInt()
            it.setBounds(left, bottom - size, left + size, bottom)
            // Dark icon over the light fill, light icon over the track.
            val covered = if (horizontal) left + size / 2 <= right else bottom - size / 2 >= top
            if (tintIcon) it.setTint(if (covered) 0xFF15171C.toInt() else 0xFFFFFFFF.toInt())
            it.draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); onStart?.invoke(); isPressed = true; lastX = event.x; set(event.y) }
            MotionEvent.ACTION_MOVE -> { lastX = event.x; set(event.y) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isPressed = false; onEnd?.invoke(); performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun set(y: Float) {
        val next = (if (horizontal) (lastX / width).coerceIn(0f, 1f) * max else (1f - (y / height).coerceIn(0f, 1f)) * max).roundToInt()
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
