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
    /** Only an explicit toggle writes Android settings; lifecycle entry is strictly read-only. */
    fun setAutomatic(activity: AppCompatActivity, automatic: Boolean): Boolean {
        if (!Settings.System.canWrite(activity)) return false
        val resolver = activity.contentResolver
        val oldAuto = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        if ((oldAuto == 1) == automatic) return true
        val oldAngle = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
        return runCatching {
            if (!automatic) check(Settings.System.putInt(resolver, Settings.System.USER_ROTATION, currentAngle(activity)))
            check(Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (automatic) 1 else 0))
            true
        }.getOrElse {
            // A rejected write must not leave half of a rotation change applied.
            runCatching { Settings.System.putInt(resolver, Settings.System.USER_ROTATION, oldAngle) }
            runCatching { Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, oldAuto) }
            false
        }
    }
}
