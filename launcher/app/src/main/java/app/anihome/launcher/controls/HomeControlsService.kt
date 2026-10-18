package app.anihome.launcher.controls

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/** Only explicit Home buttons invoke global system actions. No windows, events or input are read. */
class HomeControlsService : AccessibilityService() {
    override fun onServiceConnected() { current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

    companion object {
        @Volatile private var current: HomeControlsService? = null
        fun perform(action: Int): Boolean {
            if (action !in setOf(GLOBAL_ACTION_RECENTS, GLOBAL_ACTION_LOCK_SCREEN)) return false
            if (action == GLOBAL_ACTION_LOCK_SCREEN && Build.VERSION.SDK_INT < 28) return false
            return runCatching { current?.performGlobalAction(action) == true }.getOrDefault(false)
        }
    }
}
