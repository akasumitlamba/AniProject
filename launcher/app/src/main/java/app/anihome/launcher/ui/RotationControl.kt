package app.anihome.launcher.ui

import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

/** Android owns rotation. AniHome never forces an app/display angle independently. */
internal object RotationControl {
    @Suppress("DEPRECATION")
    fun currentAngle(activity: AppCompatActivity): Int = activity.windowManager.defaultDisplay.rotation
    fun mode(activity: AppCompatActivity): String =
        if (Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1) "Auto" else "Fixed"
    fun applyHome(activity: AppCompatActivity) {
        // One-time migration removes the old app-only override, including saved angles.
        val prefs = activity.getSharedPreferences("anihome", 0)
        if (prefs.contains("rotation_mode") || prefs.contains("rotation_angle"))
            prefs.edit().remove("rotation_mode").remove("rotation_angle").apply()
        if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
    fun setAutomatic(activity: AppCompatActivity, automatic: Boolean): Boolean = runCatching {
        check(Settings.System.canWrite(activity))
        // Capture the visible display angle before disabling the sensor.
        if (!automatic) check(Settings.System.putInt(activity.contentResolver, Settings.System.USER_ROTATION, currentAngle(activity)))
        check(Settings.System.putInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (automatic) 1 else 0))
        applyHome(activity)
        true
    }.getOrDefault(false)
}
