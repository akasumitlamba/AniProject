package app.anihome.launcher.controls

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Only explicit Home buttons invoke global system actions. No windows, events or input are read.
 * It also hosts the optional AniHome status bar / edge panel overlays ([SystemBarOverlay]).
 */
class HomeControlsService : AccessibilityService() {
    private var overlay: SystemBarOverlay? = null
    private var volume: VolumePanel? = null
    override fun onServiceConnected() {
        current = this
        SystemBar.watch(application)
        overlay = SystemBarOverlay(this).also { it.refresh() }
        volume = VolumePanel(this).also { it.start() }
        syncRotation()
        // Key filtering must also be requested at runtime for Android to route keys here.
        runCatching { serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS } }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    /** Only volume keys are acted on (and only when AniHome's volume panel is turned on). */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean = volume?.onKey(event) ?: false
    override fun onInterrupt() = Unit
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.refresh() // Edge zones follow the new screen size and orientation.
        volume?.dismiss() // Re-anchors on the next key press in the new orientation.
    }
    /**
     * On the AniHome tablet a locked orientation is held by an overlay; with Android's buttons the
     * gesture service that normally holds it isn't running, so AniHome controls holds it instead
     * (without it the tablet snaps back to landscape).
     */
    private var rotation: app.anihome.launcher.nav.RotationLock? = null
    private val displays = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) { rotation?.displayChanged() }
    }
    fun syncRotation() {
        val wanted = app.anihome.launcher.nav.NavigationService.supported() && !app.anihome.launcher.nav.NavigationMode.gesturesOn(this)
        if (wanted && rotation == null) {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            rotation = app.anihome.launcher.nav.RotationLock(this, getSystemService(android.view.WindowManager::class.java), handler)
            runCatching { getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displays, handler) }
        } else if (!wanted && rotation != null) {
            runCatching { getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displays) }
            rotation?.close(); rotation = null
        }
    }
    override fun onDestroy() {
        runCatching { getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displays) }
        rotation?.close(); rotation = null
        overlay?.destroy(); overlay = null
        volume?.stop(); volume = null
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: HomeControlsService? = null
        fun connected() = current != null
        fun refreshOverlays() { current?.let { service -> service.overlay?.refresh() } }
        /** Gesture navigation was turned on or off: hand the rotation lock over. */
        fun syncRotation() { current?.let { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ it.syncRotation() }, 800) } }
        fun updateOverlayVisibility() { current?.overlay?.updateVisibility() }
        /** Keep-awake: a tiny overlay that asks Android to keep the screen on while it exists. */
        private var awakeView: android.view.View? = null
        val awake: Boolean get() = awakeView != null
        fun setKeepAwake(on: Boolean): Boolean {
            val service = current ?: return false
            val windows = service.getSystemService(android.view.WindowManager::class.java)
            if (!on) { awakeView?.let { runCatching { windows.removeView(it) } }; awakeView = null; return true }
            if (awakeView != null) return true
            val view = android.view.View(service)
            val params = android.view.WindowManager.LayoutParams(1, 1, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, android.graphics.PixelFormat.TRANSPARENT)
            return runCatching { windows.addView(view, params); awakeView = view }.isSuccess
        }
        fun perform(action: Int): Boolean {
            if (action !in setOf(GLOBAL_ACTION_RECENTS, GLOBAL_ACTION_LOCK_SCREEN, GLOBAL_ACTION_TAKE_SCREENSHOT, GLOBAL_ACTION_NOTIFICATIONS)) return false
            if ((action == GLOBAL_ACTION_LOCK_SCREEN || action == GLOBAL_ACTION_TAKE_SCREENSHOT) && Build.VERSION.SDK_INT < 28) return false
            return runCatching { current?.performGlobalAction(action) == true }.getOrDefault(false)
        }
    }
}
