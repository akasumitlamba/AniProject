package app.anihome.launcher.controls

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.anihome.launcher.R
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

/**
 * AniHome's volume panel, shown instead of Android's when the volume keys are pressed: media
 * volume, then (expanded) ring, alarm and notification, plus one slider per app that is
 * playing sound. Per-app levels use Shizuku and are re-applied whenever an app starts new audio.
 * Calls and the lock screen keep Android's own panel.
 */
internal class VolumePanel(private val service: AccessibilityService) {
    companion object {
        fun enabled(context: Context) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE).getBoolean("volume_panel", false)
        fun setEnabled(context: Context, on: Boolean) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE).edit().putBoolean("volume_panel", on).apply()
    }
    private val audio = service.getSystemService(AudioManager::class.java)
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val levels = service.getSharedPreferences("app_volumes", Context.MODE_PRIVATE)
    private var root: LinearLayout? = null
    private var frame: android.widget.ScrollView? = null
    private var expanded = false
    private var radio: IRadioService? = null
    private val hide = Runnable { dismiss() }
    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    // --- Shizuku (shared user service) -------------------------------------------------------
    private val args = Shizuku.UserServiceArgs(ComponentName(service, RadioService::class.java))
        .daemon(false).processNameSuffix("radios").tag("anihome-radios").version(24)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { radio = IRadioService.Stub.asInterface(binder); reapplyAll() }
        override fun onServiceDisconnected(name: ComponentName) { radio = null }
    }
    private fun connect() {
        if (radio?.asBinder()?.isBinderAlive == true) return
        runCatching {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Shizuku.bindUserService(args, connection)
        }
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) = reapplyAll()
    }
    fun start() { runCatching { audio.registerAudioPlaybackCallback(playback, handler) }; connect() }
    fun stop() {
        dismiss(); runCatching { audio.unregisterAudioPlaybackCallback(playback) }
        runCatching { Shizuku.unbindUserService(args, connection, false) }
    }
    /** New players start at full volume; push saved per-app levels back onto them. */
    private fun reapplyAll() {
        val service = radio ?: return
        Thread {
            runCatching {
                val players = org.json.JSONArray(service.activePlayers())
                for (i in 0 until players.length()) {
                    val p = players.getJSONObject(i)
                    val saved = levels.getInt(p.getString("package"), 100)
                    if (saved < 100) service.setAppVolume(p.getInt("uid"), saved / 100f)
                }
            }
        }.start()
    }

    // --- keys --------------------------------------------------------------------------------
    /** Returns true when AniHome handled the key (so Android's panel stays hidden). */
    fun onKey(event: KeyEvent): Boolean {
        if (!enabled(service)) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (audio.mode != AudioManager.MODE_NORMAL) return false // calls: Android's own panel
        if (service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val stream = activeStream()
            audio.adjustStreamVolume(stream, if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            show()
        }
        return true
    }
    private fun activeStream() = if (audio.isMusicActive) AudioManager.STREAM_MUSIC else AudioManager.STREAM_MUSIC

    // --- panel -------------------------------------------------------------------------------
    private fun show() {
        handler.removeCallbacks(hide); handler.postDelayed(hide, 3500)
        if (root != null) { refresh(); return }
        connect()
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(12), dp(14))
            background = GradientDrawable().apply { setColor(0xF0181A20.toInt()); cornerRadius = dp(26).toFloat() }
            elevation = dp(10).toFloat()
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss(); false }
        }
        root = panel
        refresh()
        // Fixed anchor on the right edge, same in every orientation: the top never moves and the
        // panel only grows downward (scrolling past the available height), so it never jumps.
        val metrics = service.resources.displayMetrics
        val inset = dp(16)
        val top = statusBarHeight() + (metrics.heightPixels * 0.12f).toInt()
        val maxHeight = metrics.heightPixels - top - navigationInset() - inset
        val width = minOf(dp(300), metrics.widthPixels - 2 * inset)
        val scroll = object : android.widget.ScrollView(service) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(maxHeight, android.view.View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(panel); clipToPadding = false }
        frame = scroll
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.TOP; x = inset + navigationSideInset(); y = top }
        runCatching { windows.addView(scroll, params) }
        scroll.alpha = 0f; scroll.translationX = dp(40).toFloat()
        scroll.animate().alpha(1f).translationX(0f).setDuration(180).start()
    }

    fun dismiss() {
        handler.removeCallbacks(hide)
        val window = frame ?: return
        root = null; frame = null; expanded = false
        window.animate().alpha(0f).translationX(dp(40).toFloat()).setDuration(150).withEndAction { runCatching { windows.removeView(window) } }.start()
    }

    private fun dimension(name: String) = service.resources.getIdentifier(name, "dimen", "android").let { if (it > 0) service.resources.getDimensionPixelSize(it) else 0 }
    private fun statusBarHeight() = dimension("status_bar_height")
    private val landscape get() = service.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** Phones put the navigation bar on the side in landscape; keep clear of it. */
    private fun navigationSideInset() = if (landscape && service.resources.configuration.smallestScreenWidthDp < 600) dimension("navigation_bar_width") else 0
    private fun navigationInset() = if (landscape && service.resources.configuration.smallestScreenWidthDp < 600) 0 else dimension("navigation_bar_height")

    private fun keepOpen() { handler.removeCallbacks(hide); handler.postDelayed(hide, 3500) }

    private fun refresh() {
        val panel = root ?: return
        panel.removeAllViews()
        val head = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(service).apply {
            text = "Volume"; textSize = 16f; setTextColor(-1); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ImageButton(service).apply {
            setImageResource(R.drawable.ic_expand_more); rotation = if (expanded) 180f else 0f; background = null
            imageTintList = ColorStateList.valueOf(-1); contentDescription = if (expanded) "Fewer" else "All volumes and apps"
            setOnClickListener { expanded = !expanded; refresh(); keepOpen() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(head)
        streamRow(panel, "Media", R.drawable.ic_cc_volume, AudioManager.STREAM_MUSIC)
        if (!expanded) return
        section(panel, "System")
        streamRow(panel, "Ring", R.drawable.ic_notifications, AudioManager.STREAM_RING)
        streamRow(panel, "Notifications", R.drawable.ic_chat, AudioManager.STREAM_NOTIFICATION)
        streamRow(panel, "Alarm", R.drawable.ic_cc_lock, AudioManager.STREAM_ALARM)
        appRows(panel)
    }

    private fun streamRow(panel: LinearLayout, label: String, icon: Int, stream: Int) {
        slider(panel, label, service.getDrawable(icon), audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream)) { value ->
            runCatching { audio.setStreamVolume(stream, value, 0) }
        }
    }

    private fun appRows(panel: LinearLayout) {
        val rpc = radio
        if (rpc == null) {
            panel.addView(TextView(service).apply {
                text = if (ShizukuGate.state(service) == ShizukuGate.State.READY) "Connecting to Shizuku for app volumes…"
                    else "Per-app volume needs Shizuku. Set it up in AniHome Settings → Sound."
                textSize = 12f; setTextColor(0xFFB3B6BE.toInt()); setPadding(0, dp(12), 0, dp(4))
            })
            connect(); return
        }
        val players = runCatching { org.json.JSONArray(rpc.activePlayers()) }.getOrNull() ?: return
        if (players.length() == 0) {
            panel.addView(TextView(service).apply { text = "No apps are playing sound"; textSize = 12f; setTextColor(0xFFB3B6BE.toInt()); setPadding(0, dp(12), 0, dp(4)) })
            return
        }
        section(panel, "Apps")
        val pm = service.packageManager
        for (i in 0 until players.length()) {
            val p = players.getJSONObject(i); val pkg = p.getString("package"); val uid = p.getInt("uid")
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            slider(panel, name, icon, 100, levels.getInt(pkg, 100), tint = false) { value ->
                levels.edit().putInt(pkg, value).apply()
                Thread { runCatching { rpc.setAppVolume(uid, value / 100f) } }.start()
            }
        }
    }

    /** One row: icon + name with the level on the right, and a full-width slider underneath. */
    private fun slider(panel: LinearLayout, label: String, icon: android.graphics.drawable.Drawable?, max: Int, value: Int, tint: Boolean = true, changed: (Int) -> Unit) {
        val row = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val top = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(service).apply {
            setImageDrawable(icon); if (tint) imageTintList = ColorStateList.valueOf(-1); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        top.addView(TextView(service).apply { text = label; textSize = 13f; setTextColor(-1); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        val level = TextView(service).apply { textSize = 12f; setTextColor(0xFFB3B6BE.toInt()); gravity = Gravity.END }
        fun show(v: Int) { level.text = "${(v * 100f / max.coerceAtLeast(1)).roundToInt()}%" }
        show(value)
        top.addView(level, LinearLayout.LayoutParams(dp(44), -2))
        row.addView(top)
        row.addView(SeekBar(service).apply {
            this.max = max; progress = value; contentDescription = label
            setPadding(dp(10), 0, dp(10), 0)
            progressTintList = ColorStateList.valueOf(0xFF8AB4FF.toInt()); thumbTintList = ColorStateList.valueOf(-1)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { show(p); if (fromUser) { changed(p); keepOpen() } }
                override fun onStartTrackingTouch(bar: SeekBar) = keepOpen()
                override fun onStopTrackingTouch(bar: SeekBar) = keepOpen()
            })
        }, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(2) })
        panel.addView(row)
    }

    private fun section(panel: LinearLayout, title: String) {
        panel.addView(View(service).apply { setBackgroundColor(0x1AFFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        panel.addView(TextView(service).apply { text = title.uppercase(); textSize = 11f; letterSpacing = .08f; setTextColor(0xFF9EA2AB.toInt()) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
    }

    @Suppress("unused") private fun percent(v: Int, max: Int) = (v * 100f / max.coerceAtLeast(1)).roundToInt()
}
