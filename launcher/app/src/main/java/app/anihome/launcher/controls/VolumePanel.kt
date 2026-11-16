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
        /** Width of one thin volume line and its icon (phones and tablets alike). */
        private const val COLUMN_WIDTH = 40
        private const val REPEAT_DELAY = 350L
        private const val REPEAT_INTERVAL = 90L
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
            handler.post {
                val next = list ?: org.json.JSONArray()
                val changed = players?.toString() != next.toString()
                players = next
                if (changed && root != null && expanded && !dragging) refresh()
            }
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
        val direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                // Holding the key keeps stepping. Android doesn't generate repeats for keys an
                // accessibility service consumes, so AniHome repeats them itself.
                handler.removeCallbacks(repeat)
                held = direction
                step(direction)
                handler.postDelayed(repeat, REPEAT_DELAY)
            }
            KeyEvent.ACTION_UP -> { handler.removeCallbacks(repeat); held = 0 }
        }
        return true
    }
    private var held = 0
    private val repeat = object : Runnable {
        override fun run() {
            if (held == 0) return
            step(held)
            handler.postDelayed(this, REPEAT_INTERVAL)
        }
    }
    private fun step(direction: Int) {
        audio.adjustStreamVolume(activeStream(), direction, 0)
        show()
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
        if (root != null) { updateLevels(); return } // just move the sliders; no rebuild
        connect()
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(2))
            background = GradientDrawable().apply { setColor(0xF51A1C22.toInt()); cornerRadius = dp(22).toFloat() }
            elevation = dp(10).toFloat()
        }
        root = panel
        val window = object : android.widget.FrameLayout(service) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); return true }
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            clipToPadding = false; setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(panel, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        }
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
        handler.removeCallbacks(hide); handler.removeCallbacks(relist); handler.removeCallbacks(repeat); held = 0
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
        streamUpdaters.clear()
        val metrics = service.resources.displayMetrics
        val lineHeight = minOf(dp(150), (metrics.heightPixels * .28f).toInt())
        val columns = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        streamColumn(columns, "Media", R.drawable.ic_cc_volume, AudioManager.STREAM_MUSIC, lineHeight)
        if (expanded) {
            streamColumn(columns, "Ring", R.drawable.ic_cc_ring, AudioManager.STREAM_RING, lineHeight)
            streamColumn(columns, "Alerts", R.drawable.ic_notifications, AudioManager.STREAM_NOTIFICATION, lineHeight)
            streamColumn(columns, "Alarm", R.drawable.ic_alarm, AudioManager.STREAM_ALARM, lineHeight)
            appColumns(columns, lineHeight)
        }
        // Only scroll sideways when the columns truly can't fit on screen.
        columns.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val available = metrics.widthPixels - dp(48)
        val row: View = if (columns.measuredWidth <= available) columns
            else android.widget.HorizontalScrollView(service).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(columns)
                layoutParams = LinearLayout.LayoutParams(available, -2)
            }
        panel.addView(row, row.layoutParams ?: LinearLayout.LayoutParams(-2, -2))
        panel.addView(ImageButton(service).apply {
            setImageResource(R.drawable.ic_expand_more); rotation = if (expanded) -90f else 90f; background = null
            imageTintList = ColorStateList.valueOf(0xB3FFFFFF.toInt()); contentDescription = if (expanded) "Fewer volumes" else "More volumes"
            setOnClickListener { expanded = !expanded; if (expanded) fetchPlayers(); refresh(); keepOpen() }
        }, LinearLayout.LayoutParams(dp(32), dp(28)).apply { topMargin = dp(2); gravity = Gravity.CENTER_HORIZONTAL })
        fitWindow()
    }

    /** Live sliders by stream, so key presses update them in place. */
    private val streamUpdaters = mutableMapOf<Int, (Int) -> Unit>()
    private fun updateLevels() { streamUpdaters.forEach { (stream, update) -> update(audio.getStreamVolume(stream)) } }

    private fun streamColumn(row: LinearLayout, label: String, icon: Int, stream: Int, height: Int) {
        streamUpdaters[stream] = column(row, label, service.getDrawable(icon)?.mutate(), tint = true,
            max = audio.getStreamMaxVolume(stream), value = audio.getStreamVolume(stream), height = height,
            changed = { value -> runCatching { audio.setStreamVolume(stream, value, 0) } },
            // Tapping the icon mutes/unmutes, like Android's own panel.
            iconTap = { runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_TOGGLE_MUTE, 0) }; updateLevels() })
    }

    /** One line per app playing sound (needs Shizuku); muted apps stay listed until unmuted. */
    private fun appColumns(row: LinearLayout, height: Int) {
        val rpc = radio ?: run { connect(); return }
        val playing = players ?: run { fetchPlayers(); return }
        val list = org.json.JSONArray(playing.toString())
        val listed = (0 until list.length()).map { list.getJSONObject(it).getString("package") }.toSet()
        mutedApps().filter { it !in listed }.forEach { pkg ->
            val uid = runCatching { service.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return@forEach
            list.put(org.json.JSONObject().put("uid", uid).put("package", pkg).put("volume", false).put("muted", true))
        }
        if (list.length() == 0) return
        row.addView(View(service).apply { setBackgroundColor(0x26FFFFFF) }, LinearLayout.LayoutParams(dp(1), height - dp(16)).apply { marginStart = dp(4); marginEnd = dp(4); bottomMargin = dp(38) })
        val pm = service.packageManager
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i); val pkg = p.getString("package"); val uid = p.getInt("uid")
            if (!seen.add(pkg)) continue
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            if (p.optBoolean("volume", true)) {
                column(row, name, icon, tint = false, max = 100, value = levels.getInt(pkg, 100), height = height,
                    changed = { value ->
                        levels.edit().putInt(pkg, value).apply()
                        Thread { runCatching { rpc.setAppVolume(uid, value / 100f) } }.start()
                    })
            } else {
                // Android 10 only lets AniHome mute apps: the line shows on/off, the icon toggles it.
                val muted = p.optBoolean("muted")
                column(row, if (muted) "$name, muted" else name, icon, tint = false, max = 1, value = if (muted) 0 else 1, height = height,
                    interactive = false, dimmed = muted,
                    iconTap = {
                        Thread {
                            val ok = runCatching { rpc.setAppMuted(pkg, !muted) }.getOrDefault(false)
                            if (ok) rememberMuted(pkg, !muted)
                            handler.post {
                                if (!ok) android.widget.Toast.makeText(service, "Android didn't allow muting $name", android.widget.Toast.LENGTH_SHORT).show()
                                if (root != null && expanded && !dragging) refresh()
                                fetchPlayers()
                            }
                        }.start()
                    })
            }
        }
    }

    private fun mutedApps(): Set<String> = levels.getStringSet("muted", emptySet()).orEmpty()
    private fun rememberMuted(pkg: String, muted: Boolean) {
        val next = mutedApps().toMutableSet().apply { if (muted) add(pkg) else remove(pkg) }
        levels.edit().putStringSet("muted", next).apply()
    }

    /** One column: a thin volume line with the sound's icon under it. Returns a level updater. */
    private fun column(row: LinearLayout, label: String, icon: android.graphics.drawable.Drawable?, tint: Boolean,
                       max: Int, value: Int, height: Int, interactive: Boolean = true, dimmed: Boolean = false,
                       changed: (Int) -> Unit = {}, iconTap: (() -> Unit)? = null): (Int) -> Unit {
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val line = LineSlider(service, label).apply {
            this.max = max; this.value = value; enabledLook = !dimmed
            if (interactive) {
                onStart = { dragging = true; handler.removeCallbacks(hide) }
                onEnd = { dragging = false; keepOpen() }
                onChange = { v -> changed(v); keepOpen() }
            } else setOnTouchListener { _, _ -> true }
        }
        column.addView(line, LinearLayout.LayoutParams(dp(COLUMN_WIDTH), height))
        column.addView(ImageView(service).apply {
            setImageDrawable(icon); alpha = if (dimmed) .4f else 1f
            if (tint) imageTintList = ColorStateList.valueOf(0xFFE8EAED.toInt())
            setPadding(dp(5), dp(5), dp(5), dp(5)); contentDescription = label
            if (iconTap != null) {
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
                setOnClickListener { iconTap(); keepOpen() }
            } else importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(4) })
        row.addView(column, LinearLayout.LayoutParams(-2, -2))
        return { v -> if (!dragging) line.value = v }
    }

    @Suppress("unused") private fun percent(v: Int, max: Int) = (v * 100f / max.coerceAtLeast(1)).roundToInt()
}
