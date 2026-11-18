package app.anihome.launcher.nav

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Chooses between AniHome gestures and Android's buttons. Gestures = AniHome's navigation
 * accessibility service is on (it hides the bar while running and restores it when stopped).
 * Switching needs WRITE_SECURE_SETTINGS, granted once through Shizuku.
 */
internal object NavigationMode {
    private val ours = ComponentName("app.anihome.launcher", "app.anihome.launcher.nav.NavigationService")
    /** The old standalone app this replaces; both reading the same swipe would conflict. */
    private val edgeNavigation = ComponentName("local.tablet.navigation", "local.tablet.navigation.EdgeService")

    fun available() = NavigationService.supported()
    fun canSwitch(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun gesturesOn(context: Context) = enabledServices(context).contains(ours)

    private fun enabledServices(context: Context): List<ComponentName> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }.mapNotNull { ComponentName.unflattenFromString(it) }

    private const val NAV_BAR = "sysui_nav_bar"
    private const val REVERSED = "left[.5W],recent[1WC];home;back[1WC],right[.5W]"
    /** Android's buttons with Recent apps on the left and Back on the right. */
    fun buttonsReversed(context: Context) = Settings.Secure.getString(context.contentResolver, NAV_BAR) == REVERSED
    /** Swaps Back and Recent apps in Android's navigation bar; false when Android refused. */
    fun setButtonsReversed(context: Context, reversed: Boolean): Boolean = runCatching {
        Settings.Secure.putString(context.contentResolver, NAV_BAR, if (reversed) REVERSED else null); true
    }.getOrDefault(false)

    /** Returns false when Android refused (permission missing). */
    fun setGestures(context: Context, on: Boolean): Boolean = runCatching {
        val resolver = context.contentResolver
        val services = enabledServices(context).filterNot { it == ours || it == edgeNavigation }.toMutableList()
        if (on) services += ours
        else Settings.Global.putInt(resolver, NavigationService.TCL_NAVIGATION, 0) // Buttons back at once.
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":") { it.flattenToString() })
        if (services.isNotEmpty()) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        app.anihome.launcher.controls.HomeControlsService.syncRotation()
        true
    }.getOrDefault(false)
}
