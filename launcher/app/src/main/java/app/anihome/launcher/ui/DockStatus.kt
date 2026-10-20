package app.anihome.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.anihome.launcher.R

/** Compact, live power indicators. Only battery level and USB connection state are read. */
internal class DockStatus(context: Context) : LinearLayout(context) {
    var onPowerIndicatorsChanged: ((Boolean) -> Unit)? = null
        set(value) { field = value; value?.invoke(charging || connected) }
    private val batteryIcon = BatteryIconDrawable(resources.displayMetrics.density)
    private val percent = TextView(context).apply {
        text = "—"; textSize = 9f; setTextColor(0xffdddddd.toInt()); maxLines = 1
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val chargingIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_charging)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var charging = false
    private val usb = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var battery = "Unavailable"
    private var connected = false
    private var usbStateKnown = false
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
                val percentage = if (level >= 0 && scale > 0) (level.toLong() * 100 / scale).coerceIn(0, 100).toInt() else -1
                batteryIcon.update(percentage)
                battery = if (percentage >= 0) "$percentage%" else "Unavailable"
                percent.text = if (battery == "Unavailable") "—" else battery
                charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) == BatteryManager.BATTERY_STATUS_CHARGING
                if (!usbStateKnown) connected = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) and BatteryManager.BATTERY_PLUGGED_USB != 0
            } else if (intent.action == USB_STATE) {
                connected = intent.getBooleanExtra("connected", false); usbStateKnown = true
            }
            render()
        }
    }
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val icons = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            addView(ImageView(context).apply {
                setImageDrawable(batteryIcon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(9), dp(14)))
            addView(chargingIcon, LayoutParams(dp(8), dp(14)))
            addView(usb, LayoutParams(dp(10), dp(12)))
        }
        addView(icons, LayoutParams(-2, dp(14)))
        addView(percent, LayoutParams(-2, -2))
        render()
    }
    private fun dp(value: Int) = ArtworkUi.dp(context, value)
    private fun render() {
        chargingIcon.visibility = if (charging) View.VISIBLE else View.GONE
        usb.setImageResource(R.drawable.ic_usb)
        usb.visibility = if (connected) View.VISIBLE else View.GONE
        contentDescription = "Battery $battery, ${if (charging) "charging" else "not charging"}, USB ${if (connected) "connected" else "disconnected"}"
        tooltipText = contentDescription
        onPowerIndicatorsChanged?.invoke(charging || connected)
    }
    fun start() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(USB_STATE) }
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }
    fun stop() {
        if (registered) { context.applicationContext.unregisterReceiver(receiver); registered = false }
    }
    private companion object { const val USB_STATE = "android.hardware.usb.action.USB_STATE" }
}
