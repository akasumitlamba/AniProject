package app.anihome.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.Locale

/** AniHome's own widgets. Updates are scoped to the Home activity's visible lifecycle. */
internal class BuiltInWidgets(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val attached = mutableSetOf<Card>()
    private var running = false
    private var registered = false
    private var batteryPercent: Int? = null
    private var charging = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
            update(if (intent?.action == Intent.ACTION_BATTERY_CHANGED) BuiltInWidgetPolicy.BATTERY else BuiltInWidgetPolicy.CLOCK)
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            update(BuiltInWidgetPolicy.CLOCK)
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    fun start() {
        if (running) return
        running = true
        syncUpdates()
    }
    private fun syncUpdates() {
        handler.removeCallbacks(ticker)
        val live = running && attached.any { it.kind != BuiltInWidgetPolicy.NOTE }
        if (!live) {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            registered = false
            return
        }
        if (attached.any { it.kind == BuiltInWidgetPolicy.CLOCK }) handler.post(ticker)
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        runCatching {
            val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            if (sticky?.action == Intent.ACTION_BATTERY_CHANGED) readBattery(sticky)
        }
    }
    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
    fun title(kind: String) = when (kind) {
        BuiltInWidgetPolicy.CLOCK -> "Clock & date"
        BuiltInWidgetPolicy.BATTERY -> "Battery"
        BuiltInWidgetPolicy.NOTE -> "Quick note"
        else -> "AniHome widget"
    }
    fun create(kind: String, note: String = "", onNote: (() -> Unit)? = null): View = Card(kind, note).apply {
        updateContent()
        if (kind == BuiltInWidgetPolicy.NOTE && onNote != null) {
            isClickable = true; isFocusable = true
            setOnClickListener { onNote() }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { attached += this@apply; syncUpdates(); updateContent() }
            override fun onViewDetachedFromWindow(view: View) { attached -= this@apply; syncUpdates() }
        })
    }
    private fun readBattery(intent: Intent) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        batteryPercent = if (scale > 0 && level >= 0) (100L * level / scale).toInt().coerceIn(0, 100) else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }
    private fun update(kind: String) { attached.filter { it.kind == kind }.forEach { it.updateContent() } }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private inner class Card(val kind: String, private val note: String) : LinearLayout(context) {
        private val heading = label(10f, 0xffbdbdbd.toInt()).apply {
            letterSpacing = 0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val main = label(if (kind == BuiltInWidgetPolicy.NOTE) 16f else 40f, Color.WHITE)
        private val detail = label(12f, 0xffbdbdbd.toInt())
        private val battery = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(0xffdddddd.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x33444444)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ArtworkUi.surface(context, 0xe617191d.toInt()).apply { setStroke(0, 0) }
            clipToOutline = true
            elevation = dp(2).toFloat()
            heading.text = when (kind) {
                BuiltInWidgetPolicy.CLOCK -> "Clock"
                BuiltInWidgetPolicy.BATTERY -> "Battery"
                else -> "Note"
            }
            if (kind != BuiltInWidgetPolicy.CLOCK) addView(heading, LayoutParams(-1, -2))
            if (kind == BuiltInWidgetPolicy.NOTE) {
                main.maxLines = 6
                main.ellipsize = android.text.TextUtils.TruncateAt.END
                main.gravity = Gravity.TOP
                addView(main, LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(6) })
            } else {
                main.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                main.maxLines = 1
                main.setAutoSizeTextTypeUniformWithConfiguration(20, 48, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                addView(main, LayoutParams(-1, 0, 1f))
            }
            if (kind == BuiltInWidgetPolicy.BATTERY) addView(battery, LayoutParams(-1, dp(5)).apply { bottomMargin = dp(8) })
            detail.maxLines = 2
            addView(detail, LayoutParams(-1, -2))
        }
        fun updateContent() {
            val now = Date()
            when (kind) {
                BuiltInWidgetPolicy.CLOCK -> {
                    main.text = DateFormat.getTimeFormat(context).format(now)
                    detail.text = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE d MMM"), now)
                    contentDescription = "${main.text}, ${detail.text}"
                }
                BuiltInWidgetPolicy.BATTERY -> {
                    main.text = batteryPercent?.let { "$it%" } ?: "—"
                    detail.text = when { batteryPercent == null -> "Battery status unavailable"; batteryPercent == 100 && charging -> "Fully charged"; charging -> "Charging"; else -> "On battery" }
                    battery.progress = batteryPercent ?: 0
                    contentDescription = "Battery ${main.text}, ${detail.text}"
                }
                else -> {
                    main.text = note.ifBlank { "Tap to write" }
                    main.setTextColor(if (note.isBlank()) 0xffb9ccdc.toInt() else Color.WHITE)
                    detail.visibility = View.GONE
                    contentDescription = "Quick note. ${if (note.isBlank()) "Tap to add a note" else note}."
                }
            }
        }
        private fun label(size: Float, color: Int) = TextView(context).apply {
            textSize = size; setTextColor(color)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
    }
}
