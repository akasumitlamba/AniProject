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
    override fun onDestroy() {
        overlay?.destroy(); overlay = null
        volume?.stop(); volume = null
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: HomeControlsService? = null
        fun connected() = current != null
        fun refreshOverlays() { current?.let { service -> service.overlay?.refresh() } }
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
            if (action !in setOf(GLOBAL_ACTION_RECENTS, GLOBAL_ACTION_LOCK_SCREEN, GLOBAL_ACTION_TAKE_SCREENSHOT)) return false
            if ((action == GLOBAL_ACTION_LOCK_SCREEN || action == GLOBAL_ACTION_TAKE_SCREENSHOT) && Build.VERSION.SDK_INT < 28) return false
            return runCatching { current?.performGlobalAction(action) == true }.getOrDefault(false)
        }
    }
}
