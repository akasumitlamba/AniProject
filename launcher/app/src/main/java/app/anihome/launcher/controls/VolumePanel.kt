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
    private var frame: android.widget.FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var radio: IRadioService? = null
    private val hide = Runnable { dismiss() }
    private val relist = Runnable { if (root != null && expanded && !dragging) fetchPlayers() }
    /** Apps playing sound, fetched off the main thread (the lookup can take a moment). */
    private var players: org.json.JSONArray? = null
    private fun fetchPlayers() {
        val rpc = radio ?: return
        Thread {
            val list = runCatching { org.json.JSONArray(rpc.activePlayers()) }.getOrNull()
            handler.post { players = list ?: org.json.JSONArray(); if (root != null && expanded && !dragging) refresh() }
        }.start()
    }
    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    // --- Shizuku (shared user service) -------------------------------------------------------
    private val args = Shizuku.UserServiceArgs(ComponentName(service, RadioService::class.java))
        .daemon(false).processNameSuffix("radios").tag("anihome-radios").version(26)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            radio = IRadioService.Stub.asInterface(binder); reapplyAll()
            handler.post { if (root != null && expanded) fetchPlayers() } // App controls appear as soon as they can.
        }
        override fun onServiceDisconnected(name: ComponentName) { radio = null }
    }
    private fun connect() {
        if (radio?.asBinder()?.isBinderAlive == true) return
        runCatching {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Shizuku.bindUserService(args, connection)
        }
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            reapplyAll()
            // An app started or stopped playing while the panel is open: update its sliders.
            handler.removeCallbacks(relist)
            if (root != null && expanded) handler.postDelayed(relist, 400)
        }
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
                    if (!p.optBoolean("volume", true)) continue
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
    private val timeout = 3000L
    private var dragging = false

    /**
     * Vertical panel on the right edge, centred vertically in every orientation: one tall slider
     * per sound, side by side. Collapsed shows media only; expanded adds ring, notifications,
     * alarm and each app playing sound. Taps outside close it; it also hides after [timeout].
     */
    private fun show() {
        keepOpen()
        if (root != null) { refresh(); return }
        connect()
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(0xF5141519.toInt()); cornerRadius = dp(34).toFloat(); setStroke(dp(1), 0x1FFFFFFF) }
            elevation = dp(10).toFloat()
        }
        root = panel
        val window = object : android.widget.FrameLayout(service) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); return true }
                return super.dispatchTouchEvent(e)
            }
        }.apply { clipToPadding = false; setPadding(dp(12), dp(12), dp(12), dp(12)); addView(panel) }
        frame = window
        refresh()
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(4) + navigationSideInset(); title = "AniHome volume panel" }
        windowParams = params
        fitWindow(attached = false)
        if (runCatching { windows.addView(window, params) }.isFailure) { root = null; frame = null; windowParams = null; return }
        window.alpha = 0f; window.translationX = dp(40).toFloat()
        window.animate().alpha(1f).translationX(0f).setDuration(180).start()
    }

    /**
     * Sizes the overlay window to exactly what the panel needs, every time its content changes
     * (expanding adds columns). Relying on WRAP_CONTENT alone could leave the window at its
     * collapsed size and cut the expanded sliders off.
     */
    private fun fitWindow(attached: Boolean = true) {
        val window = frame ?: return
        val params = windowParams ?: return
        val metrics = service.resources.displayMetrics
        window.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels - dp(8), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels - dp(8), View.MeasureSpec.AT_MOST))
        if (params.width == window.measuredWidth && params.height == window.measuredHeight) return
        params.width = window.measuredWidth
        params.height = window.measuredHeight
        if (attached) runCatching { windows.updateViewLayout(window, params) }
    }

    fun dismiss() {
        handler.removeCallbacks(hide); handler.removeCallbacks(relist)
        val window = frame ?: return
        root = null; frame = null; windowParams = null; expanded = false; dragging = false
        window.animate().cancel()
        window.animate().alpha(0f).translationX(dp(40).toFloat()).setDuration(150).start()
        // Remove on a timer rather than an animation callback, so it always goes away.
        handler.postDelayed({ runCatching { windows.removeViewImmediate(window) } }, 170)
    }

    private fun dimension(name: String) = service.resources.getIdentifier(name, "dimen", "android").let { if (it > 0) service.resources.getDimensionPixelSize(it) else 0 }
    private val landscape get() = service.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** Phones put the navigation bar on the side in landscape; keep clear of it. */
    private fun navigationSideInset() = if (landscape && service.resources.configuration.smallestScreenWidthDp < 600) dimension("navigation_bar_width") else 0

    private fun keepOpen() { handler.removeCallbacks(hide); if (!dragging) handler.postDelayed(hide, timeout) }

    private fun refresh() {
        val panel = root ?: return
        panel.removeAllViews()
        val metrics = service.resources.displayMetrics
        val sliderHeight = minOf(dp(210), (metrics.heightPixels * .36f).toInt())
        if (expanded) panel.addView(header(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val columns = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        streamColumn(columns, "Media", R.drawable.ic_cc_volume, AudioManager.STREAM_MUSIC, sliderHeight)
        var note: String? = null
        if (expanded) {
            streamColumn(columns, "Ring", R.drawable.ic_cc_ring, AudioManager.STREAM_RING, sliderHeight)
            streamColumn(columns, "Alerts", R.drawable.ic_notifications, AudioManager.STREAM_NOTIFICATION, sliderHeight)
            streamColumn(columns, "Alarm", R.drawable.ic_alarm, AudioManager.STREAM_ALARM, sliderHeight)
            note = appColumns(columns, sliderHeight)
        }
        // Many apps: scroll sideways inside the panel rather than running off screen.
        val widest = metrics.widthPixels - dp(80)
        val scroller = object : android.widget.HorizontalScrollView(service) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(View.MeasureSpec.makeMeasureSpec(widest, View.MeasureSpec.AT_MOST), h)
        }.apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(columns) }
        panel.addView(scroller)
        note?.let {
            panel.addView(TextView(service).apply {
                text = it; textSize = 11.5f; setTextColor(0xFFB3B6BE.toInt()); gravity = Gravity.CENTER; maxWidth = dp(260)
                setLineSpacing(0f, 1.1f)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL })
        }
        if (expanded) panel.addView(ringerChips(), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
        panel.addView(ImageButton(service).apply {
            setImageResource(R.drawable.ic_expand_more); rotation = if (expanded) -90f else 90f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x24FFFFFF) }
            imageTintList = ColorStateList.valueOf(-1); contentDescription = if (expanded) "Fewer volumes" else "All volumes and apps"
            setOnClickListener { expanded = !expanded; if (expanded) { players = null; fetchPlayers() }; refresh(); keepOpen() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
        if (expanded) { scroller.alpha = 0f; scroller.animate().alpha(1f).setDuration(140).start() }
        fitWindow()
    }

    /** "Sound" title with a shortcut to Android's sound settings (expanded only). */
    private fun header() = LinearLayout(service).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, 0, 0)
        addView(TextView(service).apply {
            text = "Sound"; textSize = 16f; setTextColor(-1)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageButton(service).apply {
            setImageResource(R.drawable.ic_settings); background = null; imageTintList = ColorStateList.valueOf(0xFFDADCE2.toInt())
            contentDescription = "Sound settings"; setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                dismiss()
                runCatching { service.startActivity(android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    /** Ring · Vibrate · Silent in one tap (silent may need Do-not-disturb access). */
    private fun ringerChips() = LinearLayout(service).apply {
        background = GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = dp(20).toFloat() }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        val modes = listOf(Triple(AudioManager.RINGER_MODE_NORMAL, R.drawable.ic_cc_ring, "Ring"),
            Triple(AudioManager.RINGER_MODE_VIBRATE, R.drawable.ic_cc_vibrate, "Vibrate"),
            Triple(AudioManager.RINGER_MODE_SILENT, R.drawable.ic_cc_silent, "Silent"))
        modes.forEach { (mode, icon, label) ->
            val on = audio.ringerMode == mode
            addView(ImageButton(service).apply {
                setImageResource(icon); contentDescription = label
                imageTintList = ColorStateList.valueOf(if (on) 0xFF0B1220.toInt() else -1)
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (on) 0xFF8AB4FF.toInt() else 0) }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val applied = runCatching { audio.ringerMode = mode }.isSuccess
                    if (!applied && mode == AudioManager.RINGER_MODE_SILENT) {
                        dismiss()
                        runCatching { service.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return@setOnClickListener
                    }
                    refresh(); keepOpen()
                }
            }, LinearLayout.LayoutParams(dp(52), dp(34)))
        }
    }

    private fun streamColumn(row: LinearLayout, label: String, icon: Int, stream: Int, height: Int) {
        column(row, label, service.getDrawable(icon)?.mutate(), audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream), height) { value ->
            runCatching { audio.setStreamVolume(stream, value, 0) }
        }
    }

    /** Adds a control per app playing sound; returns a short note when that isn't possible. */
    private fun appColumns(row: LinearLayout, height: Int): String? {
        val rpc = radio
        if (rpc == null) {
            connect()
            return when (ShizukuGate.state(service)) {
                ShizukuGate.State.READY -> "Finding apps playing sound…"
                ShizukuGate.State.NOT_RUNNING -> "Per-app volume: start Shizuku to adjust apps one by one"
                else -> "Per-app volume needs Shizuku (AniHome Settings → Shizuku)"
            }
        }
        val list = players ?: run { fetchPlayers(); return "Finding apps playing sound…" }
        if (list.length() == 0) return "Play something to adjust apps one by one"
        row.addView(View(service).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(dp(1), height).apply { marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(24) })
        val pm = service.packageManager
        val seen = HashSet<String>()
        var muteOnly = false
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i); val pkg = p.getString("package"); val uid = p.getInt("uid")
            if (!seen.add(pkg)) continue
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            if (p.optBoolean("volume", true)) {
                column(row, name, icon, 100, levels.getInt(pkg, 100), height, tint = false) { value ->
                    levels.edit().putInt(pkg, value).apply()
                    Thread { runCatching { rpc.setAppVolume(uid, value / 100f) } }.start()
                }
            } else {
                muteOnly = true
                muteColumn(row, name, icon, p.optBoolean("muted"), height) { muted ->
                    Thread {
                        val ok = runCatching { rpc.setAppMuted(pkg, muted) }.getOrDefault(false)
                        handler.post { if (!ok) android.widget.Toast.makeText(service, "Android didn't allow muting $name", android.widget.Toast.LENGTH_SHORT).show(); fetchPlayers() }
                    }.start()
                }
            }
        }
        return if (muteOnly) "Tap an app to mute or unmute it. This Android version only lets AniHome mute apps, not set their level." else null
    }

    /** An app that can only be muted here: a tall tile with its icon; tap to toggle. */
    private fun muteColumn(row: LinearLayout, label: String, icon: android.graphics.drawable.Drawable?, muted: Boolean, height: Int, toggle: (Boolean) -> Unit) {
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(TextView(service).apply {
            text = if (muted) "Muted" else "On"; textSize = 12f; setTextColor(if (muted) 0xFFFF8A80.toInt() else -1); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        val tile = android.widget.FrameLayout(service).apply {
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(if (muted) app.anihome.launcher.ui.ControlCenter.SURFACE_HIGH else 0xF2FFFFFF.toInt()) }
            isClickable = true; isFocusable = true
            contentDescription = "$label, ${if (muted) "muted" else "sound on"}. Tap to ${if (muted) "unmute" else "mute"}"
            setOnClickListener { toggle(!muted); keepOpen() }
            addView(ImageView(service).apply { setImageDrawable(icon); alpha = if (muted) .45f else 1f },
                android.widget.FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            addView(ImageView(service).apply {
                setImageResource(if (muted) R.drawable.ic_cc_silent else R.drawable.ic_cc_volume)
                imageTintList = ColorStateList.valueOf(if (muted) -1 else 0xFF15171C.toInt())
            }, android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
        }
        column.addView(tile, LinearLayout.LayoutParams(dp(52), height))
        column.addView(TextView(service).apply {
            text = label; textSize = 11f; setTextColor(0xFFC4C7CE.toInt()); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(64), -2).apply { topMargin = dp(8) })
        row.addView(column, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2); marginEnd = dp(2) })
    }

    /** One column: level on top, a tall pill slider with its icon, and the name underneath. */
    private fun column(row: LinearLayout, label: String, icon: android.graphics.drawable.Drawable?, max: Int, value: Int, height: Int, tint: Boolean = true, changed: (Int) -> Unit) {
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val level = TextView(service).apply {
            textSize = 12f; setTextColor(-1); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        fun show(v: Int) { level.text = "${(v * 100f / max.coerceAtLeast(1)).roundToInt()}%" }
        show(value)
        column.addView(level, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        column.addView(app.anihome.launcher.ui.VerticalSlider(service, icon, label, tintIcon = tint).apply {
            this.max = max; this.value = value
            onStart = { dragging = true; handler.removeCallbacks(hide) }
            onEnd = { dragging = false; keepOpen() }
            onChange = { v -> show(v); changed(v); keepOpen() }
        }, LinearLayout.LayoutParams(dp(52), height))
        column.addView(TextView(service).apply {
            text = label; textSize = 11f; setTextColor(0xFFC4C7CE.toInt()); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(64), -2).apply { topMargin = dp(8) })
        row.addView(column, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2); marginEnd = dp(2) })
    }

    @Suppress("unused") private fun percent(v: Int, max: Int) = (v * 100f / max.coerceAtLeast(1)).roundToInt()
}
