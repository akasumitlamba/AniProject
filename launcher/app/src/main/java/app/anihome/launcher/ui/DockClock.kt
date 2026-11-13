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

    private fun layout() {
        removeAllViews()
        orientation = VERTICAL
        gravity = if (vertical) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        time.textSize = if (vertical) 17f else 18f
        date.textSize = 11f
        time.gravity = gravity; date.gravity = gravity
        addView(time, LayoutParams(-2, -2))
        addView(date, LayoutParams(-2, -2).apply { topMargin = dp(1) })
        // Battery: slim bar + percentage on one line.
        listOf(barView, percent).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val battery = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        battery.addView(barView, LayoutParams(dp(if (vertical) 18 else 20), dp(8)))
        battery.addView(percent, LayoutParams(-2, -2).apply { marginStart = dp(4) })
        addView(battery, LayoutParams(-2, -2).apply { topMargin = dp(4) })
        setPadding(dp(if (vertical) 0 else 6), 0, dp(if (vertical) 0 else 6), 0)
    }

    fun start() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)?.let { receiver.onReceive(context, it) }
    }
    fun stop() { if (registered) runCatching { context.unregisterReceiver(receiver) }; registered = false }

    private inner class BatteryBar : android.graphics.drawable.Drawable() {
        var percentLevel = -1; var charging = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = RectF(bounds); val r = b.height() / 2
            paint.color = 0x40FFFFFF; canvas.drawRoundRect(b, r, r, paint)
            if (percentLevel < 0) return
            paint.color = when { charging -> 0xFF34C759.toInt(); percentLevel <= 15 -> 0xFFFF453A.toInt(); percentLevel <= 30 -> 0xFFFFD60A.toInt(); else -> 0xFFFFFFFF.toInt() }
            val fill = RectF(b.left, b.top, b.left + (b.width() * percentLevel / 100f).coerceAtLeast(b.height()), b.bottom)
            canvas.drawRoundRect(fill, r, r, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
