package app.anihome.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Dock clock: one tidy block with the time, the date, and a slim battery bar with its
 * percentage. Stacked and centred in the side dock; left-aligned in the bottom dock.
 * Tapping it opens notifications. Only battery level and charging state are read.
 */
internal class DockClock(context: Context) : LinearLayout(context) {
    var vertical = false
        set(value) { field = value; layout() }
    private val time = TextClock(context).apply {
        format12Hour = "h:mm"; format24Hour = "HH:mm"; setTextColor(-1); includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val date = TextClock(context).apply {
        format12Hour = "EEE d"; format24Hour = "EEE d"; setTextColor(0xFFC9CCD3.toInt()); includeFontPadding = false
    }
    private val percent = TextView(context).apply { setTextColor(0xFFC9CCD3.toInt()); includeFontPadding = false; textSize = 10f }
    private val bar = BatteryBar()
    private val barView = View(context).apply { background = bar; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            bar.percentLevel = if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1
            bar.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            bar.invalidateSelf()
            percent.text = if (bar.percentLevel >= 0) "${bar.percentLevel}%" else "—"
            contentDescription = "Battery ${percent.text}${if (bar.charging) ", charging" else ""}. Opens notifications"
        }
    }

    init { isClickable = true; isFocusable = true; layout() }
    private fun dp(v: Int) = ArtworkUi.dp(context, v)

    // A getter, not a stored property: layout() already runs from init, above this line.
    private val prefs get() = context.getSharedPreferences("anihome", Context.MODE_PRIVATE)
    /** Each part can be hidden in AniHome Settings → Dock. */
    val showsAnything get() = prefs.getBoolean(SHOW_TIME, true) || prefs.getBoolean(SHOW_DATE, true) || prefs.getBoolean(SHOW_BATTERY, true)

    fun layout() {
        removeAllViews()
        val showTime = prefs.getBoolean(SHOW_TIME, true); val showDate = prefs.getBoolean(SHOW_DATE, true); val showBattery = prefs.getBoolean(SHOW_BATTERY, true)
        orientation = VERTICAL
        gravity = if (vertical) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        time.textSize = if (vertical) 17f else 18f
        date.textSize = 11f
        time.gravity = gravity; date.gravity = gravity
        listOf(time, date, barView, percent).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        if (showTime) addView(time, LayoutParams(-2, -2))
        // Battery icon + percentage on one line.
        val battery = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        battery.addView(barView, LayoutParams(dp(22), dp(11)))
        battery.addView(percent, LayoutParams(-2, -2).apply { marginStart = dp(4) })
        // With the time hidden, the date takes its larger size so the block still reads well.
        if (!showTime) date.textSize = if (vertical) 13f else 14f
        if (vertical) {
            // Side dock: narrow, so time / date / battery stack.
            if (showDate) addView(date, LayoutParams(-2, -2).apply { topMargin = dp(if (showTime) 1 else 0) })
            if (showBattery) addView(battery, LayoutParams(-2, -2).apply { topMargin = dp(4) })
        } else {
            // Bottom dock: two lines (time, then date · battery) so nothing touches the dock edge.
            val second = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            if (showDate) second.addView(date, LayoutParams(-2, -2))
            if (showBattery) second.addView(battery, LayoutParams(-2, -2).apply { if (showDate) marginStart = dp(8) })
            if (second.childCount > 0) addView(second, LayoutParams(-2, -2).apply { if (showTime) topMargin = dp(3) })
        }
        setPadding(dp(if (vertical) 0 else 6), 0, dp(if (vertical) 0 else 6), 0)
    }

    fun start() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)?.let { receiver.onReceive(context, it) }
    }
    fun stop() { if (registered) runCatching { context.unregisterReceiver(receiver) }; registered = false }

    companion object {
        const val SHOW_TIME = "dock_show_time"
        const val SHOW_DATE = "dock_show_date"
        const val SHOW_BATTERY = "dock_show_battery"
    }

    private inner class BatteryBar : android.graphics.drawable.Drawable() {
        var percentLevel = -1; var charging = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bolt = android.graphics.Path()
        /** A real battery: outlined body, terminal cap, level fill inside, bolt while charging. */
        override fun draw(canvas: Canvas) {
            val d = context.resources.displayMetrics.density
            val stroke = 1.3f * d; val cap = 2f * d; val gap = 1.3f * d
            val body = RectF(bounds.left + stroke / 2, bounds.top + stroke / 2, bounds.right - cap - stroke / 2, bounds.bottom - stroke / 2)
            val r = body.height() * .3f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = 0xB3FFFFFF.toInt()
            canvas.drawRoundRect(body, r, r, paint)
            paint.style = Paint.Style.FILL
            val capHeight = body.height() * .42f
            canvas.drawRoundRect(RectF(body.right + stroke / 2, body.centerY() - capHeight / 2, bounds.right.toFloat(), body.centerY() + capHeight / 2), cap / 2, cap / 2, paint)
            if (percentLevel < 0) return
            paint.color = when { charging -> 0xFF34C759.toInt(); percentLevel <= 15 -> 0xFFFF453A.toInt(); percentLevel <= 30 -> 0xFFFFD60A.toInt(); else -> 0xFFFFFFFF.toInt() }
            val inner = RectF(body.left + stroke / 2 + gap, body.top + stroke / 2 + gap, body.right - stroke / 2 - gap, body.bottom - stroke / 2 - gap)
            val fill = RectF(inner.left, inner.top, inner.left + inner.width() * (percentLevel.coerceIn(4, 100) / 100f), inner.bottom)
            canvas.drawRoundRect(fill, r * .6f, r * .6f, paint)
            if (charging) {
                val cx = body.centerX(); val cy = body.centerY(); val h = body.height() * .78f; val w = h * .55f
                bolt.reset()
                bolt.moveTo(cx + w * .15f, cy - h / 2); bolt.lineTo(cx - w / 2, cy + h * .06f); bolt.lineTo(cx - w * .02f, cy + h * .06f)
                bolt.lineTo(cx - w * .15f, cy + h / 2); bolt.lineTo(cx + w / 2, cy - h * .06f); bolt.lineTo(cx + w * .02f, cy - h * .06f); bolt.close()
                paint.color = 0xFF15171C.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f * d; canvas.drawPath(bolt, paint)
                paint.style = Paint.Style.FILL; paint.color = -1; canvas.drawPath(bolt, paint)
            }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
