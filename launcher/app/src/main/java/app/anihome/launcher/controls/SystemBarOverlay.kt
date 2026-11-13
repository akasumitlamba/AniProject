package app.anihome.launcher.controls

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.anihome.launcher.R
import app.anihome.launcher.notifications.HomeNotifications
import app.anihome.launcher.notifications.NotificationPolicy
import app.anihome.launcher.ui.PanelActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * System-wide AniHome status bar and edge panel, drawn as accessibility overlays by
 * [HomeControlsService]. Accessibility overlays sit above Android's status bar on every
 * supported version, so AniHome's bar can cover and replace it without changing any system
 * setting: turning the option off, disabling the service or uninstalling AniHome simply
 * removes the overlay and Android's own bar is back. Nothing on screen is read.
 */
internal class SystemBarOverlay(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collector: Job? = null
    private var edgeView: View? = null
    private var barView: LinearLayout? = null
    private var battery: TextView? = null
    private var batteryRegistered = false

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun refresh() {
        remove()
        // Android keeps its own status bar. Over the lock screen nothing is drawn at all.
        if (locked()) { watchLock(); return }
        watchLock()
        // The invisible top catch-zone stays even while AniHome's bar steps aside (Home,
        // fullscreen apps), so a swipe down always opens AniHome's panel, not Android's shade.
        if (SystemBar.edgePanel(service)) addEdge(SystemBar.Edge.TOP)
    }

    private fun locked() = service.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    private var lockWatching = false
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }
    /** Screen off, unlock and screen on decide whether the swipe zone exists. */
    private fun watchLock() {
        if (lockWatching) return
        lockWatching = true
        ContextCompat.registerReceiver(service, lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    fun destroy() {
        remove(); scope.cancel()
        if (lockWatching) { runCatching { service.unregisterReceiver(lockReceiver) }; lockWatching = false }
    }

    private fun remove() {
        listOfNotNull(edgeView, barView).forEach { runCatching { windows.removeView(it) } }
        edgeView = null; barView = null; battery = null
        collector?.cancel(); collector = null
        if (batteryRegistered) { runCatching { service.unregisterReceiver(batteryReceiver) }; batteryRegistered = false }
    }

    private fun params(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(width, height, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
        this.gravity = gravity
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /** An inward swipe opens the panel; plain touches on the strip do nothing. */
    private fun swipeOpener(edge: SystemBar.Edge) = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.setTag(R.id.motion_entry_state, floatArrayOf(event.rawX, event.rawY))
            MotionEvent.ACTION_MOVE -> (view.getTag(R.id.motion_entry_state) as? FloatArray)?.let { start ->
                val dx = event.rawX - start[0]; val dy = event.rawY - start[1]
                val inward = when (edge) {
                    SystemBar.Edge.TOP -> dy > dp(20) && dy > abs(dx)
                    SystemBar.Edge.LEFT -> dx > dp(20) && dx > abs(dy)
                    SystemBar.Edge.RIGHT -> -dx > dp(20) && -dx > abs(dy)
                }
                if (inward) { view.setTag(R.id.motion_entry_state, null); openPanel(edge) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.setTag(R.id.motion_entry_state, null)
        }
        true
    }

    private fun openPanel(edge: SystemBar.Edge) = runCatching {
        service.startActivity(Intent(service, PanelActivity::class.java)
            .putExtra(PanelActivity.EXTRA_EDGE, edge.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
    }

    private fun addEdge(edge: SystemBar.Edge) {
        val view = View(service).apply { setOnTouchListener(swipeOpener(edge)); contentDescription = "Open AniHome panel" }
        val metrics = service.resources.displayMetrics
        val p = when (edge) {
            // Top: the whole width of the status-bar strip.
            SystemBar.Edge.TOP -> params(WindowManager.LayoutParams.MATCH_PARENT, statusBarHeight(), Gravity.TOP)
            // Side edges are not offered over other apps (Android's Back gesture owns them).
            SystemBar.Edge.LEFT, SystemBar.Edge.RIGHT -> return
        }
        runCatching { windows.addView(view, p); edgeView = view }
    }

    private var wifiIcon: ImageView? = null
    private var bluetoothIcon: ImageView? = null
    private val batteryIcon = HorizontalBattery()

    /**
     * Slim bar in AniHome's style: time and date, notification icons on the left; Bluetooth,
     * Wi-Fi signal, battery percentage and a drawn battery on the right. Opaque so Android's
     * icons underneath never show through; type and spacing follow the system bar's metrics.
     */
    private fun addStatusBar() {
        val height = statusBarHeight()
        val icon = (height * .52f).toInt().coerceIn(dp(12), dp(16))
        val bar = LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(18), 0)
            setBackgroundColor(BAR)
            setOnTouchListener(swipeOpener(SystemBar.Edge.TOP))
            contentDescription = "AniHome status bar. Swipe down for notifications and controls"
        }
        fun text(size: Float, color: Int = Color.WHITE, medium: Boolean = false) = TextView(service).apply {
            textSize = size; setTextColor(color); includeFontPadding = false
            typeface = android.graphics.Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
        }
        bar.addView(TextClock(service).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 13f; setTextColor(Color.WHITE); includeFontPadding = false
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        bar.addView(TextClock(service).apply {
            format12Hour = "EEE d MMM"; format24Hour = "EEE d MMM"; textSize = 12f; setTextColor(MUTED); includeFontPadding = false
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        val row = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(row, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(14) })
        fun stateIcon(res: Int, label: String) = ImageView(service).apply {
            setImageResource(res); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); contentDescription = label
        }.also { bar.addView(it, LinearLayout.LayoutParams(icon, icon).apply { marginEnd = dp(10) }) }
        bluetoothIcon = stateIcon(R.drawable.ic_bluetooth, "Bluetooth on")
        wifiIcon = stateIcon(R.drawable.ic_wifi, "Wi-Fi")
        val level = text(12f, medium = true)
        bar.addView(level, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(5) })
        bar.addView(ImageView(service).apply { setImageDrawable(batteryIcon) }, LinearLayout.LayoutParams(dp(22), dp(11)))
        battery = level
        followSystemBarVisibility(bar)
        runCatching { windows.addView(bar, params(WindowManager.LayoutParams.MATCH_PARENT, height, Gravity.TOP)); barView = bar }
        updateVisibility()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION); addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION); addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            @Suppress("DEPRECATION") addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
        }
        ContextCompat.registerReceiver(service, batteryReceiver, filter, ContextCompat.RECEIVER_EXPORTED)?.let(::showBattery)
        batteryRegistered = true
        showRadios()
        collector = scope.launch {
            HomeNotifications.badges.collect { active ->
                val keys = NotificationPolicy.visible(active.map {
                    NotificationPolicy.Entry(it.key, it.groupKey, it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0, it.isClearable)
                })
                row.removeAllViews()
                val apps = active.filter { it.key in keys }.distinctBy { it.packageName }
                apps.take(MAX_ICONS).forEach { sbn ->
                    val drawable = runCatching { sbn.notification.smallIcon?.loadDrawable(service.createPackageContext(sbn.packageName, 0)) }.getOrNull() ?: return@forEach
                    row.addView(ImageView(service).apply { setImageDrawable(drawable); imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt()) },
                        LinearLayout.LayoutParams(icon, icon).apply { marginEnd = dp(8) })
                }
                if (apps.size > MAX_ICONS) row.addView(text(11f, MUTED).apply { text = "+${apps.size - MAX_ICONS}" })
            }
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun showRadios() {
        val wifi = service.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        // Connectivity reports Wi-Fi reliably; WifiManager hides network IDs without location access.
        val connectivity = service.getSystemService(android.net.ConnectivityManager::class.java)
        val connected = runCatching {
            connectivity?.allNetworks?.any { connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true } == true
        }.getOrDefault(false)
        val level = runCatching { android.net.wifi.WifiManager.calculateSignalLevel(wifi!!.connectionInfo.rssi, 4) }.getOrDefault(3)
        wifiIcon?.apply {
            visibility = if (wifi?.isWifiEnabled == true) View.VISIBLE else View.GONE
            alpha = if (connected) .7f + .1f * level else .35f
            contentDescription = if (connected) "Wi-Fi connected" else "Wi-Fi on, not connected"
        }
        val bt = runCatching { service.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
        bluetoothIcon?.visibility = if (bt) View.VISIBLE else View.GONE
    }

    /** Horizontal battery: outline, nub and a level fill; green while charging, red when low. */
    private inner class HorizontalBattery : android.graphics.drawable.Drawable() {
        var percent = -1; var charging = false
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds; val stroke = dp(1).toFloat().coerceAtLeast(1.5f)
            val body = android.graphics.RectF(b.left + stroke / 2, b.top + stroke / 2, b.right - dp(2.5f) - stroke / 2, b.bottom - stroke / 2)
            val radius = body.height() * .28f
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = 0xB3FFFFFF.toInt()
            canvas.drawRoundRect(body, radius, radius, paint)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(android.graphics.RectF(body.right + stroke, b.exactCenterY() - body.height() * .2f, b.right.toFloat(), b.exactCenterY() + body.height() * .2f), stroke, stroke, paint)
            if (percent < 0) return
            val inset = stroke * 1.6f
            val inner = android.graphics.RectF(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset)
            inner.right = inner.left + inner.width() * percent / 100f
            paint.color = when { charging -> 0xFF34C759.toInt(); percent <= 15 -> 0xFFFF453A.toInt(); else -> Color.WHITE }
            canvas.drawRoundRect(inner, radius * .6f, radius * .6f, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    private fun dp(value: Float) = (value * service.resources.displayMetrics.density).toInt()

    /**
     * Mirror Android's own status-bar visibility: when the app in front goes fullscreen (video),
     * Android hides its bar and AniHome's bar steps aside too, then returns with it.
     */
    private var systemBarVisible = true
    /** Hidden when Android's bar is hidden (fullscreen apps) or an AniHome screen asks. */
    fun updateVisibility() {
        val bar = barView ?: return
        val visible = systemBarVisible && !SystemBar.suppressed
        bar.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
    @Suppress("DEPRECATION")
    private fun followSystemBarVisibility(bar: View) {
        fun show(visible: Boolean) { systemBarVisible = visible; updateVisibility() }
        if (Build.VERSION.SDK_INT >= 30) ViewCompat.setOnApplyWindowInsetsListener(bar) { _, insets ->
            show(insets.isVisible(WindowInsetsCompat.Type.statusBars())); insets
        } else bar.setOnSystemUiVisibilityChangeListener { flags -> show(flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_BATTERY_CHANGED) showBattery(intent) else showRadios() }
    }
    private fun showBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        if (level < 0) return
        val percent = level * 100 / scale.coerceAtLeast(1)
        batteryIcon.percent = percent
        batteryIcon.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        batteryIcon.invalidateSelf()
        battery?.text = "$percent%"
        barView?.contentDescription = "AniHome status bar, battery $percent%${if (batteryIcon.charging) ", charging" else ""}. Swipe down for notifications and controls"
    }

    private companion object {
        const val BAR = 0xFF0E0F13.toInt()
        const val MUTED = 0xFFA6A9B2.toInt()
        const val MAX_ICONS = 5
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else dp(24)
    }
}
