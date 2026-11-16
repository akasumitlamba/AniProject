package app.anihome.launcher.controls

import android.accessibilityservice.AccessibilityService
import android.content.Context

/** Android's own notification shade, for when AniHome's panel is switched off. */
internal object SystemShade {
    @android.annotation.SuppressLint("WrongConstant")
    fun expand(context: Context): Boolean {
        if (HomeControlsService.perform(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) return true
        // Without AniHome controls: the status bar service (EXPAND_STATUS_BAR, a normal permission).
        return runCatching {
            val service = context.getSystemService("statusbar")
            service.javaClass.getMethod("expandNotificationsPanel").invoke(service); true
        }.getOrDefault(false)
    }
}
