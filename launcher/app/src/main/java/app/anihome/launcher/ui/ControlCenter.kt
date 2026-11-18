package app.anihome.launcher.ui

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.anihome.launcher.R
import app.anihome.launcher.controls.HomeControlsService
import app.anihome.launcher.notifications.HomeNotifications
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Control Center at the top of the notification sidebar. One grid holds every control
 * (radios, rotation, flashlight, Do not disturb, shell toggles, screenshot, lock and Home
 * settings). Collapsed it is a single row of icons; expanded it shows the whole labelled grid.
 * The user chooses and orders the tiles in Edit. Wi-Fi and Bluetooth device lists open
 * directly beneath the grid. Only the tile choice, order and expanded state are stored.
 */
internal class ControlCenter(
    private val activity: AppCompatActivity,
    private val quick: QuickSettings,
    private val closePanel: () -> Unit,
    private val openHomeSettings: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("anihome", Context.MODE_PRIVATE)
    private val camera = activity.getSystemService(CameraManager::class.java)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val power = activity.getSystemService(PowerManager::class.java)
    private val location = activity.getSystemService(LocationManager::class.java)
    private val notifications = activity.getSystemService(NotificationManager::class.java)
    private val wifi = activity.applicationContext.getSystemService(WifiManager::class.java)
    private val bluetooth = activity.getSystemService(BluetoothManager::class.java)?.adapter
    private val torchId: String? = runCatching {
        camera?.cameraIdList?.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private var torchOn = false
    private var attached = false
    private var media: MediaController? = null
    private var sessions: MediaSessionManager? = null

    private fun dp(value: Int) = ArtworkUi.dp(activity, value)

    private class Tile(val id: String, val label: String, val icon: () -> Int, val state: () -> Boolean,
                       val tap: () -> Unit, val settings: Intent?, val momentary: Boolean = false,
                       /** App actions carry the app's own artwork instead of a tinted glyph. */
                       val artwork: (() -> android.graphics.drawable.Drawable?)? = null,
                       /** Second line on a wide tile (the connected network, "Locked"…); default On/Off. */
                       val detail: (() -> String?)? = null,
                       /** Long press; defaults to opening [settings]. */
                       val longPress: (() -> Unit)? = null)
    /** A drawn tile: its round button, and on wide tiles the whole pill and its second line. */
    private class TileView(val tile: Tile, val button: ImageButton, val pill: View?, val detail: TextView?,
                           val name: TextView? = null, val size: CcSize = CcSize.SMALL)

    private val allTiles: List<Tile> by lazy { buildTiles() }
    private val tileViews = mutableListOf<TileView>()

    val view: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(4)) }

    /**
     * Swipe down anywhere on the controls to show them all; swipe up to fold them away.
     * Fed by the scrolling container (which would otherwise claim vertical drags first).
     * Returns true once the gesture is ours, so the container stops scrolling for it.
     */
    private val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop * 2
    private var swipeX = 0f; private var swipeY = 0f; private var swipeTracking = false
    fun interceptSwipe(e: MotionEvent, overControls: Boolean): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swipeX = e.x; swipeY = e.y; swipeTracking = overControls }
            MotionEvent.ACTION_MOVE -> if (swipeTracking) {
                val dy = e.y - swipeY; val dx = e.x - swipeX
                if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) {
                    swipeTracking = false
                    if ((dy > 0 && !expanded) || (dy < 0 && expanded)) { applyExpanded(dy > 0); return true }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeTracking = false
        }
        return false
    }
    private lateinit var brightnessRow: View
    private lateinit var volumeRow: View
    /** Wi-Fi and Bluetooth device lists render here, right under the grid. */
    val radioSlot: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val battery = TextView(activity).apply { textSize = 12f; setTextColor(MUTED) }
    private val grid = GridLayout(activity)
    private val expandButton = ImageButton(activity)
    private val mediaCard = LinearLayout(activity)
    private val mediaArt = ImageView(activity)
    private val mediaTitle = TextView(activity)
    private val mediaArtist = TextView(activity)
    private var playButton: ImageButton? = null
    private val brightness = SeekBar(activity)
    private val volume = SeekBar(activity)
    /**
     * Full-screen panel: every control in one labelled row across the top and the sliders side
     * by side, so notifications get the rest of the screen. The grid never folds there.
     */
    var wideLayout = false
        set(value) { field = value; expandButton.visibility = if (value) View.GONE else View.VISIBLE; arrangeSliders(); layoutGrid() }
    private val alwaysExpanded get() = wideLayout
    private val sliders = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val topRow = LinearLayout(activity)
    /** Full screen: tall Control-Center pills beside the grid instead of horizontal bars. */
    private val brightnessPill = VerticalSlider(activity, ContextCompat.getDrawable(activity, R.drawable.ic_cc_brightness)?.mutate(), "Brightness").apply {
        max = 100
        onStart = { if (!Settings.System.canWrite(activity)) {
            Toast.makeText(activity, "Allow AniHome to modify system settings to change brightness", Toast.LENGTH_LONG).show()
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${activity.packageName}")))
        } }
        onChange = { setBrightness(it) }
    }
    private val volumePill = VerticalSlider(activity, ContextCompat.getDrawable(activity, R.drawable.ic_cc_volume)?.mutate(), "Media volume").apply {
        max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        onChange = { volumeTo(it) }
    }
    private fun arrangeSliders() {
        if (!::brightnessRow.isInitialized) return
        sliders.removeAllViews()
        listOf(brightnessPill, volumePill).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        if (gridMode) {
            // The sliders and the media card live inside the Control Center grid.
            sliders.visibility = View.GONE
        } else {
            sliders.visibility = View.VISIBLE
            sliders.addView(brightnessRow, LinearLayout.LayoutParams(-1, dp(34)))
            sliders.addView(volumeRow, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(8) })
        }
    }
    /** Like Android's shade: every time the panel opens it starts collapsed. */
    private var expandedNow = false
    private var expanded: Boolean
        get() = alwaysExpanded || expandedNow
        set(value) { expandedNow = value }
    val isExpanded get() = expanded
    fun expandControls() { if (!expanded) applyExpanded(true) }

    private val ccGrid = CcGrid(activity)
    /** Long-press options open here, inside the panel above the controls. */
    private val sheet = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private var sheetFor: String? = null
    private var gridTitle: TextView? = null; private var gridArtist: TextView? = null
    private var gridArt: ImageView? = null; private var gridPlay: ImageButton? = null
    // Declared above so they exist when build() runs from init.
    init { build() }

    // --- tiles ---------------------------------------------------------------------------
    /** Expanded (or the full-screen panel): the iOS-style grid of resizable glass tiles. */
    private val gridMode get() = expanded && !(!wideLayout && !PanelStyle.wideTiles(activity))
    private fun isPseudo(id: String) = id == "media" || id.startsWith("slider_")
    private fun sizeOf(id: String): CcSize = CcSize.parse(prefs.getString("cc_size:$id", null), when {
        id == "wifi" || id == "bluetooth" -> CcSize.WIDE
        id == "media" -> CcSize.LARGE
        id.startsWith("slider_") -> CcSize.TALL
        else -> CcSize.SMALL
    })
    private fun sizesFor(id: String) = when {
        id == "media" -> listOf(CcSize.LARGE, CcSize.BAR)
        id.startsWith("slider_") -> listOf(CcSize.TALL, CcSize.WIDE, CcSize.BAR)
        else -> listOf(CcSize.SMALL, CcSize.WIDE, CcSize.TALL, CcSize.LARGE)
    }

    private fun buildTiles(): List<Tile> = listOfNotNull(
        Tile("media", "Now playing", { R.drawable.ic_cc_music }, { false }, {}, null, momentary = true),
        Tile("slider_brightness", "Brightness", { R.drawable.ic_cc_brightness }, { false }, {}, Intent(Settings.ACTION_DISPLAY_SETTINGS), momentary = true),
        Tile("slider_volume", "Volume", { R.drawable.ic_cc_volume }, { false }, {}, Intent(Settings.ACTION_SOUND_SETTINGS), momentary = true),
        Tile("wifi", "Wi-Fi", { R.drawable.ic_wifi }, { runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false) },
            { quick.openSection("Wi-Fi"); refresh() }, Intent(Settings.ACTION_WIFI_SETTINGS), detail = ::wifiDetail),
        Tile("bluetooth", "Bluetooth", { R.drawable.ic_bluetooth }, { runCatching { bluetooth?.isEnabled == true }.getOrDefault(false) },
            { quick.openSection("Bluetooth"); refresh() }, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), detail = ::bluetoothDetail),
        Tile("rotation", "Auto-rotate", { if (quick.rotationAutomatic) R.drawable.ic_rotation else R.drawable.ic_rotation_locked },
            { quick.rotationAutomatic }, { quick.toggleRotation(); handler.postDelayed(::refresh, 250) }, Intent(Settings.ACTION_DISPLAY_SETTINGS),
            detail = { if (quick.rotationAutomatic) "Auto" else "Locked" }),
        torchId?.let { Tile("flashlight", "Flashlight", { R.drawable.ic_cc_flashlight }, { torchOn || TorchTools.sosRunning },
            { if (TorchTools.sosRunning) TorchTools.stopSos(camera, torchId) else setTorch(!torchOn) }, null,
            detail = { when { TorchTools.sosRunning -> "Signalling · tap to stop"; torchOn -> "On"; else -> "Off" } },
            longPress = null) },
        Tile("screen_record", "Screen record", { R.drawable.ic_cc_record }, { ScreenRecorder.recording },
            { if (ScreenRecorder.recording) ScreenRecorder.stop(activity) else { closePanel(); ScreenRecorder.start(activity) } }, null,
            detail = { if (ScreenRecorder.recording) "Recording · tap to stop" else "Off" }),
        Tile("dnd", "Do not disturb", { R.drawable.ic_cc_dnd },
            { notifications?.currentInterruptionFilter.let { it != null && it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN } },
            { setDoNotDisturb(!isDnd()) }, Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)),
        Tile("dark", "Dark theme", { R.drawable.ic_cc_dark }, ::isDark,
            { privileged("dark", !isDark(), Intent(Settings.ACTION_DISPLAY_SETTINGS), ::isDark) }, Intent(Settings.ACTION_DISPLAY_SETTINGS)),
        Tile("battery_saver", "Battery saver", { R.drawable.ic_cc_battery_saver }, { power?.isPowerSaveMode == true },
            { privileged("battery_saver", power?.isPowerSaveMode != true, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) { power?.isPowerSaveMode == true } },
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
        Tile("location", "Location", { R.drawable.ic_cc_location }, ::isLocation,
            { privileged("location", !isLocation(), Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), ::isLocation) }, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
        Tile("airplane", "Airplane", { R.drawable.ic_cc_airplane }, ::isAirplane, { toggleAirplane() }, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)),
        Tile("night_light", "Night light", { R.drawable.ic_cc_night_light },
            { Settings.Secure.getInt(activity.contentResolver, "night_display_activated", 0) == 1 },
            { val on = Settings.Secure.getInt(activity.contentResolver, "night_display_activated", 0) != 1
              privileged("night_light", on, Intent(Settings.ACTION_DISPLAY_SETTINGS)) { Settings.Secure.getInt(activity.contentResolver, "night_display_activated", 0) == 1 } },
            Intent(Settings.ACTION_DISPLAY_SETTINGS)),
        Tile("auto_brightness", "Auto brightness", { R.drawable.ic_cc_auto_brightness }, ::isAutoBrightness, {
            if (Settings.System.canWrite(activity)) runCatching {
                Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (isAutoBrightness()) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            } else launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${activity.packageName}")))
        }, Intent(Settings.ACTION_DISPLAY_SETTINGS)),
        Tile("sound_mode", "Sound", { when (audio?.ringerMode) { AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_cc_vibrate; AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_cc_silent; else -> R.drawable.ic_cc_ring } },
            { audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL }, ::cycleRinger, Intent(Settings.ACTION_SOUND_SETTINGS)),
        Tile("caffeine", "Stay awake", { R.drawable.ic_cc_coffee }, { HomeControlsService.awake }, {
            if (!app.anihome.launcher.controls.ControlsAccess.ensure(activity, "Stay awake", quick::grantSecureSettings)) Unit
            else HomeControlsService.setKeepAwake(!HomeControlsService.awake)
        }, null),
        Tile("hotspot", "Hotspot", { R.drawable.ic_cc_hotspot }, ::isHotspot,
            { launch(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings").takeIf { it.resolveActivity(activity.packageManager) != null } ?: Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            // Android only lets apps open the hotspot screen, so the tile says so instead of posing as a switch.
            detail = { (if (isHotspot()) "On" else "Off") + " · opens settings" }),
        Tile("mobile_data", "Mobile data", { R.drawable.ic_cc_mobile_data }, { Settings.Global.getInt(activity.contentResolver, "mobile_data", 0) == 1 },
            { val on = Settings.Global.getInt(activity.contentResolver, "mobile_data", 0) != 1
              privileged("mobile_data", on, Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) { Settings.Global.getInt(activity.contentResolver, "mobile_data", 0) == 1 } },
            Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)).takeIf { activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) },
        Tile("invert", "Invert colours", { R.drawable.ic_cc_invert },
            { Settings.Secure.getInt(activity.contentResolver, "accessibility_display_inversion_enabled", 0) == 1 },
            { val on = Settings.Secure.getInt(activity.contentResolver, "accessibility_display_inversion_enabled", 0) != 1
              privileged("invert", on, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) { Settings.Secure.getInt(activity.contentResolver, "accessibility_display_inversion_enabled", 0) == 1 } },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        Tile("cast", "Cast", { R.drawable.ic_cc_cast }, { false }, { launch(Intent(Settings.ACTION_CAST_SETTINGS)) }, null, momentary = true),
        Tile("screenshot", "Screenshot", { R.drawable.ic_cc_screenshot }, { false },
            { globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, 450) }, null, momentary = true),
        Tile("lock", "Lock", { R.drawable.ic_cc_lock }, { false },
            { globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, 250) }, null, momentary = true),
        // AniHome's own settings, with AniHome's settings mark, so it isn't mistaken for Android's Settings.
        Tile("settings", "AniHome settings", { R.drawable.ic_anihome_settings }, { false }, openHomeSettings, Intent(Settings.ACTION_SETTINGS), momentary = true,
            detail = { "Hold for Android settings" }),
    )
    /** Quick actions apps publish (the long-press shortcuts), pinned as Control Center tiles. */
    private fun actionTile(id: String): Tile? {
        val (pkg, shortcutId) = id.removePrefix("action:").split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        val apps = activity.getSystemService(android.content.pm.LauncherApps::class.java) ?: return null
        val info = runCatching {
            apps.getShortcuts(android.content.pm.LauncherApps.ShortcutQuery().setPackage(pkg).setShortcutIds(listOf(shortcutId))
                .setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),
                android.os.Process.myUserHandle())?.firstOrNull()
        }.getOrNull() ?: return null
        val label = (info.shortLabel ?: info.longLabel ?: "Action").toString()
        return Tile(id, label, { R.drawable.ic_apps }, { false }, {
            closePanel(); AppShortcuts.start(activity, info, null)
        }, null, momentary = true, artwork = { runCatching { apps.getShortcutIconDrawable(info, activity.resources.displayMetrics.densityDpi) }.getOrNull() })
    }
    private fun chosen(): List<Tile> {
        var saved = prefs.getString("cc_tiles", null)?.split(',')?.filter { it.isNotBlank() } ?: defaultOrder
        // Layouts saved before 1.1.0 get the new controls once, ahead of Screenshot; later edits are respected.
        if (prefs.contains("cc_tiles") && !prefs.getBoolean("cc_new_controls_1_1", false)) {
            val added = listOf("night_light", "sound_mode", "mobile_data", "hotspot", "caffeine").filter { it !in saved }
            val at = saved.indexOf("screenshot").takeIf { it >= 0 } ?: saved.size
            saved = saved.take(at) + added + saved.drop(at)
            prefs.edit().putString("cc_tiles", saved.joinToString(",")).putBoolean("cc_new_controls_1_1", true).apply()
        }
        // Media and the two sliders become grid tiles once for saved layouts, after the first four controls.
        if (prefs.contains("cc_tiles") && !prefs.getBoolean("cc_grid_v1", false)) {
            val add = listOf("media", "slider_brightness", "slider_volume").filter { it !in saved }
            saved = saved.take(4) + add + saved.drop(4)
            prefs.edit().putString("cc_tiles", saved.joinToString(",")).putBoolean("cc_grid_v1", true).apply()
        }
        // Screen record arrives once for saved layouts, right after Screenshot.
        if (prefs.contains("cc_tiles") && !prefs.getBoolean("cc_new_controls_1_1_r10", false)) {
            if ("screen_record" !in saved) { val at = saved.indexOf("screenshot").takeIf { it >= 0 }?.plus(1) ?: saved.size; saved = saved.take(at) + "screen_record" + saved.drop(at) }
            prefs.edit().putString("cc_tiles", saved.joinToString(",")).putBoolean("cc_new_controls_1_1_r10", true).apply()
        }
        val byId = allTiles.associateBy { it.id } + saved.filter { it.startsWith("action:") }.mapNotNull { id -> actionTile(id)?.let { id to it } }
        // Some manufacturers lock dark theme to their own Settings; hide the control there.
        val darkLocked = prefs.getBoolean("dark_locked", false)
        return saved.mapNotNull { byId[it] }.filter { !(it.id == "dark" && darkLocked) }
    }
    private fun isAutoBrightness() = runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrDefault(false)
    @Suppress("DiscouragedPrivateApi") private fun isHotspot() = runCatching { wifi!!.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as Boolean }.getOrDefault(false)
    /** Ring → vibrate → silent (silent needs Do-not-disturb access on newer Android). */
    private var volumeWarned = false
    /** Media volume; Android can refuse it (Do not disturb, fixed-volume devices). Explain once. */
    private fun volumeTo(level: Int) {
        runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }.onFailure {
            if (!volumeWarned) { volumeWarned = true; Toast.makeText(activity, "Android didn't allow changing the volume right now", Toast.LENGTH_SHORT).show() }
        }
    }
    private fun cycleRinger() {
        val audio = audio ?: return
        val next = when (audio.ringerMode) { AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE; AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT; else -> AudioManager.RINGER_MODE_NORMAL }
        runCatching { audio.ringerMode = next }.onFailure {
            if (next == AudioManager.RINGER_MODE_SILENT) {
                // Silent needs Do not disturb access on this Android version; say so instead of quietly going back to ring.
                runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                Toast.makeText(activity, "Silent mode needs Do not disturb access. Allow AniHome, then try again.", Toast.LENGTH_LONG).show()
                launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
    }
    private fun isDark() = activity.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun isLocation() = runCatching { if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true else true }.getOrDefault(false)
    private fun isAirplane() = Settings.Global.getInt(activity.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    private fun isDnd() = allTiles.first { it.id == "dnd" }.state()

    // --- layout ----------------------------------------------------------------------------
    private fun build() {
        // Header: time + date on one baseline, battery, then Edit and expand on the right.
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), 0, dp(8)) }
        header.addView(TextClock(activity).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 26f; setTextColor(-1); includeFontPadding = false
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        })
        val sub = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        sub.addView(TextClock(activity).apply { format12Hour = "EEE, d MMM"; format24Hour = "EEE, d MMM"; textSize = 12f; setTextColor(-1) })
        sub.addView(battery)
        header.addView(sub, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(headerButton(R.drawable.ic_edit, "Edit controls") { edit() })
        expandButton.apply {
            setImageResource(R.drawable.ic_expand_more); imageTintList = ColorStateList.valueOf(-1)
            background = null; scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { applyExpanded(!expanded) }
        }
        header.addView(expandButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        view.addView(header)

        view.addView(sheet, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        grid.apply { useDefaultMargins = false }
        topRow.gravity = Gravity.CENTER_VERTICAL
        topRow.addView(grid, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(ccGrid, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(topRow, LinearLayout.LayoutParams(-1, -2))
        view.addView(radioSlot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        brightnessRow = slider(R.drawable.ic_cc_brightness, "Brightness", brightness)
        volumeRow = slider(R.drawable.ic_cc_volume, "Media volume", volume)
        view.addView(sliders, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        arrangeSliders()
        brightness.max = 100
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) setBrightness(progress) }
            override fun onStartTrackingTouch(bar: SeekBar) {
                if (!Settings.System.canWrite(activity)) {
                    Toast.makeText(activity, "Allow AniHome to modify system settings to change brightness", Toast.LENGTH_LONG).show()
                    launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${activity.packageName}")))
                }
            }
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        volume.max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) volumeTo(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        buildMedia()
        view.addView(mediaCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        layoutGrid()
    }

    private fun headerButton(icon: Int, label: String, action: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(-1); background = null
        scaleType = ImageView.ScaleType.CENTER; contentDescription = label; tooltipText = label
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    /** Collapsed: one unlabelled row. Expanded: the full grid with labels. */
    private fun layoutGrid() {
        grid.removeAllViews(); tileViews.clear()
        val tiles = chosen()
        val open = expanded
        // Full screen: two rows beside the slider pills.
        ccGrid.removeAllViews()
        arrangeSliders()
        if (gridMode) {
            grid.visibility = View.GONE; ccGrid.visibility = View.VISIBLE
            ccGrid.fixedColumns = if (wideLayout) 0 else 4
            if (mediaCard.parent != null) (mediaCard.parent as android.view.ViewGroup).removeView(mediaCard)
            tiles.forEach(::addGridTile)
            expandButton.rotation = 180f; expandButton.contentDescription = "Show fewer controls"
            if (attached) refresh(); return
        }
        grid.visibility = View.VISIBLE; ccGrid.visibility = View.GONE
        if (mediaCard.parent == null) view.addView(mediaCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val compact = tiles.filterNot { isPseudo(it.id) }
        val columns = if (open) 4 else COLLAPSED
        grid.columnCount = columns
        val shown = if (open) compact else compact.take(COLLAPSED)
        shown.forEach { tile ->
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(3), 0, dp(if (open) 6 else 3))
            }
            val button = ImageButton(activity).apply {
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = tile.label; tooltipText = tile.label
                setOnClickListener { tile.tap(); handler.postDelayed(::refresh, 350) }
                setOnLongClickListener { longPress(tile) }
                @Suppress("ClickableViewAccessibility")
                setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) MotionUi.press(v, true)
                    else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) MotionUi.press(v, false)
                    false
                }
            }
            cell.addView(button, LinearLayout.LayoutParams(dp(TILE), dp(TILE)))
            if (open) cell.addView(TextView(activity).apply {
                text = tile.label; textSize = 11f; setTextColor(0xffd6d6d6.toInt()); gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
            tileViews += TileView(tile, button, null, null)
        }
        expandButton.rotation = if (open) 180f else 0f
        expandButton.contentDescription = if (open) "Show fewer controls" else "Show all controls"
        if (attached) refresh()
    }

    private fun longPress(tile: Tile): Boolean { showSheet(tile); return true }

    /** Rounded like iOS: circles and pills for one-row tiles, soft squares for bigger ones. */
    private fun radiusFor(size: CcSize, cellPx: Int) = when (size) {
        CcSize.SMALL, CcSize.WIDE, CcSize.BAR, CcSize.TALL -> 999f
        CcSize.LARGE -> dp(30).toFloat()
    }

    /** A tile in the grid: glass pill, icon, and (when there is room) its name and a live second line. */
    private fun addGridTile(tile: Tile) {
        val size = sizeOf(tile.id)
        when {
            tile.id == "slider_brightness" || tile.id == "slider_volume" -> {
                val visible = if (tile.id == "slider_brightness") PanelStyle.brightness(activity) else PanelStyle.volume(activity)
                if (!visible) return
                val pill = if (tile.id == "slider_brightness") brightnessPill else volumePill
                (pill.parent as? android.view.ViewGroup)?.removeView(pill)
                pill.horizontal = size.columns > size.rows
                pill.setOnLongClickListener { showSheet(tile); true }
                ccGrid.addView(pill, CcGrid.Params(size)); return
            }
            tile.id == "media" -> { if (PanelStyle.media(activity)) ccGrid.addView(gridMedia(tile, size), CcGrid.Params(size)); return }
        }
        val button = ImageButton(activity).apply { scaleType = ImageView.ScaleType.CENTER; isClickable = false; isFocusable = false; background = null; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val name = TextView(activity).apply { text = tile.label; textSize = if (size == CcSize.LARGE) 15f else 13.5f; setTextColor(-1); maxLines = if (size == CcSize.TALL) 2 else 1; ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val detail = TextView(activity).apply { textSize = 12f; setTextColor(0xffc4c6d0.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(name); addView(detail) }
        val icon = dp(if (size == CcSize.LARGE) 30 else 24)
        val pill: android.view.ViewGroup = when (size) {
            CcSize.SMALL -> android.widget.FrameLayout(activity).apply { addView(button, android.widget.FrameLayout.LayoutParams(-1, -1)) }
            CcSize.WIDE, CcSize.BAR -> LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(14), 0)
                addView(button, LinearLayout.LayoutParams(icon, icon)); addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            }
            CcSize.TALL, CcSize.LARGE -> LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(14), dp(14))
                addView(button, LinearLayout.LayoutParams(icon, icon)); addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f)); addView(words)
            }
        }
        if (size == CcSize.SMALL) { name.visibility = View.GONE }
        pill.apply {
            isClickable = true; isFocusable = true; tooltipText = tile.label
            foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, GradientDrawable().apply { setColor(-1); cornerRadius = radiusFor(size, 0) })
            setOnClickListener { if (sheetFor == tile.id) closeSheet(); tile.tap(); handler.postDelayed(::refresh, 350) }
            setOnLongClickListener { showSheet(tile); true }
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) MotionUi.press(v, true)
                else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) MotionUi.press(v, false)
                false
            }
        }
        ccGrid.addView(pill, CcGrid.Params(size))
        tileViews += TileView(tile, button, pill, detail.takeIf { size != CcSize.SMALL }, name, size)
    }

    // Grid media tile: artwork, title, artist and transport, kept in step with the media session.
    private fun gridMedia(tile: Tile, size: CcSize): View {
        val card = LinearLayout(activity).apply {
            orientation = if (size == CcSize.BAR) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (size == CcSize.BAR) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            setPadding(dp(14), dp(14), dp(12), dp(10)); background = CcGlass.tile(activity, dp(30).toFloat(), false)
            isClickable = true
            setOnClickListener { media?.sessionActivity?.let { runCatching { it.send(); closePanel() } } }
            setOnLongClickListener { showSheet(tile); true }
        }
        val art = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = RoundOutline(dp(12).toFloat())
            setImageResource(R.drawable.ic_cc_music); imageTintList = null }
        val title = TextView(activity).apply { textSize = 14f; setTextColor(-1); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTypeface(null, android.graphics.Typeface.BOLD) }
        val artist = TextView(activity).apply { textSize = 12f; setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(artist) }
        val transport = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        fun control(icon: Int, label: String, action: () -> Unit) = ArtworkUi.plainIcon(activity, icon, label) { action() }.also {
            it.setPadding(dp(8), dp(8), dp(8), dp(8)); transport.addView(it, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        control(R.drawable.ic_cc_previous, "Previous") { media?.transportControls?.skipToPrevious() }
        val play = control(R.drawable.ic_cc_play, "Play or pause") {
            val c = media ?: return@control
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        control(R.drawable.ic_cc_next, "Next") { media?.transportControls?.skipToNext() }
        if (size == CcSize.BAR) {
            card.addView(art, LinearLayout.LayoutParams(dp(40), dp(40)))
            card.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            card.addView(transport, LinearLayout.LayoutParams(dp(120), -2))
        } else {
            card.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
            card.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
            card.addView(words); card.addView(transport, LinearLayout.LayoutParams(-1, -2))
        }
        gridTitle = title; gridArtist = artist; gridArt = art; gridPlay = play
        renderMedia()
        return card
    }

    // --- long-press options, inside the panel ---------------------------------------------
    private fun closeSheet() {
        if (sheet.visibility != View.VISIBLE) return
        MotionUi.expand(view); sheet.visibility = View.GONE; sheet.removeAllViews(); sheetFor = null
    }
    /**
     * Options for one control open at the top of the panel (not a pop-up): its size in the grid,
     * and what else it can do. Tap ✕, the control again, or another control's long press.
     */
    private fun showSheet(tile: Tile) {
        MotionUi.expand(view)
        sheet.removeAllViews(); sheetFor = tile.id
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(8), dp(14))
            background = CcGlass.tile(activity, dp(26).toFloat(), false)
        }
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(activity).apply { setImageResource(tile.icon()); imageTintList = ColorStateList.valueOf(-1) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        head.addView(TextView(activity).apply { text = tile.label; textSize = 16f; setTextColor(-1); setPadding(dp(10), 0, 0, 0)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(headerButton(R.drawable.ic_close, "Close options") { closeSheet() })
        card.addView(head)
        fun section(text: String) = card.addView(TextView(activity).apply { this.text = text; textSize = 12f; setTextColor(0xffc4c6d0.toInt()); setPadding(dp(2), dp(12), 0, dp(6)) })
        if (gridMode) {
            section("Size")
            val sizes = sizesFor(tile.id)
            card.addView(MaterialUi.segmented(activity, sizes.map { it.label }, sizes.indexOf(sizeOf(tile.id)).coerceAtLeast(0)) { i ->
                prefs.edit().putString("cc_size:${tile.id}", sizes[i].name).apply()
                MotionUi.expand(view); layoutGrid()
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (tile.id == "flashlight") torchOptions(card, ::section)
        fun action(label: String, run: () -> Unit) = card.addView(MaterialUi.tonal(activity, label) { run() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        when (tile.id) {
            "wifi" -> { action("Networks") { closeSheet(); quick.openSection("Wi-Fi"); refresh() }
                if (activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    action("Show the network's name") { androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 77) } }
            "bluetooth" -> { action("Devices") { closeSheet(); quick.openSection("Bluetooth"); refresh() }
                if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    action("Show connected devices") { androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 78) } }
            "settings" -> action("Android settings") { launch(Intent(Settings.ACTION_SETTINGS)) }
        }
        if (tile.id != "settings" && tile.id != "flashlight") tile.longPress?.let { lp -> action("More") { lp() } }
        tile.settings?.takeIf { tile.id != "settings" }?.let { intent -> action("Open settings") { launch(intent) } }
        sheet.addView(card, LinearLayout.LayoutParams(-1, -2))
        sheet.visibility = View.VISIBLE
    }

    /** Flashlight extras: brightness (where the phone allows), SOS, a message in Morse code, and the screen light. */
    private fun torchOptions(card: LinearLayout, section: (String) -> Unit) {
        val id = torchId ?: return
        val max = TorchTools.maxLevel(camera, id)
        if (max > 1 && Build.VERSION.SDK_INT >= 33) {
            section("Brightness")
            card.addView(com.google.android.material.slider.Slider(MaterialUi.themed(activity)).apply {
                valueFrom = 1f; valueTo = max.toFloat(); stepSize = 1f
                value = prefs.getInt("torch_level", max).coerceIn(1, max).toFloat()
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    TorchTools.stopSos()
                    runCatching { camera?.turnOnTorchWithStrengthLevel(id, v.toInt()) }
                    prefs.edit().putInt("torch_level", v.toInt()).apply(); refresh()
                }
            })
        }
        section("Signal")
        val row = LinearLayout(activity)
        row.addView(MaterialUi.tonal(activity, if (TorchTools.sosRunning) "Stop" else "SOS") {
            if (TorchTools.sosRunning) TorchTools.stopSos(camera, id) else TorchTools.startSos(camera, id)
            refresh(); showSheet(allTiles.first { it.id == "flashlight" })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MaterialUi.tonal(activity, "Screen light") {
            closePanel(); activity.startActivity(Intent(activity, ScreenLightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        card.addView(row)
        section("Message in Morse code")
        val input = android.widget.EditText(activity).apply {
            hint = "Type a message"; setText(prefs.getString("morse_text", "")); isSingleLine = true; textSize = 15f
            setTextColor(-1); setHintTextColor(0x99FFFFFF.toInt()); filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        card.addView(input, LinearLayout.LayoutParams(-1, -2))
        val flash = LinearLayout(activity)
        fun send(onScreen: Boolean) {
            val text = input.text.toString().trim()
            if (TorchTools.morse(text).isEmpty()) { input.error = "Use letters, numbers or . , ? ! / @"; return }
            prefs.edit().putString("morse_text", text).apply()
            if (onScreen) { closePanel(); activity.startActivity(Intent(activity, ScreenLightActivity::class.java).putExtra(ScreenLightActivity.EXTRA_MORSE, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else { TorchTools.startMorse(camera, id, text); refresh(); showSheet(allTiles.first { it.id == "flashlight" }) }
        }
        flash.addView(MaterialUi.tonal(activity, if (TorchTools.sosRunning) "Stop" else "Flash with torch") {
            if (TorchTools.sosRunning) { TorchTools.stopSos(camera, id); refresh(); showSheet(allTiles.first { it.id == "flashlight" }) } else send(false)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        flash.addView(MaterialUi.tonal(activity, "Flash on screen") { send(true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        card.addView(flash, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    /** The connected network's name (Android hides it without location access), or the radio's state. */
    @Suppress("DEPRECATION")
    private fun wifiDetail(): String {
        val w = wifi ?: return "Off"
        if (!w.isWifiEnabled) return "Off"
        val connectivity = activity.getSystemService(android.net.ConnectivityManager::class.java)
        val connected = runCatching { connectivity?.allNetworks?.any { connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true } == true }.getOrDefault(false)
        if (!connected) return "Not connected"
        val ssid = runCatching { w.connectionInfo?.ssid }.getOrNull()?.trim('"')
        return ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Connected"
    }
    /** Connected device names (needs the nearby devices permission on Android 12+), or On/Off. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun bluetoothDetail(): String {
        val adapter = bluetooth ?: return "Off"
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return "Off"
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return "On"
        val names = runCatching {
            adapter.bondedDevices.filter { d -> runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false) }.map { it.name ?: it.address }
        }.getOrDefault(emptyList())
        return when (names.size) { 0 -> "Not connected"; 1 -> names[0]; else -> "${names[0]} +${names.size - 1}" }
    }

    private fun applyExpanded(open: Boolean) {
        MotionUi.expand(view)
        if (!open) { sheet.visibility = View.GONE; sheet.removeAllViews(); sheetFor = null }
        expanded = open
        expandButton.animate().rotation(if (open) 180f else 0f).setDuration(MotionUi.duration(expandButton, 200)).setInterpolator(MotionUi.STANDARD).start()
        layoutGrid()
    }

    private fun slider(icon: Int, label: String, bar: SeekBar) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(4), 0)
        background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), STROKE) }
        addView(ImageView(activity).apply { setImageResource(icon); alpha = .85f }, LinearLayout.LayoutParams(dp(20), dp(20)))
        bar.contentDescription = label
        bar.progressTintList = ColorStateList.valueOf(ACCENT)
        bar.thumbTintList = ColorStateList.valueOf(-1)
        addView(bar, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun buildMedia() {
        mediaCard.apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(4), dp(8)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), STROKE) }
            isClickable = true
            setOnClickListener { media?.sessionActivity?.let { runCatching { it.send(); closePanel() } } }
        }
        mediaArt.apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = RoundOutline(dp(10).toFloat()) }
        mediaCard.addView(mediaArt, LinearLayout.LayoutParams(dp(44), dp(44)))
        val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(4), 0) }
        mediaTitle.apply { textSize = 14f; setTextColor(-1); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTypeface(null, android.graphics.Typeface.BOLD) }
        mediaArtist.apply { textSize = 12f; setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        text.addView(mediaTitle); text.addView(mediaArtist)
        mediaCard.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        fun control(icon: Int, label: String, action: () -> Unit) = ArtworkUi.plainIcon(activity, icon, label) { action() }.also {
            it.setPadding(dp(10), dp(10), dp(10), dp(10)); mediaCard.addView(it, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        control(R.drawable.ic_cc_previous, "Previous") { media?.transportControls?.skipToPrevious() }
        playButton = control(R.drawable.ic_cc_play, "Play or pause") {
            val controller = media ?: return@control
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controller.transportControls.pause() else controller.transportControls.play()
        }
        control(R.drawable.ic_cc_next, "Next") { media?.transportControls?.skipToNext() }
    }

    private class RoundOutline(val radius: Float) : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
    }

    // --- edit ------------------------------------------------------------------------------
    /** Choose which controls appear and their order; the first five fill the collapsed row. */
    private fun edit() {
        val order = (chosen().map { it.id } + allTiles.map { it.id }).distinct().toMutableList()
        val known = (allTiles + chosen()).associateBy { it.id }.toMutableMap()
        val enabled = chosen().map { it.id }.toMutableSet()
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(8), dp(4)) }
        fun render() {
            list.removeAllViews()
            order.forEachIndexed { index, id ->
                val tile = known[id] ?: return@forEachIndexed
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48) }
                row.addView(ImageView(activity).apply {
                    val art = tile.artwork?.invoke()
                    if (art != null) setImageDrawable(art) else { setImageResource(tile.icon()); imageTintList = ColorStateList.valueOf(-1) }
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                row.addView(CheckBox(activity).apply {
                    text = tile.label; isChecked = id in enabled; setTextColor(-1); textSize = 15f
                    buttonTintList = ColorStateList.valueOf(ACCENT)
                    setOnCheckedChangeListener { _, on -> if (on) enabled += id else enabled -= id }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                fun move(delta: Int, icon: Float, label: String) = ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_expand_more); rotation = icon; background = null
                    imageTintList = ColorStateList.valueOf(-1); contentDescription = "$label ${tile.label}"
                    val target = index + delta
                    isEnabled = target in order.indices; alpha = if (isEnabled) 1f else .3f
                    setOnClickListener { order.removeAt(index); order.add(target, id); MotionUi.expand(list); render() }
                }
                row.addView(move(-1, 180f, "Move up"), LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(move(1, 0f, "Move down"), LinearLayout.LayoutParams(dp(40), dp(40)))
                list.addView(row)
            }
        }
        render()
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(TextView(activity).apply {
            text = "+  Add an app action"; textSize = 15f; setTextColor(ACCENT); setPadding(dp(20), dp(12), dp(16), dp(12))
            isClickable = true; isFocusable = true
            setOnClickListener {
                pickAction { tile -> known[tile.id] = tile; order.add(0, tile.id); enabled += tile.id; MotionUi.expand(list); render() }
            }
        })
        container.addView(list)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Edit controls")
            .setMessage("The first $COLLAPSED shown appear in the collapsed row.")
            .setView(object : ScrollView(activity) {
                // Keep the dialog's buttons on screen in landscape: the list scrolls instead.
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val cap = (activity.resources.displayMetrics.heightPixels * .45f).toInt()
                    super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
                }
            }.apply { addView(container) })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ -> prefs.edit().remove("cc_tiles").apply(); layoutGrid() }
            .setPositiveButton("Done") { _, _ ->
                prefs.edit().putString("cc_tiles", order.filter { it in enabled }.joinToString(",")).apply()
                MotionUi.expand(view); layoutGrid()
            }.show()
    }

    /** Every quick action offered by installed apps (AniHome must be the default launcher). */
    private fun pickAction(done: (Tile) -> Unit) {
        val apps = activity.getSystemService(android.content.pm.LauncherApps::class.java)
        if (apps == null || !runCatching { apps.hasShortcutHostPermission() }.getOrDefault(false)) {
            Toast.makeText(activity, "Set AniHome as your Home app to use app actions", Toast.LENGTH_LONG).show(); return
        }
        val pm = activity.packageManager
        val actions = runCatching {
            apps.getShortcuts(android.content.pm.LauncherApps.ShortcutQuery().setQueryFlags(
                android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST),
                android.os.Process.myUserHandle()).orEmpty()
        }.getOrDefault(emptyList()).filter { it.isEnabled && it.`package` != activity.packageName }
            .sortedWith(compareBy({ runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it.`package`, 0)).toString() }.getOrDefault(it.`package`) }, { it.rank }))
        if (actions.isEmpty()) { Toast.makeText(activity, "No app actions available", Toast.LENGTH_SHORT).show(); return }
        val names = actions.map { info ->
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.`package`, 0)).toString() }.getOrDefault(info.`package`)
            "$app — ${info.shortLabel ?: info.longLabel}"
        }
        MaterialAlertDialogBuilder(activity).setTitle("Add an app action")
            .setItems(names.toTypedArray()) { _, which -> actionTile("action:${actions[which].`package`}|${actions[which].id}")?.let(done) }
            .setNegativeButton("Cancel", null).show()
    }

    // --- actions -------------------------------------------------------------------------
    private fun launch(intent: Intent) {
        runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closePanel() }
            .onFailure { Toast.makeText(activity, "That setting is not available on this device", Toast.LENGTH_SHORT).show() }
    }
    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        runCatching { camera?.setTorchMode(id, on) }.onFailure { Toast.makeText(activity, "The flashlight is in use by another app", Toast.LENGTH_SHORT).show() }
    }
    private fun setDoNotDisturb(on: Boolean) {
        if (HomeNotifications.requestDoNotDisturb(on)) return
        if (notifications?.isNotificationPolicyAccessGranted == true) {
            notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        } else launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
    /**
     * Shell toggles can be accepted yet ignored (dark theme on Android 10, battery saver while
     * charging). Verify the outcome and hand over to Android's own setting instead of failing silently.
     */
    private fun privileged(id: String, on: Boolean, fallback: Intent, state: () -> Boolean) {
        if (!quick.privilegedReady) {
            val gate = app.anihome.launcher.controls.ShizukuGate
            if (gate.ready(activity)) {
                // Shizuku is fine, the helper just isn't bound yet: connect and retry once.
                quick.connectPrivileged()
                handler.postDelayed({ if (attached) { if (quick.privilegedReady) privileged(id, on, fallback, state) else launch(fallback) } }, 900)
                return
            }
            // No Shizuku: open Android's own setting, with at most a small daily hint.
            gate.hint(activity, "This control")
            launch(fallback); return
        }
        quick.systemToggle(id, on) { result ->
            if (!attached) return@systemToggle
            if (result.isNotEmpty()) { Toast.makeText(activity, result, Toast.LENGTH_SHORT).show(); launch(fallback); return@systemToggle }
            handler.postDelayed({
                if (!attached) return@postDelayed
                if (runCatching(state).getOrDefault(on) != on) {
                    if (id == "dark") {
                        prefs.edit().putBoolean("dark_locked", true).apply()
                        Toast.makeText(activity, "This device only lets its own Settings change dark theme, so the control is hidden.", Toast.LENGTH_LONG).show()
                        layoutGrid(); return@postDelayed
                    }
                    val charging = isCharging()
                    Toast.makeText(activity, if (id == "battery_saver" && charging) "Battery saver can't turn on while charging"
                        else "Android didn't apply this here; opening the setting", Toast.LENGTH_SHORT).show()
                    if (id != "battery_saver" || !charging) launch(fallback)
                }
                refresh()
            }, 700)
        }
    }
    /** Airplane on remembers Wi-Fi/Bluetooth; airplane off puts each back the way it was. */
    private fun toggleAirplane() {
        val turningOn = !isAirplane()
        if (turningOn) prefs.edit()
            .putBoolean("airplane_wifi", runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false))
            .putBoolean("airplane_bt", runCatching { bluetooth?.isEnabled == true }.getOrDefault(false)).apply()
        privileged("airplane", turningOn, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), ::isAirplane)
        if (!turningOn && prefs.contains("airplane_wifi")) handler.postDelayed({
            val wantWifi = prefs.getBoolean("airplane_wifi", false); val wantBt = prefs.getBoolean("airplane_bt", false)
            if (runCatching { wifi?.isWifiEnabled }.getOrNull() != wantWifi) quick.systemToggle("wifi_power", wantWifi) { refresh() }
            if (runCatching { bluetooth?.isEnabled }.getOrNull() != wantBt) quick.systemToggle("bt_power", wantBt) { refresh() }
            prefs.edit().remove("airplane_wifi").remove("airplane_bt").apply()
        }, 1500)
    }
    private fun isCharging(): Boolean = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true
    private fun globalAction(action: Int, delay: Long) {
        if (!app.anihome.launcher.controls.ControlsAccess.ensure(activity, "This control", quick::grantSecureSettings)) return
        closePanel()
        // Let the sheet leave the screen first so it is not part of the screenshot.
        handler.postDelayed({ HomeControlsService.perform(action) }, delay)
    }
    private fun setBrightness(progress: Int) {
        if (!Settings.System.canWrite(activity)) return
        val resolver = activity.contentResolver
        runCatching {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            // Perceptual curve: equal slider steps feel like equal brightness steps.
            val value = ((progress / 100f) * (progress / 100f) * 254f).roundToInt() + 1
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(1, 255))
        }
    }

    // --- live state ------------------------------------------------------------------------
    fun refresh() {
        if (!attached) return
        brightnessRow.visibility = if (PanelStyle.brightness(activity)) View.VISIBLE else View.GONE
        volumeRow.visibility = if (PanelStyle.volume(activity)) View.VISIBLE else View.GONE
        brightnessPill.visibility = brightnessRow.visibility; volumePill.visibility = volumeRow.visibility
        val section = quick.section
        tileViews.forEach { tv ->
            val tile = tv.tile; val button = tv.button
            val on = !tile.momentary && runCatching { tile.state() }.getOrDefault(false)
            val open = (tile.id == "wifi" && section == "Wi-Fi") || (tile.id == "bluetooth" && section == "Bluetooth")
            val art = tile.artwork?.invoke()
            if (art != null) button.setImageDrawable(art) else button.setImageResource(tile.icon())
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(if (on) ACCENT else SURFACE_HIGH)
                if (open) setStroke(dp(2), -1)
            }
            button.imageTintList = if (tile.artwork != null) null else ColorStateList.valueOf(if (on) ON_ACCENT else -1)
            if (tile.artwork != null) { button.scaleType = ImageView.ScaleType.FIT_CENTER; button.setPadding(dp(11), dp(11), dp(11), dp(11)) }
            button.contentDescription = if (tile.momentary) tile.label else "${tile.label}, ${if (on) "on" else "off"}"
            if (tv.pill != null) {
                // Glass tile: Material primary showing through when on; the icon and words follow.
                tv.pill.background = CcGlass.tile(activity, radiusFor(tv.size, 0), on, outlined = open || sheetFor == tile.id)
                button.background = null
                button.imageTintList = if (tile.artwork != null) null else ColorStateList.valueOf(if (on) CcGlass.ON_TEXT else -1)
                tv.name?.setTextColor(if (on) CcGlass.ON_TEXT else -1)
                val second = runCatching { tile.detail?.invoke() }.getOrNull() ?: if (tile.momentary) null else if (on) "On" else "Off"
                tv.detail?.text = second.orEmpty(); tv.detail?.visibility = if (second.isNullOrEmpty()) View.GONE else View.VISIBLE
                tv.detail?.setTextColor(if (on) (CcGlass.ON_TEXT and 0x00FFFFFF) or 0xCC000000.toInt() else 0xffc4c6d0.toInt())
                tv.pill.contentDescription = listOfNotNull(tile.label, second).joinToString(", ")
            }
        }
        val level = runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        if (!brightness.isPressed) brightness.progress = (sqrt(((level - 1).coerceAtLeast(0)) / 254f) * 100).roundToInt()
        if (!volume.isPressed) volume.progress = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        if (!brightnessPill.isPressed) brightnessPill.value = brightness.progress
        if (!volumePill.isPressed) volumePill.value = volume.progress
        val batteryState = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val percent = batteryState?.let { (it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100) / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
        battery.text = if (percent == null) "" else "$percent%" + if (isCharging()) " · Charging" else ""
        renderMedia()
    }

    private fun renderMedia() {
        val controller = media
        val metadata = controller?.metadata
        val state = controller?.playbackState?.state
        val active = controller != null && metadata != null && state != null && state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED
        if (!active) {
            gridTitle?.text = "Not playing"; gridArtist?.text = ""; gridArt?.setImageResource(R.drawable.ic_cc_music); gridPlay?.setImageResource(R.drawable.ic_cc_play)
        }
        if (!active || !PanelStyle.media(activity) || gridMode) { mediaCard.visibility = View.GONE; if (!active) return }
        if (!gridMode) mediaCard.visibility = View.VISIBLE
        mediaTitle.text = metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Now playing"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val appLabel = runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(controller!!.packageName, 0)).toString() }.getOrNull()
        mediaArtist.text = listOfNotNull(artist, appLabel).distinct().joinToString(" · ")
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) mediaArt.setImageBitmap(art) else {
            val icon = app.anihome.launcher.data.IconCache.cachedAppIcon(controller!!.packageName, null)
            if (icon != null) mediaArt.setImageBitmap(icon) else mediaArt.setImageResource(R.drawable.ic_cc_music)
        }
        playButton?.setImageResource(if (state == PlaybackState.STATE_PLAYING) R.drawable.ic_cc_pause else R.drawable.ic_cc_play)
        gridTitle?.text = mediaTitle.text; gridArtist?.text = mediaArtist.text; gridArt?.setImageDrawable(mediaArt.drawable)
        gridPlay?.setImageResource(if (state == PlaybackState.STATE_PLAYING) R.drawable.ic_cc_pause else R.drawable.ic_cc_play)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderMedia()
        override fun onSessionDestroyed() { selectSession(emptyList()) }
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> selectSession(controllers.orEmpty()) }
    private fun selectSession(controllers: List<MediaController>) {
        val next = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        if (next?.sessionToken != media?.sessionToken) {
            media?.unregisterCallback(mediaCallback)
            media = next
            next?.registerCallback(mediaCallback, handler)
        }
        renderMedia()
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == torchId) { torchOn = enabled; refresh() } }
    }
    private val observer = object : ContentObserver(handler) { override fun onChange(selfChange: Boolean) = refresh() }
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = refresh() }

    fun attach() {
        if (attached) return
        attached = true
        runCatching { camera?.registerTorchCallback(torchCallback, handler) }
        listOf(Settings.System.CONTENT_URI, Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON))
            .forEach { runCatching { activity.contentResolver.registerContentObserver(it, true, observer) } }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION); addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        runCatching {
            val manager = activity.getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(activity, HomeNotifications::class.java)
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
            sessions = manager
            selectSession(manager.getActiveSessions(listener))
        } // Without notification access there is no media card; everything else still works.
        refresh()
    }

    fun detach() {
        if (!attached) return
        attached = false
        sheet.visibility = View.GONE; sheet.removeAllViews(); sheetFor = null
        handler.removeCallbacksAndMessages(null)
        runCatching { camera?.unregisterTorchCallback(torchCallback) }
        runCatching { activity.contentResolver.unregisterContentObserver(observer) }
        runCatching { activity.unregisterReceiver(receiver) }
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        media?.unregisterCallback(mediaCallback); media = null; sessions = null
    }

    companion object {
        const val ACCENT = 0xff8ab4ff.toInt()
        const val ON_ACCENT = 0xff0b1d3a.toInt()
        // Frosted: translucent white over the panel's tinted, blurred background.
        const val SURFACE = 0x1FFFFFFF
        const val SURFACE_HIGH = 0x2BFFFFFF
        const val STROKE = 0x14FFFFFF
        const val MUTED = 0xffb3b3b3.toInt()
        const val TILE = 48
        const val COLLAPSED = 5
        // Must be static: build() runs from init, before later instance properties exist.
        private val defaultOrder = listOf("wifi", "bluetooth", "rotation", "flashlight", "media", "slider_brightness", "slider_volume", "dnd", "location", "dark", "night_light", "sound_mode", "battery_saver", "airplane", "mobile_data", "hotspot", "caffeine", "screenshot", "screen_record", "lock", "settings")
    }
}
