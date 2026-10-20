package app.anihome.launcher.ui

/** Keep connected entries ahead of recent connections, then paired entries, then names. */
internal object QuickSettingsPolicy {
    data class Device(val id: String, val name: String, val connected: Boolean, val lastConnected: Long, val paired: Boolean)
    fun ordered(devices: List<Device>) = devices.sortedWith(
        compareByDescending<Device> { it.connected }.thenByDescending { it.lastConnected }
            .thenByDescending { it.paired }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )
    fun fixedOrientation(angle: Int, portraitAngle: Int): Int = when (angle) {
        portraitAngle -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        (portraitAngle + 2) % 4 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        (portraitAngle + 1) % 2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    }
    // A tablet can have a natural landscape display; rotation 0 is not always portrait.
    fun lockRotation(landscape: Boolean, currentLandscape: Boolean, displayRotation: Int): Int {
        val naturalLandscape = currentLandscape == (displayRotation % 2 == 0)
        return if (landscape == naturalLandscape) 0 else 1
    }
}
